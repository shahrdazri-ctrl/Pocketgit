package com.pocketgit;

import com.pocketgit.cli.InitCommand;
import com.pocketgit.cli.CatObjectCommand;
import com.pocketgit.cli.PlaceholderCommand;
import com.pocketgit.cli.RootCommand;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.ObjectInspectionService;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine;

public final class PocketGit {
    private PocketGit() {}

    /** Explicit working directory keeps CLI tests independent of process-global state. */
    public static CommandLine commandLine(Path workingDirectory) {
        return commandLine(workingDirectory, System.out);
    }

    /** Raw bytes use a separate stream so binary payloads never pass through a text writer. */
    public static CommandLine commandLine(Path workingDirectory, OutputStream rawOutput) {
        CommandLine command = new CommandLine(new RootCommand());
        command.addSubcommand(new InitCommand(workingDirectory, new RepositoryInitializer()));
        command.addSubcommand(new CatObjectCommand(workingDirectory, new ObjectInspectionService(), rawOutput));
        for (String name : List.of("status", "add", "commit", "log", "diff", "branch", "checkout", "restore")) {
            CommandLine placeholder = new CommandLine(new PlaceholderCommand());
            placeholder.getCommandSpec().name(name);
            placeholder.getCommandSpec().usageMessage().description("Not implemented yet (future phase).");
            command.addSubcommand(name, placeholder);
        }
        command.setExecutionExceptionHandler((exception, cli, parsed) -> {
            String detail = exception.getMessage();
            cli.getErr().println("error: " + (detail == null ? "operation failed" : detail));
            return 1;
        });
        return command;
    }

    public static void main(String[] args) {
        System.exit(commandLine(Path.of("")).execute(args));
    }
}
