/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.development;

import static com.android.settingslib.RestrictedLockUtilsInternal.checkIfUsbDataSignalingIsDisabled;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.RestrictedSwitchPreference;
import com.android.settingslib.core.ConfirmationDialogController;

public abstract class AbstractEnableAdbPreferenceController extends
        DeveloperOptionsPreferenceController implements ConfirmationDialogController {
    private static final String KEY_ENABLE_ADB = "enable_adb";
    public static final String ACTION_ENABLE_ADB_STATE_CHANGED =
            "com.android.settingslib.development.AbstractEnableAdbController."
                    + "ENABLE_ADB_STATE_CHANGED";

    public static final int ADB_SETTING_ON = 1;
    public static final int ADB_SETTING_OFF = 0;


    protected RestrictedSwitchPreference mPreference;

    public AbstractEnableAdbPreferenceController(Context context) {
        super(context);
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        if (isAvailable()) {
            mPreference = (RestrictedSwitchPreference) screen.findPreference(KEY_ENABLE_ADB);
        }
    }

    @Override
    public boolean isAvailable() {
        return mContext.getSystemService(UserManager.class).isAdminUser();
    }

    @Override
    public String getPreferenceKey() {
        return KEY_ENABLE_ADB;
    }

    private boolean isAdbEnabled() {
        final ContentResolver cr = mContext.getContentResolver();
        return Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, ADB_SETTING_OFF)
                != ADB_SETTING_OFF;
    }

    @Override
    public void updateState(Preference preference) {
        ((TwoStatePreference) preference).setChecked(isAdbEnabled());
        if (isAvailable()) {
            ((RestrictedSwitchPreference) preference).setDisabledByAdmin(
                    checkIfUsbDataSignalingIsDisabled(mContext, UserHandle.myUserId()));
        }
    }

    @Override
    protected void onDeveloperOptionsSwitchEnabled() {
        super.onDeveloperOptionsSwitchEnabled();
        if (isAvailable()) {
            mPreference.setDisabledByAdmin(
                    checkIfUsbDataSignalingIsDisabled(mContext, UserHandle.myUserId()));
        }
    }

    public void enablePreference(boolean enabled) {
        if (isAvailable()) {
            mPreference.setEnabled(enabled);
        }
    }

    public void resetPreference() {
        if (mPreference.isChecked()) {
            mPreference.setChecked(false);
            handlePreferenceTreeClick(mPreference);
        }
    }

    public boolean haveDebugSettings() {
        return isAdbEnabled();
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (isUserAMonkey()) {
            return false;
        }

        if (TextUtils.equals(KEY_ENABLE_ADB, preference.getKey())) {
            if (!isAdbEnabled()) {
                showConfirmationDialog(preference);
            } else {
                writeAdbSetting(false);
            }
            return true;
        } else {
            return false;
        }
    }

    protected void writeAdbSetting(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED, enabled ? ADB_SETTING_ON : ADB_SETTING_OFF);
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        LocalBroadcastManager.getInstance(mContext)
                .sendBroadcast(new Intent(ACTION_ENABLE_ADB_STATE_CHANGED));
    }

    @VisibleForTesting
    boolean isUserAMonkey() {
        return ActivityManager.isUserAMonkey();
    }
}
