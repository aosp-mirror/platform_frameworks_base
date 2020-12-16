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
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PhonePipMenuController;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;

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
    static LegacySplitScreen provideLegacySplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, @Main Handler handler,
            TransactionPool transactionPool, ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, TaskStackListenerImpl taskStackListener) {
        return new LegacySplitScreenController(context, displayController, systemWindows,
                displayImeController, handler, transactionPool, shellTaskOrganizer, syncQueue,
                taskStackListener);
    }

    @WMSingleton
    @Provides
    static AppPairs provideAppPairs(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, DisplayController displayController) {
        return new AppPairsController(shellTaskOrganizer, syncQueue, displayController);
    }

    @WMSingleton
    @Provides
    static Optional<Pip> providePip(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController, PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler, WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            @ShellMainThread ShellExecutor shellMainExecutor) {
        return Optional.ofNullable(PipController.create(context, displayController,
                pipAppOpsListener, pipBoundsAlgorithm, pipBoundsState, pipMediaController,
                phonePipMenuController, pipTaskOrganizer, pipTouchHandler,
                windowManagerShellWrapper, taskStackListener, shellMainExecutor));
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
    static PhonePipMenuController providesPipPhoneMenuController(Context context,
            PipMediaController pipMediaController, SystemWindows systemWindows) {
        return new PhonePipMenuController(context, pipMediaController, systemWindows);
    }

    @WMSingleton
    @Provides
    static PipTouchHandler providePipTouchHandler(Context context,
            PhonePipMenuController menuPhoneController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor shellMainExecutor) {
        return new PipTouchHandler(context, menuPhoneController, pipBoundsAlgorithm,
                pipBoundsState, pipTaskOrganizer, floatingContentCoordinator, pipUiEventLogger,
                shellMainExecutor);
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PhonePipMenuController menuPhoneController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<LegacySplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer) {
        return new PipTaskOrganizer(context, pipBoundsState, pipBoundsAlgorithm,
                menuPhoneController, pipSurfaceTransactionHelper, splitScreenOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer);
    }
}
