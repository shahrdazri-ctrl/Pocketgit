package com.pocketgit.cli;

import com.pocketgit.services.LogService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "show",
        mixinStandardHelpOptions = true,
        description = "Show commit metadata by full ID or unique hexadecimal prefix.")
public final class ShowCommand implements Callable<Integer> {
    private final Path cwd;
    private final LogService service;

    @Parameters(
            index = "0",
            paramLabel = "COMMIT",
            description = "Commit ID or unique lowercase hexadecimal prefix.")
    private String prefix;

    @Spec private CommandSpec spec;

    public ShowCommand(Path cwd, LogService service) {
        this.cwd = cwd;
        this.service = service;
    }

    @Override
    public Integer call() throws Exception {
        spec.commandLine()
                .getOut()
                .print(new CommitFormatter().format(service.show(cwd, prefix), true));
        spec.commandLine().getOut().flush();
        return 0;
    }
}
