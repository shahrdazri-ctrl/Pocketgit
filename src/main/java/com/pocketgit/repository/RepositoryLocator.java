package com.pocketgit.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** Discovers the nearest metadata directory without changing any files. */
public final class RepositoryLocator {
    public Optional<Path> findRepositoryRoot(Path startingDirectory) throws IOException {
        Path current = startingDirectory.toAbsolutePath().normalize().toRealPath();
        if (!Files.readAttributes(current, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("repository discovery requires a directory: " + current);
        }
        for (; current != null; current = current.getParent()) {
            Path metadata = new Repository(current).metadataDirectory();
            BasicFileAttributes attributes;
            try {
                attributes =
                        Files.readAttributes(
                                metadata, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                continue;
            }
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new InvalidRepositoryException(
                        "metadata must be a real directory: " + metadata);
            }
            return Optional.of(current);
        }
        return Optional.empty();
    }
}
