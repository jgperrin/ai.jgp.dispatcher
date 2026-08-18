package ai.jgp.gha.dataproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates ODPS/ODCS descriptors against the vendored Bitol JSON schemas
 * (#46) before anything leaves the repo — a schema-invalid spec fails the
 * GHA run instead of surfacing later as a Zeenea processing failure or a
 * Control Center {@code event_log} processingError.
 *
 * <p>Schemas are classpath resources under {@code /schemas} (no network at
 * run time):
 * <ul>
 *   <li>{@code odps-json-schema-v1.0.0.json} — from
 *       github.com/bitol-io/open-data-product-standard {@code schema/}</li>
 *   <li>{@code odps-json-schema-v1.1.0.json} (#75) — same repo, branch
 *       {@code dev-v1.1.0} at commit {@code 137e01f} (2026-06-30), byte for
 *       byte the copy vendored by {@code ai.jgp.bitol.svc#1071} so the two
 *       repos cannot disagree about what v1.1.0 is</li>
 *   <li>{@code odcs-json-schema-latest.json} (v3.2.0-capable) — from
 *       github.com/bitol-io/open-data-contract-standard {@code schema/}</li>
 * </ul>
 * All declare JSON Schema draft 2019-09. To refresh, copy the newer file
 * from the standard repo and update the constant here.
 *
 * <p><b>Both v1.1.0 and v3.2.0 are drafts</b> — ODPS v1.1.0 is vendored from
 * an unmerged {@code dev-} branch, and the ODCS alias follows upstream's
 * {@code -latest} pointer, which upstream already aims at the 3.2.0 draft.
 * Accepting them is a deliberate choice, not an accident: this validator sits
 * on the publish path for artifacts the Workbench itself authors.
 *
 * <p><b>ODPS dispatches on the declared {@code apiVersion}; ODCS stays
 * aliased</b> (#75). ODPS v1.1.0 <em>relaxes</em> requirements — input and
 * output ports need only {@code name}, top-level {@code status} is optional —
 * so validating every product against the newest schema would silently pass a
 * genuinely invalid v1.0.0 product. Each ODPS document is therefore checked
 * against the standard it actually claims to conform to. The ODCS side keeps
 * the single {@code -latest} alias because dispatching there would mean
 * vendoring all eight versions in its enum; the asymmetry is on the record
 * here rather than left to be inferred.
 */
public final class SchemaValidator {

    static final String ODPS_SCHEMA_V1_0_0 = "/schemas/odps-json-schema-v1.0.0.json";
    static final String ODPS_SCHEMA_V1_1_0 = "/schemas/odps-json-schema-v1.1.0.json";
    static final String ODCS_SCHEMA = "/schemas/odcs-json-schema-latest.json";

    // Declared ODPS apiVersion -> vendored schema. v0.9.0 and v1.0.0 both
    // resolve to the v1.0.0 file: its own enum already covers both, and it is
    // the stricter of the two, which is the point of dispatching at all.
    private static final Map<String, String> ODPS_SCHEMA_BY_API_VERSION = Map.of(
            "v0.9.0", ODPS_SCHEMA_V1_0_0,
            "v1.0.0", ODPS_SCHEMA_V1_0_0,
            "v1.1.0", ODPS_SCHEMA_V1_1_0);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    // #70 — the vendored ODCS schema's named server-type definitions
    // (ApiServer, AthenaServer, …) are read as unknown-but-irrelevant schema
    // keywords when the schema loads, and networknt logs one WARNING each
    // (~36 lines of noise per GHA run). Raise that logger to SEVERE. The
    // strong static reference is required: JUL holds loggers weakly, so
    // without it the level could be garbage-collected away mid-run.
    private static final java.util.logging.Logger NETWORKNT_UNKNOWN_KEYWORD_LOGGER =
            java.util.logging.Logger.getLogger("com.networknt.schema.UnknownKeywordFactory");
    static {
        NETWORKNT_UNKNOWN_KEYWORD_LOGGER.setLevel(java.util.logging.Level.SEVERE);
    }

    private SchemaValidator() {
    }

    /**
     * Validates every {@code *.odps.yaml} and {@code *.odcs.yaml} entry of a
     * spec ZIP (the exact bundle that would be uploaded).
     *
     * @return violations as {@code "<entry>: <message>"} lines; empty when
     *         everything validates. An unreadable ZIP or unparseable YAML is
     *         itself a violation, never an exception.
     */
    public static List<String> validateZip(Path zipPath) {
        List<String> violations = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            boolean sawEntry = false;
            while ((entry = zis.getNextEntry()) != null) {
                sawEntry = true;
                String name = entry.getName();
                if (name.endsWith(".odps.yaml")) {
                    violations.addAll(validateOdps(name, readAll(zis)));
                } else if (name.endsWith(".odcs.yaml")) {
                    violations.addAll(validateYaml(name, readAll(zis), ODCS_SCHEMA));
                }
            }
            if (!sawEntry) {
                violations.add(zipPath + ": not a readable ZIP archive (no entries)");
            }
        } catch (IOException e) {
            violations.add(zipPath + ": could not read ZIP: " + e.getMessage());
        }
        return violations;
    }

    /**
     * Validates one ODPS descriptor against the schema for the version it
     * declares (#75).
     *
     * <p>A document that declares no {@code apiVersion}, or one this
     * validator has no schema for, is a violation — never a fall-through to
     * the newest and most permissive schema, which would let an invalid
     * v1.0.0 product publish silently.
     */
    static List<String> validateOdps(String name, String yaml) {
        JsonNode node;
        try {
            node = YAML.readTree(yaml);
        } catch (Exception e) {
            return List.of(name + ": unparseable YAML: " + e.getMessage());
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of(name + ": empty document");
        }
        JsonNode declared = node.get("apiVersion");
        if (declared == null || !declared.isTextual() || declared.asText().isBlank()) {
            return List.of(name + ": missing or unreadable apiVersion — an ODPS product must"
                    + " declare which version of the standard it conforms to; this validator"
                    + " knows " + String.join(", ", knownOdpsApiVersions()));
        }
        String version = declared.asText();
        String schemaResource = ODPS_SCHEMA_BY_API_VERSION.get(version);
        if (schemaResource == null) {
            return List.of(name + ": unsupported ODPS apiVersion \"" + version + "\" — this"
                    + " validator knows " + String.join(", ", knownOdpsApiVersions()));
        }
        return validate(name, node, schemaResource);
    }

    /** The ODPS versions this validator has a vendored schema for, ascending. */
    static List<String> knownOdpsApiVersions() {
        return ODPS_SCHEMA_BY_API_VERSION.keySet().stream().sorted().toList();
    }

    /** The vendored schema resource backing a declared ODPS version, or null. */
    static String odpsSchemaFor(String apiVersion) {
        return ODPS_SCHEMA_BY_API_VERSION.get(apiVersion);
    }

    /** Validates one descriptor's YAML text against the named schema resource. */
    static List<String> validateYaml(String name, String yaml, String schemaResource) {
        JsonNode node;
        try {
            node = YAML.readTree(yaml);
        } catch (Exception e) {
            return List.of(name + ": unparseable YAML: " + e.getMessage());
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of(name + ": empty document");
        }
        return validate(name, node, schemaResource);
    }

    private static List<String> validate(String name, JsonNode node, String schemaResource) {
        Set<ValidationMessage> messages = schema(schemaResource).validate(node);
        List<String> out = new ArrayList<>(messages.size());
        for (ValidationMessage m : messages) {
            out.add(name + ": " + m.getMessage());
        }
        return out;
    }

    private static JsonSchema schema(String resource) {
        try (InputStream in = SchemaValidator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("schema resource missing from classpath: " + resource);
            }
            JsonNode schemaNode = JSON.readTree(in);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909)
                    .getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load schema " + resource, e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
