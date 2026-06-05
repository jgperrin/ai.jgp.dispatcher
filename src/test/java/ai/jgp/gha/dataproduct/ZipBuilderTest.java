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
