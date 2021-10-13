/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import com.android.systemui.dagger.SysUISingleton;

/**
 * Default implementation of the a Flag manager that returns default values for release builds
 */
@SysUISingleton
public class FeatureFlagManager {
    public boolean getBoolean(int key, boolean defaultValue) {
        return defaultValue;
    }
    public void setBoolean(int key, boolean value) {}
    public boolean getBoolean(String key, boolean defaultValue) {
        return defaultValue;
    }
    public void setBoolean(String key, boolean value) {}
    public void addFlagChangedListener(Runnable run) {}
    public void removeFlagUpdatedListener(Runnable run) {}
}
