package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;
import jakarta.servlet.annotation.WebServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * We need to hack VaadinSession.hasLock() to work with virtual threads.
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
        protected VaadinSession createVaadinSession(VaadinRequest request) {
            return new VirtualThreadAwareVaadinSession(this);
        }
    }

    private static class VirtualThreadAwareVaadinSession extends VaadinSession {
        private static final Logger log = LoggerFactory.getLogger(VirtualThreadAwareVaadinSession.class);
        public VirtualThreadAwareVaadinSession(VaadinService service) {
            super(service);
            setErrorHandler(e -> {
                log.error("Uncaught error", e.getThrowable());
                Notification.show(e.getThrowable().getMessage(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR, NotificationVariant.LUMO_PRIMARY);
            });
        }

        @Override
        public boolean hasLock() {
            if (Thread.currentThread().isVirtual()) {
                // A virtual thread. We can retrieve the lock instance by calling
                // `(ReentrantLock) getLockInstance()`, but isHeldByCurrentThread()
                // will return false (since the lock is held by the *carrier* thread, and not this virtual thread).
                //
                // We could figure out our carrier thread, but there's no way for ReentrantLock
                // to check whether it's being held by a particular thread - ReentrantLock
                // does not offer that kind of functionality.
                //
                // In other words, the following is not possible to do:
                // ((ReentrantLock) getLockInstance()).isHeldByThread(UIExecutor.currentCarrierThread());
                //
                // Let's implement a weaker check. First let's check whether there is
                // a current session - if not, then we're definitely not running in a UI thread,
                // and we can't have the lock.
                final VaadinSession current = VaadinSession.getCurrent();
                if (current == null) {
                    return false;
                }

                // There is a current session, but is it ours? If it isn't then this thread
                // is locked in another session. Return false.
                if (current != this) {
                    return false;
                }

                // The current session is this one, but are we running from VaadinSuspendingExecutor
                // and from the UIExecutor? If yes, UIExecutor uses ui.access() which grabs the lock,
                // but someone else could have called VaadinSession.setCurrent()....
                // We could in theory use a specialized ThreadLocal to check on this, but for now
                // let's assume that only VaadinSuspendingExecutor runs Vaadin code in virtual threads.
                return true;
            }
            // a regular thread. Nothing special going on - fall back to traditional lock checking.
            return super.hasLock();
        }
    }
}
