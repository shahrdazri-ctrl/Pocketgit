package com.pocketgit.services;

import com.pocketgit.model.StoredObject;
import com.pocketgit.model.ObjectType;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryLocator;
import com.pocketgit.storage.ObjectStore;
import com.pocketgit.storage.ObjectCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Read-only object inspection, reusable without the CLI. */
public final class ObjectInspectionService {
    public StoredObject inspect(Path workingDirectory, String hash) throws IOException {
        Path root = new RepositoryLocator().findRepositoryRoot(workingDirectory)
                .orElseThrow(() -> new IOException("not a PocketGit repository"));
        return new ObjectStore(new Repository(root)).read(hash);
    }

    public String prettyPayload(StoredObject object) throws IOException {
        if (object.type() != ObjectType.BLOB) return new ObjectCodec().pretty(object);
        byte[] payload = object.payload();
        for (byte value : payload) {
            if (value == 0) throw binaryPrettyError();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException binary) { throw binaryPrettyError(); }
    }

    private IOException binaryPrettyError() {
        return new IOException("binary payload cannot be displayed with --pretty; use cat-object HASH > output.bin");
    }
}
