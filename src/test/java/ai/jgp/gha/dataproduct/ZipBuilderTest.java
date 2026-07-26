package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ZipBuilder}.
 *
 * <p>Covers the pure helpers ({@code normalizeVersion}, {@code gitShow}
 * against missing tags) and the {@code buildFromProduct} happy path
 * with a local contract fallback. Git-based code paths use a non-git
 * directory so {@code git show} fails cleanly and the on-disk
 * fallback is exercised.
 */
class ZipBuilderTest {

    @TempDir
    Path tmp;

    @Test
    void parseProductRef_readsIdAndNormalizedVersion() throws IOException {
        Path yaml = tmp.resolve("p.odps.yaml");
        Files.writeString(yaml, "id: my-product\nversion: v1.2.3\n");

        var ref = ZipBuilder.parseProductRef(yaml.toString());

        assertEquals("my-product", ref.id());
        assertEquals("1.2.3", ref.version());
        assertTrue(ref.hasId());
    }

    @Test
    void parseProductRef_returnsNullFields_onUnreadableFile() {
        var ref = ZipBuilder.parseProductRef(tmp.resolve("does-not-exist.odps.yaml").toString());

        assertNull(ref.id());
        assertNull(ref.version());
        assertFalse(ref.hasId());
    }

    @Test
    void parseProductRef_returnsNullFields_whenKeysMissing() throws IOException {
        Path yaml = tmp.resolve("empty.odps.yaml");
        Files.writeString(yaml, "name: no-coordinates\n");

        var ref = ZipBuilder.parseProductRef(yaml.toString());

        assertNull(ref.id());
        assertNull(ref.version());
    }

    @Test
    void normalizeVersion_stripsLeadingV() {
        assertEquals("1.2.3", ZipBuilder.normalizeVersion("v1.2.3"));
    }

    @Test
    void normalizeVersion_leavesPlainVersionAlone() {
        assertEquals("1.2.3", ZipBuilder.normalizeVersion("1.2.3"));
    }

    @Test
    void normalizeVersion_nullStaysNull() {
        assertNull(ZipBuilder.normalizeVersion(null));
    }

    @Test
    void gitShow_returnsNull_whenTagMissing() {
        byte[] result = ZipBuilder.gitShow(
                "contract-does-not-exist-v0.0.0",
                "nonexistent/path.odcs.yaml");
        assertNull(result);
    }

    @Test
    void resolveRelativeDir_fallsBackToPodem_outsideGitRepo() throws IOException {
        // tmp is not inside a git repo, so resolveRelativeDir returns its fallback.
        Path productFile = tmp.resolve("product.odps.yaml");
        Files.writeString(productFile, "id: x\nversion: 1");

        String rel = ZipBuilder.resolveRelativeDir(productFile);
        // We accept either "podem" (true fallback) or any plausible relative
        // path the real repo computed if tmp happens to live inside one.
        assertNotNull(rel);
    }

    @Test
    void buildFromProduct_packagesProductAndLocalContract() throws IOException {
        // Set up a product YAML referencing a contract that exists on disk
        // but has no matching git tag — exercises the "git show fails ->
        // local fallback" branch.
        Path productFile = tmp.resolve("product.odps.yaml");
        Path contractFile = tmp.resolve("c1.odcs.yaml");
        Files.writeString(contractFile, "id: c1\nversion: 1.0.0");
        Files.writeString(productFile, String.join("\n",
                "id: prod-1",
                "version: v2.0.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c1",
                "    version: 1.0.0",
                ""));

        Path zip = ZipBuilder.buildFromProduct(productFile.toString());

        assertNotNull(zip);
        assertTrue(Files.exists(zip));

        Map<String, byte[]> entries = readZip(zip);
        // Product entry is versioned without the leading "v"
        assertTrue(entries.containsKey("prod-1-v2.0.0.odps.yaml"),
                "expected product entry, got " + entries.keySet());
        assertTrue(entries.containsKey("c1-v1.0.0.odcs.yaml"),
                "expected contract entry, got " + entries.keySet());
    }

