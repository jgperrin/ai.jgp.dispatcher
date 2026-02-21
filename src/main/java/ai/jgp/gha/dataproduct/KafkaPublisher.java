package ai.jgp.gha.dataproduct;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final int MAX_BLOCK_MS = 5000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int DELIVERY_TIMEOUT_MS = 10000;
    private static final long PROBE_TIMEOUT_SECONDS = 5;
    private static final long CLOSE_TIMEOUT_SECONDS = 2;

    private final KafkaProducer<String, String> producer;
    private final boolean connected;

    public KafkaPublisher(String broker, String user, String password) {
        String authMode = (user != null && password != null)
                ? "SASL_SSL (SCRAM-SHA-512, user=" + user + ")"
                : "PLAINTEXT (no credentials)";
        System.out.println("       Broker:   " + broker);
        System.out.println("       Auth:     " + authMode);
        System.out.println("       Topic:    " + K.KAFKA_TOPIC_SPEC_INGEST);
        System.out.println("       Timeout:  " + PROBE_TIMEOUT_SECONDS + "s probe, "
                + (MAX_BLOCK_MS / 1000) + "s max block");

        String truststorePath = Path.of(System.getProperty("user.home"), TRUSTSTORE_RELATIVE_PATH).toString();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // Shorter timeouts for faster failure diagnostics
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS);

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
                System.out.println("       Truststore: " + truststorePath);
            } else {
                System.out.println("       Truststore: (not found, using default SSL context)");
                log.fine("Truststore not found at " + truststorePath);
            }
        } else {
            props.put("security.protocol", "PLAINTEXT");
        }

        log.fine("Producer config: max.block.ms=" + MAX_BLOCK_MS
                + ", request.timeout.ms=" + REQUEST_TIMEOUT_MS
                + ", delivery.timeout.ms=" + DELIVERY_TIMEOUT_MS);

        // Suppress Kafka's verbose internal logging (config dumps, disconnect spam)
        Logger.getLogger("org.apache.kafka").setLevel(Level.SEVERE);

        this.producer = new KafkaProducer<>(props);
        log.fine("KafkaProducer created successfully");

        // Probe broker connectivity — forces metadata fetch with a short timeout
        boolean reachable;
        try {
            CompletableFuture.supplyAsync(() -> producer.partitionsFor(K.KAFKA_TOPIC_SPEC_INGEST))
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            reachable = true;
            log.fine("Kafka broker is reachable");
        } catch (java.util.concurrent.TimeoutException e) {
            reachable = false;
            System.err.println("       Probe:    broker did not respond within "
                    + PROBE_TIMEOUT_SECONDS + "s");
        } catch (Exception e) {
            reachable = false;
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            System.err.println("       Probe:    " + cause.getClass().getSimpleName()
                    + " — " + cause.getMessage());
        }
        this.connected = reachable;
    }

    public boolean isConnected() {
        return connected;
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
        producer.close(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS));
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
