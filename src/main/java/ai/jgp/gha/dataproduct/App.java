package ai.jgp.gha.dataproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

        // Publish specs to Kafka if configured and Zeenea upload succeeded
        if (success && config.isKafkaConfigured()) {
            log.fine("Kafka is configured (broker: " + config.getKafkaBroker()
                    + "), starting spec publishing");
            publishSpecsToKafka(config);
        } else if (!success) {
            log.fine("Zeenea upload failed, skipping Kafka publishing");
        } else {
            log.fine("Kafka not configured, skipping spec publishing");
        }

        System.exit(success ? 0 : 1);
    }

    /**
     * Opens the ZIP file and publishes each YAML entry to the Kafka spec
     * ingest topic.
     */
    private static void publishSpecsToKafka(CliConfig config) {
        System.out.println();
        System.out.println("Publishing specs to Kafka...");

        KafkaPublisher publisher = null;
        int published = 0;
        int failed = 0;

        try {
            publisher = new KafkaPublisher(
                    config.getKafkaBroker(),
                    config.getKafkaUser(),
                    config.getKafkaPassword());

            YAMLMapper yamlMapper = new YAMLMapper();

            log.fine("Opening ZIP file: " + config.getFilePath());
            try (ZipFile zipFile = new ZipFile(config.getFilePath())) {
                var entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (entry.isDirectory() || !name.endsWith(".yaml")) {
                        log.fine("Skipping ZIP entry: " + name);
                        continue;
                    }

                    log.fine("Processing ZIP entry: " + name
                            + " (" + entry.getSize() + " bytes)");

                    // Read YAML content from the ZIP entry
                    String content;
                    try (InputStream is = zipFile.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        content = reader.lines().collect(Collectors.joining("\n"));
                    }

                    // Detect kind from filename
                    String kind;
                    if (name.endsWith(".odps.yaml")) {
                        kind = "DataProduct";
                    } else if (name.endsWith(".odcs.yaml")) {
                        kind = "DataContract";
                    } else {
                        log.fine("Skipping unknown YAML type: " + name);
                        continue;
                    }

                    // Parse YAML to extract id and version
                    String artifactId;
                    String version;
                    try {
                        JsonNode root = yamlMapper.readTree(content);
                        JsonNode idNode = root.path("id");
                        JsonNode versionNode = root.path("version");

                        if (idNode.isMissingNode() || versionNode.isMissingNode()) {
                            System.err.println("  Warning: skipping " + name
                                    + " (missing id or version field)");
                            continue;
                        }

                        artifactId = idNode.asText();
                        version = versionNode.asText();
                        log.fine("Parsed " + name + ": kind=" + kind
                                + ", id=" + artifactId + ", version=" + version);
                    } catch (Exception e) {
                        System.err.println("  Warning: failed to parse " + name
                                + ": " + e.getMessage());
                        continue;
                    }

                    boolean ok = publisher.publishSpec(
                            K.KAFKA_TOPIC_SPEC_INGEST,
                            artifactId,
                            version,
                            kind,
                            content);

                    if (ok) {
                        published++;
                        System.out.println("  Published: " + name
                                + " (" + kind + " " + artifactId + " v" + version + ")");
                    } else {
                        failed++;
                    }
                }
            }

            System.out.println("Kafka publishing complete: " + published + " published, "
                    + failed + " failed.");

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