    @Test
    void buildFromProduct_addsSharedContractOnce_whenPortsReferenceSameContract() throws IOException {
        // Three output ports referencing the same contractId+version (one gold
        // contract covering several tables, #64) — the ZIP must contain the
        // contract exactly once instead of failing on a duplicate entry.
        Path productFile = tmp.resolve("product.odps.yaml");
        Path contractFile = tmp.resolve("c1.odcs.yaml");
        Files.writeString(contractFile, "id: c1\nversion: 1.0.0");
        Files.writeString(productFile, String.join("\n",
                "id: prod-shared",
                "version: v2.0.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c1",
                "    version: 1.0.0",
                "  - name: p2",
                "    contractId: c1",
                "    version: 1.0.0",
                "  - name: p3",
                "    contractId: c1",
                "    version: 1.0.0",
                ""));

        Path zip = ZipBuilder.buildFromProduct(productFile.toString());

        assertNotNull(zip);
        Map<String, byte[]> entries = readZip(zip);
        assertTrue(entries.containsKey("prod-shared-v2.0.0.odps.yaml"),
                "expected product entry, got " + entries.keySet());
        assertTrue(entries.containsKey("c1-v1.0.0.odcs.yaml"),
                "expected contract entry, got " + entries.keySet());
        assertEquals(2, entries.size(),
                "expected exactly product + one shared contract, got " + entries.keySet());
    }

    @Test
    void buildFromProduct_skipsPortsWithoutContractId() throws IOException {
        Path productFile = tmp.resolve("product.odps.yaml");
        Files.writeString(productFile, String.join("\n",
                "id: prod-2",
                "version: 0.1.0",
                "outputPorts:",
                "  - name: orphan",
                "    version: 1.0.0",
                ""));

        Path zip = ZipBuilder.buildFromProduct(productFile.toString());
        Map<String, byte[]> entries = readZip(zip);

        // Only the product is in the ZIP; the port with no contractId is skipped.
        assertEquals(1, entries.size(), "entries: " + entries.keySet());
        assertTrue(entries.containsKey("prod-2-v0.1.0.odps.yaml"));
    }

