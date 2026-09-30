package com.pocketgit.cli;

import com.pocketgit.services.CommitService;
import com.pocketgit.util.TerminalText;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "commit",
        mixinStandardHelpOptions = true,
        description = "Commit the staged snapshot on the current branch.")
public final class CommitCommand implements Callable<Integer> {
    private final Path cwd;
    private final CommitService service;

    @Option(
            names = {"-m", "--message"},
            required = true,
            description = "Commit message.")
    private String message;

    @Option(names = "--allow-empty", description = "Permit an unchanged or empty snapshot.")
    private boolean allowEmpty;

    @Spec private CommandSpec spec;

    public CommitCommand(Path cwd, CommitService service) {
        this.cwd = cwd;
        this.service = service;
    }

    @Override
    public Integer call() throws Exception {
        var result = service.commit(cwd, message, allowEmpty);
        var out = spec.commandLine().getOut();
        if (!result.created()) out.println("Nothing to commit.");
        else {
            out.println(
                    "["
                            + result.branch()
                            + " "
                            + result.commitHash().substring(0, 7)
                            + "] "
                            + TerminalText.escape(message.split("\\R", 2)[0]));
            out.println(" " + result.changedFiles() + " files changed");
        }
        return 0;
    }
}
