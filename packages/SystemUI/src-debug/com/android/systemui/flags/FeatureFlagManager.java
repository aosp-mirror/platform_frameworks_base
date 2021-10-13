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
import android.util.ArraySet;

import com.android.systemui.dagger.SysUISingleton;

/**
 * Concrete implementation of the a Flag manager that returns default values for debug builds
 */
@SysUISingleton
public class FeatureFlagManager {
    public boolean isEnabled(int key, boolean defaultValue) {
        return isEnabled(Integer.toString(key), defaultValue);
    }

    public boolean isEnabled(String key, boolean defaultValue) {
        // TODO
        return false;
    }

    public void setEnabled(int key, boolean value) {
        setEnabled(Integer.toString(key), value);
    }

    public void setEnabled(String key, boolean value) {
        // TODO
    }
}