package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryInputSafetyIT {
    @TempDir Path temporary;

    private CliProcess.Result success(CliProcess cli, Path root, String... arguments)
            throws Exception {
        return cli.success(
                root, CliArguments.portable(temporary, arguments).toArray(String[]::new));
    }

    @Test
    void adversarialAndSupplementaryUnicodeIgnoreRulesWorkInPackagedCommands() throws Exception {
        var cli = new CliProcess(temporary);
        Path root = cli.initialize();
        String name = "a".repeat(200);
        Files.writeString(root.resolve(name), "tracked");
        Files.writeString(root.resolve("😀.txt"), "ignored");
        Files.writeString(root.resolve(".pocketgitignore"), "*a".repeat(18) + "b\n😀.txt\n");
        var status = cli.success(root, "status", "--ignored");
        assertTrue(status.out().contains(name));
        assertTrue(status.out().contains("Ignored files:"));
        assertTrue(status.out().contains("😀.txt"));
        cli.success(root, "add", ".");
        String index = Files.readString(root.resolve(".pocketgit/index"));
        assertTrue(index.contains(name));
        assertFalse(index.contains("😀.txt"));
        cli.success(root, "commit", "-m", "safe ignore matching");
        cli.success(root, "verify");
    }

    @Test
    void caseAndDirectoryBranchAliasesFailWithoutMovingHead() throws Exception {
        var cli = new CliProcess(temporary);
        Path root = cli.initialize();
        cli.success(root, "commit", "--allow-empty", "-m", "initial");
        cli.success(root, "branch", "Feature");
        cli.success(root, "branch", "Team/one");
        byte[] head = Files.readAllBytes(root.resolve(".pocketgit/HEAD"));
        byte[] feature = Files.readAllBytes(root.resolve(".pocketgit/refs/heads/Feature"));
        for (String[] command :
                new String[][] {
                    {"branch", "feature"}, {"branch", "team/two"}, {"checkout", "FEATURE"}
                }) {
            var result = cli.run(root, command);
            assertEquals(1, result.code());
            assertFalse(result.err().contains("\tat "));
            assertArrayEquals(head, Files.readAllBytes(root.resolve(".pocketgit/HEAD")));
            assertArrayEquals(
                    feature, Files.readAllBytes(root.resolve(".pocketgit/refs/heads/Feature")));
        }
        cli.success(root, "verify");
    }

    @Test
    void pathLabelsAreEscapedWhileStoredNamesAndRestoredBytesStayExact() throws Exception {
        var cli = new CliProcess(temporary);
        Path root = cli.initialize();
        String name = "unsafe\u009b2J\u202e.txt";
        String label = "unsafe\\u009b2J\\u202e.txt";
        Files.writeString(root.resolve(name), "original\n");
        assertTrue(cli.success(root, "status").out().contains(label));
        assertTrue(success(cli, root, "add", name).out().contains("Staged " + label));
        cli.success(root, "commit", "-m", "initial");
        assertTrue(Files.readString(root.resolve(".pocketgit/index")).contains(name));
        Files.writeString(root.resolve(name), "modified\n");
        var diff = cli.success(root, "diff");
        assertTrue(diff.out().contains("a/" + label));
        assertTrue(diff.out().contains("+++ b/" + label));
        assertFalse(diff.out().contains("\u009b"));
        assertFalse(diff.out().contains("\u202e"));
        assertTrue(success(cli, root, "restore", name).out().contains("Restored " + label));
        assertEquals("original\n", Files.readString(root.resolve(name)));
        cli.success(root, "verify");
    }

    @Test
    void directionalBranchAuthorAndConfigTextCannotHideTheirActualSpelling() throws Exception {
        var cli = new CliProcess(temporary);
        Path root = cli.initialize();
        success(cli, root, "config", "user.name", "Reviewer\u202e");
        assertTrue(cli.success(root, "config", "user.name").out().contains("Reviewer\\u202e"));
        cli.success(root, "commit", "--allow-empty", "-m", "initial");
        assertTrue(cli.success(root, "log").out().contains("Reviewer\\u202e"));
        String branch = "feature\u202e";
        assertTrue(success(cli, root, "branch", branch).out().contains("feature\\u202e"));
        assertTrue(success(cli, root, "checkout", branch).out().contains("feature\\u202e"));
        assertTrue(cli.success(root, "branch").out().contains("* feature\\u202e"));
        assertTrue(cli.success(root, "status").out().contains("On branch feature\\u202e"));
        assertTrue(
                cli.success(root, "commit", "--allow-empty", "-m", "second")
                        .out()
                        .contains("[feature\\u202e "));
    }
}
