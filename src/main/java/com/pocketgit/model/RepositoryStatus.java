package com.pocketgit.model;

import com.pocketgit.util.PathUtils;
import com.pocketgit.validation.RefNameValidator;
import java.util.HashSet;
import java.util.List;

/** Independent HEAD/index and index/working-tree changes; one path may appear in both. */
public record RepositoryStatus(
        String branch,
        boolean hasCommits,
        List<String> stagedNew,
        List<String> stagedModified,
        List<String> stagedDeleted,
        List<String> unstagedModified,
        List<String> unstagedDeleted,
        List<String> untracked,
        List<String> ignored) {
    public RepositoryStatus {
        RefNameValidator.validateBranch(branch);
        stagedNew = paths(stagedNew, false);
        stagedModified = paths(stagedModified, false);
        stagedDeleted = paths(stagedDeleted, false);
        unstagedModified = paths(unstagedModified, false);
        unstagedDeleted = paths(unstagedDeleted, false);
        untracked = paths(untracked, false);
        ignored = paths(ignored, true);
        requireDisjoint(stagedNew, stagedModified, stagedDeleted);
        requireDisjoint(unstagedModified, unstagedDeleted);
    }

    private static List<String> paths(List<String> values, boolean directoriesAllowed) {
        var sorted = List.copyOf(values).stream().sorted().toList();
        var seen = new HashSet<String>();
        for (String value : sorted) {
            String path =
                    directoriesAllowed && value.endsWith("/")
                            ? value.substring(0, value.length() - 1)
                            : value;
            PathUtils.validateIndexPath(path);
            if (!seen.add(value))
                throw new IllegalArgumentException("duplicate status path: " + value);
        }
        return sorted;
    }

    @SafeVarargs
    private static void requireDisjoint(List<String>... groups) {
        var seen = new HashSet<String>();
        for (var group : groups)
            for (String path : group) {
                if (!seen.add(path))
                    throw new IllegalArgumentException("conflicting status categories: " + path);
            }
    }

    public boolean isClean() {
        return stagedNew.isEmpty()
                && stagedModified.isEmpty()
                && stagedDeleted.isEmpty()
                && unstagedModified.isEmpty()
                && unstagedDeleted.isEmpty()
                && untracked.isEmpty();
    }
}
