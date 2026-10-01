package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.diff.*;
import com.pocketgit.model.FileMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiffEngineTest {
    private final DiffEngine engine = new DiffEngine();
    private final DiffFormatter formatter = new DiffFormatter();

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private DiffResult diff(String a, String b) throws Exception {
        return engine.diff(
                "file",
                a == null ? null : bytes(a),
                b == null ? null : bytes(b),
                a == null ? null : FileMode.REGULAR_FILE,
                b == null ? null : FileMode.REGULAR_FILE);
    }

    @Test
    void modificationInsertionDeletionAndEmptyFiles() throws Exception {
        var change = diff("one\ntwo\n", "one\nthree\n");
        assertEquals(1, change.hunks().size());
        assertTrue(formatter.format(List.of(change)).contains("-two\n+three\n"));
        assertTrue(formatter.format(List.of(diff("", "added\n"))).contains("@@ -0,0 +1,1 @@"));
        assertTrue(formatter.format(List.of(diff("deleted\n", ""))).contains("@@ -1,1 +0,0 @@"));
        assertTrue(diff("same\n", "same\n").hunks().isEmpty());
    }

    @Test
    void creationsAndDeletionsUseDevNull() throws Exception {
        assertTrue(
                formatter
                        .format(List.of(diff(null, "new\n")))
                        .contains("--- /dev/null\n+++ b/file"));
        assertTrue(
                formatter
                        .format(List.of(diff("old\n", null)))
                        .contains("--- a/file\n+++ /dev/null"));
        assertTrue(formatter.format(List.of(diff(null, ""))).contains("new file mode 100644"));
    }

    @Test
    void separatedChangesGetThreeContextLinesAndSeparateHunks() throws Exception {
        String a = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\n";
        var result = diff(a, a.replace("a\n", "A\n").replace("o\n", "O\n"));
        assertEquals(2, result.hunks().size());
        assertEquals(1, result.hunks().getFirst().oldStart());
        assertEquals(4, result.hunks().getFirst().oldCount());
        assertEquals(12, result.hunks().getLast().oldStart());
    }

    @Test
    void finalNewlineIsARealChangeAndCrLfIsPreserved() throws Exception {
        String output = formatter.format(List.of(diff("hello\n", "hello")));
        assertTrue(output.contains("-hello\n+hello\n\\ No newline at end of file"));
        assertFalse(diff("a\r\n", "a\n").hunks().isEmpty());
    }

    @Test
    void invalidUtf8AndNulAreBinaryAndNeverPrinted() throws Exception {
        for (byte[] content : List.of(new byte[] {0, 1}, new byte[] {(byte) 0xff})) {
            var result =
                    engine.diff(
                            "binary",
                            new byte[] {1},
                            content,
                            FileMode.REGULAR_FILE,
                            FileMode.REGULAR_FILE);
            assertTrue(result.binary());
            assertTrue(result.hunks().isEmpty());
            assertTrue(formatter.format(List.of(result)).contains("Binary files differ: binary"));
        }
    }

    @Test
    void executableOnlyChangeIsVisible() throws Exception {
        var result =
                engine.diff(
                        "script",
                        bytes("same"),
                        bytes("same"),
                        FileMode.REGULAR_FILE,
                        FileMode.EXECUTABLE_FILE);
        assertTrue(formatter.format(List.of(result)).contains("old mode 100644\nnew mode 100755"));
        assertTrue(result.hunks().isEmpty());
    }

    @Test
    void matrixLimitFailsClearlyRatherThanAllocatingUnboundedMemory() throws Exception {
        assertThrows(java.io.IOException.class, () -> diff("a\n".repeat(2100), "b\n".repeat(2100)));
    }

    private List<String> tokens(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == '\n') {
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        if (start < text.length()) result.add(text.substring(start));
        return result;
    }

    private String apply(String before, DiffResult result) {
        var old = tokens(before);
        var text = new StringBuilder();
        int cursor = 0;
        for (var hunk : result.hunks()) {
            int start = hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1;
            while (cursor < start) text.append(old.get(cursor++));
            for (var line : hunk.lines()) {
                String token = line.text() + (line.terminated() ? "\n" : "");
                if (line.type() != DiffLine.Type.ADDED) assertEquals(old.get(cursor++), token);
                if (line.type() != DiffLine.Type.REMOVED) text.append(token);
            }
        }
        while (cursor < old.size()) text.append(old.get(cursor++));
        return text.toString();
    }

    @Test
    void seededRandomEditsReconstructNewContentsExactly() throws Exception {
        var random = new Random(7362);
        for (int sample = 0; sample < 150; sample++) {
            var a = new StringBuilder();
            var b = new StringBuilder();
            int n = random.nextInt(30), m = random.nextInt(30);
            for (int i = 0; i < n; i++)
                a.append(random.nextInt(6)).append(i == n - 1 && random.nextBoolean() ? "" : "\n");
            for (int i = 0; i < m; i++)
                b.append(random.nextInt(6)).append(i == m - 1 && random.nextBoolean() ? "" : "\n");
            assertEquals(b.toString(), apply(a.toString(), diff(a.toString(), b.toString())));
        }
    }
}
