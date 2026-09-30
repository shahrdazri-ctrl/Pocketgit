package com.pocketgit.cli;

import com.pocketgit.services.ObjectInspectionService;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "cat-object",
        mixinStandardHelpOptions = true,
        description = "Inspect an object; default output is the exact raw payload bytes.")
public final class CatObjectCommand implements Callable<Integer> {
    private final Path workingDirectory;
    private final ObjectInspectionService service;
    private final OutputStream rawOutput;

    @Parameters(index = "0", paramLabel = "HASH", description = "Full lowercase SHA-256 object ID.")
    private String hash;

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    private Mode mode;

    @Spec private CommandSpec spec;

    static final class Mode {
        @Option(names = "--type", description = "Print the object type.")
        boolean type;

        @Option(names = "--size", description = "Print the payload byte length.")
        boolean size;

        @Option(names = "--pretty", description = "Print UTF-8 text; reject binary payloads.")
        boolean pretty;
    }

    public CatObjectCommand(
            Path workingDirectory, ObjectInspectionService service, OutputStream rawOutput) {
        this.workingDirectory = workingDirectory;
        this.service = service;
        this.rawOutput = rawOutput;
    }

    @Override
    public Integer call() throws Exception {
        if (mode != null && mode.type)
            spec.commandLine()
                    .getOut()
                    .println(service.verify(workingDirectory, hash).type().token());
        else if (mode != null && mode.size)
            spec.commandLine().getOut().println(service.verify(workingDirectory, hash).size());
        else if (mode != null && mode.pretty) {
            service.writePretty(workingDirectory, hash, spec.commandLine().getOut());
        } else {
            service.copy(workingDirectory, hash, rawOutput);
            rawOutput.flush();
        }
        spec.commandLine().getOut().flush();
        if (spec.commandLine().getOut().checkError())
            throw new java.io.IOException("could not write object inspection output");
        return 0;
    }
}
