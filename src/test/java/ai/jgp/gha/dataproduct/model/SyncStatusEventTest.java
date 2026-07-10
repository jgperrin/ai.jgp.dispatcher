package ai.jgp.gha.dataproduct.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyncStatusEvent} — the Kafka key and the OORS
 * {@code ObservabilityResults} envelope on success and failure (#35, #43).
 * The shape must match what the bitol.svc consumer
 * ({@code ZeeneaSyncStatusConsumerService.handleMessage}) parses.
 */
class SyncStatusEventTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject firstResult(JsonObject envelope) {
        return envelope.getAsJsonArray("results").get(0).getAsJsonObject();
    }

    @Test
    void key_isIdColonVersion() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", null);
        assertEquals("prod-1:1.2.3", e.key());
        assertEquals("success", e.status());
    }

    @Test
    void envelope_isOors() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", null);
        JsonObject o = parse(e.toJson());

        assertEquals("v0.1.0", o.get("apiVersion").getAsString());
        assertEquals("ObservabilityResults", o.get("kind").getAsString());
        assertEquals("2026-06-27T10:00:00Z", o.get("observedAt").getAsString());
        JsonObject source = o.getAsJsonObject("source");
        assertEquals("zeenea-sync", source.get("process").getAsString());
        assertEquals("dispatcher", source.get("vendor").getAsString());
        assertEquals(1, o.getAsJsonArray("results").size());
    }

    @Test
    void successEvent_hasAllFields_andOmitsError() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", "ignored-on-success");
        JsonObject r = firstResult(parse(e.toJson()));

        assertEquals("prod-1:1.2.3", r.get("id").getAsString());
        assertEquals("state", r.get("type").getAsString());
        assertEquals("Zeenea sync", r.get("name").getAsString());
        assertEquals("pass", r.get("status").getAsString());
        JsonObject md = r.getAsJsonObject("metadata");
        assertEquals("up-9", md.get("uploadId").getAsString());
        assertEquals("acme", md.get("tenant").getAsString());
        assertEquals("default", md.get("catalog").getAsString());
        // error is never emitted on success, even if a message was supplied.
        assertFalse(md.has("error"), "error must be absent on success");
    }

    @Test
    void failureEvent_includesError() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", false, "up-9",
                "2026-06-27T10:00:00Z", "acme", "default", "HTTP 403: SignatureDoesNotMatch");
        JsonObject r = firstResult(parse(e.toJson()));

        assertEquals("fail", r.get("status").getAsString());
        assertEquals("HTTP 403: SignatureDoesNotMatch",
                r.getAsJsonObject("metadata").get("error").getAsString());
    }

    @Test
    void uploadId_omittedWhenNull() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", false, null,
                "2026-06-27T10:00:00Z", "acme", "default", "early failure");
        JsonObject md = firstResult(parse(e.toJson())).getAsJsonObject("metadata");

        assertFalse(md.has("uploadId"), "uploadId must be absent when null");
        assertTrue(md.has("error"));
    }

    @Test
    void tenantAndCatalog_omittedWhenNull() {
        SyncStatusEvent e = new SyncStatusEvent("prod-1", "1.2.3", true, "up-9",
                "2026-06-27T10:00:00Z", null, null, null);
        JsonObject md = firstResult(parse(e.toJson())).getAsJsonObject("metadata");

        assertFalse(md.has("tenant"));
        assertFalse(md.has("catalog"));
    }

    @Test
    void key_toleratesNullCoordinates() {
        SyncStatusEvent e = new SyncStatusEvent(null, null, false, null,
                "2026-06-27T10:00:00Z", "acme", "default", "boom");
        assertEquals(":", e.key());
    }
}
