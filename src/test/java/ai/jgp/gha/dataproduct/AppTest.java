package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import uk.org.webcompere.systemstubs.SystemStubs;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

/**
 * Unit tests for {@link App#main(String[])}.
 *
 * <p>Exercises the CLI dispatch logic by mocking the construction of
 * {@link ZeeneaClient} and {@link KafkaPublisher} and stubbing the
 * static {@link ZipBuilder} helpers. {@code System.exit} calls are
 * captured via system-stubs so the test JVM keeps running.
 */
@ExtendWith(SystemStubsExtension.class)
class AppTest {

    @TempDir
    Path tmp;

    private String[] baseArgs(String filePath) {
        return new String[]{
                "--file", filePath,
                "--tenant", "acme",
                "--api-key", "secret",
        };
    }

    @Test
    void main_singleZipFile_uploadSucceeds_exitsZero() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(true))) {

            int code = SystemStubs.catchSystemExit(() -> App.main(baseArgs(zip.toString())));

            assertEquals(0, code);
            assertEquals(1, zc.constructed().size());
        }
    }

    @Test
    void main_singleZipFile_uploadFails_exitsOne() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(false))) {

            int code = SystemStubs.catchSystemExit(() -> App.main(baseArgs(zip.toString())));

            assertEquals(1, code);
        }
    }

    @Test
    void main_singleProductYaml_buildsZipAndUploads() throws Exception {
        Path yaml = tmp.resolve("product.odps.yaml");
        Files.writeString(yaml, "id: x");
        Path builtZip = tmp.resolve("built.zip");
        Files.writeString(builtZip, "zip");

        try (MockedStatic<ZipBuilder> zb = mockStatic(ZipBuilder.class);
             MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                     (mock, ctx) -> when(mock.upload()).thenReturn(true))) {
            zb.when(() -> ZipBuilder.buildFromProduct(anyString())).thenReturn(builtZip);

            int code = SystemStubs.catchSystemExit(() -> App.main(baseArgs(yaml.toString())));

            assertEquals(0, code);
            zb.verify(() -> ZipBuilder.buildFromProduct(yaml.toString()));
        }
    }

    @Test
    void main_singleProductYaml_zipBuildFails_exitsOne() throws Exception {
        Path yaml = tmp.resolve("product.odps.yaml");
        Files.writeString(yaml, "id: x");

        try (MockedStatic<ZipBuilder> zb = mockStatic(ZipBuilder.class);
             MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class)) {
            zb.when(() -> ZipBuilder.buildFromProduct(anyString()))
                    .thenThrow(new IOException("boom"));

            int code = SystemStubs.catchSystemExit(() -> App.main(baseArgs(yaml.toString())));

            assertEquals(1, code);
            // ZeeneaClient must not be constructed when the ZIP build fails.
            assertEquals(0, zc.constructed().size());
        }
    }

    @Test
    void main_debugFlag_enablesFineLogging_andExitsZero() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        String[] args = {
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--debug",
        };

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(true))) {

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(0, code);
        }
    }

    @Test
    void main_uploadSucceeds_andKafkaConfigured_publishesZip() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "zipdata");

        String[] args = {
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "127.0.0.1:1",
        };

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(true));
             MockedConstruction<KafkaPublisher> kp = mockConstruction(KafkaPublisher.class,
                     (mock, ctx) -> {
                         when(mock.isConnected()).thenReturn(true);
                         when(mock.publishZip(anyString(), anyString(), any())).thenReturn(true);
                     })) {

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(0, code);
            assertEquals(1, kp.constructed().size());
        }
    }

    @Test
    void main_kafkaPublisherNotConnected_skipsPublishingButExitsZero() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "zipdata");

        String[] args = {
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "127.0.0.1:1",
        };

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(true));
             MockedConstruction<KafkaPublisher> kp = mockConstruction(KafkaPublisher.class,
                     (mock, ctx) -> when(mock.isConnected()).thenReturn(false))) {

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(0, code);
        }
    }

    @Test
    void main_kafkaPublisherThrows_failureIsSwallowed_exitsZero() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "zipdata");

        String[] args = {
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "127.0.0.1:1",
        };

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(true));
             MockedConstruction<KafkaPublisher> kp = mockConstruction(KafkaPublisher.class,
                     (mock, ctx) -> {
                         when(mock.isConnected()).thenReturn(true);
                         when(mock.publishZip(anyString(), anyString(), any()))
                                 .thenThrow(new RuntimeException("kafka down"));
                     })) {

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            // Kafka failures don't fail the overall upload.
            assertEquals(0, code);
        }
    }

    @Test
    void main_uploadFailsWithKafkaConfigured_doesNotPublish_exitsOne() throws Exception {
        Path zip = tmp.resolve("bundle.zip");
        Files.writeString(zip, "x");

        String[] args = {
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--kafka-broker", "127.0.0.1:1",
        };

        try (MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                (mock, ctx) -> when(mock.upload()).thenReturn(false));
             MockedConstruction<KafkaPublisher> kp = mockConstruction(KafkaPublisher.class)) {

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(1, code);
            // Kafka must not be constructed when Zeenea upload failed.
            assertEquals(0, kp.constructed().size());
        }
    }

    @Test
    void main_dirMode_noChanges_exitsZero() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("repo"));

        String[] args = {
                "--dir", dir.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        };

        try (MockedStatic<ZipBuilder> zb = mockStatic(ZipBuilder.class)) {
            zb.when(() -> ZipBuilder.findChangedProducts(anyString())).thenReturn(List.of());

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(0, code);
        }
    }

    @Test
    void main_dirMode_allProductsSucceed_exitsZero() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("repo"));
        Path built = tmp.resolve("built.zip");
        Files.writeString(built, "z");

        String[] args = {
                "--dir", dir.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        };

        try (MockedStatic<ZipBuilder> zb = mockStatic(ZipBuilder.class);
             MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                     (mock, ctx) -> when(mock.upload()).thenReturn(true))) {
            zb.when(() -> ZipBuilder.findChangedProducts(anyString()))
                    .thenReturn(List.of("a.odps.yaml", "b.odps.yaml"));
            zb.when(() -> ZipBuilder.buildFromProduct(anyString())).thenReturn(built);

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(0, code);
            assertEquals(2, zc.constructed().size());
        }
    }

    @Test
    void main_dirMode_someProductsFail_exitsOne() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("repo"));
        Path built = tmp.resolve("built.zip");
        Files.writeString(built, "z");

        String[] args = {
                "--dir", dir.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        };

        AtomicInteger callCount = new AtomicInteger();
        try (MockedStatic<ZipBuilder> zb = mockStatic(ZipBuilder.class);
             MockedConstruction<ZeeneaClient> zc = mockConstruction(ZeeneaClient.class,
                     (mock, ctx) -> when(mock.upload())
                             .thenReturn(callCount.getAndIncrement() == 0))) {
            zb.when(() -> ZipBuilder.findChangedProducts(anyString()))
                    .thenReturn(List.of("a.odps.yaml", "b.odps.yaml"));
            zb.when(() -> ZipBuilder.buildFromProduct(anyString())).thenReturn(built);

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(1, code);
        }
    }

    @Test
    void main_dirMode_zipBuildThrows_countsAsFailure_exitsOne() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("repo"));

        String[] args = {
                "--dir", dir.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
        };

        try (MockedStatic<ZipBuilder> zb = mockStatic(ZipBuilder.class)) {
            zb.when(() -> ZipBuilder.findChangedProducts(anyString()))
                    .thenReturn(List.of("a.odps.yaml"));
            zb.when(() -> ZipBuilder.buildFromProduct(anyString()))
                    .thenThrow(new IOException("boom"));

            int code = SystemStubs.catchSystemExit(() -> App.main(args));

            assertEquals(1, code);
        }
    }
}
