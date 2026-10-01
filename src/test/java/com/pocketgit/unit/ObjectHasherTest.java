package com.pocketgit.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.pocketgit.model.Blob;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;
import com.pocketgit.storage.ObjectHasher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ObjectHasherTest {
    private final ObjectHasher hasher = new ObjectHasher();

    @Test
    void matchesIndependentSha256Vectors() {
        assertEquals(
                "473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813",
                hasher.hash(ObjectType.BLOB, new byte[0]));
        assertEquals(
                "fee53a18d32820613c0527aa79be5cb30173c823a9b448fa4817767cc84c6f03",
                hasher.hash(ObjectType.BLOB, "hello world".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                "7ba7013496e406e43d12042351c0109de7a00b487afc42ff83bd86fe9f809c62",
                hasher.hash(ObjectType.BLOB, new byte[] {0, -1, -128, 13, 10}));
    }

    @Test
    void hashesAreDeterministicTypeSensitiveAndContentSensitive() {
        byte[] payload = {1, 2, 3};
        String blob = hasher.hash(ObjectType.BLOB, payload);
        assertTrue(blob.matches("[0-9a-f]{64}"));
        assertEquals(blob, hasher.hash(ObjectType.BLOB, payload.clone()));
        assertNotEquals(blob, hasher.hash(ObjectType.TREE, payload));
        assertNotEquals(blob, hasher.hash(ObjectType.COMMIT, payload));
        assertNotEquals(blob, hasher.hash(ObjectType.BLOB, new byte[] {1, 2, 4}));
    }

    @Test
    void canonicalHeaderUsesByteLengthAndNulDelimiter() {
        byte[] payload = "😀".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[] {'b', 'l', 'o', 'b', ' ', '4', 0, -16, -97, -104, -128};
        assertArrayEquals(expected, hasher.canonicalBytes(ObjectType.BLOB, payload));
    }

    @Test
    void immutableValuesDefendAgainstInputAndAccessorMutation() {
        byte[] input = {1, 2, 3};
        Blob blob = new Blob(input);
        StoredObject object = new StoredObject(ObjectType.BLOB, input);
        input[0] = 9;
        blob.content()[1] = 9;
        object.payload()[2] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, blob.content());
        assertArrayEquals(new byte[] {1, 2, 3}, object.payload());
        assertEquals(new Blob(new byte[] {1, 2, 3}), blob);
        assertEquals(new Blob(new byte[] {1, 2, 3}).hashCode(), blob.hashCode());
        assertEquals(new StoredObject(ObjectType.BLOB, new byte[] {1, 2, 3}), object);
        assertNotEquals(new StoredObject(ObjectType.TREE, new byte[] {1, 2, 3}), object);
    }
}
