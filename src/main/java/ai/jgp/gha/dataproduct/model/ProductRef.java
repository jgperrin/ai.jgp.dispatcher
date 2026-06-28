package ai.jgp.gha.dataproduct.model;

/**
 * The identifying coordinates of an ODPS product — its {@code id} and
 * {@code version} — parsed from a product YAML. Used as the key for the
 * Zeenea sync-status event (see {@link SyncStatusEvent} and #35).
 *
 * <p>Either field may be null/blank when the product YAML could not be
 * parsed; callers should guard with {@link #hasId()} before emitting a
 * status event keyed on it.
 */
public record ProductRef(String id, String version) {

    /** True when an id is present, i.e. this ref can key a status event. */
    public boolean hasId() {
        return id != null && !id.isBlank();
    }
}
