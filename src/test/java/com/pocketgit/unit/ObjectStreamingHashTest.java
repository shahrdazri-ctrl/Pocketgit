package com.pocketgit.unit;

import com.pocketgit.model.ObjectType;
import com.pocketgit.storage.ObjectHasher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObjectStreamingHashTest {
    private final ObjectHasher hasher = new ObjectHasher();

    @Test void streamingHashesMatchCanonicalByteHashesForEmptyBinaryAndLargePayloads() throws Exception {
        byte[] large = new byte[1024 * 1024 + 7]; new java.util.Random(21).nextBytes(large);
        for (byte[] payload : new byte[][]{new byte[0], new byte[]{0, -1, -128, 13, 10}, large}) {
            for (ObjectType type : ObjectType.values()) {
                assertEquals(hasher.hash(type, payload), hasher.hash(type, payload.length, new ByteArrayInputStream(payload)));
            }
        }
    }

    @Test void rejectsShortLongAndNegativePayloadLengths() {
        byte[] bytes = new byte[]{1, 2, 3};
        assertThrows(IOException.class, () -> hasher.hash(ObjectType.BLOB, 4, new ByteArrayInputStream(bytes)));
        assertThrows(IOException.class, () -> hasher.hash(ObjectType.BLOB, 2, new ByteArrayInputStream(bytes)));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(ObjectType.BLOB, -1, new ByteArrayInputStream(bytes)));
    }

    @Test void retainsCallerOwnershipAndPropagatesReadFailures() throws Exception {
        var input = new ByteArrayInputStream(new byte[]{1}) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        hasher.hash(ObjectType.BLOB, 1, input); assertFalse(input.closed);
        var broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("read failed"); }
        };
        assertEquals("read failed", assertThrows(IOException.class, () -> hasher.hash(ObjectType.BLOB, 1, broken)).getMessage());
    }
}
