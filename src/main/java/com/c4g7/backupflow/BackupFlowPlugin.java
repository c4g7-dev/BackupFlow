package com.c4g7.backupflow;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

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
    private volatile String lastError = null;

    // Instrumentation / watchdog
    private volatile String lastPhase = "IDLE";
    private volatile long lastPhaseAt = 0L;
    private volatile boolean cancelRequested = false;
    private int watchdogTaskId = -1;
    private volatile Thread backupThread = null;
    private final java.util.concurrent.atomic.AtomicLong filesCopiedThisRun = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bytesCopiedThisRun = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastProgressAt = 0L;
    private volatile long totalFilesPlanned = 0L;
    private volatile long totalBytesPlanned = 0L;
    private java.util.Map<String, Long> planBreakdown = java.util.Collections.emptyMap();
    private volatile String lastContentHash = null;

    public String pref() { return prefix; }
    public String getServerIdValue() { return serverId; }
    public String getLastPhase() { return lastPhase; }
    public long getLastPhaseAt() { return lastPhaseAt; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void requestCancel() { if (backupRunning) cancelRequested = true; }
    public String getLastError() { return lastError; }
    private void updatePhase(String phase) { lastPhase = phase; lastPhaseAt = System.currentTimeMillis(); }
    public long getLastProgressAt() { return lastProgressAt; }
    public long getFilesCopiedThisRun() { return filesCopiedThisRun.get(); }
    public long getBytesCopiedThisRun() { return bytesCopiedThisRun.get(); }
    public boolean isBackupThreadAlive() { return backupThread != null && backupThread.isAlive(); }
    public long getCurrentElapsedMs() { return backupRunning ? (System.currentTimeMillis() - lastBackupStart) : 0L; }
    public long getTotalFilesPlanned() { return totalFilesPlanned; }
    public long getTotalBytesPlanned() { return totalBytesPlanned; }
    public double getPercentComplete() { return totalBytesPlanned > 0 ? (bytesCopiedThisRun.get() * 100.0 / totalBytesPlanned) : -1; }
    public double getThroughputBytesPerSec() { long ms = getCurrentElapsedMs(); return ms > 0 ? (bytesCopiedThisRun.get() * 1000.0 / ms) : 0.0; }
    public long getEtaSeconds() { double thr = getThroughputBytesPerSec(); if (thr <= 0 || totalBytesPlanned == 0) return -1; long remaining = totalBytesPlanned - bytesCopiedThisRun.get(); return remaining <=0 ? 0 : (long)Math.ceil(remaining / thr); }
    public java.util.Map<String, Long> getPlanBreakdown() { return planBreakdown; }

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
        try { storage.createRoot(); } catch (Exception ignored) {}
        initPrefix();
        registerCommands();
        scheduleAutoBackup();
        listOnStartup();
        startWatchdog();
        getLogger().info("BackupFlow enabled. ServerId=" + serverId);
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        if (storage != null) storage.close();
        if (watchdogTaskId != -1) Bukkit.getScheduler().cancelTask(watchdogTaskId);
    }

    private String detectServerId() {
        String id = getConfig().getString("serverId");
        if (id != null && !id.isBlank()) return id.trim();
        try {
            Path cwd = Path.of("").toAbsolutePath().normalize();
            String name = cwd.getFileName() != null ? cwd.getFileName().toString() : null;
            if (name != null && !name.isBlank()) return name.replaceAll("[^a-zA-Z0-9-_]","-");
        } catch (Exception ignored) { }
        return "srv" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }

    private void initPrefix() {
        boolean color = getConfig().getBoolean("color.enabled", true);
        if (!color) { this.prefix = "[BackupFlow] "; return; }
        this.prefix = "§b§lB§3§lF§7 » §r"; // static legacy gradient
    }

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

    private void startWatchdog() {
        long period = 20L * 15; // 15s
        watchdogTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!backupRunning) return;
            if (lastBackupStart <= 0) return; // not yet initialized
            long elapsed = System.currentTimeMillis() - lastBackupStart;
            long hard = cfg.getLong("backup.hardTimeoutSeconds", 600L) * 1000L;
            if (hard > 0 && elapsed > hard) {
                getLogger().warning("Backup watchdog timeout exceeded (" + elapsed + "ms). Marking as failed.");
                lastError = "Timeout after " + elapsed + "ms phase=" + lastPhase;
                backupRunning = false;
                cancelRequested = true;
                updatePhase("TIMEOUT");
                return;
            }
            long staleLimit = cfg.getLong("backup.phaseStaleSeconds", 0L) * 1000L;
            if (staleLimit > 0 && (System.currentTimeMillis() - lastPhaseAt) > staleLimit) {
                getLogger().warning("Backup phase stale for " + (System.currentTimeMillis() - lastPhaseAt) + "ms (phase=" + lastPhase + ")");
            }
            if (backupThread != null && !backupThread.isAlive()) {
                getLogger().warning("Backup thread died unexpectedly; resetting state");
                backupRunning = false;
                updatePhase("IDLE");
            }
        }, period, period);
    }

    public void runBackup(String reason) throws Exception {
        Instant ts = Instant.now();
        lastBackupStart = System.currentTimeMillis();
        String pfx = storage.beginFullBackupKeyPrefix(ts);
        Path tempRoot = ensureTemp();
        Path buildDir = Files.createTempDirectory(tempRoot, "bf-build-");
        try {
            updatePhase("COLLECT");
            collectSources(buildDir);
            if (cancelRequested) throw new RuntimeException("Cancelled");
            updatePhase("COMPRESS");
            boolean wantHashes = cfg.getBoolean("integrity.hashes", true);
            var comp = com.c4g7.backupflow.util.CompressionUtils.compress(buildDir, cfg.getString("backup.compression", "zip"), wantHashes);
            String fileName = "full-" + ts.toEpochMilli() + "." + (cfg.getString("backup.compression", "zip").equalsIgnoreCase("gz") ? "tar.gz" : "zip");
            if (cancelRequested) throw new RuntimeException("Cancelled");
            updatePhase("UPLOAD_ARCHIVE");
            int partSizeMB = cfg.getInt("backup.performance.uploadPartSizeMB", 64);
            int bufferSizeMB = cfg.getInt("backup.performance.uploadBufferSizeMB", 8);
            storage.uploadFile(comp.archive, pfx + fileName, partSizeMB * 1024 * 1024, bufferSizeMB * 1024 * 1024);
            if (cfg.getBoolean("manifest.storeInBucket", true)) {
                Path manifest;
                updatePhase("WRITE_MANIFEST");
                if (wantHashes && comp.hashes != null && !comp.hashes.isEmpty()) {
                    manifest = com.c4g7.backupflow.util.ManifestBuilder.writeManifestWithHashes(tempRoot, storage.randomManifestName(ts), reason, serverId, List.of(fileName), comp.hashes);
                } else {
                    manifest = com.c4g7.backupflow.util.ManifestBuilder.writeSimpleManifest(tempRoot, storage.randomManifestName(ts), reason, serverId, List.of(fileName));
                }
                updatePhase("UPLOAD_MANIFEST");
                storage.uploadFile(manifest, storage.manifestObjectName(manifest.getFileName().toString()));
            }
            lastBackupEnd = System.currentTimeMillis();
            getLogger().info("Backup complete: " + fileName + " (reason=" + reason + ") took " + (lastBackupEnd - lastBackupStart) + "ms");
            refreshTimestampCacheAsync(true);
            updatePhase("DONE");
        } finally {
            com.c4g7.backupflow.util.FileUtils.deleteQuietly(buildDir);
        }
    }

    public boolean startBackupAsync(String reason, org.bukkit.command.CommandSender initiator) {
        if (backupRunning) {
            if (initiator != null) initiator.sendMessage(pref() + "§cBackup already running");
            return false;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean started = false;
            try {
                cancelRequested = false;
                updatePhase("INIT");
                lastBackupStart = System.currentTimeMillis();
                backupRunning = true; started = true;
                filesCopiedThisRun.set(0L);
                bytesCopiedThisRun.set(0L);
                lastProgressAt = System.currentTimeMillis();
                backupThread = Thread.currentThread();
                // Pre-scan to estimate total files/bytes for ETA
                try {
                    updatePhase("PRE_SCAN");
                    long prescanStart = System.currentTimeMillis();
                    var plan = planSources();
                    long prescanTime = System.currentTimeMillis() - prescanStart;
                    getLogger().info("Pre-scan completed in " + prescanTime + "ms");
                    totalFilesPlanned = plan.files;
                    totalBytesPlanned = plan.bytes;
                    planBreakdown = plan.breakdown;
                } catch (RuntimeException re) {
                    if ("NO_CHANGES_DETECTED".equals(re.getMessage())) {
                        if (initiator != null) initiator.sendMessage(pref() + "§aNo changes detected - backup skipped");
                        getLogger().info("Backup skipped - no changes since last backup");
                        return;
                    }
                    throw re;
                } catch (Exception scanEx) {
                    totalFilesPlanned = 0L; totalBytesPlanned = 0L;
                    planBreakdown = java.util.Collections.emptyMap();
                    getLogger().warning("Pre-scan failed: " + scanEx.getMessage() + " - continuing without ETA");
                }
                if (initiator != null) initiator.sendMessage(pref() + "§7Backup started...");
                runBackup(reason);
                if (initiator != null) initiator.sendMessage(pref() + "§aBackup completed in §f" + getLastBackupDuration() + "ms");
            } catch (Exception ex) {
                lastError = ex.getMessage();
                getLogger().warning("Backup failed (endpoint=" + cfg.getString("s3.endpoint") + ", bucket=" + cfg.getString("s3.bucket") + "): " + ex.getMessage());
                if (initiator != null) initiator.sendMessage(pref() + "§cBackup failed: " + ex.getMessage());
            } finally {
                backupThread = null;
                if (started) backupRunning = false;
                updatePhase("IDLE");
                totalFilesPlanned = 0L; totalBytesPlanned = 0L;
                planBreakdown = java.util.Collections.emptyMap();
            }
        });
        return true;
    }

    public boolean isBackupRunning() { return backupRunning; }
    public long getLastBackupDuration() { return lastBackupEnd > lastBackupStart ? (lastBackupEnd - lastBackupStart) : 0L; }
    public long getLastBackupEnd() { return lastBackupEnd; }

    public java.util.List<String> getCachedTimestamps() {
        int ttl = cfg.getInt("timestampCacheSeconds", 60);
        long now = System.currentTimeMillis();
        if (ttl <= 0 || (now - lastTimestampCacheAt) > ttl * 1000L) {
            refreshTimestampCacheAsync(false);
        }
        return cachedTimestamps;
    }

    public void refreshTimestampCacheAsync(boolean force) {
        int ttl = cfg.getInt("timestampCacheSeconds", 60);
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
                lastError = ex.getMessage();
                getLogger().warning("Timestamp cache refresh failed (endpoint=" + cfg.getString("s3.endpoint") + ", bucket=" + cfg.getString("s3.bucket") + "): " + ex.getMessage());
            }
        });
    }

    public boolean reloadBackupFlowConfig() {
        try {
            reloadConfig();
            cfg = getConfig();
            initPrefix();
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
                try { this.storage.createRoot(); } catch (Exception ignored) {}
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
            if (getCachedTimestamps().isEmpty()) getLogger().info("Post-reload: no backups detected yet (endpoint=" + cfg.getString("s3.endpoint") + ")");
            return true;
        } catch (Exception e) {
            getLogger().warning("Reload failed: " + e.getMessage());
            return false;
        }
    }

    private void collectSources(Path buildDir) throws IOException {
        List<String> worlds = cfg.getStringList("backup.include.worlds");
        boolean plugins = cfg.getBoolean("backup.include.plugins", true);
        boolean configs = cfg.getBoolean("backup.include.configs", true);
        List<String> extra = cfg.getStringList("backup.include.extraPaths");

        boolean wildcard = false;
        if (worlds.stream().anyMatch(s -> s.equalsIgnoreCase("*"))) wildcard = true;
        if (extra.stream().anyMatch(s -> s.equalsIgnoreCase("*"))) wildcard = true;
        if ((worlds.isEmpty()) && extra.isEmpty() && !cfg.isSet("backup.include.plugins") && !cfg.isSet("backup.include.configs")) {
            wildcard = true;
        }

        if (wildcard) {
            // Auto-detect all worlds
            try (var stream = Files.list(Path.of("."))) {
                stream.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("level.dat")))
                        .forEach(p -> {
                            try { copyIfExists(p, buildDir.resolve("worlds").resolve(p.getFileName().toString())); } catch (IOException ignored) { }
                        });
            }
            // Include all plugins
            copyIfExists(Path.of("plugins"), buildDir.resolve("plugins"));
            
            // Include comprehensive server configs
            copyIfExists(Path.of("server.properties"), buildDir.resolve("configs/server.properties"));
            copyIfExists(Path.of("bukkit.yml"), buildDir.resolve("configs/bukkit.yml"));
            copyIfExists(Path.of("spigot.yml"), buildDir.resolve("configs/spigot.yml"));
            copyIfExists(Path.of("paper-global.yml"), buildDir.resolve("configs/paper-global.yml"));
            copyIfExists(Path.of("paper-world-defaults.yml"), buildDir.resolve("configs/paper-world-defaults.yml"));
            copyIfExists(Path.of("purpur.yml"), buildDir.resolve("configs/purpur.yml"));
            copyIfExists(Path.of("pufferfish.yml"), buildDir.resolve("configs/pufferfish.yml"));
            copyIfExists(Path.of("airplane.yml"), buildDir.resolve("configs/airplane.yml"));
            
            // Include permissions and player data
            copyIfExists(Path.of("permissions.yml"), buildDir.resolve("configs/permissions.yml"));
            copyIfExists(Path.of("ops.json"), buildDir.resolve("configs/ops.json"));
            copyIfExists(Path.of("whitelist.json"), buildDir.resolve("configs/whitelist.json"));
            copyIfExists(Path.of("banned-players.json"), buildDir.resolve("configs/banned-players.json"));
            copyIfExists(Path.of("banned-ips.json"), buildDir.resolve("configs/banned-ips.json"));
            copyIfExists(Path.of("eula.txt"), buildDir.resolve("configs/eula.txt"));
            
            // Include logs directory (filtered by exclusions)
            copyIfExists(Path.of("logs"), buildDir.resolve("logs"));
            
            // Include additional config directory if exists
            copyIfExists(Path.of("config"), buildDir.resolve("config"));
            
            for (String ex : extra) {
                if (ex.equals("*")) continue;
                copyIfExists(Path.of(ex), buildDir.resolve("extra").resolve(ex));
            }
            return;
        }

        for (String w : worlds) {
            if (w.equals("*")) continue;
            copyIfExists(Path.of(w), buildDir.resolve("worlds").resolve(w));
        }
        if (plugins) copyIfExists(Path.of("plugins"), buildDir.resolve("plugins"));
        if (configs) {
            // Include comprehensive server configs
            copyIfExists(Path.of("server.properties"), buildDir.resolve("configs/server.properties"));
            copyIfExists(Path.of("bukkit.yml"), buildDir.resolve("configs/bukkit.yml"));
            copyIfExists(Path.of("spigot.yml"), buildDir.resolve("configs/spigot.yml"));
            copyIfExists(Path.of("paper-global.yml"), buildDir.resolve("configs/paper-global.yml"));
            copyIfExists(Path.of("paper-world-defaults.yml"), buildDir.resolve("configs/paper-world-defaults.yml"));
            copyIfExists(Path.of("purpur.yml"), buildDir.resolve("configs/purpur.yml"));
            copyIfExists(Path.of("pufferfish.yml"), buildDir.resolve("configs/pufferfish.yml"));
            copyIfExists(Path.of("airplane.yml"), buildDir.resolve("configs/airplane.yml"));
            
            // Include permissions and player data
            copyIfExists(Path.of("permissions.yml"), buildDir.resolve("configs/permissions.yml"));
            copyIfExists(Path.of("ops.json"), buildDir.resolve("configs/ops.json"));
            copyIfExists(Path.of("whitelist.json"), buildDir.resolve("configs/whitelist.json"));
            copyIfExists(Path.of("banned-players.json"), buildDir.resolve("configs/banned-players.json"));
            copyIfExists(Path.of("banned-ips.json"), buildDir.resolve("configs/banned-ips.json"));
            copyIfExists(Path.of("eula.txt"), buildDir.resolve("configs/eula.txt"));
        }
        for (String ex : extra) {
            if (ex.equals("*")) continue;
            copyIfExists(Path.of(ex), buildDir.resolve("extra").resolve(ex));
        }
    }

    // Planning structure
    private static final class PlanStats { 
        long files; 
        long bytes; 
        java.util.Map<String,Long> breakdown = new java.util.LinkedHashMap<>();
        StringBuilder hashInput = new StringBuilder(); // For content hash
    }

    private PlanStats planSources() throws IOException {
        PlanStats ps = new PlanStats();
        List<String> worlds = cfg.getStringList("backup.include.worlds");
        boolean plugins = cfg.getBoolean("backup.include.plugins", true);
        boolean configs = cfg.getBoolean("backup.include.configs", true);
        List<String> extra = cfg.getStringList("backup.include.extraPaths");
        
        getLogger().info("Pre-scan config: worlds=" + worlds + ", plugins=" + plugins + ", configs=" + configs + ", extra=" + extra);
        
        boolean wildcard = false;
        if (worlds.stream().anyMatch(s -> s.equalsIgnoreCase("*"))) wildcard = true;
        if (extra.stream().anyMatch(s -> s.equalsIgnoreCase("*"))) wildcard = true;
        if ((worlds.isEmpty()) && extra.isEmpty() && !cfg.isSet("backup.include.plugins") && !cfg.isSet("backup.include.configs")) wildcard = true;

        getLogger().info("Using wildcard mode: " + wildcard + " (scanning from " + Path.of(".").toAbsolutePath() + ")");

        // Get temp dir path for exclusion
        String tempDirPath = cfg.getString("restore.tempDir", "plugins/BackupFlow/work/tmp");
        Path tempDir = Path.of(tempDirPath).toAbsolutePath().normalize();
        
        java.util.function.Consumer<Path> accumulator = p -> {
            try {
                if (Files.isRegularFile(p)) {
                    // Skip files in temp directory
                    Path normalized = p.toAbsolutePath().normalize();
                    if (normalized.startsWith(tempDir)) {
                        return; // Skip temp files
                    }
                    ps.files++;
                    try { 
                        long size = Files.size(p);
                        ps.bytes += size;
                        
                        // Add to hash input: path + size + lastModified for change detection
                        long lastMod = Files.getLastModifiedTime(p).toMillis();
                        ps.hashInput.append(p.toString()).append(":").append(size).append(":").append(lastMod).append(";");
                    } catch (IOException ignored) {}
                }
            } catch (Exception ignored) {}
        };

        java.util.function.BiConsumer<String, Path> rootWalk = (label, root) -> {
            long beforeBytes = ps.bytes; long beforeFiles = ps.files;
            long scanStart = System.currentTimeMillis();
            getLogger().info("Pre-scanning: " + label + " at " + root.toAbsolutePath());
            
            try {
                // Timeout protection for slow scans
                java.util.concurrent.CompletableFuture<Void> scanTask = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    walk(root, accumulator);
                });
                
                // Wait max 10 seconds for each directory scan
                scanTask.get(10, java.util.concurrent.TimeUnit.SECONDS);
                
            } catch (java.util.concurrent.TimeoutException e) {
                getLogger().warning("Pre-scan timeout for " + label + " after 10s - skipping (may have large cache/data folders)");
                return;
            } catch (Exception e) {
                getLogger().warning("Pre-scan failed for " + label + ": " + e.getMessage());
                return;
            }
            
            long deltaBytes = ps.bytes - beforeBytes; long deltaFiles = ps.files - beforeFiles;
            long scanTime = System.currentTimeMillis() - scanStart;
            getLogger().info("Pre-scan result: " + label + " -> " + deltaFiles + " files, " + deltaBytes + " bytes (" + scanTime + "ms)");
            if (deltaFiles > 0 || deltaBytes > 0) ps.breakdown.put(label + "(" + deltaFiles + ")", deltaBytes);
        };

        if (wildcard) {
            try (var stream = Files.list(Path.of("."))) {
                stream.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("level.dat")))
                        .forEach(world -> rootWalk.accept("world:" + world.getFileName(), world));
            }
            rootWalk.accept("plugins", Path.of("plugins"));
            // config roots (individual files)
            for (String cfgFile : List.of("server.properties","bukkit.yml","spigot.yml","paper-global.yml")) {
                Path p = Path.of(cfgFile); if (Files.exists(p) && Files.isRegularFile(p)) { ps.files++; try { ps.bytes += Files.size(p);} catch (IOException ignored) {} }
            }
            for (String ex : extra) {
                if (ex.equals("*")) continue;
                rootWalk.accept("extra:" + ex, Path.of(ex));
            }
            return ps;
        }

        for (String w : worlds) { if (!w.equals("*")) rootWalk.accept("world:" + w, Path.of(w)); }
        if (plugins) rootWalk.accept("plugins", Path.of("plugins"));
        if (configs) {
            for (String cfgFile : List.of("server.properties","bukkit.yml","spigot.yml","paper-global.yml")) {
                Path p = Path.of(cfgFile); if (Files.exists(p) && Files.isRegularFile(p)) { ps.files++; try { ps.bytes += Files.size(p);} catch (IOException ignored) {} }
            }
        }
        for (String ex : extra) { if (!ex.equals("*")) rootWalk.accept("extra:" + ex, Path.of(ex)); }
        
        // Compute content hash for change detection
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String contentHash = toHex(md.digest(ps.hashInput.toString().getBytes()));
            ps.breakdown.put("ContentHash", (long)contentHash.hashCode()); // Store in breakdown for display
            getLogger().info("Content hash: " + contentHash);
            
            // Check if content changed
            if (cfg.getBoolean("backup.skipUnchanged", true) && contentHash.equals(lastContentHash)) {
                getLogger().info("No changes detected - content hash matches previous backup");
                throw new RuntimeException("NO_CHANGES_DETECTED");
            }
            
            lastContentHash = contentHash;
        } catch (java.security.NoSuchAlgorithmException e) {
            getLogger().warning("Could not compute content hash: " + e.getMessage());
        }
        
        return ps;
    }

    private boolean shouldExcludeFile(Path file, Path baseDir) {
        try {
            // Get exclusion patterns from config
            List<String> patterns = cfg.getStringList("backup.exclude.patterns");
            if (patterns.isEmpty()) {
                // Default exclusions if none configured
                patterns = List.of(
                    "cache/**", "**/cache/**", "**/temp/**", "**/tmp/**", "plugins/BackupFlow/work/**",
                    "logs/*.log.gz", "**/logs/*.log.gz", "world/session.lock", "**/session.lock", "**/uid.dat",
                    ".git/**", ".idea/**", "*.iml", "**/dynmap/web/tiles/**", "**/BlueMap/web/data/**"
                );
            }
            
            // Get relative path for pattern matching
            String relativePath = baseDir.relativize(file).toString().replace('\\', '/');
            
            // Check against patterns
            for (String pattern : patterns) {
                if (matchesGlobPattern(relativePath, pattern)) {
                    return true;
                }
            }
            
            // Check file size limits
            if (Files.isRegularFile(file)) {
                long maxSizeMB = cfg.getLong("backup.exclude.maxFileSizeMB", 100);
                if (maxSizeMB > 0) {
                    long fileSizeMB = Files.size(file) / (1024 * 1024);
                    if (fileSizeMB > maxSizeMB) {
                        getLogger().info("Excluding large file (" + fileSizeMB + "MB): " + relativePath);
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            return false; // When in doubt, include the file
        }
    }
    
    private boolean matchesGlobPattern(String path, String pattern) {
        // Convert glob pattern to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .replace("/**", "/.*");
        
        // Handle ** (recursive directory matching)
        regex = regex.replace(".*/..*", "(?:.*/)?.*");
        
        return Pattern.matches(regex, path);
    }

    private void walk(Path root, java.util.function.Consumer<Path> consumer) {
        try {
            if (!Files.exists(root)) return;
            if (Files.isRegularFile(root)) { 
                if (!shouldExcludeFile(root, Path.of("."))) {
                    consumer.accept(root); 
                }
                return; 
            }
            
            Files.walk(root)
                .filter(p -> {
                    try {
                        // Apply comprehensive exclusion logic
                        return !shouldExcludeFile(p, root.getParent() != null ? root.getParent() : Path.of("."));
                    } catch (Exception e) {
                        return true; // When in doubt, include
                    }
                })
                .forEach(consumer);
        } catch (Exception e) {
            getLogger().warning("Failed to walk " + root + ": " + e.getMessage());
        }
                    } catch (Exception e) {
                        return true; // Include if can't normalize
                    }
                })
                .forEach(consumer);
        } catch (IOException ignored) { }
    }

    private void copyIfExists(Path src, Path dest) throws IOException {
        if (!Files.exists(src)) return;
        
        // Get temp dir for exclusion
        String tempDirPath = cfg.getString("restore.tempDir", "plugins/BackupFlow/work/tmp");
        Path tempDir = Path.of(tempDirPath).toAbsolutePath().normalize();
        
        if (Files.isDirectory(src)) {
            Files.walk(src)
                .filter(p -> {
                    try {
                        Path normalized = p.toAbsolutePath().normalize();
                        return !normalized.startsWith(tempDir); // Skip temp directory
                    } catch (Exception e) {
                        return true; // Include if can't normalize
                    }
                })
                .forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dest.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        filesCopiedThisRun.incrementAndGet();
                        try { bytesCopiedThisRun.addAndGet(Files.size(p)); } catch (IOException ignored) {}
                        lastProgressAt = System.currentTimeMillis();
                        if (cancelRequested) throw new RuntimeException("Cancelled");
                    }
                } catch (IOException ignored) { }
                catch (RuntimeException rte) { throw rte; }
            });
        } else {
            // Check if single file is in temp dir
            Path normalized = src.toAbsolutePath().normalize();
            if (normalized.startsWith(tempDir)) {
                return; // Skip temp file
            }
            
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            filesCopiedThisRun.incrementAndGet();
            try { bytesCopiedThisRun.addAndGet(Files.size(src)); } catch (IOException ignored) {}
            lastProgressAt = System.currentTimeMillis();
            if (cancelRequested) throw new RuntimeException("Cancelled");
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
                if (hashes.isEmpty()) continue;
                if (!hashes.containsKey(name)) { stats.missing++; stats.problems.add("not-in-manifest:"+name); continue; }
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[8192]; int r;
                while ((r = zis.read(buf)) != -1) md.update(buf,0,r);
                String calc = toHex(md.digest());
                String expected = hashes.get(name);
                if (expected.equalsIgnoreCase(calc)) stats.matched++; else { stats.mismatched++; stats.problems.add("mismatch:"+name); }
            }
        }
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
