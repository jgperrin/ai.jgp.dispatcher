package ai.jgp.gha.dataproduct.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UploadResponseTest {

    @Test
    void fromJson_parsesAllFields() {
        String json = """
                {
                  "id": "upload-123",
                  "maximumFileSizeInBytes": 52428800,
                  "uploadParameters": {
                    "url": "https://s3.example.com/bucket/upload-123",
                    "headers": {
                      "x-amz-server-side-encryption": "aws:kms",
                      "x-amz-server-side-encryption-aws-kms-key-id": "arn:aws:kms:eu-west-1:123:key/abc"
                    }
                  }
                }
                """;

        UploadResponse response = UploadResponse.fromJson(json);

        assertEquals("upload-123", response.getId());
        assertEquals("https://s3.example.com/bucket/upload-123", response.getUrl());
        assertEquals("aws:kms", response.getKmsEncryption());
        assertEquals("arn:aws:kms:eu-west-1:123:key/abc", response.getKmsKeyId());
        assertEquals(52428800L, response.getMaxFileSize());
    }

    @Test
    void fromJson_handlesLargeFileSize() {
        String json = """
                {
                  "id": "upload-456",
                  "maximumFileSizeInBytes": 5368709120,
                  "uploadParameters": {
                    "url": "https://s3.example.com/bucket/upload-456",
                    "headers": {
                      "x-amz-server-side-encryption": "AES256",
                      "x-amz-server-side-encryption-aws-kms-key-id": "key-id"
                    }
                  }
                }
                """;

        UploadResponse response = UploadResponse.fromJson(json);

        assertEquals(5368709120L, response.getMaxFileSize());
    }

    @Test
    void fromJson_throwsOnInvalidJson() {
        assertThrows(Exception.class, () -> UploadResponse.fromJson("not json"));
    }

    @Test
    void fromJson_throwsOnMissingFields() {
        String json = """
                {
                  "id": "upload-789"
                }
                """;

        assertThrows(Exception.class, () -> UploadResponse.fromJson(json));
    }
}
