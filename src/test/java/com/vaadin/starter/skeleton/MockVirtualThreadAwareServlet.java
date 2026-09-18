/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton;

import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockService;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinServlet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;
import com.vaadin.starter.skeleton.loom.VirtualThreadAwareLock;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.locks.Lock;

/**
 * The test-side counterpart of {@link MyServlet}: installs the {@link VirtualThreadAwareLock} so
 * that tests exercise the same session locking the app uses.
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
        protected Lock getSessionLock(WrappedSession wrappedSession) {
            return VirtualThreadAwareLock.wrap(this, wrappedSession, super.getSessionLock(wrappedSession));
        }
    }
}
