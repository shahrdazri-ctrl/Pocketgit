package com.pocketgit.refs;

import com.pocketgit.repository.Repository;
import com.pocketgit.services.TreeReader;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.storage.MetadataLock;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.util.HashUtils;
import com.pocketgit.util.PathUtils;
import com.pocketgit.validation.RefNameValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Validated branch refs. Prepared writes require the caller to hold commit.lock. */
public final class RefStore {
    private final Repository repository;
    private final MetadataFiles files;

    public RefStore(Repository repository) {
        this.repository = repository;
        files = new MetadataFiles(repository);
    }

    private Path path(String branch) throws IOException {
        try {
            RefNameValidator.validateBranch(branch);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid branch name: " + branch, invalid);
        }
        return repository.headsDirectory().resolve(branch);
    }

    private String branchForRef(String ref) throws IOException {
        if (ref == null || !ref.startsWith("refs/heads/"))
            throw new IOException("unsupported ref; expected refs/heads/<branch>");
        String branch = ref.substring("refs/heads/".length());
        path(branch);
        return branch;
    }

    public Optional<String> readRef(String ref) throws IOException {
        try {
            return Optional.ofNullable(readBranch(branchForRef(ref)));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    public String readBranch(String branch) throws IOException {
        Path destination = path(branch);
        String value = files.readText(destination, 65);
        // Windows resolves case aliases; require the actual spelling rather than silently
        // attaching HEAD to a different name. macOS may normalize Unicode directory entries.
        Path actual = destination.toRealPath(LinkOption.NOFOLLOW_LINKS);
        int depth = repository.headsDirectory().relativize(destination).getNameCount();
        String spelling =
                actual.subpath(actual.getNameCount() - depth, actual.getNameCount())
                        .toString()
                        .replace(actual.getFileSystem().getSeparator(), "/");
        if (!Normalizer.normalize(branch, Normalizer.Form.NFC)
                .equals(Normalizer.normalize(spelling, Normalizer.Form.NFC)))
            throw new IOException(
                    "branch spelling differs from stored name: "
                            + branch
                            + " (stored: "
                            + spelling
                            + ")");
        if (value.isEmpty()) return null;
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        try {
            HashUtils.validateSha256(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid branch commit ID: " + branch, invalid);
        }
        return value;
    }

    private record Namespace(List<String> paths, List<String> branches) {}

    private Namespace readNamespace() throws IOException {
        files.validateTarget(repository.headsDirectory().resolve("validation"));
        var result = new ArrayList<String>();
        var names = new ArrayList<String>();
        try (var paths = Files.walk(repository.headsDirectory())) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (entry.equals(repository.headsDirectory())) continue;
                if (Files.isSymbolicLink(entry))
                    throw new IOException("branch metadata must not be a symlink: " + entry);
                String name =
                        repository
                                .headsDirectory()
                                .relativize(entry)
                                .toString()
                                .replace(entry.getFileSystem().getSeparator(), "/");
                path(name);
                names.add(name);
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                readBranch(name);
                result.add(name);
            }
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
        validateNamespace(names);
        result.sort(String::compareTo);
        return new Namespace(List.copyOf(names), List.copyOf(result));
    }

    private static void validateNamespace(List<String> names) throws IOException {
        try {
            PathUtils.validateSnapshotPaths(names);
        } catch (IllegalArgumentException collision) {
            throw new IOException("branch " + collision.getMessage(), collision);
        }
    }

    public List<String> listBranches() throws IOException {
        return readNamespace().branches();
    }

    public void requireUnchanged(String branch, String expected) throws IOException {
        if (!Objects.equals(readBranch(branch), expected))
            throw new IOException(
                    "branch changed during operation; retry after inspecting repository");
    }

    private void validateCommit(String hash) throws IOException {
        try {
            HashUtils.validateSha256(hash);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid commit ID", invalid);
        }
        var objects = new ObjectStore(repository);
        var codec = new ObjectCodec();
        var commit = codec.decodeCommit(objects.readCommit(hash));
        new TreeReader(objects).readSnapshot(commit.treeHash());
        for (String parent : commit.parentHashes()) codec.decodeCommit(objects.readCommit(parent));
    }

    public MetadataFiles.Prepared prepare(String branch, String expected, String next)
            throws IOException {
        requireUnchanged(branch, expected);
        validateCommit(next);
        return files.prepare(path(branch), (next + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    public MetadataFiles.Prepared prepareNew(String branch, String hash) throws IOException {
        Path destination = path(branch);
        var names = new ArrayList<>(readNamespace().paths());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("branch already exists: " + branch);
        // A normalizing filesystem can return NFD for an NFC parent we created. Use its
        // actual parent spelling when both names resolve to that same directory; retain
        // case differences so the namespace check rejects them on every platform.
        var proposed = new ArrayList<String>();
        Path cursor = repository.headsDirectory();
        for (Path component : repository.headsDirectory().relativize(destination)) {
            cursor = cursor.resolve(component);
            String spelling = component.toString();
            if (Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                String actual =
                        cursor.toRealPath(LinkOption.NOFOLLOW_LINKS).getFileName().toString();
                if (Normalizer.normalize(spelling, Normalizer.Form.NFC)
                        .equals(Normalizer.normalize(actual, Normalizer.Form.NFC)))
                    spelling = actual;
            }
            proposed.add(spelling);
        }
        names.add(String.join("/", proposed));
        validateNamespace(names);
        validateCommit(hash);
        files.validateTarget(repository.headsDirectory().resolve("validation"));
        Path directory = repository.headsDirectory();
        for (Path component : repository.headsDirectory().relativize(destination.getParent())) {
            if (component.toString().isEmpty()) continue;
            directory = directory.resolve(component);
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException existing) {
                /* Validate below. */
            }
            files.requireDirectory(directory);
        }
        files.validateTarget(destination);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("branch already exists: " + branch);
        return files.prepare(destination, (hash + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    public void updateRef(String ref, String hash) throws IOException {
        String branch = branchForRef(ref);
        try (var lock =
                MetadataLock.acquire(
                        files, repository.metadataDirectory().resolve("commit.lock"))) {
            if (Files.exists(path(branch), LinkOption.NOFOLLOW_LINKS)) {
                try (var update = prepare(branch, readBranch(branch), hash)) {
                    update.publish();
                }
            } else {
                try (var update = prepareNew(branch, hash)) {
                    update.publishNew();
                }
            }
        }
    }
}
