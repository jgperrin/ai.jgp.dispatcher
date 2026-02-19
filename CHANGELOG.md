# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
