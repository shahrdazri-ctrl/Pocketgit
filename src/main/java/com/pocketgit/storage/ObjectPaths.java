package com.pocketgit.storage;

import com.pocketgit.repository.InvalidRepositoryException;
import com.pocketgit.repository.Repository;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Restricts IDs and rejects symlinked metadata/object directories. */
final class ObjectPaths {
    private final Repository repository;

    ObjectPaths(Repository repository) throws IOException {
        this.repository = new Repository(repository.root().toRealPath());
        checkBase();
    }

    static void validateHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid object ID: expected 64 lowercase hexadecimal characters");
        }
    }

    Path pathForHash(String hash) {
        validateHash(hash);
        return repository.objectsDirectory().resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }

    void checkBase() throws IOException {
        requireDirectory(repository.metadataDirectory());
        requireDirectory(repository.objectsDirectory());
    }

    void preparePrefix(String hash) throws IOException {
        checkBase();
        Path prefix = pathForHash(hash).getParent();
        try { Files.createDirectory(prefix); }
        catch (FileAlreadyExistsException existing) { /* Validate the existing prefix below. */ }
        requireDirectory(prefix);
    }

    void checkPrefix(String hash) throws IOException {
        checkBase();
        requireDirectory(pathForHash(hash).getParent());
    }

    private void requireDirectory(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new InvalidRepositoryException("object storage requires a real directory: " + path);
        }
    }
}
