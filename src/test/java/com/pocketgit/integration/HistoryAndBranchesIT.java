package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every command runs in a fresh Java process against the packaged JAR. */
class HistoryAndBranchesIT {
    @TempDir Path temp;

    private record Result(int code, String out, String err) {}

    private Result run(Path cwd, String... args) throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "-Dfile.encoding=UTF-8",
                                "-jar",
                                Path.of(System.getProperty("pocketgit.jar"))
                                        .toAbsolutePath()
                                        .toString()));
        command.addAll(CliArguments.portable(temp, args));
        Path out = temp.resolve("stdout"), err = temp.resolve("stderr");
        var builder =
                new ProcessBuilder(command)
                        .directory(cwd.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().remove("POCKETGIT_AUTHOR_NAME");
        builder.environment().remove("POCKETGIT_AUTHOR_EMAIL");
        var process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "CLI must finish within 20 seconds");
        // Compare displayed text across native console line endings. Repository bytes below
        // still use exact readString/readAllBytes assertions.
        return new Result(
                process.exitValue(),
                Files.readString(out).replace("\r\n", "\n"),
                Files.readString(err).replace("\r\n", "\n"));
    }

    private Result success(Path root, String... args) throws Exception {
        var result = run(root, args);
        assertEquals(0, result.code(), result.err());
        assertEquals("", result.err());
        return result;
    }

    private Path initialize() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo 日本語 space"));
        success(root, "init");
        success(root, "config", "user.name", "Jane 日本語");
        success(root, "config", "user.email", "jane@example.com");
        return root;
    }

    private String commit(Path root, String message, String text) throws Exception {
        Files.writeString(root.resolve("hello.txt"), text);
        success(root, "add", ".");
        success(root, "commit", "-m", message);
        return Files.readString(root.resolve(".pocketgit/refs/heads/main")).strip();
    }

    private TreeMap<String, byte[]> state(Path root) throws Exception {
        var result = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString(), Files.readAllBytes(path));
        }
        return result;
    }

    private void unchanged(Path root, TreeMap<String, byte[]> before) throws Exception {
        var after = state(root);
        assertEquals(before.keySet(), after.keySet());
        before.forEach((path, bytes) -> assertArrayEquals(bytes, after.get(path), path));
    }

    @Test
    void realHistoryShowAndBranchWorkflowSurvivesRestartsAndPreservesFiles() throws Exception {
        Path root = initialize();
        assertEquals("* main\n", success(root, "branch").out());
        assertEquals("No commits yet.\n", success(root, "log").out());
        assertEquals(1, run(root, "branch", "unborn").code());
        String first = commit(root, "Initial commit\n\nDetailed body", "first\n");
        success(root, "branch", "feature/login");
        assertEquals(
                first + "\n",
                Files.readString(root.resolve(".pocketgit/refs/heads/feature/login")));
        String second = commit(root, "Update hello", "second\n");
        Files.writeString(root.resolve("hello.txt"), "unstaged work\n");
        Files.writeString(root.resolve("untracked.txt"), "private untracked work\n");
        Path nested = Files.createDirectories(root.resolve("src/deep"));
        var before = state(root);
        var log = success(nested, "log").out();
        assertTrue(log.indexOf("commit " + second) < log.indexOf("commit " + first));
        assertTrue(log.contains("Author: Jane 日本語 <jane@example.com>"));
        assertTrue(log.contains("Date:   "));
        assertTrue(log.contains("    Detailed body"));
        assertEquals(
                second.substring(0, 7)
                        + " Update hello\n"
                        + first.substring(0, 7)
                        + " Initial commit\n",
                success(nested, "log", "--oneline").out());
        var show = success(nested, "show", second.substring(0, 12)).out();
        assertTrue(show.startsWith("commit " + second + "\nTree:   "));
        assertTrue(show.contains("Parents: " + first));
        assertEquals(show, success(root, "show", second).out());
        assertTrue(success(root, "show", first.substring(0, 12)).out().contains("Parents: (none)"));
        assertEquals("  feature/login\n* main\n", success(nested, "branch").out());
        unchanged(root, before);
        success(nested, "branch", "alpha");
        assertEquals(second + "\n", Files.readString(root.resolve(".pocketgit/refs/heads/alpha")));
        assertEquals("  alpha\n  feature/login\n* main\n", success(root, "branch").out());
        assertEquals("ref: refs/heads/main\n", Files.readString(root.resolve(".pocketgit/HEAD")));
        assertEquals("unstaged work\n", Files.readString(root.resolve("hello.txt")));
        assertEquals("private untracked work\n", Files.readString(root.resolve("untracked.txt")));
        assertEquals(1, run(root, "checkout", "feature/login").code());
    }

    @Test
    void invalidCommandsAndCorruptHistoryFailWithoutPartialOutputOrMutations() throws Exception {
        Path root = initialize();
        String first = commit(root, "First", "first");
        String second = commit(root, "Second", "second");
        var before = state(root);
        for (String[] args :
                new String[][] {
                    {"branch", "main"},
                    {"branch", "../escape"},
                    {"show", "ABC"},
                    {"show", "a".repeat(64)}
                }) {
            var result = run(root, args);
            assertEquals(1, result.code());
            assertEquals("", result.out());
            assertTrue(result.err().startsWith("error: "));
            assertFalse(result.err().contains("\tat "));
        }
        for (String[] args :
                new String[][] {
                    {"show"},
                    {"show", "one", "two"},
                    {"log", "--unknown"},
                    {"log", "extra"},
                    {"branch", "one", "two"}
                }) {
            assertEquals(2, run(root, args).code());
        }
        for (String name : List.of("log", "show", "branch"))
            assertEquals(0, run(root, name, "--help").code());
        unchanged(root, before);
        Path parent =
                root.resolve(
                        ".pocketgit/objects/" + first.substring(0, 2) + "/" + first.substring(2));
        Files.delete(parent);
        before = state(root);
        var corrupt = run(root, "log");
        assertEquals(1, corrupt.code());
        assertEquals("", corrupt.out());
        assertTrue(corrupt.err().contains(first));
        assertFalse(corrupt.err().contains("\tat "));
        unchanged(root, before);
        assertEquals(second + "\n", Files.readString(root.resolve(".pocketgit/refs/heads/main")));
    }

    @Test
    void detachedHeadSupportsReadOnlyHistoryAndCreatingABranch() throws Exception {
        Path root = initialize();
        String first = commit(root, "First", "first");
        String second = commit(root, "Second", "second");
        Files.writeString(root.resolve(".pocketgit/HEAD"), first + "\n");
        var before = state(root);
        assertEquals(first.substring(0, 7) + " First\n", success(root, "log", "--oneline").out());
        assertEquals("  main\n", success(root, "branch").out());
        unchanged(root, before);
        success(root, "branch", "from-detached");
        assertEquals(
                first + "\n",
                Files.readString(root.resolve(".pocketgit/refs/heads/from-detached")));
        assertEquals(first + "\n", Files.readString(root.resolve(".pocketgit/HEAD")));
        assertEquals(second + "\n", Files.readString(root.resolve(".pocketgit/refs/heads/main")));
        assertEquals("second", Files.readString(root.resolve("hello.txt")));
    }
}
