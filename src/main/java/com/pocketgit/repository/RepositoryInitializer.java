package com.pocketgit.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pocketgit.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Initializes only newly claimed metadata. Existing metadata is never modified. */
public final class RepositoryInitializer {
    public record InitializationResult(Repository repository, boolean created) {}

    private record EmptyIndex(int version, List<Object> entries) {}

    public InitializationResult initialize(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize().toRealPath();
        if (!Files.readAttributes(root, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("initialization requires a directory: " + root);
        }
        Repository repository = new Repository(root);
        try {
            // CREATE_DIRECTORY is the ownership gate: only one initializer writes metadata.
            Files.createDirectory(repository.metadataDirectory());
        } catch (FileAlreadyExistsException existing) {
            validateExistingStructure(repository);
            return new InitializationResult(repository, false);
        }
        // If I/O fails, preserve partial metadata for inspection rather than deleting files.
        Files.createDirectory(repository.objectsDirectory());
        Files.createDirectory(repository.refsDirectory());
        Files.createDirectory(repository.headsDirectory());
        Files.createDirectory(repository.logsDirectory());
        writeNew(repository.headFile(), "ref: refs/heads/main\n");
        writeNew(repository.mainRefFile(), "");
        String index =
                new ObjectMapper()
                                .writer(JsonUtils.prettyPrinter())
                                .writeValueAsString(new EmptyIndex(1, List.of()))
                        + "\n";
        writeNew(repository.indexFile(), index);
        writeNew(repository.configFile(), "{\n  \"user\": {}\n}\n");
        return new InitializationResult(repository, true);
    }

    private void validateExistingStructure(Repository repository) throws IOException {
        for (Path path :
                List.of(
                        repository.metadataDirectory(),
                        repository.objectsDirectory(),
                        repository.refsDirectory(),
                        repository.headsDirectory(),
                        repository.logsDirectory())) {
            requireKind(path, true);
        }
        for (Path path : List.of(repository.headFile(), repository.indexFile())) {
            requireKind(path, false);
        }
        // Branches can change after initialization; do not require refs/heads/main forever.
    }

    private void requireKind(Path path, boolean directory) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new InvalidRepositoryException(
                    "incomplete repository; missing "
                            + path
                            + ". Existing metadata was preserved.");
        }
        if (attributes.isSymbolicLink()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new InvalidRepositoryException(
                    "invalid repository metadata: " + path + ". Existing metadata was preserved.");
        }
    }

    private void writeNew(Path path, String content) throws IOException {
        Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }
}
