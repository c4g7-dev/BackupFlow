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
        this(endpoint, secure, access, secret, bucket, rootDir, serverId, 10, 300, 300);
    }

    public BackupStorageService(String endpoint, boolean secure, String access, String secret, String bucket, String rootDir, String serverId, 
                               int connectionPoolSize, int readTimeoutSeconds, int writeTimeoutSeconds) {
        if (endpoint == null || access == null || secret == null || bucket == null) throw new IllegalArgumentException("Missing S3 config");
        this.bucket = bucket;
        this.rootDir = (rootDir == null || rootDir.isBlank()) ? "FlowStack/BackupFlow" : rootDir.replaceAll("^/+|/+$", "");
        this.serverId = (serverId == null || serverId.isBlank()) ? "default" : serverId;
        
        // Configure HTTP client with performance settings
        okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
            .connectionPool(new okhttp3.ConnectionPool(connectionPoolSize, 5, java.util.concurrent.TimeUnit.MINUTES))
            .readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        
        MinioClient.Builder builder = MinioClient.builder()
            .credentials(access, secret)
            .httpClient(httpClient);
            
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
        uploadFile(file, objectName, 64 * 1024 * 1024, 8 * 1024 * 1024); // Default: 64MB part, 8MB buffer
    }

    public void uploadFile(Path file, String objectName, int partSize, int bufferSize) throws Exception {
        long fileSize = Files.size(file);
        // Use larger buffer for better performance with large files
        int effectiveBufferSize = Math.max(bufferSize, 4 * 1024 * 1024); // Minimum 4MB buffer
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file), effectiveBufferSize)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, fileSize, partSize)
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

    public void createRoot() {
        // Put a tiny marker to validate credentials and root path; ignore failures
        try (InputStream in = new java.io.ByteArrayInputStream(new byte[]{0})) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(rootDir + "/.init")
                    .stream(in, 1, -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception ignored) { }
    }

    @Override
    public void close() { }
}
