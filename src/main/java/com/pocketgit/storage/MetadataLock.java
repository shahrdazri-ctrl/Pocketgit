package com.pocketgit.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exclusive, cooperating-process lock. Abandoned locks require manual inspection. */
public final class MetadataLock implements AutoCloseable {
    private final Path path;
    private boolean closed;
    private MetadataLock(Path path) { this.path = path; }
    public static MetadataLock acquire(MetadataFiles files, Path path) throws IOException {
        files.validateTarget(path);
        try { Files.createFile(path); }
        catch (FileAlreadyExistsException busy) { throw new IOException("metadata is locked: " + path + "; inspect active operations before retrying", busy); }
        return new MetadataLock(path);
    }
    @Override public void close() throws IOException {
        if (!closed) { closed = true; Files.delete(path); }
    }
}
