package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.*;
import com.pocketgit.refs.*;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.*;
import com.pocketgit.validation.IntegrityChecker;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntegrityCheckerTest {
    @TempDir Path root;
    private Repository repo;
    private final IntegrityChecker checker = new IntegrityChecker();

    @BeforeEach
    void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(root).repository();
        new ConfigStore(repo).set("user.name", "Reviewer");
        new ConfigStore(repo).set("user.email", "reviewer@example.com");
    }

    private String commit() throws Exception {
        Files.writeString(root.resolve("a"), "hello");
        new AddService().add(root, Path.of("."));
        return new CommitService().commit(root, "initial", false).commitHash();
    }

    private Map<String, byte[]> state() throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (var p : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(p).toString(), Files.readAllBytes(p));
        }
        return result;
    }

    private void same(Map<String, byte[]> before) throws Exception {
        var after = state();
        assertEquals(before.keySet(), after.keySet());
        before.forEach((p, b) -> assertArrayEquals(b, after.get(p), p));
    }

    @Test
    void unbornAndCommittedRepositoriesVerifyWithoutPersistentChanges() throws Exception {
        var before = state();
        assertTrue(checker.verify(root).valid());
        same(before);
        commit();
        new BranchService().create(root, "feature/nested");
        before = state();
        var report = checker.verify(root);
        assertTrue(report.valid(), report.errors().toString());
        assertEquals(1, report.commits());
        assertEquals(1, report.trees());
        assertEquals(1, report.blobs());
        same(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "blob",
                "tree",
                "commit",
                "index",
                "head",
                "ref",
                "renamed",
                "filename",
                "parent"
            })
    void injectedCorruptionIsReportedWithoutRepair(String kind) throws Exception {
        String hash = commit();
        var objects = new ObjectStore(repo);
        var model = new ObjectCodec().decodeCommit(objects.read(hash));
        var entry = new IndexStore(repo).load().entries().getFirst();
        switch (kind) {
            case "blob" -> Files.delete(objects.pathForHash(entry.blobHash()));
            case "tree" -> Files.delete(objects.pathForHash(model.treeHash()));
            case "commit" -> Files.write(objects.pathForHash(hash), new byte[] {1, 2, 3});
            case "index" -> Files.writeString(repo.indexFile(), "broken");
            case "head" -> Files.writeString(repo.headFile(), "ref: refs/heads/../escape\n");
            case "ref" -> Files.writeString(repo.mainRefFile(), "invalid\n");
            case "renamed" -> {
                Path wrong = objects.pathForHash("f".repeat(64));
                Files.createDirectories(wrong.getParent());
                Files.copy(objects.pathForHash(entry.blobHash()), wrong);
            }
            case "filename" ->
                    Files.writeString(repo.objectsDirectory().resolve("invalid"), "private");
            case "parent" -> {
                String bad =
                        objects.write(
                                ObjectType.COMMIT,
                                new ObjectCodec()
                                        .encodeCommit(
                                                new Commit(
                                                        model.treeHash(),
                                                        List.of("a".repeat(64)),
                                                        model.authorName(),
                                                        model.authorEmail(),
                                                        model.timestamp(),
                                                        "missing parent")));
                Files.writeString(repo.mainRefFile(), bad + "\n");
            }
        }
        var before = state();
        var report = checker.verify(root);
        assertFalse(report.valid());
        assertFalse(report.errors().isEmpty());
        same(before);
    }

    @Test
    void unreachableCorruptObjectsAreDetected() throws Exception {
        commit();
        var objects = new ObjectStore(repo);
        String orphan = objects.writeBlob(new Blob(new byte[] {9}));
        Files.writeString(objects.pathForHash(orphan), "corrupt");
        assertTrue(checker.verify(root).errors().stream().anyMatch(e -> e.contains(orphan)));
    }

    @Test
    void everyBranchAndDetachedHeadAreChecked() throws Exception {
        String hash = commit();
        new BranchService().create(root, "feature");
        Files.writeString(repo.headsDirectory().resolve("feature"), "a".repeat(64) + "\n");
        assertFalse(checker.verify(root).valid());
        Files.delete(repo.headsDirectory().resolve("feature"));
        Files.writeString(repo.headFile(), hash + "\n");
        assertTrue(checker.verify(root).valid());
        Files.writeString(repo.headFile(), "b".repeat(64) + "\n");
        assertFalse(checker.verify(root).valid());
    }

    @Test
    void wrongTypeIndexAndUnbornRefAreHandledExplicitly() throws Exception {
        String hash = commit();
        var entry = new IndexStore(repo).load().entries().getFirst();
        new IndexStore(repo)
                .save(new Index(1, List.of(new IndexEntry(entry.path(), hash, entry.mode()))));
        assertTrue(checker.verify(root).errors().stream().anyMatch(e -> e.startsWith("index a:")));
    }

    @Test
    void locksAreRespectedAndPreviouslyHeldLocksRemain() throws Exception {
        commit();
        Path lock = repo.metadataDirectory().resolve("commit.lock");
        Files.writeString(lock, "held");
        var before = state();
        assertThrows(IOException.class, () -> checker.verify(root));
        same(before);
        assertFalse(Files.exists(repo.metadataDirectory().resolve("index.lock")));
    }

    @Test
    void symlinkedObjectMetadataIsRejectedWithoutOutsideReads() throws Exception {
        commit();
        Path outside = Files.createTempDirectory(root.getParent(), "verify-outside");
        try {
            Files.createSymbolicLink(repo.objectsDirectory().resolve("ff"), outside);
        } catch (IOException | UnsupportedOperationException denied) {
            assumeTrue(false, "symlinks unavailable");
        }
        assertFalse(checker.verify(root).valid());
        try (var paths = Files.list(outside)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void unwritableMetadataFailsClearly() throws Exception {
        commit();
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        var permissions = Files.getPosixFilePermissions(repo.metadataDirectory());
        try {
            Files.setPosixFilePermissions(
                    repo.metadataDirectory(), PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeTrue(
                    !Files.isWritable(repo.metadataDirectory()),
                    "privileged process bypasses permissions");
            assertThrows(IOException.class, () -> checker.verify(root));
        } finally {
            Files.setPosixFilePermissions(repo.metadataDirectory(), permissions);
        }
    }
}
