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

import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell}.
 */
@Module
// TODO(b/161116823) Clean up dependencies after wm shell migration finished.
public class WindowManagerShellModule {
    @Singleton
    @Provides
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    @Singleton
    @Provides
    static DisplayController provideDisplayController(Context context, @Main Handler handler,
            IWindowManager wmService) {
        return new DisplayController(context, handler, wmService);
    }

    @Singleton
    @Provides
    static SystemWindows provideSystemWindows(DisplayController displayController,
            IWindowManager wmService) {
        return new SystemWindows(displayController, wmService);
    }

    @Singleton
    @Provides
    static DisplayImeController provideDisplayImeController(
            IWindowManager wmService, DisplayController displayController,
            @Main Handler mainHandler, TransactionPool transactionPool) {
        return new DisplayImeController(wmService, displayController, mainHandler, transactionPool);
    }
}
