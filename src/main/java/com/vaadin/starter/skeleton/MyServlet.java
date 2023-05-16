package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * We need to hack VaadinSession.hasLock() to work with virtual threads.
 */
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
        protected VaadinSession createVaadinSession(VaadinRequest request) {
            return new VirtualThreadAwareVaadinSession(this);
        }
    }

    private static class VirtualThreadAwareVaadinSession extends VaadinSession {
        private static final Logger log = LoggerFactory.getLogger(VirtualThreadAwareVaadinSession.class);
        public VirtualThreadAwareVaadinSession(VaadinService service) {
            super(service);
            setErrorHandler(e -> {
                log.error("", e);
                Notification.show(e.getThrowable().getMessage(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR, NotificationVariant.LUMO_PRIMARY);
            });
        }

        @Override
        public boolean hasLock() {
            if (Thread.currentThread().isVirtual()) {
                // not possible?
                // ((ReentrantLock) getLockInstance()).isHeldByThread(UIExecutor.currentCarrierThread());

                // workaround: return true
                return true;
            }
            return super.hasLock();
        }
    }
}
