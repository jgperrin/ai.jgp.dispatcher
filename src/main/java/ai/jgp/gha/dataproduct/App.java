package ai.jgp.gha.dataproduct;

import ai.jgp.gha.dataproduct.model.ProductRef;
import ai.jgp.gha.dataproduct.model.SyncStatusEvent;

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
     * publishes the product's ODPS spec to Kafka on success (#45), and emits
     * a Zeenea sync-status event to Kafka on both success and failure (#35).
     */
    private static boolean processProduct(CliConfig config, String productFile) {
        ProductRef ref = ZipBuilder.parseProductRef(productFile);
        boolean success = false;
        String uploadId = null;
        String error = null;
        try {
            Path builtZip = ZipBuilder.buildFromProduct(productFile);
            String zipPath = builtZip.toAbsolutePath().toString();
            System.out.println();

            // #46: schema-invalid specs never leave the repo — no Zeenea
            // upload and no Kafka publish, the run fails loudly instead.
            if (!validateBundle(builtZip)) {
                return false;
            }

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

        boolean kafkaOk = publishToKafka(config, success ? productFile : null,
                success, ref, uploadId, error);
        return success && kafkaOk;
    }

    /**
     * Single file mode: handles a ZIP or .odps.yaml file directly. A product
     * YAML yields a {@link ProductRef} so a sync-status event can be emitted
     * (#35) and its ODPS spec published on success (#45); a pre-built ZIP
     * carries no ODPS coordinates, so both are skipped for it — the CC only
     * ingests per-product ODPS YAML, never ZIP bytes.
     */
    private static boolean processSingleFile(CliConfig config) {
        String zipPath = config.getFilePath();
        String productFile = null;
        ProductRef ref = null;
        if (config.isProductYaml()) {
            productFile = config.getFilePath();
            ref = ZipBuilder.parseProductRef(productFile);
            try {
                Path builtZip = ZipBuilder.buildFromProduct(productFile);
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

        // #46: validate the exact bundle that would be uploaded — covers the
        // product YAML and every bundled contract, and pre-built ZIPs too.
        if (!validateBundle(Path.of(zipPath))) {
            return false;
        }

        ZeeneaClient client = new ZeeneaClient(config, zipPath);
        boolean success = client.upload();
        String uploadId = client.getLastUploadId();
        String error = success ? null : client.getLastError();

        boolean kafkaOk = publishToKafka(config, success ? productFile : null,
                success, ref, uploadId, error);
        return success && kafkaOk;
    }

    /**
     * Runs the #46 schema gate on a spec ZIP. Prints each violation and
     * returns false when the bundle is invalid; on success logs a one-line
     * summary and returns true.
     */
    private static boolean validateBundle(Path zip) {
        List<String> violations = SchemaValidator.validateZip(zip);
        if (!violations.isEmpty()) {
            System.err.println("Schema validation failed — nothing uploaded or published:");
            for (String v : violations) {
                System.err.println("  " + v);
            }
            return false;
        }
        System.out.println("Schema validation passed for " + zip.getFileName());
        return true;
    }

    /**
     * Publishes to Kafka for a single processed asset, using one publisher for
     * both messages:
     * <ul>
     *   <li>the product's ODPS spec (YAML string, key = product id, x-org-id
     *       header) to {@link K#KAFKA_TOPIC_DESCRIPTORS}, only when the Zeenea
     *       upload succeeded and the product has an ODPS id (#45);</li>
     *   <li>an append-only sync-status event to {@link K#KAFKA_TOPIC_CATALOG_FEEDBACK}
     *       on <strong>both</strong> success and failure, when the asset has an
     *       ODPS id (#35).</li>
     * </ul>
     * The descriptor (spec) publish is <strong>fail-closed</strong> (#54): a
     * descriptor that cannot be published to Kafka returns false so the run
     * fails and git-diff change detection re-processes the file on the next
     * run — a green run can never hide a lost descriptor. The sync-status
     * event stays best-effort (#35): its failure is logged and swallowed.
     * Skipped entirely (returning true) when Kafka is not configured, or when
     * there is nothing to publish (no spec and no keyable status event).
     *
     * @param productFile    path to the product's ODPS YAML to publish, or null
     *                       to skip the spec publish (upload failed / raw ZIP)
     * @param uploadSucceeded whether the Zeenea upload succeeded
     * @param ref            the product's id/version, or null (e.g. pre-built ZIP)
     * @param uploadId       the Zeenea upload id, or null
     * @param error          failure reason, or null on success
     * @return true when every required publish succeeded (or none was due);
     *         false when a due descriptor publish failed
     */
    private static boolean publishToKafka(CliConfig config, String productFile, boolean uploadSucceeded,
                                          ProductRef ref, String uploadId, String error) {
        if (!config.isKafkaConfigured()) {
            log.fine("Kafka not configured, skipping Kafka publishing");
            return true;
        }

        boolean publishSpec = productFile != null && ref != null && ref.hasId();
        if (productFile != null && !publishSpec) {
            System.err.println("  Skipping spec publish: no ODPS id in " + productFile);
        }
        boolean publishStatus = ref != null && ref.hasId();
        if (!publishSpec && !publishStatus) {
            log.fine("Nothing to publish to Kafka (no spec, no keyable status event)");
            return true;
        }

        System.out.println();
        System.out.println("Publishing to Kafka...");

        KafkaPublisher publisher = null;
        boolean specOk = !publishSpec;
        try {
            publisher = new KafkaPublisher(
                    config.getKafkaBroker(),
                    config.getKafkaUser(),
                    config.getKafkaPassword());

            if (!publisher.isConnected()) {
                if (publishSpec) {
                    System.err.println("Error: Kafka broker is not reachable and a descriptor "
                            + "publish is due — failing the run (fail closed, #54).");
                    return false;
                }
                System.err.println("Warning: Kafka broker is not reachable, skipping Kafka publishing.");
                return true;
            }

            if (publishSpec) {
                specOk = publishSpec(publisher, config, ref, productFile);
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
        if (!specOk) {
            System.err.println("Error: descriptor publish failed — failing the run "
                    + "(fail closed, #54) so change detection retries it next run.");
        }
        return specOk;
    }

    /** Reads the product's ODPS YAML and publishes it to the descriptors topic (#45). */
    private static boolean publishSpec(KafkaPublisher publisher, CliConfig config, ProductRef ref,
                                       String productFile) throws java.io.IOException {
        String odpsYaml = Files.readString(Path.of(productFile));

        boolean ok = publisher.publishSpec(
                K.KAFKA_TOPIC_DESCRIPTORS, ref.id(), odpsYaml, config.getOrgId());
        if (ok) {
            System.out.println("  Published ODPS spec: " + ref.id()
                    + " v" + ref.version() + " (" + odpsYaml.length() + " chars)");
        } else {
            System.err.println("  Failed to publish ODPS spec to Kafka.");
        }
        return ok;
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
