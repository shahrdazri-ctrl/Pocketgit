package com.pocketgit.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.pocketgit.model.AuthorIdentity;
import com.pocketgit.repository.Repository;
import com.pocketgit.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.function.Function;

public final class ConfigStore {
    public static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private record User(String name, String email) {}
    private record Config(User user) {}
    private final Repository repository;
    private final MetadataFiles files;
    private final JsonMapper mapper = JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public ConfigStore(Repository repository) {
        this.repository = repository;
        files = new MetadataFiles(repository);
        // Jackson's textual coercions are independent of ALLOW_COERCION_OF_SCALARS.
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
    }

    private User load() throws IOException {
        byte[] bytes;
        try { bytes = files.read(repository.configFile(), MAX_CONFIG_BYTES); }
        catch (NoSuchFileException absent) { files.requireDirectory(repository.metadataDirectory()); return new User(null, null); }
        try {
            Config config = mapper.readValue(bytes, Config.class);
            if (config == null || config.user() == null) throw new IOException("missing config user object");
            return config.user();
        } catch (IOException | RuntimeException invalid) { throw new IOException("invalid repository config: " + invalid.getMessage(), invalid); }
    }

    public String get(String key) throws IOException {
        validateKey(key);
        User user = load();
        String value = key.equals("user.name") ? user.name() : user.email();
        if (value == null || value.isBlank()) throw new IOException(key + " is not configured");
        return value;
    }

    public void set(String key, String value) throws IOException {
        validateKey(key);
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) throw new IOException("config value must be nonblank and contain no control characters");
        value = value.strip();
        if (key.equals("user.email")) {
            try { new AuthorIdentity("validation", value); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
        }
        try (var lock = MetadataLock.acquire(files, repository.metadataDirectory().resolve("config.lock"))) {
            User previous = load();
            User next = key.equals("user.name") ? new User(value, previous.email()) : new User(previous.name(), value);
            byte[] json = (mapper.writer(JsonUtils.prettyPrinter()).writeValueAsString(new Config(next)) + "\n").getBytes(StandardCharsets.UTF_8);
            if (json.length > MAX_CONFIG_BYTES) throw new IOException("config exceeds 1 MiB limit");
            try (var prepared = files.prepare(repository.configFile(), json)) { prepared.publish(); }
        }
    }

    public AuthorIdentity resolveAuthor(Function<String, String> environment) throws IOException {
        User configured = load();
        String name = environment.apply("POCKETGIT_AUTHOR_NAME");
        String email = environment.apply("POCKETGIT_AUTHOR_EMAIL");
        if (name == null) name = configured.name();
        if (email == null) email = configured.email();
        try { return new AuthorIdentity(name, email); }
        catch (IllegalArgumentException invalid) {
            throw new IOException("Author identity not configured or invalid. Run: pocketgit config user.name \"Your Name\" and pocketgit config user.email \"you@example.com\"", invalid);
        }
    }

    private void validateKey(String key) throws IOException {
        if (!"user.name".equals(key) && !"user.email".equals(key)) throw new IOException("unsupported config key: " + key);
    }
}
