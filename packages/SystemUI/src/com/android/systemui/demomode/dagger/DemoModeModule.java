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

package com.android.systemui.demomode.dagger;

import android.content.Context;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.settings.GlobalSettings;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module providing {@link DemoModeController}
 */
@Module
public abstract class DemoModeModule {
    /** Provides DemoModeController */
    @Provides
    @SysUISingleton
    static DemoModeController provideDemoModeController(
            Context context,
            DumpManager dumpManager,
            GlobalSettings globalSettings,
            BroadcastDispatcher broadcastDispatcher
    ) {
        DemoModeController dmc = new DemoModeController(
                context,
                dumpManager,
                globalSettings,
                broadcastDispatcher);
        dmc.initialize();
        return /*run*/dmc;
    }
}
