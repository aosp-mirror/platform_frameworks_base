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

package com.android.settingslib.enterprise;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import com.android.settingslib.RestrictedLockUtils;

/**
 * Helper interface meant to set up the "Learn more" button in the action disabled dialog.
 */
public interface ActionDisabledLearnMoreButtonLauncher {

    /**
     * Sets up a "learn more" button which shows a screen with device policy settings
     */
    void setupLearnMoreButtonToShowAdminPolicies(
            Activity activity,
            AlertDialog.Builder builder,
            int enforcementAdminUserId,
            RestrictedLockUtils.EnforcedAdmin enforcedAdmin);

    /**
     * Sets up a "learn more" button which launches a help page
     */
    void setupLearnMoreButtonToLaunchHelpPage(
            Activity activity,
            AlertDialog.Builder builder,
            String url);
}
