package com.vaadin.starter.skeleton;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Chops" a runnable into smaller parts (called continuations) and run them on given carrier thread executor.
 * <p></p>
 * Whenever the runnable blocks, the continuation ends - the virtual thread unmounts from
 * the carrier thread, allowing it to finish. When the runnable is unblocked,
 * a new continuation is passed into the carrier thread executor, and the virtual thread mounts on top of a carrier thread.
 */
public final class Loom implements AutoCloseable {
    /**
     * Runs runnables in virtual threads. Configured to emit continuations to the underlying carrier
     * thread executor.
     */
    @NotNull
    private final ExecutorService virtualThreadExecutor;

    /**
     * Creates the loom.
     * @param executor The carrier thread executor - executes given Continuations on an actual OS thread (called a carrier thread). No magic happens in this executor - the
     * runnables are run until they're terminated.
     */
    public Loom(@NotNull Executor executor) {
        virtualThreadExecutor = Executors.newThreadPerTaskExecutor(newVirtualBuilder(executor).factory());
    }

    /**
     * Runs given runnable in a virtual thread. The code is split into smaller pieces (called continuations)
     * and executed on the carrier thread executor.
     * @param runnable the runnable, not null.
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
    private static Thread.Builder.OfVirtual newVirtualBuilder(@NotNull Executor executor) {
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
