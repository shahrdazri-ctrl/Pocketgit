package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.PocketGit;
import com.pocketgit.cli.StatusFormatter;
import com.pocketgit.model.RepositoryStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusOutputTest {
    @TempDir Path temp;

    private record Result(int code, String out, String err) {}

    private Result run(String... args) {
        var cli = PocketGit.commandLine(temp);
        var out = new StringWriter();
        var err = new StringWriter();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return new Result(cli.execute(args), out.toString(), err.toString());
    }

    private RepositoryStatus empty(boolean hasCommits) {
        return new RepositoryStatus(
                "main",
                hasCommits,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    void cleanAndUnbornOutputMatchesSpecification() {
        var formatter = new StatusFormatter();
        assertEquals(
                "On branch main\nnothing to commit, working tree clean\n",
                formatter.format(empty(true), false));
        assertEquals(
                "On branch main\nNo commits yet\nnothing to commit, working tree clean\n",
                formatter.format(empty(false), false));
    }

    @Test
    void outputShowsBothComparisonsAndSortsEachSectionWithoutAnsi() {
        var status =
                new RepositoryStatus(
                        "feature/work",
                        true,
                        List.of("z", "a"),
                        List.of("b"),
                        List.of("c"),
                        List.of("z"),
                        List.of("a"),
                        List.of("untracked"),
                        List.of("cache/", "error.log"));
        String expected =
                "On branch feature/work\n\n"
                        + "Changes to be committed:\n"
                        + "  new file:   a\n"
                        + "  modified:   b\n"
                        + "  deleted:    c\n"
                        + "  new file:   z\n\n"
                        + "Changes not staged:\n"
                        + "  deleted:    a\n"
                        + "  modified:   z\n\n"
                        + "Untracked files:\n"
                        + "  untracked\n";
        assertEquals(expected, new StatusFormatter().format(status, false));
        assertEquals(
                expected + "\nIgnored files:\n  cache/\n  error.log\n",
                new StatusFormatter().format(status, true));
        assertFalse(expected.contains("\u001b"));
    }

    @Test
    void ignoredPathsAreOptionalAndDoNotAffectCleanState() {
        var status =
                new RepositoryStatus(
                        "main",
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("cache/"));
        assertTrue(status.isClean());
        assertEquals(
                "On branch main\nnothing to commit, working tree clean\n",
                new StatusFormatter().format(status, false));
        assertEquals(
                "On branch main\n\n"
                        + "Ignored files:\n"
                        + "  cache/\n\n"
                        + "nothing to commit, working tree clean\n",
                new StatusFormatter().format(status, true));
    }

    @Test
    void domainListsAreImmutableValidatedAndSorted() {
        var input = new ArrayList<>(List.of("z", "a"));
        var status =
                new RepositoryStatus(
                        "main",
                        false,
                        input,
                        List.of(),
                        List.of(),
                        List.of("a"),
                        List.of(),
                        List.of(),
                        List.of());
        input.clear();
        assertEquals(List.of("a", "z"), status.stagedNew());
        assertThrows(UnsupportedOperationException.class, () -> status.stagedNew().clear());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepositoryStatus(
                                "main",
                                true,
                                List.of("../outside"),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepositoryStatus(
                                "main",
                                true,
                                List.of("a", "a"),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepositoryStatus(
                                "main",
                                true,
                                List.of("a"),
                                List.of("a"),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepositoryStatus(
                                "main",
                                true,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of("a"),
                                List.of("a"),
                                List.of(),
                                List.of()));
    }

    @Test
    void statusHasHelpStrictArgumentsAndCleanRepositoryErrors() throws Exception {
        assertEquals(0, run("status", "--help").code());
        assertFalse(Files.exists(temp.resolve(".pocketgit")));
        assertEquals(2, run("status", "--unknown").code());
        assertEquals(2, run("status", "path").code());
        Result outside = run("status");
        assertEquals(1, outside.code());
        assertEquals("", outside.out());
        assertTrue(outside.err().contains("not a PocketGit repository"));
        assertFalse(outside.err().contains("\tat "));
        assertEquals(0, run("init").code());
        Files.writeString(temp.resolve("a"), "hello");
        Result unborn = run("status");
        assertEquals(0, unborn.code());
        assertTrue(unborn.out().contains("No commits yet"));
        assertTrue(unborn.out().contains("Untracked files:"));
        Files.writeString(temp.resolve(".pocketgit/index"), "broken");
        Result corrupt = run("status");
        assertEquals(1, corrupt.code());
        assertEquals("", corrupt.out());
        assertTrue(corrupt.err().startsWith("error: "));
    }
}
