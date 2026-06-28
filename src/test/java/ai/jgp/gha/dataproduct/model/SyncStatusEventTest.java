package ai.jgp.gha.dataproduct.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyncStatusEvent} — the Kafka key and the JSON shape
 * on success and failure (#35).
 */
class SyncStatusEventTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void key_isIdColonVersion() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", null);
        assertEquals("prod-1:1.2.3", e.key());
        assertEquals("success", e.status());
    }

    @Test
    void successEvent_hasAllFields_andOmitsError() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", "ignored-on-success");
        JsonObject o = parse(e.toJson());

        assertEquals("prod-1", o.get("id").getAsString());
        assertEquals("1.2.3", o.get("version").getAsString());
        assertEquals("success", o.get("status").getAsString());
        assertEquals("up-9", o.get("uploadId").getAsString());
        assertEquals("2026-06-27T10:00:00Z", o.get("at").getAsString());
        assertEquals("acme", o.get("tenant").getAsString());
        assertEquals("default", o.get("catalog").getAsString());
        // error is never emitted on success, even if a message was supplied.
        assertFalse(o.has("error"), "error must be absent on success");
    }

    @Test
    void failureEvent_includesError() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", false, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", "HTTP 403: SignatureDoesNotMatch");
        JsonObject o = parse(e.toJson());

        assertEquals("failed", o.get("status").getAsString());
        assertEquals("HTTP 403: SignatureDoesNotMatch", o.get("error").getAsString());
    }

    @Test
    void uploadId_omittedWhenNull() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", false, null,
                "2026-06-27T10:00:00Z", "acme", "default", "early failure");
        JsonObject o = parse(e.toJson());

        assertFalse(o.has("uploadId"), "uploadId must be absent when null");
        assertTrue(o.has("error"));
    }

    @Test
    void tenantAndCatalog_omittedWhenNull() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", null, null, null);
        JsonObject o = parse(e.toJson());

        assertFalse(o.has("tenant"));
        assertFalse(o.has("catalog"));
    }

    @Test
    void key_toleratesNullCoordinates() {
        SyncStatusEvent e = new SyncStatusEvent(null, null, false, null,
                "2026-06-27T10:00:00Z", "acme", "default", "boom");
        assertEquals(":", e.key());
    }
}
