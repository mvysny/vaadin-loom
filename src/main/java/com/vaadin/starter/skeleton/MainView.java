package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Demoes the blocking dialog.
 */
@Route("")
public class MainView extends VerticalLayout {
    private transient UIExecutor executor;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        executor = new UIExecutor(UI.getCurrent());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        try {
            executor.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor = null;
        super.onDetach(detachEvent);
    }

    public MainView() {
        add(new Button("Blocking dialog", e -> executor.loom(() -> {
            if (confirmDialog("Are you sure")) {
                Notification.show("Yes you're sure");
            } else {
                Notification.show("Nope, not sure");
            }
        })));
    }

    public static boolean confirmDialog(String message) {
        UIExecutor.assertVirtualThread();

        final BlockingQueue<Boolean> responseQueue = new LinkedBlockingQueue<>();
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(message);
        dialog.addConfirmListener(e -> responseQueue.add(true));
        dialog.setCancelable(true);
        dialog.addCancelListener(e -> responseQueue.add(false));
        dialog.open();
        try {
            // await until the user clicks a button, which adds a value to the responseQueue.
            // This is virtual thread and take() unmounts. (see https://blogs.oracle.com/javamagazine/post/java-loom-virtual-threads-platform-threads )
            // That means that while we're 'blocked' in take(), Vaadin UI thread is allowed to finish and render the dialog.
            // On button click, value is added to the responseQueue which wakes up this virtual thread.
            // This virtual thread mounts to a Vaadin UI thread and continues execution.
            final Boolean response = responseQueue.take();

            // Now we're mounted to a Vaadin UI thread.
            UIExecutor.applyVaadin();
            return response;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            dialog.close();
        }
    }
}
