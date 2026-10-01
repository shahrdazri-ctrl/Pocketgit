package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class CliProcess {
    record Result(int code, String out, String err) {}

    private final Path temporary;

    CliProcess(Path temporary) {
        this.temporary = temporary;
    }

    Result run(Path cwd, String... args) throws Exception {
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(
                                                System.getProperty("java.home"),
                                                "bin",
                                                System.getProperty("os.name").startsWith("Windows")
                                                        ? "java.exe"
                                                        : "java")
                                        .toString(),
                                "-jar",
                                Path.of(System.getProperty("pocketgit.jar"))
                                        .toAbsolutePath()
                                        .toString()));
        command.addAll(List.of(args));
        Path out = temporary.resolve("cli-out"), err = temporary.resolve("cli-err");
        var builder =
                new ProcessBuilder(command)
                        .directory(cwd.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().remove("POCKETGIT_AUTHOR_NAME");
        builder.environment().remove("POCKETGIT_AUTHOR_EMAIL");
        var process = builder.start();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        assertTrue(done, "CLI must finish");
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    Result success(Path cwd, String... args) throws Exception {
        var result = run(cwd, args);
        assertEquals(0, result.code(), result.err());
        assertEquals("", result.err());
        return result;
    }

    Path initialize() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repo"));
        success(root, "init");
        success(root, "config", "user.name", "Reviewer");
        success(root, "config", "user.email", "reviewer@example.com");
        return root;
    }
}
