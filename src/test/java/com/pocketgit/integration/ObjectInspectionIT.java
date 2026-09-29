package com.pocketgit.integration;

import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.ObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ObjectInspectionIT {
    @TempDir Path temp;
    private record Result(int code, byte[] stdout, String stderr) {}

    private Result run(Path cwd, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Dfile.encoding=UTF-8",
                "-jar", Path.of(System.getProperty("pocketgit.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Path out = temp.resolve("stdout"), err = temp.resolve("stderr");
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectOutput(out.toFile())
                .redirectError(err.toFile()).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "CLI must finish in 20 seconds");
        return new Result(process.exitValue(), Files.readAllBytes(out), Files.readString(err));
    }

    @Test void jarReadsBinaryExactlyAfterStoreRestartFromNestedDirectory() throws Exception {
        var repo = new RepositoryInitializer().initialize(temp).repository();
        var store = new ObjectStore(repo);
        byte[] payload = new byte[]{0, -1, -128, 13, 10, 65};
        String hash = store.write(ObjectType.BLOB, payload);
        Path nested = Files.createDirectories(temp.resolve("nested/directory"));
        Result result = run(nested, "cat-object", hash);
        assertEquals(0, result.code(), result.stderr());
        assertArrayEquals(payload, result.stdout());
        assertTrue(result.stderr().isEmpty());
        assertEquals("blob", new String(run(nested, "cat-object", "--type", hash).stdout()).strip());
        assertEquals("6", new String(run(nested, "cat-object", "--size", hash).stdout()).strip());
    }

    @Test void jarRejectsCorruptionBeforeWritingPayload() throws Exception {
        var store = new ObjectStore(new RepositoryInitializer().initialize(temp).repository());
        String hash = store.write(ObjectType.BLOB, new byte[]{1, 2, 3});
        Files.write(store.pathForHash(hash), new byte[]{1, 2, 3});
        Result result = run(temp, "cat-object", hash);
        assertEquals(1, result.code());
        assertEquals(0, result.stdout().length);
        assertTrue(result.stderr().startsWith("error: "));
        assertFalse(result.stderr().contains("\tat "));
    }

    @Test void jarReportsModesAndBinaryPrettyErrors() throws Exception {
        var store = new ObjectStore(new RepositoryInitializer().initialize(temp).repository());
        String binary = store.write(ObjectType.BLOB, new byte[]{0});
        Result result = run(temp, "cat-object", "--pretty", binary);
        assertEquals(1, result.code());
        assertEquals(0, result.stdout().length);
        assertTrue(result.stderr().contains("binary payload"));
        assertEquals(2, run(temp, "cat-object", "--type", "--size", binary).code());
        String text = store.write(ObjectType.BLOB, "no newline".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Result pretty = run(temp, "cat-object", "--pretty", text);
        assertEquals(0, pretty.code());
        assertArrayEquals("no newline".getBytes(java.nio.charset.StandardCharsets.UTF_8), pretty.stdout());
    }
}
