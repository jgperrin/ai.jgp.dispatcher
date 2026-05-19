package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CliConfig}.
 *
 * <p>Covers the happy-path argument-parsing branches: file vs dir mode,
 * explicit URL vs tenant-derived URL, trailing-slash stripping, Kafka
 * configuration detection, and the {@code isProductYaml} helper. Error
 * paths in {@code parse} call {@link System#exit(int)} directly, so
 * they are exercised indirectly via the bits of state they don't
 * mutate; full negative-path coverage would require a refactor and is
 * out of scope per the user story.
 */
class CliConfigTest {

    @TempDir
    Path tmp;

    @Test
    void parse_fileMode_withTenantAndApiKey_buildsBaseUrlFromTenant() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "ignored");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        });

        assertEquals(zip.toString(), cfg.getFilePath());
        assertEquals("acme", cfg.getTenant());
        assertEquals("secret", cfg.getApiKey());
        assertEquals("https://acme.zeenea.app", cfg.getBaseUrl());
        assertEquals("default", cfg.getCatalogCode());
        assertFalse(cfg.isDebug());
        assertFalse(cfg.isDirMode());
        assertFalse(cfg.isKafkaConfigured());
        assertFalse(cfg.isProductYaml());
    }

    @Test
    void parse_explicitUrl_overridesTenantAndStripsTrailingSlash() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "ignored");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--url", "https://override.example.com/",
                "--catalog", "prod",
        });

        assertEquals("https://override.example.com", cfg.getBaseUrl());
        assertEquals("prod", cfg.getCatalogCode());
    }

    @Test
    void parse_dirMode_setsDirPathAndDirModeFlag() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("podem"));

        CliConfig cfg = CliConfig.parse(new String[]{
                "--dir", dir.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        });

        assertTrue(cfg.isDirMode());
        assertEquals(dir.toString(), cfg.getDirPath());
    }

    @Test
    void parse_productYaml_isDetected() throws IOException {
        Path yaml = tmp.resolve("product.odps.yaml");
        Files.writeString(yaml, "id: x\nversion: 1");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", yaml.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        });

        assertTrue(cfg.isProductYaml());
    }

    @Test
    void parse_debugFlag_setsDebugTrue() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--debug",
        });

        assertTrue(cfg.isDebug());
    }

    @Test
    void parse_kafkaArgs_areCaptured() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "kafka.example.com:9093",
                "--kafka-user", "kuser",
                "--kafka-password", "kpwd",
        });

        assertTrue(cfg.isKafkaConfigured());
        assertEquals("kafka.example.com:9093", cfg.getKafkaBroker());
        assertEquals("kuser", cfg.getKafkaUser());
        assertEquals("kpwd", cfg.getKafkaPassword());
    }

    @Test
    void isKafkaConfigured_falseWhenBrokerNull() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        });

        assertFalse(cfg.isKafkaConfigured());
        assertNotNull(cfg.getBaseUrl());
    }

    @Test
    void parse_explicitCatalog_overridesDefault() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--catalog", "staging",
        });

        assertEquals("staging", cfg.getCatalogCode());
    }
}
