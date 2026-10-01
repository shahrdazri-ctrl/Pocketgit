package com.pocketgit.cli;

import com.pocketgit.services.ConfigService;
import com.pocketgit.util.TerminalText;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "config",
        mixinStandardHelpOptions = true,
        description = "Read or set local user.name/user.email.")
public final class ConfigCommand implements Callable<Integer> {
    private final Path cwd;

    @Parameters(index = "0", paramLabel = "KEY")
    private String key;

    @Parameters(index = "1", arity = "0..1", paramLabel = "VALUE")
    private String value;

    @Spec private CommandSpec spec;

    public ConfigCommand(Path cwd) {
        this.cwd = cwd;
    }

    @Override
    public Integer call() throws Exception {
        var service = new ConfigService();
        if (value == null)
            spec.commandLine().getOut().println(TerminalText.escapeLabel(service.get(cwd, key)));
        else service.set(cwd, key, value);
        return 0;
    }
}
