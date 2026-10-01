package com.pocketgit.services;

import com.pocketgit.model.Commit;
import com.pocketgit.refs.HeadManager;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.util.HashUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Iterative, bounded parent traversal. Complete results are validated before CLI output. */
public final class LogService {
    public record Entry(String hash, Commit commit) {}

    @FunctionalInterface
    public interface CommitReader {
        Commit read(String hash) throws IOException;
    }

    private static final class Frame {
        final String hash;
        final Commit commit;
        int nextParent;

        Frame(String hash, Commit commit) {
            this.hash = hash;
            this.commit = commit;
        }
    }

    public static final int MAX_COMMITS = 100_000;

    private Repository locate(Path cwd) throws IOException {
        return new Repository(
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository")));
    }

    public List<Entry> log(Path cwd) throws IOException {
        var repository = locate(cwd);
        var heads = new HeadManager(repository);
        var head = heads.read();
        var refs = new RefStore(repository);
        String hash = head.resolve(refs);
        var objects = new ObjectStore(repository);
        var codec = new ObjectCodec();
        var entries = traverse(hash, id -> codec.decodeCommit(objects.readCommit(id)));
        if (!head.equals(heads.read()) || !Objects.equals(hash, head.resolve(refs))) {
            throw new IOException("HEAD changed during history traversal; retry");
        }
        return entries;
    }

    public Entry show(Path cwd, String prefix) throws IOException {
        var objects = new ObjectStore(locate(cwd));
        String hash = objects.resolve(prefix);
        return new Entry(hash, new ObjectCodec().decodeCommit(objects.readCommit(hash)));
    }

    /** Parents are visited in stored order, depth first; shared ancestors are emitted only once. */
    public List<Entry> traverse(String start, CommitReader reader) throws IOException {
        if (start == null) return List.of();
        return traverseRoots(List.of(start), reader);
    }

    /**
     * Validate a union of histories once, rather than walking every shared ancestor for every ref.
     */
    public List<Entry> traverseRoots(List<String> roots, CommitReader reader) throws IOException {
        var pending = new ArrayDeque<Frame>();
        var visited = new HashSet<String>();
        var active = new HashSet<String>();
        var result = new ArrayList<Entry>();
        for (String root : roots) {
            HashUtils.validateSha256(root);
            if (visited.contains(root)) continue;
            String next = root;
            while (next != null || !pending.isEmpty()) {
                if (next != null) {
                    if (active.contains(next))
                        throw new IOException("cycle in commit history at " + next);
                    if (visited.add(next)) {
                        if (visited.size() > MAX_COMMITS)
                            throw new IOException("commit history traversal limit exceeded");
                        Commit commit;
                        try {
                            commit = reader.read(next);
                        } catch (IOException invalid) {
                            throw new IOException(
                                    "cannot read history commit "
                                            + next
                                            + ": "
                                            + invalid.getMessage(),
                                    invalid);
                        }
                        result.add(new Entry(next, commit));
                        active.add(next);
                        pending.push(new Frame(next, commit));
                    }
                    next = null;
                }
                var frame = pending.peek();
                if (frame == null) break;
                if (frame.nextParent == frame.commit.parentHashes().size()) {
                    pending.pop();
                    active.remove(frame.hash);
                } else {
                    next = frame.commit.parentHashes().get(frame.nextParent++);
                }
            }
        }
        return List.copyOf(result);
    }
}
