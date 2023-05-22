package com.vaadin.starter.skeleton.loom;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

/**
 * Runs given {@link #runnable} as a series of continuations.
 * <ul>
 *     <li>You call {@link #next()}</li>
 *     <li>The {@link #runnable} run() function is called from {@link #next()} synchronously,
 *     and will continue running until it either terminates or until it suspends via a call to {@link #suspend()}</li>
 *     <li>When any of the above happens, the execution returns to {@link #next()} which returns soon after.</li>
 *     <li>You call {@link #next()} again</li>
 *     <li>The {@link #runnable} wakes up from {@link #suspend()} and continues to run until it terminates or suspends again</li>
 *     <li>Rinse and repeat</li>
 * </ul>
 * NOT THREAD SAFE.
 */
public final class ContinuationInvoker {
    /**
     * The runnable to run. Will be run in a virtual thread. The runnable must call {@link #suspend()}
     * if it needs to suspend.
     */
    @NotNull
    private final Runnable runnable;
    /**
     * If true the {@link #runnable} has finished its execution and there will be no more
     * continuations to run.
     */
    private boolean executionDone = false;

    private BlockingQueue<Object> continuationUnpark = null;
    /**
     * Used to assert that continuation.unpark() is invoked synchronously from Thread.start() and continuationUnpark.take(),
     * otherwise this class won't work properly.
     */
    private int continuationsInvoked = 0;

    /**
     * Creates an invoker which runs given block as a series of continuations. Doesn't call the block
     * just yet - you need to call {@link #next()}.
     * @param runnable the runnable. Not run right away - first continuation is only run when {@link #next()} is called.
     */
    public ContinuationInvoker(@NotNull Runnable runnable) {
        this.runnable = Objects.requireNonNull(runnable);
    }

    public boolean isDone() {
        return executionDone;
    }

    /**
     * Runs next continuation.
     * @return true if there are follow-up continuations, false if there will be no follow-up continuations and
     * {@link #runnable} finished its execution.
     * @throws IllegalStateException if the execution is done and there are no more continuations.
     */
    public boolean next() {
        if (isDone()) {
            throw new IllegalStateException("Execution is done");
        }
        if (continuationUnpark == null) {
            // first invocation of the next() function. Let's create the virtual thread factory.
            // the factory is very special: it runs the continuations directly instead of submitting them into a runner.
            final Executor synchronousExecutor = command -> {
                try {
                    command.run();
                } finally {
                    continuationsInvoked++;
                }
            };
            final ThreadFactory virtualThreadFactory = LoomUtils.newVirtualBuilder(synchronousExecutor).factory();
            final Thread thread = virtualThreadFactory.newThread(() -> {
                try {
                    runnable.run();
                } finally {
                    executionDone = true;
                }
            });
            continuationUnpark = new LinkedBlockingQueue<>(1);

            // VirtualThread.start() immediately runs the first continuation; the continuation is
            // run via synchronousExecutor which executes it right away. That causes VirtualThread.start()
            // to block until this continuation finishes or suspends via this.suspend().
            thread.start();
            // done: first continuation finished its execution. Assert on that.
            if (continuationsInvoked != 1) {
                throw new IllegalStateException("Expected to run the continuation in VirtualThread.start() but nothing was done");
            }

            return !isDone();
        } else {
            // This deque is populated only from this function, and then it's cleaned immediately.
            // Therefore, it must be empty.
            assert continuationUnpark.isEmpty();

            final int invocationCount = continuationsInvoked;
            // Similar trick as above: continuationUnpark.offer() unblocks the runnable (which is now stuck in this.suspend()), which causes
            // the virtual thread to immediately run next continuation on our executor. Since we're using
            // synchronousExecutor, the execution runs right away, blocking the call to offer().
            // The execution either terminates, or calls this.suspend(), which cleans up the queue.
            continuationUnpark.offer(""); // the type of the item doesn't really matter.
            // the continuation finished its execution, either by terminating or by calling this.suspend().

            // check that the trick above worked and the continuation finished executing.
            if (continuationsInvoked != invocationCount + 1) {
                throw new IllegalStateException("Expected to run the continuation in unpark() but nothing was done");
            }

            if (isDone()) {
                // runnable terminated. There will be no more continuations => return false.
                return false;
            }
            // The execution of this.runnable called this.suspend() which cleared up the queue. Therefore,
            // the continuationUnpark queue must be empty. Check.
            if (!continuationUnpark.isEmpty()) {
                throw new IllegalStateException("Runnable is only allowed to call this.suspend() but it blocked in another way");
            }
            return true;
        }
    }

    /**
     * Only {@link #runnable} is allowed to call this. Suspends the execution of the {@link #runnable} and causes the ongoing call to {@link #next()} to return.
     */
    public void suspend() {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("Can only be called from this.runnable");
        }
        if (continuationUnpark == null) {
            throw new IllegalStateException("Can only be called from this.runnable");
        }
        try {
            // If the continuationUnpark is empty, this blocks. That will park the virtual thread,
            // which will
            continuationUnpark.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
