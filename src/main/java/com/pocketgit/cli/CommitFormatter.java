package com.pocketgit.cli;

import com.pocketgit.services.LogService.Entry;
import com.pocketgit.util.TerminalText;

/** Pure text rendering shared by log and show; timestamps retain canonical UTC form. */
public final class CommitFormatter {
    public String format(Entry entry, boolean details) {
        var commit = entry.commit();
        var text = new StringBuilder("commit ").append(entry.hash()).append('\n');
        if (details) {
            text.append("Tree:   ").append(commit.treeHash()).append('\n');
            text.append("Parents:")
                    .append(
                            commit.parentHashes().isEmpty()
                                    ? " (none)"
                                    : " " + String.join(" ", commit.parentHashes()))
                    .append('\n');
        }
        text.append("Author: ")
                .append(commit.authorName())
                .append(" <")
                .append(commit.authorEmail())
                .append(">\n");
        text.append("Date:   ").append(commit.timestamp()).append("\n\n");
        for (String line : commit.message().split("\\R", -1))
            text.append("    ").append(TerminalText.escape(line)).append('\n');
        return text.toString();
    }

    public String oneline(Entry entry) {
        return entry.hash().substring(0, 7)
                + " "
                + TerminalText.escape(entry.commit().message().split("\\R", -1)[0])
                + "\n";
    }
}
