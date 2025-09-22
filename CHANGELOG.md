# Changelog

All notable changes to BackupFlow will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [0.1.1] - 2025-09-22
### Added
- Granular permission node enforcement in code.
- Expanded README with restore/verify/retention + permissions table.
- CHANGELOG and release template files.
- Apache 2.0 LICENSE file within module for clarity.

### Changed
- Default permissions: list/manifests/version now open to all players.

### Security
- Explicit permission checks reduce accidental overexposure of restore/verify operations.


## [0.1.0] - 2025-09-22
### Added
- Initial public release.
- Full world + plugins + configs backup to S3/MinIO.
- Wildcard include (`*`) auto-detect worlds and implicit plugin/config include.
- ZIP compression pipeline with SHA-256 per-file hashing.
- Manifest JSON (with optional hashes map) stored in bucket.
- Manual and scheduled backups with jitter.
- Selective async restore (`--select worlds,plugins,configs,extra`).
- Async verify command comparing archive contents to manifest hashes.
- Retention plan preview command (age- / count-based hints).
- Granular permission nodes per command.

### Planned
- Tar.gz compression option.
- Incremental/deduplicated backups.
- Automated retention pruning executor.
- Partial differential chunk mode.

[0.1.0]: https://github.com/c4g7-dev/BackupFlow/releases/tag/v0.1.0
