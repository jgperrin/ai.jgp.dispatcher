package ai.jgp.gha.dataproduct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizeVersion_stripsVPrefix() {
        assertEquals("1.0.0", ZipBuilder.normalizeVersion("v1.0.0"));
    }

    @Test
    void normalizeVersion_noChangeWithoutPrefix() {
        assertEquals("1.0.0", ZipBuilder.normalizeVersion("1.0.0"));
    }

    @Test
    void normalizeVersion_handlesNull() {
        assertNull(ZipBuilder.normalizeVersion(null));
    }

    @Test
    void normalizeVersion_handlesJustV() {
        assertEquals("", ZipBuilder.normalizeVersion("v"));
    }

    @Test
    void normalizeVersion_preservesNonVPrefix() {
        assertEquals("release-1.0", ZipBuilder.normalizeVersion("release-1.0"));
    }

    @Test
    void buildFromProduct_createsZipWithProduct() throws IOException {
        String productYaml = """
                id: my-product
                version: "1.0.0"
                name: My Product
                """;
        Path productFile = tempDir.resolve("my-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        assertNotNull(zipFile);
        assertTrue(Files.exists(zipFile));
        assertTrue(Files.size(zipFile) > 0);

        Map<String, String> entries = readZipEntries(zipFile);
        assertTrue(entries.containsKey("my-product-v1.0.0.odps.yaml"));
        assertTrue(entries.get("my-product-v1.0.0.odps.yaml").contains("id: my-product"));
    }

    @Test
    void buildFromProduct_handlesVersionWithVPrefix() throws IOException {
        String productYaml = """
                id: my-product
                version: "v2.1.0"
                name: My Product
                """;
        Path productFile = tempDir.resolve("my-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        // normalizeVersion strips "v", so entry should be "my-product-v2.1.0.odps.yaml"
        assertTrue(entries.containsKey("my-product-v2.1.0.odps.yaml"));
    }

    @Test
    void buildFromProduct_includesContractFromLocalFile() throws IOException {
        // Create a contract file in the same directory
        String contractYaml = """
                id: contract-abc
                version: "v1.0.0"
                kind: DataContract
                """;
        Files.writeString(tempDir.resolve("contract-abc.odcs.yaml"), contractYaml);

        // Product referencing the contract
        String productYaml = """
                id: my-product
                version: "1.0.0"
                outputPorts:
                  - name: "port1"
                    contractId: "contract-abc"
                    version: "1.0.0"
                """;
        Path productFile = tempDir.resolve("my-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        assertTrue(entries.containsKey("my-product-v1.0.0.odps.yaml"));
        // Contract should be included via local file fallback (no git tag in test env)
        assertTrue(entries.containsKey("contract-abc-v1.0.0.odcs.yaml"));
    }

    @Test
    void buildFromProduct_skipsPortWithoutContractId() throws IOException {
        String productYaml = """
                id: my-product
                version: "1.0.0"
                outputPorts:
                  - name: "port-no-contract"
                    version: "1.0.0"
                """;
        Path productFile = tempDir.resolve("my-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        // Only the product itself, no contract entries
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("my-product-v1.0.0.odps.yaml"));
    }

    @Test
    void buildFromProduct_skipsPortWithoutVersion() throws IOException {
        String productYaml = """
                id: my-product
                version: "1.0.0"
                outputPorts:
                  - name: "port-no-version"
                    contractId: "contract-abc"
                """;
        Path productFile = tempDir.resolve("my-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        assertEquals(1, entries.size());
    }

    @Test
    void buildFromProduct_handlesNoOutputPorts() throws IOException {
        String productYaml = """
                id: simple-product
                version: "1.0.0"
                name: Simple Product
                """;
        Path productFile = tempDir.resolve("simple.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("simple-product-v1.0.0.odps.yaml"));
    }

    @Test
    void buildFromProduct_fixesMissingVPrefixInContract() throws IOException {
        // Contract without v prefix in version
        String contractYaml = """
                id: contract-fix
                version: "1.0.0"
                kind: DataContract
                """;
        Files.writeString(tempDir.resolve("contract-fix.odcs.yaml"), contractYaml);

        String productYaml = """
                id: my-product
                version: "1.0.0"
                outputPorts:
                  - name: "port1"
                    contractId: "contract-fix"
                    version: "1.0.0"
                """;
        Path productFile = tempDir.resolve("my-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        String contractContent = entries.get("contract-fix-v1.0.0.odcs.yaml");
        assertNotNull(contractContent);
        // The version fix should add "v" prefix
        assertTrue(contractContent.contains("version: \"v1.0.0\""));
    }

    @Test
    void buildFromProduct_multipleOutputPorts() throws IOException {
        Files.writeString(tempDir.resolve("contract-a.odcs.yaml"), "id: contract-a\nversion: \"v1.0.0\"");
        Files.writeString(tempDir.resolve("contract-b.odcs.yaml"), "id: contract-b\nversion: \"v2.0.0\"");

        String productYaml = """
                id: multi-product
                version: "1.0.0"
                outputPorts:
                  - name: "port-a"
                    contractId: "contract-a"
                    version: "1.0.0"
                  - name: "port-b"
                    contractId: "contract-b"
                    version: "2.0.0"
                """;
        Path productFile = tempDir.resolve("multi-product.odps.yaml");
        Files.writeString(productFile, productYaml);

        Path zipFile = ZipBuilder.buildFromProduct(productFile.toString());

        Map<String, String> entries = readZipEntries(zipFile);
        assertEquals(3, entries.size());
        assertTrue(entries.containsKey("multi-product-v1.0.0.odps.yaml"));
        assertTrue(entries.containsKey("contract-a-v1.0.0.odcs.yaml"));
        assertTrue(entries.containsKey("contract-b-v2.0.0.odcs.yaml"));
    }

    @Test
    void gitShow_returnsNullForNonexistentTag() {
        byte[] result = ZipBuilder.gitShow("nonexistent-tag-xyz", "nonexistent-file.yaml");
        assertNull(result);
    }

    @Test
    void findChangedProducts_returnsEmptyForNonGitDir() {
        var result = ZipBuilder.findChangedProducts("/tmp/nonexistent-dir-" + System.currentTimeMillis());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    private Map<String, String> readZipEntries(Path zipFile) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
