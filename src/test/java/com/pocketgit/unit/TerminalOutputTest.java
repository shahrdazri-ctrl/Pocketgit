package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.cli.CommitFormatter;
import com.pocketgit.diff.DiffEngine;
import com.pocketgit.diff.DiffFormatter;
import com.pocketgit.model.Commit;
import com.pocketgit.model.FileMode;
import com.pocketgit.services.LogService;
import com.pocketgit.util.TerminalText;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

class TerminalOutputTest {
    @Test
    void commitMessagesCannotExecuteTerminalSequences() {
        var commit =
                new Commit(
                        "a".repeat(64),
                        List.of(),
                        "Reviewer",
                        "reviewer@example.com",
                        Instant.EPOCH,
                        "message\u001b[2J\nbody\u0007");
        var entry = new LogService.Entry("b".repeat(64), commit);
        var formatter = new CommitFormatter();
        assertEquals("bbbbbbb message\\u001b[2J\n", formatter.oneline(entry));
        var full = formatter.format(entry, true);
        assertFalse(full.contains("\u001b"));
        assertTrue(full.contains("body\\u0007"));
    }

    @Test
    void textualDiffEscapesControlsWithoutModifyingDomainContent() throws Exception {
        var change =
                new DiffEngine()
                        .diff(
                                "file",
                                "old\n".getBytes(StandardCharsets.UTF_8),
                                "\u001b]52;c;payload\u0007\n".getBytes(StandardCharsets.UTF_8),
                                FileMode.REGULAR_FILE,
                                FileMode.REGULAR_FILE);
        assertTrue(
                change.hunks().getFirst().lines().stream()
                        .anyMatch(line -> line.text().contains("\u001b")));
        var output = new DiffFormatter().format(List.of(change));
        assertFalse(output.contains("\u001b"));
        assertFalse(output.contains("\u0007"));
        assertTrue(output.contains("+\\u001b]52;c;payload\\u0007"));
    }

    @Test
    void ordinaryUnicodeAndIndentationStayReadable() {
        assertEquals("\tnaïve 日本語", TerminalText.escape("\tnaïve 日本語"));
        assertEquals("hello\\u000d", TerminalText.escape("hello\r"));
        assertEquals("\\u009b31m", TerminalText.escape("\u009b31m"));
    }
}
