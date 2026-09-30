package com.pocketgit.services;

import com.pocketgit.model.Commit;
import com.pocketgit.model.FileMode;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.refs.HeadManager;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeSet;

/** Read-only snapshots for the optional viewer, with no dependency on JavaFX. */
public final class HistoryViewService {
    public record Branch(String name, String hash, boolean current) {}

    public record Node(String hash, Commit commit, int lane, List<String> labels) {
        public Node {
            labels = List.copyOf(labels);
        }
    }

    public record History(
            Path repositoryRoot,
            String currentBranch,
            String headHash,
            List<Branch> branches,
            List<Node> commits,
            int laneCount) {
        public History {
            branches = List.copyOf(branches);
            commits = List.copyOf(commits);
        }
    }

    public enum ChangeKind {
        ADDED,
        MODIFIED,
        DELETED
    }

    public record ChangedFile(
            String path, ChangeKind kind, FileMode beforeMode, FileMode afterMode) {}

    public record Details(String hash, Commit commit, List<ChangedFile> changedFiles) {
        public Details {
            changedFiles = List.copyOf(changedFiles);
        }
    }

    private Repository locate(Path cwd) throws IOException {
        return new Repository(
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository")));
    }

    /** Includes every branch, shared ancestors once, and detached HEAD when present. */
    public History load(Path cwd) throws IOException {
        var repository = locate(cwd);
        var heads = new HeadManager(repository);
        var head = heads.read();
        var refs = new RefStore(repository);
        String headHash = head.resolve(refs);
        var names = refs.listBranches();
        var branches = new ArrayList<Branch>();
        var labels = new HashMap<String, List<String>>();
        for (String name : names) {
            String hash = refs.readBranch(name);
            branches.add(new Branch(name, hash, name.equals(head.branch())));
            if (hash != null) labels.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(name);
        }
        if (head.branch() == null)
            labels.computeIfAbsent(headHash, ignored -> new ArrayList<>()).add("HEAD");
        var roots = new ArrayList<String>();
        if (headHash != null) roots.add(headHash);
        for (var branch : branches)
            if (branch.hash() != null && !roots.contains(branch.hash())) roots.add(branch.hash());

        var objects = new ObjectStore(repository);
        var codec = new ObjectCodec();
        var commits = new LinkedHashMap<String, Commit>();
        var lanes = new HashMap<String, Integer>();
        var traversal = new LogService();
        var entries =
                traversal.traverseRoots(
                        roots, hash -> codec.decodeCommit(objects.readCommit(hash)));
        entries.forEach(entry -> commits.put(entry.hash(), entry.commit()));
        int lane = 0;
        for (String root : roots) {
            if (lanes.containsKey(root)) continue;
            var pending = new ArrayDeque<String>();
            pending.push(root);
            while (!pending.isEmpty()) {
                String hash = pending.pop();
                if (lanes.putIfAbsent(hash, lane) != null) continue;
                for (String parent : commits.get(hash).parentHashes()) {
                    if (!lanes.containsKey(parent)) pending.push(parent);
                }
            }
            lane++;
        }

        // Child-before-parent ordering remains correct even when author clocks move backwards.
        var children = new HashMap<String, Integer>();
        for (String hash : commits.keySet()) children.put(hash, 0);
        for (var commit : commits.values())
            for (String parent : commit.parentHashes()) children.merge(parent, 1, Integer::sum);
        var ready =
                new PriorityQueue<String>(
                        Comparator.<String, java.time.Instant>comparing(
                                        hash -> commits.get(hash).timestamp())
                                .reversed()
                                .thenComparing(Comparator.naturalOrder()));
        children.forEach(
                (hash, count) -> {
                    if (count == 0) ready.add(hash);
                });
        var nodes = new ArrayList<Node>();
        while (!ready.isEmpty()) {
            String hash = ready.remove();
            var commit = commits.get(hash);
            nodes.add(
                    new Node(hash, commit, lanes.get(hash), labels.getOrDefault(hash, List.of())));
            for (String parent : commit.parentHashes())
                if (children.merge(parent, -1, Integer::sum) == 0) ready.add(parent);
        }
        if (nodes.size() != commits.size()) throw new IOException("cycle in viewer commit history");
        if (!head.equals(heads.read())
                || !Objects.equals(headHash, head.resolve(refs))
                || !names.equals(refs.listBranches())) {
            throw new IOException("repository refs changed while loading viewer; refresh");
        }
        for (var branch : branches) refs.requireUnchanged(branch.name(), branch.hash());
        return new History(
                repository.root(), head.branch(), headHash, branches, nodes, Math.max(lane, 1));
    }

    /** Changes are relative to the first parent; a root commit adds its entire snapshot. */
    public Details details(Path cwd, String prefix) throws IOException {
        var objects = new ObjectStore(locate(cwd));
        var codec = new ObjectCodec();
        String hash = objects.resolve(prefix);
        var commit = codec.decodeCommit(objects.readCommit(hash));
        var trees = new TreeReader(objects);
        var after = entries(trees.readSnapshot(commit.treeHash()).entries());
        Map<String, IndexEntry> before = Map.of();
        if (!commit.parentHashes().isEmpty()) {
            var parent = codec.decodeCommit(objects.readCommit(commit.parentHashes().getFirst()));
            before = entries(trees.readSnapshot(parent.treeHash()).entries());
        }
        var paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        var changes = new ArrayList<ChangedFile>();
        for (String path : paths) {
            var old = before.get(path);
            var next = after.get(path);
            if (Objects.equals(old, next)) continue;
            var kind =
                    old == null
                            ? ChangeKind.ADDED
                            : next == null ? ChangeKind.DELETED : ChangeKind.MODIFIED;
            changes.add(
                    new ChangedFile(
                            path,
                            kind,
                            old == null ? null : old.mode(),
                            next == null ? null : next.mode()));
        }
        return new Details(hash, commit, changes);
    }

    private Map<String, IndexEntry> entries(List<IndexEntry> entries) {
        var result = new HashMap<String, IndexEntry>();
        for (var entry : entries) result.put(entry.path(), entry);
        return result;
    }
}
