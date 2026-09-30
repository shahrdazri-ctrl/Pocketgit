package com.pocketgit.services;

import com.pocketgit.model.FileMode;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.Repository;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.storage.ObjectHasher;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.util.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Prepared byte-exact file edits with ordinary-failure rollback; no crash-atomic claim. */
public final class WorkingTreeEdit implements AutoCloseable {
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    private record Data(Path file, long size, String hash, Set<PosixFilePermission> permissions) {}

    private record RecoveryFile(
            String path, String backup, String blobHash, Set<PosixFilePermission> permissions) {}

    private final Repository repository;
    private final Map<String, Data> originals = new LinkedHashMap<>();
    private final Map<String, Data> replacements = new LinkedHashMap<>();
    private final Map<Path, Set<PosixFilePermission>> originalDirectories = new LinkedHashMap<>();
    private final Map<String, BasicFileAttributes> preparedAttributes = new LinkedHashMap<>();
    private final Set<Path> createdDirectories = new HashSet<>();
    private final Set<Path> removedDirectories = new HashSet<>();
    private final Set<String> changedFiles = new HashSet<>();
    private final Set<String> publishedFiles = new HashSet<>();
    private final Set<Path> requiredDirectories = new HashSet<>();
    private final List<String> removals;
    private final Path backupDirectory;
    private final Set<Path> backupFiles = new HashSet<>();
    private boolean applied;
    private boolean rolledBack;
    private boolean completed;
    private boolean closed;
    private boolean recoveryFailed;

    public WorkingTreeEdit(Repository repository, List<IndexEntry> writes, List<String> deletes)
            throws IOException {
        this(repository, writes, deletes, MAX_BYTES);
    }

