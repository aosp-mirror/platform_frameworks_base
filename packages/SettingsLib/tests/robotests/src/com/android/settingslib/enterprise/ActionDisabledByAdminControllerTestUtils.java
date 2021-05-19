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

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import com.android.settingslib.RestrictedLockUtils;

/**
 * Utils related to the action disabled by admin dialogs.
 */
class ActionDisabledByAdminControllerTestUtils {
    static final int LEARN_MORE_ACTION_NONE = 0;
    static final int LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES = 1;
    static final int LEARN_MORE_ACTION_LAUNCH_HELP_PAGE = 2;

    private int mLearnMoreButtonAction = LEARN_MORE_ACTION_NONE;

    ActionDisabledLearnMoreButtonLauncher createLearnMoreButtonLauncher() {
        return new ActionDisabledLearnMoreButtonLauncher() {
            @Override
            public void setupLearnMoreButtonToShowAdminPolicies(Activity activity,
                    AlertDialog.Builder builder, int enforcementAdminUserId,
                    RestrictedLockUtils.EnforcedAdmin enforcedAdmin) {
                mLearnMoreButtonAction = LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES;
            }

            @Override
            public void setupLearnMoreButtonToLaunchHelpPage(Activity activity,
                    AlertDialog.Builder builder, String url) {
                mLearnMoreButtonAction = LEARN_MORE_ACTION_LAUNCH_HELP_PAGE;
            }
        };
    }

    void assertLearnMoreAction(int learnMoreActionShowAdminPolicies) {
        assertThat(learnMoreActionShowAdminPolicies).isEqualTo(mLearnMoreButtonAction);
    }

    AlertDialog createAlertDialog(ActionDisabledByAdminController mController, Activity activity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        mController.setupLearnMoreButton(activity, builder);
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        return alertDialog;
    }
}
