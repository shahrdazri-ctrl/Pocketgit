package com.pocketgit.gui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;

/** Classpath launcher: JavaFX is initialized only when the optional GUI is requested. */
public final class GuiLauncher {
    private GuiLauncher() {}

    public static void launch(Path repository) throws IOException, InterruptedException {
        var closed = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try {
            Platform.startup(
                    () -> {
                        try {
                            new HistoryViewer(repository, closed::countDown, failure::set).show();
                        } catch (Throwable error) {
                            failure.set(error);
                            closed.countDown();
                            Platform.exit();
                        }
                    });
        } catch (RuntimeException error) {
            throw new IOException(
                    "cannot initialize JavaFX; a desktop display and supported platform libraries"
                            + " are required: "
                            + error.getMessage(),
                    error);
        }
        closed.await();
        Platform.exit();
        if (failure.get() != null) {
            throw new IOException(
                    "desktop viewer failed: " + failure.get().getMessage(), failure.get());
        }
    }
}
