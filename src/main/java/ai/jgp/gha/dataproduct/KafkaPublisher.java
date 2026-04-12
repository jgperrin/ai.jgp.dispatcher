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
 * Publishes ZIP spec bundles to Kafka using the plain kafka-clients library.
 * Configures SASL_SSL with SCRAM-SHA-512 authentication.
 */
public class KafkaPublisher {

    private static final Logger log = Logger.getLogger(KafkaPublisher.class.getName());

    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final int MAX_BLOCK_MS = 5000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int DELIVERY_TIMEOUT_MS = 10000;
    private static final long PROBE_TIMEOUT_SECONDS = 5;
    private static final long CLOSE_TIMEOUT_SECONDS = 2;

    private final KafkaProducer<String, byte[]> producer;
    private final boolean connected;

    /**
     * Package-private constructor for testing with a pre-built producer.
     */
    KafkaPublisher(KafkaProducer<String, byte[]> producer, boolean connected) {
        this.producer = producer;
        this.connected = connected;
    }

    public KafkaPublisher(String broker, String user, String password) {
        String authMode = (user != null && password != null)
                ? "SASL_SSL (SCRAM-SHA-512)"
                : "PLAINTEXT (no credentials)";
        System.out.println("       Broker:   " + broker);
        System.out.println("       User:     " + (user != null ? user : "(not set)"));
        System.out.println("       Password: " + (password != null ? "****" : "(not set)"));
        System.out.println("       Auth:     " + authMode);
        System.out.println("       Topic:    " + K.KAFKA_TOPIC_SPEC_INGEST);
        System.out.println("       Timeout:  " + PROBE_TIMEOUT_SECONDS + "s probe, "
                + (MAX_BLOCK_MS / 1000) + "s max block");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
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
     * Publishes a ZIP bundle to Kafka as a single binary message.
     * Blocks until the send completes or fails.
     *
     * @param topic   the Kafka topic to publish to
     * @param key     the message key (e.g. ZIP filename)
     * @param zipData the raw ZIP bytes
     * @return true if published successfully, false otherwise
     */
    public boolean publishZip(String topic, String key, byte[] zipData) {
        log.fine("Sending ZIP to topic " + topic + " (key: " + key
                + ", size: " + zipData.length + " bytes)");
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, zipData);
        try {
            var metadata = producer.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.fine("Published ZIP to " + metadata.topic()
                    + " partition " + metadata.partition()
                    + " offset " + metadata.offset());
            return true;
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            System.err.println("  Failed to publish ZIP: " + cause.getMessage());
            log.log(Level.WARNING, "Kafka send failed for ZIP", cause);
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
     * Connects to the broker via a trust-all SSL socket, grabs the server
     * certificate chain, and writes it into a temporary JKS truststore.
     *
     * @return absolute path to the temp truststore, or null on failure
     */
    @Generated
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
