package com.pocketgit.refs;

import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.storage.MetadataLock;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/** Branch operations never change HEAD, index, working files, or reflogs. */
public final class BranchService {
    public record Branches(String current, List<String> names) {
        public Branches {
            names = List.copyOf(names);
        }
    }

    private Repository locate(Path cwd) throws IOException {
        return new Repository(
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository")));
    }

    public Branches list(Path cwd) throws IOException {
        var repository = locate(cwd);
        var heads = new HeadManager(repository);
        var head = heads.read();
        var refs = new RefStore(repository);
        head.resolve(
                refs); // A symbolic HEAD must reference an existing branch, including unborn main.
        var names = refs.listBranches();
        if (!head.equals(heads.read()))
            throw new IOException("HEAD changed during branch listing; retry");
        String current = head.branch();
        if (current != null) {
            String normalized = Normalizer.normalize(current, Normalizer.Form.NFC);
            current =
                    names.stream()
                            .filter(
                                    name ->
                                            Normalizer.normalize(name, Normalizer.Form.NFC)
                                                    .equals(normalized))
                            .findFirst()
                            .orElseThrow(
                                    () -> new IOException("HEAD branch missing from namespace"));
        }
        return new Branches(current, names);
    }

    public String create(Path cwd, String name) throws IOException {
        var repository = locate(cwd);
        var files = new MetadataFiles(repository);
        try (var lock =
                MetadataLock.acquire(
                        files, repository.metadataDirectory().resolve("commit.lock"))) {
            var heads = new HeadManager(repository);
            var head = heads.read();
            var refs = new RefStore(repository);
            String hash = head.resolve(refs);
            if (hash == null) throw new IOException("Cannot create branch before first commit.");
            try (var update = refs.prepareNew(name, hash)) {
                if (!head.equals(heads.read()) || !Objects.equals(hash, head.resolve(refs))) {
                    throw new IOException("HEAD changed during branch creation; retry");
                }
                update.publishNew();
            }
            return hash;
        }
    }
}
