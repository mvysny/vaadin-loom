package com.vaadin.starter.skeleton;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs given {@link #runnable} as a series of continuations.
 * <ul>
 *     <li>You call {@link #next()}</li>
 *     <li>The {@link #runnable} is run from {@link #next()} until it terminates or until it suspends via a call to {@link #suspend()}</li>
 *     <li>When any of the above happens, {@link #next()} ends</li>
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
    private final AtomicBoolean executionDone = new AtomicBoolean();

    private BlockingQueue<Object> continuationUnpark = null;
    /**
     * Used to assert that continuation.unpark() is invoked synchronously from Thread.start() and continuationUnpark.take(),
     * otherwise this class won't work properly.
     */
    private final AtomicInteger continuationsInvoked = new AtomicInteger();

    /**
     * Creates an invoker which runs given block as a series of continuations. Doesn't call the block
     * just yet - you need to call {@link #next()}.
     * @param runnable the runnable. Not run right away - first continuation is only run when {@link #next()} is called.
     */
    public ContinuationInvoker(@NotNull Runnable runnable) {
        this.runnable = runnable;
    }

    public boolean isDone() {
        return executionDone.get();
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
                continuationsInvoked.incrementAndGet();
                command.run();
            };
            final ThreadFactory virtualThreadFactory = SuspendingExecutor.newVirtualBuilder(synchronousExecutor).factory();
            final Thread thread = virtualThreadFactory.newThread(() -> {
                try {
                    runnable.run();
                } finally {
                    executionDone.set(true);
                }
            });
            continuationUnpark = new LinkedBlockingQueue<>(1);

            // VirtualThread.start() immediately runs the first continuation; the continuation is
            // run via synchronousExecutor which executes it right away. That causes VirtualThread.start()
            // to block until this continuation finishes or suspends via this.suspend().
            thread.start();
            // done: first continuation finished its execution. Assert on that.
            if (continuationsInvoked.get() != 1) {
                throw new IllegalStateException("Expected to run the continuation in VirtualThread.start() but nothing was done");
            }

            return !isDone();
        } else {
            // this deque is populated, which causes the next continuation to run immediately;
            // the continuation suspends by cleaning
            assert continuationUnpark.isEmpty();

            final int invocationCount = continuationsInvoked.get();
            // Similar trick as above: continuationUnpark.offer() unblocks this.suspend() which causes
            // the virtual thread to mount and resume execution immediately. Since we're using
            // synchronousExecutor, the execution runs right away, blocking the call to offer().
            // The execution either terminates, or calls this.suspend(), which cleans up the queue.
            continuationUnpark.offer(new Object());

            if (isDone()) {
                return false;
            } else {
                // The execution of this.runnable called this.suspend() which cleared up the queue. Therefore,
                // the continuationUnpark queue must be empty. Check.
                if (!continuationUnpark.isEmpty()) {
                    throw new IllegalStateException("Runnable is only allowed to call this.suspend() but it blocked in another way");
                }
            }
            if (continuationsInvoked.get() != invocationCount + 1) {
                throw new IllegalStateException("Expected to run the continuation in unpark() but nothing was done");
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
        try {
            // If the continuationUnpark is empty, this blocks. That will park the virtual thread,
            // which will
            continuationUnpark.take();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
