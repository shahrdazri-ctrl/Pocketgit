package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.cli.CommitFormatter;
import com.pocketgit.cli.StatusFormatter;
import com.pocketgit.diff.DiffEngine;
import com.pocketgit.diff.DiffFormatter;
import com.pocketgit.model.Commit;
import com.pocketgit.model.FileMode;
import com.pocketgit.model.RepositoryStatus;
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

    @Test
    void labelsEscapeLineBreaksAndDirectionalControlsPreservingOrdinaryUnicode() {
        assertEquals(
                "naïve 😀\\u000a\\u0009\\u202e", TerminalText.escapeLabel("naïve 😀\n\t\u202e"));
        assertEquals(
                "\\u061c\\u200e\\u200f\\u202a\\u202b\\u202c\\u202d\\u202e\\u2066\\u2067\\u2068\\u2069",
                TerminalText.escape(
                        "\u061c\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069"));
        assertEquals("\tline\n", TerminalText.escape("\tline\n"));
    }

    @Test
    void everyStatusCategoryEscapesUntrustedPathsAndBranchLabels() {
        String control = "\u009b2J", bidi = "\u202e";
        var status =
                new RepositoryStatus(
                        "branch" + bidi,
                        true,
                        List.of("added" + control),
                        List.of("staged" + bidi),
                        List.of("deleted" + control),
                        List.of("working" + bidi),
                        List.of("missing" + control),
                        List.of("new" + control),
                        List.of("ignored" + bidi + "/"));
        String output = new StatusFormatter().format(status, true);
        assertFalse(output.contains(control));
        assertFalse(output.contains(bidi));
        assertTrue(output.contains("On branch branch\\u202e"));
        for (String path :
                List.of(
                        "added\\u009b2J",
                        "staged\\u202e",
                        "deleted\\u009b2J",
                        "working\\u202e",
                        "missing\\u009b2J",
                        "new\\u009b2J",
                        "ignored\\u202e/")) assertTrue(output.contains(path), path);
    }

    @Test
    void authorsAndCommitMessagesCannotHideDirectionalText() {
        var commit =
                new Commit(
                        "a".repeat(64),
                        List.of(),
                        "name\u202e",
                        "person\u2066@example.com",
                        Instant.EPOCH,
                        "message\u202e");
        String output =
                new CommitFormatter().format(new LogService.Entry("b".repeat(64), commit), true);
        assertTrue(output.contains("Author: name\\u202e <person\\u2066@example.com>"));
        assertTrue(output.contains("message\\u202e"));
        assertFalse(output.contains("\u202e"));
    }
}
