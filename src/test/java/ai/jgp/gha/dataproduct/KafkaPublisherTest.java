package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link KafkaPublisher}.
 *
 * <p>Tests focus on the behaviour we can exercise without a real
 * broker: the constructor's connectivity probe must fail cleanly
 * (returning {@code isConnected() == false}) when the broker is
 * unreachable, {@code publishSpec} must return {@code false} in that
 * disconnected state, and {@code close} must be safe to call.
 *
 * <p>The Kafka producer's full SASL_SSL handshake is not exercised
 * here — that path requires a live broker and is out of scope per the
 * user story.
 */
class KafkaPublisherTest {

    /** Bogus host that resolves but never accepts connections. */
    private static final String UNREACHABLE_PLAINTEXT = "127.0.0.1:1";

    @Test
    @Timeout(30)
    void unreachableBroker_plaintext_isNotConnected() {
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null);
        try {
            assertFalse(pub.isConnected());
        } finally {
            pub.close();
        }
    }

    @Test
    @Timeout(30)
    void publishSpec_returnsFalse_whenBrokerUnreachable() {
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null);
        try {
            boolean ok = pub.publishSpec(K.KAFKA_TOPIC_DESCRIPTORS, "my-product",
                    "id: my-product\nversion: 1.0.0\n",
                    "3f2b8c1e-9a4d-4e7f-b6a5-1c2d3e4f5a6b");
            assertFalse(ok);
        } finally {
            pub.close();
        }
    }

    @Test
    @Timeout(30)
    void publishStatus_returnsFalse_whenBrokerUnreachable() {
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null);
        try {
            boolean ok = pub.publishStatus("prod-1:1.0.0", "{\"status\":\"success\"}");
            assertFalse(ok);
        } finally {
            pub.close();
        }
    }

    @Test
    @Timeout(30)
    void close_isIdempotentOnUnconnectedProducer() {
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null);
        assertDoesNotThrow(pub::close);
        // Calling close twice should not throw either.
        assertDoesNotThrow(pub::close);
    }

    @Test
    @Timeout(30)
    void saslPath_unreachableBroker_isNotConnected() {
        // Exercises the SASL_SSL branch (user + password supplied). The
        // auto-truststore step fails because the host doesn't speak TLS,
        // but the constructor swallows that and continues; the probe
        // ultimately fails and connected ends up false.
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, "u", "p");
        try {
            assertFalse(pub.isConnected());
        } finally {
            pub.close();
        }
    }
}
