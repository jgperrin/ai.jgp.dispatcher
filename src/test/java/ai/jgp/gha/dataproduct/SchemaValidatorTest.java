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

    // Missing required `status`, and apiVersion outside the enum.
    private static final String INVALID_ODPS = """
            apiVersion: v9.9.9
            kind: DataProduct
            id: imdb
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
    void nonSpecEntriesAreIgnored() throws IOException {
        Path zip = zipOf(Map.of(
                "podem/imdb.odps.yaml", VALID_ODPS,
                "README.md", "# not a spec"));
        List<String> violations = SchemaValidator.validateZip(zip);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
}
