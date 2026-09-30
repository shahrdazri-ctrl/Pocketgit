package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.diff.DiffEngine;
import com.pocketgit.diff.TextProbe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

class TextProbeTest {
    @Test
    void acceptsUtf8SequencesSplitAcrossEveryByteBoundary() throws Exception {
        byte[] bytes = "naïve 日本語 😀\n".getBytes(StandardCharsets.UTF_8);
        var probe = new TextProbe();
        for (byte b : bytes) probe.write(Byte.toUnsignedInt(b));
        assertFalse(probe.binary());
        assertArrayEquals(bytes, probe.text("file"));
    }

    @Test
    void malformedAndTruncatedSequencesAreBinary() throws Exception {
        for (byte[] bytes : new byte[][] {{(byte) 0xc3}, {(byte) 0xc3, 0x28}, {(byte) 0xff}, {0}}) {
            var probe = new TextProbe();
            probe.write(bytes);
            assertTrue(probe.binary());
            assertNull(probe.text("file"));
        }
    }

    @Test
    void largeTextFailsButLateBinaryMarkersNeedNoUnboundedBuffer() throws Exception {
        byte[] chunk = new byte[8192];
        java.util.Arrays.fill(chunk, (byte) 'x');
        var text = new TextProbe();
        var binary = new TextProbe();
        for (int i = 0; i <= DiffEngine.MAX_TEXT_BYTES / chunk.length; i++) {
            text.write(chunk);
            binary.write(chunk);
        }
        assertThrows(IOException.class, () -> text.text("large"));
        binary.write(0);
        assertTrue(binary.binary());
        assertNull(binary.text("large"));
    }

    @Test
    void outputFailureStateIsExplicit() throws Exception {
        var probe = new TextProbe();
        probe.binary();
        assertThrows(IOException.class, () -> probe.write(1));
    }
}
