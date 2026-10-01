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

class StatusIT {
    @TempDir Path temp;

    private record Result(int code, String output, String error) {}

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
        command.addAll(List.of(args));
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
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private void success(Path root, String... args) throws Exception {
        var result = run(root, args);
        assertEquals(0, result.code(), result.error());
    }

    @Test
    void actualCliReportsCategoriesAcrossInitAddCommitAndLaterEdits() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo 日本語 space"));
        success(root, "init");
        assertTrue(run(root, "status").output().contains("No commits yet"));
        Files.writeString(root.resolve("base"), "original");
        Files.writeString(root.resolve("deleted"), "original");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/new"), "staged");
        success(root, "add", ".");
        assertTrue(run(root, "status").output().contains("new file:   base"));
        success(root, "config", "user.name", "Status Reviewer");
        success(root, "config", "user.email", "status@example.com");
        success(root, "commit", "-m", "base");
        assertEquals(
                "On branch main\nnothing to commit, working tree clean\n",
                run(root, "status").output());
        Files.writeString(root.resolve("base"), "staged edit");
        success(root, "add", "base");
        Files.writeString(root.resolve("base"), "unstaged edit");
        Files.delete(root.resolve("deleted"));
        success(root, "add", "deleted");
        Files.delete(root.resolve("src/new"));
        Files.writeString(root.resolve("scratch"), "untracked");
        Path extra = Files.writeString(root.resolve("added"), "new");
        success(root, "add", "added");
        Files.writeString(extra, "changed after staging");
        var result = run(root.resolve("src"), "status");
        assertEquals(0, result.code(), result.error());
        assertEquals(
                "On branch main\n\n"
                        + "Changes to be committed:\n"
                        + "  new file:   added\n"
                        + "  modified:   base\n"
                        + "  deleted:    deleted\n\n"
                        + "Changes not staged:\n"
                        + "  modified:   added\n"
                        + "  modified:   base\n"
                        + "  deleted:    src/new\n\n"
                        + "Untracked files:\n"
                        + "  scratch\n",
                result.output());
        assertFalse(result.output().contains("\u001b"));
        assertEquals("", result.error());
    }

    @Test
    void actualIgnoredFlagIsReadOnlyAndDefaultOutputSuppressesIgnoredPaths() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo"));
        success(root, "init");
        Files.writeString(root.resolve(".pocketgitignore"), "cache/\n*.log\n");
        success(root, "add", ".pocketgitignore");
        success(root, "config", "user.name", "Reviewer");
        success(root, "config", "user.email", "reviewer@example.com");
        success(root, "commit", "-m", "base");
        Files.createDirectory(root.resolve("cache"));
        Files.writeString(root.resolve("cache/private"), "ignored");
        Files.writeString(root.resolve("error.log"), "ignored");
        var before = metadata(root);
        assertEquals(
                "On branch main\nnothing to commit, working tree clean\n",
                run(root, "status").output());
        var ignored = run(root, "status", "--ignored");
        assertEquals(0, ignored.code(), ignored.error());
        assertTrue(ignored.output().contains("Ignored files:\n  cache/\n  error.log"));
        assertTrue(ignored.output().contains("working tree clean"));
        var after = metadata(root);
        assertEquals(before.keySet(), after.keySet());
        for (String path : before.keySet())
            assertArrayEquals(before.get(path), after.get(path), path);
    }

    @Test
    void actualCliErrorsAreCleanAndDoNotPrintPartialStatus() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo"));
        assertEquals(1, run(root, "status").code());
        assertEquals(0, run(root, "status", "--help").code());
        assertEquals(2, run(root, "status", "--unknown").code());
        assertEquals(2, run(root, "status", "path").code());
        success(root, "init");
        Files.writeString(root.resolve(".pocketgit/index"), "broken");
        var corrupt = run(root, "status");
        assertEquals(1, corrupt.code());
        assertEquals("", corrupt.output());
        assertTrue(corrupt.error().startsWith("error: "));
        assertFalse(corrupt.error().contains("\tat "));
        assertEquals("broken", Files.readString(root.resolve(".pocketgit/index")));
    }

    private TreeMap<String, byte[]> metadata(Path root) throws Exception {
        var result = new TreeMap<String, byte[]>();
        Path metadata = root.resolve(".pocketgit");
        try (var paths = Files.walk(metadata)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                result.put(metadata.relativize(path).toString(), Files.readAllBytes(path));
        }
        return result;
    }
}
