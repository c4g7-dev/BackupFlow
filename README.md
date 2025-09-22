# 💾 BackupFlow

Cloud‑native, incremental-ready backup system for Minecraft Paper/Purpur 1.21+. Push compressed world + plugin snapshots directly to S3/MinIO (or any S3-compatible storage). Designed for multi-server fleets, containerized deployments, and hands-off reliability.

[![License](https://img.shields.io/github/license/c4g7-dev/SchemFlow?style=for-the-badge)](../SchemFlow/LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)](https://openjdk.org/)
[![Paper](https://img.shields.io/badge/Paper-1.21+-00ADD8?style=for-the-badge&logo=minecraft)](https://papermc.io/)

---
## What is it?
BackupFlow centralizes automated world + config + plugin backups in object storage. Each server pushes into a shared logical namespace using a unique `serverId`. Future versions will support chunk/incremental diffing.

## Why use BackupFlow?
- One standard workflow across all servers
- Object storage durability + versioning
- Fast, compressed ZIP output
- Works great with container or ephemeral nodes
- Simple restore path using manifest JSON
- Low overhead: build → compress → upload → clean

---
## Features
- Worlds + Nether + End + optional extra paths
- Optional plugin + config inclusion
- ZIP compression (tar.gz planned)
- Randomized jitter scheduling to avoid cluster spikes
- Manifests stored alongside backups (JSON)
- S3/MinIO layout under configurable `rootDir`
- Multi-server isolation via `serverId`
- Manual + scheduled backups
- Basic listing of backups + manifests

Planned / Roadmap:
- Incremental/chunk-based deduplication (content addressing)
- Automatic lifecycle pruning (client + bucket policy synergy)
- Integrity hash manifest (SHA-256 map)
- Selective restore (single world / plugin folder)

---
## Storage Layout
```
FlowStack/BackupFlow/
  backups/<serverId>/full/<epochMillis>/full-<epoch>.zip
  manifests/<serverId>-<epoch>-<rand>.json
```

Example manifest (simplified):
```json
{
  "timestamp": 1737574755000,
  "serverId": "lobby",
  "reason": "scheduled",
  "files": ["full-1737574755000.zip"]
}
```

---
## Installation
1. Drop `BackupFlow-<version>-all.jar` into `plugins/`
2. Start server to generate `config.yml`
3. Configure S3 credentials + bucket
4. (Optional) Set `BACKUPFLOW_SERVER_ID` env var per instance
5. Reload or restart

---
## Quick Start Commands
```
/backupflow help         # list commands
/backupflow backup       # run a full backup now
/backupflow list         # list backup timestamps
/backupflow manifests    # list manifest objects
/backupflow version      # show plugin version
```

---
## Configuration (config.yml excerpt)
```yaml
s3:
  endpoint: "play.minio.local:9000"
  secure: true
  accessKey: "ACCESS_KEY"
  secretKey: "SECRET_KEY"
  bucket: "backups"
  rootDir: "FlowStack/BackupFlow"

backup:
  include:
    worlds: ["world", "world_nether", "world_the_end"]
    plugins: true
    configs: true
    extraPaths: []
  compression: zip
  retention:
    enableLifecycle: false
    maxLocalEntries: 10
  schedule:
    enabled: true
    intervalMinutes: 60
    jitterSeconds: 30

restore:
  allowDirectDownload: true
  tempDir: "plugins/BackupFlow/work/tmp"
  restoreDir: "restores"

manifest:
  storeInBucket: true
  prefix: "manifests"
```

---
## Permissions
| Node | Purpose | Default |
|------|---------|---------|
| `backupflow.admin` | All commands | op |

(Granular nodes will be added with restore & incremental features.)

---
## Environment Variables
| Variable | Purpose | Example |
|----------|---------|---------|
| `BACKUPFLOW_SERVER_ID` | Overrides derived server identifier | `mini-lobby-01` |

---
## Restore (Manual Outline)
1. Locate manifest JSON via `/backupflow manifests` or S3 console
2. Download associated `full-<epoch>.zip`
3. Stop server
4. Extract desired folders (e.g., `world/`) into server root
5. Start server

Automated selective restore command is planned.

---
## Roadmap Snapshot
| Feature | Status |
|---------|--------|
| Full compressed backups | ✅ |
| Multi-server separation | ✅ |
| Manifests | ✅ |
| Scheduling w/ jitter | ✅ |
| Tar.gz option | 🔜 |
| Incremental/dedup | 🔜 |
| Selective restore | 🔜 |
| Integrity hashes | 🔜 |

---
## Contributing
PRs welcome! Keep style consistent with other FlowStack plugins (SchemFlow). Avoid over-engineering early incremental logic—stage features behind clear interfaces.

---
## License
Apache-2.0. See parent repository LICENSE.

---
**BackupFlow** – Reliable backups, minimal friction.
