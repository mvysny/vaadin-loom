/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;
import com.vaadin.starter.skeleton.loom.VirtualThreadAwareLock;
import jakarta.servlet.annotation.WebServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Lock;

/**
 * Installs the {@link VirtualThreadAwareLock}, without which nothing running in a UI virtual
 * thread may touch the Vaadin session lock.
 */
@WebServlet(name = "myservlet", urlPatterns = { "/*" })
public class MyServlet extends VaadinServlet {
    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration) throws ServiceException {
        VaadinServletService service = new MyVaadinServletService(this,
                deploymentConfiguration);
        service.init();
        return service;
    }

    private static class MyVaadinServletService extends VaadinServletService {
        public MyVaadinServletService(VaadinServlet servlet, DeploymentConfiguration deploymentConfiguration) {
            super(servlet, deploymentConfiguration);
        }

        @Override
        protected Lock getSessionLock(WrappedSession wrappedSession) {
            return VirtualThreadAwareLock.wrap(this, wrappedSession, super.getSessionLock(wrappedSession));
        }

        @Override
        protected VaadinSession createVaadinSession(VaadinRequest request) {
            return new NotifyingVaadinSession(this);
        }
    }

    /**
     * Shows uncaught errors as a notification rather than only logging them.
     */
    private static class NotifyingVaadinSession extends VaadinSession {
        private static final Logger log = LoggerFactory.getLogger(NotifyingVaadinSession.class);
        public NotifyingVaadinSession(VaadinService service) {
            super(service);
            setErrorHandler(e -> {
                log.error("Uncaught error", e.getThrowable());
                Notification.show(e.getThrowable().getMessage(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR, NotificationVariant.LUMO_PRIMARY);
            });
        }
    }
}
