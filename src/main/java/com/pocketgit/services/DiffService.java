package com.pocketgit.services;

import com.pocketgit.diff.*;
import com.pocketgit.model.*;
import com.pocketgit.repository.*;
import com.pocketgit.storage.*;
import com.pocketgit.util.PathUtils;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class DiffService {
    public List<DiffResult> diff(Path cwd, boolean staged) throws IOException {
        var repository =
                new Repository(
                        new RepositoryLocator()
                                .findRepositoryRoot(cwd)
                                .orElseThrow(() -> new IOException("not a PocketGit repository")));
        var index = new IndexStore(repository).load();
        var objects = new ObjectStore(repository);
        var headReader = new HeadSnapshotReader();
        var head = staged ? headReader.read(repository) : null;
        var before = CheckoutPlanner.entries(staged ? head.index() : index);
        var after =
                CheckoutPlanner.entries(
                        staged ? index : new WorkingTree().read(repository, index).indexedFiles());
        var paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        var results = new ArrayList<DiffResult>();
        var engine = new DiffEngine();
        for (String path : paths) {
            var old = before.get(path);
            var next = after.get(path);
            if (Objects.equals(old, next)) continue;
            byte[] a = old == null ? null : objects.readBlob(old.blobHash()).content(), b = null;
            if (next != null) {
                if (staged) b = objects.readBlob(next.blobHash()).content();
                else {
                    var file =
                            PathUtils.safeWorkingPath(
                                    repository.root(), repository.root().resolve(path));
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        b = input.readNBytes(ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES + 1);
                    }
                    if (b.length > ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES)
                        throw new IOException("working file exceeds diff limit: " + path);
                    if (!new ObjectHasher().hash(ObjectType.BLOB, b).equals(next.blobHash()))
                        throw new IOException("working file changed during diff: " + path);
                }
            }
            results.add(
                    engine.diff(
                            path,
                            a,
                            b,
                            old == null ? null : old.mode(),
                            next == null ? null : next.mode()));
        }
        if (!index.equals(new IndexStore(repository).load()))
            throw new IOException("index changed during diff; retry");
        if (staged) headReader.requireUnchanged(repository, head);
        return List.copyOf(results);
    }
}
