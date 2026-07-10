package ai.jgp.gha.dataproduct.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * An append-only event recording the outcome of a single ODPS product's
 * synchronization to Zeenea. Published to the Kafka topic
 * {@code workbench.catalog.feedback} so the Libot Services backend (svc) and
 * the Workbench UI can surface "last synced to Zeenea" — and failures — per
 * artifact, instead of the outcome living only in the GitHub Actions log
 * (see #35; consumer side: ai.jgp.bitol.svc#758).
 *
 * <p>Serialized as an OORS {@code ObservabilityResults} envelope (#43) with a
 * single {@code results[]} entry, matching what the consumer
 * ({@code ZeeneaSyncStatusConsumerService.handleMessage}) parses: the sync
 * outcome is {@code results[0].status} ({@code pass}/{@code fail}) and
 * uploadId/tenant/catalog/error ride in {@code results[0].metadata}.
 *
 * <p>The event is keyed by {@code <id>:<version>} (see {@link #key()}). It is
 * emitted on both success and failure; {@code uploadId} may be absent on early
 * failures (e.g. the upload-URL request itself failed), and {@code error} is
 * present only when the sync failed.
 */
public class SyncStatusEvent {

    private final String id;
    private final String version;
    private final boolean success;
    private final String uploadId;
    private final String at;
    private final String tenant;
    private final String catalog;
    private final String error;

    public SyncStatusEvent(String id, String version, boolean success, String uploadId,
                           String at, String tenant, String catalog, String error) {
        this.id = id;
        this.version = version;
        this.success = success;
        this.uploadId = uploadId;
        this.at = at;
        this.tenant = tenant;
        this.catalog = catalog;
        this.error = error;
    }

    /** Kafka message key: {@code "<id>:<version>"}. */
    public String key() {
        return (id == null ? "" : id) + ":" + (version == null ? "" : version);
    }

    /** {@code "success"} or {@code "failed"}. */
    public String status() {
        return success ? "success" : "failed";
    }

    /**
     * Serialises the event to an OORS {@code ObservabilityResults} envelope
     * (#43). {@code uploadId}, {@code tenant} and {@code catalog} are omitted
     * from {@code results[0].metadata} when null; {@code error} is included
     * only on failure (and when non-null).
     */
    public String toJson() {
        JsonObject metadata = new JsonObject();
        if (uploadId != null) {
            metadata.addProperty("uploadId", uploadId);
        }
        if (tenant != null) {
            metadata.addProperty("tenant", tenant);
        }
        if (catalog != null) {
            metadata.addProperty("catalog", catalog);
        }
        if (!success && error != null) {
            metadata.addProperty("error", error);
        }

        JsonObject result = new JsonObject();
        result.addProperty("id", key());
        result.addProperty("type", "state");
        result.addProperty("name", "Zeenea sync");
        result.addProperty("status", success ? "pass" : "fail");
        result.add("metadata", metadata);

        JsonArray results = new JsonArray();
        results.add(result);

        JsonObject source = new JsonObject();
        source.addProperty("process", "zeenea-sync");
        source.addProperty("vendor", "dispatcher");

        JsonObject o = new JsonObject();
        o.addProperty("apiVersion", "v0.1.0");
        o.addProperty("kind", "ObservabilityResults");
        o.addProperty("observedAt", at);
        o.add("source", source);
        o.add("results", results);
        return o.toString();
    }
}
