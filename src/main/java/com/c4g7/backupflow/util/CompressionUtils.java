package com.c4g7.backupflow.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CompressionUtils {
    private CompressionUtils() {}

    public static class Result {
        public final Path archive;
        public final java.util.Map<String,String> hashes; // relative path -> sha256
        Result(Path a, java.util.Map<String,String> h) { this.archive = a; this.hashes = h; }
    }

    public static Result compress(Path dir, String mode, boolean withHashes) throws IOException {
        if (mode == null) mode = "zip";
        if (mode.equalsIgnoreCase("gz")) {
            // Future: implement tar.gz; fallback to zip for now
            mode = "zip";
        }
        Path out = Files.createTempFile("backupflow-",".zip");
        java.util.Map<String,String> map = withHashes ? new java.util.LinkedHashMap<>() : java.util.Collections.emptyMap();
        try (OutputStream fo = Files.newOutputStream(out); ZipOutputStream zos = new ZipOutputStream(fo)) {
            Files.walk(dir).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) return;
                    String rel = dir.relativize(p).toString().replace('\\','/');
                    ZipEntry ze = new ZipEntry(rel);
                    zos.putNextEntry(ze);
                    Files.copy(p, zos);
                    zos.closeEntry();
                    if (withHashes) {
                        try { map.put(rel, HashUtils.sha256(p)); } catch (IOException ignored) { }
                    }
                } catch (IOException ignored) { }
            });
        }
        return new Result(out, map);
    }
}
