package com.pocketgit.repository;

import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.storage.ObjectHasher;
import com.pocketgit.util.FileModeUtils;
import com.pocketgit.util.PathUtils;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Read-only fingerprints for indexed files and path discovery for untracked/ignored content. */
public final class WorkingTree {
    public record Snapshot(Index indexedFiles, List<String> untracked, List<String> ignored) {
        public Snapshot {
            untracked = List.copyOf(untracked);
            ignored = List.copyOf(ignored);
        }
    }

    public Snapshot read(Repository repository, Index index) throws IOException {
        Path root = repository.root();
        var ignore = IgnoreMatcher.load(repository);
        var tracked = new HashSet<String>();
        var trackedDirectories = new HashSet<String>();
        var working = new ArrayList<IndexEntry>();
        // Visit tracked paths explicitly, including files under newly ignored directories.
        for (var entry : index.entries()) {
            tracked.add(entry.path());
            for (int slash = entry.path().indexOf('/');
                    slash >= 0;
                    slash = entry.path().indexOf('/', slash + 1)) {
                trackedDirectories.add(entry.path().substring(0, slash));
            }
            Path file = PathUtils.safeWorkingPath(root, root.resolve(entry.path()));
            var attributes = PathUtils.attributesOrMissing(file);
            if (attributes == null || attributes.isDirectory()) continue;
            if (!attributes.isRegularFile())
                throw new IOException("unsupported file type: " + file);
            working.add(fingerprint(root, file, entry.path()));
        }
        var untracked = new TreeSet<String>();
        var ignored = new TreeSet<String>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        if (directory.equals(root)) return FileVisitResult.CONTINUE;
                        if (metadata(directory)) return FileVisitResult.SKIP_SUBTREE;
                        String path = relative(root, directory);
                        if (ignore.isIgnored(path, true) && !trackedDirectories.contains(path)) {
                            ignored.add(path + "/");
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        if (metadata(file)) return FileVisitResult.CONTINUE;
                        String path = relative(root, file);
                        if (tracked.contains(path)) return FileVisitResult.CONTINUE;
                        if (ignore.isIgnored(path, false)) {
                            ignored.add(path);
                            return FileVisitResult.CONTINUE;
                        }
                        if (attributes.isSymbolicLink())
                            throw new IOException("symbolic links are not supported: " + file);
                        if (!attributes.isRegularFile())
                            throw new IOException("unsupported file type: " + file);
                        untracked.add(path);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure)
                            throws IOException {
                        throw failure;
                    }
                });
        return new Snapshot(
                new Index(1, working), new ArrayList<>(untracked), new ArrayList<>(ignored));
    }

    private IndexEntry fingerprint(Path root, Path file, String path) throws IOException {
        file = PathUtils.safeWorkingPath(root, file);
        var before =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink())
            throw new IOException("unsupported file type: " + file);
        var mode = FileModeUtils.fileMode(file);
        String hash;
        try (var input =
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            hash = new ObjectHasher().hash(ObjectType.BLOB, before.size(), input);
        }
        var after =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile()
                || after.isSymbolicLink()
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())
                || !mode.equals(FileModeUtils.fileMode(file))) {
            throw new IOException("working file changed during status; retry status: " + file);
        }
        return new IndexEntry(path, hash, mode);
    }

    private boolean metadata(Path path) {
        return path.getFileName().toString().equalsIgnoreCase(Repository.METADATA_NAME);
    }

    private String relative(Path root, Path path) throws IOException {
        try {
            return PathUtils.relativePath(root, path);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("unsupported working-tree path: " + path, invalid);
        }
    }
}
