/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton.loom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which continuations reach a {@link SuspendingExecutor}'s scheduler. A virtual thread inherits the
 * scheduler of the virtual thread that creates it, so one started inside a block would otherwise run
 * on the block's carrier - in {@link VaadinSuspendingExecutor}, under the session lock.
 * <p></p>
 * The scheduler here is one platform thread, which reproduces the property that matters: a
 * continuation handed to it cannot run while another one is mounted.
 */
public class SuspendingExecutorTest {

    private RecordingScheduler scheduler;
    private SuspendingExecutor executor;

    @BeforeEach
    public void setUp() {
        scheduler = new RecordingScheduler();
        executor = new SuspendingExecutor(scheduler, "test-block-");
    }

    @AfterEach
    public void tearDown() {
        executor.close();
        scheduler.close();
    }

    @Test
    public void threadOfVirtualInsideABlockRunsWhileTheBlockIsMounted() throws InterruptedException {
        assertStartedThreadRunsBesideTheBlock(task -> Thread.ofVirtual().start(task));
    }

    /** Looks like it asks for the default scheduler, and inherits just the same. */
    @Test
    public void virtualThreadPerTaskExecutorInsideABlockRunsWhileTheBlockIsMounted() throws InterruptedException {
        assertStartedThreadRunsBesideTheBlock(task -> {
            final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            pool.submit(task);
            pool.shutdown();
        });
    }

    /** The grandchild inherits the scheduler from the child, so it must be recognised just the same. */
    @Test
    public void aThreadStartedByAnInheritedThreadRunsOffTheSchedulerToo() throws InterruptedException {
        assertStartedThreadRunsBesideTheBlock(task -> Thread.ofVirtual().start(() -> Thread.ofVirtual().start(task)));
    }

    /** The benign case: a block resuming after a park still resumes through its scheduler. */
    @Test
    public void aBlockUnparkedByAnotherBlockResumesThroughTheScheduler() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch resumed = new CountDownLatch(1);
        final AtomicReference<Thread> parked = new AtomicReference<>();
        executor.run(() -> {
            parked.set(Thread.currentThread());
            await(release);
            resumed.countDown();
        });
        awaitWaiting(parked);

        executor.run(release::countDown);

        assertTrue(resumed.await(5, TimeUnit.SECONDS), "the parked block never resumed");
        assertEquals(2, scheduler.distinctContinuations(), "both blocks run on the scheduler");
        assertTrue(scheduler.submits() >= 3, "start, start, resume - got " + scheduler.submits());
    }

    /**
     * Spins inside a block, never unmounting, until {@code start} has run a task - which only
     * happens if the started thread is scheduled somewhere other than the block's own carrier.
     */
    private void assertStartedThreadRunsBesideTheBlock(Consumer<Runnable> start) throws InterruptedException {
        final AtomicBoolean ran = new AtomicBoolean();
        final AtomicBoolean ranWhileMounted = new AtomicBoolean();
        final CountDownLatch blockDone = new CountDownLatch(1);
        executor.run(() -> {
            start.accept(() -> ran.set(true));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ran.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            ranWhileMounted.set(ran.get());
            blockDone.countDown();
        });
        assertTrue(blockDone.await(10, TimeUnit.SECONDS), "the block never finished");
        assertTrue(ranWhileMounted.get(), "the started thread was queued behind the block that started it");
        assertEquals(1, scheduler.distinctContinuations(), "only the block's own continuation may reach its scheduler");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((thread.get() == null || thread.get().getState() != Thread.State.WAITING) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.WAITING, thread.get().getState(), "the block never parked");
    }

    /** One carrier thread, recording every continuation it is handed. */
    private static final class RecordingScheduler implements Executor, AutoCloseable {
        private final ExecutorService carrier = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("test-carrier").factory());
        private final List<Runnable> submitted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void execute(Runnable command) {
            submitted.add(command);
            carrier.execute(command);
        }

        int submits() {
            return submitted.size();
        }

        int distinctContinuations() {
            synchronized (submitted) {
                final Set<Runnable> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                distinct.addAll(submitted);
                return distinct.size();
            }
        }

        @Override
        public void close() {
            carrier.shutdownNow();
        }
    }
}
