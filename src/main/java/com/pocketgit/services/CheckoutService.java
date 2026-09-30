package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.refs.HeadManager;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class CheckoutService {
    public record Result(boolean changed, String branch) {}

    @FunctionalInterface
    public interface BeforePublish {
        void run() throws IOException;
    }

    private final Clock clock;
    private final BeforePublish beforePublish;

    public CheckoutService() {
        this(Clock.systemUTC(), () -> {});
    }

    /** Failure hook runs after file edits and each metadata publication step. */
    public CheckoutService(Clock clock, BeforePublish beforePublish) {
        this.clock = clock;
        this.beforePublish = beforePublish;
    }

    private Index snapshot(ObjectStore objects, String hash) throws IOException {
        return hash == null
                ? new Index(1, List.of())
                : new TreeReader(objects)
                        .readSnapshot(
                                new ObjectCodec()
                                        .decodeCommit(objects.readCommit(hash))
                                        .treeHash());
    }

    public Result checkout(Path cwd, String branch) throws IOException {
        var repository =
                new Repository(
                        new RepositoryLocator()
                                .findRepositoryRoot(cwd)
                                .orElseThrow(() -> new IOException("not a PocketGit repository")));
        var files = new MetadataFiles(repository);
        try (var indexUpdate = new IndexStore(repository).beginUpdate();
                var lock =
                        MetadataLock.acquire(
                                files, repository.metadataDirectory().resolve("commit.lock"))) {
            var heads = new HeadManager(repository);
            var refs = new RefStore(repository);
            var sourceHead = heads.read();
            String targetHash =
                    refs.readBranch(
                            branch); // Validate existence/name even for same-branch requests.
            if (branch.equals(sourceHead.branch())) return new Result(false, branch);
            String sourceHash = sourceHead.resolve(refs);
            var objects = new ObjectStore(repository);
            Index source = snapshot(objects, sourceHash),
                    target = snapshot(objects, targetHash),
                    index = indexUpdate.load();
            var planner = new CheckoutPlanner();
            var plan = planner.plan(repository, source, index, target);
            if (!plan.conflicts().isEmpty()) throw new CheckoutConflictException(plan.conflicts());
            try (var edit =
                    new WorkingTreeEdit(repository, plan.filesToWrite(), plan.filesToDelete())) {
                byte[] oldIndex = files.read(repository.indexFile(), IndexStore.MAX_INDEX_BYTES),
                        oldHead = files.read(repository.headFile(), 4096);
                Path logPath = repository.logsDirectory().resolve("HEAD");
                byte[] oldLog;
                try {
                    oldLog = files.read(logPath, 8 * 1024 * 1024);
                } catch (NoSuchFileException absent) {
                    oldLog = null;
                }
                boolean metadataStarted = false;
                boolean completed = false;
                try (var nextHead =
                                files.prepare(
                                        repository.headFile(),
                                        ("ref: refs/heads/" + branch + "\n")
                                                .getBytes(StandardCharsets.UTF_8));
                        var nextLog =
                                new ReflogStore(repository)
                                        .prepareAppend(
                                                sourceHash,
                                                targetHash,
                                                clock.instant(),
                                                "checkout")) {
                    if (!sourceHead.equals(heads.read())
                            || !Objects.equals(sourceHash, sourceHead.resolve(refs)))
                        throw new IOException("HEAD changed during checkout");
                    refs.requireUnchanged(branch, targetHash);
                    var recheck = planner.plan(repository, source, indexUpdate.load(), target);
                    if (!recheck.equals(plan))
                        throw new IOException(
                                "working tree changed during checkout; retry after inspecting"
                                        + " files");
                    try {
                        edit.apply();
                        beforePublish.run();
                        metadataStarted = true;
                        indexUpdate.save(target);
                        beforePublish.run();
                        nextHead.publish();
                        beforePublish.run();
                        nextLog.publish();
                        beforePublish.run();
                    } catch (IOException | RuntimeException failure) {
                        if (metadataStarted) {
                            // A blocked index recovery must not prevent independent HEAD/log
                            // recovery.
                            for (var backup :
                                    List.of(
                                            new Backup(repository.indexFile(), oldIndex),
                                            new Backup(repository.headFile(), oldHead),
                                            new Backup(logPath, oldLog))) {
                                try {
                                    restore(files, backup.path(), backup.bytes());
                                } catch (IOException rollback) {
                                    failure.addSuppressed(rollback);
                                }
                            }
                        }
                        try {
                            edit.rollback();
                        } catch (IOException rollback) {
                            failure.addSuppressed(rollback);
                        }
                        throw new IOException(
                                "checkout failed; rollback attempted: "
                                        + failure.getMessage()
                                        + (failure.getSuppressed().length == 0
                                                ? ""
                                                : "; rollback incomplete, inspect repository; "
                                                        + java.util.Arrays.stream(
                                                                        failure.getSuppressed())
                                                                .map(Throwable::getMessage)
                                                                .collect(
                                                                        java.util.stream.Collectors
                                                                                .joining("; "))),
                                failure);
                    }
                    // Cleanup errors must not roll files back after metadata has committed.
                    edit.complete();
                    completed = true;
                } catch (IOException failure) {
                    if (completed) {
                        throw new IOException(
                                "checkout completed; metadata temporary-file cleanup failed: "
                                        + failure.getMessage(),
                                failure);
                    }
                    throw failure;
                }
                return new Result(true, branch);
            }
        }
    }

    private record Backup(Path path, byte[] bytes) {}

    private void restore(MetadataFiles files, Path path, byte[] bytes) throws IOException {
        if (bytes == null) {
            files.validateTarget(path);
            Files.deleteIfExists(path);
        } else
            try (var prepared = files.prepare(path, bytes)) {
                prepared.publish();
            }
    }
}
