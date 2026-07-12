package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import uk.org.webcompere.systemstubs.SystemStubs;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

/**
 * Unit tests for {@link CliConfig}.
 *
 * <p>Covers happy paths (file vs dir mode, explicit URL vs tenant-derived
 * URL, trailing-slash stripping, Kafka configuration, debug, env-var
 * fallbacks) and negative paths that call {@link System#exit(int)} —
 * those are captured with system-stubs' {@code catchSystemExit}.
 */
@ExtendWith(SystemStubsExtension.class)
class CliConfigTest {

    @SystemStub
    private EnvironmentVariables env;

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
                "--org-id", "3f2b8c1e-9a4d-4e7f-b6a5-1c2d3e4f5a6b",
        });

        assertTrue(cfg.isKafkaConfigured());
        assertEquals("kafka.example.com:9093", cfg.getKafkaBroker());
        assertEquals("kuser", cfg.getKafkaUser());
        assertEquals("kpwd", cfg.getKafkaPassword());
        assertEquals("3f2b8c1e-9a4d-4e7f-b6a5-1c2d3e4f5a6b", cfg.getOrgId());
    }

    @Test
    void parse_kafkaWithoutOrgId_failsClosed_exitsOne() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        int code = SystemStubs.catchSystemExit(() -> CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "kafka.example.com:9093",
        }));

        assertEquals(1, code);
    }

    @Test
    void parse_orgIdEnvFallback_satisfiesKafkaRequirement() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");
        env.set(K.ENV_ORG_ID, "aaaa1111-2222-3333-4444-555566667777");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "kafka.example.com:9093",
        });

        assertEquals("aaaa1111-2222-3333-4444-555566667777", cfg.getOrgId());
    }

    @Test
    void parse_noKafka_orgIdOptional() throws IOException {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        CliConfig cfg = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        });

        assertFalse(cfg.isKafkaConfigured());
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

    @Test
    void parse_envFallbacks_populateAllFields() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");
        env.set(K.ENV_FILE, zip.toString());
        env.set(K.ENV_TENANT, "envTenant");
        env.set(K.ENV_API_KEY, "envKey");
        env.set(K.ENV_CATALOG, "envCat");
        env.set(K.ENV_URL, "https://env.example/");
        env.set(K.ENV_KAFKA_BROKER, "envbroker:9093");
        env.set(K.ENV_KAFKA_USER, "envU");
        env.set(K.ENV_KAFKA_PASSWORD, "envP");
        env.set(K.ENV_ORG_ID, "envOrg");

        CliConfig cfg = CliConfig.parse(new String[]{});

        assertEquals(zip.toString(), cfg.getFilePath());
        assertEquals("envTenant", cfg.getTenant());
        assertEquals("envKey", cfg.getApiKey());
        assertEquals("envCat", cfg.getCatalogCode());
        assertEquals("https://env.example", cfg.getBaseUrl());
        assertEquals("envbroker:9093", cfg.getKafkaBroker());
        assertEquals("envU", cfg.getKafkaUser());
        assertEquals("envP", cfg.getKafkaPassword());
        assertEquals("envOrg", cfg.getOrgId());
    }

    @Test
    void parse_dirEnvFallback_setsDirMode() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("envdir"));
        env.set(K.ENV_DIR, dir.toString());
        env.set(K.ENV_TENANT, "t");
        env.set(K.ENV_API_KEY, "k");

        CliConfig cfg = CliConfig.parse(new String[]{});

        assertTrue(cfg.isDirMode());
        assertEquals(dir.toString(), cfg.getDirPath());
    }

    @Test
    void parse_blankEnvVar_isTreatedAsUnset_andExitsWithError() throws Exception {
        env.set(K.ENV_FILE, "   ");
        env.set(K.ENV_TENANT, "t");
        env.set(K.ENV_API_KEY, "k");

        int code = SystemStubs.catchSystemExit(() -> CliConfig.parse(new String[]{}));
        assertEquals(1, code);
    }

    @Test
    void parse_unknownOption_exitsOne() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{"--bogus"}));
        assertEquals(1, code);
    }

    @Test
    void parse_missingValueForFlag_exitsOne() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{"--tenant"}));
        assertEquals(1, code);
    }

    @Test
    void parse_missingFileAndDir_exitsOne() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{
                        "--tenant", "t", "--api-key", "k"}));
        assertEquals(1, code);
    }

    @Test
    void parse_fileAndDir_mutuallyExclusive_exitsOne() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");
        Path dir = Files.createDirectory(tmp.resolve("d"));

        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{
                        "--file", zip.toString(),
                        "--dir", dir.toString(),
                        "--tenant", "t", "--api-key", "k"}));
        assertEquals(1, code);
    }

    @Test
    void parse_missingTenant_exitsOne() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{
                        "--file", zip.toString(),
                        "--api-key", "k"}));
        assertEquals(1, code);
    }

    @Test
    void parse_missingApiKey_exitsOne() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{
                        "--file", zip.toString(),
                        "--tenant", "t"}));
        assertEquals(1, code);
    }

    @Test
    void parse_nonexistentFile_exitsOne() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{
                        "--file", tmp.resolve("nope.zip").toString(),
                        "--tenant", "t", "--api-key", "k"}));
        assertEquals(1, code);
    }

    @Test
    void parse_nonexistentDir_exitsOne() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{
                        "--dir", tmp.resolve("missing").toString(),
                        "--tenant", "t", "--api-key", "k"}));
        assertEquals(1, code);
    }

    @Test
    void parse_helpFlag_exitsZero() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{"--help"}));
        assertEquals(0, code);
    }

    @Test
    void parse_helpShortFlag_exitsZero() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{"-h"}));
        assertEquals(0, code);
    }

    @Test
    void parse_versionFlag_exitsZero() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{"--version"}));
        assertEquals(0, code);
    }

    @Test
    void parse_versionShortFlag_exitsZero() throws Exception {
        int code = SystemStubs.catchSystemExit(
                () -> CliConfig.parse(new String[]{"-v"}));
        assertEquals(0, code);
    }

}
