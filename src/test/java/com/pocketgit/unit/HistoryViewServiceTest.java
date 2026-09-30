package com.pocketgit.unit;

import com.pocketgit.model.Blob;
import com.pocketgit.model.Commit;
import com.pocketgit.model.FileMode;
import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.ObjectType;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.HistoryViewService;
import com.pocketgit.services.HistoryViewService.ChangeKind;
import com.pocketgit.services.TreeBuilder;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryViewServiceTest {
    @TempDir Path root;
    private Repository repository;
    private ObjectStore objects;
    private final HistoryViewService viewer = new HistoryViewService();

    @BeforeEach void initialize() throws Exception {
        repository = new RepositoryInitializer().initialize(root).repository();
        objects = new ObjectStore(repository);
    }

    private IndexEntry file(String path, String text, FileMode mode) throws Exception {
        return new IndexEntry(path, objects.writeBlob(new Blob(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))), mode);
    }

    private String commit(List<String> parents, List<IndexEntry> files, String message, long second) throws Exception {
        String tree = new TreeBuilder(objects).build(new Index(1, files));
        var commit = new Commit(tree, parents, "Jane 日本語", "jane@example.com", Instant.ofEpochSecond(second), message);
        return objects.write(ObjectType.COMMIT, new ObjectCodec().encodeCommit(commit));
    }

    private void branch(String name, String hash) throws Exception { new RefStore(repository).updateRef("refs/heads/" + name, hash); }

    private Map<String, byte[]> state() throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(path).toString(), Files.readAllBytes(path));
        }
        return result;
    }

    @Test void unbornRepositoryAndNestedDiscoveryHaveReadOnlyEmptyHistory() throws Exception {
        Path nested = Files.createDirectories(root.resolve("folder with spaces/日本語"));
        var before = state();
        var history = viewer.load(nested);
        assertEquals(root.toAbsolutePath(), history.repositoryRoot());
        assertEquals("main", history.currentBranch());
        assertNull(history.headHash());
        assertEquals(List.of(new HistoryViewService.Branch("main", null, true)), history.branches());
        assertTrue(history.commits().isEmpty());
        assertEquals(1, history.laneCount());
        assertUnchanged(before);
    }

    @Test void graphLoadsEveryBranchDeduplicatesAncestorsAndOrdersParentsAfterChildrenDespiteClockSkew() throws Exception {
        var files = List.of(file("readme", "base", FileMode.REGULAR_FILE));
        String base = commit(List.of(), files, "base", 200);
        String main = commit(List.of(base), files, "main", 100);
        String feature = commit(List.of(base), files, "feature", 300);
        branch("main", main);
        branch("feature/login", feature);
        branch("alias", main);
        Files.writeString(root.resolve("local-untracked"), "user work");
        var before = state();
        var history = viewer.load(root);
        assertEquals(List.of("alias", "feature/login", "main"), history.branches().stream().map(HistoryViewService.Branch::name).toList());
        assertEquals(List.of(feature, main, base), history.commits().stream().map(HistoryViewService.Node::hash).toList());
        var nodes = new HashMap<String, HistoryViewService.Node>();
        history.commits().forEach(node -> nodes.put(node.hash(), node));
        assertEquals(0, nodes.get(main).lane());
        assertEquals(0, nodes.get(base).lane());
        assertEquals(1, nodes.get(feature).lane());
        assertEquals(List.of("alias", "main"), nodes.get(main).labels());
        assertEquals(2, history.laneCount());
        assertEquals(main, history.headHash());
        assertUnchanged(before);
    }

    @Test void detachedHeadIsIncludedEvenWhenNotReachableFromAnyBranch() throws Exception {
        String base = commit(List.of(), List.of(), "base", 1);
        String detached = commit(List.of(base), List.of(), "detached", 2);
        branch("main", base);
        Files.writeString(repository.headFile(), detached + "\n");
        var history = viewer.load(root);
        assertNull(history.currentBranch());
        assertEquals(detached, history.headHash());
        assertEquals(List.of(detached, base), history.commits().stream().map(HistoryViewService.Node::hash).toList());
        assertEquals(List.of("HEAD"), history.commits().getFirst().labels());
        assertFalse(history.branches().getFirst().current());
    }

    @Test void commitDetailsReportAddedModifiedDeletedAndExecutableChangesInSortedOrder() throws Exception {
        var common = file("same", "unchanged", FileMode.REGULAR_FILE);
        var executable = file("run", "echo yes", FileMode.REGULAR_FILE);
        String base = commit(List.of(), List.of(common, executable, file("gone", "deleted", FileMode.REGULAR_FILE), file("edited", "old", FileMode.REGULAR_FILE)), "base", 1);
        String next = commit(List.of(base), List.of(common, new IndexEntry("run", executable.blobHash(), FileMode.EXECUTABLE_FILE), file("edited", "new", FileMode.REGULAR_FILE), file("added/日本語", "new", FileMode.REGULAR_FILE)), "summary\n\nbody", 2);
        branch("main", next);
        var before = state();
        var value = viewer.details(root, next.substring(0, 12));
        assertEquals(next, value.hash());
        assertEquals("summary\n\nbody", value.commit().message());
        assertEquals(List.of("added/日本語", "edited", "gone", "run"), value.changedFiles().stream().map(HistoryViewService.ChangedFile::path).toList());
        assertEquals(List.of(ChangeKind.ADDED, ChangeKind.MODIFIED, ChangeKind.DELETED, ChangeKind.MODIFIED), value.changedFiles().stream().map(HistoryViewService.ChangedFile::kind).toList());
        assertNull(value.changedFiles().getFirst().beforeMode());
        assertEquals(FileMode.REGULAR_FILE, value.changedFiles().get(3).beforeMode());
        assertEquals(FileMode.EXECUTABLE_FILE, value.changedFiles().get(3).afterMode());
        assertNull(value.changedFiles().get(2).afterMode());
        assertUnchanged(before);
    }

    @Test void rootCommitAddsAllFilesAndMergeDetailsCompareOnlyFirstParent() throws Exception {
        var file = file("hello", "hello", FileMode.REGULAR_FILE);
        String base = commit(List.of(), List.of(file), "base", 1);
        String other = commit(List.of(base), List.of(), "other", 2);
        String merge = commit(List.of(base, other), List.of(file), "merge", 3);
        branch("main", merge);
        var first = viewer.details(root, base);
        assertEquals(List.of(ChangeKind.ADDED), first.changedFiles().stream().map(HistoryViewService.ChangedFile::kind).toList());
        assertTrue(viewer.details(root, merge).changedFiles().isEmpty());
        var history = viewer.load(root);
        assertEquals(List.of(merge, other, base), history.commits().stream().map(HistoryViewService.Node::hash).toList());
    }

    @Test void corruptedHistoryAndMissingSnapshotsFailBeforeReturningMisleadingData() throws Exception {
        var entry = file("hello", "hello", FileMode.REGULAR_FILE);
        String base = commit(List.of(), List.of(entry), "base", 1);
        String next = commit(List.of(base), List.of(entry), "next", 2);
        branch("main", next);
        Files.delete(objects.pathForHash(entry.blobHash()));
        assertThrows(IOException.class, () -> viewer.details(root, next));
        Files.delete(objects.pathForHash(base));
        assertThrows(IOException.class, () -> viewer.load(root));
        assertThrows(IOException.class, () -> viewer.details(root, "abcdefabcdef"));
    }

    @Test void missingRepositoryAndMalformedHeadFailClearly() throws Exception {
        Path outside = Files.createTempDirectory("pocketgit-viewer-outside");
        try {
            assertEquals("not a PocketGit repository", assertThrows(IOException.class, () -> viewer.load(outside)).getMessage());
            assertThrows(IOException.class, () -> viewer.details(outside, "a".repeat(64)));
        } finally { Files.delete(outside); }
        Files.writeString(repository.headFile(), "bad head\n");
        assertThrows(IOException.class, () -> viewer.load(root));
    }

    private void assertUnchanged(Map<String, byte[]> before) throws Exception {
        var after = state();
        assertEquals(before.keySet(), after.keySet());
        before.forEach((path, bytes) -> assertArrayEquals(bytes, after.get(path), path));
    }
}
