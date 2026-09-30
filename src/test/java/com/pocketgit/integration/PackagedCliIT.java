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
        return run(cwd, List.of(), args);
    }

    private Result run(Path cwd, List<String> vmOptions, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8"));
        command.addAll(vmOptions);
        command.addAll(List.of("-jar", Path.of(System.getProperty("pocketgit.jar")).toAbsolutePath().toString()));
        command.addAll(CliArguments.portable(temp, args));
        Path output = temp.resolve("process-output.txt");
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "CLI must finish within 20 seconds");
        return new Result(process.exitValue(), Files.readString(output));
    }

    @Test void packagedJarSupportsHelpVersionAndCleanRepositoryErrors() throws Exception {
        Result help = run(temp, "--help");
        assertEquals(0, help.code());
        assertTrue(help.output().contains("Usage: pocketgit"), help.output());
        assertTrue(help.output().contains("restore"));
        assertEquals("PocketGit 1.0.0", run(temp, "--version").output().strip());
        Result placeholder = run(temp, "diff");
        assertEquals(1, placeholder.code());
        assertTrue(placeholder.output().contains("not a PocketGit repository"));
        assertFalse(Files.exists(temp.resolve(".pocketgit")));
    }

    @Test void redirectedTextIsUtf8AndPlainEvenWithLegacyEncodingAndAnsiEnabled() throws Exception {
        var legacy = List.of("-Dsun.stdout.encoding=US-ASCII", "-Dsun.stderr.encoding=US-ASCII", "-Dpicocli.ansi=true");
        assertEquals(0, run(temp, legacy, "init").code());
        assertEquals(0, run(temp, legacy, "config", "user.name", "Jane 日本語").code());
        assertTrue(Files.readString(temp.resolve(".pocketgit/config")).contains("Jane 日本語"));
        assertEquals("Jane 日本語", run(temp, legacy, "config", "user.name").output().strip());
        Result help = run(temp, legacy, "--help");
        assertEquals(0, help.code());
        assertTrue(help.output().contains("Usage: pocketgit"));
        assertFalse(help.output().contains("\u001b"));
        Result error = run(temp, legacy, "add", "missing-日本語");
        assertEquals(1, error.code());
        assertTrue(error.output().contains("日本語"), error.output());
        assertFalse(error.output().contains("\u001b"));
        assertEquals(0, run(temp, legacy, "config", "user.email", "jane@example.com").code());
        String message = "Subject 日本語\n\nBody with a \\\\ and \"quote\"";
        assertEquals(0, run(temp, legacy, "commit", "--allow-empty", "-m", message).code());
        Result history = run(temp, legacy, "log");
        assertEquals(0, history.code());
        assertTrue(history.output().contains("Subject 日本語"));
        assertTrue(history.output().contains("Body with a \\\\ and \"quote\""));
    }

    @Test void packagedGuiHelpAndDefaultArtifactGuidanceAreClean() throws Exception {
        assertEquals(0,run(temp,"gui","--help").code());
        assertEquals(1,run(temp,"gui").code());
        try (var jar=new java.util.jar.JarFile(System.getProperty("pocketgit.jar"))) {
            if(jar.getEntry("com/pocketgit/gui/GuiLauncher.class")==null) {
                Path root=Files.createDirectory(temp.resolve("gui-repo"));assertEquals(0,run(root,"init").code());
                var missing=run(root,"gui");assertEquals(1,missing.code());assertTrue(missing.output().contains("not included"));assertFalse(missing.output().contains("\tat "));
            }
        }
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
