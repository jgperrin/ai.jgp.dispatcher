package ai.jgp.gha.dataproduct;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * publishes to Kafka.
     */
    private static boolean processProduct(CliConfig config, String productFile) {
        try {
            Path builtZip = ZipBuilder.buildFromProduct(productFile);
            String zipPath = builtZip.toAbsolutePath().toString();
            System.out.println();

            ZeeneaClient client = new ZeeneaClient(config, zipPath);
            boolean success = client.upload();

            if (success && config.isKafkaConfigured()) {
                publishZipToKafka(config, zipPath);
            }

            return success;
        } catch (Exception e) {
            System.err.println("Error processing " + productFile + ": " + e.getMessage());
            log.log(Level.SEVERE, "Processing failed for " + productFile, e);
            return false;
        }
    }

    /**
     * Single file mode: handles a ZIP or .odps.yaml file directly.
     */
    private static boolean processSingleFile(CliConfig config) {
        String zipPath = config.getFilePath();
        if (config.isProductYaml()) {
            try {
                Path builtZip = ZipBuilder.buildFromProduct(config.getFilePath());
                zipPath = builtZip.toAbsolutePath().toString();
                System.out.println();
            } catch (Exception e) {
                System.err.println("Error building ZIP from product YAML: " + e.getMessage());
                log.log(Level.SEVERE, "ZipBuilder failed", e);
                return false;
            }
        }

        ZeeneaClient client = new ZeeneaClient(config, zipPath);
        boolean success = client.upload();

        if (success && config.isKafkaConfigured()) {
            log.fine("Kafka is configured (broker: " + config.getKafkaBroker()
                    + "), starting ZIP publishing");
            publishZipToKafka(config, zipPath);
        } else if (!success) {
            log.fine("Zeenea upload failed, skipping Kafka publishing");
        } else {
            log.fine("Kafka not configured, skipping Kafka publishing");
        }

        return success;
    }

    /**
     * Reads the ZIP file and publishes it as a single binary message to the
     * Kafka spec ingest topic.
     */
    private static void publishZipToKafka(CliConfig config, String zipPath) {
        System.out.println();
        System.out.println("Publishing ZIP to Kafka...");

        KafkaPublisher publisher = null;

        try {
            publisher = new KafkaPublisher(
                    config.getKafkaBroker(),
                    config.getKafkaUser(),
                    config.getKafkaPassword());

            if (!publisher.isConnected()) {
                System.err.println("Warning: Kafka broker is not reachable, skipping ZIP publishing.");
                return;
            }

            File zipFile = new File(zipPath);
            byte[] zipData = Files.readAllBytes(zipFile.toPath());
            String key = zipFile.getName();

            log.fine("Read ZIP file: " + key + " (" + zipData.length + " bytes)");

            boolean ok = publisher.publishZip(
                    K.KAFKA_TOPIC_SPEC_INGEST,
                    key,
                    zipData);

            if (ok) {
                System.out.println("  Published: " + key + " (" + zipData.length + " bytes)");
            } else {
                System.err.println("  Failed to publish ZIP to Kafka.");
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
}
