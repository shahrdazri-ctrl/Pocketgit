package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.*;
import com.pocketgit.refs.*;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryAndBranchesTest {
    @TempDir Path root;
    private Repository repo;
    private ObjectStore objects;
    private final ObjectCodec codec = new ObjectCodec();
    private final BranchService branches = new BranchService();
    private final LogService history = new LogService();

    @BeforeEach
    void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(root).repository();
        objects = new ObjectStore(repo);
    }

    private Commit commit(List<String> parents, String message) throws Exception {
        String tree = objects.write(ObjectType.TREE, codec.encodeTree(new Tree(List.of())));
        return new Commit(
                tree,
                parents,
                "Jane 日本語",
                "jane@example.com",
                Instant.parse("2026-01-02T03:04:05Z"),
                message);
    }

    private String save(List<String> parents, String message) throws Exception {
        return objects.write(ObjectType.COMMIT, codec.encodeCommit(commit(parents, message)));
    }

    private String attach() throws Exception {
        String hash = save(List.of(), "initial\n\nbody");
        new RefStore(repo).updateRef("refs/heads/main", hash);
        return hash;
    }

    private Map<String, byte[]> state() throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString(), Files.readAllBytes(path));
        }
        return result;
    }

    private void same(Map<String, byte[]> before) throws Exception {
        var after = state();
        assertEquals(before.keySet(), after.keySet());
        before.forEach((path, bytes) -> assertArrayEquals(bytes, after.get(path), path));
    }

    @Test
    void unbornMainCanBeListedButCannotCreateBranches() throws Exception {
        var before = state();
        assertEquals(new BranchService.Branches("main", List.of("main")), branches.list(root));
        assertTrue(history.log(root).isEmpty());
        assertTrue(
                assertThrows(IOException.class, () -> branches.create(root, "feature"))
                        .getMessage()
                        .contains("before first commit"));
        same(before);
    }

    @Test
    void branchesPointAtHeadAndLeaveWorkingFilesIndexHeadAndReflogUnchanged() throws Exception {
        String first = attach();
        Files.writeString(root.resolve("user-file"), "unstaged user work");
        byte[] index = Files.readAllBytes(repo.indexFile()),
                head = Files.readAllBytes(repo.headFile());
        assertEquals(first, branches.create(root, "feature/login"));
        branches.create(root, "alpha");
        assertEquals(List.of("alpha", "feature/login", "main"), branches.list(root).names());
        assertEquals("main", branches.list(root).current());
        assertEquals(first, new RefStore(repo).readBranch("feature/login"));
        String second = save(List.of(first), "second");
        new RefStore(repo).updateRef("refs/heads/main", second);
        assertEquals(first, new RefStore(repo).readBranch("feature/login"));
        assertArrayEquals(index, Files.readAllBytes(repo.indexFile()));
        assertArrayEquals(head, Files.readAllBytes(repo.headFile()));
        assertEquals("unstaged user work", Files.readString(root.resolve("user-file")));
        assertFalse(Files.exists(repo.logsDirectory().resolve("HEAD")));
        var before = state();
        branches.list(root);
        history.log(root);
        history.show(root, second.substring(0, 10));
        same(before);
    }

    @Test
    void duplicateAndDirectoryConflictsNeverReplaceBranches() throws Exception {
        String hash = attach();
        branches.create(root, "feature");
        var before = state();
        assertTrue(
                assertThrows(IOException.class, () -> branches.create(root, "feature"))
                        .getMessage()
                        .contains("already exists"));
        assertThrows(IOException.class, () -> branches.create(root, "feature/login"));
        same(before);
        branches.create(root, "nested/login");
        before = state();
        assertThrows(IOException.class, () -> branches.create(root, "nested"));
        same(before);
        assertEquals(hash, new RefStore(repo).readBranch("nested/login"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "HEAD",
                "../escape",
                "feature/../escape",
                "/absolute",
                "tail/",
                "a//b",
                "a\\b",
                "a\nb",
                "a\u0000b",
                ".hidden",
                "a.lock",
                "a.",
                "-option",
                "bad:name",
                "a@{b",
                "space name"
            })
    void invalidNamesAreRejectedWithoutChanges(String name) throws Exception {
        attach();
        var before = state();
        assertThrows(IOException.class, () -> branches.create(root, name));
        same(before);
    }

    @Test
    void branchCreationAndRefUpdatesRespectCommitLock() throws Exception {
        String first = attach(), second = save(List.of(first), "second");
        Files.writeString(repo.metadataDirectory().resolve("commit.lock"), "existing operation");
        var before = state();
        assertThrows(IOException.class, () -> branches.create(root, "feature"));
        assertThrows(
                IOException.class, () -> new RefStore(repo).updateRef("refs/heads/main", second));
        same(before);
    }

    @Test
    void refsRejectMissingObjectsNoncommitsAndMissingTreesBeforePublication() throws Exception {
        String first = attach();
        var refs = new RefStore(repo);
        assertEquals(first, refs.readRef("refs/heads/main").orElseThrow());
        assertTrue(refs.readRef("refs/heads/missing").isEmpty());
        assertThrows(IOException.class, () -> refs.readRef("refs/tags/test"));
        assertThrows(IOException.class, () -> refs.updateRef("refs/heads/main", "a".repeat(64)));
        String blob = objects.writeBlob(new Blob(new byte[] {1}));
        assertThrows(IOException.class, () -> refs.updateRef("refs/heads/main", blob));
        String broken =
                objects.write(
                        ObjectType.COMMIT,
                        codec.encodeCommit(
                                new Commit(
                                        "b".repeat(64),
                                        List.of(),
                                        "Jane",
                                        "jane@example.com",
                                        Instant.EPOCH,
                                        "broken")));
        assertThrows(IOException.class, () -> refs.updateRef("refs/heads/main", broken));
        assertEquals(first, refs.readBranch("main"));
        assertFalse(Files.exists(repo.metadataDirectory().resolve("commit.lock")));
        refs.updateRef("refs/heads/new/nested", first);
        assertEquals(first, refs.readBranch("new/nested"));
    }

    @Test
    void headUnderstandsDetachedHistoryAndBranchCreationWithoutSwitching() throws Exception {
        String hash = attach();
        var heads = new HeadManager(repo);
        assertEquals(new HeadManager.Head("main", null), heads.read());
        Files.writeString(repo.headFile(), hash + "\n");
        assertEquals(new HeadManager.Head(null, hash), heads.read());
        assertThrows(IOException.class, heads::readBranch);
        assertEquals(hash, history.log(root).getFirst().hash());
        assertNull(branches.list(root).current());
        branches.create(root, "detached-copy");
        assertEquals(hash, new RefStore(repo).readBranch("detached-copy"));
        assertEquals(hash + "\n", Files.readString(repo.headFile()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "ref: refs/heads/../bad\n",
                "ref: refs/tags/main\n",
                "abc\n",
                "ref: refs/heads/main\n\n"
            })
    void malformedHeadFailsCleanly(String value) throws Exception {
        Files.writeString(repo.headFile(), value);
        assertThrows(IOException.class, () -> new HeadManager(repo).read());
        assertThrows(IOException.class, () -> history.log(root));
        assertThrows(IOException.class, () -> branches.list(root));
    }

    @Test
    void historyIsNewestFirstAndShowAcceptsUniquePrefixes() throws Exception {
        String first = attach(),
                second = save(List.of(first), "second"),
                third = save(List.of(second), "third");
        new RefStore(repo).updateRef("refs/heads/main", third);
        assertEquals(
                List.of(third, second, first),
                history.log(root).stream().map(LogService.Entry::hash).toList());
        assertEquals(
                "initial\n\nbody", history.show(root, first.substring(0, 12)).commit().message());
        assertEquals(first, history.show(root, first).hash());
        String blob = objects.writeBlob(new Blob(new byte[] {3}));
        assertThrows(IOException.class, () -> history.show(root, blob));
    }

    @Test
    void missingMalformedAndWrongTypeParentsAreRejected() throws Exception {
        String first = attach(), second = save(List.of(first), "second");
        new RefStore(repo).updateRef("refs/heads/main", second);
        Files.delete(objects.pathForHash(first));
        assertTrue(
                assertThrows(IOException.class, () -> history.log(root))
                        .getMessage()
                        .contains(first));
        String malformed =
                objects.write(
                        ObjectType.COMMIT, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(repo.mainRefFile(), malformed + "\n");
        assertThrows(IOException.class, () -> history.log(root));
        String blob = objects.writeBlob(new Blob(new byte[] {7}));
        String wrong = save(List.of(blob), "wrong parent");
        Files.writeString(repo.mainRefFile(), wrong + "\n");
        assertThrows(IOException.class, () -> history.log(root));
    }

    @Test
    void traversalDetectsCyclesDeduplicatesSharedAncestorsAndAvoidsRecursion() throws Exception {
        String a = "a".repeat(64), b = "b".repeat(64), c = "c".repeat(64), d = "d".repeat(64);
        var cycle = Map.of(a, commit(List.of(b), "a"), b, commit(List.of(a), "b"));
        assertTrue(
                assertThrows(IOException.class, () -> history.traverse(a, cycle::get))
                        .getMessage()
                        .contains("cycle"));
        var dag =
                Map.of(
                        a,
                        commit(List.of(b, c), "merge"),
                        b,
                        commit(List.of(d), "left"),
                        c,
                        commit(List.of(d), "right"),
                        d,
                        commit(List.of(), "base"));
        assertEquals(
                List.of(a, b, d, c),
                history.traverse(a, dag::get).stream().map(LogService.Entry::hash).toList());
        Commit model = commit(List.of(), "chain");
        assertEquals(
                5000,
                history.traverse(
                                String.format("%064x", 5000),
                                id -> {
                                    int number = Integer.parseInt(id.substring(56), 16);
                                    return new Commit(
                                            model.treeHash(),
                                            number == 1
                                                    ? List.of()
                                                    : List.of(String.format("%064x", number - 1)),
                                            model.authorName(),
                                            model.authorEmail(),
                                            model.timestamp(),
                                            model.message());
                                })
                        .size());
    }

    @Test
    void branchMetadataSymlinksAreRejectedWithoutTouchingOutsideFiles() throws Exception {
        attach();
        Path outside = Files.createTempDirectory(root.getParent(), "outside-refs");
        try {
            Files.createSymbolicLink(repo.headsDirectory().resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException restricted) {
            assumeTrue(false, "symlinks unavailable");
        }
        assertThrows(IOException.class, () -> branches.create(root, "escape/new"));
        assertThrows(IOException.class, () -> branches.list(root));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void exclusivePreparedPublicationCannotOverwriteAnExistingRef() throws Exception {
        String first = attach();
        try (var prepared = new RefStore(repo).prepareNew("raced", first)) {
            Path destination = repo.headsDirectory().resolve("raced");
            Files.writeString(destination, "concurrent winner");
            assertThrows(IOException.class, prepared::publishNew);
            assertEquals("concurrent winner", Files.readString(destination));
        }
    }
}
