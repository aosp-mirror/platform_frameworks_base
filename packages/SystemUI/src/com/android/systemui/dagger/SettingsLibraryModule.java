/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dagger;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;

import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.dagger.qualifiers.Background;

import dagger.Module;
import dagger.Provides;

/** */
@Module
public class SettingsLibraryModule {

    /** */
    @SuppressLint("MissingPermission")
    @SysUISingleton
    @Provides
    @Nullable
    static LocalBluetoothManager provideLocalBluetoothController(Context context,
            @Background Handler bgHandler) {
        return LocalBluetoothManager.create(context, bgHandler, UserHandle.ALL);
    }
}
