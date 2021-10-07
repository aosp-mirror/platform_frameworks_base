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

import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCED_ADMIN;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCEMENT_ADMIN_USER_ID;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.LEARN_MORE_ACTION_LAUNCH_HELP_PAGE;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.SUPPORT_MESSAGE;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.URL;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DISABLED_BY_POLICY_CONTENT;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DISABLED_BY_POLICY_TITLE;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DISALLOW_ADJUST_VOLUME_TITLE;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class ManagedDeviceActionDisabledByAdminControllerTest {

    private static UserHandle MANAGED_USER = UserHandle.of(123);
    private static final String RESTRICTION = UserManager.DISALLOW_ADJUST_VOLUME;
    private static final String EMPTY_URL = "";
    private static final String SUPPORT_TITLE_FOR_RESTRICTION = DISALLOW_ADJUST_VOLUME_TITLE;
    public static final ResolveInfo TEST_RESULT_INFO = new ResolveInfo();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final Activity mActivity = ActivityController.of(new Activity()).get();
    private final ActionDisabledByAdminControllerTestUtils mTestUtils =
            new ActionDisabledByAdminControllerTestUtils();

    @Before
    public void setUp() {
        mActivity.setTheme(R.style.Theme_AppCompat_DayNight);
    }

    @Test
    public void setupLearnMoreButton_noUrl_negativeButtonSet() {
        ManagedDeviceActionDisabledByAdminController controller = createController(EMPTY_URL);

        controller.setupLearnMoreButton(mContext);

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES);
    }

    @Test
    public void setupLearnMoreButton_validUrl_foregroundUser_launchesHelpPage() {
        ManagedDeviceActionDisabledByAdminController controller = createController(
                URL,
                /* isUserForeground= */ true,
                /* preferredUserHandle= */ MANAGED_USER,
                /* userContainingBrowser= */ MANAGED_USER);

        controller.setupLearnMoreButton(mContext);

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_LAUNCH_HELP_PAGE);
    }

    @Test
    public void setupLearnMoreButton_validUrl_browserInPreferredUser_notForeground_showsAdminPolicies() {
        ManagedDeviceActionDisabledByAdminController controller = createController(
                URL,
                /* isUserForeground= */ false,
                /* preferredUserHandle= */ MANAGED_USER,
                /* userContainingBrowser= */ MANAGED_USER);

        controller.setupLearnMoreButton(mContext);

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES);
    }

    @Test
    public void setupLearnMoreButton_validUrl_browserInCurrentUser_launchesHelpPage() {
        ManagedDeviceActionDisabledByAdminController controller = createController(
                URL,
                /* isUserForeground= */ false,
                /* preferredUserHandle= */ MANAGED_USER,
                /* userContainingBrowser= */ mContext.getUser());

        controller.setupLearnMoreButton(mContext);

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_LAUNCH_HELP_PAGE);
    }

    @Test
    public void setupLearnMoreButton_validUrl_browserNotOnAnyUser_showsAdminPolicies() {
        ManagedDeviceActionDisabledByAdminController controller = createController(
                URL,
                /* isUserForeground= */ false,
                /* preferredUserHandle= */ MANAGED_USER,
                /* userContainingBrowser= */ null);

        controller.setupLearnMoreButton(mContext);

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES);
    }

    @Test
    public void getAdminSupportTitleResource_noRestriction_works() {
        ManagedDeviceActionDisabledByAdminController controller = createController();

        assertThat(controller.getAdminSupportTitle(null))
                .isEqualTo(DEFAULT_DISABLED_BY_POLICY_TITLE);
    }

    @Test
    public void getAdminSupportTitleResource_withRestriction_works() {
        ManagedDeviceActionDisabledByAdminController controller = createController();

        assertThat(controller.getAdminSupportTitle(RESTRICTION))
                .isEqualTo(SUPPORT_TITLE_FOR_RESTRICTION);
    }

    @Test
    public void getAdminSupportContentString_withSupportMessage_returnsSupportMessage() {
        ManagedDeviceActionDisabledByAdminController controller = createController();

        assertThat(controller.getAdminSupportContentString(mActivity, SUPPORT_MESSAGE))
                .isEqualTo(SUPPORT_MESSAGE);
    }

    @Test
    public void getAdminSupportContentString_noSupportMessage_returnsDefault() {
        ManagedDeviceActionDisabledByAdminController controller = createController();

        assertThat(controller.getAdminSupportContentString(mActivity, /* supportMessage= */ null))
                .isEqualTo(DEFAULT_DISABLED_BY_POLICY_CONTENT);
    }

    private ManagedDeviceActionDisabledByAdminController createController() {
        return createController(
                /* url= */ null,
                /* foregroundUserChecker= */ true,
                mContext.getUser(),
                /* userContainingBrowser= */ null);
    }

    private ManagedDeviceActionDisabledByAdminController createController(String url) {
        return createController(
                url,
                /* foregroundUserChecker= */ true,
                mContext.getUser(),
                /* userContainingBrowser= */ null);
    }

    private ManagedDeviceActionDisabledByAdminController createController(
            String url,
            boolean isUserForeground,
            UserHandle preferredUserHandle,
            UserHandle userContainingBrowser) {
        ManagedDeviceActionDisabledByAdminController controller =
                new ManagedDeviceActionDisabledByAdminController(
                        new FakeDeviceAdminStringProvider(url),
                        preferredUserHandle,
                        /* foregroundUserChecker= */ (context, userHandle) -> isUserForeground,
                        /* resolveActivityChecker= */ (packageManager, __, userHandle) ->
                                userHandle.equals(userContainingBrowser));
        controller.initialize(mTestUtils.createLearnMoreButtonLauncher());
        controller.updateEnforcedAdmin(ENFORCED_ADMIN, ENFORCEMENT_ADMIN_USER_ID);
        return controller;
    }
}
