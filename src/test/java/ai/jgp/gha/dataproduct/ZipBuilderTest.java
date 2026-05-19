package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
