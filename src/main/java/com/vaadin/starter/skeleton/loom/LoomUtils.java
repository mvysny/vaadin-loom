package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
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
}
