package ai.jgp.gha.dataproduct;

/**
 * Application constants.
 */
public class K {

    public static final String VERSION = "0.2.13";

    public static final String DEFAULT_CATALOG = "default";

    public static final String ENV_FILE = "ZEENEA_FILE";
    public static final String ENV_TENANT = "ZEENEA_TENANT";
    public static final String ENV_API_KEY = "ZEENEA_API_KEY";
    public static final String ENV_CATALOG = "ZEENEA_CATALOG";
    public static final String ENV_URL = "ZEENEA_URL";

    public static final String BASE_URL_TEMPLATE = "https://%s.zeenea.app";
    public static final String UPLOAD_PATH = "/api/synchronization/data-product-uploads";

    public static final String HEADER_API_SECRET = "X-API-SECRET";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_KMS_ENCRYPTION = "x-amz-server-side-encryption";
    public static final String HEADER_KMS_KEY_ID = "x-amz-server-side-encryption-aws-kms-key-id";

    public static final int POLL_INTERVAL_MS = 2000;
    public static final int POLL_MAX_RETRIES = 60;

    // Kafka
    public static final String ENV_KAFKA_BROKER = "KAFKA_BROKER_URL";
    public static final String ENV_KAFKA_USER = "KAFKA_USERNAME";
    public static final String ENV_KAFKA_PASSWORD = "KAFKA_PASSWORD";
    public static final String KAFKA_TOPIC_SPEC_INGEST = "controlcenter.spec.ingest";

    private K() {
    }
}
