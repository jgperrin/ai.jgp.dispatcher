# Data Product Uploader

A Java CLI tool that uploads [ODPS](https://opendataproductspecification.com/) data product descriptors to the [Actian Data Intelligence Platform](https://www.actian.com/) (Zeenea) via its Data Product API. Optionally, it also publishes each spec to a Kafka topic for ingestion by the [Data Product Control Center](https://github.com/jgperrin/ai.jgp.dataproduct.controlcenter.svc). It can be used standalone or as part of a GitHub Actions workflow.

## How It Works

The uploader takes a ZIP file containing `.odps.yaml` and `.odcs.yaml` descriptors and:

1. **Request** an upload URL from the platform
2. **Upload** the ZIP file to the provided S3 URL
3. **Trigger** processing for the target catalog
4. **Poll** until processing completes and report the results
5. **Publish specs to Kafka** (optional) — each YAML file from the ZIP is wrapped in an OOCS `deliver-spec` envelope and published to the `controlcenter.spec.ingest` topic

Steps 1–4 upload to Zeenea. Step 5 runs only when Kafka is configured and the Zeenea upload succeeds.

## Requirements

- Java 21 or later
- Maven 3.8+ (to build from source)

## Quick Start

### Build

```bash
mvn clean package
```

### Run

```bash
java -jar target/data-product-uploader-0.2.0.jar \
  --file data-products.zip \
  --tenant my-company \
  --api-key YOUR_API_KEY \
  --catalog default
```

With Kafka publishing enabled:

```bash
java -jar target/data-product-uploader-0.2.0.jar \
  --file data-products.zip \
  --tenant my-company \
  --api-key YOUR_API_KEY \
  --kafka-broker api.jgp.ai:9093 \
  --kafka-user YOUR_KAFKA_USER \
  --kafka-password YOUR_KAFKA_PASSWORD
```

Or use the convenience wrapper (auto-builds if needed):

```bash
./bin/upload.sh --file data-products.zip --tenant my-company --api-key YOUR_API_KEY
```

### CLI Flags

#### Zeenea (required)

| Flag | Env Variable | Required | Default | Description |
|------|-------------|----------|---------|-------------|
| `--file` | `ZEENEA_FILE` | Yes | - | Path to the ZIP file containing `.odps.yaml` descriptors |
| `--tenant` | `ZEENEA_TENANT` | Yes* | - | Zeenea tenant name (builds URL `https://{tenant}.zeenea.app`) |
| `--api-key` | `ZEENEA_API_KEY` | Yes | - | Platform API key (`X-API-SECRET`) |
| `--catalog` | `ZEENEA_CATALOG` | No | `default` | Target catalog code |
| `--url` | `ZEENEA_URL` | No | - | Custom base URL (overrides tenant-based URL) |

*\*Not required if `--url` is provided.*

#### Kafka (optional)

When configured, the uploader publishes each YAML spec from the ZIP to the `controlcenter.spec.ingest` Kafka topic after a successful Zeenea upload. If no broker is provided, Kafka publishing is silently skipped.

| Flag | Env Variable | Required | Default | Description |
|------|-------------|----------|---------|-------------|
| `--kafka-broker` | `KAFKA_BROKER_URL` | No | - | Kafka broker URL (e.g. `api.jgp.ai:9093`) |
| `--kafka-user` | `KAFKA_USERNAME` | No | - | SASL username for Kafka authentication |
| `--kafka-password` | `KAFKA_PASSWORD` | No | - | SASL password for Kafka authentication |

The Kafka producer uses **SASL_SSL** with **SCRAM-SHA-512** and expects a truststore at `~/.kafka/kafka.client.truststore.jks`. If no credentials are provided (broker only), it falls back to PLAINTEXT.

#### General

| Flag | Env Variable | Required | Default | Description |
|------|-------------|----------|---------|-------------|
| `--debug` | - | No | Off | Enable debug logging |
| `--version`, `-v` | - | No | - | Show version |
| `--help`, `-h` | - | No | - | Show usage help |

CLI flags take precedence over environment variables.

## GitHub Actions Integration

This tool can be set up as a GitHub Actions workflow to automatically upload data products whenever `.odps.yaml` files are merged into `main`. See [GITHUB_ACTION_SETUP.md](GITHUB_ACTION_SETUP.md) for a step-by-step guide.

## Example Output

```
Data Product Uploader v0.2.0
[1/4] Requesting upload URL...
       Upload ID: abc123...
       Max file size: 50 MB
[2/4] Uploading ZIP file...
       Upload complete.
[3/4] Triggering processing (catalog: default)...
       Processing triggered.
[4/4] Polling for processing status...
       Attempt 1/60 — status: Processed

Processing complete:
  Descriptors processed: 3
  Data products upserted: 3

Publishing specs to Kafka...
  Published: product-a.odps.yaml (DataProduct product-a v1.0.0)
  Published: contract-1.odcs.yaml (DataContract contract-1 v1.0.0)
  Published: contract-2.odcs.yaml (DataContract contract-2 v1.0.0)
Kafka publishing complete: 3 spec(s) published.
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
