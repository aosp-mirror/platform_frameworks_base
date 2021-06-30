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

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.util.DebugUtils;

import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Utils related to the action disabled by admin dialogs.
 */
// NOTE: must be public because of DebugUtils.constantToString() call
public final class ActionDisabledByAdminControllerTestUtils {

    static final int ENFORCEMENT_ADMIN_USER_ID = 123;
    static final UserHandle ENFORCEMENT_ADMIN_USER = UserHandle.of(ENFORCEMENT_ADMIN_USER_ID);

    static final String SUPPORT_MESSAGE = "support message";

    static final ComponentName ADMIN_COMPONENT =
            new ComponentName("some.package.name", "some.package.name.SomeClass");
    static final EnforcedAdmin ENFORCED_ADMIN = new EnforcedAdmin(
                    ADMIN_COMPONENT, UserHandle.of(ENFORCEMENT_ADMIN_USER_ID));
    static final EnforcedAdmin ENFORCED_ADMIN_WITHOUT_COMPONENT = new EnforcedAdmin(
            /* component= */ null, UserHandle.of(ENFORCEMENT_ADMIN_USER_ID));

    static final String URL = "https://testexample.com";

    // NOTE: fields below must be public because of DebugUtils.constantToString() call
    public static final int LEARN_MORE_ACTION_NONE = 0;
    public static final int LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES = 1;
    public static final int LEARN_MORE_ACTION_SHOW_ADMIN_SETTINGS = 2;
    public static final int LEARN_MORE_ACTION_LAUNCH_HELP_PAGE = 3;

    private int mLearnMoreButtonAction = LEARN_MORE_ACTION_NONE;

    ActionDisabledLearnMoreButtonLauncher createLearnMoreButtonLauncher() {
        return new ActionDisabledLearnMoreButtonLauncher() {

            @Override
            public void setLearnMoreButton(Runnable action) {
                action.run();
            }

            @Override
            protected void launchShowAdminPolicies(Context context, UserHandle user,
                    ComponentName admin) {
                mLearnMoreButtonAction = LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES;
            }

            @Override
            protected void launchShowAdminSettings(Context context) {
                mLearnMoreButtonAction = LEARN_MORE_ACTION_SHOW_ADMIN_SETTINGS;
            }

            @Override
            public void showHelpPage(Context context, String url, UserHandle userHandle) {
                mLearnMoreButtonAction = LEARN_MORE_ACTION_LAUNCH_HELP_PAGE;
            }

            @Override
            protected boolean isSameProfileGroup(Context context, int enforcementAdminUserId) {
                return true;
            }
        };
    }

    void assertLearnMoreAction(int learnMoreActionShowAdminPolicies) {
        assertWithMessage("action").that(actionToString(mLearnMoreButtonAction))
                .isEqualTo(actionToString(learnMoreActionShowAdminPolicies));
    }

    private static String actionToString(int action) {
        return DebugUtils.constantToString(ActionDisabledByAdminControllerTestUtils.class,
                "LEARN_MORE_ACTION_", action);
    }
}
