/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.utils;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * A wrapper class for the {@link Settings.System#STAY_AWAKE_ON_FOLD} setting.
 *
 * This class provides a convenient way to access the {@link Settings.System#STAY_AWAKE_ON_FOLD}
 * setting for testing.
 */
public class FoldSettingWrapper {
    private final ContentResolver mContentResolver;

    public FoldSettingWrapper(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    /**
     * Returns whether the device should remain awake after folding.
     */
    public boolean shouldStayAwakeOnFold() {
        try {
            return (Settings.System.getIntForUser(
                    mContentResolver,
                    Settings.System.STAY_AWAKE_ON_FOLD,
                    0) == 1);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }
}
