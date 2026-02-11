# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
