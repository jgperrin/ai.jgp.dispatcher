# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.4] - 2026-03-03

### Fixed
- Added `workflow_dispatch:` trigger to `upload-data-products.yml` template so the workflow can be triggered manually from the GitHub UI or via the API (used by the Workbench Publish wizard)
- Updated `GITHUB_ACTION_SETUP.md` to document manual dispatch and added troubleshooting entry for missing `workflow_dispatch` trigger

## [0.3.3] - 2026-03-02

### Fixed
- Fixed workflow template: changed repo reference from `jgperrin/ai.jgp.gha.data-product-uploader` (old name) to `jgperrin/ai.jgp.dispatcher`
- Synced pom.xml version with K.java version (was 0.2.5, now 0.3.3)
- Updated CLAUDE.md project name from "GitHub Action Uploader" to "Dispatcher"
- Updated JAR version references in README.md and bin/upload.sh

## [0.3.2] - 2026-02-23

### Fixed
- Fixed `--dir` mode: glob pattern `podem/**/*.odps.yaml` was not matching files via `ProcessBuilder` (no shell expansion). Now passes just the directory to `git diff` and filters `.odps.yaml` in Java.
- Added diagnostic output: prints git diff command, exit code, all changed files, and product files to process.

## [0.3.1] - 2026-02-23

### Added
- `--dir` flag to scan a directory for changed `.odps.yaml` files using `git diff` (replaces shell-based detection).
- Directory mode processes each changed product individually: builds versioned ZIP, uploads to Zeenea, publishes to Kafka.

### Changed
- GHA workflow template simplified to a single `java -jar ... --dir podem` command — all logic now in Java.

## [0.3.0] - 2026-02-23

### Changed
- GHA workflow template now triggers on both `main` and `dev` branches.

## [0.2.16] - 2026-02-23

### Fixed
- Fixed git tag format to match bitol service's v3 enhanced publish: tags are now `contract-{contractId}-v{version}` (with `contract-` prefix), not `{contractId}-v{version}`.

## [0.2.15] - 2026-02-23

### Changed
- Contract content is now retrieved at the correct version using git tags (format: `<contractId>-v<version>`). Falls back to current file on disk if the tag is not found.
- GHA workflow now checks out with full history and tags (`fetch-depth: 0`, `fetch-tags: true`) to support versioned contract retrieval.

## [0.2.14] - 2026-02-23

### Added
- Smart ZIP building: `--file` now accepts a `.odps.yaml` product file. The uploader parses the product, resolves contract references from `outputPorts`, and builds a versioned ZIP containing only the product and its contracts (e.g. `<id>-v<version>.odps.yaml`, `<contractId>-v<portVersion>.odcs.yaml`).
- New `ZipBuilder` class for versioned ZIP construction from product YAML.

### Changed
- GHA workflow template now uses `git diff` to detect changed product files and processes each one individually (instead of zipping all files in `podem/`).
- Re-added `jackson-dataformat-yaml` dependency for YAML parsing in ZipBuilder.

## [0.2.13] - 2026-02-23

### Changed
- Kafka publishing now sends the entire ZIP as a single binary message instead of individual YAML specs. Removes per-file YAML parsing and OOCS envelope wrapping.
- Removed `jackson-dataformat-yaml` dependency (no longer needed).
- ZIP files should use versioned filenames: `<id>-v<version>.odps.yaml` / `<id>-v<version>.odcs.yaml`.

## [0.2.12] - 2026-02-23

### Changed
- Extracted GHA workflow template into standalone [`upload-data-products.yml`](upload-data-products.yml) for easier automatic deployment by the Workbench.
- Simplified `GITHUB_ACTION_SETUP.md` to reference the standalone file instead of embedding the full YAML.

## [0.2.11] - 2026-02-21

### Fixed
- Removed duplicate `v` prefix in Kafka publish output (was showing `vv0.1.0` when version already contains `v`).

## [0.2.10] - 2026-02-21

### Changed
- SSL trust: auto-fetches the broker's server certificate at runtime and creates a temporary JKS truststore. No external truststore file needed. Connection is still TLS-encrypted.
- Removed static truststore path (`~/.kafka/kafka.client.truststore.jks`) — no longer required.
- **Tech debt**: certificate verification is relaxed (hostname check disabled, server cert auto-trusted). Replace with proper CA trust management when feasible.

## [0.2.9] - 2026-02-21

### Changed
- Kafka diagnostics now explicitly print resolved user and password (masked) values to help debug authentication issues (e.g. missing `KAFKA_USERNAME` env var).

## [0.2.8] - 2026-02-21

### Changed
- Kafka probe failure now prints a clean one-line message instead of a Java stack trace.
- Connection diagnostics now show topic name and timeout values for easier troubleshooting.

## [0.2.7] - 2026-02-21

### Fixed
- Suppressed Kafka's verbose internal logging (config dumps, disconnect spam) — only SEVERE errors are logged from `org.apache.kafka`.
- Reduced Kafka producer timeouts (`max.block.ms=5s`, `request.timeout.ms=5s`, `delivery.timeout.ms=10s`) so the connectivity probe actually cuts off within 5 seconds.
- `close()` now uses a 2-second timeout instead of blocking indefinitely on flush when broker is unreachable.

## [0.2.6] - 2026-02-21

### Fixed
- Kafka connectivity is now probed upfront with a 5-second timeout. If the broker is unreachable, publishing is skipped immediately with a warning instead of hanging per message. Kafka failures no longer block the workflow — they are treated as warnings.

