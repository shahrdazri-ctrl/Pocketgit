package com.pocketgit.diff;

import java.util.List;

public record DiffHunk(
        int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {
    public DiffHunk {
        lines = List.copyOf(lines);
    }
}
