package ai.jgp.gha.dataproduct;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes YAML spec files to Kafka using the plain kafka-clients library.
 * Configures SASL_SSL with SCRAM-SHA-512 authentication.
 */
public class KafkaPublisher {

    private static final Logger log = Logger.getLogger(KafkaPublisher.class.getName());

    private static final String TRUSTSTORE_RELATIVE_PATH = ".kafka/kafka.client.truststore.jks";
    private static final String TRUSTSTORE_PASSWORD = "changeit";

    private final KafkaProducer<String, String> producer;

    public KafkaPublisher(String broker, String user, String password) {
        String truststorePath = Path.of(System.getProperty("user.home"), TRUSTSTORE_RELATIVE_PATH).toString();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // SASL_SSL + SCRAM-SHA-512
        if (user != null && password != null) {
            props.put("security.protocol", "SASL_SSL");
            props.put("sasl.mechanism", "SCRAM-SHA-512");
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.scram.ScramLoginModule required "
                            + "username=\"" + user + "\" "
                            + "password=\"" + password + "\";");

            // Truststore configuration
            if (Files.exists(Path.of(truststorePath))) {
                props.put("ssl.truststore.location", truststorePath);
                props.put("ssl.truststore.password", TRUSTSTORE_PASSWORD);
                log.fine("Using truststore: " + truststorePath);
            } else {
                log.fine("Truststore not found at " + truststorePath + ", using default SSL context");
            }
        } else {
            log.fine("No Kafka credentials provided, using PLAINTEXT");
            props.put("security.protocol", "PLAINTEXT");
        }

        this.producer = new KafkaProducer<>(props);
    }

    /**
     * Publishes a spec YAML to Kafka wrapped in an OOCS deliver-spec envelope.
     *
     * @param topic      the Kafka topic to publish to
     * @param artifactId the spec artifact ID (e.g. data product or contract ID)
     * @param version    the spec version
     * @param kind       the spec kind ("DataProduct" or "DataContract")
     * @param content    the full YAML content of the spec
     */
    public void publishSpec(String topic, String artifactId, String version,
                            String kind, String content) {
        String envelope = buildEnvelope(artifactId, version, kind, content);

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, artifactId, envelope);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                System.err.println("Failed to publish spec " + artifactId + " to Kafka: "
                        + exception.getMessage());
                log.log(Level.WARNING, "Kafka send failed for " + artifactId, exception);
            } else {
                log.fine("Published " + artifactId + " to " + metadata.topic()
                        + " partition " + metadata.partition()
                        + " offset " + metadata.offset());
            }
        });
    }

    /**
     * Flushes pending records and closes the producer.
     */
    public void close() {
        producer.flush();
        producer.close();
    }

    /**
     * Builds an OOCS deliver-spec envelope as a YAML string.
     */
    static String buildEnvelope(String artifactId, String version,
                                String kind, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("kind: \"OrchestrationControl\"\n");
        sb.append("apiVersion: \"v0.1.0\"\n");
        sb.append("id: \"").append(UUID.randomUUID()).append("\"\n");
        sb.append("version: \"1.0.0\"\n");
        sb.append("actions:\n");
        sb.append("- id: \"deliver-spec\"\n");
        sb.append("  type: \"deliver-spec\"\n");
        sb.append("  payload:\n");
        sb.append("    kind: \"").append(kind).append("\"\n");
        sb.append("    id: \"").append(artifactId).append("\"\n");
        sb.append("    version: \"").append(version).append("\"\n");
        sb.append("    content: |\n");

        // Indent each line of the YAML content by 6 spaces for the YAML block scalar
        for (String line : content.split("\n", -1)) {
            if (line.isEmpty()) {
                sb.append("\n");
            } else {
                sb.append("      ").append(line).append("\n");
            }
        }

        return sb.toString();
    }
}