## [0.2.5] - 2026-02-19

### Fixed
- Kafka username env var renamed from `KAFKA_USER` to `KAFKA_USERNAME` to match the actual secret name. Updated K.java, CLI help, README, and GitHub Action docs.

## [0.2.4] - 2026-02-19

### Changed
- Removed `ensureTopicExists` — no longer attempts to create the Kafka topic (assumes it exists).
- Reduced Kafka producer timeouts for faster failure: `max.block.ms=10s`, `request.timeout.ms=10s`, `delivery.timeout.ms=15s` (down from 60s defaults).
- Connection diagnostics printed before publishing: broker URL, auth mode, truststore status.

### Fixed
- SLF4J binding: switched from `slf4j-simple` 2.0.x to `slf4j-jdk14` 1.7.36 to match kafka-clients' SLF4J 1.7.x API. Kafka internal logs now route through `java.util.logging` (visible with `--debug`).

## [0.2.3] - 2026-02-19

### Fixed
- Kafka topic check is now best-effort: if `ensureTopicExists` times out (e.g. network/auth issue with AdminClient), publishing proceeds anyway instead of aborting.
- Eliminated SLF4J "Failed to load class" warning by adding `slf4j-simple` binding dependency.

## [0.2.2] - 2026-02-19

### Added
- Debug traces throughout the Kafka publishing flow (visible with `--debug` flag): broker connection details, topic existence check, ZIP entry processing, YAML parsing results, and envelope size before send.

## [0.2.1] - 2026-02-19

### Fixed
- Kafka topic auto-creation: the publisher now creates the `controlcenter.spec.ingest` topic if it does not exist on the broker, preventing `TimeoutException`.
- Kafka publishing is now synchronous — "Published:" output only appears after a successful send, not before the result is known.
- Publishing summary now reports both published and failed counts.

## [0.2.0] - 2026-02-19

### Added
- Kafka publishing support: after Zeenea upload, each YAML spec from the ZIP is published to the `controlcenter.spec.ingest` Kafka topic for Control Center ingestion.
- New `KafkaPublisher` class using plain `kafka-clients` with SASL_SSL + SCRAM-SHA-512 authentication.
- CLI flags `--kafka-broker`, `--kafka-user`, `--kafka-password` with env var fallbacks (`KAFKA_BROKER_URL`, `KAFKA_USER`, `KAFKA_PASSWORD`).
- Kafka is optional — publishing is skipped when no broker is configured.
- OOCS `deliver-spec` envelope wrapping for each published spec.
- Automatic kind detection from filename (`.odps.yaml` -> DataProduct, `.odcs.yaml` -> DataContract).
- YAML parsing via Jackson to extract `id` and `version` from each spec.
- Dependencies: `kafka-clients` 3.8.1, `jackson-dataformat-yaml` 2.17.0.

## [0.1.6] - 2026-02-10

### Fixed
- Empty environment variables (e.g. unset GitHub Secrets) now correctly fall back to defaults instead of being treated as valid values. Fixes `ZEENEA_CATALOG` defaulting to empty string instead of `"default"`.

## [0.1.5] - 2026-02-10

### Added
- Apache License 2.0 (`LICENSE` file).
- Comprehensive `README.md` with usage instructions, CLI reference, and example output.

### Changed
- Repository is now public — removed `UPLOADER_PAT` secret requirement from `GITHUB_ACTION_SETUP.md`.
- Simplified GitHub Actions checkout step (no longer needs a PAT token).

## [0.1.4] - 2026-02-10

### Fixed
- GitHub Actions workflow: added PAT-based authentication for checking out the private uploader repository (`UPLOADER_PAT` secret + `token` parameter on `actions/checkout`).
- Added PAT creation instructions and troubleshooting entry to `GITHUB_ACTION_SETUP.md`.

## [0.1.3] - 2026-02-10

### Added
- `GITHUB_ACTION_SETUP.md` — step-by-step guide for setting up the uploader as a GitHub Actions workflow, triggered on `.odps.yaml` changes in `podem/` merged to `main`.

## [0.1.2] - 2026-02-10

### Added
- `--url` flag and `ZEENEA_URL` env var for configurable base URL (overrides tenant-based URL).
- `--debug` flag to enable debug-level logging of all HTTP requests and responses.
- `java.util.logging`-based debug logging in `ZeeneaClient` for full HTTP communication tracing.

### Fixed
- `UploadResponse` JSON parsing to match actual API response structure (`uploadParameters.url`, `uploadParameters.headers`, root-level `maximumFileSizeInBytes`).
- Poll status response parsing — `processed`, `upserted`, and `errors` are nested inside `result` object.

## [0.1.1] - 2026-02-10

### Added
- `bin/upload.sh` convenience shell wrapper with auto-build.

### Improved
- Better error diagnostics: exception class name and root cause now printed on failure.
- Updated `.gitignore` with Maven `target/`, IDE files, and `.env` patterns.

## [0.1.0] - 2026-02-10

### Added
- Initial release.
- CLI tool to upload ZIP data product descriptors to Zeenea Data Catalog.
- Support for `--file`, `--tenant`, `--api-key`, and `--catalog` flags.
- Environment variable fallbacks (`ZEENEA_FILE`, `ZEENEA_TENANT`, `ZEENEA_API_KEY`, `ZEENEA_CATALOG`).
- 4-step API workflow: request upload URL, upload ZIP, trigger processing, poll status.
