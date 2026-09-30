package com.pocketgit.services;

import com.pocketgit.model.FileMode;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.repository.Repository;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.util.FileModeUtils;
import com.pocketgit.util.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prepared byte-exact file edits with ordinary-failure rollback; no crash-atomic claim. */
public final class WorkingTreeEdit {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private record Data(byte[] bytes, Set<PosixFilePermission> permissions) {}
    private final Repository repository;
    private final Map<String, Data> originals = new LinkedHashMap<>();
    private final Map<String, Data> replacements = new LinkedHashMap<>();
    private final Set<Path> originalDirectories = new HashSet<>();
    private final Set<Path> createdDirectories = new HashSet<>();
    private final List<String> removals;
    private boolean applied;

    public WorkingTreeEdit(Repository repository, List<IndexEntry> writes, List<String> deletes) throws IOException {
        this.repository = repository; removals = List.copyOf(deletes);
        var objects = new ObjectStore(repository);
        long total = 0;
        var affected = new java.util.TreeSet<>(deletes); writes.forEach(entry -> affected.add(entry.path()));
        for (String name : affected) {
            var path = safe(name); var attributes = PathUtils.attributesOrMissing(path);
            if (attributes != null && attributes.isRegularFile()) {
                byte[] bytes;
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES + 1); }
                if (bytes.length > ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES) throw new IOException("working file exceeds edit limit: " + name);
                originals.put(name, new Data(bytes, permissions(path))); total += bytes.length;
            } else if (attributes != null && !attributes.isDirectory()) throw new IOException("unsupported working file: " + name);
            for (Path parent = path.getParent(); parent.startsWith(repository.root()); parent = parent.getParent()) {
                if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) originalDirectories.add(parent);
                if (parent.equals(repository.root())) break;
            }
        }
        for (var entry : writes) {
            byte[] bytes = objects.readBlob(entry.blobHash()).content(); total += bytes.length;
            if (total > MAX_BYTES) throw new IOException("working-tree edit exceeds 256 MiB backup and content limit");
            var existing = originals.get(entry.path());
            var mode = existing == null || existing.permissions() == null ? defaultPermissions() : new HashSet<>(existing.permissions());
            mode.remove(PosixFilePermission.OWNER_EXECUTE); mode.remove(PosixFilePermission.GROUP_EXECUTE); mode.remove(PosixFilePermission.OTHERS_EXECUTE);
            if (entry.mode() == FileMode.EXECUTABLE_FILE) mode.add(PosixFilePermission.OWNER_EXECUTE);
            replacements.put(entry.path(), new Data(bytes, mode));
        }
        if (total > MAX_BYTES) throw new IOException("working-tree edit exceeds 256 MiB backup and content limit");
    }
    private Set<PosixFilePermission> defaultPermissions() {
        return new HashSet<>(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
    }
    private Set<PosixFilePermission> permissions(Path path) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return view == null ? null : new HashSet<>(view.readAttributes().permissions());
    }
    private Path safe(String name) throws IOException { return PathUtils.safeWorkingPath(repository.root(), repository.root().resolve(name)); }
    public void apply() throws IOException {
        applied = true;
        var ordered = new ArrayList<>(removals); ordered.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : ordered) {
            Path path = safe(name);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path);
            prune(path.getParent());
        }
        for (var entry : replacements.entrySet()) write(entry.getKey(), entry.getValue());
    }
    private void prune(Path directory) throws IOException {
        while (!directory.equals(repository.root()) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(directory)) { if (children.findAny().isPresent()) break; }
            Files.delete(directory); directory = directory.getParent();
        }
    }
    private void write(String name, Data data) throws IOException {
        Path destination = safe(name);
        var missing = new ArrayList<Path>();
        for (Path parent = destination.getParent(); !Files.exists(parent, LinkOption.NOFOLLOW_LINKS); parent = parent.getParent()) missing.add(parent);
        java.util.Collections.reverse(missing);
        for (Path parent : missing) { Files.createDirectory(parent); createdDirectories.add(parent); }
        destination = safe(name);
        Path temporary = Files.createTempFile(destination.getParent(), ".pocketgit-write-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(data.bytes()); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            if (data.permissions() != null && Files.getFileAttributeView(temporary, PosixFileAttributeView.class) != null) Files.setPosixFilePermissions(temporary, data.permissions());
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public void rollback() throws IOException {
        if (!applied) return;
        IOException failure = null;
        var names = new ArrayList<>(replacements.keySet()); names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            try { Path path = safe(name); if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path); prune(path.getParent()); }
            catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        }
        var directories = new ArrayList<>(createdDirectories); directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : directories) {
            try { if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { try (var children = Files.list(directory)) { if (children.findAny().isEmpty()) Files.delete(directory); } } }
            catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        }
        var previousDirectories = new ArrayList<>(originalDirectories); previousDirectories.sort(Comparator.comparingInt(Path::getNameCount));
        for (Path directory : previousDirectories) { if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory); }
        for (var entry : originals.entrySet()) { try { write(entry.getKey(), entry.getValue()); } catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); } }
        if (failure != null) throw failure;
    }
}
