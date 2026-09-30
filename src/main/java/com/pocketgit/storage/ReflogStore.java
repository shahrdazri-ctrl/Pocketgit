package com.pocketgit.storage;

import com.pocketgit.repository.Repository;
import com.pocketgit.util.HashUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;

public final class ReflogStore {
    private static final int MAX_LOG_BYTES = 8 * 1024 * 1024;
    private final Repository repository;
    private final MetadataFiles files;
    public ReflogStore(Repository repository) { this.repository = repository; files = new MetadataFiles(repository); }
    public MetadataFiles.Prepared prepareAppend(String oldHash, String newHash, Instant timestamp) throws IOException {
        HashUtils.validateSha256(newHash);
        return prepareAppend(oldHash, newHash, timestamp, "commit");
    }
    public MetadataFiles.Prepared prepareAppend(String oldHash, String newHash, Instant timestamp, String operation) throws IOException {
        if (oldHash != null) HashUtils.validateSha256(oldHash);
        if (newHash != null) HashUtils.validateSha256(newHash);
        if (operation == null || !operation.matches("[a-z]+(?: [A-Za-z0-9_./-]+)*")) throw new IOException("invalid reflog operation");
        java.util.Objects.requireNonNull(timestamp, "timestamp");
        var path = repository.logsDirectory().resolve("HEAD");
        String previous;
        try { previous = files.readText(path, MAX_LOG_BYTES); }
        catch (NoSuchFileException absent) { files.requireDirectory(repository.logsDirectory()); previous = ""; }
        if (!previous.isEmpty() && !previous.endsWith("\n")) throw new IOException("reflog has a truncated final line");
        String line = (oldHash == null ? "0".repeat(64) : oldHash) + " " + (newHash == null ? "0".repeat(64) : newHash) + " " + timestamp + " " + operation + "\n";
        byte[] next = (previous + line).getBytes(StandardCharsets.UTF_8);
        if (next.length > MAX_LOG_BYTES) throw new IOException("reflog exceeds 8 MiB limit");
        return files.prepare(path, next);
    }
}
