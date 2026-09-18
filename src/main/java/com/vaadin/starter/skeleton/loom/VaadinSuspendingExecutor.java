/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton.loom;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.VaadinSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes Runnables in Vaadin UI thread, but in a very special way: when the code blocks,
 * all UI changes are rendered in the browser, and the UI thread is not blocked. You can
 * wait for a button click and unblock the code block to execute further.
 * <p></p>
 * Runs on Java Virtual Threads (AKA Project Loom).
 */
public final class VaadinSuspendingExecutor implements AutoCloseable {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger(VaadinSuspendingExecutor.class);
    @NotNull
    private final SuspendingExecutor suspendingExecutor;
    @NotNull
    private final UI ui;

    public VaadinSuspendingExecutor(@NotNull UI ui) {
        Objects.requireNonNull(ui);
        // fail here rather than inside the first virtual thread, where the error would only surface
        // through the session error handler
        VirtualThreadAwareLock.asVirtualThreadAware(ui.getSession().getLockInstance());
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
                // Marks this virtual thread as one whose carrier holds the session lock, which is what
                // lets it "take" the session lock without spinning. See VirtualThreadAwareLock.
                VirtualThreadAwareLock.enterUIVirtualThread(ui.getSession().getLockInstance());
                // post-check: make sure everything is set correctly.
                assertUIVirtualThread();

                // Perfect. Now we can run the code.
                runnable.run();
            } catch (Throwable t) {
                if (t instanceof RuntimeException && t.getCause() != null && t.getCause() instanceof InterruptedException && isClosing.get()) {
                    // this is okay - when the underlying suspendingExecutor.close() calls shutdownNow() in its Executor,
                    // that Executor interrupts all parked virtual threads in order to kill them cleanly.
                    // this exception is expected to be thrown in that case. The best thing is to do nothing here.
                    log.info("Virtual thread was interrupted but " + this + " is closing; this is OK");
                } else {
                    ui.getSession().getErrorHandler().error(new ErrorEvent(t));
                }
            } finally {
                // clean up current instances so that they can be GCed if needed.
                VirtualThreadAwareLock.exitUIVirtualThread();
                UI.setCurrent(null);
                VaadinSession.setCurrent(null);
            }
        });
    }

    /**
     * Set to true by {@link #close()}.
     */
    @NotNull
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    /**
     * Closes the executor immediately. Any suspended virtual threads are killed immediately, then garbage-collected eventually.
     */
    @Override
    public void close() {
        if (isClosing.compareAndSet(false, true)) {
            suspendingExecutor.close();
        }
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
    private class UIExecutor implements Executor {
        /**
         * Legitimate nesting is one virtual thread unparking another from inside its own continuation,
         * which stays shallow. A continuation that feeds itself back in recurses until the stack dies,
         * so anything in between makes a fine tripwire.
         */
        private static final int MAX_NESTED_SUBMITS = 64;

        /**
         * How deep {@link #execute} has re-entered itself on the current thread.
         */
        @NotNull
        private static final ThreadLocal<int[]> nestedSubmits = ThreadLocal.withInitial(() -> new int[1]);

        /**
         * Set once a UI virtual thread has been warned, so a block that spawns in a loop doesn't flood
         * the log.
         */
        @NotNull
        private static final ThreadLocal<Boolean> warned = new ThreadLocal<>();

        @NotNull
        private final UI ui;

        public UIExecutor(@NotNull UI ui) {
            this.ui = Objects.requireNonNull(ui);
        }

        /**
         * Submits {@code command} - a continuation - to the Vaadin UI thread.
         *
         * @throws RejectedExecutionException if submits nest {@code MAX_NESTED_SUBMITS} deep on this
         * thread: a continuation is feeding itself back in and would otherwise recurse until
         * {@link StackOverflowError}. Refusing strands that virtual thread for good - the JDK moved it
         * out of {@code PARKED} before asking us to submit, so no later {@code unpark()} resubmits it -
         * but a stranded thread can't restart the runaway either.
         */
        @Override
        public void execute(@NotNull Runnable command) {
            warnIfSubmittedByAUIVirtualThread();
            final int[] depth = nestedSubmits.get();
            if (depth[0] >= MAX_NESTED_SUBMITS) {
                throw new RejectedExecutionException("Continuation submits are " + MAX_NESTED_SUBMITS
                        + " deep on " + Thread.currentThread() + ": a virtual thread is most likely waiting for"
                        + " something that the Vaadin UI thread re-releases on every continuation."
                        + " See https://github.com/mvysny/vaadin-loom/issues/3");
            }
            depth[0]++;
            try {
                submit(command);
            } finally {
                depth[0]--;
            }
        }

        /**
         * Warns, once per thread, when a UI virtual thread is the one submitting - which usually means
         * it just started a virtual thread that silently inherited this executor as its scheduler.
         * <p></p>
         * A warning and not a rejection: the other way to get here is one {@link VaadinSuspendingExecutor#run} block unparking
         * another, which is fine, and the continuation alone doesn't say which of the two it is.
         * Rejecting virtual callers outright would also break a background virtual thread completing a
         * future that a UI virtual thread awaits.
         */
        private static void warnIfSubmittedByAUIVirtualThread() {
            if (!VirtualThreadAwareLock.isUIVirtualThread() || warned.get() != null) {
                return;
            }
            warned.set(Boolean.TRUE);
            log.warn("{} submitted a continuation to its own executor. If you started a virtual thread"
                    + " from inside a VaadinSuspendingExecutor block, it inherited this executor as its"
                    + " scheduler: its code runs under the Vaadin session lock with UI.getCurrent() unset,"
                    + " and taking the session lock from it recurses until the executor rejects it. Only"
                    + " Thread.ofVirtual() inherits - new Thread(..) and Thread.ofPlatform() give you an"
                    + " ordinary platform thread. Start virtual threads from the Vaadin UI thread instead."
                    + " Disregard this if another block of the same executor simply unparked this one.",
                    Thread.currentThread());
        }

        private void submit(@NotNull Runnable command) {
            if (isClosing.get()) {
                // UI has been detached but the virtual thread is still around!
                // This is called from VaadinSuspendingExecutor.close() when the Virtual Thread Executor is closed:
                // it needs to interrupt() all active virtual threads, in order to terminate them cleanly.
                // VirtualThread.interrupt() calls VirtualThread.unpark(), which in turn calls VirtualThread.submitRunContinuation() which in turn call this.
                //
                // This is also called from `jcmd Thread.dump_to_file`; see https://github.com/mvysny/vaadin-loom/issues/1 for more details.
                // I think in this case the best thing is to run the command directly.
                command.run();
                return;
            }
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
