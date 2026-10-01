package com.pocketgit.diff;

import com.pocketgit.model.FileMode;
import com.pocketgit.util.TerminalText;
import java.io.IOException;
import java.util.List;

public final class DiffFormatter {
    private String mode(FileMode mode) {
        return mode == FileMode.EXECUTABLE_FILE ? "100755" : "100644";
    }

    public String format(List<DiffResult> results) {
        var text = new StringBuilder();
        try {
            write(results, text);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return text.toString();
    }

    public void write(List<DiffResult> results, Appendable text) throws IOException {
        for (var result : results) {
            String path = TerminalText.escapeLabel(result.path());
            text.append("diff --pocketgit a/").append(path).append(" b/").append(path).append('\n');
            if (!result.oldExists())
                text.append("new file mode ").append(mode(result.newMode())).append('\n');
            else if (!result.newExists())
                text.append("deleted file mode ").append(mode(result.oldMode())).append('\n');
            else if (result.oldMode() != result.newMode())
                text.append("old mode ")
                        .append(mode(result.oldMode()))
                        .append("\nnew mode ")
                        .append(mode(result.newMode()))
                        .append('\n');
            if (result.binary()) {
                text.append("Binary files differ: ").append(path).append('\n');
                continue;
            }
            if (result.hunks().isEmpty()) continue;
            text.append("--- ").append(result.oldExists() ? "a/" + path : "/dev/null").append('\n');
            text.append("+++ ").append(result.newExists() ? "b/" + path : "/dev/null").append('\n');
            for (var hunk : result.hunks()) {
                text.append("@@ -")
                        .append(Integer.toString(hunk.oldStart()))
                        .append(',')
                        .append(Integer.toString(hunk.oldCount()))
                        .append(" +")
                        .append(Integer.toString(hunk.newStart()))
                        .append(',')
                        .append(Integer.toString(hunk.newCount()))
                        .append(" @@\n");
                for (var line : hunk.lines()) {
                    text.append(
                            switch (line.type()) {
                                case CONTEXT -> ' ';
                                case ADDED -> '+';
                                case REMOVED -> '-';
                            });
                    TerminalText.appendEscaped(line.text(), text);
                    text.append('\n');
                    if (!line.terminated()) text.append("\\ No newline at end of file\n");
                }
            }
        }
    }
}
