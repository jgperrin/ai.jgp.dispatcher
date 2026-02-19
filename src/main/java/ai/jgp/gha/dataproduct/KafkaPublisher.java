package ai.jgp.gha.dataproduct;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final int TOPIC_PARTITIONS = 1;
    private static final short TOPIC_REPLICATION_FACTOR = 1;
    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final KafkaProducer<String, String> producer;
    private final Properties connectionProps;

    public KafkaPublisher(String broker, String user, String password) {
        log.fine("Initializing KafkaPublisher with broker: " + broker
                + ", user: " + (user != null ? user : "(none)")
                + ", password: " + (password != null ? "***" : "(none)"));

        String truststorePath = Path.of(System.getProperty("user.home"), TRUSTSTORE_RELATIVE_PATH).toString();

        connectionProps = new Properties();
        connectionProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);

        // SASL_SSL + SCRAM-SHA-512
        if (user != null && password != null) {
            connectionProps.put("security.protocol", "SASL_SSL");
            connectionProps.put("sasl.mechanism", "SCRAM-SHA-512");
            connectionProps.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.scram.ScramLoginModule required "
                            + "username=\"" + user + "\" "
                            + "password=\"" + password + "\";");

            // Truststore configuration
            if (Files.exists(Path.of(truststorePath))) {
                connectionProps.put("ssl.truststore.location", truststorePath);
                connectionProps.put("ssl.truststore.password", TRUSTSTORE_PASSWORD);
                log.fine("Using truststore: " + truststorePath);
            } else {
                log.fine("Truststore not found at " + truststorePath + ", using default SSL context");
            }
        } else {
            log.fine("No Kafka credentials provided, using PLAINTEXT");
            connectionProps.put("security.protocol", "PLAINTEXT");
        }

        Properties producerProps = new Properties();
        producerProps.putAll(connectionProps);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");

        log.fine("Creating KafkaProducer with security.protocol="
                + producerProps.getProperty("security.protocol"));
        this.producer = new KafkaProducer<>(producerProps);
        log.fine("KafkaProducer created successfully");
    }

    /**
     * Ensures the given topic exists, creating it if necessary.
     *
     * @param topic the topic name to ensure exists
     */
    /**
     * Best-effort check: ensures the given topic exists, creating it if
     * necessary. If the check fails (e.g. network/auth issue), a warning
     * is logged and publishing proceeds anyway — the producer will fail
     * later with a clearer error if the topic truly doesn't exist.
     *
     * @param topic the topic name to ensure exists
     */
    public void ensureTopicExists(String topic) {
        log.fine("Checking if topic " + topic + " exists...");
        try (AdminClient admin = AdminClient.create(connectionProps)) {
            var existingTopics = admin.listTopics().names().get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.fine("Broker has " + existingTopics.size() + " topic(s): " + existingTopics);
            if (existingTopics.contains(topic)) {
                log.fine("Topic " + topic + " already exists");
                return;
            }

            log.fine("Topic " + topic + " not found, creating with "
                    + TOPIC_PARTITIONS + " partition(s), replication factor "
                    + TOPIC_REPLICATION_FACTOR);
            NewTopic newTopic = new NewTopic(topic, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR);
            admin.createTopics(Collections.singleton(newTopic)).all()
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            System.out.println("       Created Kafka topic: " + topic);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                log.fine("Topic " + topic + " was created concurrently");
            } else {
                System.err.println("  Warning: could not verify topic " + topic
                        + ": " + e.getCause().getMessage());
                log.log(Level.WARNING, "Failed to verify/create topic " + topic, e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("  Warning: interrupted while checking topic " + topic);
        } catch (TimeoutException e) {
            System.err.println("  Warning: timed out checking topic " + topic
                    + ", proceeding anyway");
            log.warning("Timed out checking topic " + topic + ", will attempt publish regardless");
        }
    }

    /**
     * Publishes a spec YAML to Kafka wrapped in an OOCS deliver-spec envelope.
     * Blocks until the send completes or fails.
     *
     * @param topic      the Kafka topic to publish to
     * @param artifactId the spec artifact ID (e.g. data product or contract ID)
     * @param version    the spec version
     * @param kind       the spec kind ("DataProduct" or "DataContract")
     * @param content    the full YAML content of the spec
     * @return true if published successfully, false otherwise
     */
    public boolean publishSpec(String topic, String artifactId, String version,
                               String kind, String content) {
        String envelope = buildEnvelope(artifactId, version, kind, content);

        log.fine("Sending " + kind + " " + artifactId + " v" + version
                + " to topic " + topic + " (envelope size: " + envelope.length() + " chars)");
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, artifactId, envelope);
        try {
            var metadata = producer.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.fine("Published " + artifactId + " to " + metadata.topic()
                    + " partition " + metadata.partition()
                    + " offset " + metadata.offset());
            return true;
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            System.err.println("  Failed to publish " + artifactId + ": " + cause.getMessage());
            log.log(Level.WARNING, "Kafka send failed for " + artifactId, cause);
            return false;
        }
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
