# Release Draft Template

Use this template when drafting a GitHub Release.

## Tag
`v0.1.0`

## Title
BackupFlow v0.1.0 – Initial Release

## Summary
First public release including full backup pipeline, manifests with optional integrity hashes, selective async restore, async verify, and retention plan preview.

## Highlights
- Full worlds + plugins + configs backup
- Wildcard include (`*`) auto detection
- SHA-256 per-file integrity hashing (manifest)
- Async selective restore (`--select`) and verify
- Retention plan preview (`/backupflow retention plan`)
- Granular permissions per command

## Permissions
```
backupflow.admin
backupflow.backup
backupflow.restore
backupflow.verify
backupflow.retention
backupflow.list
backupflow.manifests
backupflow.version
```

## Checksums (attach after build)
```
# Example (fill in actual)
sha256  BackupFlow-0.1.0-all.jar  <hash>
```

## Upgrade Notes
- Verify `integrity.hashes` in config.yml (defaults true).
- Ensure environment variable `BACKUPFLOW_SERVER_ID` is set uniquely per server.

## Roadmap Next
- Tar.gz compression
- Incremental/deduplicated backups
- Automated prune executor
- Differential chunk mode

---
Generated on 2025-09-22.
