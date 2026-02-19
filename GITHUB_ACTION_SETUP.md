# Setting Up the Data Product Uploader as a GitHub Action

This guide explains how to automatically upload your data product descriptors to the Actian Data Intelligence Platform (Zeenea) and optionally publish them to the Data Product Control Center via Kafka, every time an ODPS or ODCS file is merged into your `main` branch.

## How It Works

1. You keep your data product descriptors (`.odps.yaml` and `.odcs.yaml` files) in a `podem/` directory in your repository.
2. When a pull request modifying any `.odps.yaml` or `.odcs.yaml` file is merged into `main`, a GitHub Actions workflow automatically:
   - Packages the spec files into a ZIP
   - Uploads the ZIP to the Actian Data Intelligence Platform using the API
   - Publishes each spec to the `controlcenter.spec.ingest` Kafka topic (if Kafka is configured)
3. The platform processes the descriptors and creates or updates the corresponding data products in the catalog.
4. The Control Center receives the specs and transitions data products to `SPEC_READY` status.

## Prerequisites

- A GitHub repository containing your `.odps.yaml` and `.odcs.yaml` files in a `podem/` directory.
- An Actian Data Intelligence Platform account with API access.
- An API key (X-API-SECRET) with **Admin** or **Scanner** permission scope, generated from the platform's Administration page.
- (Optional) A Kafka broker accessible from GitHub Actions runners, with SASL credentials for the `controlcenter.spec.ingest` topic.

## Step 1: Store Secrets in GitHub

Credentials must **never** be committed to your repository. GitHub Secrets store them securely and make them available to workflows.

1. Go to your repository on GitHub.
2. Click **Settings** (the gear icon tab at the top of the repository page).
3. In the left sidebar, click **Secrets and variables** > **Actions**.
4. Click the **New repository secret** button.
5. Add each secret from the tables below.

### Zeenea secrets (required)

| Secret Name      | Description                                         | Required |
|------------------|-----------------------------------------------------|----------|
| `ZEENEA_API_KEY` | Your X-API-SECRET from the platform Administration  | Yes      |
| `ZEENEA_TENANT`  | Your tenant name (e.g. `my-company`)                | Yes      |
| `ZEENEA_URL`     | Custom base URL (only if not using production)      | No       |
| `ZEENEA_CATALOG` | Catalog code (defaults to `default` if not set)     | No       |

### Kafka secrets (optional)

If you want the uploader to publish specs to the Data Product Control Center via Kafka, add these secrets. If omitted, the Zeenea upload still works — Kafka publishing is silently skipped.

| Secret Name        | Description                                            | Required |
|--------------------|--------------------------------------------------------|----------|
| `KAFKA_BROKER_URL` | Kafka broker URL (e.g. `cloud.jgp.ai:9093`)           | No       |
| `KAFKA_USER`       | SASL username for Kafka SCRAM-SHA-512 authentication   | No       |
| `KAFKA_PASSWORD`   | SASL password for Kafka authentication                 | No       |

## Step 2: Create the Workflow File

In your repository, create the file `.github/workflows/upload-data-products.yml` with the following content:

```yaml
name: Upload Data Products

# Trigger: when spec files in podem/ are changed on main
on:
  push:
    branches:
      - main
    paths:
      - 'podem/**/*.odps.yaml'
      - 'podem/**/*.odcs.yaml'

jobs:
  upload:
    runs-on: ubuntu-latest

    steps:
      # 1. Check out the repository
      - name: Checkout repository
        uses: actions/checkout@v4

      # 2. Set up Java 21
      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      # 3. Check out the uploader tool (public repo)
      - name: Checkout uploader
        uses: actions/checkout@v4
        with:
          repository: jgperrin/ai.jgp.gha.data-product-uploader
          path: uploader

      - name: Build uploader
        run: mvn -q -f uploader/pom.xml clean package

      # 4. Package spec files into a ZIP
      - name: Package spec files
        run: |
          cd podem
          zip -r ../data-products.zip *.odps.yaml *.odcs.yaml
          cd ..
          echo "Packaged $(zipinfo -1 data-products.zip | wc -l) file(s) into data-products.zip"

      # 5. Upload to Zeenea and publish to Kafka
      - name: Upload and publish
        env:
          ZEENEA_API_KEY: ${{ secrets.ZEENEA_API_KEY }}
          ZEENEA_TENANT: ${{ secrets.ZEENEA_TENANT }}
          ZEENEA_URL: ${{ secrets.ZEENEA_URL }}
          ZEENEA_CATALOG: ${{ secrets.ZEENEA_CATALOG }}
          KAFKA_BROKER_URL: ${{ secrets.KAFKA_BROKER_URL }}
          KAFKA_USER: ${{ secrets.KAFKA_USER }}
          KAFKA_PASSWORD: ${{ secrets.KAFKA_PASSWORD }}
        run: |
          java -jar uploader/target/data-product-uploader-*.jar \
            --file data-products.zip
```

## Step 3: Commit and Push the Workflow

```bash
git add .github/workflows/upload-data-products.yml
git commit -m "Add workflow to upload data products on merge"
git push
```

## How to Use It

Once the workflow is in place, your day-to-day process is:

1. **Edit or add** `.odps.yaml` and `.odcs.yaml` files in the `podem/` directory on a feature branch.
2. **Open a pull request** to `main`.
3. **Merge** the pull request.
4. The workflow runs automatically --- you can monitor it in the **Actions** tab of your repository.

The workflow **only triggers** when `.odps.yaml` or `.odcs.yaml` files inside `podem/` are changed. Other changes to `main` (documentation, code, etc.) will not trigger an upload.

## Checking Workflow Results

1. Go to the **Actions** tab in your repository.
2. Click on the latest "Upload Data Products" run.
3. Expand the "Upload and publish" step to see the output, which will look like:

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
Kafka publishing complete: 2 spec(s) published.
```

If the upload fails, the workflow will show a red X and you can inspect the error in the logs.

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|-------------|-----|
| Workflow does not trigger | Files are not in `podem/` or don't end with `.odps.yaml`/`.odcs.yaml` | Check file paths and extensions |
| `Error: --api-key is required` | Secret `ZEENEA_API_KEY` is not set | Add it in Settings > Secrets |
| `Error: --tenant is required` | Secret `ZEENEA_TENANT` is not set | Add it in Settings > Secrets |
| `HTTP 500: Permission denied` | API key lacks required permissions | Regenerate key with Admin or Scanner scope |
| `HTTP 500` on processing | Missing or invalid `catalogCode` | Set `ZEENEA_CATALOG` secret or check catalog name |
| `Error publishing to Kafka` | Broker unreachable or bad credentials | Verify `KAFKA_BROKER_URL`, `KAFKA_USER`, `KAFKA_PASSWORD` secrets |
| Kafka publishing skipped | `KAFKA_BROKER_URL` secret not set | Add it in Settings > Secrets (Kafka is optional) |
