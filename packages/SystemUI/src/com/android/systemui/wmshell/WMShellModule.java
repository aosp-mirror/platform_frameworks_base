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

import android.animation.AnimationHandler;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.Handler;
import android.view.IWindowManager;

import com.android.systemui.dagger.WMSingleton;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
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
import com.android.wm.shell.common.annotations.ChoreographerSfVsync;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
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
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

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
            DisplayController displayController, @ShellMainThread ShellExecutor mainExecutor,
            TransactionPool transactionPool) {
        return new DisplayImeController(wmService, displayController, mainExecutor,
                transactionPool);
    }

    @WMSingleton
    @Provides
    static LegacySplitScreen provideLegacySplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, TransactionPool transactionPool,
            ShellTaskOrganizer shellTaskOrganizer, SyncTransactionQueue syncQueue,
            TaskStackListenerImpl taskStackListener, Transitions transitions,
            @ShellMainThread ShellExecutor mainExecutor,
            @ChoreographerSfVsync AnimationHandler sfVsyncAnimationHandler) {
        return LegacySplitScreenController.create(context, displayController, systemWindows,
                displayImeController, transactionPool, shellTaskOrganizer, syncQueue,
                taskStackListener, transitions, mainExecutor, sfVsyncAnimationHandler);
    }

    @WMSingleton
    @Provides
    static AppPairs provideAppPairs(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return AppPairsController.create(shellTaskOrganizer, syncQueue, displayController,
                mainExecutor);
    }

    @WMSingleton
    @Provides
    static Optional<Pip> providePip(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController, PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler, WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(PipController.create(context, displayController,
                pipAppOpsListener, pipBoundsAlgorithm, pipBoundsState, pipMediaController,
                phonePipMenuController, pipTaskOrganizer, pipTouchHandler,
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

    // Handler is used by Icon.loadDrawableAsync
    @WMSingleton
    @Provides
    static PhonePipMenuController providesPipPhoneMenuController(Context context,
            PipMediaController pipMediaController, SystemWindows systemWindows,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return new PhonePipMenuController(context, pipMediaController, systemWindows,
                mainExecutor, mainHandler);
    }

    @WMSingleton
    @Provides
    static PipTouchHandler providePipTouchHandler(Context context,
            PhonePipMenuController menuPhoneController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTouchHandler(context, menuPhoneController, pipBoundsAlgorithm,
                pipBoundsState, pipTaskOrganizer, floatingContentCoordinator, pipUiEventLogger,
                mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PhonePipMenuController menuPhoneController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<LegacySplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTaskOrganizer(context, pipBoundsState, pipBoundsAlgorithm,
                menuPhoneController, pipSurfaceTransactionHelper, splitScreenOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer, mainExecutor);
    }
}
