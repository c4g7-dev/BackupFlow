package com.c4g7.backupflow;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class BackupFlowPlugin extends JavaPlugin {
    private BackupStorageService storage;
    private FileConfiguration cfg;
    private int taskId = -1;
    private String serverId;
    private volatile boolean backupRunning = false;
    private String prefix;
    private long lastBackupStart = 0L;
    private long lastBackupEnd = 0L;
    private java.util.List<String> cachedTimestamps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private long lastTimestampCacheAt = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        serverId = detectServerId();
        try {
            storage = new BackupStorageService(
                    cfg.getString("s3.endpoint"),
                    cfg.getBoolean("s3.secure", true),
                    cfg.getString("s3.accessKey"),
                    cfg.getString("s3.secretKey"),
                    cfg.getString("s3.bucket"),
                    cfg.getString("s3.rootDir"),
                    serverId
            );
        } catch (Exception e) {
            getLogger().severe("Failed to init S3 storage: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        registerCommands();
        initPrefix();
        scheduleAutoBackup();
        listOnStartup();
        getLogger().info("BackupFlow enabled. ServerId=" + serverId);
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        if (storage != null) storage.close();
    }

    private String detectServerId() {
        String id = System.getenv("BACKUPFLOW_SERVER_ID");
        if (id != null && !id.isBlank()) return id.trim();
        // fallback: derive from current working directory name (server root folder)
        try {
            Path cwd = Path.of("").toAbsolutePath().normalize();
            String name = cwd.getFileName() != null ? cwd.getFileName().toString() : null;
            if (name != null && !name.isBlank()) return name.replaceAll("[^a-zA-Z0-9-_]","-");
        } catch (Exception ignored) { }
        return "srv" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }

    private void initPrefix() {
        boolean color = getConfig().getBoolean("color.enabled", true);
        if (color) {
            this.prefix = "§b§lB§3§lF§r§7 » §r"; // colored
        } else {
            this.prefix = "[BackupFlow] ";
        }
    }

    public String pref() { return prefix; }

    private void listOnStartup() {
        if (!cfg.getBoolean("autoListOnStart", false)) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var list = storage.listBackups("full");
                getLogger().info("Found " + list.size() + " backups (full)");
            } catch (Exception ex) {
                getLogger().warning("List on start failed: " + ex.getMessage());
            }
        });
    }

    private void registerCommands() {
        var cmd = getCommand("BackupFlow");
        if (cmd != null) {
            var executor = new com.c4g7.backupflow.cmd.BackupFlowCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }

    private void scheduleAutoBackup() {
        if (!cfg.getBoolean("backup.schedule.enabled", true)) return;
        long intervalMinutes = cfg.getLong("backup.schedule.intervalMinutes", 60L);
        long ticks = intervalMinutes * 60L * 20L;
        long jitter = cfg.getLong("backup.schedule.jitterSeconds", 30L) * 20L;
        long delay = 100L + ThreadLocalRandom.current().nextLong(jitter + 1);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (backupRunning) {
                getLogger().info("Skip scheduled backup: previous still running");
                return;
            }
            startBackupAsync("scheduled", null);
        }, delay, ticks);
    }

    public void runBackup(String reason) throws Exception {
        Instant ts = Instant.now();
        lastBackupStart = System.currentTimeMillis();
        String prefix = storage.beginFullBackupKeyPrefix(ts);
        Path tempRoot = ensureTemp();
        Path buildDir = Files.createTempDirectory(tempRoot, "bf-build-");
        try {
            collectSources(buildDir);
            boolean wantHashes = cfg.getBoolean("integrity.hashes", true);
            var comp = com.c4g7.backupflow.util.CompressionUtils.compress(buildDir, cfg.getString("backup.compression", "zip"), wantHashes);
            String fileName = "full-" + ts.toEpochMilli() + "." + (cfg.getString("backup.compression", "zip").equalsIgnoreCase("gz") ? "tar.gz" : "zip");
            storage.uploadFile(comp.archive, prefix + fileName);
            if (cfg.getBoolean("manifest.storeInBucket", true)) {
                Path manifest;
                if (wantHashes && comp.hashes != null && !comp.hashes.isEmpty()) {
                    manifest = com.c4g7.backupflow.util.ManifestBuilder.writeManifestWithHashes(tempRoot, storage.randomManifestName(ts), reason, serverId, List.of(fileName), comp.hashes);
                } else {
                    manifest = com.c4g7.backupflow.util.ManifestBuilder.writeSimpleManifest(tempRoot, storage.randomManifestName(ts), reason, serverId, List.of(fileName));
                }
                storage.uploadFile(manifest, storage.manifestObjectName(manifest.getFileName().toString()));
            }
            lastBackupEnd = System.currentTimeMillis();
            getLogger().info("Backup complete: " + fileName + " (reason=" + reason + ") took " + (lastBackupEnd - lastBackupStart) + "ms");
            // refresh timestamp cache in background
            refreshTimestampCacheAsync(true);
        } finally {
            com.c4g7.backupflow.util.FileUtils.deleteQuietly(buildDir);
        }
    }

    public boolean startBackupAsync(String reason, org.bukkit.command.CommandSender initiator) {
        if (backupRunning) {
            if (initiator != null) initiator.sendMessage("§cBackup already running");
            return false;
        }
        backupRunning = true;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                runBackup(reason);
                if (initiator != null) initiator.sendMessage("§aBackup completed.");
            } catch (Exception ex) {
                getLogger().warning("Backup failed: " + ex.getMessage());
                if (initiator != null) initiator.sendMessage("§cBackup failed: " + ex.getMessage());
            } finally {
                backupRunning = false;
            }
        });
        return true;
    }

    public boolean isBackupRunning() { return backupRunning; }

    public long getLastBackupDuration() { return lastBackupEnd > lastBackupStart ? (lastBackupEnd - lastBackupStart) : 0L; }
    public long getLastBackupEnd() { return lastBackupEnd; }

    public java.util.List<String> getCachedTimestamps() {
        int ttl = getConfig().getInt("timestampCacheSeconds", 60);
        long now = System.currentTimeMillis();
        if (ttl <= 0 || (now - lastTimestampCacheAt) > ttl * 1000L) {
            refreshTimestampCacheAsync(false);
        }
        return cachedTimestamps;
    }

    public void refreshTimestampCacheAsync(boolean force) {
        int ttl = getConfig().getInt("timestampCacheSeconds", 60);
        long now = System.currentTimeMillis();
        if (!force && ttl > 0 && (now - lastTimestampCacheAt) < ttl * 1000L) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var list = storage.listBackups("full");
                list.sort(String::compareTo);
                cachedTimestamps.clear();
                cachedTimestamps.addAll(list);
                lastTimestampCacheAt = System.currentTimeMillis();
            } catch (Exception ex) {
                getLogger().warning("Timestamp cache refresh failed: " + ex.getMessage());
            }
        });
    }

    public boolean reloadBackupFlowConfig() {
        try {
            reloadConfig();
            cfg = getConfig();
            initPrefix();
            // Recreate storage with new config values
            BackupStorageService old = this.storage;
            try {
                this.storage = new BackupStorageService(
                        cfg.getString("s3.endpoint"),
                        cfg.getBoolean("s3.secure", true),
                        cfg.getString("s3.accessKey"),
                        cfg.getString("s3.secretKey"),
                        cfg.getString("s3.bucket"),
                        cfg.getString("s3.rootDir"),
                        serverId
                );
            } catch (Exception ex) {
                getLogger().severe("Reload: failed to initialize new storage: " + ex.getMessage());
                if (old != null) this.storage = old; // revert
                return false;
            }
            if (old != null) {
                try { old.close(); } catch (Exception ignore) {}
            }
            if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
            scheduleAutoBackup();
            refreshTimestampCacheAsync(true);
            return true;
        } catch (Exception e) {
            getLogger().warning("Reload failed: " + e.getMessage());
            return false;
        }
    }

    private void collectSources(Path buildDir) throws IOException {
        // Determine effective sections based on wildcard rules
        List<String> worlds = cfg.getStringList("backup.include.worlds");
        boolean plugins = cfg.getBoolean("backup.include.plugins", true);
        boolean configs = cfg.getBoolean("backup.include.configs", true);
        List<String> extra = cfg.getStringList("backup.include.extraPaths");

        boolean wildcard = false;
        if (worlds.stream().anyMatch(s -> s.equalsIgnoreCase("*"))) wildcard = true;
        if (extra.stream().anyMatch(s -> s.equalsIgnoreCase("*"))) wildcard = true;

        // If all lists empty and booleans absent => treat as wildcard (include everything sensible)
        if ((worlds.isEmpty()) && extra.isEmpty() && !cfg.isSet("backup.include.plugins") && !cfg.isSet("backup.include.configs")) {
            wildcard = true;
        }

        if (wildcard) {
            // Worlds: auto-detect directories with level.dat (standard MC worlds)
            try (var stream = Files.list(Path.of("."))) {
                stream.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("level.dat")))
                        .forEach(p -> {
                            try { copyIfExists(p, buildDir.resolve("worlds").resolve(p.getFileName().toString())); } catch (IOException ignored) { }
                        });
            }
            // Plugins
            copyIfExists(Path.of("plugins"), buildDir.resolve("plugins"));
            // Config roots
            copyIfExists(Path.of("server.properties"), buildDir.resolve("configs/server.properties"));
            copyIfExists(Path.of("bukkit.yml"), buildDir.resolve("configs/bukkit.yml"));
            copyIfExists(Path.of("spigot.yml"), buildDir.resolve("configs/spigot.yml"));
            copyIfExists(Path.of("paper-global.yml"), buildDir.resolve("configs/paper-global.yml"));
            // No extras in wildcard unless specified
            for (String ex : extra) {
                if (ex.equals("*")) continue;
                copyIfExists(Path.of(ex), buildDir.resolve("extra").resolve(ex));
            }
            return;
        }

        // Explicit worlds list
        for (String w : worlds) {
            if (w.equals("*")) continue; // already handled
            copyIfExists(Path.of(w), buildDir.resolve("worlds").resolve(w));
        }
        if (plugins) {
            copyIfExists(Path.of("plugins"), buildDir.resolve("plugins"));
        }
        if (configs) {
            copyIfExists(Path.of("server.properties"), buildDir.resolve("configs/server.properties"));
            copyIfExists(Path.of("bukkit.yml"), buildDir.resolve("configs/bukkit.yml"));
            copyIfExists(Path.of("spigot.yml"), buildDir.resolve("configs/spigot.yml"));
            copyIfExists(Path.of("paper-global.yml"), buildDir.resolve("configs/paper-global.yml"));
        }
        for (String ex : extra) {
            if (ex.equals("*")) continue; // wildcard not meaningful here
            copyIfExists(Path.of(ex), buildDir.resolve("extra").resolve(ex));
        }
    }

    private void copyIfExists(Path src, Path dest) throws IOException {
        if (!Files.exists(src)) return;
        if (Files.isDirectory(src)) {
            Files.walk(src).forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dest.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ignored) { }
            });
        } else {
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path ensureTemp() throws IOException {
        Path p = Path.of(cfg.getString("restore.tempDir", "plugins/BackupFlow/work/tmp"));
        Files.createDirectories(p);
        return p;
    }

    public BackupStorageService getStorage() { return storage; }

    public void restoreBackupAsync(String timestamp, java.util.Set<String> sections, boolean force, org.bukkit.command.CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                doRestore(timestamp, sections, force);
                sender.sendMessage("§aRestore complete for " + timestamp);
            } catch (Exception ex) {
                sender.sendMessage("§cRestore failed: " + ex.getMessage());
                getLogger().warning("Restore failed: " + ex.getMessage());
            }
        });
    }

    private void doRestore(String timestamp, java.util.Set<String> sections, boolean force) throws Exception {
        String keyPrefix = storage.beginFullBackupKeyPrefix(Instant.ofEpochMilli(Long.parseLong(timestamp)));
        String archiveNameZip = "full-" + timestamp + ".zip";
        java.nio.file.Path tempRoot = ensureTemp();
        java.nio.file.Path dl = tempRoot.resolve(archiveNameZip);
        storage.downloadFile(keyPrefix + archiveNameZip, dl);
        java.nio.file.Path extractDir = java.nio.file.Files.createTempDirectory(tempRoot, "bf-restore-");
        com.c4g7.backupflow.util.ZipExtractUtils.extractFiltered(dl, extractDir, com.c4g7.backupflow.util.ZipExtractUtils.buildSelector(sections));
        java.nio.file.Files.walk(extractDir).forEach(p -> {
            try {
                if (java.nio.file.Files.isDirectory(p)) return;
                java.nio.file.Path rel = extractDir.relativize(p);
                java.nio.file.Path target = java.nio.file.Path.of(".").resolve(rel.toString());
                if (!force && java.nio.file.Files.exists(target)) return;
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) { }
        });
    }

    public void verifyBackupAsync(String timestamp, java.util.Set<String> sections, org.bukkit.command.CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var result = doVerify(timestamp, sections);
                sender.sendMessage("§eVerification: files=" + result.total + " ok=" + result.matched + " mismatched=" + result.mismatched + " missing=" + result.missing);
                if (!result.problems.isEmpty()) {
                    sender.sendMessage("§cProblem samples: " + String.join(", ", result.problems.stream().limit(10).toList()));
                }
            } catch (Exception ex) {
                sender.sendMessage("§cVerify failed: " + ex.getMessage());
            }
        });
    }

    private static final class VerifyStats { int total; int matched; int mismatched; int missing; java.util.List<String> problems = new java.util.ArrayList<>(); }

    private VerifyStats doVerify(String timestamp, java.util.Set<String> sections) throws Exception {
        String keyPrefix = storage.beginFullBackupKeyPrefix(Instant.ofEpochMilli(Long.parseLong(timestamp)));
        String archiveNameZip = "full-" + timestamp + ".zip";
        java.nio.file.Path tempRoot = ensureTemp();
        java.nio.file.Path dl = tempRoot.resolve(archiveNameZip);
        storage.downloadFile(keyPrefix + archiveNameZip, dl);
        // Download manifest if present
        java.util.List<String> manifests = storage.listManifests();
        String manifestForTs = null;
        for (String m : manifests) if (m.contains(timestamp)) { manifestForTs = m; break; }
        java.util.Map<String,String> hashes = new java.util.HashMap<>();
        if (manifestForTs != null) {
            java.nio.file.Path mf = tempRoot.resolve(manifestForTs);
            storage.downloadFile(storage.manifestObjectName(manifestForTs), mf);
            String json = java.nio.file.Files.readString(mf);
            int idx = json.indexOf("\"hashes\":");
            if (idx >= 0) {
                // naive parse: {"hashes":{"path":"hex",...}}
                String sub = json.substring(idx);
                int open = sub.indexOf('{');
                int close = sub.indexOf('}');
                if (open >=0 && close>open) {
                    String body = sub.substring(open+1, close);
                    for (String pair : body.split(",")) {
                        int c = pair.indexOf(':');
                        if (c>0) {
                            String k = pair.substring(0,c).replace("\"","" ).trim();
                            String v = pair.substring(c+1).replace("\"","" ).trim();
                            if (!k.isEmpty() && !v.isEmpty()) hashes.put(k, v);
                        }
                    }
                }
            }
        }
        VerifyStats stats = new VerifyStats();
        java.util.function.Predicate<String> selector = com.c4g7.backupflow.util.ZipExtractUtils.buildSelector(sections);
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(dl); java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!selector.test(name)) continue;
                stats.total++;
                if (hashes.isEmpty()) continue; // nothing to compare
                if (!hashes.containsKey(name)) { stats.missing++; stats.problems.add("not-in-manifest:"+name); continue; }
                // compute hash
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[8192]; int r;
                while ((r = zis.read(buf)) != -1) md.update(buf,0,r);
                String calc = toHex(md.digest());
                String expected = hashes.get(name);
                if (expected.equalsIgnoreCase(calc)) stats.matched++; else { stats.mismatched++; stats.problems.add("mismatch:"+name); }
            }
        } catch (Exception ex) { throw ex; }
        return stats;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length*2);
        for (byte b: bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public java.util.List<String> retentionPlan(Integer keepDays, Integer max) throws Exception {
        java.util.List<String> all = storage.listBackups("full");
        java.util.List<String> sorted = new java.util.ArrayList<>(all);
        sorted.sort(String::compareTo);
        long now = System.currentTimeMillis();
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String ts : sorted) {
            try {
                long t = Long.parseLong(ts);
                boolean old = (keepDays != null && keepDays > 0 && now - t > keepDays*86400000L);
                candidates.add((old?"old:" : "") + ts);
            } catch (NumberFormatException ignored) { }
        }
        if (max != null && max > 0 && sorted.size() > max) {
            int removeCount = sorted.size() - max;
            for (int i=0;i<removeCount && i<sorted.size();i++) {
                String ts = sorted.get(i);
                if (!candidates.contains(ts)) candidates.add("excess:"+ts);
            }
        }
        return candidates;
    }
}
