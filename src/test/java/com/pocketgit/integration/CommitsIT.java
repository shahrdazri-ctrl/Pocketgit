package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.repository.Repository;
import com.pocketgit.services.TreeReader;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitsIT {
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
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private void configure(Path root) throws Exception {
        assertEquals(0, run(root, "config", "user.name", "Jane 日本語").code());
        assertEquals(0, run(root, "config", "user.email", "jane@example.com").code());
    }

    @Test
    void twoCommitsSurviveRestartAndCatObjectShowsStoredSnapshot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo 日本語 space"));
        assertEquals(0, run(root, "init").code());
        configure(root);
        assertEquals("Jane 日本語\n", run(root, "config", "user.name").output().replace("\r\n", "\n"));
        Path nested = Files.createDirectories(root.resolve("src/main"));
        Files.writeString(nested.resolve("a"), "staged");
        assertEquals(0, run(nested, "add", ".").code());
        Files.writeString(nested.resolve("a"), "unstaged");
        Result first = run(nested, "commit", "-m", "First\n\nbody");
        assertEquals(0, first.code(), first.error());
        assertTrue(first.output().contains("[main "));
        var repo = new Repository(root);
        var objects = new ObjectStore(repo);
        var codec = new ObjectCodec();
        String firstHash = Files.readString(repo.mainRefFile()).strip();
        var firstCommit = codec.decodeCommit(objects.read(firstHash));
        assertEquals(List.of(), firstCommit.parentHashes());
        assertEquals("First\n\nbody", firstCommit.message());
        var snapshot = new TreeReader(objects).readSnapshot(firstCommit.treeHash());
        assertEquals(
                "staged",
                new String(
                        objects.readBlob(snapshot.entries().getFirst().blobHash()).content(),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(
                run(root, "cat-object", "--pretty", firstHash)
                        .output()
                        .contains("jane@example.com"));
        assertEquals("commit", run(root, "cat-object", "--type", firstHash).output().strip());
        assertEquals(0, run(root, "add", ".").code());
        assertEquals(0, run(root, "commit", "-m", "Second").code());
        String secondHash = Files.readString(repo.mainRefFile()).strip();
        assertEquals(
                List.of(firstHash), codec.decodeCommit(objects.read(secondHash)).parentHashes());
        byte[] log = Files.readAllBytes(repo.logsDirectory().resolve("HEAD"));
        assertEquals("Nothing to commit.", run(root, "commit", "-m", "unchanged").output().strip());
        assertEquals(secondHash, Files.readString(repo.mainRefFile()).strip());
        assertArrayEquals(log, Files.readAllBytes(repo.logsDirectory().resolve("HEAD")));
        assertEquals(0, run(root, "commit", "--allow-empty", "-m", "empty").code());
        assertEquals(
                List.of(secondHash),
                codec.decodeCommit(objects.read(Files.readString(repo.mainRefFile()).strip()))
                        .parentHashes());
        assertEquals(3, Files.readAllLines(repo.logsDirectory().resolve("HEAD")).size());
    }

    @Test
    void missingIdentityAndMalformedMessagesReturnCleanErrorsWithoutPublishing() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo"));
        run(root, "init");
        Files.writeString(root.resolve("a"), "hello");
        run(root, "add", "a");
        Result missing = run(root, "commit", "-m", "first");
        assertEquals(1, missing.code());
        assertTrue(missing.error().contains("config user.name"));
        assertFalse(missing.error().contains("\tat "));
        assertEquals("", Files.readString(root.resolve(".pocketgit/refs/heads/main")));
        assertEquals(2, run(root, "commit").code());
        configure(root);
        assertEquals(1, run(root, "commit", "-m", " ").code());
        assertEquals(1, run(root, "config", "unsupported", "value").code());
        assertEquals(1, run(root, "config", "user.email", "invalid").code());
        assertEquals(0, run(root, "commit", "-m", "first").code());
    }
}