    /** A smaller preparation budget can be selected without raising the production maximum. */
    public WorkingTreeEdit(
            Repository repository, List<IndexEntry> writes, List<String> deletes, long maxBytes)
            throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_BYTES)
            throw new IllegalArgumentException("invalid working-tree edit budget");
        this.repository = repository;
        removals = List.copyOf(deletes);
        new MetadataFiles(repository).requireDirectory(repository.metadataDirectory());
        backupDirectory = Files.createTempDirectory(repository.metadataDirectory(), "edit-");
        try {
            var objects = new ObjectStore(repository);
            long total = 0;
            var affected = new java.util.TreeSet<>(deletes);
            writes.forEach(entry -> affected.add(entry.path()));
            for (String name : affected) {
                var path = safe(name);
                var attributes = PathUtils.attributesOrMissing(path);
                preparedAttributes.put(name, attributes);
                if (attributes != null && attributes.isRegularFile()) {
                    if (attributes.size() > ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES)
                        throw new IOException("working file exceeds edit limit: " + name);
                    requireBudget(total, attributes.size(), maxBytes);
                    var mode = permissions(path);
                    Path backup = newBackup("original-");
                    long size =
                            copyWorkingFile(
                                    path,
                                    backup,
                                    Math.min(
                                            ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES,
                                            maxBytes - total));
                    requireBudget(total, size, maxBytes);
                    var after =
                            Files.readAttributes(
                                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!sameAttributes(attributes, after)
                            || size != attributes.size()
                            || !Objects.equals(mode, permissions(path))) {
                        throw new IOException("working file changed during preparation: " + name);
                    }
                    originals.put(name, new Data(backup, size, hashFile(backup, size), mode));
                    total += size;
                } else if (attributes != null && !attributes.isDirectory())
                    throw new IOException("unsupported working file: " + name);
                for (Path parent = path.getParent();
                        parent.startsWith(repository.root());
                        parent = parent.getParent()) {
                    if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                            && !originalDirectories.containsKey(parent)) {
                        originalDirectories.put(parent, permissions(parent));
                    }
                    if (parent.equals(repository.root())) break;
                }
            }
            for (var entry : writes) {
                for (Path parent = repository.root().resolve(entry.path()).getParent();
                        !parent.equals(repository.root());
                        parent = parent.getParent()) {
                    requiredDirectories.add(parent);
                }
                var summary = objects.verifyBlob(entry.blobHash());
                requireBudget(total, summary.size(), maxBytes);
                Path content = newBackup("replacement-");
                try (var channel =
                                java.nio.channels.FileChannel.open(
                                        content, java.nio.file.StandardOpenOption.WRITE);
                        var output = java.nio.channels.Channels.newOutputStream(channel)) {
                    objects.copyBlob(entry.blobHash(), output);
                    channel.force(true);
                }
                total += summary.size();
                var existing = originals.get(entry.path());
                var mode =
                        existing == null || existing.permissions() == null
                                ? defaultPermissions()
                                : new HashSet<>(existing.permissions());
                mode.remove(PosixFilePermission.OWNER_EXECUTE);
                mode.remove(PosixFilePermission.GROUP_EXECUTE);
                mode.remove(PosixFilePermission.OTHERS_EXECUTE);
                if (entry.mode() == FileMode.EXECUTABLE_FILE)
                    mode.add(PosixFilePermission.OWNER_EXECUTE);
                replacements.put(
                        entry.path(), new Data(content, summary.size(), entry.blobHash(), mode));
            }
            var recovery =
                    originals.entrySet().stream()
                            .map(
                                    entry ->
                                            new RecoveryFile(
                                                    entry.getKey(),
                                                    entry.getValue()
                                                            .file()
                                                            .getFileName()
                                                            .toString(),
                                                    entry.getValue().hash(),
                                                    entry.getValue().permissions()))
                            .toList();
            Path manifest = backupDirectory.resolve("manifest.json");
            backupFiles.add(manifest);
            Files.write(
                    manifest,
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsBytes(
                                    Map.of(
                                            "version",
                                            1,
                                            "originals",
                                            recovery,
                                            "replacements",
                                            List.copyOf(replacements.keySet()))),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException | RuntimeException failure) {
            try {
                cleanup();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Path newBackup(String prefix) throws IOException {
        Path file = Files.createTempFile(backupDirectory, prefix, ".data");
        backupFiles.add(file);
        return file;
    }

    private long copyWorkingFile(Path source, Path backup, long limit) throws IOException {
        long copied = 0;
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                var channel =
                        java.nio.channels.FileChannel.open(
                                backup, java.nio.file.StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - copied)
                    throw new IOException("working file exceeds preparation budget: " + source);
                copied += count;
                var bytes = java.nio.ByteBuffer.wrap(buffer, 0, count);
                while (bytes.hasRemaining()) channel.write(bytes);
            }
            channel.force(true);
        }
        return copied;
    }

    private String hashFile(Path file, long size) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return new ObjectHasher().hash(ObjectType.BLOB, size, input);
        }
    }

    private static void requireBudget(long total, long additional, long maxBytes)
            throws IOException {
        if (additional > maxBytes - total)
            throw new IOException(
                    "working-tree edit exceeds " + maxBytes + " byte backup and content limit");
    }

    private Set<PosixFilePermission> defaultPermissions() {
        return new HashSet<>(
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
    }

    private Set<PosixFilePermission> permissions(Path path) throws IOException {
        var view =
                Files.getFileAttributeView(
                        path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return view == null ? null : new HashSet<>(view.readAttributes().permissions());
    }

    private Path safe(String name) throws IOException {
        return PathUtils.safeWorkingPath(repository.root(), repository.root().resolve(name));
    }

    public void apply() throws IOException {
        if (applied || rolledBack || closed)
            throw new IllegalStateException(
                    "working-tree edit has already been applied or rolled back");
        requireUnchanged();
        applied = true;
        var ordered = new ArrayList<>(removals);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : ordered) {
            // Replacing a regular file is already atomic; deleting it first needlessly
            // recreates parent directories and changes their permissions and identity.
            if (replacements.containsKey(name)) continue;
            Path path = safe(name);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path);
                changedFiles.add(name);
                prune(path.getParent());
            }
        }
        for (var entry : replacements.entrySet()) write(entry.getKey(), entry.getValue(), true);
    }

    private static boolean sameAttributes(BasicFileAttributes before, BasicFileAttributes after) {
        return before.isRegularFile() == after.isRegularFile()
                && before.isDirectory() == after.isDirectory()
                && !after.isSymbolicLink()
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey());
    }

    /** Recheck every backup before the first mutation, including originally missing paths. */
    private void requireUnchanged() throws IOException {
        for (var entry : preparedAttributes.entrySet()) {
            Path path = safe(entry.getKey());
            var before = entry.getValue();
            var now = PathUtils.attributesOrMissing(path);
            boolean unchanged =
                    before == null ? now == null : now != null && sameAttributes(before, now);
            var original = originals.get(entry.getKey());
            if (unchanged && original != null) {
                unchanged =
                        original.hash().equals(hashFile(path, original.size()))
                                && Objects.equals(original.permissions(), permissions(path));
            }
            if (!unchanged)
                throw new IOException(
                        "working path changed after preparation; retry after inspecting files: "
                                + entry.getKey());
        }
    }

    private void prune(Path directory) throws IOException {
        while (!directory.equals(repository.root())
                && !requiredDirectories.contains(directory)
                && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(directory)) {
                if (children.findAny().isPresent()) break;
            }
            Files.delete(directory);
            removedDirectories.add(directory);
            directory = directory.getParent();
        }
    }

    private void write(String name, Data data, boolean recordMutation) throws IOException {
        Path destination = safe(name);
        var missing = new ArrayList<Path>();
        for (Path parent = destination.getParent();
                !Files.exists(parent, LinkOption.NOFOLLOW_LINKS);
                parent = parent.getParent()) missing.add(parent);
        java.util.Collections.reverse(missing);
        for (Path parent : missing) {
            Files.createDirectory(parent);
            createdDirectories.add(parent);
        }
        destination = safe(name);
        Path temporary = Files.createTempFile(destination.getParent(), ".pocketgit-write-", ".tmp");
        try {
            try (var channel =
                    java.nio.channels.FileChannel.open(
                            temporary, java.nio.file.StandardOpenOption.WRITE)) {
                try (var input = Files.newInputStream(data.file(), LinkOption.NOFOLLOW_LINKS)) {
                    var output = java.nio.channels.Channels.newOutputStream(channel);
                    input.transferTo(output);
                }
                channel.force(true);
            }
            if (data.permissions() != null
                    && Files.getFileAttributeView(temporary, PosixFileAttributeView.class) != null)
                Files.setPosixFilePermissions(temporary, data.permissions());
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            if (recordMutation) {
                changedFiles.add(name);
                publishedFiles.add(name);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void rollback() throws IOException {
        if (completed) throw new IllegalStateException("working-tree edit is already completed");
        if (!applied || rolledBack) return;
        IOException failure = null;
        var names = new ArrayList<>(publishedFiles);
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            try {
                Path path = safe(name);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
                prune(path.getParent());
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        var directories = new ArrayList<>(createdDirectories);
        directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : directories) {
            try {
                if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    try (var children = Files.list(directory)) {
                        if (children.findAny().isEmpty()) Files.delete(directory);
                    }
                }
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        var previousDirectories = new ArrayList<>(removedDirectories);
        previousDirectories.sort(Comparator.comparingInt(Path::getNameCount));
        for (Path directory : previousDirectories) {
            try {
                PathUtils.safeWorkingPath(repository.root(), directory);
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(directory);
                    var mode = originalDirectories.get(directory);
                    if (mode != null
                            && Files.getFileAttributeView(directory, PosixFileAttributeView.class)
                                    != null) Files.setPosixFilePermissions(directory, mode);
                }
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        for (var entry : originals.entrySet()) {
            if (!changedFiles.contains(entry.getKey())) continue;
            try {
                write(entry.getKey(), entry.getValue(), false);
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) {
            recoveryFailed = true;
            throw new IOException(
                    "working-tree recovery incomplete; private backups retained at "
                            + backupDirectory,
                    failure);
        }
        rolledBack = true;
    }

    /** Called only after all cooperating metadata publications have succeeded. */
    public void complete() {
        if (!applied || rolledBack || closed || completed)
            throw new IllegalStateException("edit cannot be completed");
        completed = true;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        if (recoveryFailed)
            throw new IOException("private recovery backups retained at " + backupDirectory);
        if (applied && !completed && !rolledBack) {
            try {
                rollback();
            } catch (IOException failure) {
                throw new IOException(
                        "working-tree recovery incomplete; private backups retained at "
                                + backupDirectory,
                        failure);
            }
        }
        try {
            cleanup();
        } catch (IOException failure) {
            throw new IOException(
                    (completed ? "operation completed" : "working-tree changes recovered")
                            + "; could not clean private preparation directory "
                            + backupDirectory,
                    failure);
        }
        closed = true;
    }

    private void cleanup() throws IOException {
        IOException failure = null;
        for (Path path : backupFiles) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        try {
            Files.deleteIfExists(backupDirectory);
        } catch (IOException problem) {
            if (failure == null) failure = problem;
            else failure.addSuppressed(problem);
        }
        if (failure != null) throw failure;
    }
}
