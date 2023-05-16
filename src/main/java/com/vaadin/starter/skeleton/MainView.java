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
 * The main view contains a button and a click listener.
 */
@Route("")
public class MainView extends VerticalLayout {
    private UIExecutor executor;

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
        final BlockingQueue<Boolean> responseQueue = new LinkedBlockingQueue<>();
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(message);
        dialog.addConfirmListener(e -> responseQueue.add(true));
        dialog.setCancelable(true);
        dialog.addCancelListener(e -> responseQueue.add(false));
        dialog.open();
        try {
            final Boolean response = responseQueue.take();
            UIExecutor.applyVaadin();
            dialog.close();
            return response;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
