package ai.jgp.gha.dataproduct.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class UploadResponse {

    private final String id;
    private final String url;
    private final String kmsEncryption;
    private final String kmsKeyId;
    private final long maxFileSize;

    private UploadResponse(String id, String url, String kmsEncryption,
                           String kmsKeyId, long maxFileSize) {
        this.id = id;
        this.url = url;
        this.kmsEncryption = kmsEncryption;
        this.kmsKeyId = kmsKeyId;
        this.maxFileSize = maxFileSize;
    }

    public static UploadResponse fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String id = root.get("id").getAsString();

        JsonObject uploadParams = root.getAsJsonObject("uploadParameters");
        String url = uploadParams.get("url").getAsString();
        long maxFileSize = root.get("maximumFileSizeInBytes").getAsLong();

        JsonObject headers = uploadParams.getAsJsonObject("headers");
        String kmsEncryption = headers.get("x-amz-server-side-encryption").getAsString();
        String kmsKeyId = headers.get("x-amz-server-side-encryption-aws-kms-key-id").getAsString();

        return new UploadResponse(id, url, kmsEncryption, kmsKeyId, maxFileSize);
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getKmsEncryption() {
        return kmsEncryption;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }
}
