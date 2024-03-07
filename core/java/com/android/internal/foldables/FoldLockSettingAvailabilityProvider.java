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

package com.android.internal.foldables;

import android.content.res.Resources;
import android.os.Build;
import android.sysprop.FoldLockBehaviorProperties;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.foldables.flags.Flags;

import java.util.function.Supplier;

/**
 * Wrapper class to access {@link FoldLockBehaviorProperties} and also assists with testing
 */
public class FoldLockSettingAvailabilityProvider {

    private static final String TAG = "FoldLockSettingAvailabilityProvider";
    private final boolean mFoldLockBehaviorResourceValue;
    private final Supplier<Boolean> mFoldLockSettingEnabled = Flags::foldLockSettingEnabled;

    public FoldLockSettingAvailabilityProvider(Resources resources) {
        mFoldLockBehaviorResourceValue = resources.getBoolean(
                R.bool.config_fold_lock_behavior);
    }

    public boolean isFoldLockBehaviorAvailable() {
        return mFoldLockBehaviorResourceValue
                && flagOrSystemProperty();
    }

    private boolean flagOrSystemProperty() {
        if ((Build.IS_ENG || Build.IS_USERDEBUG)
                && FoldLockBehaviorProperties.fold_lock_setting_enabled().orElse(false)) {
            return true;
        }
        try {
            return mFoldLockSettingEnabled.get();
        } catch (Throwable ex) {
            Slog.i(TAG,
                    "Flags not ready yet. Return false for "
                            + Flags.FLAG_FOLD_LOCK_SETTING_ENABLED,
                    ex);
            return false;
        }
    }
}
