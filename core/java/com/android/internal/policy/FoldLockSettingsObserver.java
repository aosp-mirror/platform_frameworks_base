/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Set;

/**
 * A ContentObserver that listens for changes in the "Continue using apps on fold" setting. This
 * setting determines a device's behavior when the user folds the device.
 * @hide
 *
 * Keep the setting values in this class in sync with the values in
 * {@link com.android.server.utils.FoldSettingProvider} and
 * {@link com.android.settings.display.FoldLockBehaviorSettings}
 */
public class FoldLockSettingsObserver extends ContentObserver {
    /** The setting for "stay awake on fold". */
    public static final String SETTING_VALUE_STAY_AWAKE_ON_FOLD = "stay_awake_on_fold_key";
    /** The setting for "swipe up to continue". */
    public static final String SETTING_VALUE_SELECTIVE_STAY_AWAKE = "selective_stay_awake_key";
    /** The setting for "always sleep on fold". */
    public static final String SETTING_VALUE_SLEEP_ON_FOLD = "sleep_on_fold_key";
    public static final String SETTING_VALUE_DEFAULT = SETTING_VALUE_SELECTIVE_STAY_AWAKE;
    private static final Set<String> SETTING_VALUES = Set.of(SETTING_VALUE_STAY_AWAKE_ON_FOLD,
            SETTING_VALUE_SELECTIVE_STAY_AWAKE, SETTING_VALUE_SLEEP_ON_FOLD);

    private final Context mContext;

    /** The cached value of the setting. */
    @VisibleForTesting
    String mFoldLockSetting;

    public FoldLockSettingsObserver(Handler handler, Context context) {
        super(handler);
        mContext = context;
    }

    /** Registers the observer and updates the cache for the first time. */
    public void register() {
        final ContentResolver r = mContext.getContentResolver();
        r.registerContentObserver(
                Settings.System.getUriFor(Settings.System.FOLD_LOCK_BEHAVIOR),
                false, this, UserHandle.USER_ALL);
        requestAndCacheFoldLockSetting();
    }

    /** Unregisters the observer. */
    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
    }

    /** Runs when settings changes. */
    @Override
    public void onChange(boolean selfChange) {
        requestAndCacheFoldLockSetting();
    }

    /**
     * Requests and caches the current FOLD_LOCK_BEHAVIOR setting, which should be one of three
     * values: SETTING_VALUE_STAY_AWAKE_ON_FOLD, SETTING_VALUE_SELECTIVE_STAY_AWAKE,
     * SETTING_VALUE_SLEEP_ON_FOLD. If null (not set), returns the system default.
     */
    @VisibleForTesting
    void requestAndCacheFoldLockSetting() {
        String currentSetting = request();

        if (currentSetting == null || !SETTING_VALUES.contains(currentSetting)) {
            currentSetting = SETTING_VALUE_DEFAULT;
        }

        setCurrentFoldSetting(currentSetting);
    }

    /**
     * Makes a binder call to request the current FOLD_LOCK_BEHAVIOR setting.
     */
    @VisibleForTesting
    @Nullable
    String request() {
        return Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.FOLD_LOCK_BEHAVIOR, UserHandle.USER_CURRENT);
    }

    /** Caches the fold-lock behavior received from Settings. */
    @VisibleForTesting
    void setCurrentFoldSetting(String newSetting) {
        mFoldLockSetting = newSetting;
    }

    /** Used by external requesters: checks if the current setting is "stay awake on fold". */
    public boolean isStayAwakeOnFold() {
        return mFoldLockSetting.equals(SETTING_VALUE_STAY_AWAKE_ON_FOLD);
    }

    /** Used by external requesters: checks if the current setting is "swipe up to continue". */
    public boolean isSelectiveStayAwake() {
        return mFoldLockSetting.equals(SETTING_VALUE_SELECTIVE_STAY_AWAKE);
    }

    /** Used by external requesters: checks if the current setting is "sleep on fold". */
    public boolean isSleepOnFold() {
        return mFoldLockSetting.equals(SETTING_VALUE_SLEEP_ON_FOLD);
    }
}
