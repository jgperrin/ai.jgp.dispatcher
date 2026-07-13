package ai.jgp.gha.dataproduct;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes ODPS specs and sync-status events to Kafka using the plain
 * kafka-clients library. Configures SASL_SSL with SCRAM-SHA-512
 * authentication.
 */
public class KafkaPublisher {

    private static final Logger log = Logger.getLogger(KafkaPublisher.class.getName());

    private static final long CLOSE_TIMEOUT_SECONDS = 2;

    /**
     * Producer timeouts and the retry budget. Cold-producer metadata fetches
     * from GH runners routinely exceed 5s (#54: a 5s {@code max.block.ms}
     * timed out the IMDb descriptor publish behind a green run), so the
     * production tuning blocks up to 15s per attempt and retries with
     * backoff. Package-private so tests can inject tiny values and stay fast.
     */
    record Tuning(int maxBlockMs, int requestTimeoutMs, int deliveryTimeoutMs,
                  long sendTimeoutSeconds, long probeTimeoutSeconds,
                  int attempts, long backoffMs) {
        static final Tuning PRODUCTION =
                new Tuning(15000, 10000, 30000, 40, 10, 3, 2000);
    }

    private final Tuning tuning;
    private final KafkaProducer<String, byte[]> producer;
    private final boolean connected;

    public KafkaPublisher(String broker, String user, String password) {
        this(broker, user, password, Tuning.PRODUCTION);
    }

