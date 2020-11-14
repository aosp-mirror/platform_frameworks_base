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

import com.android.systemui.dagger.WMSingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.apppairs.AppPairsController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipMenuActivityController;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.Optional;
import java.util.concurrent.Executor;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell} which could be customized among different
 * branches of SystemUI.
 */
@Module(includes = WMShellBaseModule.class)
public class WMShellModule {
    @WMSingleton
    @Provides
    static DisplayImeController provideDisplayImeController(IWindowManager wmService,
            DisplayController displayController, @Main Executor mainExecutor,
            TransactionPool transactionPool) {
        return new DisplayImeController(wmService, displayController, mainExecutor,
                transactionPool);
    }

    @WMSingleton
    @Provides
    static SplitScreen provideSplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, @Main Handler handler,
            TransactionPool transactionPool, ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, TaskStackListenerImpl taskStackListener) {
        return new SplitScreenController(context, displayController, systemWindows,
                displayImeController, handler, transactionPool, shellTaskOrganizer, syncQueue,
                taskStackListener);
    }

    @WMSingleton
    @Provides
    static AppPairs provideAppPairs(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue) {
        return new AppPairsController(shellTaskOrganizer, syncQueue);
    }

    @WMSingleton
    @Provides
    static Optional<Pip> providePip(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PipMenuActivityController pipMenuActivityController, PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler, WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener, ShellExecutor mainExecutor) {
        return Optional.ofNullable(PipController.create(context, displayController,
                pipAppOpsListener, pipBoundsAlgorithm, pipBoundsState, pipMediaController,
                pipMenuActivityController, pipTaskOrganizer, pipTouchHandler,
                windowManagerShellWrapper, taskStackListener, mainExecutor));
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context) {
        return new PipBoundsState(context);
    }

    @WMSingleton
    @Provides
    static PipBoundsAlgorithm providesPipBoundsHandler(Context context,
            PipBoundsState pipBoundsState) {
        return new PipBoundsAlgorithm(context, pipBoundsState);
    }

    @WMSingleton
    @Provides
    static PipMenuActivityController providesPipMenuActivityController(Context context,
            PipMediaController pipMediaController, SystemWindows systemWindows) {
        return new PipMenuActivityController(context, pipMediaController, systemWindows);
    }

    @WMSingleton
    @Provides
    static PipTouchHandler providePipTouchHandler(Context context,
            PipMenuActivityController menuActivityController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger) {
        return new PipTouchHandler(context, menuActivityController, pipBoundsAlgorithm,
                pipBoundsState, pipTaskOrganizer, floatingContentCoordinator, pipUiEventLogger);
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipMenuActivityController menuActivityController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer) {
        return new PipTaskOrganizer(context, pipBoundsState, pipBoundsAlgorithm,
                menuActivityController, pipSurfaceTransactionHelper, splitScreenOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer);
    }
}
