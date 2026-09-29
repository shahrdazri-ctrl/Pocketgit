package com.pocketgit.repository;

import java.nio.file.Path;
import java.util.Objects;

/** Central path vocabulary for a PocketGit working tree. Does not perform I/O. */
public record Repository(Path root) {
    public static final String METADATA_NAME = ".pocketgit";

    public Repository {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path metadataDirectory() { return root.resolve(METADATA_NAME); }
    public Path objectsDirectory() { return metadataDirectory().resolve("objects"); }
    public Path refsDirectory() { return metadataDirectory().resolve("refs"); }
    public Path headsDirectory() { return refsDirectory().resolve("heads"); }
    public Path headFile() { return metadataDirectory().resolve("HEAD"); }
    public Path indexFile() { return metadataDirectory().resolve("index"); }
    public Path configFile() { return metadataDirectory().resolve("config"); }
    public Path logsDirectory() { return metadataDirectory().resolve("logs"); }
    public Path mainRefFile() { return headsDirectory().resolve("main"); }
}
