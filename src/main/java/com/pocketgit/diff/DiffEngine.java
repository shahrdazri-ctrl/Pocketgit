package com.pocketgit.diff;

import static com.pocketgit.diff.DiffLine.Type.*;

import com.pocketgit.model.FileMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic LCS edit script with bounded input, one matrix, and three context lines. */
public final class DiffEngine {
    public static final long MAX_CELLS = 4_000_000;
    public static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_LINES = 100_000;
    private static final int CONTEXT_LINES = 3;

    private record Line(String text, boolean terminated) {}

    private String text(byte[] bytes, String path) throws IOException {
        for (byte b : bytes) if (b == 0) return null;
        if (bytes.length > MAX_TEXT_BYTES)
            throw new IOException("text input limit of 8 MiB exceeded: " + path);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException binary) {
            return null;
        }
    }

    private List<Line> lines(String text, String path) throws IOException {
        // Count before allocating substrings/records: a small byte payload can contain millions of
        // lines.
        int count = text.endsWith("\n") || text.isEmpty() ? 0 : 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++count > MAX_LINES) {
                throw new IOException("text line limit of " + MAX_LINES + " exceeded: " + path);
            }
        }
        var result = new ArrayList<Line>(count);
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                result.add(new Line(text.substring(start, i), true));
                start = i + 1;
            }
        }
        if (start < text.length()) result.add(new Line(text.substring(start), false));
        return result;
    }

    public DiffResult diff(
            String path, byte[] before, byte[] after, FileMode oldMode, FileMode newMode)
            throws IOException {
        boolean oldExists = before != null, newExists = after != null;
        byte[] oldBytes = oldExists ? before : new byte[0],
                newBytes = newExists ? after : new byte[0];
        if (Arrays.equals(oldBytes, newBytes)) {
            return new DiffResult(path, false, oldExists, newExists, oldMode, newMode, List.of());
        }
        String oldText = text(oldBytes, path), newText = text(newBytes, path);
        if (oldText == null || newText == null) {
            return new DiffResult(path, true, oldExists, newExists, oldMode, newMode, List.of());
        }
        var a = lines(oldText, path);
        var b = lines(newText, path);
        int prefix = 0, suffix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix)))
            prefix++;
        while (suffix < a.size() - prefix
                && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        int n = a.size() - prefix - suffix, m = b.size() - prefix - suffix;
        long cells = (long) (n + 1) * (m + 1);
        if (cells > MAX_CELLS)
            throw new IOException("text diff exceeds 4,000,000 LCS cells: " + path);
        // Flat storage prevents millions of small row allocations for strongly asymmetric inputs.
        int width = m + 1;
        int[] lcs = new int[(int) cells];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                int cell = i * width + j;
                lcs[cell] =
                        a.get(prefix + i).equals(b.get(prefix + j))
                                ? 1 + lcs[cell + width + 1]
                                : Math.max(lcs[cell + width], lcs[cell + 1]);
            }
        }
        var edits = new ArrayList<DiffLine>();
        for (int k = 0; k < prefix; k++) edits.add(line(CONTEXT, a.get(k)));
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(prefix + i).equals(b.get(prefix + j))) {
                edits.add(line(CONTEXT, a.get(prefix + i)));
                i++;
                j++;
            } else if (i < n && (j == m || lcs[(i + 1) * width + j] >= lcs[i * width + j + 1])) {
                edits.add(line(REMOVED, a.get(prefix + i++)));
            } else {
                edits.add(line(ADDED, b.get(prefix + j++)));
            }
        }
        for (i = a.size() - suffix; i < a.size(); i++) edits.add(line(CONTEXT, a.get(i)));
        return new DiffResult(path, false, oldExists, newExists, oldMode, newMode, hunks(edits));
    }

    private DiffLine line(DiffLine.Type type, Line line) {
        return new DiffLine(type, line.text(), line.terminated());
    }

    private List<DiffHunk> hunks(List<DiffLine> edits) {
        int[] old = new int[edits.size() + 1], next = new int[edits.size() + 1];
        for (int i = 0; i < edits.size(); i++) {
            old[i + 1] = old[i] + (edits.get(i).type() == ADDED ? 0 : 1);
            next[i + 1] = next[i] + (edits.get(i).type() == REMOVED ? 0 : 1);
        }
        var result = new ArrayList<DiffHunk>();
        int cursor = 0;
        while (cursor < edits.size()) {
            while (cursor < edits.size() && edits.get(cursor).type() == CONTEXT) cursor++;
            if (cursor == edits.size()) break;
            int start = Math.max(0, cursor - CONTEXT_LINES), last = cursor;
            while (true) {
                int candidate = last + 1;
                while (candidate < edits.size() && edits.get(candidate).type() == CONTEXT)
                    candidate++;
                if (candidate == edits.size() || candidate - last > 2 * CONTEXT_LINES + 1) break;
                last = candidate;
            }
            int end = Math.min(edits.size(), last + CONTEXT_LINES + 1);
            int oldCount = old[end] - old[start], newCount = next[end] - next[start];
            result.add(
                    new DiffHunk(
                            old[start] + (oldCount == 0 ? 0 : 1),
                            oldCount,
                            next[start] + (newCount == 0 ? 0 : 1),
                            newCount,
                            edits.subList(start, end)));
            cursor = end;
        }
        return result;
    }
}
