package com.vaadin.starter.skeleton;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;

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
        public VirtualThreadAwareVaadinSession(VaadinService service) {
            super(service);
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
