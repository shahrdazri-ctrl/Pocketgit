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
import java.util.regex.Pattern;

/** Deliberately small, documented root .pocketgitignore grammar. */
public final class IgnoreMatcher {
    private record Rule(Pattern pattern, boolean rooted, boolean directoryOnly) {}
    private final List<Rule> rules;

    private IgnoreMatcher(List<Rule> rules) { this.rules = List.copyOf(rules); }

    public static IgnoreMatcher load(Repository repository) throws IOException {
        var file = repository.root().resolve(".pocketgitignore");
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return parse(""); }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException(".pocketgitignore must be a regular file");
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException(".pocketgitignore exceeds 1 MiB limit");
            var decoder = StandardCharsets.UTF_8.newDecoder()
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
                throw new IOException("unsupported ignore syntax: " + pattern + " (negation, **, and escapes are not supported)");
            }
            boolean rooted = pattern.startsWith("/");
            if (rooted) pattern = pattern.substring(1);
            boolean directoryOnly = pattern.endsWith("/");
            if (directoryOnly) pattern = pattern.substring(0, pattern.length() - 1);
            if (pattern.isEmpty() || pattern.contains("//")) throw new IOException("invalid ignore pattern: " + line);
            rooted |= pattern.contains("/");
            StringBuilder regex = new StringBuilder("^");
            for (char c : pattern.toCharArray()) {
                if (c == '*') regex.append("[^/]*");
                else if (c == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            rules.add(new Rule(Pattern.compile(regex.append('$').toString()), rooted, directoryOnly));
        }
        return new IgnoreMatcher(rules);
    }

    public boolean isIgnored(String relative, boolean directory) {
        String[] components = relative.split("/");
        for (Rule rule : rules) {
            for (int i = 0; i < components.length; i++) {
                boolean isDirectory = i < components.length - 1 || directory;
                if (rule.directoryOnly && !isDirectory) continue;
                String candidate = rule.rooted ? String.join("/", java.util.Arrays.copyOfRange(components, 0, i + 1)) : components[i];
                if (rule.pattern.matcher(candidate).matches()) return true;
            }
        }
        return false;
    }
}
