/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton;

import com.github.mvysny.vaadinboot.VaadinBoot;
import org.jetbrains.annotations.NotNull;

/**
 * Run {@link #main(String[])} to launch your app in Embedded Jetty.
 * @author mavi
 */
public final class Main {
    public static void main(@NotNull String[] args) throws Exception {
        new VaadinBoot()
                .useVirtualThreadsIfAvailable(false)  // see VaadinSuspendingExecutor for the reason
                .run();
    }
}
