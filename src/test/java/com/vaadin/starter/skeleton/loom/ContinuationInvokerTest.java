package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
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

    @Test
    public void testRunnableFailureIsRethrownFromNext() {
        final AtomicReference<ContinuationInvoker> self = new AtomicReference<>();
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {
            self.get().suspend();
            throw new IllegalArgumentException("simulated");
        });
        self.set(invoker);

        assertTrue(invoker.next());
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, invoker::next);
        assertEquals("simulated", ex.getMessage());
        assertTrue(invoker.isDone());
        // the failure isn't consumed: a failed invoker must never look like a cleanly finished one.
        assertEquals("simulated", assertThrows(IllegalArgumentException.class, invoker::next).getMessage());
    }

    @Test
    public void testRunnableFailureInTheFirstContinuationIsRethrownFromNext() {
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {
            throw new IllegalArgumentException("simulated");
        });
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, invoker::next);
        assertEquals("simulated", ex.getMessage());
    }

    @Test
    public void testRunnableFailureIsRethrownWithItsOriginalType() {
        // the throwable must not be wrapped: the caller must be able to catch it by its own type.
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {
            throw new IllegalArgumentException("simulated");
        });
        assertThrows(IllegalArgumentException.class, invoker::next);
    }

    /**
     * A stack walk of a virtual thread can not see past the mount point, so the trace of a throwable
     * thrown by the runnable ends at the virtual thread entry point and says nothing about who was
     * driving the continuation. The invoker stitches the caller frames back on.
     * <p></p>
     * Note that this asserts on real method names only. Synthetic names (a lambda gets a
     * {@code lambda$method$N} name whose counter is assigned per class in compilation order) are an
     * unspecified compiler detail and differ between javac versions, and the name of the virtual
     * thread entry frame belongs to the JDK - neither is something this test should pin down.
     */
    @Test
    public void testRunnableFailureStackTraceContainsTheCallerFrames() {
        final ContinuationInvoker invoker = new ContinuationInvoker(ContinuationInvokerTest::throwSimulated);
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, invoker::next);
        final List<String> frames = framesOf(ex);

        // the runnable half comes first, with the throw site on top, intact.
        assertEquals(ContinuationInvokerTest.class.getName() + ".throwSimulated", frames.get(0),
                () -> "expected the throw site on top, got " + frames);

        // the two halves are joined by the stitching frames, which run straight into next().
        final int stitch = frames.indexOf(ContinuationInvoker.class.getName() + ".stitchCallerFrames");
        assertTrue(stitch > 0, () -> "expected the stitch marker frames, got " + frames);
        assertEquals(List.of(ContinuationInvoker.class.getName() + ".stitchCallerFrames",
                        ContinuationInvoker.class.getName() + ".rethrowFailure",
                        ContinuationInvoker.class.getName() + ".next"),
                frames.subList(stitch, stitch + 3),
                () -> "expected the stitch to run into next(), got " + frames);

        // and the caller half below the stitch reaches all the way up to whoever called next().
        assertTrue(frames.subList(stitch, frames.size())
                        .contains(ContinuationInvokerTest.class.getName() + ".testRunnableFailureStackTraceContainsTheCallerFrames"),
                () -> "expected the calling test method below the stitch, got " + frames);
    }

    /**
     * A named throw site, so that the trace assertions above don't depend on how the compiler
     * happens to name a lambda.
     */
    private static void throwSimulated() {
        throw new IllegalArgumentException("simulated");
    }

    @Test
    public void testStackTraceIsStitchedOnlyOnce() {
        // the same failure is rethrown by every subsequent next(); the trace must not grow each time.
        final ContinuationInvoker invoker = new ContinuationInvoker(() -> {
            throw new IllegalArgumentException("simulated");
        });
        final int length = assertThrows(IllegalArgumentException.class, invoker::next).getStackTrace().length;
        for (int i = 0; i < 3; i++) {
            assertEquals(length, assertThrows(IllegalArgumentException.class, invoker::next).getStackTrace().length);
        }
    }

    @NotNull
    private static List<String> framesOf(@NotNull Throwable t) {
        return Arrays.stream(t.getStackTrace())
                .map(it -> it.getClassName() + "." + it.getMethodName())
                .toList();
    }
}
