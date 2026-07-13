# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.7] - 2026-07-13

### Fixed

- **Kafka descriptor publish: retry + fail closed (#54, epic controlcenter#111).** Observed live during controlcenter#110 onboarding: a cold producer on a GH runner hit `TimeoutException: Topic ... not present in metadata after 5000 ms`, `KafkaPublisher` logged a warning, the run stayed green — and git-diff change detection never re-processed the file, permanently losing IMDb's ODPS. Now: `max.block.ms` raised 5s → 15s (request 10s, delivery 30s), every send and the connectivity probe retry up to 3 attempts with 2s backoff (`KafkaPublisher.retry`, unit-tested), and a due descriptor publish that still fails — or an unreachable broker when a spec publish is due — fails the run with a non-zero exit so the next run retries the same changed files. The #35 sync-status event stays best-effort.

## [0.6.6] - 2026-07-12

### Fixed

- **ODCS gate accepts Workbench-authored v3.1.0 contracts** (#51, epic `controlcenter#99`). The vendored `odcs-json-schema-v3.0.2.json` rejected every real WB contract (`apiVersion` enum, v3.1.0 property shapes); replaced with `odcs-json-schema-latest.json` from the standard repo (v3.2.0…v2.2.0, draft 2019-09). Found live by the epic E2E in `data-contracts-private`. New v3.1.0-shape test.

## [0.6.5] - 2026-07-11

### Changed

- **Org UUID resolves from repo-committed metadata** (#49, epic `controlcenter#99`). The org id for the `x-org-id` header now defaults to the `orgId:` field of `.workbench.yaml` next to the specs (`--dir` in directory mode, the product file's parent in single-file mode) — the Workbench commits it at publish time (bitol.svc#872), so published repos need zero identity setup. `--org-id` / `X_ORG_ID` become overrides. Fail-closed unchanged when Kafka is configured and no source yields a value; the error now names all three sources. `.workbench.yaml` is never treated as a spec.

## [0.6.4] - 2026-07-11

### Added

- **JSON-schema validation before upload** (#46, epic `controlcenter#99`). New `SchemaValidator` validates every `.odps.yaml` / `.odcs.yaml` entry of the exact bundle about to ship against the vendored Bitol schemas (`odps-json-schema-v1.0.0.json`, `odcs-json-schema-v3.0.2.json`, draft 2019-09, classpath resources — offline-safe; source noted in the class javadoc for refreshes). Any violation fails the run (exit ≠ 0) listing file + violation messages, and neither the Zeenea upload nor any Kafka publish happens. Unparseable YAML and unreadable ZIPs are violations, not exceptions. Applies to directory mode, single product YAML, and pre-built ZIPs alike. Dependency: com.networknt:json-schema-validator 1.5.6.

## [0.6.3] - 2026-07-11

### Changed

- **Descriptors publish is now per-product ODPS YAML with the `x-org-id` header** (#45, epic `controlcenter#99`). `KafkaPublisher.publishZip` (whole ZIP as one binary message, keyed by filename, no headers — which the Control Center's `SpecIngestConsumer` could never ingest) is replaced by `publishSpec`: the changed product's ODPS YAML as a UTF-8 string, keyed by the ODPS product id, stamped with the `x-org-id` header (CC issue #81 contract). New `--org-id` flag / `X_ORG_ID` env var carries the authoring tenant's org UUID and is **required whenever Kafka is configured** — the run fails closed instead of publishing records the CC would drop. A pre-built ZIP in `--file` mode no longer publishes anything to the descriptors topic (no ODPS coordinates). `upload-data-products.yml` and `GITHUB_ACTION_SETUP.md` document the new `X_ORG_ID` secret. Zeenea upload behaviour unchanged.

## [0.6.2] - 2026-07-10

### Fixed

- **Sync-status events are now OORS** (#43). `SyncStatusEvent.toJson()` emits an `ObservabilityResults` envelope (`apiVersion` v0.1.0, `kind`, `observedAt`, `source`, one `results[]` entry with id `<artifactId>:<version>`, status `pass`/`fail`, and metadata uploadId/tenant/catalog/error) matching the `bitol.svc` consumer, which was skipping every flat-JSON message ("no results[]"). Kafka key unchanged.
- **Version lockstep**: `pom.xml` had drifted to 0.4.5 while `K.VERSION` was 0.6.1; both now read 0.6.2.

## [0.6.1] - 2026-07-08

### Changed

- **Kafka naming convention** (#41, epic `bitol.svc#843`). Topics renamed: `controlcenter.spec.ingest` -> `controlcenter.dataproduct.descriptors` (constant `K.KAFKA_TOPIC_DESCRIPTORS`), `controlcenter.spec.status` -> `workbench.catalog.feedback` (constant `K.KAFKA_TOPIC_CATALOG_FEEDBACK`). Broker topics pre-created (ops#54); the consuming workflow builds from the default branch, so this lands on merge. Historical changelog entries keep the old names by design.

## [0.6.0] - 2026-06-27

### Added
- **Per-asset Zeenea sync-status events** (#35). The uploader was fire-and-forget — whether an asset actually synced to Zeenea lived only in the GitHub Actions log, invisible to the Libot Services backend (svc) and the Workbench UI. After each per-asset upload it now publishes an append-only status event keyed by `<id>:<version>` to a new Kafka topic `controlcenter.spec.status`, on **both success and failure**. The payload carries `status`, `uploadId`, `at` (ISO-8601), `tenant`, `catalog`, and (on failure) `error`. It reuses the existing `KafkaPublisher` and `KAFKA_*` SASL credentials — no new svc URL, HTTP client, or secret — since Kafka is the dispatcher's only svc-bound transport. The publish is **best-effort**: a failed status report (or unreachable broker) logs a warning and never alters the process exit code; it is skipped when Kafka is unconfigured and for pre-built ZIPs that carry no ODPS coordinates. New `K.KAFKA_TOPIC_SPEC_STATUS`, `model.ProductRef`, `model.SyncStatusEvent`, `ZipBuilder.parseProductRef`, `ZeeneaClient.getLastUploadId`/`getLastError`, and `KafkaPublisher.publishStatus`. Consumer side tracked in `ai.jgp.bitol.svc#758`. Twenty new tests; line coverage 88.3% (gate 0.80). The pom `<version>` is intentionally left unchanged so the published GitHub-Action jar name stays stable (`K.VERSION` is the version of record).

## [0.5.1] - 2026-06-24

### Fixed
- **S3 upload no longer fails with `403 SignatureDoesNotMatch`** (#38). The presigned PUT URL is signed over `host;x-amz-server-side-encryption;x-amz-server-side-encryption-aws-kms-key-id;x-amz-tagging`, but `uploadFile` only sent the two KMS headers — the signed (empty) `x-amz-tagging` was never replayed, so S3's reconstructed canonical request diverged from the signed one. `UploadResponse` now captures the **full** `uploadParameters.headers` map, and `uploadFile` sends every one of them plus any header named in the URL's `X-Amz-SignedHeaders` (except `host`), defaulting absent ones (e.g. `x-amz-tagging`) to an empty value. Future-proof: any header the presigner signs is replayed verbatim.

## [0.5.0] - 2026-06-23

### Added
- **Test runs report to the WB2 Test Log as OORS `ObservabilityResults`** (#36) — joining svc, the webapp, the MCP server and the iOS app in the family-wide test-reporting rollout (`doc/oors-test-rollout.md` in svc). Count metrics, with the `failed` metric carrying the verdict; green is derived server-side and self-resolves at ingest.
- `bin/post-test-run.sh` — the OORS reporter, shared byte-for-byte with svc/mcp (the test use-case stays out of the OORS/OOCS standards themselves, per JGP).
- `bin/test-gate.sh` — runs `mvn verify` (suite + the 0.80 JaCoCo gate), tallies the surefire XML, and posts the summary (`app=dispatcher`, `suite=gate`). Maven's exit code is preserved; the POST never changes it. The dispatcher has no CI workflow of its own (it runs as a step inside other repos' workflows), so reporting is driven by this local gate.
- `README.md` **Testing** section documenting the test directory (`src/test/java/**`) and the reporting convention.

Reporting is shell only — no production-code change, so the 80% JaCoCo gate is unaffected. (`K.VERSION` bump only; the pom `<version>` is intentionally left unchanged so the published GitHub-Action jar name is stable.)


## [0.4.6] - 2026-06-05

### Fixed
- **`ZipBuilder` now normalises a contract's internal `version` to match the product output-port reference when they differ only by the leading `v` prefix** (#33). Previously a product referencing a contract as `v0.2.1` while the contract's own `version` was `0.2.1` (or vice-versa) produced a ZIP that Zeenea rejected with "no matching data contract". The pass is conservative — it only adjusts the `v` prefix, never the numeric portion; already-consistent or genuinely-different versions are left byte-for-byte untouched. A `WARNING` is logged whenever the normalisation fires so the upstream root-cause fix (`ai.jgp.bitol.svc#595`) can be tracked. Added `ZipBuilderTest` coverage for the prefix-only fix, the no-op consistent case, the numeric-mismatch left-alone case, the missing-version guard, the warning, and an end-to-end `buildFromProduct` check.

## [0.4.5] - 2026-05-22

### Added
- **End-to-End Process** section in `README.md` documenting the full pipeline from a Workbench publish through GitHub Actions, change detection, ZIP building, the Zeenea upload, and the optional Kafka publish — including a Mermaid `sequenceDiagram` of the flow.

### Fixed
- Corrected the stale `How It Works` step 5: the dispatcher publishes the **entire spec ZIP as a single binary message** to `controlcenter.spec.ingest` (per `KafkaPublisher.publishZip`), not per-YAML `deliver-spec` envelopes.

## [0.4.4] - 2026-05-19

### Added
- `AppTest` covering `App#main` across single-file ZIP, single-file `.odps.yaml`, directory mode (no changes, all success, partial failure, ZIP build error), debug logging, and the Kafka publish branches (configured + connected, configured + unreachable, publish exception). Uses Mockito `mockConstruction` to swap in mock `ZeeneaClient` and `KafkaPublisher` instances and `MockedStatic` to stub `ZipBuilder.findChangedProducts` / `buildFromProduct`.
- Negative-path tests for `CliConfig.parse` (unknown flag, missing value, missing file/dir, mutually exclusive `--file`/`--dir`, missing tenant/api-key, nonexistent file/dir, `--help`, `-h`, `--version`, `-v`, blank env var, env-var fallbacks for all options including `--dir`). Verifies the exit codes via `system-stubs-jupiter`'s `catchSystemExit`.

### Changed
- Added `uk.org.webcompere:system-stubs-jupiter:2.1.7` as a test-scope dependency so tests can capture `System.exit` calls without a refactor. Surefire `argLine` now passes `-Djava.security.manager=allow` because JDK 21 forbids `setSecurityManager` otherwise and system-stubs installs a `NoExitSecurityManager` to intercept exits.
- Raised the JaCoCo `jacoco.line.coverage` threshold from `0.60` to `0.80`. Actual line coverage is now **87.05%** (511/587 covered), satisfying the audit rule from #283 and AC-4 of #286.

## [0.4.3] - 2026-05-19

### Changed
- Raised the JaCoCo `jacoco.line.coverage` threshold from the `0.00` placeholder to `0.60`, so the build now fails if dispatcher line coverage drops below 60%. The umbrella user story (US-0057, #214) targets 80%, but the dispatcher's `App` entrypoint is currently uncoverable without a refactor (every path calls `System.exit`); 60% is the realistic floor the existing test suite leaves us. Tightening to 80% is tracked in a follow-up "Claude:" note on #214.

## [0.4.2] - 2026-05-19

### Added
- Initial unit-test suite for the dispatcher under `src/test/java/ai/jgp/gha/dataproduct/`, covering `UploadResponse`, `CliConfig`, `ZipBuilder`, `ZeeneaClient`, and `KafkaPublisher`. Tests use JUnit Jupiter + Mockito; the HTTP boundary of `ZeeneaClient` is exercised through a mocked `HttpClient` and the Kafka publisher is verified against an unreachable broker so the unconnected branch is covered. This is the first batch of coverage tests for `ai.jgp.dispatcher` (closes #212, US-0055).

### Changed
- Added a package-private constructor seam to `ZeeneaClient` accepting an `HttpClient`, so tests can inject a mock without the production callers changing. This is the only source change required to make the upload flow testable.

## [0.4.1] - 2026-05-19

### Added
- Wired the `jacoco-maven-plugin` into `pom.xml` so test runs produce a coverage report at `target/site/jacoco/`. The `check` rule is configured with a placeholder `jacoco.line.coverage` threshold of `0.00` so existing builds keep passing; the threshold is tightened once real test classes land (closes #286).
- Added JUnit Jupiter 5.10.2 and Mockito 5.12.0 as test-scope dependencies, and a `KTest` smoke test covering the `K` constants holder so `mvn test` has something to instrument and the JaCoCo wiring is exercised end-to-end.

### Changed
- Bumped Maven Surefire to 3.2.5 with `@{argLine}` propagation so the JaCoCo agent is picked up automatically by the test JVM.

## [0.4.0] - 2026-03-05

### Changed
- Minor version bump.

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
