package ai.jgp.gha.dataproduct.model;

import ai.jgp.gha.dataproduct.K;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class UploadResponse {

    private final String id;
    private final String url;
    private final Map<String, String> headers;
    private final long maxFileSize;

    private UploadResponse(String id, String url, Map<String, String> headers, long maxFileSize) {
        this.id = id;
        this.url = url;
        this.headers = headers;
        this.maxFileSize = maxFileSize;
    }

    public static UploadResponse fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String id = root.get("id").getAsString();

        JsonObject uploadParams = root.getAsJsonObject("uploadParameters");
        String url = uploadParams.get("url").getAsString();
        long maxFileSize = root.get("maximumFileSizeInBytes").getAsLong();

        // Capture EVERY header the server tells us to send on the PUT. The
        // presigned URL is signed over these headers, so cherry-picking a subset
        // breaks the SigV4 signature (a missing x-amz-tagging → 403, see #38).
        Map<String, String> headers = new LinkedHashMap<>();
        JsonObject headersJson = uploadParams.getAsJsonObject("headers");
        if (headersJson != null) {
            for (Map.Entry<String, JsonElement> e : headersJson.entrySet()) {
                headers.put(e.getKey(), e.getValue().getAsString());
            }
        }

        return new UploadResponse(id, url, Collections.unmodifiableMap(headers), maxFileSize);
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    /** Every header the server requires on the upload PUT (all signed headers). */
    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getKmsEncryption() {
        return headers.get(K.HEADER_KMS_ENCRYPTION);
    }

    public String getKmsKeyId() {
        return headers.get(K.HEADER_KMS_KEY_ID);
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }
}
