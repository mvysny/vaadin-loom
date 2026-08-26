package com.vaadin.starter.skeleton.loom;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static com.vaadin.starter.skeleton.loom.VirtualThreadTestUtils.runInVirtualThread;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ContinuationInvoker} directly, in particular the guards which enforce its
 * (rather strict) contract: only the runnable may call {@link ContinuationInvoker#suspend()},
 * and it must not block in any other way.
 */
public class ContinuationInvokerTest {
    @Test
    public void testEmptyRunnableIsDoneAfterFirstNext() {
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {});
        assertFalse(invoker.isDone());
        assertFalse(invoker.next(), "the runnable never suspends, so there are no follow-up continuations");
        assertTrue(invoker.isDone());
    }

    @Test
    public void testRunnableIsChoppedIntoContinuations() {
        final List<String> log = new ArrayList<>();
        final AtomicReference<ContinuationInvoker> self = new AtomicReference<>();
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {
            log.add("first");
            self.get().suspend();
            log.add("second");
            self.get().suspend();
            log.add("third");
        });
        self.set(invoker);

        assertEquals(List.of(), log, "the runnable must not run until next() is called");
        assertTrue(invoker.next());
        assertEquals(List.of("first"), log);
        assertTrue(invoker.next());
        assertEquals(List.of("first", "second"), log);
        assertFalse(invoker.next(), "the runnable terminated");
        assertEquals(List.of("first", "second", "third"), log);
        assertTrue(invoker.isDone());
    }

    @Test
    public void testNextAfterExecutionDoneThrows() {
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {});
        assertFalse(invoker.next());
        final IllegalStateException ex = assertThrows(IllegalStateException.class, invoker::next);
        assertEquals("Execution is done", ex.getMessage());
    }

    @Test
    public void testSuspendFromPlatformThreadThrows() {
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {});
        assertFalse(Thread.currentThread().isVirtual());
        final IllegalStateException ex = assertThrows(IllegalStateException.class, invoker::suspend);
        assertEquals("Can only be called from this.runnable", ex.getMessage());
    }

    @Test
    public void testSuspendFromAForeignVirtualThreadThrows() {
        // next() was never called, so the runnable isn't running and can't possibly be the caller.
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {});
        runInVirtualThread(() -> {
            final IllegalStateException ex = assertThrows(IllegalStateException.class, invoker::suspend);
            assertEquals("Can only be called from this.runnable", ex.getMessage());
        });
    }

    @Test
    public void testRunnableBlockingInAnotherWayIsDetected() {
        // The runnable is only allowed to block via suspend(). Here it parks on an unrelated
        // queue instead; the invoker must fail fast rather than silently produce nothing.
        final LinkedBlockingQueue<String> unrelatedQueue = new LinkedBlockingQueue<>();
        final AtomicReference<ContinuationInvoker> self = new AtomicReference<>();
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {
            self.get().suspend();
            try {
                // parks the virtual thread on something the invoker knows nothing about,
                // and nobody ever offers to this queue.
                unrelatedQueue.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        self.set(invoker);

        assertTrue(invoker.next());
        // this continuation ends by parking on unrelatedQueue, which still looks fine to the invoker
        assertTrue(invoker.next());
        // but now there is nothing to unpark: the runnable isn't waiting in suspend().
        final IllegalStateException ex = assertThrows(IllegalStateException.class, invoker::next);
        assertEquals("Expected to run the continuation in unpark() but nothing was done", ex.getMessage());
    }
}
