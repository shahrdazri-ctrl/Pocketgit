package com.pocketgit.storage;

import com.pocketgit.repository.InvalidRepositoryException;
import com.pocketgit.repository.Repository;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Bounded no-follow metadata reads and fully prepared atomic replacements. */
public final class MetadataFiles {
    private final Repository repository;

    public MetadataFiles(Repository repository) {
        this.repository = repository;
    }

    public void validateTarget(Path path) throws IOException {
        if (!path.equals(path.toAbsolutePath().normalize())
                || !path.startsWith(repository.metadataDirectory())
                || path.equals(repository.metadataDirectory())) {
            throw new IOException("metadata path escapes repository");
        }
        Path directory = repository.metadataDirectory();
        requireDirectory(directory);
        for (Path part : directory.relativize(path.getParent())) {
            if (part.toString().isEmpty()) continue;
            directory = directory.resolve(part);
            requireDirectory(directory);
        }
        try {
            var attributes =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                throw new IOException("metadata must be a regular file: " + path);
        } catch (NoSuchFileException missing) {
            /* Some metadata, such as config/logs, can be created. */
        }
    }

    public void requireDirectory(Path path) throws IOException {
        var attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink())
            throw new InvalidRepositoryException("metadata must be a real directory: " + path);
    }

    public byte[] read(Path path, int limit) throws IOException {
        validateTarget(path);
        try (var input =
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit)
                throw new IOException("metadata size limit exceeded: " + path);
            return bytes;
        }
    }

    public String readText(Path path, int limit) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(read(path, limit)))
                .toString();
    }

    public Prepared prepare(Path destination, byte[] bytes) throws IOException {
        validateTarget(destination);
        Path temporary = Files.createTempFile(destination.getParent(), ".metadata-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            return new Prepared(destination, temporary);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public final class Prepared implements AutoCloseable {
        private final Path destination;
        private final Path temporary;
        private boolean published;
        private boolean closed;

        private Prepared(Path destination, Path temporary) {
            this.destination = destination;
            this.temporary = temporary;
        }

        public void publish() throws IOException {
            if (published || closed)
                throw new IllegalStateException(
                        "metadata replacement is closed or already published");
            validateTarget(destination);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException(
                        "filesystem requires atomic metadata replacement", unsupported);
            }
            published = true;
        }

        /** Exclusively publishes complete bytes; never replaces an existing reference. */
        public void publishNew() throws IOException {
            if (published || closed)
                throw new IllegalStateException(
                        "metadata replacement is closed or already published");
            validateTarget(destination);
            Files.createLink(destination, temporary);
            published = true;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            Files.deleteIfExists(temporary);
        }
    }
}
