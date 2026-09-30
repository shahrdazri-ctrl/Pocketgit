package com.pocketgit.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketgit.model.Index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** The supported maximum Blob must not require multiple whole-file heap allocations. */
class LargeFileIT {
    @TempDir Path temporary;

    private void cli(Path root, Path output, String... args) throws Exception {
        cli(root, output, 0, args);
    }

    private String cli(Path root, Path output, int expectedExit, String... args) throws Exception {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", binary).toString(),
                                "-Xmx96m",
                                "-jar",
                                Path.of(System.getProperty("pocketgit.jar"))
                                        .toAbsolutePath()
                                        .toString()));
        command.addAll(List.of(args));
        Path error = temporary.resolve("error.txt");
        var builder =
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectOutput(output.toFile())
                        .redirectError(error.toFile());
        builder.environment().remove("POCKETGIT_AUTHOR_NAME");
        builder.environment().remove("POCKETGIT_AUTHOR_EMAIL");
        Process process = builder.start();
        boolean completed = process.waitFor(90, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertTrue(completed, "large-file CLI must complete: " + Arrays.toString(args));
        String diagnostic = Files.readString(error);
        assertEquals(expectedExit, process.exitValue(), diagnostic);
        assertFalse(diagnostic.contains("OutOfMemoryError"), diagnostic);
        if (expectedExit == 0) assertEquals("", diagnostic);
        else assertEquals("", Files.readString(output), "failed validation must emit no result");
        return diagnostic;
    }

    @Test
    void maximumSizedSnapshotRoundTripAndInspectionWorkIn96MiBHeap() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repo"));
        Path output = temporary.resolve("output.txt");
        cli(root, output, "init");
        cli(root, output, "config", "user.name", "Audit");
        cli(root, output, "config", "user.email", "audit@example.com");
        Path file = root.resolve("large.bin");
        try (var channel =
                java.nio.channels.FileChannel.open(
                        file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(64L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {42}));
        }
        cli(root, output, "add", ".");
        // Reuse of the existing immutable Blob must also remain bounded.
        cli(root, output, "add", ".");
        cli(root, output, "commit", "-m", "Large snapshot");
        cli(root, output, "branch", "saved");
        var index =
                new ObjectMapper()
                        .readValue(
                                Files.readAllBytes(root.resolve(".pocketgit/index")), Index.class);
        String hash = index.entries().getFirst().blobHash();
        Path original = temporary.resolve("original.bin");
        Files.copy(file, original);
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.position(64L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {43}));
        }
        cli(root, output, "diff");
        assertTrue(Files.readString(output).contains("Binary files differ: large.bin"));
        cli(root, output, "restore", "large.bin");
        assertEquals(-1, Files.mismatch(original, file));
        Files.delete(file);
        cli(root, output, "add", ".");
        cli(root, output, "commit", "-m", "Remove large snapshot");
        cli(root, output, "checkout", "saved");
        assertEquals(-1, Files.mismatch(original, file));
        cli(root, output, "status");
        assertTrue(Files.readString(output).contains("working tree clean"));
        cli(root, output, "verify");
        assertTrue(Files.readString(output).contains("Repository OK"));
        cli(root, output, "cat-object", "--size", hash);
        assertEquals("67108864", Files.readString(output).strip());
        Path extracted = temporary.resolve("extracted.bin");
        cli(root, extracted, "cat-object", hash);
        assertEquals(-1, Files.mismatch(original, extracted));
        assertTrue(cli(root, output, 1, "cat-object", "--pretty", hash).contains("pretty"));
        assertTrue(cli(root, output, 1, "show", hash.substring(0, 12)).contains("expected commit"));
        Files.writeString(root.resolve(".pocketgit/refs/heads/saved"), hash + "\n");
        for (String command : List.of("log", "status")) {
            assertTrue(cli(root, output, 1, command).contains("expected commit"));
        }
        assertEquals(-1, Files.mismatch(original, file));
        try (var paths = Files.list(root.resolve(".pocketgit"))) {
            assertFalse(
                    paths.anyMatch(
                            path ->
                                    path.getFileName().toString().startsWith("edit-")
                                            || path.getFileName().toString().endsWith(".lock")));
        }
    }
}
