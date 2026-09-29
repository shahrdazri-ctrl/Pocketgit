package com.pocketgit.unit;

import com.pocketgit.repository.IgnoreMatcher;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IgnoreMatcherTest {
    @Test void handlesCommentsBlanksLiteralsStarsQuestionsAndDirectoryPropagation() throws Exception {
        var ignore = IgnoreMatcher.parse("# comment\n\n target/\n*.class\n*.log\n.env\n.idea/\nnotes?.txt\n");
        assertTrue(ignore.isIgnored("target/output.bin", false));
        assertTrue(ignore.isIgnored("nested/target/output.bin", false));
        assertTrue(ignore.isIgnored("src/Main.class", false));
        assertTrue(ignore.isIgnored("run.log", false));
        assertTrue(ignore.isIgnored("nested/.env", false));
        assertTrue(ignore.isIgnored(".idea/config.xml", false));
        assertTrue(ignore.isIgnored("notes1.txt", false));
        assertFalse(ignore.isIgnored("notes12.txt", false));
        assertFalse(ignore.isIgnored("target", false));
        assertFalse(ignore.isIgnored("src/Main.java", false));
    }

    @Test void slashPatternsAndLeadingSlashAreRootRelative() throws Exception {
        var ignore = IgnoreMatcher.parse("/root.txt\nsrc/generated/\nsrc/*.tmp\n");
        assertTrue(ignore.isIgnored("root.txt", false));
        assertFalse(ignore.isIgnored("nested/root.txt", false));
        assertTrue(ignore.isIgnored("src/generated/a", false));
        assertFalse(ignore.isIgnored("nested/src/generated/a", false));
        assertTrue(ignore.isIgnored("src/a.tmp", false));
        assertFalse(ignore.isIgnored("src/nested/a.tmp", false));
    }

    @Test void treatsRegexCharactersAndUnicodeLiterally() throws Exception {
        var ignore = IgnoreMatcher.parse("[a].txt\n日本語/\n");
        assertTrue(ignore.isIgnored("[a].txt", false));
        assertFalse(ignore.isIgnored("a.txt", false));
        assertTrue(ignore.isIgnored("日本語/秘密", false));
    }

    @Test void unsupportedSyntaxFailsClearly() {
        for (String rule : new String[]{"!secret", "**/build", "src\\file", "/", "src//file"}) {
            assertThrows(IOException.class, () -> IgnoreMatcher.parse(rule));
        }
    }
}
