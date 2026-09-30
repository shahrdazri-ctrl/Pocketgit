package com.pocketgit.integration;

import com.pocketgit.repository.RepositoryLocator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the shaded JAR in real child processes after Maven's package phase. */
class PackagedCliIT {
    @TempDir Path temp;
    private record Result(int code, String output) {}

    private Result run(Path cwd, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8", "-jar", Path.of(System.getProperty("pocketgit.jar")).toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Path output = temp.resolve("process-output.txt");
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "CLI must finish within 20 seconds");
        return new Result(process.exitValue(), Files.readString(output));
    }

    @Test void packagedJarSupportsHelpVersionAndPlaceholder() throws Exception {
        Result help = run(temp, "--help");
        assertEquals(0, help.code());
        assertTrue(help.output().contains("Usage: pocketgit"));
        assertTrue(help.output().contains("restore"));
        assertEquals("PocketGit 0.1.0", run(temp, "--version").output().strip());
        Result placeholder = run(temp, "log");
        assertEquals(3, placeholder.code());
        assertEquals("Not implemented yet.", placeholder.output().strip());
        assertFalse(Files.exists(temp.resolve(".pocketgit")));
    }

    @Test void realInitWorkflowPreservesMetadataAndFindsNestedRepository() throws Exception {
        Path root = Files.createDirectory(temp.resolve("demo space 日本語"));
        Files.writeString(root.resolve("notes.txt"), "do not modify");
        Result first = run(root, "init");
        assertEquals(0, first.code(), first.output());
        assertTrue(first.output().startsWith("Initialized empty PocketGit repository in "));
        assertEquals("ref: refs/heads/main\n", Files.readString(root.resolve(".pocketgit/HEAD")));
        byte[] index = Files.readAllBytes(root.resolve(".pocketgit/index"));
        Result second = run(root, "init");
        assertEquals(0, second.code());
        assertTrue(second.output().startsWith("PocketGit repository already exists at "));
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".pocketgit/index")));
        assertEquals("do not modify", Files.readString(root.resolve("notes.txt")));
        Path nested = Files.createDirectories(root.resolve("src/main/java"));
        assertEquals(root.toRealPath(), new RepositoryLocator().findRepositoryRoot(nested).orElseThrow());
    }

    @Test void realProcessReportsInvalidMetadataWithoutStackTrace() throws Exception {
        Files.writeString(temp.resolve(".pocketgit"), "preserve");
        Result result = run(temp, "init");
        assertEquals(1, result.code());
        assertTrue(result.output().startsWith("error: "));
        assertFalse(result.output().contains("\tat "));
        assertEquals("preserve", Files.readString(temp.resolve(".pocketgit")));
    }
}
