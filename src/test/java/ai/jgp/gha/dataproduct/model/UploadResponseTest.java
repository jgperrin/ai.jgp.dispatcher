package ai.jgp.gha.dataproduct.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UploadResponse#fromJson(String)}.
 *
 * <p>Verifies the JSON parsing pulls the right fields out of the
 * Zeenea-shaped upload response, including the nested
 * {@code uploadParameters.headers} block with the AWS KMS headers.
 */
class UploadResponseTest {

    private static final String SAMPLE = """
            {
              "id": "upload-123",
              "maximumFileSizeInBytes": 26214400,
              "uploadParameters": {
                "url": "https://s3.example.com/upload/upload-123",
                "headers": {
                  "x-amz-server-side-encryption": "aws:kms",
                  "x-amz-server-side-encryption-aws-kms-key-id": "arn:aws:kms:eu-west-1:1:key/abc"
                }
              }
            }
            """;

    @Test
    void fromJson_parsesAllFields() {
        UploadResponse r = UploadResponse.fromJson(SAMPLE);

        assertEquals("upload-123", r.getId());
        assertEquals("https://s3.example.com/upload/upload-123", r.getUrl());
        assertEquals(26214400L, r.getMaxFileSize());
        assertEquals("aws:kms", r.getKmsEncryption());
        assertEquals("arn:aws:kms:eu-west-1:1:key/abc", r.getKmsKeyId());
    }

    @Test
    void fromJson_throwsOnMissingId() {
        String bad = "{\"maximumFileSizeInBytes\":1,\"uploadParameters\":{\"url\":\"u\",\"headers\":{}}}";
        assertThrows(Exception.class, () -> UploadResponse.fromJson(bad));
    }

    @Test
    void fromJson_throwsOnInvalidJson() {
        assertThrows(Exception.class, () -> UploadResponse.fromJson("not json"));
    }

    @Test
    void fromJson_throwsOnMissingUploadParameters() {
        String bad = "{\"id\":\"x\",\"maximumFileSizeInBytes\":1}";
        assertThrows(Exception.class, () -> UploadResponse.fromJson(bad));
    }
}
