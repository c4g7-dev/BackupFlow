# Changelog

All notable changes to BackupFlow will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [0.1.2] - 2025-09-22
### Added
- **Reliability & Diagnostics**
  - Phase instrumentation with backup states (IDLE, PREPARING, SCANNING, COMPRESSING, UPLOADING)
  - Watchdog system for automatic detection and recovery from stuck backup states
  - Cancel command (`/backupflow cancel`) with permission `backupflow.cancel` for emergency termination
  - Enhanced diagnostics showing elapsed time, active thread, progress, and stuck detection
  - Status command with real-time backup progress and detailed phase information
  - Reload command for dynamic config updates without restart (recreates S3 storage)

- **Performance Optimizations**
  - ETA & throughput calculations with pre-scan for accurate progress estimation
  - Size breakdown logging per-root path (worlds, plugins, configs, extra) for diagnostics
  - Directory scan timeouts (30-second limit) to prevent hanging on slow network mounts
  - Hash-based incremental backup using SHA-256 to skip unchanged backups
  - Upload optimization with buffered streams and larger part sizes for faster S3 transfers
  - Exclusion logic to prevent recursive backup by filtering BackupFlow's temp directories

- **Configuration Options**
  - `incremental.enabled` for hash-based change detection
  - `phaseStaleSeconds` for watchdog timeout configuration
  - `hardTimeoutSeconds` for maximum backup duration limits
  - `timestampCacheSeconds` for tab completion cache lifetime

### Changed
- Improved async state management to prevent race conditions
- Enhanced error handling for better recovery from early exceptions
- Optimized memory usage with streaming operations and efficient buffer sizes
- Better network efficiency through compressed archives with temporary file exclusion

### Fixed
- Resolved backup state getting stuck in "running" status after exceptions
- Fixed serverId display in diagnostics output
- Prevented recursive backup issues by excluding plugin temp directories
- Improved thread safety in backup operations

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
