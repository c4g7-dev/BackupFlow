package com.c4g7.backupflow.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class ManifestBuilder {
    private ManifestBuilder() {}

    public static Path writeSimpleManifest(Path tempDir, String fileName, String reason, String serverId, List<String> files) throws IOException {
        String json = toJson(reason, serverId, files);
        Path out = tempDir.resolve(fileName);
        Files.writeString(out, json, StandardCharsets.UTF_8);
        return out;
    }

    private static String toJson(String reason, String serverId, List<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"timestamp\":").append(Instant.now().toEpochMilli()).append(',');
        sb.append("\"serverId\":\"").append(escape(serverId)).append("\",");
        sb.append("\"reason\":\"").append(escape(reason)).append("\",");
        sb.append("\"files\":[");
        for (int i=0;i<files.size();i++) {
            if (i>0) sb.append(',');
            sb.append("\"").append(escape(files.get(i))).append("\"");
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String in) {
        if (in == null) return "";
        return in.replace("\\","\\\\").replace("\"","\\\"");
    }
}
