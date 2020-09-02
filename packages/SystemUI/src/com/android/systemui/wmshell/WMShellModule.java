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
import android.view.IWindowManager;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.onehanded.OneHanded;
import com.android.systemui.onehanded.OneHandedController;
import com.android.systemui.pip.Pip;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipSurfaceTransactionHelper;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.pip.PipUiEventLogger;
import com.android.systemui.pip.phone.PipController;
import com.android.systemui.stackdivider.SplitScreen;
import com.android.systemui.stackdivider.SplitScreenController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell} which could be customized among different
 * branches of SystemUI.
 */
// TODO(b/162923491): Move most of these dependencies into WMSingleton scope.
@Module(includes = WMShellBaseModule.class)
public class WMShellModule {
    @SysUISingleton
    @Provides
    static DisplayImeController provideDisplayImeController(IWindowManager wmService,
            DisplayController displayController, @Main Handler mainHandler,
            TransactionPool transactionPool) {
        return new DisplayImeController(wmService, displayController, mainHandler, transactionPool);
    }

    @SysUISingleton
    @Provides
    static Pip providePipController(Context context,
            BroadcastDispatcher broadcastDispatcher,
            ConfigurationController configController,
            DeviceConfigProxy deviceConfig,
            DisplayController displayController,
            FloatingContentCoordinator floatingContentCoordinator,
            SysUiState sysUiState,
            PipBoundsHandler pipBoundsHandler,
            PipSurfaceTransactionHelper surfaceTransactionHelper,
            PipTaskOrganizer pipTaskOrganizer,
            PipUiEventLogger pipUiEventLogger) {
        return new PipController(context, broadcastDispatcher, configController, deviceConfig,
                displayController, floatingContentCoordinator, sysUiState, pipBoundsHandler,
                surfaceTransactionHelper,
                pipTaskOrganizer,
                pipUiEventLogger);
    }

    @SysUISingleton
    @Provides
    static SplitScreen provideSplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, @Main Handler handler,
            TransactionPool transactionPool, ShellTaskOrganizer shellTaskOrganizer) {
        return new SplitScreenController(context, displayController, systemWindows,
                displayImeController, handler, transactionPool, shellTaskOrganizer);
    }

    @SysUISingleton
    @Provides
    static PipBoundsHandler providesPipBoundsHandler(Context context) {
        return new PipBoundsHandler(context);
    }

    @SysUISingleton
    @Provides
    static PipTaskOrganizer providesPipTaskOrganizer(Context context,
            PipBoundsHandler pipBoundsHandler,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer) {
        return new PipTaskOrganizer(context, pipBoundsHandler,
                pipSurfaceTransactionHelper, splitScreenOptional, displayController,
                pipUiEventLogger, shellTaskOrganizer);
    }

    @SysUISingleton
    @Provides
    static OneHanded provideOneHandedController(Context context,
            DisplayController displayController) {
        return OneHandedController.create(context, displayController);
    }
}
