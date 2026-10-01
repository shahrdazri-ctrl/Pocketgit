package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.Blob;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.HeadSnapshotReader;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadSnapshotReaderTest {
    @TempDir Path root;
    private Repository repo;
    private final HeadSnapshotReader reader = new HeadSnapshotReader();

    @BeforeEach
    void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(root).repository();
    }

    @Test
    void detectsHeadOrBranchMovementWhileReadingStatus() throws Exception {
        var first = reader.read(repo);
        reader.requireUnchanged(repo, first);
        Files.writeString(repo.mainRefFile(), "a".repeat(64) + "\n");
        assertTrue(
                assertThrows(IOException.class, () -> reader.requireUnchanged(repo, first))
                        .getMessage()
                        .contains("during status"));
        Files.writeString(repo.mainRefFile(), "");
        Files.writeString(repo.headFile(), "ref: refs/heads/feature\n");
        Files.writeString(repo.headsDirectory().resolve("feature"), "");
        assertTrue(
                assertThrows(IOException.class, () -> reader.requireUnchanged(repo, first))
                        .getMessage()
                        .contains("HEAD changed"));
    }

    @Test
    void missingOrNoncommitHeadTargetIsAnError() throws Exception {
        Files.writeString(repo.mainRefFile(), "a".repeat(64) + "\n");
        assertThrows(IOException.class, () -> reader.read(repo));
        String blob = new ObjectStore(repo).writeBlob(new Blob(new byte[] {1}));
        Files.writeString(repo.mainRefFile(), blob + "\n");
        assertThrows(IOException.class, () -> reader.read(repo));
        Files.delete(repo.mainRefFile());
        assertThrows(IOException.class, () -> reader.read(repo));
    }
}
