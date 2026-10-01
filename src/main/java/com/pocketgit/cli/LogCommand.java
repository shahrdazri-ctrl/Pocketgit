package com.pocketgit.cli;

import com.pocketgit.services.LogService;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "log",
        mixinStandardHelpOptions = true,
        description = "Show commit history starting at HEAD.")
public final class LogCommand implements Callable<Integer> {
    private final Path cwd;
    private final LogService service;

    @Option(names = "--oneline", description = "Show short IDs and first message lines.")
    private boolean oneline;

    @Spec private CommandSpec spec;

    public LogCommand(Path cwd, LogService service) {
        this.cwd = cwd;
        this.service = service;
    }

    @Override
    public Integer call() throws Exception {
        var entries = service.log(cwd);
        var text = new StringBuilder();
        var formatter = new CommitFormatter();
        for (var entry : entries) {
            if (!oneline && !text.isEmpty()) text.append('\n');
            text.append(oneline ? formatter.oneline(entry) : formatter.format(entry, false));
        }
        if (entries.isEmpty()) text.append("No commits yet.\n");
        spec.commandLine().getOut().print(text);
        spec.commandLine().getOut().flush();
        return 0;
    }
}
