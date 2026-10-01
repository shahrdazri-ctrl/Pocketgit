package com.pocketgit.services;

import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.ObjectCodec;
import com.pocketgit.storage.ObjectReader;
import com.pocketgit.storage.ObjectStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Read-only object inspection, reusable without the CLI. */
public final class ObjectInspectionService {
    public static final int MAX_PRETTY_BYTES = 8 * 1024 * 1024;

    private ObjectStore store(Path cwd) throws IOException {
        Path root =
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository"));
        return new ObjectStore(new Repository(root));
    }

    public ObjectReader.Summary verify(Path cwd, String hash) throws IOException {
        return store(cwd).verify(hash);
    }

    public void copy(Path cwd, String hash, OutputStream output) throws IOException {
        var objects = store(cwd);
        var verified = objects.verify(hash);
        // Verification must succeed before raw output receives any bytes.
        objects.copy(hash, verified.type(), output);
    }

    public StoredObject inspect(Path cwd, String hash) throws IOException {
        return store(cwd).read(hash);
    }

    public void writePretty(Path cwd, String hash, Appendable output) throws IOException {
        var objects = store(cwd);
        requirePrettySize(objects.verify(hash).size());
        writePretty(objects.read(hash), output);
    }

    private void requirePrettySize(int size) throws IOException {
        if (size > MAX_PRETTY_BYTES) {
            throw new IOException(
                    "payload exceeds 8 MiB pretty-print limit; use cat-object HASH > output.bin");
        }
    }

    public String prettyPayload(StoredObject object) throws IOException {
        return com.pocketgit.util.TerminalText.escape(prettyText(object));
    }

    public void writePretty(StoredObject object, Appendable output) throws IOException {
        com.pocketgit.util.TerminalText.appendEscaped(prettyText(object), output);
    }

    private String prettyText(StoredObject object) throws IOException {
        requirePrettySize(object.size());
        if (object.type() != ObjectType.BLOB) return new ObjectCodec().pretty(object);
        byte[] payload = object.payload();
        for (byte value : payload) {
            if (value == 0) throw binaryPrettyError();
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException binary) {
            throw binaryPrettyError();
        }
    }

    private IOException binaryPrettyError() {
        return new IOException(
                "binary payload cannot be displayed with --pretty; use cat-object HASH >"
                        + " output.bin");
    }
}
