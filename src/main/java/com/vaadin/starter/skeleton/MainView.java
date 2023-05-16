package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Demoes the blocking dialog.
 */
@Route("")
public class MainView extends VerticalLayout {
    private transient VaadinLoom executor;

    @Override
    protected void onAttach(@NotNull AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        executor = new VaadinLoom(UI.getCurrent());
    }

    @Override
    protected void onDetach(@NotNull DetachEvent detachEvent) {
        try {
            executor.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor = null;
        super.onDetach(detachEvent);
    }

    public MainView() {
        add(new Button("Blocking dialog", e -> executor.run(() -> {
            if (confirmDialog("Are you sure")) {
                Notification.show("Yes you're sure");
            } else {
                Notification.show("Nope, not sure");
            }
        })));
    }

    /**
     * Opens a confirm dialog with two buttons, Cancel and Confirm. Blocks until the
     * user clicks either of the buttons (or closes the dialog via ESC, which counts as a cancelation).
     * @param message the message to show in the dialog.
     * @return true if the user pressed Confirm, false if the user pressed Cancel (or closed the dialog via ESC).
     */
    public static boolean confirmDialog(@NotNull String message) {
        VaadinLoom.assertUIVirtualThread();

        final BlockingQueue<Boolean> responseQueue = new LinkedBlockingQueue<>();
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(message);
        dialog.addConfirmListener(e -> responseQueue.add(true));
        dialog.setCancelable(true);
        dialog.addCancelListener(e -> responseQueue.add(false));
        dialog.open();
        try {
            // Await until the user clicks a button, which adds a value to the responseQueue.
            // This only works with virtual threads.
            //
            // Since this is virtual thread, take() unmounts instead of blocking the current thread.
            // See https://blogs.oracle.com/javamagazine/post/java-loom-virtual-threads-platform-threads for more details on the virtual thread basics.
            // That means that while we're 'blocked' in take(), Vaadin UI thread is allowed to finish and render the dialog.
            // On button click, value is added to the responseQueue which wakes up this virtual thread.
            // This virtual thread mounts to a Vaadin UI thread and continues execution.
            //
            // If this was not a virtual thread, this would obviously block endlessly and would not draw any dialog.
            // See https://mvysny.github.io/vaadin-blocking-dialogs/ for more details.
            final Boolean response = responseQueue.take();

            // Now we're mounted back to a Vaadin UI thread.
            return response;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            dialog.close();
        }
    }
}
