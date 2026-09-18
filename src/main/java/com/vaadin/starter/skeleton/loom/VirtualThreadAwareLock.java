/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton.loom;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Vaadin session lock, made safe to take from a UI virtual thread. Install it by routing
 * {@link VaadinService#getSessionLock(WrappedSession)} through {@link #wrap}:
 *
 * <pre>{@code
 * protected Lock getSessionLock(WrappedSession wrappedSession) {
 *     return VirtualThreadAwareLock.wrap(this, wrappedSession, super.getSessionLock(wrappedSession));
 * }
 * }</pre>
 *
 * A UI virtual thread already runs under the session lock - its carrier holds it for the whole
 * {@code UI.access()} callback - yet it can never take that lock itself, because
 * {@link ReentrantLock} keys on {@link Thread} identity and the carrier is a different
 * {@link Thread}. Trying anyway spins forever, see
 * <a href="https://github.com/mvysny/vaadin-loom/issues/3">issue #3</a>. So on a thread marked by
 * {@link #enterUIVirtualThread} every operation here is bookkeeping only ("pretend mode"); every
 * other thread gets the real lock.
 *
 * @implNote Delegates rather than replaces, so Vaadin's own {@code InstrumentedReentrantLock} keeps
 * firing {@code SessionLockListener} events for the acquisitions that really happen. Pretend mode
 * fires nothing on purpose: the real outermost acquire/release is the carrier's {@code UI.access()},
 * and a pretend hold sits strictly inside that window.
 * <p></p>
 * The price of delegating is {@link #hasQueuedThreads()} and friends, which are {@code final} and so
 * report this wrapper's forever-empty queue. That costs {@code VaadinService.isUIActive()} the grace
 * period it grants a UI while somebody waits for the session lock.
 */
public final class VirtualThreadAwareLock extends ReentrantLock {
    @NotNull
    private final ReentrantLock delegate;

    /**
     * The pretend hold of the virtual thread running right now; {@code null} on every other thread.
     * Static so that it survives session serialization - the lock itself is stored as a session
     * attribute.
     */
    @NotNull
    private static final ThreadLocal<PretendHold> pretendHold = new ThreadLocal<>();

    private VirtualThreadAwareLock(@NotNull ReentrantLock delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns the wrapper for {@code lock}, installing it into the session so that everybody -
     * {@link VaadinSession#getLockInstance()} included - ends up with this one instance.
     *
     * @param lock whatever {@link VaadinService#getSessionLock(WrappedSession)} found, possibly
     * {@code null} before the session has a lock at all
     * @return the wrapper, or {@code null} if {@code lock} was {@code null}
     */
    @Nullable
    public static Lock wrap(@NotNull VaadinService service, @NotNull WrappedSession wrappedSession,
                            @Nullable Lock lock) {
        if (lock == null || lock instanceof VirtualThreadAwareLock) {
            return lock;
        }
        // mirrors VaadinService.getLockAttributeName(), which is private
        final String attributeName = service.getServiceName() + ".lock";
        // VaadinService.lockSession() creates the session lock under this very monitor. Share it:
        // two requests racing on a fresh session must not install two wrappers, since
        // VaadinSession.refreshLock() asserts that the lock instance never changes.
        synchronized (VaadinService.class) {
            final Object current = wrappedSession.getAttribute(attributeName);
            if (current instanceof VirtualThreadAwareLock) {
                return (Lock) current;
            }
            final VirtualThreadAwareLock wrapper = new VirtualThreadAwareLock((ReentrantLock) lock);
            wrappedSession.setAttribute(attributeName, wrapper);
            return wrapper;
        }
    }

    /**
     * Marks the current virtual thread as running under {@code sessionLock}, held by its carrier.
     * Call it from the virtual thread itself, and pair it with {@link #exitUIVirtualThread}.
     *
     * @param sessionLock {@link VaadinSession#getLockInstance()}
     * @throws IllegalStateException if the session lock isn't a {@link VirtualThreadAwareLock} -
     * the service is missing its {@link #wrap} call
     */
    public static void enterUIVirtualThread(@NotNull Lock sessionLock) {
        pretendHold.set(new PretendHold(asVirtualThreadAware(sessionLock)));
    }

    public static void exitUIVirtualThread() {
        pretendHold.remove();
    }

    /**
     * Whether the calling thread is a UI virtual thread of any session, i.e. one that
     * {@link #enterUIVirtualThread} has marked.
     */
    public static boolean isUIVirtualThread() {
        return pretendHold.get() != null;
    }

    /**
     * @throws IllegalStateException if {@code sessionLock} isn't a {@link VirtualThreadAwareLock}
     */
    @NotNull
    public static VirtualThreadAwareLock asVirtualThreadAware(@NotNull Lock sessionLock) {
        if (!(sessionLock instanceof VirtualThreadAwareLock)) {
            throw new IllegalStateException("Expected " + VirtualThreadAwareLock.class.getSimpleName()
                    + " but got " + sessionLock.getClass().getName()
                    + ": the VaadinService must route getSessionLock() through VirtualThreadAwareLock.wrap()");
        }
        return (VirtualThreadAwareLock) sessionLock;
    }

    /**
     * Whether the caller is a UI virtual thread of <em>this</em> lock's session.
     */
    private boolean isPretending() {
        final PretendHold hold = pretendHold.get();
        return hold != null && hold.lock == this;
    }

    @NotNull
    private PretendHold hold() {
        return Objects.requireNonNull(pretendHold.get());
    }

    @Override
    public void lock() {
        if (isPretending()) {
            hold().depth++;
        } else {
            delegate.lock();
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (isPretending()) {
            // a real lockInterruptibly() throws even when the acquire wouldn't have blocked
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            hold().depth++;
        } else {
            delegate.lockInterruptibly();
        }
    }

    @Override
    public boolean tryLock() {
        if (isPretending()) {
            hold().depth++;
            return true;
        }
        return delegate.tryLock();
    }

    @Override
    public boolean tryLock(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        if (isPretending()) {
            hold().depth++;
            return true;
        }
        return delegate.tryLock(timeout, unit);
    }

    /**
     * @throws IllegalStateException in pretend mode, with no pretend hold left to release. A UI
     * virtual thread can't hand the lock back - the carrier owns it until this thread returns or
     * unmounts - and doesn't need to: blocking already releases it, so the unlock-block-relock idiom
     * is unnecessary here.
     */
    @Override
    public void unlock() {
        if (isPretending()) {
            final PretendHold hold = hold();
            if (hold.depth == 0) {
                throw new IllegalStateException("The Vaadin session lock can't be fully unlocked from a UI virtual thread");
            }
            hold.depth--;
        } else {
            delegate.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException in pretend mode - awaiting would try to release the
     * lock on behalf of the carrier and fail with {@link IllegalMonitorStateException}
     */
    @Override
    @NotNull
    public Condition newCondition() {
        if (isPretending()) {
            throw new UnsupportedOperationException("Conditions on the Vaadin session lock are not available on a UI virtual thread");
        }
        return delegate.newCondition();
    }

    /**
     * @return in pretend mode, the number of pretend holds plus two
     * @implNote The {@code +2} keeps {@link VaadinSession#unlock()} off its {@code holdCount == 1}
     * "ultimate release" branch on a virtual thread. That branch runs the pending access tasks and
     * pushes to every UI - the carrier's job - and it runs <em>before</em> {@link #unlock()} can
     * reject an unbalanced call, so reporting 1 would fire it on the way to the exception.
     */
    @Override
    public int getHoldCount() {
        return isPretending() ? hold().depth + 2 : delegate.getHoldCount();
    }

    /**
     * @return {@code true} in pretend mode; this is what makes {@link VaadinSession#hasLock()} tell
     * the truth on a UI virtual thread
     */
    @Override
    public boolean isHeldByCurrentThread() {
        return isPretending() || delegate.isHeldByCurrentThread();
    }

    @Override
    public boolean isLocked() {
        return delegate.isLocked();
    }

    @Override
    public boolean hasWaiters(@NotNull Condition condition) {
        return delegate.hasWaiters(condition);
    }

    @Override
    public int getWaitQueueLength(@NotNull Condition condition) {
        return delegate.getWaitQueueLength(condition);
    }

    @Override
    @NotNull
    public String toString() {
        return getClass().getSimpleName() + "(" + delegate + (isPretending() ? ", pretend depth " + hold().depth : "") + ")";
    }

    /**
     * How many times the virtual thread has called {@link #lock()} on top of the hold its carrier
     * already has.
     */
    private static final class PretendHold {
        @NotNull
        private final VirtualThreadAwareLock lock;
        private int depth;

        private PretendHold(@NotNull VirtualThreadAwareLock lock) {
            this.lock = lock;
        }
    }
}
