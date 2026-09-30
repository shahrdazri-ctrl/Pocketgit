package com.pocketgit.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Deliberately small, documented root .pocketgitignore grammar. */
public final class IgnoreMatcher {
    private record Rule(List<int[]> components, boolean rooted, boolean directoryOnly) {}

    private final List<Rule> rules;

    private IgnoreMatcher(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static IgnoreMatcher load(Repository repository) throws IOException {
        var file = repository.root().resolve(".pocketgitignore");
        BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return parse("");
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink())
            throw new IOException(".pocketgitignore must be a regular file");
        try (var input =
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024)
                throw new IOException(".pocketgitignore exceeds 1 MiB limit");
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            return parse(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString());
        }
    }

    public static IgnoreMatcher parse(String content) throws IOException {
        var rules = new ArrayList<Rule>();
        for (String line : content.split("\\R")) {
            String pattern = line.strip();
            if (pattern.isEmpty() || pattern.startsWith("#")) continue;
            if (pattern.startsWith("!") || pattern.contains("**") || pattern.contains("\\")) {
                throw new IOException(
                        "unsupported ignore syntax: "
                                + pattern
                                + " (negation, **, and escapes are not supported)");
            }
            boolean rooted = pattern.startsWith("/");
            if (rooted) pattern = pattern.substring(1);
            boolean directoryOnly = pattern.endsWith("/");
            if (directoryOnly) pattern = pattern.substring(0, pattern.length() - 1);
            if (pattern.isEmpty() || pattern.contains("//"))
                throw new IOException("invalid ignore pattern: " + line);
            rooted |= pattern.contains("/");
            var components = new ArrayList<int[]>();
            for (String component : pattern.split("/"))
                components.add(component.codePoints().toArray());
            rules.add(new Rule(List.copyOf(components), rooted, directoryOnly));
        }
        return new IgnoreMatcher(rules);
    }

    public boolean isIgnored(String relative, boolean directory) {
        String[] names = relative.split("/");
        int[][] components = new int[names.length][];
        for (int i = 0; i < names.length; i++) components[i] = names[i].codePoints().toArray();
        for (Rule rule : rules) {
            if (rule.rooted) {
                int count = rule.components.size();
                if (count > components.length
                        || (rule.directoryOnly && count == components.length && !directory))
                    continue;
                boolean matches = true;
                for (int i = 0; i < count; i++) {
                    if (!matches(rule.components.get(i), components[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return true;
                continue;
            }
            for (int i = 0; i < components.length; i++) {
                boolean isDirectory = i < components.length - 1 || directory;
                if (rule.directoryOnly && !isDirectory) continue;
                if (matches(rule.components.getFirst(), components[i])) return true;
            }
        }
        return false;
    }

    /** Constant auxiliary space and at most O(pattern length * name length) comparisons. */
    private static boolean matches(int[] pattern, int[] name) {
        int p = 0, n = 0, star = -1, retry = 0;
        while (n < name.length) {
            if (p < pattern.length
                    && pattern[p] != '*'
                    && (pattern[p] == '?' || pattern[p] == name[n])) {
                p++;
                n++;
            } else if (p < pattern.length && pattern[p] == '*') {
                star = p++;
                retry = n;
            } else if (star >= 0) {
                p = star + 1;
                n = ++retry;
            } else return false;
        }
        while (p < pattern.length && pattern[p] == '*') p++;
        return p == pattern.length;
    }
}
