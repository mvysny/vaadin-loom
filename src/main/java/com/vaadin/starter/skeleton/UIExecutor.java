package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes UI code in Vaadin UI thread, but in a very special way: when the code blocks,
 * all UI changes are rendered in the browser, and the UI thread is not blocked. You can
 * wait for a button click and unblock the code block to execute further.
 * <p></p>
 * Runs on Loom virtual threads. Obviously uses a very dark magic.
 */
public final class UIExecutor implements AutoCloseable {
    private final ExecutorService loomExecutor;

    public UIExecutor(UI ui) {
        loomExecutor = Executors.newThreadPerTaskExecutor(newVirtualBuilder(ui).factory());
    }

    /**
     * You can call this from anywhere, even from a background thread. Runs given runnable
     * in a virtual thread, with the Vaadin session lock held.
     * <p></p>
     * CURRENT LIMITATION: Every time you block in an unpinned way (when the virtual thread is unmounted and mounted back),
     * you MUST call {@link #applyVaadin()} to correctly fill in
     * the {@link UI#getCurrent()}.
     * @param runnable runs.
     */
    public void loom(Runnable runnable) {
        Objects.requireNonNull(runnable);
        loomExecutor.submit(() -> {
            // now we're running in the virtual thread.
            if (!Thread.currentThread().isVirtual()) {
                throw new IllegalStateException("Expected to be running in a virtual thread?!?");
            }
            try {
                applyVaadin();
                runnable.run();
            } catch (Throwable t) {
                // yeah yeah, this is prototype.
                t.printStackTrace();
            }
        });
    }

    @Override
    public void close() throws Exception {
        loomExecutor.close();
    }

    private static Thread.Builder.OfVirtual newVirtualBuilder(UI ui) {
        Objects.requireNonNull(ui);
        // we'll create a virtual thread builder which runs continuations on the UI thread, via ui.access().
        try {
            final Class<?> vtbclass = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
            final Constructor<?> c = vtbclass.getDeclaredConstructor(Executor.class);
            c.setAccessible(true);
            Thread.Builder.OfVirtual vtb = (Thread.Builder.OfVirtual) c.newInstance((Executor) command -> {
                ui.access((Command) () -> {
                    CURRENT_UI.put(Thread.currentThread(), UI.getCurrent());
                    try {
                        // This calls a JVM Continuation. Upon mounting a continuation,
                        // the current thread is switched to a virtual thread (!!). The current (carrier) thread is blocked while executing virtual thread,
                        // which means that the virtual thread also runs in the UI thread and is safe to mutate the state of the UI components.
                        //
                        // However, this crazy 'thread flip' (or mount) causes UI.getCurrent() to return null. We'll fix that by remembering the
                        // current UI for the carrier thread, then we'll look it up from the virtual thread and set it as current.
                        // CURRENT_UI is going to help us with that.
                        command.run();
                    } finally {
                        CURRENT_UI.remove(Thread.currentThread());
                    }
                });
            });
            return vtb;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final ConcurrentHashMap<Thread, UI> CURRENT_UI = new ConcurrentHashMap<>();

    /**
     * Applies UI.getCurrent() and VaadinSession.getCurrent() to the current virtual thread. Call this
     * every time you block in an unpinned way (when the virtual thread is unmounted and mounted back).
     */
    public static void applyVaadin() {
        assertVirtualThread();
        final Thread carrierThread = currentCarrierThread();
        // the carrier thread has the current UIs set properly.
        UI ui = CURRENT_UI.get(carrierThread);
        if (ui == null) {
            throw new IllegalStateException("Invalid state: UI is null. Perhaps the carrier thread was misdetected, or something went really wrong");
        }
        UI.setCurrent(ui);
        VaadinSession.setCurrent(ui.getSession());
    }

    /**
     * Returns the carrier thread of this virtual thread.
     */
    private static Thread currentCarrierThread() {
        try {
            final Class<?> cc = Class.forName("jdk.internal.vm.Continuation");
            final Method m = cc.getDeclaredMethod("currentCarrierThread");
            m.setAccessible(true);
            return ((Thread) m.invoke(null));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void assertVirtualThread() {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("This can only be called from closures run via loom()");
        }
    }
}
