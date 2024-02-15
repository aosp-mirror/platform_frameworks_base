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
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.foldables.FoldLockSettingAvailabilityProvider;
import com.android.internal.util.SettingsWrapper;

import java.util.Set;

/**
 * This class provides a convenient way to access the {@link Settings.System#FOLD_LOCK_BEHAVIOR}.
 * The {@link Settings.System#FOLD_LOCK_BEHAVIOR} setting controls the behavior of the device when
 * it is folded, and provides the user with three different options to choose from. Those are:
 * 1. Stay awake on fold: The device will remain unlocked when it is folded.
 * 2. Selective stay awake: The device will remain unlocked when it is folded only if there are
 * apps with wakelocks running. This is also the set default behavior.
 * 3. Sleep on fold: The device will lock when it is folded, regardless of which apps are running
 * or whether any wakelocks are held.
 *
 * Keep the setting values in this class in sync with the values in
 * {@link com.android.settings.display.FoldLockBehaviorSettings}
 */
public class FoldSettingProvider {

    public static final String SETTING_VALUE_STAY_AWAKE_ON_FOLD = "stay_awake_on_fold_key";
    public static final String SETTING_VALUE_SELECTIVE_STAY_AWAKE = "selective_stay_awake_key";
    public static final String SETTING_VALUE_SLEEP_ON_FOLD = "sleep_on_fold_key";
    private static final String SETTING_VALUE_DEFAULT = SETTING_VALUE_SELECTIVE_STAY_AWAKE;
    private static final Set<String> SETTING_VALUES = Set.of(SETTING_VALUE_STAY_AWAKE_ON_FOLD,
            SETTING_VALUE_SELECTIVE_STAY_AWAKE, SETTING_VALUE_SLEEP_ON_FOLD);
    private static final String TAG = "FoldSettingProvider";

    private final ContentResolver mContentResolver;
    private final SettingsWrapper mSettingsWrapper;
    private final FoldLockSettingAvailabilityProvider mFoldLockSettingAvailabilityProvider;

    public FoldSettingProvider(Context context, SettingsWrapper settingsWrapper,
            FoldLockSettingAvailabilityProvider foldLockSettingAvailabilityProvider) {
        mContentResolver = context.getContentResolver();
        mSettingsWrapper = settingsWrapper;
        mFoldLockSettingAvailabilityProvider = foldLockSettingAvailabilityProvider;
    }

    /**
     * Returns whether the device should remain awake after folding.
     */
    public boolean shouldStayAwakeOnFold() {
        return getFoldSettingValue().equals(SETTING_VALUE_STAY_AWAKE_ON_FOLD);
    }

    /**
     * Returns whether the device should selective remain awake after folding.
     */
    public boolean shouldSelectiveStayAwakeOnFold() {
        return getFoldSettingValue().equals(SETTING_VALUE_SELECTIVE_STAY_AWAKE);
    }

    /**
     * Returns whether the device should strictly sleep after folding.
     */
    public boolean shouldSleepOnFold() {
        return getFoldSettingValue().equals(SETTING_VALUE_SLEEP_ON_FOLD);
    }

    private String getFoldSettingValue() {
        boolean isFoldLockBehaviorAvailable =
                mFoldLockSettingAvailabilityProvider.isFoldLockBehaviorAvailable();
        if (!isFoldLockBehaviorAvailable) {
            return SETTING_VALUE_DEFAULT;
        }
        String foldSettingValue = mSettingsWrapper.getStringForUser(
                mContentResolver,
                Settings.System.FOLD_LOCK_BEHAVIOR,
                UserHandle.USER_CURRENT);
        foldSettingValue = (foldSettingValue != null) ? foldSettingValue : SETTING_VALUE_DEFAULT;
        if (!SETTING_VALUES.contains(foldSettingValue)) {
            Log.e(TAG,
                    "getFoldSettingValue: Invalid setting value, returning default setting value");
            foldSettingValue = SETTING_VALUE_DEFAULT;
        }

        return foldSettingValue;
    }
}
