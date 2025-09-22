package com.c4g7.backupflow.util;

import java.io.IOException;
import java.nio.file.*;

public final class FileUtils {
    private FileUtils() {}

    public static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            if (!Files.exists(p)) return;
            Files.walk(p)
                    .sorted((a,b)->b.compareTo(a))
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} });
        } catch (IOException ignored) { }
    }
}
