package com.pocketgit.storage;

import com.pocketgit.model.Blob;
import com.pocketgit.model.ObjectType;
import com.pocketgit.model.StoredObject;
import com.pocketgit.repository.Repository;
import java.io.IOException;
import java.nio.file.Path;

/** Public object database facade for future staging, trees, and commits. */
public final class ObjectStore {
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private final ObjectPaths paths;
    private final ObjectReader reader;
    private final ObjectWriter writer;

    public ObjectStore(Repository repository) throws IOException { this(repository, DEFAULT_MAX_PAYLOAD_BYTES); }

    public ObjectStore(Repository repository, int maxPayloadBytes) throws IOException {
        if (maxPayloadBytes < 0 || maxPayloadBytes > Integer.MAX_VALUE - ObjectReader.MAX_HEADER_BYTES) {
            throw new IllegalArgumentException("invalid object payload limit");
        }
        paths = new ObjectPaths(repository);
        reader = new ObjectReader(paths, maxPayloadBytes);
        writer = new ObjectWriter(paths, reader, maxPayloadBytes);
    }

    public Path pathForHash(String hash) { return paths.pathForHash(hash); }
    public String write(ObjectType type, byte[] payload) throws IOException { return writer.write(type, payload); }
    public String writeBlob(Blob blob) throws IOException { return write(ObjectType.BLOB, blob.content()); }
    public StoredObject read(String hash) throws IOException { return reader.read(hash); }
    public Blob readBlob(String hash) throws IOException {
        StoredObject object = read(hash);
        if (object.type() != ObjectType.BLOB) throw new IOException("object is not a blob: " + hash);
        return new Blob(object.payload());
    }
}
