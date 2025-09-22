package com.c4g7.backupflow;

import io.minio.*;
import io.minio.messages.Item;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Storage abstraction for BackupFlow.
 * Layout:
 *   <rootDir>/backups/<serverId>/full/<timestamp>/<archiveFile>
 *   <rootDir>/backups/<serverId>/inc/<timestamp>/<archiveFile>   (future incremental)
 *   <rootDir>/manifests/<serverId>-<timestamp>.json
 */
public class BackupStorageService implements AutoCloseable {
    private final MinioClient client;
    private final String bucket;
    private final String rootDir;
    private final String serverId;

    public BackupStorageService(String endpoint, boolean secure, String access, String secret, String bucket, String rootDir, String serverId) {
        if (endpoint == null || access == null || secret == null || bucket == null) throw new IllegalArgumentException("Missing S3 config");
        this.bucket = bucket;
        this.rootDir = (rootDir == null || rootDir.isBlank()) ? "FlowStack/BackupFlow" : rootDir.replaceAll("^/+|/+$", "");
        this.serverId = (serverId == null || serverId.isBlank()) ? "default" : serverId;
        MinioClient.Builder builder = MinioClient.builder().credentials(access, secret);
        String ep = endpoint.trim();
        try {
            if (ep.startsWith("http://") || ep.startsWith("https://")) builder = builder.endpoint(ep);
            else if (ep.contains(":")) {
                int last = ep.lastIndexOf(':');
                String host = ep.substring(0, last);
                int port = Integer.parseInt(ep.substring(last + 1));
                builder = builder.endpoint(host, port, secure);
            } else builder = builder.endpoint(ep, secure ? 443 : 9000, secure);
        } catch (Exception e) { builder = builder.endpoint(ep); }
        this.client = builder.build();
    }

    public String beginFullBackupKeyPrefix(Instant ts) {
        return rootDir + "/backups/" + serverId + "/full/" + ts.toEpochMilli() + "/";
    }

    public String manifestObjectName(String baseName) {
        return rootDir + "/manifests/" + baseName;
    }

    public void uploadFile(Path file, String objectName) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, Files.size(file), -1)
                    .contentType("application/octet-stream")
                    .build());
        }
    }

    public void downloadFile(String objectName, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
             OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }

    public List<String> listBackups(String type) throws Exception { // type: full | inc
        String prefix = rootDir + "/backups/" + serverId + "/" + (type == null ? "full" : type) + "/";
        Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).prefix(prefix).build());
        List<String> entries = new ArrayList<>();
        for (Result<Item> r : results) {
            Item it = r.get();
            String key = it.objectName();
            if (!key.startsWith(prefix)) continue;
            // Collect top-level timestamp folder names
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String ts = rest.substring(0, slash);
                if (!entries.contains(ts)) entries.add(ts);
            }
        }
        return entries;
    }

    public List<String> listManifests() throws Exception {
        String prefix = rootDir + "/manifests/";
        Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(false).prefix(prefix).build());
        List<String> names = new ArrayList<>();
        for (Result<Item> r : results) {
            Item it = r.get();
            String key = it.objectName();
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            if (!rest.isBlank() && !rest.contains("/")) names.add(rest);
        }
        return names;
    }

    public String randomManifestName(Instant ts) {
        return serverId + "-" + ts.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0,8) + ".json";
    }

    @Override
    public void close() { }
}
