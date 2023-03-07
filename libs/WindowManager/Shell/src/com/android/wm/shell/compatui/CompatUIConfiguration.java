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
     * The name of the {@link SharedPreferences} that holds information about compat ui.
     */
    private static final String COMPAT_UI_SHARED_PREFERENCES = "dont_show_restart_dialog";

    /**
     * The name of the {@link SharedPreferences} that holds which user has seen the Letterbox
     * Education dialog.
     */
    private static final String HAS_SEEN_LETTERBOX_EDUCATION_SHARED_PREFERENCES =
            "has_seen_letterbox_education";

    /**
     * Key prefix for the {@link SharedPreferences} entries related to the reachability
     * education.
     */
    private static final String HAS_SEEN_REACHABILITY_EDUCATION_KEY_PREFIX =
            "has_seen_reachability_education";

    /**
     * The {@link SharedPreferences} instance for the restart dialog and the reachability
     * education.
     */
    private final SharedPreferences mCompatUISharedPreferences;

    /**
     * The {@link SharedPreferences} instance for the letterbox education dialog.
     */
    private final SharedPreferences mLetterboxEduSharedPreferences;

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
        mCompatUISharedPreferences = context.getSharedPreferences(getCompatUISharedPreferenceName(),
                Context.MODE_PRIVATE);
        mLetterboxEduSharedPreferences = context.getSharedPreferences(
                getHasSeenLetterboxEducationSharedPreferencedName(), Context.MODE_PRIVATE);
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

    void setDontShowRestartDialogAgain(TaskInfo taskInfo) {
        mCompatUISharedPreferences.edit().putBoolean(
                getDontShowAgainRestartKey(taskInfo.userId, taskInfo.topActivity.getPackageName()),
                true).apply();
    }

    boolean shouldShowRestartDialogAgain(TaskInfo taskInfo) {
        return !mCompatUISharedPreferences.getBoolean(getDontShowAgainRestartKey(taskInfo.userId,
                taskInfo.topActivity.getPackageName()), /* default= */ false);
    }

    void setDontShowReachabilityEducationAgain(TaskInfo taskInfo) {
        mCompatUISharedPreferences.edit().putBoolean(
                getDontShowAgainReachabilityEduKey(taskInfo.userId,
                        taskInfo.topActivity.getPackageName()), true).apply();
    }

    boolean shouldShowReachabilityEducation(@NonNull TaskInfo taskInfo) {
        return getHasSeenLetterboxEducation(taskInfo.userId)
                && !mCompatUISharedPreferences.getBoolean(
                getDontShowAgainReachabilityEduKey(taskInfo.userId,
                        taskInfo.topActivity.getPackageName()), /* default= */false);
    }

    boolean getHasSeenLetterboxEducation(int userId) {
        return mLetterboxEduSharedPreferences
                .getBoolean(getDontShowLetterboxEduKey(userId), /* default= */ false);
    }

    void setSeenLetterboxEducation(int userId) {
        mLetterboxEduSharedPreferences.edit().putBoolean(getDontShowLetterboxEduKey(userId),
                true).apply();
    }

    protected String getCompatUISharedPreferenceName() {
        return COMPAT_UI_SHARED_PREFERENCES;
    }

    protected String getHasSeenLetterboxEducationSharedPreferencedName() {
        return HAS_SEEN_LETTERBOX_EDUCATION_SHARED_PREFERENCES;
    }

    /**
     * Updates the {@link DeviceConfig} state for the CompatUI
     * @param properties Contains the complete collection of properties which have changed for a
     *                   single namespace. This includes only those which were added, updated,
     */
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

    private static String getDontShowAgainReachabilityEduKey(int userId, String packageName) {
        return HAS_SEEN_REACHABILITY_EDUCATION_KEY_PREFIX + "_" + packageName + "@" + userId;
    }

    private static String getDontShowLetterboxEduKey(int userId) {
        return String.valueOf(userId);
    }

    private String getDontShowAgainRestartKey(int userId, String packageName) {
        return packageName + "@" + userId;
    }
}