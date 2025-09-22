package com.c4g7.backupflow.cmd;

import com.c4g7.backupflow.BackupFlowPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BackupFlowCommand implements CommandExecutor, TabCompleter {
    private final BackupFlowPlugin plugin;

    public BackupFlowCommand(BackupFlowPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        try {
            switch (sub) {
                case "backup":
                    require(sender, "backupflow.backup");
                    if (plugin.startBackupAsync("manual", sender)) {
                        sender.sendMessage(plugin.pref() + "§7Backup queued.");
                    }
                    return true;
                case "restore":
                    require(sender, "backupflow.restore");
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /" + label + " restore <timestamp> [--select worlds,plugins,configs,extra] [--force]");
                        return true;
                    }
                    String ts = args[1];
                    java.util.Set<String> sections = new java.util.LinkedHashSet<>();
                    boolean force = false;
                    for (int i=2;i<args.length;i++) {
                        String part = args[i];
                        if (part.equalsIgnoreCase("--force")) { force = true; continue; }
                        if (part.equalsIgnoreCase("--select") && i+1 < args.length) {
                            String list = args[++i];
                            for (String seg : list.split(",")) {
                                seg = seg.trim().toLowerCase();
                                if (!seg.isEmpty()) sections.add(seg);
                            }
                        }
                    }
                    plugin.restoreBackupAsync(ts, sections, force, sender);
                    sender.sendMessage(plugin.pref() + "§7Restore queued §f" + ts + (sections.isEmpty()?" §8(all sections)":" §8sections=" + sections));
                    return true;
                case "verify":
                    require(sender, "backupflow.verify");
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /" + label + " verify <timestamp> [--select worlds,plugins,configs,extra]");
                        return true;
                    }
                    String vts = args[1];
                    java.util.Set<String> vSections = new java.util.LinkedHashSet<>();
                    for (int i=2;i<args.length;i++) {
                        if (args[i].equalsIgnoreCase("--select") && i+1 < args.length) {
                            for (String seg : args[++i].split(",")) {
                                seg = seg.trim().toLowerCase();
                                if (!seg.isEmpty()) vSections.add(seg);
                            }
                        }
                    }
                    sender.sendMessage(plugin.pref() + "§7Verify queued §f" + vts + (vSections.isEmpty()?" §8(all sections)":" §8sections=" + vSections));
                    plugin.verifyBackupAsync(vts, vSections, sender);
                    return true;
                case "retention":
                    require(sender, "backupflow.retention");
                    if (args.length >= 2 && args[1].equalsIgnoreCase("plan")) {
                        Integer keepDays = null; Integer max = null;
                        for (int i=2;i<args.length;i++) {
                            if (args[i].equalsIgnoreCase("--keepDays") && i+1<args.length) { keepDays = Integer.parseInt(args[++i]); }
                            else if (args[i].equalsIgnoreCase("--max") && i+1<args.length) { max = Integer.parseInt(args[++i]); }
                        }
                        try {
                            java.util.List<String> plan = plugin.retentionPlan(keepDays, max);
                            sender.sendMessage(plugin.pref() + "§bRetention plan §7(" + plan.size() + "):");
                            for (int pi=0; pi<plan.size(); pi++) sender.sendMessage("§7 - §f" + plan.get(pi));
                        } catch (Exception ex) {
                            sender.sendMessage(plugin.pref() + "§cRetention plan failed: " + ex.getMessage());
                        }
                        return true;
                    }
                    sender.sendMessage(plugin.pref() + "§cUsage: /" + label + " retention plan [--keepDays N] [--max N]");
                    return true;
                case "list":
                    require(sender, "backupflow.list");
                    var list = plugin.getStorage().listBackups("full");
                    sender.sendMessage(plugin.pref() + "§bBackups §7(" + list.size() + "):");
                    if (list.isEmpty()) sender.sendMessage("§8 (none)");
                    else sender.sendMessage("§7 " + list);
                    return true;
                case "manifests":
                    require(sender, "backupflow.manifests");
                    var manifests = plugin.getStorage().listManifests();
                    sender.sendMessage(plugin.pref() + "§bManifests §7(" + manifests.size() + "):");
                    if (manifests.isEmpty()) sender.sendMessage("§8 (none)"); else sender.sendMessage("§7 " + manifests);
                    return true;
                case "version":
                    require(sender, "backupflow.version");
                    sender.sendMessage(plugin.pref() + "§fBackupFlow §b" + plugin.getDescription().getVersion());
                    return true;
                case "diag":
                    require(sender, "backupflow.diag");
                    sender.sendMessage(plugin.pref() + "§bDiagnostics:");
                    org.bukkit.configuration.file.FileConfiguration c = plugin.getConfig();
                    sender.sendMessage("§7Endpoint: §f" + c.getString("s3.endpoint"));
                    sender.sendMessage("§7Bucket: §f" + c.getString("s3.bucket"));
                    sender.sendMessage("§7RootDir: §f" + c.getString("s3.rootDir"));
                    sender.sendMessage("§7ServerId: §f" + plugin.getDescription().getName() + ":" + plugin.getName());
                    sender.sendMessage("§7Backups cached: §f" + plugin.getCachedTimestamps().size());
                    try { sender.sendMessage("§7Running: §f" + plugin.isBackupRunning()); } catch (Exception ignored) {}
                    try { java.lang.reflect.Field f = plugin.getClass().getDeclaredField("lastError"); f.setAccessible(true); Object le = f.get(plugin); if (le != null) sender.sendMessage("§7LastError: §c" + le); } catch (Exception ignored) {}
                    return true;
                case "status":
                    require(sender, "backupflow.status");
                    sender.sendMessage(plugin.pref() + (plugin.isBackupRunning()?"§eBackup: RUNNING":"§aBackup: IDLE"));
                    long dur = plugin.getLastBackupDuration();
                    if (dur > 0) sender.sendMessage("§7Last duration: §f" + dur + "ms");
                    long end = plugin.getLastBackupEnd();
                    if (end > 0) sender.sendMessage("§7Last finished: §f" + end);
                    sender.sendMessage("§7Cached timestamps: §f" + plugin.getCachedTimestamps().size());
                    return true;
                case "reload":
                    require(sender, "backupflow.reload");
                    boolean ok = plugin.reloadBackupFlowConfig();
                    sender.sendMessage(plugin.pref() + (ok?"§aReload complete":"§cReload failed – check console"));
                    return true;
                default:
                    sender.sendMessage(plugin.pref() + "§cUnknown subcommand. /" + label + " help");
                    return true;
            }
        } catch (Exception e) {
            sender.sendMessage(plugin.pref() + "§cError: " + e.getMessage());
            return true;
        }
    }

    private void require(CommandSender sender, String node) {
        if (sender.hasPermission("backupflow.admin")) return;
        if (sender.hasPermission(node)) return;
        throw new RuntimeException("Missing permission: " + node);
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(plugin.pref() + "§bCommands:");
        s.sendMessage("§f/backupflow backup §7- run full backup");
        s.sendMessage("§f/backupflow list §7- list backup timestamps");
        s.sendMessage("§f/backupflow restore <ts> [--select worlds,plugins,...] [--force] §7- restore backup");
        s.sendMessage("§f/backupflow verify <ts> [--select ...] §7- verify archive hashes");
        s.sendMessage("§f/backupflow retention plan [--keepDays N] [--max N] §7- retention preview");
        s.sendMessage("§f/backupflow manifests §7- list manifest files");
        s.sendMessage("§f/backupflow version §7- show plugin version");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String a = args[0].toLowerCase();
            for (String opt : List.of("help","backup","list","restore","verify","retention","manifests","version","status","reload")) {
                if (opt.startsWith(a)) out.add(opt);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("restore") || args[0].equalsIgnoreCase("verify"))) {
            try { for (String ts : plugin.getCachedTimestamps()) if (ts.startsWith(args[1])) out.add(ts); } catch (Exception ignored) { }
        } else if (args[0].equalsIgnoreCase("restore") || args[0].equalsIgnoreCase("verify")) {
            // suggest flags
            String last = args[args.length-1].toLowerCase();
            for (String opt : List.of("--select","--force")) {
                if (opt.startsWith(last)) out.add(opt);
            }
            // after --select provide section suggestions
            for (int i=0;i<args.length;i++) {
                if (args[i].equalsIgnoreCase("--select") && i+1 < args.length) {
                    String seg = args[i+1];
                    // partial list (comma separated)
                    String[] parts = seg.split(",");
                    String current = parts[parts.length-1].toLowerCase();
                    for (String section : List.of("worlds","plugins","configs","extra")) {
                        if (section.startsWith(current) && !seg.contains(section)) {
                            out.add(section);
                        }
                    }
                }
            }
        } else if (args[0].equalsIgnoreCase("retention") && args.length >=2 ) {
            if (args.length == 2) {
                if ("plan".startsWith(args[1].toLowerCase())) out.add("plan");
            } else {
                String last = args[args.length-1].toLowerCase();
                for (String opt : List.of("--keepDays","--max")) if (opt.startsWith(last)) out.add(opt);
            }
        }
        return out;
    }
}
