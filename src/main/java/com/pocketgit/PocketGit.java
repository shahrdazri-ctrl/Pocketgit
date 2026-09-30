package com.pocketgit;

import com.pocketgit.cli.InitCommand;
import com.pocketgit.cli.VerifyCommand;
import com.pocketgit.validation.IntegrityChecker;
import com.pocketgit.cli.DiffCommand;
import com.pocketgit.cli.RestoreCommand;
import com.pocketgit.services.DiffService;
import com.pocketgit.services.RestoreService;
import com.pocketgit.cli.CheckoutCommand;
import com.pocketgit.services.CheckoutService;
import com.pocketgit.cli.LogCommand;
import com.pocketgit.cli.ShowCommand;
import com.pocketgit.cli.BranchCommand;
import com.pocketgit.services.LogService;
import com.pocketgit.refs.BranchService;
import com.pocketgit.cli.CatObjectCommand;
import com.pocketgit.cli.AddCommand;
import com.pocketgit.cli.CommitCommand;
import com.pocketgit.cli.ConfigCommand;
import com.pocketgit.cli.StatusCommand;
import com.pocketgit.cli.RootCommand;
import com.pocketgit.repository.RepositoryInitializer;
import com.pocketgit.services.ObjectInspectionService;
import com.pocketgit.services.AddService;
import com.pocketgit.services.CommitService;
import com.pocketgit.services.StatusService;
import java.io.OutputStream;
import java.nio.file.Path;
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
        command.addSubcommand(new AddCommand(workingDirectory, new AddService()));
        command.addSubcommand(new CommitCommand(workingDirectory, new CommitService()));
        command.addSubcommand(new ConfigCommand(workingDirectory));
        command.addSubcommand(new StatusCommand(workingDirectory, new StatusService()));
        command.addSubcommand(new LogCommand(workingDirectory, new LogService()));
        command.addSubcommand(new ShowCommand(workingDirectory, new LogService()));
        command.addSubcommand(new BranchCommand(workingDirectory, new BranchService()));
        command.addSubcommand(new CheckoutCommand(workingDirectory, new CheckoutService()));
        command.addSubcommand(new DiffCommand(workingDirectory, new DiffService()));
        command.addSubcommand(new RestoreCommand(workingDirectory, new RestoreService()));
        command.addSubcommand(new VerifyCommand(workingDirectory, new IntegrityChecker()));
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
