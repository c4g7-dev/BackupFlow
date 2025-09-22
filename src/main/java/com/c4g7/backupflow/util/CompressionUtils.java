package com.c4g7.backupflow.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CompressionUtils {
    private CompressionUtils() {}

    public static Path compress(Path dir, String mode) throws IOException {
        if (mode == null) mode = "zip";
        if (mode.equalsIgnoreCase("gz")) {
            // Future: implement tar.gz; fallback to zip for now
            mode = "zip";
        }
        Path out = Files.createTempFile("backupflow-",".zip");
        try (OutputStream fo = Files.newOutputStream(out); ZipOutputStream zos = new ZipOutputStream(fo)) {
            Files.walk(dir).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) return;
                    String rel = dir.relativize(p).toString().replace('\\','/');
                    ZipEntry ze = new ZipEntry(rel);
                    zos.putNextEntry(ze);
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException ignored) { }
            });
        }
        return out;
    }
}
