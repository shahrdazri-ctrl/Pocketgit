package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.FileMode;
import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.model.RepositoryStatus;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.AddService;
import com.pocketgit.services.CommitService;
import com.pocketgit.services.HeadSnapshotReader;
import com.pocketgit.services.StatusService;
import com.pocketgit.storage.ConfigStore;
import com.pocketgit.storage.IndexStore;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StatusServiceTest {
    @TempDir Path temp;
    private Path root;
    private Repository repo;
    private final StatusService statuses = new StatusService();

    @BeforeEach
    void initialize() throws Exception {
        root = Files.createDirectory(temp.resolve("repo 日本語 space"));
        repo = new RepositoryInitializer().initialize(root).repository();
    }

    private Path write(String name, String value) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, value);
    }

    private void add(String path) throws IOException {
        new AddService().add(root, Path.of(path));
    }

    private void commit(String message) throws IOException {
        new ConfigStore(repo).set("user.name", "Status Reviewer");
        new ConfigStore(repo).set("user.email", "status@example.com");
        new CommitService().commit(root, message, false);
    }

    private RepositoryStatus status() throws IOException {
        return statuses.status(root);
    }

    @Test
    void emptyRepositoryHasNoCommitsAndIsCleanWithoutConfiguration() throws Exception {
        Files.delete(repo.configFile());
        RepositoryStatus result = status();
        assertEquals("main", result.branch());
        assertFalse(result.hasCommits());
        assertTrue(result.isClean());
        assertEquals(List.of(), result.ignored());
        assertEquals(new Index(1, List.of()), new HeadSnapshotReader().read(repo).index());
    }

    @Test
    void untrackedNewStagedAndModifiedAfterStagingBeforeFirstCommit() throws Exception {
        write("untracked", "untracked");
        write("src/new", "staged");
        add("src/new");
        write("src/new", "changed after add");
        RepositoryStatus result = statuses.status(root.resolve("src"));
        assertFalse(result.hasCommits());
        assertFalse(result.isClean());
        assertEquals(List.of("src/new"), result.stagedNew());
        assertEquals(List.of("src/new"), result.unstagedModified());
        assertEquals(List.of("untracked"), result.untracked());
        assertEquals(List.of(), result.stagedModified());
        assertEquals(List.of(), result.stagedDeleted());
    }

    @Test
    void newStagedFileDeletedBeforeCommitAppearsInBothComparisons() throws Exception {
        Path file = write("new", "staged");
        add("new");
        Files.delete(file);
        assertEquals(List.of("new"), status().stagedNew());
        assertEquals(List.of("new"), status().unstagedDeleted());
        add("new");
        assertTrue(status().isClean());
        assertFalse(status().hasCommits());
    }

    @Test
    void committedRepositoryAndUnchangedAddAreClean() throws Exception {
        write("a", "same");
        write("dir/b", "same");
        add(".");
        commit("base");
        assertTrue(status().hasCommits());
        assertTrue(status().isClean());
        add(".");
        assertTrue(status().isClean());
        assertEquals(new IndexStore(repo).load(), new HeadSnapshotReader().read(repo).index());
    }

    @Test
    void simultaneousCategoriesAreIndependentAndSorted() throws Exception {
        for (String file :
                List.of(
                        "staged-modified",
                        "staged-deleted",
                        "unstaged-modified",
                        "unstaged-deleted",
                        "both")) write(file, "original");
        add(".");
        commit("base");
        write("staged-modified", "staged edit");
        add("staged-modified");
        Files.delete(root.resolve("staged-deleted"));
        add("staged-deleted");
        write("unstaged-modified", "unstaged edit");
        Files.delete(root.resolve("unstaged-deleted"));
        write("both", "staged");
        add("both");
        write("both", "unstaged");
        write("z-new", "new");
        add("z-new");
        write("a-new", "new");
        add("a-new");
        write("scratch", "untracked");
        RepositoryStatus result = status();
        assertEquals(List.of("a-new", "z-new"), result.stagedNew());
        assertEquals(List.of("both", "staged-modified"), result.stagedModified());
        assertEquals(List.of("staged-deleted"), result.stagedDeleted());
        assertEquals(List.of("both", "unstaged-modified"), result.unstagedModified());
        assertEquals(List.of("unstaged-deleted"), result.unstagedDeleted());
        assertEquals(List.of("scratch"), result.untracked());
        assertFalse(result.isClean());
    }

    @Test
    void revertingWorkingBytesToHeadAfterStagingStillReportsBothChanges() throws Exception {
        write("a", "original");
        add("a");
        commit("base");
        write("a", "staged");
        add("a");
        write("a", "original");
        assertEquals(List.of("a"), status().stagedModified());
        assertEquals(List.of("a"), status().unstagedModified());
        add("a");
        assertTrue(status().isClean());
    }

    @Test
    void stagedDeletionThenRecreationIsUntrackedAlongsideStagedDeletion() throws Exception {
        Path file = write("a", "original");
        add("a");
        commit("base");
        Files.delete(file);
        add("a");
        write("a", "recreated");
        assertEquals(List.of("a"), status().stagedDeleted());
        assertEquals(List.of("a"), status().untracked());
        assertEquals(List.of(), status().unstagedModified());
        assertEquals(List.of(), status().unstagedDeleted());
    }

    @Test
    void deleteWholeCommittedDirectoryAndStageDeletion() throws Exception {
        write("dir/a", "a");
        write("dir/b", "b");
        add(".");
        commit("base");
        Files.delete(root.resolve("dir/a"));
        Files.delete(root.resolve("dir/b"));
        Files.delete(root.resolve("dir"));
        assertEquals(List.of("dir/a", "dir/b"), status().unstagedDeleted());
        add("dir");
        assertEquals(List.of("dir/a", "dir/b"), status().stagedDeleted());
        assertEquals(List.of(), status().unstagedDeleted());
    }

    @Test
    void fileDirectoryReplacementsReportDeletedPathsAndUntrackedReplacements() throws Exception {
        Path file = write("a", "file");
        add("a");
        commit("base");
        Files.delete(file);
        write("a/child", "child");
        assertEquals(List.of("a"), status().unstagedDeleted());
        assertEquals(List.of("a/child"), status().untracked());
        add("a");
        assertEquals(List.of("a"), status().stagedDeleted());
        assertEquals(List.of("a/child"), status().stagedNew());
        Files.delete(root.resolve("a/child"));
        Files.delete(root.resolve("a"));
        write("a", "new file");
        assertEquals(List.of("a/child"), status().unstagedDeleted());
        assertEquals(List.of("a"), status().untracked());
    }

    @Test
    void ignoresUntrackedContentButKeepsIndexedPathsTrackedUnderIgnoredDirectories()
            throws Exception {
        write("target/tracked", "base");
        write("private.env", "base");
        add(".");
        commit("base");
        write(".pocketgitignore", "target/\n*.env\ncache/\n*.log\n");
        add(".pocketgitignore");
        commit("ignore rules");
        write("target/tracked", "changed");
        write("private.env", "changed");
        write("target/untracked", "skip");
        write("target/nested/out", "skip");
        write("cache/nested/out", "skip");
        write("error.log", "skip");
        write("src/error.log", "skip");
        RepositoryStatus result = status();
        assertEquals(List.of("private.env", "target/tracked"), result.unstagedModified());
        assertEquals(
                List.of(
                        "cache/",
                        "error.log",
                        "src/error.log",
                        "target/nested/",
                        "target/untracked"),
                result.ignored());
        assertEquals(List.of(), result.untracked());
        Files.delete(root.resolve("target/tracked"));
        assertEquals(List.of("target/tracked"), status().unstagedDeleted());
    }

    @Test
    void ignoredOnlyContentDoesNotMakeRepositoryDirtyAndEmptyDirectoriesAreNotUntracked()
            throws Exception {
        write(".pocketgitignore", "cache/\n*.log\n");
        add(".pocketgitignore");
        commit("base");
        write("cache/secret", "ignored");
        write("error.log", "ignored");
        Files.createDirectory(root.resolve("empty"));
        assertTrue(status().isClean());
        assertEquals(List.of("cache/", "error.log"), status().ignored());
    }

    @Test
    void hashesContentsEvenWithIdenticalSizeAndRestoredTimestamp() throws Exception {
        Path file = write("a", "AAAA");
        add("a");
        commit("base");
        var timestamp = Files.getLastModifiedTime(file);
        write("a", "BBBB");
        Files.setLastModifiedTime(file, timestamp);
        assertEquals(List.of("a"), status().unstagedModified());
        write("a", "AAAA");
        assertTrue(status().isClean());
    }

    @Test
    void binaryEmptyAndUnicodeFilesCompareExactBytes() throws Exception {
        Files.write(root.resolve("binary"), new byte[] {0, -1, -128});
        write("empty", "");
        write("日本語/فایل space", "hello");
        add(".");
        commit("base");
        assertTrue(status().isClean());
        Files.write(root.resolve("binary"), new byte[] {0, -1, -127});
        write("empty", "x");
        write("日本語/فایل space", "changed");
        assertEquals(List.of("binary", "empty", "日本語/فایل space"), status().unstagedModified());
    }

    @Test
    void executableModeChangesAreReportedBeforeAndAfterStaging() throws Exception {
        Path file = write("run", "echo hi");
        assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null);
        Files.setPosixFilePermissions(
                file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        add("run");
        commit("base");
        Files.setPosixFilePermissions(
                file,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        assertEquals(List.of("run"), status().unstagedModified());
        add("run");
        assertEquals(List.of("run"), status().stagedModified());
        assertEquals(List.of(), status().unstagedModified());
    }

    @Test
    void trackedFilesLargerThanObjectLimitAreHashedAndLargeUntrackedFilesAreListed()
            throws Exception {
        Path file = write("large", "small");
        add("large");
        commit("base");
        try (var sparse = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength((long) ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES + 1);
        }
        try (var sparse =
                new java.io.RandomAccessFile(root.resolve("untracked-big").toFile(), "rw")) {
            sparse.setLength((long) ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES + 1);
        }
        assertEquals(List.of("large"), status().unstagedModified());
        assertEquals(List.of("untracked-big"), status().untracked());
    }

    @Test
    void statusNeverWritesMetadataObjectsOrLocksEvenWithDirtyFilesAndExistingLocks()
            throws Exception {
        write("a", "base");
        add("a");
        commit("base");
        write("a", "edit");
        write("untracked", "new");
        Files.writeString(repo.metadataDirectory().resolve("index.lock"), "held");
        Files.writeString(repo.metadataDirectory().resolve("commit.lock"), "held");
        var before = metadata();
        assertFalse(status().isClean());
        assertFalse(status().isClean());
        var after = metadata();
        assertEquals(before.keySet(), after.keySet());
        for (String path : before.keySet())
            assertArrayEquals(before.get(path), after.get(path), path);
    }

    @Test
    void metadataComponentsAreAlwaysExcludedFromWorkingTree() throws Exception {
        write("nested/.POCKETGIT/secret", "skip");
        write("a", "keep");
        assertEquals(List.of("a"), status().untracked());
        assertEquals(List.of(), status().ignored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracked file", "tracked parent", "untracked file"})
    void rejectsNonignoredSymlinksWithoutFollowingOrWritingTargets(String scenario)
            throws Exception {
        Path outside = Files.writeString(temp.resolve("outside"), "private");
        Path link;
        if (scenario.equals("tracked parent")) {
            write("dir/a", "base");
            add("dir/a");
            Files.delete(root.resolve("dir/a"));
            Files.delete(root.resolve("dir"));
            link = root.resolve("dir");
        } else if (scenario.equals("tracked file")) {
            link = write("a", "base");
            add("a");
            Files.delete(link);
        } else link = root.resolve("untracked");
        symlink(link, outside);
        assertThrows(IOException.class, this::status);
        assertEquals("private", Files.readString(outside));
    }

    @Test
    void ignoredSymlinkAndIgnoredSubtreeAreNotFollowed() throws Exception {
        write(".pocketgitignore", "private\ncache/\n");
        add(".pocketgitignore");
        commit("base");
        Path outside = Files.writeString(temp.resolve("outside"), "private data");
        symlink(root.resolve("private"), outside);
        Files.createDirectory(root.resolve("cache"));
        symlink(root.resolve("cache/link"), outside);
        assertTrue(status().isClean());
        assertEquals(List.of("cache/", "private"), status().ignored());
        assertEquals("private data", Files.readString(outside));
    }

    @ParameterizedTest
    @ValueSource(strings = {"index", "HEAD", "ref", "blob", "commit", "tree", "ignore"})
    void corruptionIsAnErrorInsteadOfAFakeCleanStatus(String kind) throws Exception {
        write("a", "base");
        add("a");
        commit("base");
        Path target =
                switch (kind) {
                    case "index" -> repo.indexFile();
                    case "HEAD" -> repo.headFile();
                    case "ref" -> repo.mainRefFile();
                    case "ignore" -> root.resolve(".pocketgitignore");
                    case "blob" ->
                            new ObjectStore(repo)
                                    .pathForHash(
                                            new IndexStore(repo)
                                                    .load()
                                                    .entries()
                                                    .getFirst()
                                                    .blobHash());
                    case "commit" ->
                            new ObjectStore(repo)
                                    .pathForHash(Files.readString(repo.mainRefFile()).strip());
                    default -> {
                        var objects = new ObjectStore(repo);
                        var commit =
                                new com.pocketgit.storage.ObjectCodec()
                                        .decodeCommit(
                                                objects.read(
                                                        Files.readString(repo.mainRefFile())
                                                                .strip()));
                        yield objects.pathForHash(commit.treeHash());
                    }
                };
        Files.writeString(target, kind.equals("ignore") ? "**/bad\n" : "broken");
        assertThrows(IOException.class, this::status);
        assertEquals(kind.equals("ignore") ? "**/bad\n" : "broken", Files.readString(target));
    }

    @Test
    void missingOrMistypedIndexedObjectsCannotProduceStatus() throws Exception {
        new IndexStore(repo)
                .save(
                        new Index(
                                1,
                                List.of(
                                        new IndexEntry(
                                                "a", "a".repeat(64), FileMode.REGULAR_FILE))));
        assertThrows(IOException.class, this::status);
        var objects = new ObjectStore(repo);
        String tree =
                new com.pocketgit.services.TreeBuilder(objects).build(new Index(1, List.of()));
        new IndexStore(repo)
                .save(new Index(1, List.of(new IndexEntry("a", tree, FileMode.REGULAR_FILE))));
        assertThrows(IOException.class, this::status);
    }

    @Test
    void corruptConfigAndReflogDoNotBlockUnrelatedReadOnlyStatus() throws Exception {
        write("a", "base");
        add("a");
        commit("base");
        Files.writeString(repo.configFile(), "broken");
        Files.writeString(repo.logsDirectory().resolve("HEAD"), "broken");
        assertTrue(status().isClean());
    }

    @Test
    void outsideRepositoryFailsCleanly() {
        assertThrows(IOException.class, () -> statuses.status(temp));
    }

    private TreeMap<String, byte[]> metadata() throws IOException {
        var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(repo.metadataDirectory())) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                result.put(
                        repo.metadataDirectory().relativize(path).toString(),
                        Files.readAllBytes(path));
        }
        return result;
    }

    private void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "symlinks unavailable");
        }
    }
}
