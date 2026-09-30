package com.pocketgit.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exercise Picocli's UTF-8 argument-file route for text outside a native launcher code page. */
final class CliArguments {
    private CliArguments() {}

    static List<String> portable(Path temporary, String... arguments) throws IOException {
        if (List.of(arguments).stream()
                .noneMatch(argument -> argument.codePoints().anyMatch(c -> c > 127))) {
            return List.of(arguments);
        }
        StringBuilder content = new StringBuilder();
        for (String argument : arguments) {
            content.append('"')
                    .append(
                            argument.replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\r", "\\r")
                                    .replace("\n", "\\n"))
                    .append("\"\n");
        }
        Path file = temporary.resolve("utf8-arguments.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return List.of("@" + file);
    }
}
