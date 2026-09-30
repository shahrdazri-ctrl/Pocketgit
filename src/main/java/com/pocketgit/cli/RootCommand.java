package com.pocketgit.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "pocketgit", mixinStandardHelpOptions = true, version = "PocketGit 1.0.0",
        description = "A lightweight version-control system written in Java.")
public final class RootCommand implements Runnable {
    @Spec private CommandSpec spec;

    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
}
