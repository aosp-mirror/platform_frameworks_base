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

import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DISABLED_BY_POLICY_TITLE_FINANCED_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.R;
import com.android.settingslib.RestrictedLockUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class FinancedDeviceActionDisabledByAdminControllerTest {
    private static final int ENFORCEMENT_ADMIN_USER_ID = 123;
    private static final ComponentName ADMIN_COMPONENT =
            new ComponentName("some.package.name", "some.package.name.SomeClass");
    private static final String SUPPORT_MESSAGE = "support message";
    private static final DeviceAdminStringProvider DEVICE_ADMIN_STRING_PROVIDER =
            new FakeDeviceAdminStringProvider(/* url = */ null);
    private static final RestrictedLockUtils.EnforcedAdmin ENFORCED_ADMIN =
            new RestrictedLockUtils.EnforcedAdmin(
                    ADMIN_COMPONENT, UserHandle.of(ENFORCEMENT_ADMIN_USER_ID));

    private final Context mContext = ApplicationProvider.getApplicationContext();
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
    public void setupLearnMoreButton_negativeButtonSet() {
        FinancedDeviceActionDisabledByAdminController mController = createController(mLauncher);
        AlertDialog alertDialog = mTestUtils.createAlertDialog(mController, mActivity);

        alertDialog.getButton(Dialog.BUTTON_NEUTRAL).performClick();

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES);
    }

    @Test
    public void getAdminSupportTitleResource_works() {
        FinancedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportTitle(null))
                .isEqualTo(DEFAULT_DISABLED_BY_POLICY_TITLE_FINANCED_DEVICE);
    }

    @Test
    public void getAdminSupportContentString_withSupportMessage_returnsSupportMessage() {
        FinancedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportContentString(mContext, SUPPORT_MESSAGE))
                .isEqualTo(SUPPORT_MESSAGE);
    }

    @Test
    public void getAdminSupportContentString_noSupportMessage_returnsNull() {
        FinancedDeviceActionDisabledByAdminController mController = createController();

        assertThat(mController.getAdminSupportContentString(mContext, /* supportMessage= */ null))
                .isNull();
    }

    private FinancedDeviceActionDisabledByAdminController createController() {
        return createController(mLauncher);
    }

    private FinancedDeviceActionDisabledByAdminController createController(
            ActionDisabledLearnMoreButtonLauncher buttonHelper) {
        FinancedDeviceActionDisabledByAdminController controller =
                new FinancedDeviceActionDisabledByAdminController(
                        buttonHelper,
                        DEVICE_ADMIN_STRING_PROVIDER);
        controller.updateEnforcedAdmin(ENFORCED_ADMIN, ENFORCEMENT_ADMIN_USER_ID);
        return controller;
    }
}
