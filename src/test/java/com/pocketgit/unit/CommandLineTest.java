package com.pocketgit.unit;

import com.pocketgit.PocketGit;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommandLineTest {
    @TempDir Path temp;

    private record Result(int code, String out, String err) {}
    private Result execute(String... args) {
        var cli = PocketGit.commandLine(temp);
        var out = new StringWriter();
        var err = new StringWriter();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        int code = cli.execute(args);
        return new Result(code, out.toString(), err.toString());
    }

    @Test void helpAndNoArgumentsListAllPlannedCommandsWithoutSideEffects() {
        for (String[] args : new String[][]{{}, {"--help"}}) {
            Result result = execute(args);
            assertEquals(0, result.code());
            for (String name : new String[]{"init", "status", "add", "commit", "log", "diff", "branch", "checkout", "restore"}) {
                assertTrue(result.out().contains(name));
            }
            assertFalse(Files.exists(temp.resolve(".pocketgit")));
        }
    }

    @Test void versionIsCorrect() { assertEquals("PocketGit 0.1.0", execute("--version").out().strip()); }

    @Test void initAndRepeatHaveDistinctHonestOutput() {
        assertTrue(execute("init").out().startsWith("Initialized empty PocketGit repository in "));
        assertTrue(execute("init").out().startsWith("PocketGit repository already exists at "));
    }

    @Test void initResolvesOptionalDirectoryRelativeToWorkingDirectory() throws Exception {
        Path target = Files.createDirectory(temp.resolve("project with spaces"));
        Result result = execute("init", "project with spaces");
        assertEquals(0, result.code());
        assertTrue(Files.exists(target.resolve(".pocketgit/HEAD")));
        assertFalse(Files.exists(temp.resolve(".pocketgit")));
    }

    @Test void futureCommandsReportNotImplementedAndDoNotWriteAnything() {
        for (String name : new String[]{"status", "log", "diff", "branch", "checkout", "restore"}) {
            Result result = execute(name);
            assertEquals(3, result.code(), name);
            assertEquals("Not implemented yet.", result.err().strip());
            assertTrue(result.out().isEmpty());
        }
        assertEquals(3, execute("log", "--oneline").code());
        assertFalse(Files.exists(temp.resolve(".pocketgit")));
    }

    @Test void invalidArgumentsReturnUsageErrorWithoutMutation() {
        assertEquals(2, execute("unknown").code());
        assertEquals(2, execute("init", "--unknown").code());
        assertEquals(2, execute("init", "one", "two").code());
        assertFalse(Files.exists(temp.resolve(".pocketgit")));
    }

    @Test void ioFailuresAreCleanAndActionable() throws Exception {
        Files.writeString(temp.resolve(".pocketgit"), "keep me");
        Result result = execute("init");
        assertEquals(1, result.code());
        assertTrue(result.err().startsWith("error: "));
        assertFalse(result.err().contains("\tat "));
        assertEquals("keep me", Files.readString(temp.resolve(".pocketgit")));
    }

    @Test void addHasHelpArgumentValidationAndCleanErrors() throws Exception {
        assertEquals(0, execute("add", "--help").code());
        assertEquals(2, execute("add").code());
        assertEquals(2, execute("add", "one", "two").code());
        assertEquals(2, execute("add", "--unknown").code());
        assertEquals(1, execute("add", "").code());
        Result outside = execute("add", "file");
        assertEquals(1, outside.code());
        assertTrue(outside.err().contains("not a PocketGit repository"));
        execute("init");
        Files.writeString(temp.resolve("file"), "hello");
        Result result = execute("add", "file");
        assertEquals(0, result.code());
        assertEquals("Staged file", result.out().strip());
        Files.writeString(temp.resolve("-notes"), "flag-like filename");
        assertEquals(0, execute("add", "--", "-notes").code());
    }
}
