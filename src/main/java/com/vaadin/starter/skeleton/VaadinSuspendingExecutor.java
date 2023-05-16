package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.VaadinSession;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Executes Runnables in Vaadin UI thread, but in a very special way: when the code blocks,
 * all UI changes are rendered in the browser, and the UI thread is not blocked. You can
 * wait for a button click and unblock the code block to execute further.
 * <p></p>
 * Runs on Loom virtual threads. Obviously uses a very dark magic.
 */
public final class VaadinSuspendingExecutor implements AutoCloseable {
    @NotNull
    private final SuspendingExecutor suspendingExecutor;
    @NotNull
    private final UI ui;

    public VaadinSuspendingExecutor(@NotNull UI ui) {
        suspendingExecutor = new SuspendingExecutor(newUIExecutor(ui));
        this.ui = ui;
    }

    /**
     * You can call this from anywhere, even from a background thread. Runs given runnable
     * in a virtual thread, with the Vaadin session lock held.
     * @param runnable runs.
     */
    public void run(@NotNull Runnable runnable) {
        Objects.requireNonNull(runnable);
        suspendingExecutor.run(() -> {
            // now we're running in the virtual thread.
            // The UI.current is null for the virtual thread since the virtual thread doesn't inherit UI.current
            // from its carrier thread. Fix that.
            try {
                UI.setCurrent(ui);
                VaadinSession.setCurrent(ui.getSession());
                // post-check: make sure everything is set correctly.
                assertUIVirtualThread();

                // there. Now we can run the code.
                runnable.run();
            } catch (Throwable t) {
                ui.getSession().getErrorHandler().error(new ErrorEvent(t));
            }
        });
    }

    @Override
    public void close() throws Exception {
        suspendingExecutor.close();
    }

    /**
     * Runs all Runnables on the UI thread. No virtual thread magic happens here - the Runnables are run until they terminate.
     * @param ui run the runnables on this UI.
     * @return the executor.
     */
    @NotNull
    private static Executor newUIExecutor(@NotNull UI ui) {
        Objects.requireNonNull(ui);
        // We'll construct an executor which runs submitted Runnables in the UI thread, via ui.access()
        return command -> {
            // "command" is a continuation which runs a piece of the virtual thread.
            ui.access((Command) () -> {
                // current thread will become a carrier thread once command.run() is run;
                // the command.run() itself will run in a virtual thread.
                command.run();
            });
        };
    }

    /**
     * Asserts that this thread is a virtual thread which is run in the Vaadin UI thread.
     * It's useful to assert this before attempting to block, to make sure the
     * blocking operation suspends current virtual thread instead.
     */
    public static void assertUIVirtualThread() {
        SuspendingExecutor.assertVirtualThread();
        if (UI.getCurrent() == null) {
            throw new IllegalStateException("UI.getCurrent() is null, this needs to be run in the Vaadin UI thread");
        }
    }
}
