package com.pocketgit.diff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Classifies an entire payload while retaining at most the bounded text prefix. */
public final class TextProbe extends OutputStream {
    private final java.nio.charset.CharsetDecoder decoder =
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final ByteBuffer pending = ByteBuffer.allocate(16 * 1024 + 4);
    private final CharBuffer decoded = CharBuffer.allocate(16 * 1024);
    private ByteArrayOutputStream retained = new ByteArrayOutputStream();
    private long size;
    private boolean binary;
    private boolean finished;

    @Override
    public void write(int value) throws IOException {
        write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (finished) throw new IOException("text probe is finished");
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        size += length;
        if (binary) return;
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] == 0) {
                markBinary();
                return;
            }
        }
        int remaining = length;
        int position = offset;
        while (remaining > 0 && !binary) {
            int count = Math.min(remaining, pending.remaining());
            pending.put(bytes, position, count);
            pending.flip();
            decode(false);
            pending.compact();
            remaining -= count;
            position += count;
        }
        if (!binary && retained.size() < DiffEngine.MAX_TEXT_BYTES) {
            retained.write(
                    bytes, offset, Math.min(length, DiffEngine.MAX_TEXT_BYTES - retained.size()));
        }
    }

    private void decode(boolean end) {
        while (true) {
            decoded.clear();
            var result = decoder.decode(pending, decoded, end);
            if (result.isError()) {
                markBinary();
                return;
            }
            if (!result.isOverflow()) return;
        }
    }

    private void markBinary() {
        binary = true;
        retained = null;
    }

    public boolean binary() {
        if (!finished) {
            if (!binary) {
                pending.flip();
                decode(true);
            }
            finished = true;
        }
        return binary;
    }

    /** Null represents binary content; large valid text fails before large token allocations. */
    public byte[] text(String path) throws IOException {
        if (binary()) return null;
        if (size > DiffEngine.MAX_TEXT_BYTES)
            throw new IOException("text diff input exceeds 8 MiB limit: " + path);
        return retained.toByteArray();
    }
}
