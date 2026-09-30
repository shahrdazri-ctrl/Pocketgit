package com.pocketgit.services;

import com.pocketgit.model.Commit;
import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.refs.HeadManager;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.ConfigStore;
import com.pocketgit.storage.IndexStore;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.storage.MetadataLock;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.storage.ReflogStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

public final class CommitService {
    public record CommitResult(boolean created, String branch, String commitHash, int changedFiles) {}
    private final Clock clock;
    private final Function<String, String> environment;
    public CommitService() { this(Clock.systemUTC(), System::getenv); }
    public CommitService(Clock clock, Function<String, String> environment) { this.clock = clock; this.environment = environment; }

    public CommitResult commit(Path cwd, String message, boolean allowEmpty) throws IOException {
        if (message == null || message.isBlank() || message.indexOf('\0') >= 0) throw new IOException("commit message must be nonblank and contain no NUL");
        var root = new RepositoryLocator().findRepositoryRoot(cwd)
                .orElseThrow(() -> new IOException("not a PocketGit repository"));
        Repository repository = new Repository(root);
        var files = new MetadataFiles(repository);
        String published = null;
        try (var indexLock = new IndexStore(repository).beginUpdate();
                var commitLock = MetadataLock.acquire(files, repository.metadataDirectory().resolve("commit.lock"))) {
            Index index = indexLock.load();
            var heads = new HeadManager(repository);
            String branch = heads.readBranch();
            var refs = new RefStore(repository);
            String parentHash = refs.readBranch(branch);
            var objects = new ObjectStore(repository);
            var codec = new ObjectCodec();
            Commit parent = parentHash == null ? null : codec.decodeCommit(objects.read(parentHash));
            Index before = parent == null ? new Index(1, List.of()) : new TreeReader(objects).readSnapshot(parent.treeHash());
            if (!allowEmpty && index.equals(before)) return new CommitResult(false, branch, null, 0);
            var author = new ConfigStore(repository).resolveAuthor(environment);
            String rootTree = new TreeBuilder(objects).build(index);
            // Compare the serialized snapshot as well as the flattened index.
            if (!allowEmpty && parent != null && parent.treeHash().equals(rootTree)) return new CommitResult(false, branch, null, 0);
            var commit = new Commit(rootTree, parentHash == null ? List.of() : List.of(parentHash),
                    author.name(), author.email(), clock.instant(), message);
            String hash = objects.write(ObjectType.COMMIT, codec.encodeCommit(commit));
            try (var nextRef = refs.prepare(branch, parentHash, hash);
                    var nextLog = new ReflogStore(repository).prepareAppend(parentHash, hash, commit.timestamp())) {
                if (!heads.readBranch().equals(branch)) throw new IOException("HEAD changed during commit; inspect repository before retrying");
                refs.requireUnchanged(branch, parentHash);
                nextRef.publish();
                published = hash;
                nextLog.publish();
            }
            return new CommitResult(true, branch, hash, changedFiles(before, index));
        } catch (IOException failure) {
            if (published != null) throw new PublishedCommitException(published, failure);
            throw failure;
        }
    }

    private int changedFiles(Index before, Index after) {
        var oldEntries = new HashMap<String, IndexEntry>();
        var newEntries = new HashMap<String, IndexEntry>();
        before.entries().forEach(entry -> oldEntries.put(entry.path(), entry));
        after.entries().forEach(entry -> newEntries.put(entry.path(), entry));
        var paths = new HashSet<>(oldEntries.keySet());
        paths.addAll(newEntries.keySet());
        return (int) paths.stream().filter(path -> !java.util.Objects.equals(oldEntries.get(path), newEntries.get(path))).count();
    }
}
