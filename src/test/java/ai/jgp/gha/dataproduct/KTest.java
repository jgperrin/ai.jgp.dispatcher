package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for the {@link K} constants holder.
 *
 * <p>Verifies that the constants exposed by {@code K} are wired with
 * the values the rest of the dispatcher relies on (env var names, URL
 * template, Kafka topic). This also gives JaCoCo a class to instrument
 * and confirms the JUnit + JaCoCo wiring is working end-to-end.
 */
class KTest {

    @Test
    void version_isNotBlank() {
        assertNotNull(K.VERSION);
        assertFalse(K.VERSION.isBlank());
    }

    @Test
    void baseUrlTemplate_formatsTenant() {
        String url = String.format(K.BASE_URL_TEMPLATE, "acme");
        assertEquals("https://acme.zeenea.app", url);
    }

    @Test
    void uploadPath_startsWithSlash() {
        assertTrue(K.UPLOAD_PATH.startsWith("/"));
    }

    @Test
    void kafkaTopic_isControlCenterIngest() {
        assertEquals("controlcenter.dataproduct.descriptors", K.KAFKA_TOPIC_DESCRIPTORS);
    }

    @Test
    void envVarNames_areStable() {
        assertEquals("ZEENEA_FILE", K.ENV_FILE);
        assertEquals("ZEENEA_DIR", K.ENV_DIR);
        assertEquals("ZEENEA_TENANT", K.ENV_TENANT);
        assertEquals("ZEENEA_API_KEY", K.ENV_API_KEY);
        assertEquals("ZEENEA_CATALOG", K.ENV_CATALOG);
        assertEquals("ZEENEA_URL", K.ENV_URL);
        assertEquals("KAFKA_BROKER_URL", K.ENV_KAFKA_BROKER);
        assertEquals("KAFKA_USERNAME", K.ENV_KAFKA_USER);
        assertEquals("KAFKA_PASSWORD", K.ENV_KAFKA_PASSWORD);
    }

    @Test
    void defaultCatalog_isDefault() {
        assertEquals("default", K.DEFAULT_CATALOG);
    }
}
