package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.Blob;
import com.pocketgit.model.FileMode;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.WorkingTreeEdit;
import com.pocketgit.storage.ObjectStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class WorkingTreeEditLifecycleTest {
    @TempDir Path root;
    private Repository repository;
    private IndexEntry entry;

    @BeforeEach
    void initialize() throws Exception {
        repository = new RepositoryInitializer().initialize(root).repository();
        String hash = new ObjectStore(repository).writeBlob(new Blob(new byte[] {1, 2, 3}));
        entry = new IndexEntry("file", hash, FileMode.REGULAR_FILE);
        Files.writeString(root.resolve("file"), "original");
    }

    private List<Path> backups() throws Exception {
        try (var paths = Files.list(repository.metadataDirectory())) {
            return paths.filter(path -> path.getFileName().toString().startsWith("edit-")).toList();
        }
    }

    @Test
    void closeRestoresAnUncompletedEditAndCleansItsPreparation() throws Exception {
        try (var edit = new WorkingTreeEdit(repository, List.of(entry), List.of())) {
            assertEquals(1, backups().size());
            edit.apply();
        }
        assertEquals("original", Files.readString(root.resolve("file")));
        assertTrue(backups().isEmpty());
    }

    @Test
    void completedEditsAndCancelledPreparationCleanPrivateFiles() throws Exception {
        try (var edit = new WorkingTreeEdit(repository, List.of(entry), List.of())) {}
        assertTrue(backups().isEmpty());
        try (var edit = new WorkingTreeEdit(repository, List.of(entry), List.of())) {
            edit.apply();
            edit.complete();
            assertThrows(IllegalStateException.class, edit::rollback);
        }
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(root.resolve("file")));
        assertTrue(backups().isEmpty());
    }

    @Test
    void failedPreparationLeavesNoBackupsOrUserMutations() throws Exception {
        var missing = new IndexEntry("other", "a".repeat(64), FileMode.REGULAR_FILE);
        assertThrows(
                IOException.class,
                () -> new WorkingTreeEdit(repository, List.of(missing), List.of("file")));
        assertTrue(backups().isEmpty());
        assertEquals("original", Files.readString(root.resolve("file")));
    }

    @Test
    void obstructedRecoveryRetainsPrivateBackupsAndAPathManifest() throws Exception {
        var edit = new WorkingTreeEdit(repository, List.of(entry), List.of());
        Path backup = backups().getFirst();
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(backup));
        }
        edit.apply();
        Files.delete(root.resolve("file"));
        Files.createDirectory(root.resolve("file"));
        Files.writeString(root.resolve("file/private"), "obstruction");
        assertThrows(IOException.class, edit::rollback);
        var failure = assertThrows(IOException.class, edit::close);
        assertTrue(failure.getMessage().contains(backup.toString()));
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(Files.readAllBytes(backup.resolve("manifest.json")));
        assertEquals("file", json.get("originals").get(0).get("path").asText());
        assertEquals(
                "original",
                Files.readString(
                        backup.resolve(json.get("originals").get(0).get("backup").asText())));
        assertEquals("obstruction", Files.readString(root.resolve("file/private")));
    }
}
