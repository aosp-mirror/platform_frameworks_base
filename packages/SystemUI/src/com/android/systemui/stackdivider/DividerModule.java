/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import android.content.Context;
import android.os.Handler;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TransactionPool;

import dagger.Module;
import dagger.Provides;

/**
 * Module which provides a Divider.
 */
@Module
public class DividerModule {
    @SysUISingleton
    @Provides
    static Divider provideDivider(Context context, DisplayController displayController,
            SystemWindows systemWindows, DisplayImeController imeController, @Main Handler handler,
            KeyguardStateController keyguardStateController, TransactionPool transactionPool) {
        // TODO(b/161116823): fetch DividerProxy from WM shell lib.
        DividerController dividerController = new DividerController(context, displayController,
                systemWindows, imeController, handler, transactionPool);
        return new Divider(context, dividerController, keyguardStateController);
    }
}
