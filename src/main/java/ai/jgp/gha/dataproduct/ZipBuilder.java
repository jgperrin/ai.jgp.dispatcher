package ai.jgp.gha.dataproduct;

import ai.jgp.gha.dataproduct.model.ProductRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a versioned ZIP from a product YAML file and its referenced
 * contract files. The ZIP contains:
 * <ul>
 *   <li>{productId}-v{productVersion}.odps.yaml</li>
 *   <li>{contractId}-v{portVersion}.odcs.yaml for each output port</li>
 * </ul>
 * Contract content is retrieved at the correct version using git tags
 * (format: contract-{contractId}-v{version}, as created by the bitol
 * service's v3 enhanced publish).
 */
public class ZipBuilder {

    private static final Logger log = Logger.getLogger(ZipBuilder.class.getName());

    /**
     * Builds a ZIP from a product YAML file. Parses the product to find
     * referenced contracts via outputPorts, retrieves the contract content
     * at the tagged version using git, and packages everything with
     * versioned filenames.
     *
     * @param productYamlPath path to the .odps.yaml file
     * @return path to the temporary ZIP file
     */
    public static Path buildFromProduct(String productYamlPath) throws IOException {
        Path productPath = Path.of(productYamlPath);
        Path dir = productPath.getParent();
        if (dir == null) {
            dir = Path.of(".");
        }

        // Determine the relative path of the contract files within the repo
        // (needed for git show <tag>:<path>)
        String relativeDir = resolveRelativeDir(productPath);

        YAMLMapper yamlMapper = new YAMLMapper();
        String productContent = Files.readString(productPath);
        JsonNode root = yamlMapper.readTree(productContent);

        String productId = root.path("id").asText();
        String productVersion = normalizeVersion(root.path("version").asText());

        System.out.println("Building versioned ZIP for product: " + productId + " v" + productVersion);

        Path zipFile = Files.createTempFile("data-products-", ".zip");
        zipFile.toFile().deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            // 1. Add the product itself
            String productEntry = productId + "-v" + productVersion + ".odps.yaml";
            addEntry(zos, productEntry, productContent.getBytes(StandardCharsets.UTF_8));
            System.out.println("  + " + productEntry);

            // 2. Add contracts from output ports
            JsonNode outputPorts = root.path("outputPorts");
            if (outputPorts.isArray()) {
                for (JsonNode port : outputPorts) {
                    JsonNode contractIdNode = port.path("contractId");
                    JsonNode portVersionNode = port.path("version");
                    JsonNode portNameNode = port.path("name");
                    String contractId = contractIdNode.isMissingNode() ? null : contractIdNode.asText();
                    String portVersion = portVersionNode.isMissingNode() ? null : normalizeVersion(portVersionNode.asText());
                    String portName = portNameNode.isMissingNode() ? "unnamed" : portNameNode.asText();

                    if (contractId == null || contractId.isEmpty()) {
                        log.fine("Skipping output port '" + portName + "' — no contractId");
                        continue;
                    }
                    if (portVersion == null || portVersion.isEmpty()) {
                        log.fine("Skipping output port '" + portName + "' — no version");
                        continue;
                    }

                    // Tag format: contract-<contractId>-v<version>
                    // (matches GitHubService.buildTagName("contract", id, version))
                    String tag = "contract-" + contractId + "-v" + portVersion;
                    String gitPath = relativeDir + "/" + contractId + ".odcs.yaml";

                    // 1. Conventional fast path: the contract lives at
                    //    <dir>/<contractId>.odcs.yaml inside the tag.
                    byte[] contractBytes = gitShow(tag, gitPath, dir);

                    // 2. Name-based canonicalUrl (#52): the Workbench publishes a
                    //    contract wherever its canonicalUrl points — usually a
                    //    human-readable name, not "<contractId>.odcs.yaml". List
                    //    the tag's tree and pick the *.odcs.yaml whose YAML id
                    //    matches the referenced contract id.
                    if (contractBytes == null) {
                        contractBytes = resolveFromTagTree(tag, contractId, dir);
                        if (contractBytes != null) {
                            System.out.println("  (resolved '" + contractId
                                    + "' by matching id in the tag tree — name-based path)");
                        }
                    }

                    // 3. Local-file fallbacks (no usable tag): conventional path
                    //    first, then a directory scan matching the id.
                    if (contractBytes == null) {
                        Path contractPath = dir.resolve(contractId + ".odcs.yaml");
                        if (Files.exists(contractPath)) {
                            contractBytes = Files.readAllBytes(contractPath);
                            System.err.println("  Warning: tag '" + tag + "' not found, "
                                    + "using current file for " + contractId);
                        } else {
                            contractBytes = resolveFromLocalScan(dir, contractId);
                            if (contractBytes != null) {
                                System.err.println("  Warning: tag '" + tag + "' not found, "
                                        + "using name-based local file for " + contractId);
                            } else {
                                // #58 — say precisely which half failed: a
                                // present-but-pathless tag reads very
                                // differently from a missing tag.
                                boolean tagPresent = !gitLsTree(tag, dir).isEmpty();
                                System.err.println("  Warning: contract not found for " + contractId
                                        + (tagPresent
                                            ? " (tag '" + tag + "' exists but carries no"
                                                + " *.odcs.yaml with that id at any path;"
                                                + " no local file either)"
                                            : " (no tag '" + tag + "', no local file)"));
                                continue;
                            }
                        }
                    }

                    // Safety net (#33): make the contract's internal version
                    // match how this output port references it, when they differ
                    // only by the leading "v" — otherwise Zeenea rejects the
                    // upload with "no matching data contract".
                    contractBytes = normalizeContractVersion(
                            contractBytes, portVersionNode.asText(), contractId);

                    String contractEntry = contractId + "-v" + portVersion + ".odcs.yaml";
                    addEntry(zos, contractEntry, contractBytes);
                    System.out.println("  + " + contractEntry + " (from output port '" + portName
                            + "', tag: " + tag + ")");
                }
            }
        }

        return zipFile;
    }

    /**
     * Parses just the identifying coordinates ({@code id} + normalized
     * {@code version}) from a product YAML, for keying the sync-status event
     * (#35). Best-effort: never throws — on any read/parse failure it returns a
     * {@link ProductRef} with null fields, so a status event can still be
     * attempted (and skipped if it has no id).
     *
     * @param productYamlPath path to the .odps.yaml file
     * @return the product's id/version (fields may be null on failure)
     */
    public static ProductRef parseProductRef(String productYamlPath) {
        try {
            YAMLMapper yamlMapper = new YAMLMapper();
            JsonNode root = yamlMapper.readTree(Files.readString(Path.of(productYamlPath)));
            JsonNode idNode = root.path("id");
            JsonNode versionNode = root.path("version");
            String id = idNode.isMissingNode() || idNode.isNull() ? null : idNode.asText();
            String version = versionNode.isMissingNode() || versionNode.isNull()
                    ? null : normalizeVersion(versionNode.asText());
            return new ProductRef(id, version);
        } catch (Exception e) {
            log.fine("Could not parse product ref from " + productYamlPath + ": " + e.getMessage());
            return new ProductRef(null, null);
        }
    }

    /**
     * Retrieves file content at a specific git tag using {@code git show},
     * running git in the current process directory.
     *
     * @param tag  the git tag (e.g. "contract-a7403a03-...-v1.0.4")
     * @param path the file path relative to the repo root
     * @return file content as bytes, or null if the tag/file doesn't exist
     */
    static byte[] gitShow(String tag, String path) {
        return gitShow(tag, path, null);
    }

    /**
     * Retrieves file content at a specific git tag using {@code git show},
     * running git in {@code workDir} (or the process directory when null).
     *
     * @param tag     the git tag (e.g. "contract-a7403a03-...-v1.0.4")
     * @param path    the file path relative to the repo root
     * @param workDir directory to run git in, or null for the process cwd
     * @return file content as bytes, or null if the tag/file doesn't exist
     */
    static byte[] gitShow(String tag, String path, Path workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "show", tag + ":" + path);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();

            byte[] output;
            try (var is = process.getInputStream()) {
                output = is.readAllBytes();
            }

            // Consume stderr
            try (var es = process.getErrorStream();
                 var reader = new BufferedReader(new InputStreamReader(es))) {
                String err = reader.lines().collect(Collectors.joining("\n"));
                if (!err.isEmpty()) {
                    log.fine("git show " + tag + ":" + path + " stderr: " + err);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.length > 0) {
                log.fine("Retrieved " + path + " at tag " + tag + " (" + output.length + " bytes)");
                return output;
            }
            log.fine("git show failed for " + tag + ":" + path + " (exit " + exitCode + ")");
            return null;
        } catch (Exception e) {
            log.fine("git show failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves a contract that was tagged under a name-based path (#52). Lists
     * every file in the tag's tree ({@code git ls-tree -r <tag> --name-only}),
     * and among the {@code *.odcs.yaml} files returns the one whose YAML
     * {@code id:} equals {@code contractId}.
     *
     * <p>The conventional {@code <contractId>.odcs.yaml} fast path is tried by
     * the caller first; this is the fallback for contracts published wherever
     * their {@code canonicalUrl} points (usually a human-readable name).
     *
     * @param tag        the contract tag (e.g. {@code contract-<id>-v1.0.0})
     * @param contractId the referenced contract id to match
     * @param workDir    directory to run git in (inside the repo), or null
     * @return the matching contract bytes, or null if the tag or a match is absent
     * @throws IOException if two files in the tag tree declare the same id
     */
    static byte[] resolveFromTagTree(String tag, String contractId, Path workDir)
            throws IOException {
        List<String> paths = gitLsTree(tag, workDir);
        String matchPath = null;
        byte[] matchBytes = null;
        for (String path : paths) {
            if (!path.endsWith(".odcs.yaml")) {
                continue;
            }
            byte[] bytes = gitShow(tag, path, workDir);
            if (bytes == null || !contractId.equals(yamlId(bytes))) {
                continue;
            }
            if (matchPath != null) {
                throw new IOException("Ambiguous contract id '" + contractId
                        + "' at tag '" + tag + "': matched both '" + matchPath
                        + "' and '" + path + "' — cannot decide which to bundle");
            }
            matchPath = path;
            matchBytes = bytes;
        }
        return matchBytes;
    }

    /**
     * Resolves a name-based contract file on disk (#52) when no usable git tag
     * exists. Scans {@code dir} for {@code *.odcs.yaml} files and returns the one
     * whose YAML {@code id:} equals {@code contractId}.
     *
     * @param dir        the directory holding the product and its contracts
     * @param contractId the referenced contract id to match
     * @return the matching contract bytes, or null if no file matches
     * @throws IOException if two files in the directory declare the same id
     */
    static byte[] resolveFromLocalScan(Path dir, String contractId) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        Path matchPath = null;
        byte[] matchBytes = null;
        // Recursive (#58): a product published flat can reference a contract
        // kept in a per-product subfolder (podem/ vs podem/entnews/), so the
        // scan walks the whole directory tree, not just its top level.
        try (var stream = Files.walk(dir)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".odcs.yaml"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path p : candidates) {
                byte[] bytes = Files.readAllBytes(p);
                if (!contractId.equals(yamlId(bytes))) {
                    continue;
                }
                if (matchPath != null) {
                    throw new IOException("Ambiguous contract id '" + contractId
                            + "' in '" + dir + "': matched both '"
                            + dir.relativize(matchPath) + "' and '" + dir.relativize(p)
                            + "' — cannot decide which to bundle");
                }
                matchPath = p;
                matchBytes = bytes;
            }
        }
        return matchBytes;
    }

    /**
     * Lists the files in a tag's tree via {@code git ls-tree -r <tag> --name-only}.
     *
     * @param tag     the git tag
     * @param workDir directory to run git in, or null for the process cwd
     * @return the file paths in the tag (empty if the tag is missing)
     */
    static List<String> gitLsTree(String tag, Path workDir) {
        List<String> out = new ArrayList<>();
        try {
            // --full-tree makes ls-tree operate from the repo root and emit
            // repo-root-relative paths regardless of the working directory, so
            // the paths line up with what `git show <tag>:<path>` expects.
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "ls-tree", "-r", "--full-tree", tag, "--name-only");
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        out.add(line);
                    }
                }
            }
            try (var es = process.getErrorStream();
                 var reader = new BufferedReader(new InputStreamReader(es))) {
                reader.lines().collect(Collectors.joining("\n"));
            }
            process.waitFor();
        } catch (Exception e) {
            log.fine("git ls-tree failed for " + tag + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * Extracts the top-level {@code id} field from a YAML payload, or null if it
     * cannot be parsed.
     */
    private static String yamlId(byte[] yaml) {
        try {
            JsonNode node = new YAMLMapper().readTree(yaml);
            JsonNode id = node == null ? null : node.get("id");
            return id == null || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves the directory of the product file relative to the git repo root.
     * Uses {@code git rev-parse --show-toplevel} to find the repo root.
     */
    static String resolveRelativeDir(Path productPath) {
        try {
            Path absDir = productPath.toAbsolutePath().getParent();

            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
            pb.directory(absDir.toFile());
            Process process = pb.start();
            String repoRoot;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                repoRoot = reader.readLine();
            }
            process.waitFor();

            if (repoRoot != null) {
                // Canonicalise both sides before relativising: git reports the
                // real path (e.g. /private/var/... on macOS) while absDir may be
                // a symlinked alias (/var/...). Without this they share no common
                // prefix and relativize() yields a bogus ../../ path, so the
                // conventional git-tag fast path misses. Best-effort — fall back
                // to the raw paths if either cannot be canonicalised.
                Path root = toReal(Path.of(repoRoot));
                Path leaf = toReal(absDir);
                Path relativePath = root.relativize(leaf);
                String rel = relativePath.toString();
                log.fine("Repo root: " + repoRoot + ", relative dir: " + rel);
                return rel.isEmpty() ? "." : rel;
            }
        } catch (Exception e) {
            log.fine("Failed to resolve git repo root: " + e.getMessage());
        }
        return "podem";
    }

    /** Canonicalises a path, returning it unchanged if the real path can't be read. */
    private static Path toReal(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    private static void addEntry(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /**
     * Finds .odps.yaml product files changed in the last commit within the
     * given directory, using {@code git diff --name-only HEAD~1 HEAD}.
     *
     * <p>Deletions are excluded ({@code --diff-filter=ACMRT}, #59): a deleted
     * product file no longer exists on disk, so processing it can only crash.
     * A belt-and-braces existence check below guards against any other way a
     * listed path can be gone by the time it is read.
     *
     * @param dirPath directory to scan (e.g. "podem")
     * @return list of paths to changed product files (may be empty)
     */
    public static List<String> findChangedProducts(String dirPath) {
        return findChangedProducts(dirPath, null);
    }

    /** Test seam: like {@link #findChangedProducts(String)} but running git in {@code workDir}. */
    static List<String> findChangedProducts(String dirPath, Path workDir) {
        List<String> changed = new ArrayList<>();
        try {
            // Get all changed files under the directory, filter by extension in Java.
            // Using just the directory path avoids glob issues with ProcessBuilder.
            List<String> cmd = List.of(
                    "git", "diff", "--name-only", "--diff-filter=ACMRT",
                    "HEAD~1", "HEAD", "--", dirPath);
            System.out.println("Running: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();

            List<String> allChanged = new ArrayList<>();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        allChanged.add(line);
                    }
                }
            }

            // Consume stderr
            try (var es = process.getErrorStream();
                 var reader = new BufferedReader(new InputStreamReader(es))) {
                String err = reader.lines().collect(Collectors.joining("\n"));
                if (!err.isEmpty()) {
                    System.err.println("git diff stderr: " + err);
                }
            }

            int exitCode = process.waitFor();
            System.out.println("git diff exit code: " + exitCode
                    + ", files changed in " + dirPath + ": " + allChanged.size());

            for (String file : allChanged) {
                System.out.println("  changed: " + file);
                if (!file.endsWith(".odps.yaml")) {
                    continue;
                }
                // #59 — guard for paths gone from the working tree anyway
                // (racy checkout states, odd renames): skip, don't crash.
                Path onDisk = workDir != null ? workDir.resolve(file) : Path.of(file);
                if (!Files.exists(onDisk)) {
                    System.out.println("  deleted — skipped: " + file);
                    continue;
                }
                changed.add(file);
            }

            if (!changed.isEmpty()) {
                System.out.println("Product files to process: " + changed.size());
            }
        } catch (Exception e) {
            System.err.println("git diff failed: " + e.getMessage());
        }
        return changed;
    }

    /**
     * Normalises the contract YAML's top-level {@code version} field so it
     * matches exactly how the product's output port references it, when the two
     * differ <strong>only</strong> by a leading {@code v} prefix (e.g. contract
     * {@code 0.2.1} vs port {@code v0.2.1}). Zeenea matches a product output
     * port to its data contract by this version string, so a prefix-only
     * mismatch causes a "no matching data contract" rejection.
     *
     * <p>This is a conservative safety net (#33): the numeric portion is never
     * changed, and already-consistent or genuinely-different (e.g. {@code 0.2.1}
     * vs {@code 0.2.2}) inputs are returned untouched. The root cause is fixed
     * upstream in {@code ai.jgp.bitol.svc} (#595).
     *
     * @param contractBytes  the contract YAML as stored (may be null)
     * @param portVersionRef the output port's version reference, raw and
     *                       un-stripped (e.g. {@code "v0.2.1"})
     * @param contractId     the contract id, for the log message
     * @return the contract bytes with the {@code version} field rewritten iff a
     *         prefix-only mismatch was found; otherwise the original bytes
     */
    static byte[] normalizeContractVersion(byte[] contractBytes, String portVersionRef,
            String contractId) {
        if (contractBytes == null || portVersionRef == null || portVersionRef.isEmpty()) {
            return contractBytes;
        }
        try {
            YAMLMapper mapper = new YAMLMapper();
            JsonNode root = mapper.readTree(contractBytes);
            if (root == null || !root.has("version")) {
                return contractBytes;
            }
            String contractVersion = root.path("version").asText();
            // Already consistent — nothing to do.
            if (portVersionRef.equals(contractVersion)) {
                return contractBytes;
            }
            // Only act when the difference is purely the leading "v": the
            // numeric portions must be identical, otherwise leave it alone.
            if (!normalizeVersion(portVersionRef).equals(normalizeVersion(contractVersion))) {
                return contractBytes;
            }
            ((ObjectNode) root).put("version", portVersionRef);
            log.warning("Normalised contract '" + contractId + "' version '" + contractVersion
                    + "' -> '" + portVersionRef + "' to match the product output-port reference "
                    + "(prefix-only mismatch; upstream fix tracked in ai.jgp.bitol.svc#595)");
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            // A defensive normalisation must never break packaging — on any
            // parse/serialise failure, fall back to the original bytes.
            log.warning("Contract version normalisation skipped for '" + contractId
                    + "': " + e.getMessage());
            return contractBytes;
        }
    }

    /**
     * Normalizes a version string by stripping a leading "v" if present.
     * The caller adds "v" back in the filename, so we store just the number.
     */
    static String normalizeVersion(String version) {
        if (version != null && version.startsWith("v")) {
            return version.substring(1);
        }
        return version;
    }
}
