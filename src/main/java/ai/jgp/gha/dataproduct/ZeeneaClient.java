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

    // Captured during upload() for the sync-status event (#35). lastUploadId is
    // the Zeenea upload id once obtained (null on early failure); lastError is a
    // human-readable reason when upload() returns false or throws.
    private String lastUploadId;
    private String lastError;

    public ZeeneaClient(CliConfig config) {
        this(config, config.getFilePath());
    }

    public ZeeneaClient(CliConfig config, String zipPath) {
        this(config, zipPath, HttpClient.newHttpClient());
    }

    /**
     * Package-private constructor seam used by tests to inject a mocked
     * {@link HttpClient}. Not part of the public API.
     */
    ZeeneaClient(CliConfig config, String zipPath, HttpClient httpClient) {
        this.config = config;
        this.zipPath = zipPath;
        this.httpClient = httpClient;
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
            this.lastUploadId = uploadResponse.getId();
            System.out.println("       Upload ID: " + uploadResponse.getId());
            System.out.println("       Max file size: " + (uploadResponse.getMaxFileSize() / 1024 / 1024) + " MB");

            // Validate file size
            long fileSize = Files.size(Path.of(zipPath));
            if (fileSize > uploadResponse.getMaxFileSize()) {
                this.lastError = "file size (" + fileSize + " bytes) exceeds maximum ("
                        + uploadResponse.getMaxFileSize() + " bytes)";
                System.err.println("Error: " + this.lastError);
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
            this.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("Error: " + this.lastError);
            if (e.getCause() != null) {
                System.err.println("Caused by: " + e.getCause());
            }
            return false;
        }
    }

    /**
     * The Zeenea upload id obtained during the last {@link #upload()}, or null
     * if the upload failed before one was issued. Used to populate the
     * sync-status event (#35).
     */
    public String getLastUploadId() {
        return lastUploadId;
    }

    /**
     * A human-readable reason the last {@link #upload()} failed, or null if it
     * succeeded (or has not run). Used to populate the sync-status event (#35).
     */
    public String getLastError() {
        return lastError;
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
        String url = uploadResponse.getUrl();
        log.fine(">> PUT " + url);
        log.fine(">> File size: " + fileBytes.length + " bytes");

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));

        // Replay EVERY header the server told us to send. The presigned URL is
        // signed over these, so sending a subset breaks the SigV4 signature.
        uploadResponse.getHeaders().forEach(builder::header);

        // Guarantee every header in the URL's X-Amz-SignedHeaders (except host,
        // which the client sets automatically) is present — S3 returns 403
        // SignatureDoesNotMatch if a signed header is missing. Any signed header
        // the server did not enumerate (e.g. an empty x-amz-tagging) is sent with
        // an empty value, matching what was signed. See #38.
        for (String signed : signedHeaderNames(url)) {
            if (!signed.equalsIgnoreCase("host") && !uploadResponse.getHeaders().containsKey(signed)) {
                builder.header(signed, "");
            }
        }

        HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logResponse("PUT upload", response);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to upload file. HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    /**
     * Extracts the lower-cased header names from a presigned URL's
     * {@code X-Amz-SignedHeaders} query parameter (semicolon-separated, URL
     * encoded). Returns an empty list when the URL carries no such parameter.
     */
    static java.util.List<String> signedHeaderNames(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return java.util.List.of();
        }
        for (String param : url.substring(q + 1).split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(param.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            if ("X-Amz-SignedHeaders".equalsIgnoreCase(key)) {
                String value = java.net.URLDecoder.decode(param.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                java.util.List<String> names = new java.util.ArrayList<>();
                for (String h : value.split(";")) {
                    if (!h.isBlank()) {
                        names.add(h.trim());
                    }
                }
                return names;
            }
        }
        return java.util.List.of();
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
                    this.lastError = "processing reported " + errors.size() + " error(s)";
                    return false;
                }

                return true;
            }
        }

        this.lastError = "processing timed out after " + K.POLL_MAX_RETRIES + " attempts";
        System.err.println("Error: " + this.lastError);
        return false;
    }

    private void logResponse(String label, HttpResponse<String> response) {
        log.fine("<< " + label + " — HTTP " + response.statusCode());
        log.fine("<< Headers: " + response.headers().map());
        log.fine("<< Body: " + response.body());
    }
}
