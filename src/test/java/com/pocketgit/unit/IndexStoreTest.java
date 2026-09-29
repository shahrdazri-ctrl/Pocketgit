package com.pocketgit.unit;

import com.pocketgit.model.FileMode;
import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.IndexCorruptionException;
import com.pocketgit.storage.IndexStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IndexStoreTest {
    @TempDir Path temp;
    private Repository repo;
    private IndexStore store;
    private IndexEntry entry(String path) { return new IndexEntry(path, "a".repeat(64), FileMode.REGULAR_FILE); }
    @BeforeEach void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(temp).repository();
        store = new IndexStore(repo);
    }

    @Test void loadsPhaseOneIndexAndSavesSortedDeterministicSnapshots() throws Exception {
        assertEquals(new Index(1, List.of()), store.load());
        var index = new Index(1, List.of(entry("z/file"), entry("a.txt")));
        store.save(index);
        assertEquals(index, new IndexStore(repo).load());
        byte[] first = Files.readAllBytes(repo.indexFile());
        assertFalse(new String(first, java.nio.charset.StandardCharsets.UTF_8).contains("\r"));
        store.save(new Index(1, List.of(entry("a.txt"), entry("z/file"))));
        assertArrayEquals(first, Files.readAllBytes(repo.indexFile()));
        assertEquals(List.of("a.txt", "z/file"), store.load().entries().stream().map(IndexEntry::path).toList());
        assertClean();
    }

    @Test void indexIsImmutableAndRejectsDuplicatesAndFileDirectoryConflicts() {
        var input = new ArrayList<>(List.of(entry("a")));
        var index = new Index(1, input);
        input.clear();
        assertEquals(1, index.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> index.entries().clear());
        assertThrows(IllegalArgumentException.class, () -> new Index(1, List.of(entry("a"), entry("a"))));
        assertThrows(IllegalArgumentException.class, () -> new Index(1, List.of(entry("a"), entry("a/file"))));
        assertThrows(IllegalArgumentException.class, () -> new Index(2, List.of()));
    }

    @ParameterizedTest @ValueSource(strings = {"/outside", "../outside", "src/../outside", "C:/file", "a\\b", "a//b", "./a", "a/", "", ".pocketgit/HEAD", "src/.POCKETGIT/HEAD", "line\nname"})
    void rejectsUnsafeIndexPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> entry(path));
    }

    @Test void rejectsMalformedHashAndMissingMode() {
        assertThrows(IllegalArgumentException.class, () -> new IndexEntry("a", "invalid", FileMode.REGULAR_FILE));
        assertThrows(NullPointerException.class, () -> new IndexEntry("a", "a".repeat(64), null));
    }

    @ParameterizedTest @ValueSource(strings = {"not json", "null", "{}", "{\"version\":2,\"entries\":[]}", "{\"version\":1,\"entries\":null}",
            "{\"version\":1,\"version\":1,\"entries\":[]}", "{\"version\":1,\"entries\":[],\"unknown\":1}",
            "{\"version\":1,\"entries\":[]} {}", "{\"version\":\"1\",\"entries\":[]}", "{\"version\":1.1,\"entries\":[]}"})
    void rejectsCorruptSchemaWithoutChangingBytes(String json) throws Exception {
        Files.writeString(repo.indexFile(), json);
        assertThrows(IndexCorruptionException.class, () -> store.load());
        assertEquals(json, Files.readString(repo.indexFile()));
        assertClean();
    }

    @Test void lockExcludesOtherWritersAndReleasesAfterClose() throws Exception {
        try (var update = store.beginUpdate()) {
            assertThrows(IOException.class, () -> new IndexStore(repo).beginUpdate());
            assertThrows(IOException.class, () -> store.save(new Index(1, List.of())));
            update.save(new Index(1, List.of(entry("a"))));
            assertEquals(1, update.load().entries().size());
        }
        store.save(new Index(1, List.of()));
        assertClean();
    }

    @Test void failedSerializationLimitPreservesOriginalAndReleasesLock() throws Exception {
        byte[] before = Files.readAllBytes(repo.indexFile());
        var huge = new Index(1, List.of(entry("x".repeat(IndexStore.MAX_INDEX_BYTES))));
        assertThrows(IOException.class, () -> store.save(huge));
        assertArrayEquals(before, Files.readAllBytes(repo.indexFile()));
        assertClean();
        var closed = store.beginUpdate();
        closed.close();
        assertThrows(IllegalStateException.class, closed::load);
        assertThrows(IllegalStateException.class, () -> closed.save(new Index(1, List.of())));
    }

    @Test void rejectsOversizedIndexAndSymlinkWithoutFollowingIt() throws Exception {
        Files.write(repo.indexFile(), new byte[IndexStore.MAX_INDEX_BYTES + 1]);
        assertThrows(IndexCorruptionException.class, () -> store.load());
        Files.delete(repo.indexFile());
        Path outside = Files.writeString(temp.resolve("outside"), "keep");
        try { Files.createSymbolicLink(repo.indexFile(), outside); }
        catch (IOException | UnsupportedOperationException unavailable) { assumeTrue(false, "symlinks unavailable"); }
        assertThrows(IndexCorruptionException.class, () -> store.load());
        assertThrows(IndexCorruptionException.class, () -> store.save(new Index(1, List.of())));
        assertEquals("keep", Files.readString(outside));
    }

    private void assertClean() throws IOException {
        assertFalse(Files.exists(repo.metadataDirectory().resolve("index.lock")));
        try (var paths = Files.list(repo.metadataDirectory())) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".index-")));
        }
    }
}
