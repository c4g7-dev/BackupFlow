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
                    requireAdmin(sender);
                    plugin.runBackup("manual");
                    sender.sendMessage("§aBackup started.");
                    return true;
                case "restore":
                    requireAdmin(sender);
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
                    plugin.restoreBackup(ts, sections, force);
                    sender.sendMessage("§aRestore initiated for " + ts + (sections.isEmpty()?" (all sections)":" sections=" + sections));
                    return true;
                case "list":
                    var list = plugin.getStorage().listBackups("full");
                    sender.sendMessage("§eFull Backups (" + list.size() + "): " + list);
                    return true;
                case "manifests":
                    var manifests = plugin.getStorage().listManifests();
                    sender.sendMessage("§eManifests (" + manifests.size() + "): " + manifests);
                    return true;
                case "version":
                    sender.sendMessage("BackupFlow " + plugin.getDescription().getVersion());
                    return true;
                default:
                    sender.sendMessage("§cUnknown subcommand. /" + label + " help");
                    return true;
            }
        } catch (Exception e) {
            sender.sendMessage("§cError: " + e.getMessage());
            return true;
        }
    }

    private void requireAdmin(CommandSender sender) {
        if (!(sender.hasPermission("backupflow.admin") || !(sender instanceof Player))) return;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§b§lBackupFlow Commands:");
        s.sendMessage("§f/backupflow backup §7- run full backup");
    s.sendMessage("§f/backupflow list §7- list backup timestamps");
    s.sendMessage("§f/backupflow restore <ts> [--select worlds,plugins,...] [--force] §7- restore backup");
        s.sendMessage("§f/backupflow manifests §7- list manifest files");
        s.sendMessage("§f/backupflow version §7- show plugin version");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String a = args[0].toLowerCase();
            for (String opt : List.of("help","backup","list","restore","manifests","version")) {
                if (opt.startsWith(a)) out.add(opt);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
            try {
                for (String ts : plugin.getStorage().listBackups("full")) {
                    if (ts.startsWith(args[1])) out.add(ts);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }
}
