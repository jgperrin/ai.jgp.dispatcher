package ai.jgp.gha.dataproduct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ZeeneaClient}.
 *
 * <p>Uses a mocked {@link HttpClient} (injected via the package-private
 * constructor seam) to exercise the four steps of {@code upload}:
 * request upload URL, PUT file, trigger processing, poll status. Also
 * covers the error branches (non-200 responses, file-too-large) which
 * are caught and surface as a {@code false} return value.
 */
class ZeeneaClientTest {

    @TempDir
    Path tmp;

    private CliConfig config;
    private Path zip;
    private HttpClient http;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        zip = tmp.resolve("bundle.zip");
        Files.write(zip, new byte[]{1, 2, 3});

        config = CliConfig.parse(new String[]{
                "--file", zip.toString(),
                "--tenant", "acme",
                "--api-key", "secret",
                "--catalog", "default",
        });
        http = mock(HttpClient.class);
    }

    private static final String UPLOAD_RESPONSE_BODY = """
            {
              "id": "u-1",
              "maximumFileSizeInBytes": 26214400,
              "uploadParameters": {
                "url": "https://s3.example.com/u-1",
                "headers": {
                  "x-amz-server-side-encryption": "aws:kms",
                  "x-amz-server-side-encryption-aws-kms-key-id": "k"
                }
              }
            }
            """;

    private static final String STATUS_PROCESSED_BODY = """
            {
              "status": "Processed",
              "result": {
                "processed": 2,
                "upserted": 2,
                "errors": []
              }
            }
            """;

    private static final String STATUS_PROCESSED_WITH_ERRORS = """
            {
              "status": "Processed",
              "result": {
                "processed": 2,
                "upserted": 1,
                "errors": ["bad row 7"]
              }
            }
            """;

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> stubResponse(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        org.mockito.Mockito.doReturn(status).when(r).statusCode();
        org.mockito.Mockito.doReturn(body).when(r).body();
        java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true);
        org.mockito.Mockito.doReturn(headers).when(r).headers();
        return r;
    }

    @SuppressWarnings("unchecked")
    @Test
    void upload_happyPath_returnsTrue() throws Exception {
        HttpResponse<String> r1 = stubResponse(200, UPLOAD_RESPONSE_BODY);
        HttpResponse<String> r2 = stubResponse(200, "");
        HttpResponse<String> r3 = stubResponse(204, "");
        HttpResponse<String> r4 = stubResponse(200, STATUS_PROCESSED_BODY);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r1, r2, r3, r4);

        // POLL_INTERVAL_MS is 2s — keep the test reasonable by relying on a
        // single iteration (status "Processed" on the first poll).
        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        boolean ok = client.upload();

        assertTrue(ok);
        verify(http, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getLastUploadId_set_andNoError_afterSuccessfulUpload() throws Exception {
        HttpResponse<String> r1 = stubResponse(200, UPLOAD_RESPONSE_BODY);
        HttpResponse<String> r2 = stubResponse(200, "");
        HttpResponse<String> r3 = stubResponse(204, "");
        HttpResponse<String> r4 = stubResponse(200, STATUS_PROCESSED_BODY);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r1, r2, r3, r4);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertTrue(client.upload());

        assertEquals("u-1", client.getLastUploadId());
        org.junit.jupiter.api.Assertions.assertNull(client.getLastError());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getLastError_set_andNoUploadId_whenRequestUploadUrlFails() throws Exception {
        HttpResponse<String> r = stubResponse(500, "boom");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertFalse(client.upload());

        // No upload id was ever issued on an early failure.
        org.junit.jupiter.api.Assertions.assertNull(client.getLastUploadId());
        org.junit.jupiter.api.Assertions.assertNotNull(client.getLastError());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getLastError_set_butUploadIdRetained_whenProcessingReportsErrors() throws Exception {
        HttpResponse<String> r1 = stubResponse(200, UPLOAD_RESPONSE_BODY);
        HttpResponse<String> r2 = stubResponse(200, "");
        HttpResponse<String> r3 = stubResponse(204, "");
        HttpResponse<String> r4 = stubResponse(200, STATUS_PROCESSED_WITH_ERRORS);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r1, r2, r3, r4);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertFalse(client.upload());

        // The upload id was issued before processing reported errors.
        assertEquals("u-1", client.getLastUploadId());
        org.junit.jupiter.api.Assertions.assertNotNull(client.getLastError());
    }

    @SuppressWarnings("unchecked")
    @Test
    void upload_returnsFalse_whenRequestUploadUrlFails() throws Exception {
        HttpResponse<String> r = stubResponse(500, "boom");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertFalse(client.upload());
    }

    @SuppressWarnings("unchecked")
    @Test
    void upload_returnsFalse_whenFileExceedsMaxSize() throws Exception {
        // Cap the server-side max at 1 byte; our zip is 3 bytes.
        String tinyMax = UPLOAD_RESPONSE_BODY.replace("26214400", "1");
        HttpResponse<String> r = stubResponse(200, tinyMax);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertFalse(client.upload());
    }

    @SuppressWarnings("unchecked")
    @Test
    void upload_returnsFalse_whenProcessingReportsErrors() throws Exception {
        HttpResponse<String> r1 = stubResponse(200, UPLOAD_RESPONSE_BODY);
        HttpResponse<String> r2 = stubResponse(200, "");
        HttpResponse<String> r3 = stubResponse(204, "");
        HttpResponse<String> r4 = stubResponse(200, STATUS_PROCESSED_WITH_ERRORS);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r1, r2, r3, r4);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertFalse(client.upload());
    }

    @SuppressWarnings("unchecked")
    @Test
    void requestUploadUrl_parsesResponse() throws Exception {
        HttpResponse<String> r = stubResponse(200, UPLOAD_RESPONSE_BODY);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        var resp = client.requestUploadUrl();

        assertEquals("u-1", resp.getId());
        assertEquals("https://s3.example.com/u-1", resp.getUrl());
        verify(http, atLeastOnce()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void triggerProcessing_throwsOnNon204() throws Exception {
        HttpResponse<String> r = stubResponse(500, "denied");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> client.triggerProcessing("u-1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadFile_throwsOnNon200() throws Exception {
        HttpResponse<String> r = stubResponse(403, "nope");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r);

        var resp = ai.jgp.gha.dataproduct.model.UploadResponse.fromJson(UPLOAD_RESPONSE_BODY);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> client.uploadFile(resp));
    }

    // --- #38: replay every signed header (incl. an empty x-amz-tagging) on the PUT ---

    /** Upload-URL response whose presigned URL signs x-amz-tagging but whose
     * headers map omits it (the real Actian landing-zone behaviour from #38). */
    private static final String SIGNED_URL_RESPONSE_BODY = """
            {
              "id": "u-1",
              "maximumFileSizeInBytes": 26214400,
              "uploadParameters": {
                "url": "https://s3.example.com/u-1?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-SignedHeaders=host%3Bx-amz-server-side-encryption%3Bx-amz-server-side-encryption-aws-kms-key-id%3Bx-amz-tagging",
                "headers": {
                  "x-amz-server-side-encryption": "aws:kms",
                  "x-amz-server-side-encryption-aws-kms-key-id": "arn:key/abc"
                }
              }
            }
            """;

    @SuppressWarnings("unchecked")
    @Test
    void upload_putReplaysEverySignedHeaderIncludingEmptyTagging() throws Exception {
        HttpResponse<String> r1 = stubResponse(200, SIGNED_URL_RESPONSE_BODY);
        HttpResponse<String> r2 = stubResponse(200, "");
        HttpResponse<String> r3 = stubResponse(204, "");
        HttpResponse<String> r4 = stubResponse(200, STATUS_PROCESSED_BODY);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(r1, r2, r3, r4);

        ZeeneaClient client = new ZeeneaClient(config, zip.toString(), http);
        assertTrue(client.upload());

        org.mockito.ArgumentCaptor<HttpRequest> captor =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(4)).send(captor.capture(), any(HttpResponse.BodyHandler.class));

        // The PUT is the 2nd request (1: POST url, 2: PUT, 3: POST process, 4: GET poll).
        HttpRequest put = captor.getAllValues().get(1);
        assertEquals("PUT", put.method());
        var headers = put.headers();
        assertEquals("aws:kms", headers.firstValue("x-amz-server-side-encryption").orElse(null));
        assertEquals("arn:key/abc", headers.firstValue("x-amz-server-side-encryption-aws-kms-key-id").orElse(null));
        // The crux of #38: x-amz-tagging is signed but absent from the server's
        // headers map, so it must still be sent — with an empty value.
        assertTrue(headers.map().containsKey("x-amz-tagging"), "x-amz-tagging must be sent on the PUT");
        assertEquals("", headers.firstValue("x-amz-tagging").orElse(null));
    }

    @Test
    void signedHeaderNames_parsesAndUrlDecodes() {
        var names = ZeeneaClient.signedHeaderNames(
                "https://s3/u?X-Amz-SignedHeaders=host%3Bx-amz-server-side-encryption%3Bx-amz-tagging");
        assertEquals(java.util.List.of("host", "x-amz-server-side-encryption", "x-amz-tagging"), names);
    }

    @Test
    void signedHeaderNames_emptyWhenNoQueryOrParam() {
        assertTrue(ZeeneaClient.signedHeaderNames("https://s3/u").isEmpty());
        assertTrue(ZeeneaClient.signedHeaderNames("https://s3/u?X-Amz-Date=20260624T0").isEmpty());
    }
}
