package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.repository.IgnoreMatcher;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IgnoreMatcherTest {
    @Test
    void handlesCommentsBlanksLiteralsStarsQuestionsAndDirectoryPropagation() throws Exception {
        var ignore =
                IgnoreMatcher.parse(
                        "# comment\n\n target/\n*.class\n*.log\n.env\n.idea/\nnotes?.txt\n");
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

    @Test
    void slashPatternsAndLeadingSlashAreRootRelative() throws Exception {
        var ignore = IgnoreMatcher.parse("/root.txt\nsrc/generated/\nsrc/*.tmp\n");
        assertTrue(ignore.isIgnored("root.txt", false));
        assertFalse(ignore.isIgnored("nested/root.txt", false));
        assertTrue(ignore.isIgnored("src/generated/a", false));
        assertFalse(ignore.isIgnored("nested/src/generated/a", false));
        assertTrue(ignore.isIgnored("src/a.tmp", false));
        assertFalse(ignore.isIgnored("src/nested/a.tmp", false));
    }

    @Test
    void treatsRegexCharactersAndUnicodeLiterally() throws Exception {
        var ignore = IgnoreMatcher.parse("[a].txt\n日本語/\n");
        assertTrue(ignore.isIgnored("[a].txt", false));
        assertFalse(ignore.isIgnored("a.txt", false));
        assertTrue(ignore.isIgnored("日本語/秘密", false));
    }

    @Test
    void unsupportedSyntaxFailsClearly() {
        for (String rule : new String[] {"!secret", "**/build", "src\\file", "/", "src//file"}) {
            assertThrows(IOException.class, () -> IgnoreMatcher.parse(rule));
        }
    }

    @Test
    void supplementaryUnicodeLiteralsAndQuestionsMatchCodePoints() throws Exception {
        var literal = IgnoreMatcher.parse("😀.txt\n/📦/生成😀/\n");
        assertTrue(literal.isIgnored("nested/😀.txt", false));
        assertTrue(literal.isIgnored("📦/生成😀/file", false));
        assertFalse(literal.isIgnored("nested/📦/生成😀/file", false));
        var wildcard = IgnoreMatcher.parse("?.txt\n");
        assertTrue(wildcard.isIgnored("😀.txt", false));
        assertFalse(wildcard.isIgnored("😀😀.txt", false));
    }

    @Test
    void repeatedWildcardsHaveBoundedMatchingCost() throws Exception {
        var ignore = IgnoreMatcher.parse("*a".repeat(18) + "b\n");
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> {
                    assertFalse(ignore.isIgnored("a".repeat(200), false));
                    assertTrue(ignore.isIgnored("a".repeat(200) + "b", false));
                });
    }

    @Test
    void wildcardMatchesAgreeWithIndependentDynamicProgrammingOracle() throws Exception {
        var random = new Random(4815162342L);
        int[] alphabet = {'a', 'b', 0x1f600};
        int[] patternAlphabet = {'a', 'b', 0x1f600, '*', '?'};
        for (int sample = 0; sample < 2000; sample++) {
            var pattern = new StringBuilder();
            for (int i = random.nextInt(1, 13); i > 0; i--) {
                int value = patternAlphabet[random.nextInt(patternAlphabet.length)];
                if (value == '*'
                        && !pattern.isEmpty()
                        && pattern.charAt(pattern.length() - 1) == '*') value = '?';
                pattern.appendCodePoint(value);
            }
            var name = new StringBuilder();
            for (int i = random.nextInt(1, 13); i > 0; i--)
                name.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
            boolean expected =
                    oracle(
                            pattern.toString().codePoints().toArray(),
                            name.toString().codePoints().toArray());
            assertEquals(
                    expected,
                    IgnoreMatcher.parse(pattern.toString()).isIgnored(name.toString(), false),
                    pattern + " / " + name);
        }
    }

    private static boolean oracle(int[] pattern, int[] name) {
        boolean[][] table = new boolean[pattern.length + 1][name.length + 1];
        table[0][0] = true;
        for (int p = 1; p <= pattern.length; p++) {
            if (pattern[p - 1] == '*') table[p][0] = table[p - 1][0];
            for (int n = 1; n <= name.length; n++) {
                table[p][n] =
                        pattern[p - 1] == '*'
                                ? table[p - 1][n] || table[p][n - 1]
                                : (pattern[p - 1] == '?' || pattern[p - 1] == name[n - 1])
                                        && table[p - 1][n - 1];
            }
        }
        return table[pattern.length][name.length];
    }
}
