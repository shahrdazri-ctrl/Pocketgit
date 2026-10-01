package com.pocketgit.cli;

import com.pocketgit.services.StatusService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Show staged, unstaged, and untracked changes.")
public final class StatusCommand implements Callable<Integer> {
    private final Path cwd;
    private final StatusService service;

    @Option(names = "--ignored", description = "Also show ignored untracked paths.")
    private boolean ignored;

    @Spec private CommandSpec spec;

    public StatusCommand(Path cwd, StatusService service) {
        this.cwd = cwd;
        this.service = service;
    }

    @Override
    public Integer call() throws Exception {
        spec.commandLine()
                .getOut()
                .print(new StatusFormatter().format(service.status(cwd), ignored));
        spec.commandLine().getOut().flush();
        return 0;
    }
}
