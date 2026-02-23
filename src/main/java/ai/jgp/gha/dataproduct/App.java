package ai.jgp.gha.dataproduct;

import java.io.File;
import java.nio.file.Files;
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

        ZeeneaClient client = new ZeeneaClient(config);

        boolean success = client.upload();

        // Publish ZIP to Kafka if configured and Zeenea upload succeeded
        if (success && config.isKafkaConfigured()) {
            log.fine("Kafka is configured (broker: " + config.getKafkaBroker()
                    + "), starting ZIP publishing");
            publishZipToKafka(config);
        } else if (!success) {
            log.fine("Zeenea upload failed, skipping Kafka publishing");
        } else {
            log.fine("Kafka not configured, skipping Kafka publishing");
        }

        System.exit(success ? 0 : 1);
    }

    /**
     * Reads the ZIP file and publishes it as a single binary message to the
     * Kafka spec ingest topic.
     */
    private static void publishZipToKafka(CliConfig config) {
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

            File zipFile = new File(config.getFilePath());
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