    KafkaPublisher(String broker, String user, String password, Tuning tuning) {
        this.tuning = tuning;
        String authMode = (user != null && password != null)
                ? "SASL_SSL (SCRAM-SHA-512)"
                : "PLAINTEXT (no credentials)";
        System.out.println("       Broker:   " + broker);
        System.out.println("       User:     " + (user != null ? user : "(not set)"));
        System.out.println("       Password: " + (password != null ? "****" : "(not set)"));
        System.out.println("       Auth:     " + authMode);
        System.out.println("       Topic:    " + K.KAFKA_TOPIC_DESCRIPTORS);
        System.out.println("       Timeout:  " + tuning.probeTimeoutSeconds() + "s probe, "
                + (tuning.maxBlockMs() / 1000) + "s max block, "
                + tuning.attempts() + " attempts");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, tuning.maxBlockMs());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, tuning.requestTimeoutMs());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, tuning.deliveryTimeoutMs());

        // SASL_SSL + SCRAM-SHA-512
        if (user != null && password != null) {
            props.put("security.protocol", "SASL_SSL");
            props.put("sasl.mechanism", "SCRAM-SHA-512");
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.scram.ScramLoginModule required "
                            + "username=\"" + user + "\" "
                            + "password=\"" + password + "\";");

            // TECH DEBT: auto-trust the broker's certificate to avoid external
            // truststore files. Fetches the server cert at runtime and creates a
            // temporary JKS truststore. The connection is encrypted (TLS) and trusts
            // only the actual server cert. Replace with proper CA management when feasible.
            props.put("ssl.endpoint.identification.algorithm", "");
            try {
                String tempTruststore = buildTruststoreFromBroker(broker);
                if (tempTruststore != null) {
                    props.put("ssl.truststore.location", tempTruststore);
                    props.put("ssl.truststore.password", "changeit");
                    System.out.println("       SSL:      auto-trusted broker certificate");
                }
            } catch (Exception e) {
                System.err.println("       SSL:      failed to auto-trust — " + e.getMessage());
            }
        } else {
            props.put("security.protocol", "PLAINTEXT");
        }

        log.fine("Producer config: max.block.ms=" + tuning.maxBlockMs()
                + ", request.timeout.ms=" + tuning.requestTimeoutMs()
                + ", delivery.timeout.ms=" + tuning.deliveryTimeoutMs());

        // Suppress Kafka's verbose internal logging (config dumps, disconnect spam)
        Logger.getLogger("org.apache.kafka").setLevel(Level.SEVERE);

        this.producer = new KafkaProducer<>(props);
        log.fine("KafkaProducer created successfully");

        // Probe broker connectivity — forces a metadata fetch. Retried (#54):
        // a cold producer's first metadata fetch from a GH runner routinely
        // exceeds a single short timeout.
        this.connected = retry("metadata probe", tuning.attempts(),
                tuning.backoffMs(), this::probeOnce);
        if (!connected) {
            System.err.println("       Probe:    broker unreachable after "
                    + tuning.attempts() + " attempt(s)");
        }
    }

    /** One metadata-fetch attempt against the descriptors topic. */
    private boolean probeOnce() {
        try {
            CompletableFuture.supplyAsync(() -> producer.partitionsFor(K.KAFKA_TOPIC_DESCRIPTORS))
                    .get(tuning.probeTimeoutSeconds(), TimeUnit.SECONDS);
            log.fine("Kafka broker is reachable");
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("       Probe:    broker did not respond within "
                    + tuning.probeTimeoutSeconds() + "s");
            return false;
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            System.err.println("       Probe:    " + cause.getClass().getSimpleName()
                    + " — " + cause.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Publishes one product's ODPS spec to the descriptors topic (#45).
     * The value is the raw ODPS YAML as UTF-8 bytes — wire-identical to what
     * the Control Center's {@code StringDeserializer}-based
     * {@code SpecIngestConsumer} expects — keyed by the ODPS product id and
     * stamped with the {@link K#KAFKA_HEADER_ORG_ID} header (CC issue #81
     * header contract: header-less records are dropped to event_log).
     * Blocks until the send completes or fails.
     *
     * @param topic     the Kafka topic to publish to
     * @param productId the ODPS product id (message key)
     * @param odpsYaml  the product's ODPS YAML content
     * @param orgId     the authoring tenant's org UUID (x-org-id header)
     * @return true if published successfully, false otherwise
     */
    public boolean publishSpec(String topic, String productId, String odpsYaml, String orgId) {
        log.fine("Sending ODPS spec to topic " + topic + " (key: " + productId
                + ", size: " + odpsYaml.length() + " chars, org: " + orgId + ")");
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                topic, productId, odpsYaml.getBytes(StandardCharsets.UTF_8));
        record.headers().add(K.KAFKA_HEADER_ORG_ID,
                orgId.getBytes(StandardCharsets.UTF_8));
        return send(record);
    }

    /**
     * Publishes an append-only sync-status event to the status topic
     * ({@link K#KAFKA_TOPIC_CATALOG_FEEDBACK}) as a single UTF-8 JSON message (#35).
     * Best-effort: returns false on failure without throwing, so a failed
     * status report never breaks the caller.
     *
     * @param key  the message key (e.g. {@code "<id>:<version>"})
     * @param json the event payload as JSON
     * @return true if published successfully, false otherwise
     */
    public boolean publishStatus(String key, String json) {
        log.fine("Sending sync-status to topic " + K.KAFKA_TOPIC_CATALOG_FEEDBACK
                + " (key: " + key + ")");
        return send(K.KAFKA_TOPIC_CATALOG_FEEDBACK, key, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends a single record and blocks until it completes or fails. Shared by
     * {@link #publishSpec} and {@link #publishStatus}.
     */
    private boolean send(String topic, String key, byte[] value) {
        return send(new ProducerRecord<>(topic, key, value));
    }

    private boolean send(ProducerRecord<String, byte[]> record) {
        // #54: sends are retried — transient metadata timeouts on a cold
        // producer must not lose a descriptor. The publish is an upsert
        // downstream, so a duplicate from a retried-but-actually-delivered
        // send is harmless.
        return retry("publish to " + record.topic(), tuning.attempts(),
                tuning.backoffMs(), () -> sendOnce(record));
    }

    /** One blocking send attempt. */
    private boolean sendOnce(ProducerRecord<String, byte[]> record) {
        String topic = record.topic();
        try {
            var metadata = producer.send(record).get(tuning.sendTimeoutSeconds(), TimeUnit.SECONDS);
            log.fine("Published to " + metadata.topic()
                    + " partition " + metadata.partition()
                    + " offset " + metadata.offset());
            return true;
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            System.err.println("  Failed to publish to " + topic + ": " + cause.getMessage());
            log.log(Level.WARNING, "Kafka send failed for " + topic, cause);
            return false;
        }
    }

    /**
     * Runs {@code op} up to {@code attempts} times, sleeping {@code backoffMs}
     * between failed attempts. Returns true as soon as one attempt succeeds,
     * false when the budget is exhausted (#54).
     */
    static boolean retry(String what, int attempts, long backoffMs,
                         java.util.function.BooleanSupplier op) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (op.getAsBoolean()) {
                return true;
            }
            if (attempt < attempts) {
                System.err.println("  Retrying " + what + " (attempt "
                        + (attempt + 1) + "/" + attempts + ")...");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Flushes pending records and closes the producer.
     */
    public void close() {
        producer.close(Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS));
    }

    /**
     * Connects to the broker via a trust-all SSL socket, grabs the server
     * certificate chain, and writes it into a temporary JKS truststore.
     *
     * @return absolute path to the temp truststore, or null on failure
     */
    private static String buildTruststoreFromBroker(String broker) throws Exception {
        // Parse host:port from broker string
        String host;
        int port;
        String[] parts = broker.split(",")[0].split(":");
        host = parts[0];
        port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9093;

        // Create a trust-all context just to grab the cert
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String t) { }
                    public void checkServerTrusted(X509Certificate[] c, String t) { }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());

        Certificate[] serverCerts;
        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port)) {
            socket.setSoTimeout(5000);
            socket.startHandshake();
            serverCerts = socket.getSession().getPeerCertificates();
        }

        // Build a JKS truststore containing the server's cert chain
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, "changeit".toCharArray());
        for (int i = 0; i < serverCerts.length; i++) {
            ks.setCertificateEntry("kafka-cert-" + i, serverCerts[i]);
        }
        Path tempFile = Files.createTempFile("kafka-trust-", ".jks");
        tempFile.toFile().deleteOnExit();
        try (OutputStream os = Files.newOutputStream(tempFile)) {
            ks.store(os, "changeit".toCharArray());
        }
        return tempFile.toAbsolutePath().toString();
    }
}
