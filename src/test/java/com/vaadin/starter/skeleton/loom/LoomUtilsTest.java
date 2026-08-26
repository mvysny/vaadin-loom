package com.vaadin.starter.skeleton.loom;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.vaadin.starter.skeleton.loom.VirtualThreadTestUtils.runInVirtualThread;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the low-level reflection hack. These assertions are JVM-implementation-sensitive
 * (see JDK-8308541) which is exactly why they're worth running on every JDK vendor in the CI matrix.
 */
public class LoomUtilsTest {
    @Test
    public void testAssertVirtualThreadFailsOnPlatformThread() {
        assertFalse(Thread.currentThread().isVirtual());
        final IllegalStateException ex = assertThrows(IllegalStateException.class, LoomUtils::assertVirtualThread);
        assertEquals("This can only be called from a virtual thread", ex.getMessage());
    }

    @Test
    public void testAssertVirtualThreadPassesOnVirtualThread() {
        runInVirtualThread(LoomUtils::assertVirtualThread);
    }

    @Test
    public void testNewVirtualBuilderRunsContinuationsOnGivenExecutor() {
        // the whole point of the reflection hack: continuations must land in OUR executor
        // rather than in the default ForkJoinPool.
        final AtomicInteger continuationsRun = new AtomicInteger();
        final AtomicReference<Thread> carrier = new AtomicReference<>();
        final Executor synchronousExecutor = command -> {
            continuationsRun.incrementAndGet();
            carrier.set(Thread.currentThread());
            command.run();
        };

        final AtomicBoolean ranInVirtualThread = new AtomicBoolean();
        final Thread thread = LoomUtils.newVirtualBuilder(synchronousExecutor)
                .name("test-vt")
                .unstarted(() -> ranInVirtualThread.set(Thread.currentThread().isVirtual()));
        assertTrue(thread.isVirtual());
        thread.start();

        assertEquals(1, continuationsRun.get(), "the continuation must have been run by our executor");
        // our executor runs the continuation inline, therefore the carrier is this very test thread
        assertSame(Thread.currentThread(), carrier.get());
        assertTrue(ranInVirtualThread.get());
    }
}
