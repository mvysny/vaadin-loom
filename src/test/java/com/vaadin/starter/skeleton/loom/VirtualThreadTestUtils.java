package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Helpers for tests which need to assert on code running in a virtual thread.
 */
public final class VirtualThreadTestUtils {
    private VirtualThreadTestUtils() {}

    /**
     * Runs given block in a plain virtual thread (scheduled by the default ForkJoinPool),
     * waits for it to finish, and rethrows whatever it threw. Since JUnit assertion failures
     * are {@link Error}s they propagate unchanged, which means you can simply assert inside the block.
     * @param runnable the block to run in a virtual thread.
     */
    public static void runInVirtualThread(@NotNull Runnable runnable) {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        final Throwable t = failure.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
        if (t != null) {
            throw new RuntimeException(t);
        }
    }
}
