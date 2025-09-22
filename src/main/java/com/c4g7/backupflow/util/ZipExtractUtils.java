package com.c4g7.backupflow.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipExtractUtils {
    private ZipExtractUtils() {}

    public static void extractFiltered(Path zip, Path dest, Predicate<String> include) throws IOException {
        Files.createDirectories(dest);
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (include != null && !include.test(name)) continue;
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) continue; // security
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static Predicate<String> buildSelector(Set<String> sections) {
        if (sections == null || sections.isEmpty()) return s -> true; // all
        boolean wantWorlds = sections.contains("worlds");
        boolean wantPlugins = sections.contains("plugins");
        boolean wantConfigs = sections.contains("configs");
        boolean wantExtra = sections.contains("extra");
        return name -> {
            if (wantWorlds && name.startsWith("worlds/")) return true;
            if (wantPlugins && name.startsWith("plugins/")) return true;
            if (wantConfigs && name.startsWith("configs/")) return true;
            if (wantExtra && name.startsWith("extra/")) return true;
            return false;
        };
    }
}
