package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SchemaValidator} (#46): valid ODPS/ODCS pass, each
 * schema violation is reported with the entry name, unparseable YAML and
 * unreadable ZIPs are violations (never exceptions).
 */
class SchemaValidatorTest {

    @TempDir
    Path tmp;

    private static final String VALID_ODPS = """
            apiVersion: v1.0.0
            kind: DataProduct
            id: imdb
            status: active
            name: IMDB
            version: 1.0.0
            """;

    // Missing required `status` — invalid under the v1.0.0 schema it declares.
    private static final String INVALID_ODPS = """
            apiVersion: v1.0.0
            kind: DataProduct
            id: imdb
            """;

    // #75 — a v1.1.0 product exercising the relaxation: the output port
    // carries only `name` (v1.0.0 also requires `version`), and there is no
    // top-level `status` (required in v1.0.0, optional in v1.1.0).
    private static final String VALID_ODPS_V110 = """
            apiVersion: v1.1.0
            kind: DataProduct
            id: imdb
            name: IMDB
            version: 1.1.0
            outputPorts:
              - name: bronze
            """;

    // #75 AC-4 — invalid *only* under v1.0.0's stricter rules: an input port
    // without `contractId` (required in v1.0.0, relaxed to name-only in
    // v1.1.0). This is the property a single permissive schema would lose.
    private static final String INVALID_ODPS_V100_STRICT_ONLY = """
            apiVersion: v1.0.0
            kind: DataProduct
            id: imdb
            status: active
            name: IMDB
            version: 1.0.0
            inputPorts:
              - name: source
            """;

    // #75 AC-3 — declares a version this validator has no schema for.
    private static final String UNKNOWN_API_VERSION_ODPS = """
            apiVersion: v9.9.9
            kind: DataProduct
            id: imdb
            """;

    // #75 AC-3 — no apiVersion at all: must not fall through to the newest,
    // most permissive schema.
    private static final String NO_API_VERSION_ODPS = """
            kind: DataProduct
            id: imdb
            name: IMDB
            """;

    // #75 AC-7 — ODCS regression: a v3.2.0 contract still validates against
    // the aliased `-latest` schema.
    private static final String VALID_ODCS_V320 = """
            apiVersion: v3.2.0
            kind: DataContract
            id: c3
            version: 1.0.0
            status: active
            """;

    private static final String VALID_ODCS = """
            apiVersion: v3.0.2
            kind: DataContract
            id: c1
            version: 1.0.0
            status: active
            """;

    // Missing required `version` and `status`.
    private static final String INVALID_ODCS = """
            apiVersion: v3.0.2
            kind: DataContract
            id: c1
            """;

    private Path zipOf(Map<String, String> entries) throws IOException {
        Path zip = tmp.resolve("bundle-" + Math.abs(entries.hashCode()) + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    // A WB-authored v3.1.0 contract shape (#51): logicalType/physicalType
    // properties, array team — must pass with the `latest` vendored schema.
    private static final String VALID_ODCS_V310 = """
            apiVersion: v3.1.0
            kind: DataContract
            id: c2
            version: 1.0.0
            status: active
            schema:
              - name: t1
                properties:
                  - name: id
                    logicalType: integer
                    physicalType: INTEGER
                    required: true
            team:
              - username: jgp
                role: owner
            """;

    @Test
    void wbAuthoredV310ContractPasses() throws IOException {
        Path zip = zipOf(Map.of("podem/c2.odcs.yaml", VALID_ODCS_V310));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void validOdpsAndOdcsPass() throws IOException {
        Path zip = zipOf(Map.of(
                "podem/imdb.odps.yaml", VALID_ODPS,
                "podem/c1.odcs.yaml", VALID_ODCS));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void invalidOdpsIsReportedWithEntryName() throws IOException {
        Path zip = zipOf(Map.of("podem/imdb.odps.yaml", INVALID_ODPS));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertFalse(violations.isEmpty());
        for (String v : violations) {
            assertTrue(v.startsWith("podem/imdb.odps.yaml: "), v);
        }
        // The missing required field is named in the report.
        assertTrue(String.join("\n", violations).contains("status"),
                () -> String.join("\n", violations));
    }

    @Test
    void invalidBundledOdcsIsReported() throws IOException {
        Path zip = zipOf(Map.of(
                "podem/imdb.odps.yaml", VALID_ODPS,
                "podem/c1.odcs.yaml", INVALID_ODCS));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertFalse(violations.isEmpty());
        for (String v : violations) {
            assertTrue(v.startsWith("podem/c1.odcs.yaml: "), v);
        }
    }

    @Test
    void unparseableYamlIsAViolation() throws IOException {
        Path zip = zipOf(Map.of("podem/broken.odps.yaml", "this: is: not: yaml {{{"));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("broken.odps.yaml"), violations.get(0));
        assertTrue(violations.get(0).contains("unparseable YAML"), violations.get(0));
    }

    @Test
    void nonZipFileIsAViolationNotAnException() throws IOException {
        Path notAZip = tmp.resolve("fake.zip");
        Files.writeString(notAZip, "z");
        assertFalse(SchemaValidator.validateZip(notAZip).isEmpty());
    }

    @Test
    void odpsV110ProductPasses() throws IOException {
        Path zip = zipOf(Map.of("podem/imdb.odps.yaml", VALID_ODPS_V110));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void v100ProductStillFailsOnV100StrictRules() throws IOException {
        Path zip = zipOf(Map.of("podem/imdb.odps.yaml", INVALID_ODPS_V100_STRICT_ONLY));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertFalse(violations.isEmpty(),
                "a v1.0.0 input port without contractId must still be rejected");
        assertTrue(String.join("\n", violations).contains("contractId"),
                () -> String.join("\n", violations));
    }

    @Test
    void theSameDocumentPassesWhenItDeclaresV110() throws IOException {
        // Same shape as the v1.0.0 negative fixture, but declaring v1.1.0 —
        // proves the dispatch is on the declared version, not on the content.
        Path zip = zipOf(Map.of("podem/imdb.odps.yaml",
                INVALID_ODPS_V100_STRICT_ONLY.replace("apiVersion: v1.0.0", "apiVersion: v1.1.0")));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void unknownApiVersionIsRejectedAndListsTheKnownOnes() throws IOException {
        Path zip = zipOf(Map.of("podem/imdb.odps.yaml", UNKNOWN_API_VERSION_ODPS));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertEquals(1, violations.size(), () -> String.join("\n", violations));
        String v = violations.get(0);
        assertTrue(v.startsWith("podem/imdb.odps.yaml: "), v);
        assertTrue(v.contains("v9.9.9"), v);
        assertTrue(v.contains("v0.9.0") && v.contains("v1.0.0") && v.contains("v1.1.0"), v);
    }

    @Test
    void missingApiVersionDoesNotFallThroughToTheNewestSchema() throws IOException {
        Path zip = zipOf(Map.of("podem/imdb.odps.yaml", NO_API_VERSION_ODPS));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertEquals(1, violations.size(), () -> String.join("\n", violations));
        assertTrue(violations.get(0).contains("apiVersion"), violations.get(0));
    }

    @Test
    void odcsV320ContractStillPasses() throws IOException {
        Path zip = zipOf(Map.of("podem/c3.odcs.yaml", VALID_ODCS_V320));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void everyKnownOdpsVersionResolvesToAVendoredSchema() {
        for (String version : SchemaValidator.knownOdpsApiVersions()) {
            String resource = SchemaValidator.odpsSchemaFor(version);
            assertTrue(SchemaValidator.class.getResource(resource) != null,
                    () -> version + " maps to " + resource + ", which is not on the classpath");
        }
    }

    @Test
    void nonSpecEntriesAreIgnored() throws IOException {
        Path zip = zipOf(Map.of(
                "podem/imdb.odps.yaml", VALID_ODPS,
                "README.md", "# not a spec",
                "podem/.workbench.yaml", "orgId: 3f2b8c1e-9a4d-4e7f-b6a5-1c2d3e4f5a6b"));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
}
