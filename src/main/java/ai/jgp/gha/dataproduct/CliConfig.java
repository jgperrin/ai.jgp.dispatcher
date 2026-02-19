package ai.jgp.gha.dataproduct;

import java.nio.file.Files;
import java.nio.file.Path;

public class CliConfig {

    private final String filePath;
    private final String tenant;
    private final String apiKey;
    private final String catalogCode;
    private final String baseUrl;
    private final boolean debug;
    private final String kafkaBroker;
    private final String kafkaUser;
    private final String kafkaPassword;

    private CliConfig(String filePath, String tenant, String apiKey,
                      String catalogCode, String baseUrl, boolean debug,
                      String kafkaBroker, String kafkaUser, String kafkaPassword) {
        this.filePath = filePath;
        this.tenant = tenant;
        this.apiKey = apiKey;
        this.catalogCode = catalogCode;
        this.baseUrl = baseUrl;
        this.debug = debug;
        this.kafkaBroker = kafkaBroker;
        this.kafkaUser = kafkaUser;
        this.kafkaPassword = kafkaPassword;
    }

    public static CliConfig parse(String[] args) {
        String file = null;
        String tenant = null;
        String apiKey = null;
        String catalog = null;
        String url = null;
        boolean debug = false;
        String kafkaBroker = null;
        String kafkaUser = null;
        String kafkaPassword = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--file":
                    file = nextArg(args, i++, "--file");
                    break;
                case "--tenant":
                    tenant = nextArg(args, i++, "--tenant");
                    break;
                case "--api-key":
                    apiKey = nextArg(args, i++, "--api-key");
                    break;
                case "--catalog":
                    catalog = nextArg(args, i++, "--catalog");
                    break;
                case "--url":
                    url = nextArg(args, i++, "--url");
                    break;
                case "--kafka-broker":
                    kafkaBroker = nextArg(args, i++, "--kafka-broker");
                    break;
                case "--kafka-user":
                    kafkaUser = nextArg(args, i++, "--kafka-user");
                    break;
                case "--kafka-password":
                    kafkaPassword = nextArg(args, i++, "--kafka-password");
                    break;
                case "--debug":
                    debug = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    System.exit(0);
                    break;
                case "--version":
                case "-v":
                    System.out.println("data-product-uploader v" + K.VERSION);
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        // Fall back to environment variables (treat blank as unset)
        if (file == null) file = envOrNull(K.ENV_FILE);
        if (tenant == null) tenant = envOrNull(K.ENV_TENANT);
        if (apiKey == null) apiKey = envOrNull(K.ENV_API_KEY);
        if (catalog == null) catalog = envOrNull(K.ENV_CATALOG);
        if (catalog == null) catalog = K.DEFAULT_CATALOG;
        if (url == null) url = envOrNull(K.ENV_URL);
        if (kafkaBroker == null) kafkaBroker = envOrNull(K.ENV_KAFKA_BROKER);
        if (kafkaUser == null) kafkaUser = envOrNull(K.ENV_KAFKA_USER);
        if (kafkaPassword == null) kafkaPassword = envOrNull(K.ENV_KAFKA_PASSWORD);

        // Validate required fields
        boolean valid = true;
        if (file == null || file.isBlank()) {
            System.err.println("Error: --file is required (or set ZEENEA_FILE)");
            valid = false;
        }
        if (tenant == null || tenant.isBlank()) {
            System.err.println("Error: --tenant is required (or set ZEENEA_TENANT)");
            valid = false;
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: --api-key is required (or set ZEENEA_API_KEY)");
            valid = false;
        }
        if (!valid) {
            printUsage();
            System.exit(1);
        }

        // Validate file exists
        if (!Files.exists(Path.of(file))) {
            System.err.println("Error: file not found: " + file);
            System.exit(1);
        }

        // Resolve base URL: explicit URL > built from tenant
        String baseUrl = url != null ? url : String.format(K.BASE_URL_TEMPLATE, tenant);
        // Strip trailing slash if present
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return new CliConfig(file, tenant, apiKey, catalog, baseUrl, debug,
                kafkaBroker, kafkaUser, kafkaPassword);
    }

    private static String envOrNull(String name) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String nextArg(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            System.err.println("Error: " + flag + " requires a value");
            printUsage();
            System.exit(1);
        }
        return args[index + 1];
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("Data Product Uploader v" + K.VERSION);
        System.out.println();
        System.out.println("Usage: java -jar data-product-uploader.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --file <path>           Path to the ZIP file (required)");
        System.out.println("  --tenant <name>         Zeenea tenant name (required)");
        System.out.println("  --api-key <key>         API key secret (required)");
        System.out.println("  --catalog <code>        Catalog code (default: \"default\")");
        System.out.println("  --url <url>             Base URL (overrides tenant-based URL)");
        System.out.println("  --kafka-broker <url>    Kafka broker URL (optional)");
        System.out.println("  --kafka-user <user>     Kafka SASL username (optional)");
        System.out.println("  --kafka-password <pwd>  Kafka SASL password (optional)");
        System.out.println("  --debug                 Enable debug logging");
        System.out.println("  --version, -v           Show version");
        System.out.println("  --help, -h              Show this help");
        System.out.println();
        System.out.println("Environment variables:");
        System.out.println("  ZEENEA_FILE         Fallback for --file");
        System.out.println("  ZEENEA_TENANT       Fallback for --tenant");
        System.out.println("  ZEENEA_API_KEY      Fallback for --api-key");
        System.out.println("  ZEENEA_CATALOG      Fallback for --catalog");
        System.out.println("  ZEENEA_URL          Fallback for --url");
        System.out.println("  KAFKA_BROKER_URL    Fallback for --kafka-broker");
        System.out.println("  KAFKA_USERNAME      Fallback for --kafka-user");
        System.out.println("  KAFKA_PASSWORD      Fallback for --kafka-password");
    }

    public String getFilePath() {
        return filePath;
    }

    public String getTenant() {
        return tenant;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getCatalogCode() {
        return catalogCode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public boolean isDebug() {
        return debug;
    }

    public String getKafkaBroker() {
        return kafkaBroker;
    }

    public String getKafkaUser() {
        return kafkaUser;
    }

    public String getKafkaPassword() {
        return kafkaPassword;
    }

    /**
     * Returns true if Kafka publishing is configured (broker URL is set).
     */
    public boolean isKafkaConfigured() {
        return kafkaBroker != null;
    }
}
