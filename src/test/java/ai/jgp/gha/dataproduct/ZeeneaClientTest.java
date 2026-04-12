package ai.jgp.gha.dataproduct;

import ai.jgp.gha.dataproduct.model.UploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ZeeneaClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @TempDir
    Path tempDir;

    private Path zipFile;
    private CliConfig config;

    @BeforeEach
    void setUp() throws IOException {
        zipFile = tempDir.resolve("test.zip");
        Files.write(zipFile, new byte[]{0x50, 0x4B, 0x03, 0x04, 1, 2, 3});

        config = CliConfig.parse(new String[]{
                "--file", zipFile.toString(),
                "--tenant", "test-tenant",
                "--api-key", "test-api-key",
                "--catalog", "test-catalog"
        });

        // logResponse() calls response.headers().map(), so we must stub it
        HttpHeaders emptyHeaders = HttpHeaders.of(Map.of(), (a, b) -> true);
        lenient().when(httpResponse.headers()).thenReturn(emptyHeaders);
    }

    @Test
    void requestUploadUrl_success() throws Exception {
        String responseJson = """
                {
                  "id": "upload-001",
                  "maximumFileSizeInBytes": 52428800,
                  "uploadParameters": {
                    "url": "https://s3.example.com/upload-001",
                    "headers": {
                      "x-amz-server-side-encryption": "aws:kms",
                      "x-amz-server-side-encryption-aws-kms-key-id": "key-123"
                    }
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(responseJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        UploadResponse response = client.requestUploadUrl();

        assertEquals("upload-001", response.getId());
        assertEquals("https://s3.example.com/upload-001", response.getUrl());
        assertEquals(52428800L, response.getMaxFileSize());
    }

    @Test
    void requestUploadUrl_failsOnNon200() throws Exception {
        when(httpResponse.statusCode()).thenReturn(403);
        when(httpResponse.body()).thenReturn("Forbidden");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);

        IOException ex = assertThrows(IOException.class, () -> client.requestUploadUrl());
        assertTrue(ex.getMessage().contains("403"));
    }

    @Test
    void uploadFile_success() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        UploadResponse uploadResponse = mockUploadResponse();

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertDoesNotThrow(() -> client.uploadFile(uploadResponse));
    }

    @Test
    void uploadFile_failsOnNon200() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        UploadResponse uploadResponse = mockUploadResponse();

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);

        IOException ex = assertThrows(IOException.class, () -> client.uploadFile(uploadResponse));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void triggerProcessing_success() throws Exception {
        when(httpResponse.statusCode()).thenReturn(204);
        when(httpResponse.body()).thenReturn("");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertDoesNotThrow(() -> client.triggerProcessing("upload-001"));
    }

    @Test
    void triggerProcessing_failsOnNon204() throws Exception {
        when(httpResponse.statusCode()).thenReturn(400);
        when(httpResponse.body()).thenReturn("Bad Request");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);

        IOException ex = assertThrows(IOException.class, () -> client.triggerProcessing("upload-001"));
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void pollStatus_returnsTrue_whenProcessed() throws Exception {
        String statusJson = """
                {
                  "status": "Processed",
                  "result": {
                    "processed": 2,
                    "upserted": 2,
                    "errors": []
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(statusJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertTrue(client.pollStatus("upload-001"));
    }

    @Test
    void pollStatus_returnsFalse_whenProcessedWithErrors() throws Exception {
        String statusJson = """
                {
                  "status": "Processed",
                  "result": {
                    "processed": 1,
                    "upserted": 0,
                    "errors": ["Invalid schema"]
                  }
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(statusJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertFalse(client.pollStatus("upload-001"));
    }

    @Test
    void pollStatus_failsOnNon200() throws Exception {
        when(httpResponse.statusCode()).thenReturn(404);
        when(httpResponse.body()).thenReturn("Not Found");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);

        assertThrows(IOException.class, () -> client.pollStatus("upload-001"));
    }

    @Test
    void pollStatus_handlesNullResult() throws Exception {
        String statusJson = """
                {
                  "status": "Processed",
                  "result": null
                }
                """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(statusJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertTrue(client.pollStatus("upload-001"));
    }

    @Test
    void upload_fullWorkflow_success() throws Exception {
        String uploadUrlJson = """
                {
                  "id": "upload-full",
                  "maximumFileSizeInBytes": 52428800,
                  "uploadParameters": {
                    "url": "https://s3.example.com/upload-full",
                    "headers": {
                      "x-amz-server-side-encryption": "aws:kms",
                      "x-amz-server-side-encryption-aws-kms-key-id": "key-123"
                    }
                  }
                }
                """;

        String processedJson = """
                {
                  "status": "Processed",
                  "result": {
                    "processed": 1,
                    "upserted": 1,
                    "errors": []
                  }
                }
                """;

        // Use separate mock responses per call to avoid body() call-count issues from logResponse
        HttpHeaders emptyHeaders = HttpHeaders.of(Map.of(), (a, b) -> true);

        HttpResponse<String> resp1 = mock(HttpResponse.class);
        when(resp1.statusCode()).thenReturn(200);
        when(resp1.body()).thenReturn(uploadUrlJson);
        when(resp1.headers()).thenReturn(emptyHeaders);

        HttpResponse<String> resp2 = mock(HttpResponse.class);
        when(resp2.statusCode()).thenReturn(200);
        when(resp2.body()).thenReturn("");
        when(resp2.headers()).thenReturn(emptyHeaders);

        HttpResponse<String> resp3 = mock(HttpResponse.class);
        when(resp3.statusCode()).thenReturn(204);
        when(resp3.body()).thenReturn("");
        when(resp3.headers()).thenReturn(emptyHeaders);

        HttpResponse<String> resp4 = mock(HttpResponse.class);
        when(resp4.statusCode()).thenReturn(200);
        when(resp4.body()).thenReturn(processedJson);
        when(resp4.headers()).thenReturn(emptyHeaders);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp1, resp2, resp3, resp4);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertTrue(client.upload());

        verify(httpClient, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void upload_returnsFalse_whenFileTooLarge() throws Exception {
        // Upload response with very small max file size
        String uploadUrlJson = """
                {
                  "id": "upload-too-big",
                  "maximumFileSizeInBytes": 1,
                  "uploadParameters": {
                    "url": "https://s3.example.com/upload",
                    "headers": {
                      "x-amz-server-side-encryption": "aws:kms",
                      "x-amz-server-side-encryption-aws-kms-key-id": "key-123"
                    }
                  }
                }
                """;

        // Use lenient for body since logResponse may or may not call it depending on path
        when(httpResponse.statusCode()).thenReturn(200);
        lenient().when(httpResponse.body()).thenReturn(uploadUrlJson);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertFalse(client.upload());

        // Should only make 1 call (requestUploadUrl), then bail
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void upload_returnsFalse_onException() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        assertFalse(client.upload());
    }

    @Test
    void constructor_setsFieldsCorrectly() {
        ZeeneaClient client = new ZeeneaClient(config, zipFile.toString(), httpClient);
        // Just verifying construction doesn't throw
        assertNotNull(client);
    }

    @Test
    void constructor_convenienceUsesFilePath() throws IOException {
        // The single-arg constructor uses config.getFilePath()
        // Just verify it doesn't throw
        ZeeneaClient client = new ZeeneaClient(config);
        assertNotNull(client);
    }

    private UploadResponse mockUploadResponse() {
        String json = """
                {
                  "id": "upload-mock",
                  "maximumFileSizeInBytes": 52428800,
                  "uploadParameters": {
                    "url": "https://s3.example.com/upload-mock",
                    "headers": {
                      "x-amz-server-side-encryption": "aws:kms",
                      "x-amz-server-side-encryption-aws-kms-key-id": "key-mock"
                    }
                  }
                }
                """;
        return UploadResponse.fromJson(json);
    }
}
