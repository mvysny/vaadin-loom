/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.Executor;

public class LoomUtils {
    /**
     * Asserts that this thread is a virtual one.
     */
    public static void assertVirtualThread() {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("This can only be called from a virtual thread");
        }
    }

    /**
     * Creates a virtual thread builder which runs continuations on given executor.
     *
     * @param executor runs continuations.
     * @return the virtual thread builder
     */
    @NotNull
    public static Thread.Builder.OfVirtual newVirtualBuilder(@NotNull Executor executor) {
        Objects.requireNonNull(executor);

        // Construct a specialized virtual thread builder which runs continuations on given executor.
        // We need to use reflection since the API is not public:
        // https://bugs.java.com/bugdatabase/view_bug?bug_id=JDK-8308541
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

    /** Resolved once by {@link #runContinuationField()}. */
    private static volatile Field runContinuation;

    /**
     * The one {@code Runnable} the JDK hands a virtual thread's scheduler on every submit - at
     * start, and again at each resume after a park - so it identifies the thread from the
     * scheduler's side, which sees nothing else.
     * <p></p>
     * Reads the private final {@code java.lang.VirtualThread.runContinuation}, set in the
     * constructor. Requires {@code --add-opens java.base/java.lang=ALL-UNNAMED}, like
     * {@link #newVirtualBuilder}.
     */
    @NotNull
    static Runnable continuationOf(@NotNull Thread virtualThread) {
        try {
            return (Runnable) runContinuationField().get(virtualThread);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @throws IllegalStateException if this JDK's {@code VirtualThread} has no readable
     *                               {@code runContinuation} field
     */
    @NotNull
    static Field runContinuationField() {
        Field field = runContinuation;
        if (field == null) {
            try {
                field = Class.forName("java.lang.VirtualThread").getDeclaredField("runContinuation");
                field.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot read java.lang.VirtualThread.runContinuation on "
                        + Runtime.version() + "; it tells this executor's own virtual threads from ones that"
                        + " inherited its scheduler. Is --add-opens java.base/java.lang=ALL-UNNAMED set?", e);
            }
            runContinuation = field;
        }
        return field;
    }
}
