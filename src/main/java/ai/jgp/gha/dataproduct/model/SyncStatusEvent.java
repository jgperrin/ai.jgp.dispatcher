package ai.jgp.gha.dataproduct.model;

import com.google.gson.JsonObject;

/**
 * An append-only event recording the outcome of a single ODPS product's
 * synchronization to Zeenea. Published to the Kafka topic
 * {@code controlcenter.spec.status} so the Libot Services backend (svc) and
 * the Workbench UI can surface "last synced to Zeenea" — and failures — per
 * artifact, instead of the outcome living only in the GitHub Actions log
 * (see #35; consumer side: ai.jgp.bitol.svc#758).
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
     * Serialises the event to JSON. {@code uploadId}, {@code tenant} and
     * {@code catalog} are omitted when null; {@code error} is included only on
     * failure (and when non-null).
     */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("version", version);
        o.addProperty("status", status());
        if (uploadId != null) {
            o.addProperty("uploadId", uploadId);
        }
        o.addProperty("at", at);
        if (tenant != null) {
            o.addProperty("tenant", tenant);
        }
        if (catalog != null) {
            o.addProperty("catalog", catalog);
        }
        if (!success && error != null) {
            o.addProperty("error", error);
        }
        return o.toString();
    }
}
