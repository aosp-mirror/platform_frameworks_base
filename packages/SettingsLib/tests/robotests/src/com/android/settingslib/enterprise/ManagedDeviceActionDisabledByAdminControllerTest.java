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

import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.LEARN_MORE_ACTION_LAUNCH_HELP_PAGE;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DISABLED_BY_POLICY_CONTENT;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DISABLED_BY_POLICY_TITLE;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DISALLOW_ADJUST_VOLUME_TITLE;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.appcompat.app.AlertDialog;

import com.android.settingslib.R;
import com.android.settingslib.RestrictedLockUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class ManagedDeviceActionDisabledByAdminControllerTest {
    private static final int ENFORCEMENT_ADMIN_USER_ID = 123;
    private static final ComponentName ADMIN_COMPONENT =
            new ComponentName("some.package.name", "some.package.name.SomeClass");
    private static final String SUPPORT_MESSAGE = "support message";
    private static final String RESTRICTION = UserManager.DISALLOW_ADJUST_VOLUME;
    private static final String URL = "https://testexample.com";
    private static final String EMPTY_URL = "";
    private static final RestrictedLockUtils.EnforcedAdmin ENFORCED_ADMIN =
            new RestrictedLockUtils.EnforcedAdmin(
                    ADMIN_COMPONENT, UserHandle.of(ENFORCEMENT_ADMIN_USER_ID));
    private static final String SUPPORT_TITLE_FOR_RESTRICTION = DISALLOW_ADJUST_VOLUME_TITLE;

    private final Activity mActivity = ActivityController.of(new Activity()).get();
    private final ActionDisabledByAdminControllerTestUtils mTestUtils =
            new ActionDisabledByAdminControllerTestUtils();
    private final ActionDisabledLearnMoreButtonLauncher mLauncher =
            mTestUtils.createLearnMoreButtonLauncher();

    @Before
    public void setUp() {
        mActivity.setTheme(R.style.Theme_AppCompat_DayNight);
    }

    @Test
    public void setupLearnMoreButton_validUrl_negativeButtonSet() {
        ManagedDeviceActionDisabledByAdminController mController =
                createController(mLauncher, URL);
        AlertDialog alertDialog = mTestUtils.createAlertDialog(mController, mActivity);

        alertDialog.getButton(Dialog.BUTTON_NEUTRAL).performClick();

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_LAUNCH_HELP_PAGE);
    }

    @Test
    public void setupLearnMoreButton_noUrl_negativeButtonSet() {
        ManagedDeviceActionDisabledByAdminController mController =
                createController(mLauncher, EMPTY_URL);
        AlertDialog alertDialog = mTestUtils.createAlertDialog(mController, mActivity);

        alertDialog.getButton(Dialog.BUTTON_NEUTRAL).performClick();

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES);
    }

    @Test
    public void getAdminSupportTitleResource_noRestriction_works() {
        ManagedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportTitle(null))
                .isEqualTo(DEFAULT_DISABLED_BY_POLICY_TITLE);
    }

    @Test
    public void getAdminSupportTitleResource_withRestriction_works() {
        ManagedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportTitle(RESTRICTION))
                .isEqualTo(SUPPORT_TITLE_FOR_RESTRICTION);
    }

    @Test
    public void getAdminSupportContentString_withSupportMessage_returnsSupportMessage() {
        ManagedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportContentString(mActivity, SUPPORT_MESSAGE))
                .isEqualTo(SUPPORT_MESSAGE);
    }

    @Test
    public void getAdminSupportContentString_noSupportMessage_returnsDefault() {
        ManagedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportContentString(mActivity, /* supportMessage= */ null))
                .isEqualTo(DEFAULT_DISABLED_BY_POLICY_CONTENT);
    }

    private ManagedDeviceActionDisabledByAdminController createController() {
        return createController(mLauncher, /* url= */ null);
    }

    private ManagedDeviceActionDisabledByAdminController createController(
            ActionDisabledLearnMoreButtonLauncher buttonHelper, String url) {
        ManagedDeviceActionDisabledByAdminController controller =
                new ManagedDeviceActionDisabledByAdminController(
                        buttonHelper,
                        new FakeDeviceAdminStringProvider(url));
        controller.updateEnforcedAdmin(ENFORCED_ADMIN, ENFORCEMENT_ADMIN_USER_ID);
        return controller;
    }
}
