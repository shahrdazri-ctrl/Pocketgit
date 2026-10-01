package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditSafetyIT {
    @TempDir Path temp;

    @Test
    void terminalEscapesInMessagesAndFilesAreDisplayedAsText() throws Exception {
        var cli = new CliProcess(temp);
        Path root = cli.initialize();
        Files.writeString(root.resolve("file"), "first\n");
        cli.success(root, "add", ".");
        var commit = cli.success(root, "commit", "-m", "message\u001b[2J");
        assertFalse(commit.out().contains("\u001b"));
        assertTrue(commit.out().contains("message\\u001b[2J"));
        assertTrue(cli.success(root, "log", "--oneline").out().contains("message\\u001b[2J"));
        Files.writeString(root.resolve("file"), "\u001b]52;c;payload\u0007\n");
        var diff = cli.success(root, "diff");
        assertFalse(diff.out().contains("\u001b"));
        assertFalse(diff.out().contains("\u0007"));
        assertTrue(diff.out().contains("+\\u001b]52;c;payload\\u0007"));
    }

    @Test
    void nonportableMetadataAliasAndDeviceBranchAreRejectedCleanly() throws Exception {
        var cli = new CliProcess(temp);
        Path root = cli.initialize();
        byte[] head = Files.readAllBytes(root.resolve(".pocketgit/HEAD"));
        byte[] index = Files.readAllBytes(root.resolve(".pocketgit/index"));
        var add = cli.run(root, "add", ".pocketgit./HEAD");
        assertEquals(1, add.code());
        assertFalse(add.err().contains("\tat "));
        assertArrayEquals(head, Files.readAllBytes(root.resolve(".pocketgit/HEAD")));
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".pocketgit/index")));
        cli.success(root, "commit", "--allow-empty", "-m", "empty");
        assertEquals(1, cli.run(root, "branch", "NUL.txt").code());
        assertFalse(Files.exists(root.resolve(".pocketgit/refs/heads/NUL.txt")));
    }

    @Test
    void stagingCaseAliasesPreservesIndexOnCaseSensitiveFilesystem() throws Exception {
        var cli = new CliProcess(temp);
        Path root = cli.initialize();
        Files.writeString(root.resolve("README"), "first");
        assumeTrue(!Files.exists(root.resolve("readme")), "case-insensitive filesystem");
        Files.writeString(root.resolve("readme"), "second");
        byte[] index = Files.readAllBytes(root.resolve(".pocketgit/index"));
        var result = cli.run(root, "add", ".");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("filesystem path collision"));
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".pocketgit/index")));
        assertEquals("first", Files.readString(root.resolve("README")));
        assertEquals("second", Files.readString(root.resolve("readme")));
    }
}
