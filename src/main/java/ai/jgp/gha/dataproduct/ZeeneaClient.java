package ai.jgp.gha.dataproduct;

import ai.jgp.gha.dataproduct.model.UploadResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ZeeneaClient {

    private static final Logger log = Logger.getLogger(ZeeneaClient.class.getName());

    private final CliConfig config;
    private final String zipPath;
    private final HttpClient httpClient;

    public ZeeneaClient(CliConfig config) {
        this(config, config.getFilePath());
    }

    public ZeeneaClient(CliConfig config, String zipPath) {
        this.config = config;
        this.zipPath = zipPath;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Orchestrates the full upload workflow.
     *
     * @return true if upload and processing succeeded, false otherwise.
     */
    public boolean upload() {
        try {
            log.fine("Base URL: " + config.getBaseUrl());
            log.fine("File: " + zipPath);
            log.fine("Catalog: " + config.getCatalogCode());

            // Step 1: Request upload URL
            System.out.println("[1/4] Requesting upload URL...");
            UploadResponse uploadResponse = requestUploadUrl();
            System.out.println("       Upload ID: " + uploadResponse.getId());
            System.out.println("       Max file size: " + (uploadResponse.getMaxFileSize() / 1024 / 1024) + " MB");

            // Validate file size
            long fileSize = Files.size(Path.of(zipPath));
            if (fileSize > uploadResponse.getMaxFileSize()) {
                System.err.println("Error: file size (" + fileSize + " bytes) exceeds maximum ("
                        + uploadResponse.getMaxFileSize() + " bytes)");
                return false;
            }

            // Step 2: Upload the ZIP file
            System.out.println("[2/4] Uploading ZIP file...");
            uploadFile(uploadResponse);
            System.out.println("       Upload complete.");

            // Step 3: Trigger processing
            System.out.println("[3/4] Triggering processing (catalog: " + config.getCatalogCode() + ")...");
            triggerProcessing(uploadResponse.getId());
            System.out.println("       Processing triggered.");

            // Step 4: Poll status
            System.out.println("[4/4] Polling for processing status...");
            return pollStatus(uploadResponse.getId());

        } catch (Exception e) {
            System.err.println("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Caused by: " + e.getCause());
            }
            return false;
        }
    }

    UploadResponse requestUploadUrl() throws IOException, InterruptedException {
        String uri = config.getBaseUrl() + K.UPLOAD_PATH;
        log.fine(">> POST " + uri);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header(K.HEADER_API_SECRET, config.getApiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logResponse("POST " + K.UPLOAD_PATH, response);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to request upload URL. HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return UploadResponse.fromJson(response.body());
    }

    void uploadFile(UploadResponse uploadResponse) throws IOException, InterruptedException {
        byte[] fileBytes = Files.readAllBytes(Path.of(zipPath));
        log.fine(">> PUT " + uploadResponse.getUrl());
        log.fine(">> File size: " + fileBytes.length + " bytes");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadResponse.getUrl()))
                .header(K.HEADER_KMS_ENCRYPTION, uploadResponse.getKmsEncryption())
                .header(K.HEADER_KMS_KEY_ID, uploadResponse.getKmsKeyId())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logResponse("PUT upload", response);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to upload file. HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    void triggerProcessing(String id) throws IOException, InterruptedException {
        String uri = config.getBaseUrl() + K.UPLOAD_PATH + "/" + id + "/process";
        String body = "{\"catalogCode\":\"" + config.getCatalogCode() + "\"}";
        log.fine(">> POST " + uri);
        log.fine(">> Body: " + body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header(K.HEADER_API_SECRET, config.getApiKey())
                .header(K.HEADER_CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logResponse("POST process", response);

        if (response.statusCode() != 204) {
            throw new IOException("Failed to trigger processing. HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    boolean pollStatus(String id) throws IOException, InterruptedException {
        String uri = config.getBaseUrl() + K.UPLOAD_PATH + "/" + id;

        for (int attempt = 1; attempt <= K.POLL_MAX_RETRIES; attempt++) {
            Thread.sleep(K.POLL_INTERVAL_MS);

            log.fine(">> GET " + uri + " (attempt " + attempt + ")");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header(K.HEADER_API_SECRET, config.getApiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logResponse("GET status", response);

            if (response.statusCode() != 200) {
                throw new IOException("Failed to check status. HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            JsonObject status = JsonParser.parseString(response.body()).getAsJsonObject();
            String state = status.get("status").getAsString();

            System.out.println("       Attempt " + attempt + "/" + K.POLL_MAX_RETRIES + " — status: " + state);

            if ("Processed".equals(state)) {
                JsonObject result = status.has("result") && !status.get("result").isJsonNull()
                        ? status.getAsJsonObject("result") : new JsonObject();
                int processed = result.has("processed") ? result.get("processed").getAsInt() : 0;
                int upserted = result.has("upserted") ? result.get("upserted").getAsInt() : 0;
                JsonArray errors = result.has("errors") ? result.getAsJsonArray("errors") : new JsonArray();

                System.out.println();
                System.out.println("Processing complete:");
                System.out.println("  Descriptors processed: " + processed);
                System.out.println("  Data products upserted: " + upserted);

                if (!errors.isEmpty()) {
                    System.err.println("  Errors (" + errors.size() + "):");
                    for (int i = 0; i < errors.size(); i++) {
                        System.err.println("    - " + errors.get(i).getAsString());
                    }
                    return false;
                }

                return true;
            }
        }

        System.err.println("Error: processing timed out after " + K.POLL_MAX_RETRIES + " attempts");
        return false;
    }

    private void logResponse(String label, HttpResponse<String> response) {
        log.fine("<< " + label + " — HTTP " + response.statusCode());
        log.fine("<< Headers: " + response.headers().map());
        log.fine("<< Body: " + response.body());
    }
}
