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
 *   <li>{@code odcs-json-schema-latest.json} (v3.2.0-capable) — from
 *       github.com/bitol-io/open-data-contract-standard {@code schema/}</li>
 * </ul>
 * Both declare JSON Schema draft 2019-09. To refresh, copy the newer file
 * from the standard repo and update the constant here.
 */
public final class SchemaValidator {

    static final String ODPS_SCHEMA = "/schemas/odps-json-schema-v1.0.0.json";
    static final String ODCS_SCHEMA = "/schemas/odcs-json-schema-latest.json";

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
                    violations.addAll(validateYaml(name, readAll(zis), ODPS_SCHEMA));
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
