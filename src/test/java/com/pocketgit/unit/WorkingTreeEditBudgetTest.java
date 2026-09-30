package com.pocketgit.unit;

import com.pocketgit.model.Blob;
import com.pocketgit.model.FileMode;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.WorkingTreeEdit;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkingTreeEditBudgetTest {
    @TempDir Path root;
    private Repository repository;

    @BeforeEach void initialize() throws Exception {
        repository = new RepositoryInitializer().initialize(root).repository();
    }

    @Test void deletionBackupsStopAtBudgetBeforeResolvingReplacementObjects() throws Exception {
        Files.writeString(root.resolve("a"), "1234");
        Files.writeString(root.resolve("b"), "5678");
        // The missing replacement must not be read after cumulative backups exhaust the budget.
        var missing = new IndexEntry("c", "a".repeat(64), FileMode.REGULAR_FILE);
        var failure = assertThrows(IOException.class,
                () -> new WorkingTreeEdit(repository, List.of(missing), List.of("a", "b"), 7));
        assertTrue(failure.getMessage().contains("7 byte backup and content limit"));
        assertEquals("1234", Files.readString(root.resolve("a")));
        assertEquals("5678", Files.readString(root.resolve("b")));
        assertFalse(Files.exists(root.resolve("c")));
    }

    @Test void oversizedWorkingBackupIsRejectedBeforeReplacementReads() throws Exception {
        Files.writeString(root.resolve("a"), "12345678");
        var missing = new IndexEntry("c", "a".repeat(64), FileMode.REGULAR_FILE);
        var failure = assertThrows(IOException.class,
                () -> new WorkingTreeEdit(repository, List.of(missing), List.of("a"), 7));
        assertTrue(failure.getMessage().contains("7 byte backup and content limit"));
        assertEquals("12345678", Files.readString(root.resolve("a")));
    }

    @Test void deletionOnlyEditCanUseExactBudgetAndRollback() throws Exception {
        Files.writeString(root.resolve("a"), "1234");
        var edit = new WorkingTreeEdit(repository, List.of(), List.of("a"), 4);
        edit.apply();
        assertFalse(Files.exists(root.resolve("a")));
        edit.rollback();
        assertEquals("1234", Files.readString(root.resolve("a")));
    }

    @Test void replacementContentSharesBudgetWithOriginalBackup() throws Exception {
        Files.writeString(root.resolve("a"), "1234");
        String hash = new ObjectStore(repository).writeBlob(new Blob(new byte[]{5, 6, 7, 8}));
        var entry = new IndexEntry("a", hash, FileMode.REGULAR_FILE);
        var failure = assertThrows(IOException.class,
                () -> new WorkingTreeEdit(repository, List.of(entry), List.of(), 7));
        assertTrue(failure.getMessage().contains("7 byte backup and content limit"));
        assertEquals("1234", Files.readString(root.resolve("a")));
        var edit = new WorkingTreeEdit(repository, List.of(entry), List.of(), 8);
        edit.apply();
        assertArrayEquals(new byte[]{5, 6, 7, 8}, Files.readAllBytes(root.resolve("a")));
        edit.rollback();
        assertEquals("1234", Files.readString(root.resolve("a")));
    }

    @Test void zeroBudgetAllowsEmptyFilesButCannotIncreaseProductionLimit() throws Exception {
        Files.write(root.resolve("empty"), new byte[0]);
        var edit = new WorkingTreeEdit(repository, List.of(), List.of("empty"), 0);
        edit.apply();
        edit.rollback();
        assertArrayEquals(new byte[0], Files.readAllBytes(root.resolve("empty")));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkingTreeEdit(repository, List.of(), List.of(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkingTreeEdit(repository, List.of(), List.of(), WorkingTreeEdit.MAX_BYTES + 1));
    }
}
