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

package com.android.wm.shell.compatui;

import android.annotation.NonNull;
import android.app.TaskInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.DeviceConfig;

import com.android.wm.shell.R;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.dagger.WMSingleton;

import javax.inject.Inject;

/**
 * Configuration flags for the CompatUX implementation
 */
@WMSingleton
public class CompatUIConfiguration implements DeviceConfig.OnPropertiesChangedListener {

    private static final String KEY_ENABLE_LETTERBOX_RESTART_DIALOG =
            "enable_letterbox_restart_confirmation_dialog";

    private static final String KEY_ENABLE_LETTERBOX_REACHABILITY_EDUCATION =
            "enable_letterbox_reachability_education";

    private static final boolean DEFAULT_VALUE_ENABLE_LETTERBOX_RESTART_DIALOG = true;

    private static final boolean DEFAULT_VALUE_ENABLE_LETTERBOX_REACHABILITY_EDUCATION = false;

    /**
     * The name of the {@link SharedPreferences} that holds which user has seen the Restart
     * confirmation dialog.
     */
    private static final String DONT_SHOW_RESTART_DIALOG_PREF_NAME = "dont_show_restart_dialog";

    /**
     * The {@link SharedPreferences} instance for {@link #DONT_SHOW_RESTART_DIALOG_PREF_NAME}.
     */
    private final SharedPreferences mSharedPreferences;

    // Whether the extended restart dialog is enabled
    private boolean mIsRestartDialogEnabled;

    // Whether the additional education about reachability is enabled
    private boolean mIsReachabilityEducationEnabled;

    // Whether the extended restart dialog is enabled
    private boolean mIsRestartDialogOverrideEnabled;

    // Whether the additional education about reachability is enabled
    private boolean mIsReachabilityEducationOverrideEnabled;

    // Whether the extended restart dialog is allowed from backend
    private boolean mIsLetterboxRestartDialogAllowed;

    // Whether the additional education about reachability is allowed from backend
    private boolean mIsLetterboxReachabilityEducationAllowed;

    @Inject
    public CompatUIConfiguration(Context context, @ShellMainThread ShellExecutor mainExecutor) {
        mIsRestartDialogEnabled = context.getResources().getBoolean(
                R.bool.config_letterboxIsRestartDialogEnabled);
        mIsReachabilityEducationEnabled = context.getResources().getBoolean(
                R.bool.config_letterboxIsReachabilityEducationEnabled);
        mIsLetterboxRestartDialogAllowed = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_ENABLE_LETTERBOX_RESTART_DIALOG,
                DEFAULT_VALUE_ENABLE_LETTERBOX_RESTART_DIALOG);
        mIsLetterboxReachabilityEducationAllowed = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_ENABLE_LETTERBOX_REACHABILITY_EDUCATION,
                DEFAULT_VALUE_ENABLE_LETTERBOX_REACHABILITY_EDUCATION);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_APP_COMPAT, mainExecutor,
                this);
        mSharedPreferences = context.getSharedPreferences(DONT_SHOW_RESTART_DIALOG_PREF_NAME,
                Context.MODE_PRIVATE);
    }

    /**
     * @return {@value true} if the restart dialog is enabled.
     */
    boolean isRestartDialogEnabled() {
        return mIsRestartDialogOverrideEnabled || (mIsRestartDialogEnabled
                && mIsLetterboxRestartDialogAllowed);
    }

    /**
     * Enables/Disables the restart education dialog
     */
    void setIsRestartDialogOverrideEnabled(boolean enabled) {
        mIsRestartDialogOverrideEnabled = enabled;
    }

    /**
     * @return {@value true} if the reachability education is enabled.
     */
    boolean isReachabilityEducationEnabled() {
        return mIsReachabilityEducationOverrideEnabled || (mIsReachabilityEducationEnabled
                && mIsLetterboxReachabilityEducationAllowed);
    }

    /**
     * Enables/Disables the reachability education
     */
    void setIsReachabilityEducationOverrideEnabled(boolean enabled) {
        mIsReachabilityEducationOverrideEnabled = enabled;
    }

    boolean getDontShowRestartDialogAgain(TaskInfo taskInfo) {
        final int userId = taskInfo.userId;
        final String packageName = taskInfo.topActivity.getPackageName();
        return mSharedPreferences.getBoolean(
                getDontShowAgainRestartKey(userId, packageName), /* default= */ false);
    }

    void setDontShowRestartDialogAgain(TaskInfo taskInfo) {
        final int userId = taskInfo.userId;
        final String packageName = taskInfo.topActivity.getPackageName();
        mSharedPreferences.edit().putBoolean(getDontShowAgainRestartKey(userId, packageName),
                true).apply();
    }

    @Override
    public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
        if (properties.getKeyset().contains(KEY_ENABLE_LETTERBOX_RESTART_DIALOG)) {
            mIsLetterboxRestartDialogAllowed = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_ENABLE_LETTERBOX_RESTART_DIALOG,
                    DEFAULT_VALUE_ENABLE_LETTERBOX_RESTART_DIALOG);
        }
        // TODO(b/263349751): Update flag and default value to true
        if (properties.getKeyset().contains(KEY_ENABLE_LETTERBOX_REACHABILITY_EDUCATION)) {
            mIsLetterboxReachabilityEducationAllowed = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                    KEY_ENABLE_LETTERBOX_REACHABILITY_EDUCATION,
                    DEFAULT_VALUE_ENABLE_LETTERBOX_REACHABILITY_EDUCATION);
        }
    }

    private String getDontShowAgainRestartKey(int userId, String packageName) {
        return packageName + "@" + userId;
    }
}