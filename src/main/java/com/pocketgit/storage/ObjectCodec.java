package com.pocketgit.storage;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pocketgit.model.Commit;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;
import com.pocketgit.model.Tree;
import com.pocketgit.util.JsonUtils;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Canonical compact JSON for Tree/Commit payloads, with strict semantic decoding. */
public final class ObjectCodec {
    @JsonPropertyOrder({
        "treeHash",
        "parentHashes",
        "authorName",
        "authorEmail",
        "timestamp",
        "message"
    })
    private record CommitPayload(
            String treeHash,
            List<String> parentHashes,
            String authorName,
            String authorEmail,
            String timestamp,
            String message) {}

    private final ObjectMapper mapper =
            JsonMapper.builder()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();

    public byte[] encodeTree(Tree tree) throws IOException {
        return mapper.writeValueAsBytes(tree);
    }

    public byte[] encodeCommit(Commit commit) throws IOException {
        return mapper.writeValueAsBytes(payload(commit));
    }

    private CommitPayload payload(Commit commit) {
        return new CommitPayload(
                commit.treeHash(),
                commit.parentHashes(),
                commit.authorName(),
                commit.authorEmail(),
                commit.timestamp().toString(),
                commit.message());
    }

    public Tree decodeTree(StoredObject object) throws IOException {
        requireType(object, ObjectType.TREE);
        try {
            Tree tree = mapper.readValue(object.payload(), Tree.class);
            if (tree == null || !Arrays.equals(object.payload(), encodeTree(tree)))
                throw new IOException("noncanonical tree payload");
            return tree;
        } catch (IOException | RuntimeException invalid) {
            throw new CorruptObjectException(
                    "invalid tree payload: " + invalid.getMessage(), invalid);
        }
    }

    public Commit decodeCommit(StoredObject object) throws IOException {
        requireType(object, ObjectType.COMMIT);
        try {
            var value = mapper.readValue(object.payload(), CommitPayload.class);
            if (value == null) throw new IOException("null commit payload");
            Commit commit =
                    new Commit(
                            value.treeHash(),
                            value.parentHashes(),
                            value.authorName(),
                            value.authorEmail(),
                            Instant.parse(value.timestamp()),
                            value.message());
            if (!Arrays.equals(object.payload(), encodeCommit(commit)))
                throw new IOException("noncanonical commit payload");
            return commit;
        } catch (IOException | RuntimeException invalid) {
            throw new CorruptObjectException(
                    "invalid commit payload: " + invalid.getMessage(), invalid);
        }
    }

    public String pretty(StoredObject object) throws IOException {
        if (object.type() == ObjectType.TREE) decodeTree(object);
        else decodeCommit(object);
        return mapper.writer(JsonUtils.prettyPrinter())
                        .writeValueAsString(mapper.readTree(object.payload()))
                + "\n";
    }

    private void requireType(StoredObject object, ObjectType type) throws CorruptObjectException {
        if (object.type() != type)
            throw new CorruptObjectException(
                    "expected " + type.token() + " object, found " + object.type().token());
    }
}
