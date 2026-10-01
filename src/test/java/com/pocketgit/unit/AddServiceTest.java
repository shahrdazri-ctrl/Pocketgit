package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pocketgit.model.FileMode;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.AddService;
import com.pocketgit.storage.IndexStore;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddServiceTest {
    @TempDir Path temp;
    private Path root;
    private Repository repo;
    private IndexStore indexes;
    private ObjectStore objects;
    private final AddService service = new AddService();

    @BeforeEach
    void initialize() throws Exception {
        root = Files.createDirectory(temp.resolve("repo"));
        repo = new RepositoryInitializer().initialize(root).repository();
        indexes = new IndexStore(repo);
        objects = new ObjectStore(repo);
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    private Path text(String name, String content) throws IOException {
        return write(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private AddService.AddResult add(String path) throws IOException {
        return service.add(root, Path.of(path));
    }

    private List<String> paths() throws IOException {
        return indexes.load().entries().stream().map(e -> e.path()).toList();
    }

    @Test
    void stagesNewModifiedEmptyAndBinaryFilesWithExactBlobContents() throws Exception {
        for (var content :
                java.util.Map.of(
                                "empty",
                                new byte[0],
                                "binary",
                                new byte[] {0, -1, -128},
                                "hello.txt",
                                "hello\n".getBytes())
                        .entrySet()) {
            write(content.getKey(), content.getValue());
            assertEquals(List.of(content.getKey()), add(content.getKey()).stagedPaths());
        }
        String original =
                indexes.load().entries().stream()
                        .filter(e -> e.path().equals("hello.txt"))
                        .findFirst()
                        .orElseThrow()
                        .blobHash();
        text("hello.txt", "modified\n");
        add("hello.txt");
        for (var entry : indexes.load().entries()) {
            assertArrayEquals(
                    Files.readAllBytes(root.resolve(entry.path())),
                    objects.readBlob(entry.blobHash()).content());
        }
        assertNotEquals(
                original,
                indexes.load().entries().stream()
                        .filter(e -> e.path().equals("hello.txt"))
                        .findFirst()
                        .orElseThrow()
                        .blobHash());
        assertClean();
    }

    @Test
    void duplicateAddKeepsOneEntryAndUnchangedIndexBytes() throws Exception {
        text("a", "same");
        add("a");
        byte[] before = Files.readAllBytes(repo.indexFile());
        add("a");
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertEquals(List.of("a"), paths());
        assertClean();
    }

    @Test
    void stagesDirectoryInSortedOrderAndPreservesOtherScope() throws Exception {
        text("other.txt", "outside scope");
        add("other.txt");
        text("src/z.txt", "z");
        text("src/nested/a.txt", "a");
        add("src");
        assertEquals(List.of("other.txt", "src/nested/a.txt", "src/z.txt"), paths());
        assertTrue(paths().stream().noneMatch(p -> p.contains("\\")));
    }

    @Test
    void dotStagesWholeRepositoryEvenFromNestedDirectory() throws Exception {
        text("root.txt", "root");
        Path nested = Files.createDirectories(root.resolve("src/main/java"));
        text("src/main/java/Code.java", "code");
        service.add(nested, Path.of("."));
        assertEquals(List.of("root.txt", "src/main/java/Code.java"), paths());
    }

    @Test
    void otherArgumentsResolveRelativeToInvocationDirectory() throws Exception {
        Path file = text("src/file.txt", "nested");
        service.add(file.getParent(), Path.of("file.txt"));
        assertEquals(List.of("src/file.txt"), paths());
        service.add(file.getParent(), file.toAbsolutePath());
        assertEquals(1, indexes.load().entries().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"with spaces.txt", "日本語/فایل.txt"})
    void supportsUnicodeAndSpaces(String name) throws Exception {
        text(name, "hello");
        add(name);
        assertEquals(List.of(name), paths());
    }

    @Test
    void stagesDeletedFileDeletedDirectoryAndDotWithinScope() throws Exception {
        Path a = text("src/a", "a"), b = text("src/b", "b"), c = text("other", "keep");
        add(".");
        Files.delete(a);
        assertEquals(List.of("src/a"), add("src/a").removedPaths());
        assertEquals(List.of("other", "src/b"), paths());
        Files.delete(b);
        Files.delete(b.getParent());
        assertEquals(List.of("src/b"), add("src").removedPaths());
        Files.delete(c);
        add(".");
        assertTrue(paths().isEmpty());
        assertClean();
    }

    @Test
    void missingUnstagedPathFailsAndLeavesIndexUnchanged() throws Exception {
        byte[] before = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> add("absent"));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertClean();
    }

    @Test
    void ignoreRulesExcludeUntrackedFilesAndSubtreesButNotIgnoreFileItself() throws Exception {
        text(".pocketgitignore", "target/\n*.class\n*.log\n.env\n.idea/\n");
        for (String name :
                List.of(
                        "target/output",
                        "nested/target/output",
                        "src/App.class",
                        "build.log",
                        ".env",
                        ".idea/workspace.xml")) text(name, "skip");
        text("src/App.java", "keep");
        var result = add(".");
        assertEquals(List.of(".pocketgitignore", "src/App.java"), paths());
        assertFalse(result.ignoredPaths().isEmpty());
        assertTrue(add(".env").stagedPaths().isEmpty());
        assertClean();
    }

    @Test
    void trackedFilesRemainStagedUnderNewIgnoreRulesIncludingDeletions() throws Exception {
        Path a = text("target/a", "old"), b = text("target/b", "old");
        add("target");
        text(".pocketgitignore", "target/\n");
        text("target/a", "new");
        Files.delete(b);
        text("target/untracked", "skip");
        var result = add("target");
        assertEquals(List.of("target/a"), paths());
        assertEquals(List.of("target/b"), result.removedPaths());
        assertArrayEquals(
                Files.readAllBytes(a),
                objects.readBlob(indexes.load().entries().getFirst().blobHash()).content());
    }

    @Test
    void neverStagesMetadataAndExplicitMetadataRequestsFail() throws Exception {
        text("safe", "ok");
        text("nested/.pocketgit/secret", "metadata");
        add(".");
        assertEquals(List.of("safe"), paths());
        byte[] before = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> add(".pocketgit/HEAD"));
        assertThrows(IOException.class, () -> add("nested/.pocketgit"));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
    }

    @Test
    void fileDirectoryTransitionsRemoveConflictingIndexEntries() throws Exception {
        Path file = text("a", "old file");
        add("a");
        Files.delete(file);
        text("a/child", "new child");
        add("a/child");
        assertEquals(List.of("a/child"), paths());
        Files.delete(root.resolve("a/child"));
        Files.delete(root.resolve("a"));
        text("a", "file again");
        add("a");
        assertEquals(List.of("a"), paths());
        assertArrayEquals(
                "file again".getBytes(),
                objects.readBlob(indexes.load().entries().getFirst().blobHash()).content());
    }

    @Test
    void rejectsTraversalAndCanceledSymlinksWithoutChangingIndex() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret"), "private");
        text("safe", "ok");
        byte[] before = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> add("../outside/secret"));
        assertThrows(IOException.class, () -> service.add(root, outside.resolve("secret")));
        symlinkOrSkip(root.resolve("link"), outside);
        for (String path : List.of("link/secret", "link/../safe", "link/..", "./link/../safe")) {
            assertThrows(IOException.class, () -> add(path), path);
        }
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertClean();
    }

    @Test
    void directoryScanRejectsSymlinksAndKeepsIndexUnchanged() throws Exception {
        text("a-first", "could otherwise stage");
        Path outside = Files.writeString(temp.resolve("outside"), "private");
        symlinkOrSkip(root.resolve("z-link"), outside);
        byte[] before = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> add("."));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertEquals("private", Files.readString(outside));
        try (var paths = Files.walk(repo.objectsDirectory())) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
        assertClean();
    }

    @Test
    void corruptIndexInvalidIgnoreAndExistingLockFailWithoutOverwritingMetadata() throws Exception {
        text("file", "stage me");
        Files.writeString(repo.indexFile(), "broken");
        assertThrows(IOException.class, () -> add("file"));
        assertEquals("broken", Files.readString(repo.indexFile()));
        assertClean();
        indexes.save(new com.pocketgit.model.Index(1, List.of()));
        byte[] before = Files.readAllBytes(repo.indexFile());
        text(".pocketgitignore", "**/target\n");
        assertThrows(IOException.class, () -> add("file"));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertClean();
        Files.delete(root.resolve(".pocketgitignore"));
        Path lock =
                Files.writeString(
                        repo.metadataDirectory().resolve("index.lock"), "another operation");
        assertThrows(IOException.class, () -> add("file"));
        assertEquals("another operation", Files.readString(lock));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        Files.delete(lock);
        add("file");
        assertClean();
    }

    @Test
    void objectWriteFailurePreservesIndexAndReleasesLock() throws Exception {
        Path file = write("binary", new byte[] {1, 2, 3});
        add("binary");
        var entry = indexes.load().entries().getFirst();
        Files.write(objects.pathForHash(entry.blobHash()), new byte[] {0, 0});
        byte[] before = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> add("binary"));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(file));
        assertClean();
    }

    @Test
    void tooLargeFileFailsBeforeReadingItAndPreservesIndex() throws Exception {
        Path file = root.resolve("large");
        try (var sparse = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength((long) ObjectStore.DEFAULT_MAX_PAYLOAD_BYTES + 1);
        }
        byte[] before = Files.readAllBytes(repo.indexFile());
        assertThrows(IOException.class, () -> add("large"));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertClean();
    }

    @Test
    void preservesPosixExecutableModeWhenSupported() throws Exception {
        Path file = text("run.sh", "echo hi\n");
        assumeTrue(Files.getFileAttributeView(file, PosixFileAttributeView.class) != null);
        Files.setPosixFilePermissions(
                file,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        add("run.sh");
        assertEquals(FileMode.EXECUTABLE_FILE, indexes.load().entries().getFirst().mode());
        Files.setPosixFilePermissions(
                file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        add("run.sh");
        assertEquals(FileMode.REGULAR_FILE, indexes.load().entries().getFirst().mode());
    }

    @Test
    void concurrentAddsWithRetryPreserveAllIndependentPaths() throws Exception {
        for (int i = 0; i < 8; i++) text("file" + i, "payload" + i);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 8; i++) {
                String name = "file" + i;
                futures.add(
                        executor.submit(
                                () -> {
                                    long deadline =
                                            System.nanoTime()
                                                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                                                            10);
                                    while (true) {
                                        try {
                                            new AddService().add(root, Path.of(name));
                                            return null;
                                        } catch (IOException busy) {
                                            if (!busy.getMessage().contains("index is locked")
                                                    || System.nanoTime() > deadline) throw busy;
                                            Thread.sleep(10);
                                        }
                                    }
                                }));
            }
            for (var future : futures) future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(8, paths().size());
        for (var entry : indexes.load().entries()) {
            assertArrayEquals(
                    Files.readAllBytes(root.resolve(entry.path())),
                    objects.readBlob(entry.blobHash()).content());
        }
        assertClean();
    }

    private void symlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "symlinks unavailable");
        }
    }

    private void assertClean() throws IOException {
        assertFalse(Files.exists(repo.metadataDirectory().resolve("index.lock")));
        try (var paths = Files.walk(repo.metadataDirectory())) {
            assertFalse(
                    paths.anyMatch(
                            p ->
                                    p.getFileName().toString().startsWith(".index-")
                                            || p.getFileName().toString().startsWith(".tmp-")));
        }
    }
}
