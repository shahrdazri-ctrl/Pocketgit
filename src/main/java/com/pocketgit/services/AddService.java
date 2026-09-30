package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.repository.IgnoreMatcher;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.IndexStore;
import com.pocketgit.storage.ObjectStore;
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
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stages one scope as a new index snapshot; publishes the index only after all Blob writes succeed.
 */
public final class AddService {
    public record AddResult(
            List<String> stagedPaths, List<String> removedPaths, List<String> ignoredPaths) {
        public AddResult {
            stagedPaths = List.copyOf(stagedPaths);
            removedPaths = List.copyOf(removedPaths);
            ignoredPaths = List.copyOf(ignoredPaths);
        }
    }

    public AddResult add(Path workingDirectory, Path requested) throws IOException {
        if (requested.toString().isEmpty())
            throw new IOException(
                    "staging path must not be empty; use '.' for the whole repository");
        Path cwd = workingDirectory.toRealPath();
        Path root =
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository"));
        Repository repository = new Repository(root);
        // The master prompt defines add . as the whole working-tree scope, including nested
        // invocations.
        boolean all = requested.toString().equals(".");
        Path scopePath = PathUtils.safeWorkingPath(root, all ? root : cwd.resolve(requested));
        String scope = scopePath.equals(root) ? "" : PathUtils.relativePath(root, scopePath);
        ObjectStore objects = new ObjectStore(repository);
        IndexStore indexes = new IndexStore(repository);
        try (var update = indexes.beginUpdate()) {
            Index original = update.load();
            IgnoreMatcher ignore = IgnoreMatcher.load(repository);
            var entries = new TreeMap<String, IndexEntry>();
            original.entries().forEach(entry -> entries.put(entry.path(), entry));
            var candidates = new TreeMap<String, Path>();
            var ignored = new TreeSet<String>();
            boolean matchedTracked = false;
            // Tracked paths stay tracked even after new ignore rules are introduced.
            for (IndexEntry entry : original.entries()) {
                if (!PathUtils.inScope(entry.path(), scope)) continue;
                matchedTracked = true;
                Path file = PathUtils.safeWorkingPath(root, root.resolve(entry.path()));
                BasicFileAttributes attributes = attributesOrMissing(file);
                if (attributes == null || attributes.isDirectory()) entries.remove(entry.path());
                else if (!attributes.isRegularFile())
                    throw new IOException("unsupported file type: " + file);
                else candidates.put(entry.path(), file);
            }
            BasicFileAttributes scopeAttributes = attributesOrMissing(scopePath);
            if (scopeAttributes == null && !matchedTracked)
                throw new IOException("path does not exist and is not staged: " + requested);
            if (scopeAttributes != null) {
                Files.walkFileTree(
                        scopePath,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                    Path directory, BasicFileAttributes attributes)
                                    throws IOException {
                                if (directory.getFileName() != null
                                        && directory
                                                .getFileName()
                                                .toString()
                                                .equalsIgnoreCase(Repository.METADATA_NAME)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                String path =
                                        directory.equals(root)
                                                ? ""
                                                : PathUtils.relativePath(root, directory);
                                if (!path.isEmpty() && ignore.isIgnored(path, true)) {
                                    ignored.add(path);
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(
                                    Path file, BasicFileAttributes attributes) throws IOException {
                                if (file.getFileName()
                                        .toString()
                                        .equalsIgnoreCase(Repository.METADATA_NAME))
                                    return FileVisitResult.CONTINUE;
                                String path = PathUtils.relativePath(root, file);
                                if (!entries.containsKey(path) && ignore.isIgnored(path, false)) {
                                    ignored.add(path);
                                    return FileVisitResult.CONTINUE;
                                }
                                if (attributes.isSymbolicLink())
                                    throw new IOException(
                                            "symbolic links are not supported: " + file);
                                if (!attributes.isRegularFile())
                                    throw new IOException("unsupported file type: " + file);
                                candidates.put(path, file);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException failure)
                                    throws IOException {
                                throw failure;
                            }
                        });
            }
            for (var candidate : candidates.entrySet()) {
                String path = candidate.getKey();
                Path file = PathUtils.safeWorkingPath(root, candidate.getValue());
                BasicFileAttributes before =
                        Files.readAttributes(
                                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!before.isRegularFile())
                    throw new IOException("unsupported file type: " + file);
                if (before.size() > ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES)
                    throw new IOException("file exceeds 64 MiB object limit: " + file);
                String hash;
                var mode = FileModeUtils.fileMode(file);
                try (var input =
                        Files.newInputStream(
                                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    hash = objects.writeBlob(before.size(), input);
                }
                BasicFileAttributes after =
                        Files.readAttributes(
                                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (after.isSymbolicLink()
                        || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                        || mode != FileModeUtils.fileMode(file)) {
                    throw new IOException("file changed during staging; retry add: " + file);
                }
                // Resolve file/directory transitions, including a newly staged child of an old
                // file.
                // Sorted descendants occupy one range; only actual ancestors need inspection.
                // A full-index removeIf here made staging N unchanged files quadratic.
                entries.subMap(path + "/", true, path + "0", false).clear();
                for (int slash = path.indexOf('/');
                        slash >= 0;
                        slash = path.indexOf('/', slash + 1)) {
                    entries.remove(path.substring(0, slash));
                }
                entries.put(path, new IndexEntry(path, hash, mode));
            }
            Index next = new Index(1, new ArrayList<>(entries.values()));
            if (!next.equals(original)) update.save(next);
            List<String> removed =
                    original.entries().stream()
                            .map(IndexEntry::path)
                            .filter(path -> !entries.containsKey(path))
                            .toList();
            return new AddResult(
                    new ArrayList<>(candidates.keySet()), removed, new ArrayList<>(ignored));
        }
    }

    private BasicFileAttributes attributesOrMissing(Path path) throws IOException {
        return PathUtils.attributesOrMissing(path);
    }
}
