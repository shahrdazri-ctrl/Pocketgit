package com.pocketgit.cli;

import com.pocketgit.model.RepositoryStatus;
import com.pocketgit.util.TerminalText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plain deterministic text; status classification lives in StatusService. */
public final class StatusFormatter {
    public String format(RepositoryStatus status, boolean showIgnored) {
        var lines = new ArrayList<String>();
        lines.add("On branch " + TerminalText.escapeLabel(status.branch()));
        if (!status.hasCommits()) lines.add("No commits yet");
        var staged = new TreeMap<String, String>();
        status.stagedNew().forEach(path -> staged.put(path, "new file:   "));
        status.stagedModified().forEach(path -> staged.put(path, "modified:   "));
        status.stagedDeleted().forEach(path -> staged.put(path, "deleted:    "));
        changes(lines, "Changes to be committed:", staged);
        var unstaged = new TreeMap<String, String>();
        status.unstagedModified().forEach(path -> unstaged.put(path, "modified:   "));
        status.unstagedDeleted().forEach(path -> unstaged.put(path, "deleted:    "));
        changes(lines, "Changes not staged:", unstaged);
        paths(lines, "Untracked files:", status.untracked());
        if (showIgnored) paths(lines, "Ignored files:", status.ignored());
        if (status.isClean()) {
            if (showIgnored && !status.ignored().isEmpty()) lines.add("");
            lines.add("nothing to commit, working tree clean");
        }
        return String.join("\n", lines) + "\n";
    }

    private void changes(List<String> lines, String title, Map<String, String> entries) {
        if (entries.isEmpty()) return;
        lines.add("");
        lines.add(title);
        entries.forEach((path, label) -> lines.add("  " + label + TerminalText.escapeLabel(path)));
    }

    private void paths(List<String> lines, String title, List<String> paths) {
        if (paths.isEmpty()) return;
        lines.add("");
        lines.add(title);
        paths.forEach(path -> lines.add("  " + TerminalText.escapeLabel(path)));
    }
}
