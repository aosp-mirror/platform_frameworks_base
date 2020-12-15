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
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;

import java.util.concurrent.Executor;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell} which could be customized among different
 * branches of SystemUI.
 */
@Module(includes = {TvPipModule.class})
public class TvWMShellModule {
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
    static LegacySplitScreen provideSplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, @Main Handler handler,
            TransactionPool transactionPool, ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, TaskStackListenerImpl taskStackListener) {
        return new LegacySplitScreenController(context, displayController, systemWindows,
                displayImeController, handler, transactionPool, shellTaskOrganizer, syncQueue,
                taskStackListener);
    }
}
