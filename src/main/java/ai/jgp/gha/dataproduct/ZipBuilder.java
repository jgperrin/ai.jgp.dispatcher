package ai.jgp.gha.dataproduct;

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

                    // Try to retrieve the contract at the tagged version
                    byte[] contractBytes = gitShow(tag, gitPath);
                    if (contractBytes == null) {
                        // Fall back to the current file on disk
                        Path contractPath = dir.resolve(contractId + ".odcs.yaml");
                        if (Files.exists(contractPath)) {
                            contractBytes = Files.readAllBytes(contractPath);
                            System.err.println("  Warning: tag '" + tag + "' not found, "
                                    + "using current file for " + contractId);
                        } else {
                            System.err.println("  Warning: contract not found for " + contractId
                                    + " (no tag '" + tag + "', no local file)");
                            continue;
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
     * Retrieves file content at a specific git tag using {@code git show}.
     *
     * @param tag  the git tag (e.g. "contract-a7403a03-...-v1.0.4")
     * @param path the file path relative to the repo root
     * @return file content as bytes, or null if the tag/file doesn't exist
     */
    static byte[] gitShow(String tag, String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "show", tag + ":" + path);
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
                Path relativePath = Path.of(repoRoot).relativize(absDir);
                String rel = relativePath.toString();
                log.fine("Repo root: " + repoRoot + ", relative dir: " + rel);
                return rel.isEmpty() ? "." : rel;
            }
        } catch (Exception e) {
            log.fine("Failed to resolve git repo root: " + e.getMessage());
        }
        return "podem";
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
     * @param dirPath directory to scan (e.g. "podem")
     * @return list of paths to changed product files (may be empty)
     */
    public static List<String> findChangedProducts(String dirPath) {
        List<String> changed = new ArrayList<>();
        try {
            // Get all changed files under the directory, filter by extension in Java.
            // Using just the directory path avoids glob issues with ProcessBuilder.
            List<String> cmd = List.of(
                    "git", "diff", "--name-only", "HEAD~1", "HEAD", "--", dirPath);
            System.out.println("Running: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
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
                if (file.endsWith(".odps.yaml")) {
                    changed.add(file);
                }
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
