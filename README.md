# Data Product Uploader

A Java CLI tool that uploads [ODPS](https://opendataproductspecification.com/) data product descriptors to the [Actian Data Intelligence Platform](https://www.actian.com/) (Zeenea) via its Data Product API. Optionally, it also publishes each spec to a Kafka topic for ingestion by the [Data Product Control Center](https://github.com/jgperrin/ai.jgp.dataproduct.controlcenter.svc). It can be used standalone or as part of a GitHub Actions workflow.

## How It Works

The uploader takes a ZIP file containing `.odps.yaml` and `.odcs.yaml` descriptors and:

0. **Validate** every descriptor in the bundle against the vendored Bitol JSON schemas (ODPS v1.0.0, ODCS v3.0.2 — classpath resources under `src/main/resources/schemas/`, no network needed; refresh by copying newer files from the standard repos). Any violation fails the run listing file + messages, and nothing is uploaded or published.
1. **Request** an upload URL from the platform
2. **Upload** the ZIP file to the provided S3 URL
3. **Trigger** processing for the target catalog
4. **Poll** until processing completes and report the results
5. **Publish to Kafka** (optional) — each changed product's ODPS YAML is published to the `controlcenter.dataproduct.descriptors` topic, keyed by product id with the `x-org-id` header

Steps 1–4 upload to Zeenea. Step 5 runs only when Kafka is configured and the Zeenea upload succeeds.

## End-to-End Process

The dispatcher is the final stage in a pipeline that moves data product specs from the Bitol Workbench to downstream systems (the Zeenea catalog and the Control Center). The full pipeline:

### Stage 1: User publishes from the Workbench

The user clicks "Publish to GitHub" in the Workbench web app (or calls the API directly). The Bitol service (`ai.jgp.bitol.svc`) handles the publish:

1. **V3 enhanced publish** (`POST /v3/products/publish-github`):
   - Runs pre-publish checks on the product and all referenced contracts (version-conflict detection via git tags).
   - Publishes each referenced contract to the GitHub repo via `PUT /repos/{owner}/{repo}/contents/{path}` (one commit per contract).
   - Publishes the product itself (one more commit).
   - Creates/updates git tags for each published artifact (`product-{id}-v{version}`, `contract-{id}-v{version}`).
   - Files are placed at the path specified by the product's `canonicalUrl` (e.g. `podem/04acb87d-….odps.yaml`).
2. **V1 publish** (`POST /v1/products/publish-github`): publishes a single product file with no pre-publish checks and no auto-publish of contracts.

### Stage 2: GitHub Actions triggers the dispatcher

The user repo's workflow (`.github/workflows/upload-data-products.yml`) triggers on a **push** that touches `podem/**/*.odps.yaml` / `podem/**/*.odcs.yaml`, or on **manual dispatch**. It checks out the repo with full history and tags, builds this dispatcher, and runs `java -jar data-product-uploader-*.jar --dir podem`.

### Stage 3: Dispatcher detects changed products (directory mode)

With `--dir`, the dispatcher runs `git diff --name-only HEAD~1 HEAD -- podem` to find what changed. Files ending in `.odps.yaml` are selected as **products to process**; `.odcs.yaml` files are logged but not processed directly (contracts are bundled with their parent product). If no product files changed, it exits with `No product files changed, nothing to upload.`

### Stage 4: ZIP building (per product)

For each changed product, `ZipBuilder.buildFromProduct()` creates a versioned ZIP:

1. Parse the product YAML to extract `id`, `version`, and `outputPorts[]`.
2. Add the product as `{productId}-v{version}.odps.yaml`.
3. Resolve each referenced contract — extract `contractId` and `version` from the port, fetch the content at the tagged version (`git show contract-{contractId}-v{version}:podem/{contractId}.odcs.yaml`), falling back to the working copy if the tag is absent, and add it as `{contractId}-v{version}.odcs.yaml`.

### Stage 5: Upload to Zeenea

`ZeeneaClient` requests an upload URL (`POST /api/synchronization/data-product-uploads`), `PUT`s the ZIP to the returned S3 presigned URL, triggers processing for the target catalog, and polls `GET …/{uploadId}` every 2 seconds (up to 60 retries) until the status is `Processed` or `Failed`.

### Stage 6: Publish the ODPS spec to Kafka (optional)

If Kafka credentials are configured, `KafkaPublisher.publishSpec()` sends the **product's ODPS YAML as a UTF-8 string** to the `controlcenter.dataproduct.descriptors` topic, keyed by the ODPS product id and stamped with the `x-org-id` header (the authoring tenant's org UUID, from `X_ORG_ID` — required whenever Kafka is configured; the run fails closed without it). This matches the Control Center's `SpecIngestConsumer` contract, which upserts the catalog and drops header-less records. If the broker is unreachable, this step is skipped with a warning — the Zeenea upload still counts as success.

### Process diagram

