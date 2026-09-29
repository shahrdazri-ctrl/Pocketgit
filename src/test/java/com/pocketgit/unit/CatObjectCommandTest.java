package com.pocketgit.unit;

import com.pocketgit.PocketGit;
import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.ObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatObjectCommandTest {
    @TempDir Path temp;
    private ObjectStore store;
    private record Result(int code, String text, String error, byte[] raw) {}

    @BeforeEach void initialize() throws Exception {
        store = new ObjectStore(new RepositoryInitializer().initialize(temp).repository());
    }

    private Result execute(Path cwd, String... args) {
        var raw = new ByteArrayOutputStream();
        var text = new StringWriter();
        var error = new StringWriter();
        var command = PocketGit.commandLine(cwd, raw);
        command.setOut(new PrintWriter(text, true));
        command.setErr(new PrintWriter(error, true));
        return new Result(command.execute(args), text.toString(), error.toString(), raw.toByteArray());
    }

    @Test void printsTypeByteSizeAndUtf8PayloadFromNestedDirectory() throws Exception {
        byte[] payload = "Hello 日本語\n".getBytes(StandardCharsets.UTF_8);
        String hash = store.write(ObjectType.BLOB, payload);
        Path nested = Files.createDirectories(temp.resolve("src/main/java"));
        assertEquals("blob", execute(nested, "cat-object", "--type", hash).text().strip());
        assertEquals(Integer.toString(payload.length), execute(nested, "cat-object", "--size", hash).text().strip());
        Result pretty = execute(nested, "cat-object", "--pretty", hash);
        assertEquals(0, pretty.code());
        assertEquals(new String(payload, StandardCharsets.UTF_8), pretty.text());
        assertEquals(0, pretty.raw().length);
    }

    @Test void rawOutputIsExactForBinaryAndHasNoAddedNewline() throws Exception {
        byte[] payload = {0, -1, 13, 10, -128};
        String hash = store.write(ObjectType.BLOB, payload);
        Result raw = execute(temp, "cat-object", hash);
        assertEquals(0, raw.code());
        assertArrayEquals(payload, raw.raw());
        assertTrue(raw.text().isEmpty());
        assertTrue(raw.error().isEmpty());
        String empty = store.write(ObjectType.BLOB, new byte[0]);
        assertEquals(0, execute(temp, "cat-object", empty).raw().length);
    }

    @Test void rejectsBinaryPrettyButAllowsBinaryMetadata() throws Exception {
        for (byte[] payload : new byte[][]{{0}, {-1}, {(byte) 0xc3, 0x28}}) {
            String hash = store.write(ObjectType.BLOB, payload);
            Result pretty = execute(temp, "cat-object", "--pretty", hash);
            assertEquals(1, pretty.code());
            assertTrue(pretty.error().contains("binary payload"));
            assertTrue(pretty.text().isEmpty());
            assertEquals(0, pretty.raw().length);
            assertEquals(0, execute(temp, "cat-object", "--type", hash).code());
        }
    }

    @Test void validatesArgumentsAndModes() {
        assertEquals(2, execute(temp, "cat-object").code());
        assertEquals(2, execute(temp, "cat-object", "--type", "--size", "0".repeat(64)).code());
        assertEquals(2, execute(temp, "cat-object", "--bad", "0".repeat(64)).code());
        assertEquals(1, execute(temp, "cat-object", "../outside").code());
    }

    @Test void missingCorruptAndOutsideRepositoriesHaveCleanErrors() throws Exception {
        Result missing = execute(temp, "cat-object", "0".repeat(64));
        assertEquals(1, missing.code());
        assertTrue(missing.error().contains("object not found"));
        Path outside = Files.createTempDirectory("pocketgit-outside-");
        try {
            Result result = execute(outside, "cat-object", "0".repeat(64));
            assertEquals(1, result.code());
            assertTrue(result.error().contains("not a PocketGit repository"));
        } finally { Files.delete(outside); }
        String hash = store.write(ObjectType.BLOB, new byte[]{1, 2});
        Files.write(store.pathForHash(hash), new byte[]{0, 0, 0});
        Result corrupt = execute(temp, "cat-object", "--type", hash);
        assertEquals(1, corrupt.code());
        assertTrue(corrupt.error().startsWith("error: "));
        assertFalse(corrupt.error().contains("\tat "));
        assertEquals(0, corrupt.raw().length);
    }

    @Test void inspectionDoesNotModifyIndexRefsOrObjects() throws Exception {
        byte[] payload = {1, 2};
        String hash = store.write(ObjectType.TREE, payload);
        byte[] objectBefore = Files.readAllBytes(store.pathForHash(hash));
        byte[] indexBefore = Files.readAllBytes(temp.resolve(".pocketgit/index"));
        assertEquals(0, execute(temp, "cat-object", "--type", hash).code());
        assertArrayEquals(objectBefore, Files.readAllBytes(store.pathForHash(hash)));
        assertArrayEquals(indexBefore, Files.readAllBytes(temp.resolve(".pocketgit/index")));
        assertEquals("ref: refs/heads/main\n", Files.readString(temp.resolve(".pocketgit/HEAD")));
        assertEquals(0, Files.size(temp.resolve(".pocketgit/refs/heads/main")));
    }
}
