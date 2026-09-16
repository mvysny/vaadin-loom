/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.vaadin.starter.skeleton;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.lumo.Lumo;

@Push
@StyleSheet(Lumo.STYLESHEET)
public class AppShell implements AppShellConfigurator {
}
