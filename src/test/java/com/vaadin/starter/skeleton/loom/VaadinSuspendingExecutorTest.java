package com.vaadin.starter.skeleton.loom;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.starter.skeleton.MockVirtualThreadAwareServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.vaadin.starter.skeleton.loom.VirtualThreadTestUtils.runInVirtualThread;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link VaadinSuspendingExecutor} directly, focusing on the lifecycle paths that
 * {@link com.vaadin.starter.skeleton.MainViewTest} doesn't reach: failure reporting, and
 * closing the executor while a virtual thread is still parked.
 */
public class VaadinSuspendingExecutorTest {
    private static Routes routes;

    /**
     * Collects everything handed to the Vaadin session error handler during a test.
     */
    private List<Throwable> reportedErrors;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.vaadin.starter.skeleton");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
        reportedErrors = new ArrayList<>();
        VaadinSession.getCurrent().setErrorHandler(event -> reportedErrors.add(event.getThrowable()));
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    public void testRunnableRunsInVirtualThreadWithUIAndSessionSet() {
        final UI ui = UI.getCurrent();
        final AtomicBoolean virtual = new AtomicBoolean();
        final AtomicReference<UI> uiSeenByRunnable = new AtomicReference<>();
        final AtomicReference<VaadinSession> sessionSeenByRunnable = new AtomicReference<>();

        try (VaadinSuspendingExecutor executor = new VaadinSuspendingExecutor(ui)) {
            executor.run(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                uiSeenByRunnable.set(UI.getCurrent());
                sessionSeenByRunnable.set(VaadinSession.getCurrent());
            });
            // run() only schedules the runnable via ui.access(); the queue is drained on a client roundtrip
            MockVaadin.clientRoundtrip();
        }

        assertTrue(virtual.get(), "the runnable must run in a virtual thread");
        // virtual threads don't inherit these from the carrier - the executor must re-seed them
        assertSame(ui, uiSeenByRunnable.get());
        assertSame(ui.getSession(), sessionSeenByRunnable.get());
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void testCurrentInstancesAreClearedAfterTheRunnableFinishes() {
        final UI ui = UI.getCurrent();
        try (VaadinSuspendingExecutor executor = new VaadinSuspendingExecutor(ui)) {
            executor.run(() -> {});
            MockVaadin.clientRoundtrip();
        }
        // UI.setCurrent(null) in the finally block runs on the virtual thread, so it can't affect
        // this thread's current instances; assert they survived, otherwise MockVaadin teardown breaks.
        assertSame(ui, UI.getCurrent());
    }

    @Test
    public void testRunnableFailureIsReportedToTheSessionErrorHandler() {
        final RuntimeException failure = new RuntimeException("simulated");
        try (VaadinSuspendingExecutor executor = new VaadinSuspendingExecutor(UI.getCurrent())) {
            executor.run(() -> {
                throw failure;
            });
            // `true` keeps our own error handler installed instead of Karibu's fail-the-test one,
            // so that we can assert on what the executor actually reported.
            MockVaadin.clientRoundtrip(true);
        }
        assertEquals(1, reportedErrors.size(), "expected exactly one error, got " + reportedErrors);
        assertSame(failure, reportedErrors.get(0));
    }

    @Test
    public void testCloseWhileTheRunnableIsParkedReportsNoError() {
        // This is the "user navigated away while a blocking dialog was open" scenario:
        // close() interrupts the parked virtual thread, which surfaces as a RuntimeException(InterruptedException).
        // That is expected and must NOT reach the error handler.
        final CompletableFuture<Boolean> neverCompleted = new CompletableFuture<>();
        final AtomicBoolean startedRunning = new AtomicBoolean();
        final AtomicBoolean resumedAfterPark = new AtomicBoolean();

        final VaadinSuspendingExecutor executor = new VaadinSuspendingExecutor(UI.getCurrent());
        executor.run(() -> {
            startedRunning.set(true);
            try {
                neverCompleted.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            resumedAfterPark.set(true);
        });
        MockVaadin.clientRoundtrip();

        assertTrue(startedRunning.get(), "the runnable must have started");
        assertFalse(neverCompleted.isDone(), "the runnable must be parked, waiting for the future");
        assertFalse(resumedAfterPark.get());

        executor.close();

        assertFalse(resumedAfterPark.get(), "the parked runnable must have been killed, not resumed");
        assertEquals(List.of(), reportedErrors,
                "the interrupt caused by close() is expected and must be swallowed, got " + reportedErrors);
    }

    @Test
    public void testCloseIsIdempotent() {
        final VaadinSuspendingExecutor executor = new VaadinSuspendingExecutor(UI.getCurrent());
        executor.close();
        executor.close();
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void testAssertUIVirtualThreadFailsOnPlatformThread() {
        assertFalse(Thread.currentThread().isVirtual());
        assertNotNull(UI.getCurrent());
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                VaadinSuspendingExecutor::assertUIVirtualThread);
        assertEquals("This can only be called from a virtual thread", ex.getMessage());
    }

    @Test
    public void testAssertUIVirtualThreadFailsWhenUICurrentIsNotSet() {
        // a plain virtual thread doesn't inherit UI.getCurrent() from its parent
        runInVirtualThread(() -> {
            assertNull(UI.getCurrent());
            final IllegalStateException ex = assertThrows(IllegalStateException.class,
                    VaadinSuspendingExecutor::assertUIVirtualThread);
            assertEquals("UI.getCurrent() is null, this needs to be run in the Vaadin UI thread", ex.getMessage());
        });
    }

    @Test
    public void testAssertUIVirtualThreadPassesInsideTheExecutor() {
        final AtomicBoolean passed = new AtomicBoolean();
        try (VaadinSuspendingExecutor executor = new VaadinSuspendingExecutor(UI.getCurrent())) {
            executor.run(() -> {
                VaadinSuspendingExecutor.assertUIVirtualThread();
                passed.set(true);
            });
            MockVaadin.clientRoundtrip();
        }
        assertTrue(passed.get());
        assertEquals(List.of(), reportedErrors);
    }
}
