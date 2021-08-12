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
import android.content.Context;
import android.view.IWindowManager;

import com.android.systemui.dagger.WMComponent;
import com.android.systemui.dagger.WMSingleton;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ChoreographerSfVsync;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.startingsurface.StartingWindowTypeAlgorithm;
import com.android.wm.shell.startingsurface.tv.TvStartingWindowTypeAlgorithm;
import com.android.wm.shell.transition.Transitions;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell}, these dependencies are only
 * accessible from components within the WM subcomponent (can be explicitly exposed to the
 * SysUIComponent, see {@link WMComponent}).
 *
 * This module only defines Shell dependencies for the TV SystemUI implementation.  Common
 * dependencies should go into {@link WMShellBaseModule}.
 */
@Module(includes = {TvPipModule.class})
public class TvWMShellModule {

    //
    // Internal common - Components used internally by multiple shell features
    //

    @WMSingleton
    @Provides
    static DisplayImeController provideDisplayImeController(IWindowManager wmService,
            DisplayController displayController, DisplayInsetsController displayInsetsController,
            @ShellMainThread ShellExecutor mainExecutor, TransactionPool transactionPool) {
        return new DisplayImeController(wmService, displayController, displayInsetsController,
                mainExecutor, transactionPool);
    }

    //
    // Split/multiwindow
    //

    @WMSingleton
    @Provides
    static LegacySplitScreenController provideSplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, TransactionPool transactionPool,
            ShellTaskOrganizer shellTaskOrganizer, SyncTransactionQueue syncQueue,
            TaskStackListenerImpl taskStackListener, Transitions transitions,
            @ShellMainThread ShellExecutor mainExecutor,
            @ChoreographerSfVsync AnimationHandler sfVsyncAnimationHandler) {
        return new LegacySplitScreenController(context, displayController, systemWindows,
                displayImeController, transactionPool, shellTaskOrganizer, syncQueue,
                taskStackListener, transitions, mainExecutor, sfVsyncAnimationHandler);
    }

    //
    // Starting Windows (Splash Screen)
    //

    @WMSingleton
    @Provides
    static StartingWindowTypeAlgorithm provideStartingWindowTypeAlgorithm() {
        return new TvStartingWindowTypeAlgorithm();
    };
}
