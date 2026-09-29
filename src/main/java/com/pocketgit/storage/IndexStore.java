package com.pocketgit.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pocketgit.model.Index;
import com.pocketgit.repository.InvalidRepositoryException;
import com.pocketgit.repository.Repository;
import com.pocketgit.util.JsonUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Versioned index serialization with exclusive updates and atomic replacement. */
public final class IndexStore {
    public static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private final Repository repository;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public IndexStore(Repository repository) { this.repository = repository; }

    public Index load() throws IOException {
        validateStorage();
        try (var input = Files.newInputStream(repository.indexFile(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) throw new IndexCorruptionException("index exceeds 16 MiB limit");
            try {
                Index index = mapper.readValue(bytes, Index.class);
                if (index == null) throw new IndexCorruptionException("index cannot be null");
                return index;
            }
            catch (IOException | IllegalArgumentException invalid) {
                throw new IndexCorruptionException("invalid index: " + invalid.getMessage(), invalid);
            }
        }
    }

    public void save(Index index) throws IOException {
        java.util.Objects.requireNonNull(index, "index");
        try (Update update = beginUpdate()) { update.save(index); }
    }

    /** Hold the lock across load, planning, Blob writes, and save to avoid lost additions. */
    public Update beginUpdate() throws IOException {
        validateStorage();
        Path lock = repository.metadataDirectory().resolve("index.lock");
        try { Files.createFile(lock); }
        catch (FileAlreadyExistsException busy) {
            throw new IOException("index is locked by another operation; inspect .pocketgit/index.lock before retrying", busy);
        }
        return new Update(lock);
    }

    public final class Update implements AutoCloseable {
        private final Path lock;
        private boolean closed;
        private Update(Path lock) { this.lock = lock; }
        public Index load() throws IOException { requireOpen(); return IndexStore.this.load(); }
        public void save(Index index) throws IOException { requireOpen(); saveLocked(index); }
        private void requireOpen() { if (closed) throw new IllegalStateException("index update is closed"); }
        @Override public void close() throws IOException {
            if (!closed) { closed = true; Files.delete(lock); }
        }
    }

    private void saveLocked(Index index) throws IOException {
        java.util.Objects.requireNonNull(index, "index");
        validateStorage();
        byte[] bytes = (mapper.writer(JsonUtils.prettyPrinter()).writeValueAsString(index) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_INDEX_BYTES) throw new IOException("index exceeds 16 MiB limit");
        Path temporary = Files.createTempFile(repository.metadataDirectory(), ".index-", ".tmp");
        Throwable failure = null;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, repository.indexFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("filesystem does not support atomic index replacement; original index preserved", unsupported);
            }
        } catch (IOException | RuntimeException problem) {
            failure = problem;
            throw problem;
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private void validateStorage() throws IOException {
        var metadata = Files.readAttributes(repository.metadataDirectory(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
            throw new InvalidRepositoryException("index metadata must be a real directory");
        }
        var index = Files.readAttributes(repository.indexFile(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!index.isRegularFile() || index.isSymbolicLink()) throw new IndexCorruptionException("index must be a regular file");
    }
}
