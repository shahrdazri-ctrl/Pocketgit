package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.refs.HeadManager;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Verified flattened HEAD snapshot, retaining file modes as well as Blob IDs. */
public final class HeadSnapshotReader {
    public record HeadSnapshot(String branch, String commitHash, Index index) {}

    public HeadSnapshot read(Repository repository) throws IOException {
        String branch = new HeadManager(repository).readBranch();
        String hash = new RefStore(repository).readBranch(branch);
        if (hash == null) return new HeadSnapshot(branch, null, new Index(1, List.of()));
        var objects = new ObjectStore(repository);
        var commit = new ObjectCodec().decodeCommit(objects.read(hash));
        return new HeadSnapshot(branch, hash, new TreeReader(objects).readSnapshot(commit.treeHash()));
    }

    public void requireUnchanged(Repository repository, HeadSnapshot expected) throws IOException {
        if (!new HeadManager(repository).readBranch().equals(expected.branch())) {
            throw new IOException("HEAD changed during status; retry status");
        }
        if (!Objects.equals(new RefStore(repository).readBranch(expected.branch()), expected.commitHash())) {
            throw new IOException("branch changed during status; retry status");
        }
    }
}
