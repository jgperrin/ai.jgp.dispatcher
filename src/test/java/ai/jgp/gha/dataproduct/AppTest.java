package ai.jgp.gha.dataproduct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    @TempDir
    Path tempDir;

    @Test
    void processDirectory_returnsZero_whenNoChangedFiles() {
        // An empty temp dir with no git history will produce no changed files
        CliConfig config = CliConfig.parse(new String[]{
                "--dir", tempDir.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        int exitCode = App.processDirectory(config);
        assertEquals(0, exitCode);
    }

    @Test
    void processProduct_returnsFalse_forNonExistentFile() {
        CliConfig config = CliConfig.parse(new String[]{
                "--dir", tempDir.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        // Non-existent product file should fail gracefully
        boolean result = App.processProduct(config, "/tmp/nonexistent-" + System.currentTimeMillis() + ".odps.yaml");
        assertFalse(result);
    }

    @Test
    void processProduct_returnsFalse_forInvalidYaml() throws IOException {
        Path invalidProduct = tempDir.resolve("invalid.odps.yaml");
        Files.writeString(invalidProduct, "not: valid: yaml: [broken");

        CliConfig config = CliConfig.parse(new String[]{
                "--dir", tempDir.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        boolean result = App.processProduct(config, invalidProduct.toString());
        assertFalse(result);
    }

    @Test
    void processSingleFile_returnsFalse_forMissingZip() throws IOException {
        // Create a dummy file to pass CliConfig validation, then delete it
        Path zipFile = tempDir.resolve("test.zip");
        Files.write(zipFile, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", zipFile.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--url", "https://nonexistent.example.com"
        });

        // Upload will fail since the URL is unreachable
        boolean result = App.processSingleFile(config);
        assertFalse(result);
    }

    @Test
    void processSingleFile_returnsFalse_forBadProductYaml() throws IOException {
        Path productFile = tempDir.resolve("broken.odps.yaml");
        Files.writeString(productFile, "not: valid: yaml: [broken");

        CliConfig config = CliConfig.parse(new String[]{
                "--file", productFile.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        boolean result = App.processSingleFile(config);
        assertFalse(result);
    }

    @Test
    void publishZipToKafka_handlesUnreachableBroker() throws IOException {
        Path zipFile = tempDir.resolve("test.zip");
        Files.write(zipFile, new byte[]{0x50, 0x4B, 0x03, 0x04});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", zipFile.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--kafka-broker", "localhost:19999"
        });

        // Should not throw — just prints warnings
        assertDoesNotThrow(() -> App.publishZipToKafka(config, zipFile.toString()));
    }

    @Test
    void publishZipToKafka_handlesNonExistentZipFile() throws IOException {
        Path dummyFile = tempDir.resolve("exists.zip");
        Files.write(dummyFile, new byte[]{1});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", dummyFile.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--kafka-broker", "localhost:19999"
        });

        // Pass a non-existent path for the ZIP
        assertDoesNotThrow(() -> App.publishZipToKafka(config,
                "/tmp/nonexistent-" + System.currentTimeMillis() + ".zip"));
    }

    @Test
    void publishZipToKafka_handlesUnreachableBrokerWithSasl() throws IOException {
        Path zipFile = tempDir.resolve("test-sasl.zip");
        Files.write(zipFile, new byte[]{0x50, 0x4B, 0x03, 0x04});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", zipFile.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--kafka-broker", "localhost:19999",
                "--kafka-user", "myuser",
                "--kafka-password", "mypass"
        });

        // SASL_SSL path — broker unreachable, but constructor should not throw
        assertDoesNotThrow(() -> App.publishZipToKafka(config, zipFile.toString()));
    }

    @Test
    void processProduct_returnsFalse_forValidYamlButUnreachableServer() throws IOException {
        Path productFile = tempDir.resolve("valid.odps.yaml");
        Files.writeString(productFile, """
                id: test-product
                version: "1.0.0"
                name: Test Product
                """);

        CliConfig config = CliConfig.parse(new String[]{
                "--dir", tempDir.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--url", "https://nonexistent.example.com"
        });

        // ZIP builds OK, but Zeenea upload fails (unreachable)
        boolean result = App.processProduct(config, productFile.toString());
        assertFalse(result);
    }

    @Test
    void processSingleFile_returnsFalse_forValidYamlButUnreachableServer() throws IOException {
        Path productFile = tempDir.resolve("good.odps.yaml");
        Files.writeString(productFile, """
                id: good-product
                version: "2.0.0"
                name: Good Product
                """);

        CliConfig config = CliConfig.parse(new String[]{
                "--file", productFile.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--url", "https://nonexistent.example.com"
        });

        // YAML is valid, ZIP builds, but Zeenea upload fails
        boolean result = App.processSingleFile(config);
        assertFalse(result);
    }
}
