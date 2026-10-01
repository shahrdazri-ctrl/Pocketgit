package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.diff.DiffEngine;
import com.pocketgit.model.FileMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DiffResourceLimitTest {
    private final DiffEngine engine = new DiffEngine();

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void excessiveLineCountFailsBeforeBuildingMillionsOfLineObjects() {
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                engine.diff(
                                        "many-lines",
                                        bytes("\n".repeat(100_001)),
                                        bytes("changed\n"),
                                        FileMode.REGULAR_FILE,
                                        FileMode.REGULAR_FILE));
        assertTrue(failure.getMessage().contains("line limit"));
    }

    @Test
    void hugeSingleLineFailsBeforeDecodingToAnUnboundedString() {
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                engine.diff(
                                        "one-line",
                                        bytes("a".repeat(8 * 1024 * 1024 + 1)),
                                        bytes("b"),
                                        FileMode.REGULAR_FILE,
                                        FileMode.REGULAR_FILE));
        assertTrue(failure.getMessage().contains("text input limit"));
    }

    @Test
    void unchangedLargeContentAndNulBinaryNeedNoTextAllocation() throws Exception {
        byte[] large = new byte[8 * 1024 * 1024 + 1];
        assertTrue(
                engine.diff(
                                "unchanged",
                                large,
                                large,
                                FileMode.REGULAR_FILE,
                                FileMode.EXECUTABLE_FILE)
                        .hunks()
                        .isEmpty());
        assertTrue(
                engine.diff(
                                "binary",
                                large,
                                new byte[] {1},
                                FileMode.REGULAR_FILE,
                                FileMode.REGULAR_FILE)
                        .binary());
    }

    @Test
    void highlyAsymmetricDiffUsesOneBoundedMatrixAllocation() throws Exception {
        var result =
                engine.diff(
                        "insertion",
                        new byte[0],
                        bytes("line\n".repeat(100_000)),
                        FileMode.REGULAR_FILE,
                        FileMode.REGULAR_FILE);
        assertEquals(1, result.hunks().size());
        assertEquals(100_000, result.hunks().getFirst().newCount());
    }
}
