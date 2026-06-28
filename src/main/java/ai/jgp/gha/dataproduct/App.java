package ai.jgp.gha.dataproduct;

import ai.jgp.gha.dataproduct.model.ProductRef;
import ai.jgp.gha.dataproduct.model.SyncStatusEvent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    private static final Logger log = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        System.out.println("Data Product Uploader v" + K.VERSION);
        System.out.println();

        CliConfig config = CliConfig.parse(args);

        if (config.isDebug()) {
            Logger root = Logger.getLogger("ai.jgp.gha.dataproduct");
            root.setLevel(Level.FINE);
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(Level.FINE);
            root.addHandler(handler);
        }

        if (config.isDirMode()) {
            System.exit(processDirectory(config));
        } else {
            System.exit(processSingleFile(config) ? 0 : 1);
        }
    }

    /**
     * Directory mode: uses git diff to find changed .odps.yaml files,
     * then processes each one.
     *
     * @return exit code (0 = all succeeded, 1 = at least one failure)
     */
    private static int processDirectory(CliConfig config) {
        List<String> changed = ZipBuilder.findChangedProducts(config.getDirPath());

        if (changed.isEmpty()) {
            System.out.println("No product files changed, nothing to upload.");
            return 0;
        }

        System.out.println("Changed product files:");
        for (String f : changed) {
            System.out.println("  " + f);
        }
        System.out.println();

        int succeeded = 0;
        int failed = 0;

        for (String productFile : changed) {
            System.out.println("==========================================");
            System.out.println("Processing: " + productFile);
            System.out.println("==========================================");

            boolean ok = processProduct(config, productFile);
            if (ok) {
                succeeded++;
            } else {
                failed++;
            }
            System.out.println();
        }

        System.out.println("Summary: " + succeeded + " succeeded, " + failed + " failed"
                + " (out of " + changed.size() + " products)");

        return failed > 0 ? 1 : 0;
    }

    /**
     * Processes a single product YAML: builds ZIP, uploads to Zeenea,
     * publishes the spec bundle to Kafka on success, and emits a Zeenea
     * sync-status event to Kafka on both success and failure (#35).
     */
    private static boolean processProduct(CliConfig config, String productFile) {
        ProductRef ref = ZipBuilder.parseProductRef(productFile);
        boolean success = false;
        String zipPath = null;
        String uploadId = null;
        String error = null;
        try {
            Path builtZip = ZipBuilder.buildFromProduct(productFile);
            zipPath = builtZip.toAbsolutePath().toString();
            System.out.println();

            ZeeneaClient client = new ZeeneaClient(config, zipPath);
            success = client.upload();
            uploadId = client.getLastUploadId();
            if (!success) {
                error = client.getLastError();
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("Error processing " + productFile + ": " + e.getMessage());
            log.log(Level.SEVERE, "Processing failed for " + productFile, e);
        }

        publishToKafka(config, success ? zipPath : null, success, ref, uploadId, error);
        return success;
    }

    /**
     * Single file mode: handles a ZIP or .odps.yaml file directly. A product
     * YAML yields a {@link ProductRef} so a sync-status event can be emitted
     * (#35); a pre-built ZIP carries no ODPS coordinates, so status is skipped
     * for it (the spec-bundle publish on success is unchanged).
     */
    private static boolean processSingleFile(CliConfig config) {
        String zipPath = config.getFilePath();
        ProductRef ref = null;
        if (config.isProductYaml()) {
            ref = ZipBuilder.parseProductRef(config.getFilePath());
            try {
                Path builtZip = ZipBuilder.buildFromProduct(config.getFilePath());
                zipPath = builtZip.toAbsolutePath().toString();
                System.out.println();
            } catch (Exception e) {
                String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                System.err.println("Error building ZIP from product YAML: " + e.getMessage());
                log.log(Level.SEVERE, "ZipBuilder failed", e);
                publishToKafka(config, null, false, ref, null, error);
                return false;
            }
        }

        ZeeneaClient client = new ZeeneaClient(config, zipPath);
        boolean success = client.upload();
        String uploadId = client.getLastUploadId();
        String error = success ? null : client.getLastError();

        publishToKafka(config, success ? zipPath : null, success, ref, uploadId, error);
        return success;
    }

    /**
     * Publishes to Kafka for a single processed asset, using one publisher for
     * both messages:
     * <ul>
     *   <li>the spec bundle to {@link K#KAFKA_TOPIC_SPEC_INGEST}, only when the
     *       Zeenea upload succeeded (unchanged behavior);</li>
     *   <li>an append-only sync-status event to {@link K#KAFKA_TOPIC_SPEC_STATUS}
     *       on <strong>both</strong> success and failure, when the asset has an
     *       ODPS id (#35).</li>
     * </ul>
     * Best-effort: any failure here is logged and swallowed, never altering the
     * process exit code. Skipped entirely when Kafka is not configured, or when
     * there is nothing to publish (no ZIP and no keyable status event).
     *
     * @param zipPath        path to the built ZIP to publish, or null to skip the
     *                       spec-bundle publish (upload failed / no ZIP)
     * @param uploadSucceeded whether the Zeenea upload succeeded
     * @param ref            the product's id/version, or null (e.g. pre-built ZIP)
     * @param uploadId       the Zeenea upload id, or null
     * @param error          failure reason, or null on success
     */
    private static void publishToKafka(CliConfig config, String zipPath, boolean uploadSucceeded,
                                       ProductRef ref, String uploadId, String error) {
        if (!config.isKafkaConfigured()) {
            log.fine("Kafka not configured, skipping Kafka publishing");
            return;
        }

        boolean publishZip = zipPath != null;
        boolean publishStatus = ref != null && ref.hasId();
        if (!publishZip && !publishStatus) {
            log.fine("Nothing to publish to Kafka (no ZIP, no keyable status event)");
            return;
        }

        System.out.println();
        System.out.println("Publishing to Kafka...");

        KafkaPublisher publisher = null;
        try {
            publisher = new KafkaPublisher(
                    config.getKafkaBroker(),
                    config.getKafkaUser(),
                    config.getKafkaPassword());

            if (!publisher.isConnected()) {
                System.err.println("Warning: Kafka broker is not reachable, skipping Kafka publishing.");
                return;
            }

            if (publishZip) {
                publishZip(publisher, zipPath);
            }
            if (publishStatus) {
                publishStatus(publisher, config, ref, uploadSucceeded, uploadId, error);
            }
        } catch (Exception e) {
            System.err.println("Error publishing to Kafka: " + e.getMessage());
            log.log(Level.WARNING, "Kafka publishing failed", e);
        } finally {
            if (publisher != null) {
                publisher.close();
            }
        }
    }

    /** Reads the built ZIP and publishes it to the spec-ingest topic. */
    private static void publishZip(KafkaPublisher publisher, String zipPath) throws java.io.IOException {
        File zipFile = new File(zipPath);
        byte[] zipData = Files.readAllBytes(zipFile.toPath());
        String key = zipFile.getName();

        log.fine("Read ZIP file: " + key + " (" + zipData.length + " bytes)");

        boolean ok = publisher.publishZip(K.KAFKA_TOPIC_SPEC_INGEST, key, zipData);
        if (ok) {
            System.out.println("  Published spec bundle: " + key + " (" + zipData.length + " bytes)");
        } else {
            System.err.println("  Failed to publish spec bundle to Kafka.");
        }
    }

    /** Builds and publishes the sync-status event to the status topic (#35). */
    private static void publishStatus(KafkaPublisher publisher, CliConfig config, ProductRef ref,
                                      boolean success, String uploadId, String error) {
        SyncStatusEvent event = new SyncStatusEvent(
                ref.id(), ref.version(), success, uploadId,
                Instant.now().toString(), config.getTenant(), config.getCatalogCode(), error);

        boolean ok = publisher.publishStatus(event.key(), event.toJson());
        if (ok) {
            System.out.println("  Published sync-status [" + event.status() + "]: " + event.key());
        } else {
            System.err.println("  Failed to publish sync-status event to Kafka.");
        }
    }
}
