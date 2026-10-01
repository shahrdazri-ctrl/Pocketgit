package com.pocketgit.cli;

import com.pocketgit.repository.RepositoryLocator;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/** Keeps the standard CLI artifact independent of JavaFX and its native libraries. */
@Command(
        name = "gui",
        mixinStandardHelpOptions = true,
        description = "Open the optional read-only desktop history viewer.")
public final class GuiCommand implements Callable<Integer> {
    private final Path cwd;

    public GuiCommand(Path cwd) {
        this.cwd = cwd;
    }

    @Override
    public Integer call() throws Exception {
        Path root =
                new RepositoryLocator()
                        .findRepositoryRoot(cwd)
                        .orElseThrow(() -> new IOException("not a PocketGit repository"));
        try {
            Class.forName("com.pocketgit.gui.GuiLauncher")
                    .getMethod("launch", Path.class)
                    .invoke(null, root);
        } catch (ClassNotFoundException missing) {
            throw new IOException(
                    "desktop viewer is not included in this CLI build; build with mvn -Pgui package"
                            + " and use the GUI artifact",
                    missing);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IOException("cannot start desktop viewer: " + cause.getMessage(), cause);
        }
        return 0;
    }
}
