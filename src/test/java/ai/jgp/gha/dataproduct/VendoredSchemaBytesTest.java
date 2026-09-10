/*
 * Copyright (C) 2026 JGP.ai / Oplo LLC.
 * All rights reserved.
 */
package ai.jgp.gha.dataproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vendored Bitol schemas to the released upstream blobs (#84).
 *
 * <h2>Why the bytes, and not the label</h2>
 *
 * <p>{@code odcs-json-schema-latest.json} was {@code e5e6ef95…} — a body that predated ODCS
 * v3.2.0 <b>entirely</b>: 35 server types instead of 44, no {@code $defs.Port}, and none of the
 * v3.2.0 feature set. And all the while its {@code properties.apiVersion.default} read
 * {@code "v3.2.0"}, exactly as the released file does.
 *
 * <p>That is the whole lesson. <b>The label was right and the body was two editions behind</b>, so
 * every assertion anyone would naturally reach for — the declared version, the file name — passed
 * happily. The same fault has now been found three times across the fleet, twice with the label
 * and the content disagreeing in opposite directions. So this asserts the blob hash, and pins a
 * few features whose absence was the actual damage.
 *
 * <p>Hashes are the released upstream blobs, pinned: {@code bitol-io/open-data-contract-standard}
 * at tag {@code v3.2.0} and {@code bitol-io/open-data-product-standard} at tag {@code v1.1.0},
 * both tagged 2026-09-08.
 */
@DisplayName("#84: the vendored schemas are the released upstream bytes")
class VendoredSchemaBytesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] bytes(String resource) throws Exception {
        try (InputStream in = VendoredSchemaBytesTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the classpath");
            return in.readAllBytes();
        }
    }

    /** Same construction as {@code git hash-object}: sha1 over {@code "blob <len>\0"} + content. */
    private static String gitBlobHash(byte[] content) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
        sha1.update(content);
        StringBuilder hex = new StringBuilder();
        for (byte b : sha1.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static JsonNode schema(String resource) throws Exception {
        return JSON.readTree(bytes(resource));
    }

    @Nested
    @DisplayName("the released blobs, by hash")
    class Blobs {

        @Test
        @DisplayName("odcs-json-schema-latest.json is the released v3.2.0 blob")
        void odcsLatestIsTheReleasedBlob() throws Exception {
            assertEquals("f934075459fce0a20a3c8260dd2076872f91e2b9",
                    gitBlobHash(bytes(SchemaValidator.ODCS_SCHEMA)),
                    "the ODCS alias is not the bytes upstream released at tag v3.2.0. This "
                            + "validator sits on the publish path, so a stale copy here rejects "
                            + "artifacts the standard permits.");
        }

        @Test
        @DisplayName("odps-json-schema-v1.1.0.json is the released v1.1.0 blob")
        void odpsV110IsTheReleasedBlob() throws Exception {
            assertEquals("3384d244d3a81182189f123b3b374b4241f70be1",
                    gitBlobHash(bytes(SchemaValidator.ODPS_SCHEMA_V1_1_0)));
        }

        @Test
        @DisplayName("odps-json-schema-v1.0.0.json is untouched — it was already released bytes")
        void odpsV100IsUnchanged() throws Exception {
            assertEquals("40b46c694d60f70313b40deb2b49bbaea8b29299",
                    gitBlobHash(bytes(SchemaValidator.ODPS_SCHEMA_V1_0_0)),
                    "#84 deliberately did not touch this file. If it moved, something re-synced "
                            + "more than it meant to.");
        }
    }

    @Nested
    @DisplayName("what the pre-v3.2.0 body was missing")
    class ContentThatWasAbsent {

        @Test
        @DisplayName("the label alone proves nothing — it read v3.2.0 on the stale body too")
        void theLabelIsNotEvidence() throws Exception {
            assertEquals("v3.2.0",
                    schema(SchemaValidator.ODCS_SCHEMA).path("properties").path("apiVersion")
                            .path("default").asText(),
                    "this assertion is here to be documented, not to catch anything: the body "
                            + "replaced by #84 also declared v3.2.0. Never accept it as evidence "
                            + "that a vendored copy is current.");
        }

        @Test
        @DisplayName("44 server types, not the 35 the stale body carried")
        void carriesAllFortyFourServerTypes() throws Exception {
            JsonNode types = schema(SchemaValidator.ODCS_SCHEMA)
                    .path("$defs").path("Server").path("properties").path("type").path("enum");
            assertTrue(types.isArray(), "Server.type.enum is missing");
            assertEquals(44, types.size(),
                    "the pre-v3.2.0 body had 35. This is the cheapest fingerprint that tells two "
                            + "ODCS editions apart — necessary, but never sufficient on its own.");
        }

        @Test
        @DisplayName("the RFC-0057 and RFC-0059 server types are all present")
        void carriesTheServerTypesAddedByRfcs() throws Exception {
            JsonNode types = schema(SchemaValidator.ODCS_SCHEMA)
                    .path("$defs").path("Server").path("properties").path("type").path("enum");
            StringBuilder all = new StringBuilder();
            types.forEach(t -> all.append(t.asText()).append(' '));
            for (String t : new String[] {"teradata", "ingres", "vectorwise", "versant", "poet",
                    "fastobjects", "btrieve", "exasol", "iceberg"}) {
                assertTrue(all.toString().contains(t),
                        "server type '" + t + "' is missing — the vendored copy predates the RFC "
                                + "that added it: " + all);
            }
        }

        @Test
        @DisplayName("$defs.Port exists — the stale body had no such definition at all")
        void carriesThePortDefinition() throws Exception {
            assertFalse(schema(SchemaValidator.ODCS_SCHEMA).path("$defs").path("Port").isMissingNode(),
                    "$defs.Port is absent, which was one of the clearest markers that the "
                            + "vendored body predated v3.2.0");
        }

        @Test
        @DisplayName("the id pattern is RFC-0026a's denylist, so namespaced ids validate")
        void carriesTheWidenedIdPattern() throws Exception {
            assertEquals("^[^\\s.#/\\\\@!%&^]+$",
                    schema(SchemaValidator.ODCS_SCHEMA).path("$defs").path("StableId")
                            .path("pattern").asText(),
                    "the stale body carried the allowlist ^[A-Za-z0-9_-]+$, which rejects the "
                            + "colon-namespaced ids catalogs emit (fdir:ISU:TAD) and any "
                            + "non-ASCII id. The release replaced it with a denylist, which is "
                            + "strictly wider — nothing that validated before can stop validating.");
        }

        @Test
        @DisplayName("ODPS SBOM gains the three RFC-0061 properties, and stays closed")
        void odpsSbomCarriesRfc0061() throws Exception {
            JsonNode sbom = schema(SchemaValidator.ODPS_SCHEMA_V1_1_0).path("$defs").path("SBOM");
            for (String prop : new String[] {"tags", "customProperties", "authoritativeDefinitions"}) {
                assertFalse(sbom.path("properties").path(prop).isMissingNode(),
                        "SBOM." + prop + " is missing (RFC-0061)");
            }
            assertFalse(sbom.path("additionalProperties").asBoolean(true),
                    "SBOM must stay closed — only its property list grew, so every valid v1.1.0 "
                            + "document stays valid");
        }
    }
}
