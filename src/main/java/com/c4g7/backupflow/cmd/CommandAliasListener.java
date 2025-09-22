package com.c4g7.backupflow.cmd;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

public class CommandAliasListener implements Listener {
    private static final String PRIMARY = "BackupFlow";
    private static final String SHORT = "bf";

    @EventHandler
    public void onPlayer(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (msg == null || msg.length() < 2 || msg.charAt(0) != '/') return;
        String body = msg.substring(1);
        if (body.regionMatches(true,0,SHORT,0,SHORT.length()) && (body.length()==SHORT.length() || Character.isWhitespace(body.charAt(SHORT.length())))) {
            String rest = body.length()>SHORT.length()? body.substring(SHORT.length()):"";
            e.setMessage("/" + PRIMARY + rest);
        }
    }

    @EventHandler
    public void onServer(ServerCommandEvent e) {
        String cmd = e.getCommand();
        if (cmd == null) return;
        if (cmd.regionMatches(true,0,SHORT,0,SHORT.length()) && (cmd.length()==SHORT.length() || Character.isWhitespace(cmd.charAt(SHORT.length())))) {
            String rest = cmd.length()>SHORT.length()? cmd.substring(SHORT.length()):"";
            e.setCommand(PRIMARY + rest);
        }
    }
}
