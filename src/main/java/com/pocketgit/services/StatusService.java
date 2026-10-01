package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.RepositoryStatus;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.repository.WorkingTree;
import com.pocketgit.storage.IndexStore;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

public final class StatusService {
    public RepositoryStatus status(Path cwd) throws IOException {
        var root =
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository"));
        var repository = new Repository(root);
        var heads = new HeadSnapshotReader();
        var head = heads.read(repository);
        var indexes = new IndexStore(repository);
        Index index = indexes.load();
        var objects = new ObjectStore(repository);
        var verified = new HashSet<String>();
        for (var entry : index.entries())
            if (verified.add(entry.blobHash())) objects.verifyBlob(entry.blobHash());
        var working = new WorkingTree().read(repository, index);
        // Observe complete atomic snapshots without creating locks or other metadata.
        if (!indexes.load().equals(index))
            throw new IOException("index changed during status; retry status");
        heads.requireUnchanged(repository, head);

        var committed = entries(head.index());
        var staged = entries(index);
        var files = entries(working.indexedFiles());
        var stagedNew = new ArrayList<String>();
        var stagedModified = new ArrayList<String>();
        var stagedDeleted = new ArrayList<String>();
        var unstagedModified = new ArrayList<String>();
        var unstagedDeleted = new ArrayList<String>();
        for (var entry : staged.entrySet()) {
            String path = entry.getKey();
            if (!committed.containsKey(path)) stagedNew.add(path);
            else if (!entry.getValue().equals(committed.get(path))) stagedModified.add(path);
            if (!files.containsKey(path)) unstagedDeleted.add(path);
            else if (!entry.getValue().equals(files.get(path))) unstagedModified.add(path);
        }
        for (String path : committed.keySet())
            if (!staged.containsKey(path)) stagedDeleted.add(path);
        return new RepositoryStatus(
                head.branch(),
                head.commitHash() != null,
                stagedNew,
                stagedModified,
                stagedDeleted,
                unstagedModified,
                unstagedDeleted,
                working.untracked(),
                working.ignored());
    }

    private Map<String, IndexEntry> entries(Index index) {
        var result = new TreeMap<String, IndexEntry>();
        index.entries().forEach(entry -> result.put(entry.path(), entry));
        return result;
    }
}
