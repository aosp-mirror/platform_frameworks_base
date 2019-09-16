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

package com.android.systemui;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;

import com.android.systemui.assist.AssistModule;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardLiftController;
import com.android.systemui.util.sensors.AsyncSensorManager;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * A dagger module for injecting components of System UI that are not overridden by the System UI
 * implementation.
 */
@Module(includes = {AssistModule.class})
public abstract class SystemUIModule {

    @Singleton
    @Provides
    @Nullable
    static KeyguardLiftController provideKeyguardLiftController(Context context,
            StatusBarStateController statusBarStateController,
            AsyncSensorManager asyncSensorManager) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)) {
            return null;
        }
        return new KeyguardLiftController(statusBarStateController, asyncSensorManager);
    }


    @Singleton
    @Provides
    static SysUiState provideSysUiState() {
        return new SysUiState();
    }
}
