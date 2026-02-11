# Data Product Uploader

A Java CLI tool that uploads [ODPS](https://opendataproductspecification.com/) data product descriptors to the [Actian Data Intelligence Platform](https://www.actian.com/) (Zeenea) via its Data Product API. It can be used standalone or as part of a GitHub Actions workflow.

## How It Works

The uploader takes a ZIP file containing `.odps.yaml` descriptors and pushes them through the platform's 4-step upload API:

1. **Request** an upload URL from the platform
2. **Upload** the ZIP file to the provided S3 URL
3. **Trigger** processing for the target catalog
4. **Poll** until processing completes and report the results

## Requirements

- Java 17 or later
- Maven 3.8+ (to build from source)

## Quick Start

### Build

```bash
mvn clean package
```

### Run

```bash
java -jar target/data-product-uploader-0.1.5.jar \
  --file data-products.zip \
  --tenant my-company \
  --api-key YOUR_API_KEY \
  --catalog default
```

Or use the convenience wrapper (auto-builds if needed):

```bash
./bin/upload.sh --file data-products.zip --tenant my-company --api-key YOUR_API_KEY
```

### CLI Flags

| Flag | Env Variable | Required | Default | Description |
|------|-------------|----------|---------|-------------|
| `--file` | `ZEENEA_FILE` | Yes | - | Path to the ZIP file containing `.odps.yaml` descriptors |
| `--tenant` | `ZEENEA_TENANT` | Yes* | - | Zeenea tenant name (builds URL `https://{tenant}.zeenea.app`) |
| `--api-key` | `ZEENEA_API_KEY` | Yes | - | Platform API key (`X-API-SECRET`) |
| `--catalog` | `ZEENEA_CATALOG` | No | `default` | Target catalog code |
| `--url` | `ZEENEA_URL` | No | - | Custom base URL (overrides tenant-based URL) |
| `--debug` | - | No | Off | Enable debug logging of HTTP requests and responses |

*\*Not required if `--url` is provided.*

CLI flags take precedence over environment variables.

## GitHub Actions Integration

This tool can be set up as a GitHub Actions workflow to automatically upload data products whenever `.odps.yaml` files are merged into `main`. See [GITHUB_ACTION_SETUP.md](GITHUB_ACTION_SETUP.md) for a step-by-step guide.

## Example Output

```
Data Product Uploader v0.1.5
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
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
