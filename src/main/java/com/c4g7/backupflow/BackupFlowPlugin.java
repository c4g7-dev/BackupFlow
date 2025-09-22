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
        scheduleAutoBackup();
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
        // fallback: folder name or random
        return getServer().getServerId() != null ? getServer().getServerId() : "srv" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
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
            try {
                runBackup("scheduled");
            } catch (Exception e) {
                getLogger().warning("Scheduled backup failed: " + e.getMessage());
            }
        }, delay, ticks);
    }

    public void runBackup(String reason) throws Exception {
        Instant ts = Instant.now();
        String prefix = storage.beginFullBackupKeyPrefix(ts);
        Path tempRoot = ensureTemp();
        Path buildDir = Files.createTempDirectory(tempRoot, "bf-build-");
        try {
            collectSources(buildDir);
            Path archive = com.c4g7.backupflow.util.CompressionUtils.compress(buildDir, cfg.getString("backup.compression", "zip"));
            String fileName = "full-" + ts.toEpochMilli() + "." + (cfg.getString("backup.compression", "zip").equalsIgnoreCase("gz") ? "tar.gz" : "zip");
            storage.uploadFile(archive, prefix + fileName);
            if (cfg.getBoolean("manifest.storeInBucket", true)) {
                Path manifest = com.c4g7.backupflow.util.ManifestBuilder.writeSimpleManifest(tempRoot, storage.randomManifestName(ts), reason, serverId, List.of(fileName));
                storage.uploadFile(manifest, storage.manifestObjectName(manifest.getFileName().toString()));
            }
            getLogger().info("Backup complete: " + fileName + " (reason=" + reason + ")");
        } finally {
            com.c4g7.backupflow.util.FileUtils.deleteQuietly(buildDir);
        }
    }

    private void collectSources(Path buildDir) throws IOException {
        // Worlds
        for (String w : cfg.getStringList("backup.include.worlds")) {
            copyIfExists(Path.of(w), buildDir.resolve("worlds").resolve(w));
        }
        if (cfg.getBoolean("backup.include.plugins", true)) {
            copyIfExists(Path.of("plugins"), buildDir.resolve("plugins"));
        }
        if (cfg.getBoolean("backup.include.configs", true)) {
            // server root configs
            copyIfExists(Path.of("server.properties"), buildDir.resolve("configs/server.properties"));
            copyIfExists(Path.of("bukkit.yml"), buildDir.resolve("configs/bukkit.yml"));
            copyIfExists(Path.of("spigot.yml"), buildDir.resolve("configs/spigot.yml"));
            copyIfExists(Path.of("paper-global.yml"), buildDir.resolve("configs/paper-global.yml"));
        }
        for (String extra : cfg.getStringList("backup.include.extraPaths")) {
            copyIfExists(Path.of(extra), buildDir.resolve("extra").resolve(extra));
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
}
