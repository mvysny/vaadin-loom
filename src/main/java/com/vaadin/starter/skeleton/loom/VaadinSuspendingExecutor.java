package com.vaadin.starter.skeleton.loom;

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
 * Runs on Java Virtual Threads (AKA Project Loom).
 */
public final class VaadinSuspendingExecutor implements AutoCloseable {
    @NotNull
    private final SuspendingExecutor suspendingExecutor;
    @NotNull
    private final UI ui;

    public VaadinSuspendingExecutor(@NotNull UI ui) {
        // the carrier threads will always execute with Vaadin Session lock held, and with a non-null UI.current
        suspendingExecutor = new SuspendingExecutor(new UIExecutor(ui), "Vaadin-VirtualThreadExecutor-" + ui);
        this.ui = ui;
    }

    /**
     * You can call this from anywhere, even from a background thread. Runs given Runnable
     * with the Vaadin session lock held. The Runnable is run in a virtual thread,
     * which means that the runnable may block. When it does, all changes done to the Vaadin
     * components are transmitted to the client-side. Upon unblocking, the runnable
     * continues its execution in the Vaadin UI thread.
     * @param runnable the code block to run.
     */
    public void run(@NotNull Runnable runnable) {
        Objects.requireNonNull(runnable);
        suspendingExecutor.run(() -> {
            // now we're running in the virtual thread; the virtual thread is mounted to a Vaadin UI thread.
            //
            // There's a slight problem though:
            // The UI.current is null for the virtual thread since the virtual thread doesn't inherit UI.current
            // from its carrier thread. Fix that.
            try {
                VaadinSession.setCurrent(ui.getSession());
                UI.setCurrent(ui);
                // post-check: make sure everything is set correctly.
                assertUIVirtualThread();

                // Perfect. Now we can run the code.
                runnable.run();
            } catch (Throwable t) {
                ui.getSession().getErrorHandler().error(new ErrorEvent(t));
            } finally {
                // clean up current instances so that they can be GCed if needed.
                UI.setCurrent(null);
                VaadinSession.setCurrent(null);
            }
        });
    }

    /**
     * Closes the executor immediately. Any suspended virtual threads are killed immediately, then garbage-collected eventually.
     */
    @Override
    public void close() {
        suspendingExecutor.close();
    }

    /**
     * Asserts that this thread is a virtual thread which is run in the Vaadin UI thread.
     * It's useful to assert this before attempting to block, to make sure the
     * blocking operation suspends current virtual thread instead.
     */
    public static void assertUIVirtualThread() {
        LoomUtils.assertVirtualThread();
        if (UI.getCurrent() == null) {
            throw new IllegalStateException("UI.getCurrent() is null, this needs to be run in the Vaadin UI thread");
        }
    }

    /**
     * Executor which runs submitted Runnables in the Vaadin UI thread, via {@link UI#access(Command)}.
     * No virtual thread magic happens here - the Runnables are run until they terminate.
     */
    private static class UIExecutor implements Executor {
        @NotNull
        private final UI ui;

        public UIExecutor(@NotNull UI ui) {
            this.ui = Objects.requireNonNull(ui);
        }

        @Override
        public void execute(@NotNull Runnable command) {
            ui.access(() -> {
                // "command" is a Continuation which runs a piece of code.
                // Continuations require native OS threads to run - they can not be run on a virtual thread.
                if (Thread.currentThread().isVirtual()) {
                    // Looks like Jetty uses virtual threads to serve http requests. There's a bit of a problem with that.
                    //
                    // We are chopping the execution into continuations, then running those continuations in Vaadin UI thread via ui.access().
                    // The problem is that if the UI thread itself is virtual, it can not serve as a carrier thread for the continuation,
                    // and the whole thing blows up with the "java.lang.WrongThreadException"
                    //    at java.base/java.lang.VirtualThread.runContinuation(VirtualThread.java:204)
                    //
                    // Currently there's no solution for that, so fail fast and clean
                    throw new IllegalStateException("http requests seems to be running in virtual threads. This is currently unsupported.");
                }
                command.run();
            });
        }
    }
}
