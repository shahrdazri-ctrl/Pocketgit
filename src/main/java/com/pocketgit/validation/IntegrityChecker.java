package com.pocketgit.validation;

import com.pocketgit.model.*;
import com.pocketgit.refs.*;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Read-only full object scan and reachable graph verification under cooperating writer locks. */
public final class IntegrityChecker {
    public record Report(List<String> errors, int objects, int commits, int trees, int blobs) {
        public Report {
            errors = List.copyOf(errors);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public Report verify(Path cwd) throws IOException {
        var repository =
                new Repository(
                        new RepositoryLocator()
                                .findRepositoryRoot(cwd)
                                .orElseThrow(() -> new IOException("not a PocketGit repository")));
        var files = new MetadataFiles(repository);
        try (var indexLock =
                        MetadataLock.acquire(
                                files, repository.metadataDirectory().resolve("index.lock"));
                var commitLock =
                        MetadataLock.acquire(
                                files, repository.metadataDirectory().resolve("commit.lock"))) {
            return inspect(repository, files);
        }
    }

    private Report inspect(Repository repository, MetadataFiles files) throws IOException {
        var errors = new TreeSet<String>();
        var roots = new TreeSet<String>();
        var types = new TreeMap<String, ObjectType>();
        var commits = new TreeMap<String, Commit>();
        var trees = new TreeMap<String, Tree>();
        var refs = new RefStore(repository);
        try {
            var head = new HeadManager(repository).read();
            String hash = head.resolve(refs);
            if (hash != null) roots.add(hash);
        } catch (IOException bad) {
            errors.add("HEAD: " + bad.getMessage());
        }
        try {
            files.validateTarget(repository.headsDirectory().resolve("validation"));
            try (var paths = Files.walk(repository.headsDirectory())) {
                for (Path path : paths.sorted().toList()) {
                    if (path.equals(repository.headsDirectory())) continue;
                    String branch =
                            repository
                                    .headsDirectory()
                                    .relativize(path)
                                    .toString()
                                    .replace(path.getFileSystem().getSeparator(), "/");
                    try {
                        RefNameValidator.validateBranch(branch);
                        var attributes =
                                Files.readAttributes(
                                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isSymbolicLink())
                            throw new IOException("symbolic ref metadata");
                        if (attributes.isDirectory()) continue;
                        String hash = refs.readBranch(branch);
                        if (hash != null) roots.add(hash);
                    } catch (IOException | IllegalArgumentException bad) {
                        errors.add("ref " + branch + ": " + bad.getMessage());
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException bad) {
            errors.add("refs: " + bad.getMessage());
        }
        ObjectStore store;
        try {
            store = new ObjectStore(repository);
        } catch (IOException bad) {
            errors.add("objects: " + bad.getMessage());
            return new Report(new ArrayList<>(errors), 0, 0, 0, 0);
        }
        var codec = new ObjectCodec();
        int blobs = 0;
        try (var directories = Files.newDirectoryStream(repository.objectsDirectory())) {
            for (Path directory : directories) {
                String prefix = directory.getFileName().toString();
                try {
                    if (!prefix.matches("[0-9a-f]{2}"))
                        throw new IOException("illegal object directory name");
                    files.requireDirectory(directory);
                    try (var paths = Files.newDirectoryStream(directory)) {
                        for (Path path : paths) {
                            String hash = prefix + path.getFileName();
                            try {
                                if (!hash.matches("[0-9a-f]{64}"))
                                    throw new IOException("illegal object filename");
                                var summary = store.verify(hash);
                                types.put(hash, summary.type());
                                switch (summary.type()) {
                                    case BLOB -> blobs++;
                                    case TREE ->
                                            trees.put(hash, codec.decodeTree(store.readTree(hash)));
                                    case COMMIT ->
                                            commits.put(
                                                    hash,
                                                    codec.decodeCommit(store.readCommit(hash)));
                                }
                            } catch (IOException bad) {
                                errors.add("object " + hash + ": " + bad.getMessage());
                            }
                        }
                    }
                } catch (IOException bad) {
                    errors.add("objects/" + prefix + ": " + bad.getMessage());
                }
            }
        } catch (IOException bad) {
            errors.add("objects: " + bad.getMessage());
        }
        for (var item : trees.entrySet())
            for (var entry : item.getValue().entries()) {
                if (types.get(entry.objectHash()) != entry.type())
                    errors.add(
                            "tree "
                                    + item.getKey()
                                    + ": missing, corrupt, or wrong-type child "
                                    + entry.objectHash());
            }
        for (var item : commits.entrySet()) {
            if (!trees.containsKey(item.getValue().treeHash()))
                errors.add(
                        "commit "
                                + item.getKey()
                                + ": missing or invalid tree "
                                + item.getValue().treeHash());
            for (String parent : item.getValue().parentHashes())
                if (!commits.containsKey(parent))
                    errors.add("commit " + item.getKey() + ": missing or invalid parent " + parent);
        }
        var snapshots = new HashSet<String>();
        var validRoots = new ArrayList<String>();
        for (String root : roots) {
            if (commits.containsKey(root)) validRoots.add(root);
            else errors.add("history " + root + ": missing or invalid commit");
        }
        try {
            var history =
                    new LogService()
                            .traverseRoots(
                                    validRoots,
                                    hash -> {
                                        var commit = commits.get(hash);
                                        if (commit == null)
                                            throw new IOException(
                                                    "missing or invalid commit " + hash);
                                        return commit;
                                    });
            for (var entry : history) {
                String treeHash = entry.commit().treeHash();
                if (!snapshots.add(treeHash)) continue;
                try {
                    new TreeReader(store).readSnapshot(treeHash);
                } catch (IOException bad) {
                    errors.add("snapshot " + treeHash + ": " + bad.getMessage());
                }
            }
        } catch (IOException bad) {
            errors.add("history: " + bad.getMessage());
        }
        try {
            var index = new IndexStore(repository).load();
            for (var entry : index.entries())
                if (types.get(entry.blobHash()) != ObjectType.BLOB)
                    errors.add(
                            "index "
                                    + entry.path()
                                    + ": missing, corrupt, or wrong-type blob "
                                    + entry.blobHash());
        } catch (IOException bad) {
            errors.add("index: " + bad.getMessage());
        }
        return new Report(
                new ArrayList<>(errors), types.size(), commits.size(), trees.size(), blobs);
    }
}
