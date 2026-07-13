package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link KafkaPublisher}.
 *
 * <p>Tests focus on the behaviour we can exercise without a real
 * broker: the constructor's connectivity probe must fail cleanly
 * (returning {@code isConnected() == false}) when the broker is
 * unreachable, {@code publishSpec} must return {@code false} in that
 * disconnected state, {@code close} must be safe to call, and the #54
 * retry budget must survive transient failures. Unreachable-broker
 * tests inject a tiny {@link KafkaPublisher.Tuning} so the retry
 * budget doesn't slow the suite.
 *
 * <p>The Kafka producer's full SASL_SSL handshake is not exercised
 * here — that path requires a live broker and is out of scope per the
 * user story.
 */
class KafkaPublisherTest {

    /** Bogus host that resolves but never accepts connections. */
    private static final String UNREACHABLE_PLAINTEXT = "127.0.0.1:1";

    /** Small timeouts + a 2-attempt budget so failure paths stay fast. */
    private static final KafkaPublisher.Tuning FAST =
            new KafkaPublisher.Tuning(500, 500, 1000, 2, 1, 2, 10);

    @Test
    @Timeout(30)
    void unreachableBroker_plaintext_isNotConnected() {
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null, FAST);
        try {
            assertFalse(pub.isConnected());
        } finally {
            pub.close();
        }
    }

    @Test
    @Timeout(30)
    void publishSpec_returnsFalse_whenBrokerUnreachable() {
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null, FAST);
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
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null, FAST);
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
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, null, null, FAST);
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
        KafkaPublisher pub = new KafkaPublisher(UNREACHABLE_PLAINTEXT, "u", "p", FAST);
        try {
            assertFalse(pub.isConnected());
        } finally {
            pub.close();
        }
    }

    // --- #54 retry budget ---------------------------------------------------

    @Test
    void retry_transientFailure_succeedsWithinBudget() {
        // A cold-producer metadata timeout on the first attempt must not
        // lose the send: attempt 1 and 2 fail, attempt 3 succeeds.
        AtomicInteger calls = new AtomicInteger();
        boolean ok = KafkaPublisher.retry("test-op", 3, 1,
                () -> calls.incrementAndGet() >= 3);
        assertTrue(ok);
        assertEquals(3, calls.get());
    }

    @Test
    void retry_firstAttemptSucceeds_runsOnce() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = KafkaPublisher.retry("test-op", 3, 1,
                () -> calls.incrementAndGet() > 0);
        assertTrue(ok);
        assertEquals(1, calls.get());
    }

    @Test
    void retry_budgetExhausted_returnsFalse() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = KafkaPublisher.retry("test-op", 3, 1,
                () -> { calls.incrementAndGet(); return false; });
        assertFalse(ok);
        assertEquals(3, calls.get());
    }

    @Test
    void productionTuning_blocksWellAboveFiveSeconds() {
        // #54: the live loss was a 5s max.block.ms metadata timeout on a GH
        // runner. The production tuning must block well above that and carry
        // a multi-attempt budget.
        KafkaPublisher.Tuning t = KafkaPublisher.Tuning.PRODUCTION;
        assertTrue(t.maxBlockMs() > 5000, "max.block.ms must exceed the 5s that lost IMDb");
        assertTrue(t.attempts() >= 2, "sends must be retried");
    }
}
