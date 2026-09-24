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
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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

    /**
     * Creates a factory of virtual threads that run their continuations on {@code carrier} - and
     * only theirs:
     * <pre>{@code
     * ThreadFactory factory = LoomUtils.newVirtualThreadFactory(carrier, "worker-");
     * factory.newThread(() -> {
     *     // runs on carrier
     *     Thread.ofVirtual().start(() -> {
     *         // runs on a platform carrier pool, not on carrier
     *     });
     * }).start();
     * }</pre>
     * A virtual thread created with no explicit scheduler inherits the scheduler of the virtual
     * thread that creates it - {@code Thread.ofVirtual()} and {@code Executors.newVirtualThreadPerTaskExecutor()}
     * alike. Started from a thread of this factory, it would otherwise run on {@code carrier}, queued
     * behind or nested inside the thread that started it. So every other continuation goes to a
     * JVM-wide pool of platform carriers, where it runs as it would on the JDK's default scheduler.
     *
     * @param carrier runs the continuations of the threads this factory creates
     * @param name the name prefix of the threads; a counter from 0 is appended
     * @throws IllegalStateException if this JDK has no readable {@code VirtualThread.runContinuation}
     */
    @NotNull
    static ThreadFactory newVirtualThreadFactory(@NotNull Executor carrier, @NotNull String name) {
        Objects.requireNonNull(carrier);
        // fail here rather than at the first newThread(), on whichever thread happens to call it
        runContinuationField();
        // weak, since a finished thread never submits again
        final Set<Runnable> ownContinuations = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
        final ThreadFactory factory = newVirtualBuilder(continuation ->
                        (ownContinuations.contains(continuation) ? carrier : InheritedThreadCarriers.POOL).execute(continuation))
                .name(name, 0)
                .factory();
        return task -> {
            final Thread thread = factory.newThread(task);
            ownContinuations.add(continuationOf(thread));
            return thread;
        };
    }

    /**
     * Carries the virtual threads that inherited the scheduler of a {@link #newVirtualThreadFactory} thread.
     * Cached rather than bounded: a continuation that blocks without unmounting holds its carrier,
     * and a bounded pool would queue unrelated threads behind it.
     */
    private static final class InheritedThreadCarriers {
        static final ExecutorService POOL = Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name("loom-inherited-carrier-", 0).factory());
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
    private static Runnable continuationOf(@NotNull Thread virtualThread) {
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
    private static Field runContinuationField() {
        Field field = runContinuation;
        if (field == null) {
            try {
                field = Class.forName("java.lang.VirtualThread").getDeclaredField("runContinuation");
                field.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot read java.lang.VirtualThread.runContinuation on "
                        + Runtime.version() + "; it tells a virtual thread factory's own threads from ones that"
                        + " inherited its scheduler. Is --add-opens java.base/java.lang=ALL-UNNAMED set?", e);
            }
            runContinuation = field;
        }
        return field;
    }
}
