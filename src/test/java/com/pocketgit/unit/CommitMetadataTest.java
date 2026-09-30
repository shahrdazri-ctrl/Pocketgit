package com.pocketgit.unit;

import com.pocketgit.refs.HeadManager;
import com.pocketgit.refs.RefStore;
import com.pocketgit.repository.Repository;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.storage.ConfigStore;
import com.pocketgit.storage.MetadataFiles;
import com.pocketgit.storage.ReflogStore;
import com.pocketgit.validation.RefNameValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CommitMetadataTest {
    @TempDir Path temp;
    private Repository repo;
    private ConfigStore config;
    @BeforeEach void initialize() throws Exception {
        repo = new RepositoryInitializer().initialize(Files.createDirectory(temp.resolve("repo"))).repository();
        config = new ConfigStore(repo);
    }

    @Test void configSurvivesReloadAndSupportsLegacyRepositoriesAndEnvironmentOverrides() throws Exception {
        assertThrows(IOException.class, () -> config.get("user.name"));
        Files.delete(repo.configFile());
        config.set("user.name", " Jane 日本語 "); config.set("user.email", "jane@example.com");
        var reloaded = new ConfigStore(repo);
        assertEquals("Jane 日本語", reloaded.get("user.name")); assertEquals("jane@example.com", reloaded.get("user.email"));
        assertEquals("Jane 日本語", reloaded.resolveAuthor(key -> null).name());
        var environment = Map.of("POCKETGIT_AUTHOR_NAME", "Env User");
        assertEquals("Env User", reloaded.resolveAuthor(environment::get).name());
        assertEquals("jane@example.com", reloaded.resolveAuthor(environment::get).email());
        assertThrows(IOException.class, () -> reloaded.resolveAuthor(key -> ""));
        assertFalse(Files.readString(repo.configFile()).contains("\r"));
        assertFalse(Files.exists(repo.metadataDirectory().resolve("config.lock")));
    }

    @Test void configRejectsBadKeysValuesAndExistingLocksWithoutOverwriting() throws Exception {
        byte[] before = Files.readAllBytes(repo.configFile());
        for (String key : new String[]{"other", "user", null}) assertThrows(IOException.class, () -> config.set(key, "value"));
        for (String value : new String[]{"", " ", "line\nbreak", null}) assertThrows(IOException.class, () -> config.set("user.name", value));
        assertThrows(IOException.class, () -> config.set("user.email", "not-an-email"));
        Path lock = Files.writeString(repo.metadataDirectory().resolve("config.lock"), "busy");
        assertThrows(IOException.class, () -> config.set("user.name", "Jane"));
        assertEquals("busy", Files.readString(lock)); assertArrayEquals(before, Files.readAllBytes(repo.configFile()));
    }

    @Test void oversizedConfigUpdatesPreserveReadableMetadata() throws Exception {
        byte[] before = Files.readAllBytes(repo.configFile());
        assertThrows(IOException.class, () -> config.set("user.name", "x".repeat(ConfigStore.MAX_CONFIG_BYTES)));
        assertArrayEquals(before, Files.readAllBytes(repo.configFile()));
        assertFalse(Files.exists(repo.metadataDirectory().resolve("config.lock")));
        Files.writeString(repo.configFile(), "x".repeat(ConfigStore.MAX_CONFIG_BYTES + 1));
        assertThrows(IOException.class, () -> config.get("user.name"));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"user\":null}", "{\"user\":{},\"unknown\":1}", "{\"user\":{},\"user\":{}}", "{\"user\":{\"name\":123}}", "{\"user\":{\"name\":true}}", "{\"user\":{\"email\":1.5}}", "{\"user\":{}} {}"})
    void corruptConfigIsNeverSilentlyOverwritten(String json) throws Exception {
        Files.writeString(repo.configFile(), json);
        assertThrows(IOException.class, () -> config.set("user.name", "Jane"));
        assertThrows(IOException.class, () -> config.resolveAuthor(key -> "env"));
        assertEquals(json, Files.readString(repo.configFile()));
        assertFalse(Files.exists(repo.metadataDirectory().resolve("config.lock")));
    }

    @ParameterizedTest @ValueSource(strings = {"../outside", "a//b", "a.lock", ".hidden", "a.", "a b", "a\nb", "a\\b", "HEAD", "a@{b", "/absolute", "-option", "a:b", "a?b", "a[b"})
    void invalidBranchNamesAreRejected(String branch) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RefNameValidator.validateBranch(branch));
        assertThrows(IOException.class, () -> new RefStore(repo).readBranch(branch));
    }

    @Test void headAndRefsReadNestedBranchesAndRejectDetachedOrMalformedHead() throws Exception {
        Files.createDirectory(repo.headsDirectory().resolve("feature"));
        Files.writeString(repo.headsDirectory().resolve("feature/work"), "");
        Files.writeString(repo.headFile(), "ref: refs/heads/feature/work\n");
        assertEquals("feature/work", new HeadManager(repo).readBranch());
        assertNull(new RefStore(repo).readBranch("feature/work"));
        for (String value : new String[]{"a".repeat(64), "ref: refs/tags/main\n", "ref: refs/heads/main\n\n", "ref: refs/heads/main\r\n"}) {
            Files.writeString(repo.headFile(), value);
            assertThrows(IOException.class, () -> new HeadManager(repo).readBranch());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"config", "HEAD", "refs", "ref", "logs", "log"})
    void metadataSymlinksAreRejectedAndTargetsUntouched(String kind) throws Exception {
        Path outside = Files.writeString(temp.resolve("outside"), "private");
        Path link = switch (kind) {
            case "config" -> repo.configFile(); case "HEAD" -> repo.headFile();
            case "refs" -> repo.headsDirectory(); case "ref" -> repo.mainRefFile();
            case "logs" -> repo.logsDirectory(); default -> repo.logsDirectory().resolve("HEAD");
        };
        if (kind.equals("refs")) Files.delete(repo.mainRefFile());
        Files.deleteIfExists(link);
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException unavailable) { assumeTrue(false, "symlinks unavailable"); }
        assertThrows(IOException.class, () -> {
            switch (kind) {
                case "config" -> config.set("user.name", "Jane");
                case "HEAD" -> new HeadManager(repo).readBranch();
                case "refs", "ref" -> new RefStore(repo).readBranch("main");
                default -> new ReflogStore(repo).prepareAppend(null, "a".repeat(64), Instant.EPOCH);
            }
        });
        assertEquals("private", Files.readString(outside)); assertTrue(Files.isSymbolicLink(link));
    }

    @Test void preparedMetadataIsInvisibleUntilPublicationAndCleansAbandonedTemporaries() throws Exception {
        var files = new MetadataFiles(repo);
        byte[] before = Files.readAllBytes(repo.configFile());
        try (var prepared = files.prepare(repo.configFile(), "new".getBytes())) { assertArrayEquals(before, Files.readAllBytes(repo.configFile())); }
        assertArrayEquals(before, Files.readAllBytes(repo.configFile()));
        try (var prepared = files.prepare(repo.configFile(), "new".getBytes())) {
            prepared.publish(); assertEquals("new", Files.readString(repo.configFile()));
            assertThrows(IllegalStateException.class, prepared::publish);
        }
        assertThrows(IOException.class, () -> files.prepare(temp.resolve("escape"), new byte[0]));
        try (var paths = Files.walk(repo.metadataDirectory())) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".metadata-"))); }
    }

    @Test void reflogReplacementRechecksDestinationAndRejectsInvalidUtf8() throws Exception {
        Path log = repo.logsDirectory().resolve("HEAD");
        try (var prepared = new ReflogStore(repo).prepareAppend(null, "a".repeat(64), Instant.EPOCH)) {
            Files.createDirectory(log);
            assertThrows(IOException.class, prepared::publish);
        }
        Files.delete(log); Files.write(log, new byte[]{(byte) 0xff, '\n'});
        assertThrows(IOException.class, () -> new ReflogStore(repo).prepareAppend(null, "a".repeat(64), Instant.EPOCH));
    }
}
