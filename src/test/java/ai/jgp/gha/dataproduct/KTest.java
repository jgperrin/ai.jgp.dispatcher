package ai.jgp.gha.dataproduct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KTest {

    @Test
    void versionIsSet() {
        assertNotNull(K.VERSION);
        assertFalse(K.VERSION.isBlank());
    }

    @Test
    void defaultCatalogIsDefault() {
        assertEquals("default", K.DEFAULT_CATALOG);
    }

    @Test
    void envVariableNamesAreSet() {
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
    void baseUrlTemplateContainsPlaceholder() {
        assertTrue(K.BASE_URL_TEMPLATE.contains("%s"));
        assertTrue(K.BASE_URL_TEMPLATE.startsWith("https://"));
    }

    @Test
    void uploadPathIsCorrect() {
        assertEquals("/api/synchronization/data-product-uploads", K.UPLOAD_PATH);
    }

    @Test
    void headersAreSet() {
        assertEquals("X-API-SECRET", K.HEADER_API_SECRET);
        assertEquals("Content-Type", K.HEADER_CONTENT_TYPE);
        assertNotNull(K.HEADER_KMS_ENCRYPTION);
        assertNotNull(K.HEADER_KMS_KEY_ID);
    }

    @Test
    void pollConfigIsReasonable() {
        assertTrue(K.POLL_INTERVAL_MS > 0);
        assertTrue(K.POLL_MAX_RETRIES > 0);
        // Total poll time should be at least 1 minute
        assertTrue((long) K.POLL_INTERVAL_MS * K.POLL_MAX_RETRIES >= 60000);
    }

    @Test
    void kafkaTopicIsSet() {
        assertEquals("controlcenter.spec.ingest", K.KAFKA_TOPIC_SPEC_INGEST);
    }
}
