package com.vaadin.starter.skeleton;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
     * @param executor The carrier thread executor - executes given Runnables (Continuations) on an actual OS thread
     *                 (called a carrier thread). No magic happens in this executor - the
     *                 runnables are run until they're terminated.
     */
    public SuspendingExecutor(@NotNull Executor executor) {
        virtualThreadExecutor = Executors.newThreadPerTaskExecutor(newVirtualBuilder(executor).factory());
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
     * Asserts that this thread is a virtual one.
     */
    public static void assertVirtualThread() {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("This can only be called from closures run via loom()");
        }
    }

    /**
     * Creates a virtual thread builder which runs continuations on given executor.
     * @param executor runs continuations.
     * @return the virtual thread builder
     */
    @NotNull
    public static Thread.Builder.OfVirtual newVirtualBuilder(@NotNull Executor executor) {
        Objects.requireNonNull(executor);

        // construct a specialized virtual thread builder which runs continuations on uiExecutor
        try {
            final Class<?> vtbclass = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
            final Constructor<?> c = vtbclass.getDeclaredConstructor(Executor.class);
            c.setAccessible(true);
            final Thread.Builder.OfVirtual vtb = (Thread.Builder.OfVirtual) c.newInstance(executor);
            return vtb;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        virtualThreadExecutor.close();
    }
}
