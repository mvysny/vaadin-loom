package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * "Chops" the execution of the runnable into smaller parts (called Continuations) and run them on given carrier thread executor.
 * Runs on Java Virtual Threads (AKA Project Loom).
 * <p></p>
 * Whenever the Runnable execution blocks in a way that it unmounts (see
 * <a href="https://blogs.oracle.com/javamagazine/post/java-loom-virtual-threads-platform-threads">Blocking and unmounting</a> for more info),
 * it "suspends" - the virtual thread stops execution and unmounts from
 * the carrier thread, allowing the carrier thread Runnable to finish. When the Runnable execution unblocks,
 * a new continuation is passed into the carrier thread executor, and the virtual thread mounts on top of a carrier thread.
 */
public final class SuspendingExecutor implements AutoCloseable {
    /**
     * Runs Runnables in virtual threads. Emits 'continuation' as Runnables to the underlying carrier
     * thread executor.
     */
    @NotNull
    private final ExecutorService virtualThreadExecutor;

    /**
     * Creates the suspending executor.
     * @param executor Executes given Runnables (Continuations) on an actual OS thread
     *                 (called a carrier thread). No magic happens in this executor - the
     *                 runnables are run until they're terminated.
     * @param name the name prefix of the threads constructed for this executor.
     */
    public SuspendingExecutor(@NotNull Executor executor, @NotNull String name) {
        final ThreadFactory virtualThreadFactory = LoomUtils.newVirtualBuilder(executor)
                .name(name, 0)
                .factory();
        virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    }

    /**
     * Runs given runnable in a virtual thread. The actual code execution is "split into smaller pieces" (called continuations)
     * and executed on the carrier thread executor.
     * @param runnable the runnable, not null. Run in a virtual thread.
     */
    public void run(@NotNull Runnable runnable) {
        Objects.requireNonNull(runnable);
        virtualThreadExecutor.submit(runnable);
    }

    /**
     * Closes the executor immediately. Any suspended virtual threads are killed immediately, then garbage-collected eventually.
     */
    @Override
    public void close() {
        // can not use close() or shutdown()/awaitTermination() since the function would block until the virtual threads actually end.
        // But they may be suspended, waiting for the user to click "Yes" on a blocking dialog which could take a long time.
//        virtualThreadExecutor.close();
        virtualThreadExecutor.shutdownNow();
        // don't call this: it will block until all ongoing virtual threads actually finish, which they may never do.
        // Throwing away the executor instance should also garbage-collect all submitted tasks, since the executor doesn't
        // have any ongoing threads that would keep the reference to the tasks.
//        virtualThreadExecutor.awaitTermination(1, TimeUnit.DAYS);
    }
}
