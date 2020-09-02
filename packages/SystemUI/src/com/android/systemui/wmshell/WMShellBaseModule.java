/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.wmshell;

import android.content.Context;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.IWindowManager;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.onehanded.OneHanded;
import com.android.systemui.pip.Pip;
import com.android.systemui.pip.PipSurfaceTransactionHelper;
import com.android.systemui.pip.PipUiEventLogger;
import com.android.systemui.stackdivider.SplitScreen;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.animation.FlingAnimationUtils;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * Provides basic dependencies from {@link com.android.wm.shell}, the dependencies declared here
 * should be shared among different branches of SystemUI.
 */
// TODO(b/162923491): Move most of these dependencies into WMSingleton scope.
@Module
public abstract class WMShellBaseModule {
    @SysUISingleton
    @Provides
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    @SysUISingleton
    @Provides
    static DisplayController provideDisplayController(Context context, @Main Handler handler,
            IWindowManager wmService) {
        return new DisplayController(context, handler, wmService);
    }

    @SysUISingleton
    @Provides
    static DeviceConfigProxy provideDeviceConfigProxy() {
        return new DeviceConfigProxy();
    }

    @SysUISingleton
    @Provides
    static FloatingContentCoordinator provideFloatingContentCoordinator() {
        return new FloatingContentCoordinator();
    }

    @SysUISingleton
    @Provides
    static PipUiEventLogger providePipUiEventLogger(UiEventLogger uiEventLogger) {
        return new PipUiEventLogger(uiEventLogger);
    }

    @SysUISingleton
    @Provides
    static PipSurfaceTransactionHelper providesPipSurfaceTransactionHelper(Context context,
            ConfigurationController configController) {
        return new PipSurfaceTransactionHelper(context, configController);
    }

    @SysUISingleton
    @Provides
    static SystemWindows provideSystemWindows(DisplayController displayController,
            IWindowManager wmService) {
        return new SystemWindows(displayController, wmService);
    }

    @SysUISingleton
    @Provides
    static ShellTaskOrganizer provideShellTaskOrganizer() {
        ShellTaskOrganizer organizer = new ShellTaskOrganizer();
        organizer.registerOrganizer();
        return organizer;
    }

    @SysUISingleton
    @Provides
    static FlingAnimationUtils.Builder provideFlingAnimationUtilsBuilder(
            DisplayMetrics displayMetrics) {
        return new FlingAnimationUtils.Builder(displayMetrics);
    }

    @BindsOptionalOf
    abstract Pip optionalPip();

    @BindsOptionalOf
    abstract SplitScreen optionalSplitScreen();

    @BindsOptionalOf
    abstract OneHanded optionalOneHanded();
}
