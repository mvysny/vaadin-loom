package com.vaadin.starter.skeleton;

import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockService;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinServlet;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinSession;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;

/**
 * We need to hack VaadinSession.hasLock() to work with virtual threads.
 */
public class MockVirtualThreadAwareServlet extends MockVaadinServlet {
    public MockVirtualThreadAwareServlet(@NotNull Routes routes) {
        super(routes);
    }

    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration) {
        VaadinServletService service = new MyVaadinServletService(this,
                deploymentConfiguration, getUiFactory());
        try {
            service.init();
        } catch (ServiceException e) {
            throw new RuntimeException(e);
        }
        getRoutes().register(service.getContext());
        return service;
    }

    private static class MyVaadinServletService extends MockService {
        public MyVaadinServletService(@NotNull VaadinServlet servlet, @NotNull DeploymentConfiguration deploymentConfiguration, @NotNull Function0<? extends UI> uiFactory) {
            super(servlet, deploymentConfiguration, uiFactory);
        }

        @Override
        protected VaadinSession createVaadinSession(VaadinRequest request) {
            return new VirtualThreadAwareVaadinSession(this, getUiFactory());
        }
    }

    private static class VirtualThreadAwareVaadinSession extends MockVaadinSession {
        public VirtualThreadAwareVaadinSession(@NotNull VaadinService service, @NotNull Function0<? extends UI> uiFactory) {
            super(service, uiFactory);
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