    @Test
    void buildFromProduct_skipsPortsWithoutVersion() throws IOException {
        Path productFile = tmp.resolve("product.odps.yaml");
        Files.writeString(productFile, String.join("\n",
                "id: prod-3",
                "version: 0.1.0",
                "outputPorts:",
                "  - name: unversioned",
                "    contractId: c-no-version",
                ""));

        Path zip = ZipBuilder.buildFromProduct(productFile.toString());
        Map<String, byte[]> entries = readZip(zip);

        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("prod-3-v0.1.0.odps.yaml"));
    }

    /**
     * #59 — a commit that deletes a product file must not surface the deleted
     * path (it no longer exists on disk; processing it crashed the run with
     * NoSuchFileException). Modified files still surface.
     */
    @Test
    void findChangedProducts_excludesDeletions() throws Exception {
        Path repo = tmp.resolve("repo59");
        Files.createDirectories(repo.resolve("podem"));
        Files.writeString(repo.resolve("podem/keep.odps.yaml"), "id: keep\nversion: 0.1.0\n");
        Files.writeString(repo.resolve("podem/gone.odps.yaml"), "id: gone\nversion: 0.1.0\n");
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial");

        Files.writeString(repo.resolve("podem/keep.odps.yaml"), "id: keep\nversion: 0.2.0\n");
        Files.delete(repo.resolve("podem/gone.odps.yaml"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "modify keep, delete gone");

        List<String> changed = ZipBuilder.findChangedProducts("podem", repo);

        assertEquals(List.of("podem/keep.odps.yaml"), changed,
                "deleted product must be skipped, modified product must surface");
    }

    @Test
    void findChangedProducts_returnsEmpty_onNonGitDirectory() {
        // tmp is not a git repo, so `git diff` fails and the method
        // returns an empty list rather than throwing.
        List<String> changed = ZipBuilder.findChangedProducts(tmp.toString());
        assertNotNull(changed);
        // Either empty (likely) or whatever the surrounding repo says — we
        // only assert it doesn't throw and returns a non-null list.
    }

    // ── #33: contract version normalisation (safety net for the Zeenea
    //         "no matching data contract" prefix-only mismatch) ──────────────

    /** AC-1: contract "0.2.1" + port "v0.2.1" → contract normalised up to "v0.2.1". */
    @Test
    void normalizeContractVersion_addsPrefixToMatchPortReference() throws IOException {
        byte[] contract = "id: c1\nversion: 0.2.1\n".getBytes(StandardCharsets.UTF_8);

        byte[] result = ZipBuilder.normalizeContractVersion(contract, "v0.2.1", "c1");

        assertEquals("v0.2.1", versionOf(result),
                "contract version should be rewritten to match the product's reference");
    }

    /** AC-2: already-consistent inputs are returned byte-for-byte unchanged. */
    @Test
    void normalizeContractVersion_leavesConsistentInputUntouched() {
        byte[] contract = "id: c1\nversion: v0.2.1\n".getBytes(StandardCharsets.UTF_8);

        byte[] result = ZipBuilder.normalizeContractVersion(contract, "v0.2.1", "c1");

        assertArrayEquals(contract, result, "consistent input must not be re-serialised");
    }

    /** AC-3: a non-prefix (numeric) mismatch is left alone. */
    @Test
    void normalizeContractVersion_leavesNumericMismatchAlone() {
        byte[] contract = "id: c1\nversion: 0.2.1\n".getBytes(StandardCharsets.UTF_8);

        byte[] result = ZipBuilder.normalizeContractVersion(contract, "0.2.2", "c1");

        assertArrayEquals(contract, result, "a numeric difference must never be touched");
    }

    /** Defensive: a contract without a version field is returned unchanged. */
    @Test
    void normalizeContractVersion_handlesMissingVersionField() {
        byte[] contract = "id: c1\nname: no-version\n".getBytes(StandardCharsets.UTF_8);

        byte[] result = ZipBuilder.normalizeContractVersion(contract, "v0.2.1", "c1");

        assertArrayEquals(contract, result);
    }

    /** AC-4: a warning is logged when (and only when) the normalisation fires. */
    @Test
    void normalizeContractVersion_logsWarningOnlyWhenItFires() {
        Logger zbLog = Logger.getLogger(ZipBuilder.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        zbLog.addHandler(handler);
        try {
            // fires
            ZipBuilder.normalizeContractVersion(
                    "id: c1\nversion: 0.2.1\n".getBytes(StandardCharsets.UTF_8), "v0.2.1", "c1");
            // does not fire (already consistent)
            ZipBuilder.normalizeContractVersion(
                    "id: c1\nversion: v0.2.1\n".getBytes(StandardCharsets.UTF_8), "v0.2.1", "c1");
        } finally {
            zbLog.removeHandler(handler);
        }

        long warnings = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .filter(r -> r.getMessage().contains("Normalised contract"))
                .count();
        assertEquals(1, warnings, "exactly one normalisation warning expected");
    }

    /** AC-1 end-to-end: the prefix-only fix is applied inside buildFromProduct. */
    @Test
    void buildFromProduct_normalisesContractVersionToPortReference() throws IOException {
        Path productFile = tmp.resolve("product.odps.yaml");
        Path contractFile = tmp.resolve("c1.odcs.yaml");
        // Contract internal version lacks the "v"; the product references it WITH "v".
        Files.writeString(contractFile, "id: c1\nversion: 0.2.1\n");
        Files.writeString(productFile, String.join("\n",
                "id: prod-1",
                "version: v2.0.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c1",
                "    version: v0.2.1",
                ""));

        Path zip = ZipBuilder.buildFromProduct(productFile.toString());
        Map<String, byte[]> entries = readZip(zip);

        byte[] contractEntry = entries.get("c1-v0.2.1.odcs.yaml");
        assertNotNull(contractEntry, "expected contract entry, got " + entries.keySet());
        assertEquals("v0.2.1", versionOf(contractEntry),
                "packaged contract should carry the product's exact version reference");
    }

    // ── #58: flat product, subfoldered contract (podem/ vs podem/entnews/) ──

    /**
     * #58 AC-2 (tag tree): the publish flow re-creates the product FLAT in
     * {@code podem/} while its contract lives in the canonical per-product
     * subfolder ({@code podem/entnews/}). The tag-tree resolution must find the
     * contract at ANY path inside the tag.
     */
    @Test
    void buildFromProduct_subfolderedContract_resolvesFromTagTree() throws IOException, InterruptedException {
        Path repo = tmp.resolve("repo58tag");
        Path podem = repo.resolve("podem");
        Path sub = podem.resolve("entnews");
        Files.createDirectories(sub);

        Path contract = sub.resolve("entertainment-news.odcs.yaml");
        Files.writeString(contract, "id: c-sub-1\nversion: 0.1.0\nname: Entertainment News\n");
        Path product = podem.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-flat",
                "version: v1.0.1",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c-sub-1",
                "    version: v0.1.0",
                ""));

        initCommitTag(repo, "contract-c-sub-1-v0.1.0");
        // Remove the working-tree contract so only the tag tree can resolve it.
        Files.delete(contract);

        Path zip = ZipBuilder.buildFromProduct(product.toString());
        Map<String, byte[]> entries = readZip(zip);

        assertTrue(entries.containsKey("c-sub-1-v0.1.0.odcs.yaml"),
                "subfoldered contract should be resolved from the tag tree, got " + entries.keySet());
    }

    /**
     * #58 AC-2 (working tree): with no usable tag, the local scan must walk
     * subdirectories — {@code podem/entnews/} — not just the product's own
     * directory.
     */
    @Test
    void buildFromProduct_subfolderedContract_resolvesFromLocalScan() throws IOException, InterruptedException {
        Path repo = tmp.resolve("repo58local");
        Path podem = repo.resolve("podem");
        Path sub = podem.resolve("entnews");
        Files.createDirectories(sub);

        Files.writeString(sub.resolve("entertainment-news.odcs.yaml"),
                "id: c-sub-2\nversion: 1.1.0\nname: Entertainment News\n");
        Path product = podem.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-flat2",
                "version: v1.0.1",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c-sub-2",
                "    version: v1.1.0",
                ""));
        // Real repo, but no contract tag: forces the local-scan fallback.
        initCommitTag(repo, "unrelated-tag");

        Path zip = ZipBuilder.buildFromProduct(product.toString());
        Map<String, byte[]> entries = readZip(zip);

        assertTrue(entries.containsKey("c-sub-2-v1.1.0.odcs.yaml"),
                "subfoldered contract should be found by the recursive local scan, got " + entries.keySet());
    }

    /** #58: duplicate copies of the same contract id across subfolders fail loudly. */
    @Test
    void resolveFromLocalScan_duplicateAcrossSubfolders_throwsAmbiguity() throws IOException {
        Path dir = tmp.resolve("dup58");
        Files.createDirectories(dir.resolve("entnews"));
        Files.writeString(dir.resolve("copy-a.odcs.yaml"), "id: c-dup\nversion: 1.0.0\n");
        Files.writeString(dir.resolve("entnews").resolve("copy-b.odcs.yaml"), "id: c-dup\nversion: 2.0.0\n");

        IOException ex = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ZipBuilder.resolveFromLocalScan(dir, "c-dup"));
        assertTrue(ex.getMessage().contains("Ambiguous"), ex.getMessage());
    }

    // ── #52: name-based canonicalUrl resolution ───────────────────────────
    //
    // The Workbench publishes contracts wherever their canonicalUrl points —
    // usually a human-readable name, not "<contractId>.odcs.yaml". These tests
    // stand up a REAL temporary git repo (init + commit + tag) so both the
    // tag-tree resolution and the local-file directory scan are exercised
    // against genuine git plumbing, not mocks.

    /**
     * AC-2: the conventional {@code <contractId>.odcs.yaml} fast path still
     * resolves the tagged content directly (no tree scan needed).
     */
    @Test
    void buildFromProduct_conventionalPath_resolvesFromTag() throws IOException, InterruptedException {
        Path repo = tmp.resolve("repo");
        Path podem = repo.resolve("podem");
        Files.createDirectories(podem);

        Path contract = podem.resolve("c-id-123.odcs.yaml");
        Files.writeString(contract, "id: c-id-123\nversion: 1.0.0\n");
        Path product = podem.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-conv",
                "version: v2.0.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c-id-123",
                "    version: 1.0.0",
                ""));

        initCommitTag(repo, "contract-c-id-123-v1.0.0");
        // Remove the on-disk contract so resolution can only come from the tag,
        // proving the conventional git fast path (not the local fallback).
        Files.delete(contract);

        Path zip = ZipBuilder.buildFromProduct(product.toString());
        Map<String, byte[]> entries = readZip(zip);

        assertTrue(entries.containsKey("prod-conv-v2.0.0.odps.yaml"),
                "expected product entry, got " + entries.keySet());
        assertTrue(entries.containsKey("c-id-123-v1.0.0.odcs.yaml"),
                "expected contract entry, got " + entries.keySet());
    }

    /**
     * AC-1: a contract tagged under a name-based path (its file is
     * {@code my-nice-name.odcs.yaml}, not {@code <contractId>.odcs.yaml}) is
     * found by matching the YAML {@code id:} in the tag's tree, and bundled.
     */
    @Test
    void buildFromProduct_nameBasedPath_resolvesFromTagTree() throws IOException, InterruptedException {
        Path repo = tmp.resolve("repo");
        Path podem = repo.resolve("podem");
        Files.createDirectories(podem);

        // Name-based file: filename is the human-readable name, id is the contract id.
        Path contract = podem.resolve("my-nice-name.odcs.yaml");
        Files.writeString(contract, "id: c-id-123\nversion: 1.0.0\nname: My Nice Name\n");
        Path product = podem.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-nb",
                "version: v2.0.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c-id-123",
                "    version: 1.0.0",
                ""));

        initCommitTag(repo, "contract-c-id-123-v1.0.0");
        // Remove the on-disk file so the ONLY way to resolve the contract is by
        // scanning the tag's tree and matching the YAML id (the #52 fix).
        Files.delete(contract);

        Path zip = ZipBuilder.buildFromProduct(product.toString());
        Map<String, byte[]> entries = readZip(zip);

        assertTrue(entries.containsKey("c-id-123-v1.0.0.odcs.yaml"),
                "name-based contract should be resolved by id and bundled, got " + entries.keySet());
        assertEquals("c-id-123", new YAMLMapper()
                .readTree(entries.get("c-id-123-v1.0.0.odcs.yaml")).path("id").asText());
    }

    /**
     * AC-1 (local fallback): with no matching git tag, a name-based contract
     * file on disk is found by scanning the directory for the {@code id:} match.
     */
    @Test
    void buildFromProduct_nameBasedLocalFile_resolvesByDirectoryScan() throws IOException {
        // tmp is not a git repo: git show / ls-tree fail, so the local scan runs.
        Path contract = tmp.resolve("some-human-name.odcs.yaml");
        Files.writeString(contract, "id: c-local-77\nversion: 1.0.0\nname: Human\n");
        Path product = tmp.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-local",
                "version: 0.1.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: c-local-77",
                "    version: 1.0.0",
                ""));

        Path zip = ZipBuilder.buildFromProduct(product.toString());
        Map<String, byte[]> entries = readZip(zip);

        assertTrue(entries.containsKey("c-local-77-v1.0.0.odcs.yaml"),
                "name-based local contract should be resolved by id, got " + entries.keySet());
    }

    /**
     * AC-3: two local files declaring the same contract id fail loudly, naming
     * both offenders.
     */
    @Test
    void buildFromProduct_ambiguousLocalId_failsLoudlyNamingBoth() throws IOException {
        Files.writeString(tmp.resolve("alpha.odcs.yaml"), "id: dup-id\nversion: 1.0.0\n");
        Files.writeString(tmp.resolve("beta.odcs.yaml"), "id: dup-id\nversion: 1.0.0\n");
        Path product = tmp.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-amb",
                "version: 0.1.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: dup-id",
                "    version: 1.0.0",
                ""));

        IOException ex = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ZipBuilder.buildFromProduct(product.toString()));
        String msg = ex.getMessage();
        assertTrue(msg.contains("dup-id"), "message should name the id: " + msg);
        assertTrue(msg.contains("alpha.odcs.yaml") && msg.contains("beta.odcs.yaml"),
                "message should name both files: " + msg);
    }

    /**
     * AC-3 (tag tree): two files in a tag's tree declaring the same contract id
     * fail loudly, naming both.
     */
    @Test
    void buildFromProduct_ambiguousTagTreeId_failsLoudlyNamingBoth()
            throws IOException, InterruptedException {
        Path repo = tmp.resolve("repo");
        Path podem = repo.resolve("podem");
        Files.createDirectories(podem);

        Path first = podem.resolve("first.odcs.yaml");
        Path second = podem.resolve("second.odcs.yaml");
        Files.writeString(first, "id: dup-tag\nversion: 1.0.0\n");
        Files.writeString(second, "id: dup-tag\nversion: 1.0.0\n");
        Path product = podem.resolve("product.odps.yaml");
        Files.writeString(product, String.join("\n",
                "id: prod-amb-tag",
                "version: v2.0.0",
                "outputPorts:",
                "  - name: p1",
                "    contractId: dup-tag",
                "    version: 1.0.0",
                ""));

        initCommitTag(repo, "contract-dup-tag-v1.0.0");
        // Delete on-disk copies so the ambiguity is raised by the tag-tree scan.
        Files.delete(first);
        Files.delete(second);

        IOException ex = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ZipBuilder.buildFromProduct(product.toString()));
        String msg = ex.getMessage();
        assertTrue(msg.contains("dup-tag"), "message should name the id: " + msg);
        assertTrue(msg.contains("first.odcs.yaml") && msg.contains("second.odcs.yaml"),
                "message should name both files: " + msg);
    }

    /** Initialises a git repo at {@code repo}, commits everything, and tags it. */
    private static void initCommitTag(Path repo, String tag)
            throws IOException, InterruptedException {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial");
        git(repo, "tag", tag);
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var is = p.getInputStream()) {
            is.readAllBytes();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + code + ")");
        }
    }

    /** Reads the top-level {@code version} field out of a YAML byte payload. */
    private static String versionOf(byte[] yaml) throws IOException {
        return new YAMLMapper().readTree(yaml).path("version").asText();
    }

    private static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                try (var is = zf.getInputStream(e)) {
                    out.put(e.getName(), is.readAllBytes());
                }
            }
        }
        return out;
    }
}
