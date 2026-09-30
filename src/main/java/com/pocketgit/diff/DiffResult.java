package com.pocketgit.diff;

import com.pocketgit.model.FileMode;
import java.util.List;

public record DiffResult(String path, boolean binary, boolean oldExists, boolean newExists,
                         FileMode oldMode, FileMode newMode, List<DiffHunk> hunks) {
    public DiffResult { hunks = List.copyOf(hunks); }
}
