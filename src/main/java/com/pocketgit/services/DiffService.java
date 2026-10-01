package com.pocketgit.services;

import com.pocketgit.diff.*;
import com.pocketgit.model.*;
import com.pocketgit.repository.*;
import com.pocketgit.storage.*;
import com.pocketgit.util.FileModeUtils;
import com.pocketgit.util.PathUtils;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Validated comparisons with streaming binary detection and bounded retained text results. */
public final class DiffService {
    public static final long MAX_TOTAL_TEXT_BYTES = 32L * 1024 * 1024;
    public static final long MAX_TOTAL_LINES = 250_000;

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
        long retainedBytes = 0, retainedLines = 0;
        for (String path : paths) {
            var old = before.get(path);
            var next = after.get(path);
            if (Objects.equals(old, next)) continue;
            if (old != null && next != null && old.blobHash().equals(next.blobHash())) {
                objects.verifyBlob(old.blobHash());
                results.add(
                        new DiffResult(
                                path, false, true, true, old.mode(), next.mode(), List.of()));
                continue;
            }
            var oldContent = new TextProbe();
            var newContent = new TextProbe();
            if (old != null) objects.copyBlob(old.blobHash(), oldContent);
            if (next != null) {
                if (staged) objects.copyBlob(next.blobHash(), newContent);
                else {
                    var file =
                            PathUtils.safeWorkingPath(
                                    repository.root(), repository.root().resolve(path));
                    long size = Files.size(file);
                    if (size > ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES)
                        throw new IOException("working file exceeds diff limit: " + path);
                    try (var input =
                            new FilterInputStream(
                                    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                @Override
                                public int read(byte[] bytes, int offset, int length)
                                        throws IOException {
                                    int count = in.read(bytes, offset, length);
                                    if (count > 0) newContent.write(bytes, offset, count);
                                    return count;
                                }
                            }) {
                        if (!new ObjectHasher()
                                .hash(ObjectType.BLOB, size, input)
                                .equals(next.blobHash())) {
                            throw new IOException("working file changed during diff: " + path);
                        }
                    }
                    if (FileModeUtils.fileMode(file) != next.mode())
                        throw new IOException("working file mode changed during diff: " + path);
                }
            }
            DiffResult result;
            if (oldContent.binary() || newContent.binary()) {
                result =
                        new DiffResult(
                                path,
                                true,
                                old != null,
                                next != null,
                                old == null ? null : old.mode(),
                                next == null ? null : next.mode(),
                                List.of());
            } else {
                byte[] a = old == null ? null : oldContent.text(path);
                byte[] b = next == null ? null : newContent.text(path);
                retainedBytes += (a == null ? 0 : a.length) + (long) (b == null ? 0 : b.length);
                if (retainedBytes > MAX_TOTAL_TEXT_BYTES)
                    throw new IOException("diff exceeds 32 MiB cumulative text limit");
                result =
                        engine.diff(
                                path,
                                a,
                                b,
                                old == null ? null : old.mode(),
                                next == null ? null : next.mode());
                for (var hunk : result.hunks()) retainedLines += hunk.lines().size();
                if (retainedLines > MAX_TOTAL_LINES)
                    throw new IOException("diff exceeds 250,000 cumulative output lines");
            }
            results.add(result);
        }
        if (!index.equals(new IndexStore(repository).load()))
            throw new IOException("index changed during diff; retry");
        if (staged) headReader.requireUnchanged(repository, head);
        return List.copyOf(results);
    }
}
