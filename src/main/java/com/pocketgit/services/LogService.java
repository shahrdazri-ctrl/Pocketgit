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
    @FunctionalInterface public interface CommitReader { Commit read(String hash) throws IOException; }
    private record Visit(String hash, boolean leaving) {}
    public static final int MAX_COMMITS = 100_000;

    private Repository locate(Path cwd) throws IOException {
        return new Repository(new RepositoryLocator().findRepositoryRoot(cwd)
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
        var entries = traverse(hash, id -> codec.decodeCommit(objects.read(id)));
        if (!head.equals(heads.read()) || !Objects.equals(hash, head.resolve(refs))) {
            throw new IOException("HEAD changed during history traversal; retry");
        }
        return entries;
    }
    public Entry show(Path cwd, String prefix) throws IOException {
        var objects = new ObjectStore(locate(cwd));
        String hash = objects.resolve(prefix);
        return new Entry(hash, new ObjectCodec().decodeCommit(objects.read(hash)));
    }
    /** Parents are visited in stored order, depth first; shared ancestors are emitted only once. */
    public List<Entry> traverse(String start, CommitReader reader) throws IOException {
        if (start == null) return List.of();
        HashUtils.validateSha256(start);
        var pending = new ArrayDeque<Visit>();
        var visited = new HashSet<String>();
        var active = new HashSet<String>();
        var result = new ArrayList<Entry>();
        pending.push(new Visit(start, false));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (visit.leaving()) { active.remove(visit.hash()); continue; }
            if (active.contains(visit.hash())) throw new IOException("cycle in commit history at " + visit.hash());
            if (!visited.add(visit.hash())) continue;
            if (visited.size() > MAX_COMMITS) throw new IOException("commit history traversal limit exceeded");
            Commit commit;
            try { commit = reader.read(visit.hash()); }
            catch (IOException invalid) { throw new IOException("cannot read history commit " + visit.hash() + ": " + invalid.getMessage(), invalid); }
            result.add(new Entry(visit.hash(), commit));
            active.add(visit.hash());
            pending.push(new Visit(visit.hash(), true));
            var parents = commit.parentHashes();
            for (int i = parents.size() - 1; i >= 0; i--) pending.push(new Visit(parents.get(i), false));
        }
        return List.copyOf(result);
    }
}
