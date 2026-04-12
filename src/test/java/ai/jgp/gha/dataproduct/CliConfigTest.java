package ai.jgp.gha.dataproduct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void parseFileMode() throws IOException {
        Path file = tempDir.resolve("test.odps.yaml");
        Files.writeString(file, "id: test");

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "my-tenant",
                "--api-key", "secret-key"
        });

        assertEquals(file.toString(), config.getFilePath());
        assertNull(config.getDirPath());
        assertEquals("my-tenant", config.getTenant());
        assertEquals("secret-key", config.getApiKey());
        assertFalse(config.isDirMode());
        assertTrue(config.isProductYaml());
    }

    @Test
    void parseDirMode() {
        CliConfig config = CliConfig.parse(new String[]{
                "--dir", tempDir.toString(),
                "--tenant", "my-tenant",
                "--api-key", "secret-key"
        });

        assertNull(config.getFilePath());
        assertEquals(tempDir.toString(), config.getDirPath());
        assertTrue(config.isDirMode());
        assertFalse(config.isProductYaml());
    }

    @Test
    void parseWithExplicitUrl() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "my-tenant",
                "--api-key", "secret-key",
                "--url", "https://custom.example.com"
        });

        assertEquals("https://custom.example.com", config.getBaseUrl());
    }

    @Test
    void parseUrlStripsTrailingSlash() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "my-tenant",
                "--api-key", "secret-key",
                "--url", "https://custom.example.com/"
        });

        assertEquals("https://custom.example.com", config.getBaseUrl());
    }

    @Test
    void parseBuildsUrlFromTenant() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "acme",
                "--api-key", "key"
        });

        assertEquals("https://acme.zeenea.app", config.getBaseUrl());
    }

    @Test
    void parseDefaultCatalog() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        assertEquals("default", config.getCatalogCode());
    }

    @Test
    void parseExplicitCatalog() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--catalog", "my-catalog"
        });

        assertEquals("my-catalog", config.getCatalogCode());
    }

    @Test
    void parseDebugFlag() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--debug"
        });

        assertTrue(config.isDebug());
    }

    @Test
    void parseDebugDefaultsFalse() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        assertFalse(config.isDebug());
    }

    @Test
    void parseKafkaConfig() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k",
                "--kafka-broker", "broker:9092",
                "--kafka-user", "kafka-user",
                "--kafka-password", "kafka-pass"
        });

        assertEquals("broker:9092", config.getKafkaBroker());
        assertEquals("kafka-user", config.getKafkaUser());
        assertEquals("kafka-pass", config.getKafkaPassword());
        assertTrue(config.isKafkaConfigured());
    }

    @Test
    void parseKafkaNotConfiguredByDefault() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        assertNull(config.getKafkaBroker());
        assertFalse(config.isKafkaConfigured());
    }

    @Test
    void isProductYamlForZipFile() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        assertFalse(config.isProductYaml());
    }

    @Test
    void isProductYamlForYamlFile() throws IOException {
        Path file = tempDir.resolve("product.odps.yaml");
        Files.writeString(file, "id: test");

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        assertTrue(config.isProductYaml());
    }

    @Test
    void isDirModeReturnsFalseInFileMode() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        CliConfig config = CliConfig.parse(new String[]{
                "--file", file.toString(),
                "--tenant", "t",
                "--api-key", "k"
        });

        assertFalse(config.isDirMode());
    }

    // --- Error/validation tests ---

    @Test
    void parseThrowsOnUnknownOption() {
        assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{"--unknown-flag"}));
    }

    @Test
    void parseThrowsWhenNoFileOrDir() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{"--tenant", "t", "--api-key", "k"}));
        assertTrue(ex.getMessage().contains("--file or --dir"));
    }

    @Test
    void parseThrowsWhenBothFileAndDir() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{
                        "--file", file.toString(),
                        "--dir", tempDir.toString(),
                        "--tenant", "t",
                        "--api-key", "k"
                }));
        assertTrue(ex.getMessage().contains("mutually exclusive"));
    }

    @Test
    void parseThrowsWhenMissingTenant() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{
                        "--file", file.toString(),
                        "--api-key", "k"
                }));
        assertTrue(ex.getMessage().contains("--tenant"));
    }

    @Test
    void parseThrowsWhenMissingApiKey() throws IOException {
        Path file = tempDir.resolve("test.zip");
        Files.write(file, new byte[]{1, 2, 3});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{
                        "--file", file.toString(),
                        "--tenant", "t"
                }));
        assertTrue(ex.getMessage().contains("--api-key"));
    }

    @Test
    void parseThrowsWhenFileNotFound() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{
                        "--file", "/tmp/nonexistent-" + System.currentTimeMillis() + ".zip",
                        "--tenant", "t",
                        "--api-key", "k"
                }));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void parseThrowsWhenDirNotFound() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{
                        "--dir", "/tmp/nonexistent-dir-" + System.currentTimeMillis(),
                        "--tenant", "t",
                        "--api-key", "k"
                }));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void parseThrowsWhenFlagMissingValue() {
        assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{"--file"}));
    }

    @Test
    void parseCollectsMultipleErrors() {
        // Missing both file/dir and tenant and api-key
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                CliConfig.parse(new String[]{}));
        String msg = ex.getMessage();
        assertTrue(msg.contains("--file or --dir"));
        assertTrue(msg.contains("--tenant"));
        assertTrue(msg.contains("--api-key"));
    }
}
