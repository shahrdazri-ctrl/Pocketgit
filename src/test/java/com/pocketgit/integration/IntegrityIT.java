package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.Blob;
import com.pocketgit.repository.Repository;
import com.pocketgit.storage.ObjectStore;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrityIT {
    @TempDir Path temp;

    @Test
    void realisticWorkflowVerifiesBranchSnapshotsAndProtectsConflicts() throws Exception {
        var cli = new CliProcess(temp);
        Path root = cli.initialize();
        Files.writeString(root.resolve("a"), "first\n");
        cli.success(root, "add", ".");
        cli.success(root, "commit", "-m", "first");
        cli.success(root, "branch", "feature");
        Files.writeString(root.resolve("a"), "second\n");
        assertTrue(cli.success(root, "status").out().contains("modified"));
        assertTrue(cli.success(root, "diff").out().contains("+second"));
        cli.success(root, "add", ".");
        cli.success(root, "commit", "-m", "second");
        assertTrue(cli.success(root, "log", "--oneline").out().contains("second"));
        assertTrue(cli.success(root, "verify").out().contains("Repository OK."));
        cli.success(root, "checkout", "feature");
        assertEquals("first\n", Files.readString(root.resolve("a")));
        cli.success(root, "verify");
        Files.writeString(root.resolve("a"), "private\n");
        byte[] head = Files.readAllBytes(root.resolve(".pocketgit/HEAD"));
        assertEquals(1, cli.run(root, "checkout", "main").code());
        assertEquals("private\n", Files.readString(root.resolve("a")));
        assertArrayEquals(head, Files.readAllBytes(root.resolve(".pocketgit/HEAD")));
        cli.success(root, "restore", "a");
        cli.success(root, "checkout", "main");
        cli.success(root, "verify");
    }

    @Test
    void corruptionHasExitOneClearErrorsAndNoRepairs() throws Exception {
        var cli = new CliProcess(temp);
        Path root = cli.initialize();
        var objects = new ObjectStore(new Repository(root));
        String orphan = objects.writeBlob(new Blob(new byte[] {8}));
        Path object = objects.pathForHash(orphan);
        Files.writeString(object, "corrupt");
        Files.writeString(root.resolve(".pocketgit/index"), "broken");
        var result = cli.run(root, "verify");
        assertEquals(1, result.code());
        assertTrue(result.out().contains("ERROR object " + orphan));
        assertTrue(result.out().contains("ERROR index:"));
        assertTrue(result.out().contains("integrity check failed"));
        assertFalse(result.out().contains("\tat "));
        assertEquals("", result.err());
        assertEquals("corrupt", Files.readString(object));
        assertEquals("broken", Files.readString(root.resolve(".pocketgit/index")));
        assertFalse(Files.exists(root.resolve(".pocketgit/index.lock")));
    }
}