```mermaid
sequenceDiagram
    participant W as Workbench UI
    participant B as Bitol SVC
    participant G as GitHub
    participant D as Dispatcher
    participant Z as Zeenea
    participant K as Kafka

    Note over W,B: Stage 1: User publishes
    W->>B: POST /v3/products/publish-github
    B->>G: PUT contract.odcs.yaml (commit 1)
    B->>G: PUT product.odps.yaml (commit 2)
    B->>G: POST tags (contract + product)

    Note over G,D: Stage 2: GitHub Actions triggers
    G->>D: push event triggers workflow
    D->>G: checkout repo (full history + tags)

    Note over D: Stage 3: Detect changes
    D->>D: git diff HEAD~1 HEAD -- podem
    D->>D: filter .odps.yaml files only

    Note over D: Stage 4: Build ZIP
    D->>D: parse product YAML (id, version, outputPorts)
    D->>D: git show tag:contract.odcs.yaml
    D->>D: package product + contracts into ZIP

    Note over D,Z: Stage 5: Upload to Zeenea
    D->>Z: POST /data-product-uploads (request URL)
    Z-->>D: S3 presigned URL + upload ID
    D->>Z: PUT ZIP to S3
    D->>Z: POST trigger processing
    loop Poll every 2s (max 60 retries)
        D->>Z: GET upload status
        Z-->>D: Processing / Processed / Failed
    end

    Note over D,K: Stage 6: Publish to Kafka (optional)
    D->>K: probe broker (5s timeout)
    D->>K: publish ZIP to controlcenter.dataproduct.descriptors
```

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
java -jar target/data-product-uploader-0.3.3.jar \
  --file data-products.zip \
  --tenant my-company \
  --api-key YOUR_API_KEY \
  --catalog default
```

With Kafka publishing enabled:

```bash
java -jar target/data-product-uploader-0.3.3.jar \
  --file data-products.zip \
  --tenant my-company \
  --api-key YOUR_API_KEY \
  --kafka-broker api.jgp.ai:9093 \
  --kafka-user YOUR_KAFKA_USER \
  --kafka-password YOUR_KAFKA_PASSWORD \
  --org-id YOUR_ORG_UUID
```

Or use the convenience wrapper (auto-builds if needed):

```bash
./bin/upload.sh --file data-products.zip --tenant my-company --api-key YOUR_API_KEY
```

### CLI Flags

#### Zeenea (required)

| Flag        | Env Variable     | Required | Default   | Description                                                    |
|-------------|------------------|----------|-----------|----------------------------------------------------------------|
| `--file`    | `ZEENEA_FILE`    | Yes      | -         | Path to the ZIP file containing `.odps.yaml` descriptors       |
| `--tenant`  | `ZEENEA_TENANT`  | Yes*     | -         | Zeenea tenant name (builds URL `https://{tenant}.zeenea.app`)  |
| `--api-key` | `ZEENEA_API_KEY` | Yes      | -         | Platform API key (`X-API-SECRET`)                              |
| `--catalog` | `ZEENEA_CATALOG` | No       | `default` | Target catalog code                                            |
| `--url`     | `ZEENEA_URL`     | No       | -         | Custom base URL (overrides tenant-based URL)                   |

*\*Not required if `--url` is provided.*

#### Kafka (optional)

When configured, the uploader publishes each YAML spec from the ZIP to the `controlcenter.dataproduct.descriptors` Kafka topic after a successful Zeenea upload. If no broker is provided, Kafka publishing is silently skipped.

| Flag               | Env Variable       | Required | Default | Description                                |
|--------------------|--------------------|---------|---------|--------------------------------------------|
| `--kafka-broker`   | `KAFKA_BROKER_URL` | No      | -       | Kafka broker URL (e.g. `api.jgp.ai:9093`)  |
| `--kafka-user`     | `KAFKA_USERNAME`   | No      | -       | SASL username for Kafka authentication     |
| `--kafka-password` | `KAFKA_PASSWORD`   | No      | -       | SASL password for Kafka authentication     |
| `--org-id`         | `X_ORG_ID`         | When Kafka is configured | - | Authoring org UUID, stamped as the `x-org-id` header on descriptors records |

The Kafka producer uses **SASL_SSL** with **SCRAM-SHA-512** and expects a truststore at `~/.kafka/kafka.client.truststore.jks`. If no credentials are provided (broker only), it falls back to PLAINTEXT.

#### General

| Flag              | Env Variable | Required | Default | Description          |
|-------------------|--------------|----------|---------|----------------------|
| `--debug`         | -            | No       | Off     | Enable debug logging |
| `--version`, `-v` | -            | No       | -       | Show version         |
| `--help`, `-h`    | -            | No       | -       | Show usage help      |

CLI flags take precedence over environment variables.

## Testing

The test suite lives under `src/test/java/ai/jgp/gha/dataproduct/**` (JUnit 5 +
Mockito): ZIP building, the Zeenea HTTP client, the Kafka publisher, CLI config,
and the end-to-end `App` flows. JaCoCo enforces an **80%** line-coverage gate on
the `verify` phase.

```bash
mvn verify            # full suite + the JaCoCo coverage gate
bin/test-gate.sh      # the same, then reports the run to the WB2 Test Log
```

**Reporting.** Every run reports a summary to the **WB2 Test Log** (Admin → Logs
→ Test Log, backed by `POST /v4/diagnostics` with `type=test-run`) as an
**OORS `ObservabilityResults`** document (RFC-0018): count metrics where the
`failed` metric carries the pass/fail verdict. Green is derived server-side from
`results[]` and self-resolves at ingest, so only failures surface in the triage
queue. `bin/test-gate.sh` posts the run (`app=dispatcher`, `suite=gate`) via
`bin/post-test-run.sh` — the reporter shared byte-for-byte with svc and the MCP
server. `POST_TEST_RUN=0` opts out. The dispatcher has no CI workflow of its own
(it runs as a step inside other repos' workflows), so reporting is driven by the
local gate; the convention is documented in `doc/oors-test-rollout.md` (svc).

## GitHub Actions Integration

This tool can be set up as a GitHub Actions workflow to automatically upload data products whenever `.odps.yaml` files are merged into `main`. See [GITHUB_ACTION_SETUP.md](GITHUB_ACTION_SETUP.md) for a step-by-step guide.

## Example Output

```
Data Product Uploader v0.3.3
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
