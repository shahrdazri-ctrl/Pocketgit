package com.pocketgit.integration;

import com.pocketgit.repository.Repository;
import com.pocketgit.storage.IndexStore;
import com.pocketgit.storage.ObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StagingIT {
    @TempDir Path temp;
    private record Result(int code, byte[] output, String error) {}

    private Result run(Path cwd, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Dfile.encoding=UTF-8",
                "-jar", Path.of(System.getProperty("pocketgit.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Path out = temp.resolve("stdout"), err = temp.resolve("stderr");
        var process = new ProcessBuilder(command).directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "CLI must finish within 20 seconds");
        return new Result(process.exitValue(), Files.readAllBytes(out), Files.readString(err));
    }

    @Test void actualInitAddInspectModifyAndDeleteWorkflow() throws Exception {
        Path root = Files.createDirectory(temp.resolve("demo 日本語 space"));
        assertEquals(0, run(root, "init").code());
        byte[] payload = new byte[]{0, -1, -128, 13, 10};
        Files.write(root.resolve("binary.bin"), payload);
        Files.writeString(root.resolve("empty.txt"), "");
        Path nested = Files.createDirectories(root.resolve("src/main"));
        Files.writeString(nested.resolve("App.java"), "class App {}\n");
        assertEquals(0, run(nested, "add", ".").code());
        var repository = new Repository(root);
        var indexes = new IndexStore(repository);
        assertEquals(List.of("binary.bin", "empty.txt", "src/main/App.java"), indexes.load().entries().stream().map(e -> e.path()).toList());
        String binary = indexes.load().entries().stream().filter(e -> e.path().equals("binary.bin")).findFirst().orElseThrow().blobHash();
        assertArrayEquals(payload, run(nested, "cat-object", binary).output());
        Files.write(root.resolve("binary.bin"), new byte[]{9});
        Files.delete(root.resolve("empty.txt"));
        assertEquals(0, run(root, "add", ".").code());
        assertEquals(2, indexes.load().entries().size());
        for (var entry : indexes.load().entries()) {
            assertArrayEquals(Files.readAllBytes(root.resolve(entry.path())), new ObjectStore(repository).readBlob(entry.blobHash()).content());
        }
        assertFalse(Files.exists(root.resolve(".pocketgit/index.lock")));
        assertEquals("ref: refs/heads/main\n", Files.readString(root.resolve(".pocketgit/HEAD")));
        assertEquals(0, Files.size(root.resolve(".pocketgit/refs/heads/main")));
    }

    @Test void realCliAppliesIgnoresAndScopesDeletedEntries() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo"));
        run(root, "init");
        Files.writeString(root.resolve(".pocketgitignore"), "target/\n*.log\n.env\n");
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("target/output"), "skip");
        Files.writeString(root.resolve("error.log"), "skip");
        Files.writeString(root.resolve(".env"), "skip");
        Files.writeString(root.resolve("keep"), "keep");
        assertEquals(0, run(root, "add", ".").code());
        var indexes = new IndexStore(new Repository(root));
        assertEquals(List.of(".pocketgitignore", "keep"), indexes.load().entries().stream().map(e -> e.path()).toList());
        Files.delete(root.resolve("keep"));
        assertEquals(0, run(root, "add", "keep").code());
        assertEquals(List.of(".pocketgitignore"), indexes.load().entries().stream().map(e -> e.path()).toList());
    }

    @Test void realCliRejectsTraversalCorruptionAndLockWithoutChangingIndex() throws Exception {
        Path root = Files.createDirectory(temp.resolve("repo"));
        run(root, "init");
        Files.writeString(root.resolve("file"), "content");
        byte[] before = Files.readAllBytes(root.resolve(".pocketgit/index"));
        Result escape = run(root, "add", "../outside");
        assertEquals(1, escape.code());
        assertTrue(escape.error().startsWith("error: "));
        assertFalse(escape.error().contains("\tat "));
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".pocketgit/index")));
        Files.writeString(root.resolve(".pocketgit/index.lock"), "busy");
        assertEquals(1, run(root, "add", "file").code());
        assertEquals("busy", Files.readString(root.resolve(".pocketgit/index.lock")));
        Files.delete(root.resolve(".pocketgit/index.lock"));
        Files.writeString(root.resolve(".pocketgit/index"), "broken");
        Result corrupt = run(root, "add", "file");
        assertEquals(1, corrupt.code());
        assertEquals("broken", Files.readString(root.resolve(".pocketgit/index")));
        assertFalse(Files.exists(root.resolve(".pocketgit/index.lock")));
    }
}
