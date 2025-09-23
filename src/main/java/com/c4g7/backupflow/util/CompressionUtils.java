package com.c4g7.backupflow.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
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
        return compress(dir, mode, withHashes, java.util.zip.Deflater.DEFAULT_COMPRESSION);
    }

    public static Result compress(Path dir, String mode, boolean withHashes, int compressionLevel) throws IOException {
        return compress(dir, mode, withHashes, compressionLevel, false);
    }

    public static Result compress(Path dir, String mode, boolean withHashes, int compressionLevel, boolean parallel) throws IOException {
        if (mode == null) mode = "zip";
        if (mode.equalsIgnoreCase("gz")) {
            // Future: implement tar.gz; fallback to zip for now
            mode = "zip";
        }
        Path out = Files.createTempFile("backupflow-",".zip");
        java.util.Map<String,String> map = withHashes ? 
            (parallel ? new ConcurrentHashMap<>() : new java.util.LinkedHashMap<>()) : 
            java.util.Collections.emptyMap();
        
        try (OutputStream fo = Files.newOutputStream(out); 
             java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fo, 1024 * 1024); // 1MB buffer
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            // Set compression level (0-9: 0=no compression, 9=max compression)
            zos.setLevel(Math.max(0, Math.min(9, compressionLevel)));
            
            if (parallel && withHashes) {
                // Parallel processing: first collect all files and compute hashes in parallel
                try (Stream<Path> pathStream = Files.walk(dir)) {
                    pathStream.filter(Files::isRegularFile)
                        .parallel()
                        .forEach(p -> {
                            try {
                                String rel = dir.relativize(p).toString().replace('\\','/');
                                String hash = HashUtils.sha256(p);
                                map.put(rel, hash);
                            } catch (IOException ignored) { }
                        });
                }
                // Then add files to zip sequentially (ZipOutputStream is not thread-safe)
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
            } else {
                // Sequential processing (original behavior)
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
        }
        return new Result(out, map);
    }
}
