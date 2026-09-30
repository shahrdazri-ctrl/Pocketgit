package com.pocketgit.services;

import com.pocketgit.model.Index;
import com.pocketgit.model.IndexEntry;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.WorkingTree;
import com.pocketgit.util.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** No writes: detect staged loss, affected local edits, and file/directory obstructions. */
public final class CheckoutPlanner {
    public static Map<String, IndexEntry> entries(Index index) {
        var result = new TreeMap<String, IndexEntry>();
        index.entries().forEach(entry -> result.put(entry.path(), entry)); return result;
    }
    public CheckoutPlan plan(Repository repository, Index head, Index index, Index target) throws IOException {
        var before = entries(head); var staged = entries(index); var after = entries(target);
        var working = entries(new WorkingTree().read(repository, index).indexedFiles());
        var paths = new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
        var writes = new ArrayList<IndexEntry>(); var deletes = new TreeSet<String>(); var conflicts = new TreeSet<String>();
        // Conservative: switching never discards staged work, even when target files are unrelated.
        var stagedPaths = new TreeSet<>(before.keySet()); stagedPaths.addAll(staged.keySet());
        for (String path : stagedPaths) if (!Objects.equals(before.get(path), staged.get(path))) conflicts.add(path);
        for (String path : paths) {
            if (Objects.equals(before.get(path), after.get(path))) continue;
            if (before.containsKey(path)) {
                if (!Objects.equals(staged.get(path), working.get(path))) conflicts.add(path);
                deletes.add(path);
            }
            if (after.containsKey(path)) writes.add(after.get(path));
        }
        for (var entry : writes) {
            String name = entry.path();
            var path = PathUtils.safeWorkingPath(repository.root(), repository.root().resolve(name));
            var attributes = PathUtils.attributesOrMissing(path);
            if (attributes != null && attributes.isDirectory()) {
                // Only directories made entirely of tracked paths scheduled for deletion can collapse.
                try (var children = Files.walk(path)) {
                    for (var child : children.toList()) {
                        if (child.equals(path)) continue;
                        String relative = PathUtils.relativePath(repository.root(), child);
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            if (deletes.stream().noneMatch(p -> p.startsWith(relative + "/"))) conflicts.add(relative);
                        } else if (!deletes.contains(relative)) conflicts.add(relative);
                    }
                }
                if (deletes.stream().noneMatch(p -> p.startsWith(name + "/"))) conflicts.add(name);
            } else if (attributes != null && !deletes.contains(name)) conflicts.add(name);
            for (var parent = path.getParent(); !parent.equals(repository.root()); parent = parent.getParent()) {
                var a = PathUtils.attributesOrMissing(parent);
                if (a != null && !a.isDirectory() && !deletes.contains(PathUtils.relativePath(repository.root(), parent))) {
                    conflicts.add(PathUtils.relativePath(repository.root(), parent));
                }
            }
        }
        return new CheckoutPlan(writes, new ArrayList<>(deletes), new ArrayList<>(conflicts), target);
    }
}
