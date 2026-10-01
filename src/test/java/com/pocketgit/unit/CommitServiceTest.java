package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.*;
import com.pocketgit.refs.*;
import com.pocketgit.repository.*;
import com.pocketgit.services.*;
import com.pocketgit.storage.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommitServiceTest {
    @TempDir Path root;
    private Repository repo;
    private ObjectStore objects;
    private final ObjectCodec codec = new ObjectCodec();
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private final CommitService commits =
            new CommitService(Clock.fixed(NOW, ZoneOffset.UTC), key -> null);

    @BeforeEach
    void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(root).repository();
        objects = new ObjectStore(repo);
    }

    private void identity() throws Exception {
        var config = new ConfigStore(repo);
        config.set("user.name", "Jane 日本語");
        config.set("user.email", "jane@example.com");
    }

    private void stage(String name, String value) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
        new AddService().add(root, Path.of(name));
    }

    private String head() throws IOException {
        return new RefStore(repo).readBranch("main");
    }

    private Commit read(String hash) throws IOException {
        return codec.decodeCommit(objects.read(hash));
    }

    private void clean() throws Exception {
        try (var paths = Files.walk(repo.metadataDirectory())) {
            assertFalse(
                    paths.anyMatch(
                            p ->
                                    p.toString().endsWith(".lock")
                                            || p.getFileName()
                                                    .toString()
                                                    .startsWith(".metadata-")));
        }
    }

    @Test
    void firstAndSecondCommitsStoreIndexSnapshotsAndParentHistory() throws Exception {
        identity();
        stage("src/main/a", "staged");
        stage("README", "hello");
        var index = new IndexStore(repo).load();
        byte[] indexBytes = Files.readAllBytes(repo.indexFile());
        Files.writeString(root.resolve("src/main/a"), "unstaged modification");
        Files.writeString(root.resolve("untracked"), "never staged");
        var first = commits.commit(root.resolve("src"), "First\n\nbody", false);
        assertTrue(first.created());
        assertEquals(2, first.changedFiles());
        assertEquals(first.commitHash(), head());
        Commit saved = read(head());
        assertEquals(List.of(), saved.parentHashes());
        assertEquals("Jane 日本語", saved.authorName());
        assertEquals("jane@example.com", saved.authorEmail());
        assertEquals(NOW, saved.timestamp());
        assertEquals("First\n\nbody", saved.message());
        assertEquals(index, new TreeReader(objects).readSnapshot(saved.treeHash()));
        assertArrayEquals(indexBytes, Files.readAllBytes(repo.indexFile()));
        stage("src/main/a", "second staged");
        Files.delete(root.resolve("README"));
        new AddService().add(root, Path.of("README"));
        var second = commits.commit(root, "Second", false);
        assertEquals(2, second.changedFiles());
        assertEquals(second.commitHash(), head());
        assertEquals(List.of(first.commitHash()), read(head()).parentHashes());
        assertEquals(
                new IndexStore(repo).load(),
                new TreeReader(objects).readSnapshot(read(head()).treeHash()));
        assertEquals(saved, read(first.commitHash()));
        assertEquals(
                List.of(
                        "0".repeat(64) + " " + first.commitHash() + " " + NOW + " commit",
                        first.commitHash() + " " + second.commitHash() + " " + NOW + " commit"),
                Files.readAllLines(repo.logsDirectory().resolve("HEAD")));
        clean();
    }

    @Test
    void unchangedOrInitialEmptyIndexDoesNotCreateCommitOrMoveRef() throws Exception {
        assertFalse(commits.commit(root, "empty", false).created());
        assertNull(head());
        assertFalse(Files.exists(repo.logsDirectory().resolve("HEAD")));
        identity();
        stage("a", "hello");
        String first = commits.commit(root, "first", false).commitHash();
        byte[] log = Files.readAllBytes(repo.logsDirectory().resolve("HEAD"));
        Files.writeString(root.resolve("a"), "working tree changed only");
        Files.delete(repo.configFile());
        assertFalse(commits.commit(root, "unchanged", false).created());
        assertEquals(first, head());
        assertArrayEquals(log, Files.readAllBytes(repo.logsDirectory().resolve("HEAD")));
        clean();
    }

    @Test
    void allowEmptyCreatesInitialAndSubsequentCommitsAndDeletionCanEmptySnapshot()
            throws Exception {
        identity();
        var first = commits.commit(root, "empty root", true);
        assertTrue(first.created());
        assertEquals(0, first.changedFiles());
        assertEquals(List.of(), read(head()).parentHashes());
        var second = commits.commit(root, "empty child", true);
        assertEquals(List.of(first.commitHash()), read(second.commitHash()).parentHashes());
        assertEquals(read(first.commitHash()).treeHash(), read(second.commitHash()).treeHash());
        stage("a", "hello");
        commits.commit(root, "add", false);
        Files.delete(root.resolve("a"));
        new AddService().add(root, Path.of("a"));
        var deletion = commits.commit(root, "delete last file", false);
        assertEquals(1, deletion.changedFiles());
        assertEquals(read(first.commitHash()).treeHash(), read(deletion.commitHash()).treeHash());
        clean();
    }

    @Test
    void modeOnlyChangeCreatesDifferentTree() throws Exception {
        identity();
        stage("run", "echo hi");
        var first = commits.commit(root, "regular", false);
        var entry = new IndexStore(repo).load().entries().getFirst();
        new IndexStore(repo)
                .save(
                        new Index(
                                1,
                                List.of(
                                        new IndexEntry(
                                                entry.path(),
                                                entry.blobHash(),
                                                FileMode.EXECUTABLE_FILE))));
        var second = commits.commit(root, "executable", false);
        assertTrue(second.created());
        assertEquals(1, second.changedFiles());
        assertNotEquals(read(first.commitHash()).treeHash(), read(second.commitHash()).treeHash());
    }

    @Test
    void missingAuthorFailsWithoutPublishingAndEnvironmentCanProvideIdentity() throws Exception {
        stage("a", "hello");
        assertTrue(
                assertThrows(IOException.class, () -> commits.commit(root, "first", false))
                        .getMessage()
                        .contains("config user.name"));
        assertNull(head());
        assertFalse(Files.exists(repo.logsDirectory().resolve("HEAD")));
        clean();
        var env =
                new CommitService(
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        key ->
                                key.equals("POCKETGIT_AUTHOR_NAME")
                                        ? "Env User"
                                        : "env@example.com");
        assertEquals(
                "Env User", read(env.commit(root, "environment", false).commitHash()).authorName());
    }

    @Test
    void corruptIndexedBlobFailsBeforePublishing() throws Exception {
        identity();
        stage("a", "hello");
        String blob = new IndexStore(repo).load().entries().getFirst().blobHash();
        Files.write(objects.pathForHash(blob), new byte[] {0});
        assertThrows(IOException.class, () -> commits.commit(root, "first", false));
        assertNull(head());
        assertFalse(Files.exists(repo.logsDirectory().resolve("HEAD")));
        clean();
    }

    @Test
    void corruptParentOrSnapshotCannotBeExtendedEvenWhenIndexUnchanged() throws Exception {
        identity();
        stage("a", "hello");
        String hash = commits.commit(root, "first", false).commitHash();
        byte[] log = Files.readAllBytes(repo.logsDirectory().resolve("HEAD"));
        String tree = read(hash).treeHash();
        byte[] original = Files.readAllBytes(objects.pathForHash(tree));
        Files.write(objects.pathForHash(tree), new byte[] {0});
        assertThrows(IOException.class, () -> commits.commit(root, "unchanged", false));
        Files.write(objects.pathForHash(tree), original);
        Files.write(objects.pathForHash(hash), new byte[] {0});
        assertThrows(IOException.class, () -> commits.commit(root, "child", true));
        assertEquals(hash, head());
        assertArrayEquals(log, Files.readAllBytes(repo.logsDirectory().resolve("HEAD")));
        clean();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "truncated log",
                "directory log",
                "missing logs",
                "index lock",
                "commit lock",
                "invalid config",
                "invalid index",
                "invalid HEAD",
                "invalid ref",
                "missing ref"
            })
    void metadataFailureLeavesRefAndIndexUntouched(String failure) throws Exception {
        identity();
        stage("a", "hello");
        Path log = repo.logsDirectory().resolve("HEAD");
        switch (failure) {
            case "truncated log" -> Files.writeString(log, "truncated");
            case "directory log" -> Files.createDirectory(log);
            case "missing logs" -> Files.delete(repo.logsDirectory());
            case "index lock" ->
                    Files.writeString(repo.metadataDirectory().resolve("index.lock"), "busy");
            case "commit lock" ->
                    Files.writeString(repo.metadataDirectory().resolve("commit.lock"), "busy");
            case "invalid config" -> Files.writeString(repo.configFile(), "broken");
            case "invalid index" -> Files.writeString(repo.indexFile(), "broken");
            case "invalid HEAD" ->
                    Files.writeString(repo.headFile(), "ref: refs/heads/../../outside\n");
            case "invalid ref" ->
                    Files.writeString(repo.headsDirectory().resolve("main"), "invalid\n");
            case "missing ref" -> Files.delete(repo.headsDirectory().resolve("main"));
        }
        Path ref = repo.headsDirectory().resolve("main");
        byte[] before = Files.exists(ref) ? Files.readAllBytes(ref) : null;
        byte[] index = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> commits.commit(root, "first", false));
        if (before == null) assertFalse(Files.exists(ref));
        else assertArrayEquals(before, Files.readAllBytes(ref));
        assertArrayEquals(index, Files.readAllBytes(repo.indexFile()));
        if (failure.equals("index lock")) {
            assertEquals("busy", Files.readString(repo.metadataDirectory().resolve("index.lock")));
            Files.delete(repo.metadataDirectory().resolve("index.lock"));
        }
        if (failure.equals("commit lock")) {
            assertEquals("busy", Files.readString(repo.metadataDirectory().resolve("commit.lock")));
            Files.delete(repo.metadataDirectory().resolve("commit.lock"));
        }
        clean();
    }

    @Test
    void refsCannotPublishBeforeObjectsAndEntireSnapshotExist() throws Exception {
        String missing = "a".repeat(64);
        var refs = new RefStore(repo);
        assertThrows(IOException.class, () -> refs.prepare("main", null, missing));
        assertNull(head());
        String invalidCommit =
                objects.write(
                        ObjectType.COMMIT,
                        codec.encodeCommit(
                                new Commit(
                                        missing,
                                        List.of(),
                                        "Jane",
                                        "jane@example.com",
                                        NOW,
                                        "missing tree")));
        assertThrows(IOException.class, () -> refs.prepare("main", null, invalidCommit));
        assertNull(head());
        String tree = new TreeBuilder(objects).build(new Index(1, List.of()));
        String missingParent =
                objects.write(
                        ObjectType.COMMIT,
                        codec.encodeCommit(
                                new Commit(
                                        tree,
                                        List.of(missing),
                                        "Jane",
                                        "jane@example.com",
                                        NOW,
                                        "missing parent")));
        assertThrows(IOException.class, () -> refs.prepare("main", null, missingParent));
        assertNull(head());
        identity();
        String valid = commits.commit(root, "empty", true).commitHash();
        assertThrows(IOException.class, () -> refs.prepare("main", null, valid));
        assertEquals(valid, head());
        clean();
    }

    @Test
    void invalidMessagesAndOutsideRepositoryFailWithoutSideEffects() throws Exception {
        for (String message : List.of("", " ", "contains\0nul"))
            assertThrows(IOException.class, () -> commits.commit(root, message, true));
        assertThrows(IOException.class, () -> commits.commit(root.getParent(), "outside", true));
        assertNull(head());
        clean();
    }
}
