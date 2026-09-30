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

class WorkingTreeEditSafetyTest {
    @TempDir Path root;
    private Repository repository;
    private ObjectStore objects;

    @BeforeEach
    void initialize() throws Exception {
        repository = new RepositoryInitializer().initialize(root).repository();
        objects = new ObjectStore(repository);
    }

    private IndexEntry replacement(String name) throws Exception {
        return new IndexEntry(
                name,
                objects.writeBlob(
                        new Blob("replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                FileMode.REGULAR_FILE);
    }

    @Test
    void failedApplyAndRollbackPreserveFilesCreatedAfterPreparation() throws Exception {
        var edit =
                new WorkingTreeEdit(
                        repository, List.of(replacement("a"), replacement("z")), List.of());
        Files.createDirectory(root.resolve("a"));
        Files.writeString(root.resolve("a/private"), "directory obstruction");
        Files.writeString(root.resolve("z"), "new user work");
        assertThrows(IOException.class, edit::apply);
        edit.rollback();
        assertEquals("new user work", Files.readString(root.resolve("z")));
        assertEquals("directory obstruction", Files.readString(root.resolve("a/private")));
    }

    @Test
    void changedBackupIsRejectedBeforeAnyDeletion() throws Exception {
        Files.writeString(root.resolve("a"), "original");
        Files.writeString(root.resolve("b"), "original");
        var edit = new WorkingTreeEdit(repository, List.of(replacement("b")), List.of("a"));
        Files.writeString(root.resolve("b"), "user changed this after preparation");
        assertThrows(IOException.class, edit::apply);
        edit.rollback();
        assertEquals("original", Files.readString(root.resolve("a")));
        assertEquals("user changed this after preparation", Files.readString(root.resolve("b")));
    }

    @Test
    void newFileAtPreviouslyMissingDeletePathIsPreserved() throws Exception {
        var edit = new WorkingTreeEdit(repository, List.of(), List.of("missing"));
        Files.writeString(root.resolve("missing"), "untracked content");
        assertThrows(IOException.class, edit::apply);
        edit.rollback();
        assertEquals("untracked content", Files.readString(root.resolve("missing")));
    }

    @Test
    void appliedEditCannotRunTwiceAndRollbackIsIdempotent() throws Exception {
        Files.writeString(root.resolve("a"), "original");
        var edit = new WorkingTreeEdit(repository, List.of(replacement("a")), List.of());
        edit.apply();
        assertThrows(IllegalStateException.class, edit::apply);
        edit.rollback();
        edit.rollback();
        assertEquals("original", Files.readString(root.resolve("a")));
        assertThrows(IllegalStateException.class, edit::apply);
    }

    @Test
    void rollbackRestoresOnlyMutatedFilesAfterPartialApplication() throws Exception {
        Files.writeString(root.resolve("a"), "first original");
        Files.writeString(root.resolve("blocked"), "not a directory");
        Files.writeString(root.resolve("z"), "untouched original");
        var before =
                Files.readAttributes(
                        root.resolve("z"), java.nio.file.attribute.BasicFileAttributes.class);
        var edit =
                new WorkingTreeEdit(
                        repository,
                        List.of(replacement("a"), replacement("blocked/child"), replacement("z")),
                        List.of());
        assertThrows(IOException.class, edit::apply);
        edit.rollback();
        var after =
                Files.readAttributes(
                        root.resolve("z"), java.nio.file.attribute.BasicFileAttributes.class);
        assertEquals("first original", Files.readString(root.resolve("a")));
        assertEquals("not a directory", Files.readString(root.resolve("blocked")));
        assertEquals("untouched original", Files.readString(root.resolve("z")));
        assertEquals(before.fileKey(), after.fileKey());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
    }
}
