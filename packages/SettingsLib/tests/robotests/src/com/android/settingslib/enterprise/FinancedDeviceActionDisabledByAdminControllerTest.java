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
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.SUPPORT_MESSAGE;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DEVICE_ADMIN_STRING_PROVIDER;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DISABLED_BY_POLICY_TITLE_FINANCED_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class FinancedDeviceActionDisabledByAdminControllerTest {

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final Activity mActivity = ActivityController.of(new Activity()).get();
    private final ActionDisabledByAdminControllerTestUtils mTestUtils =
            new ActionDisabledByAdminControllerTestUtils();
    private final FinancedDeviceActionDisabledByAdminController mController =
            new FinancedDeviceActionDisabledByAdminController(
                    DEFAULT_DEVICE_ADMIN_STRING_PROVIDER);

    @Before
    public void setUp() {
        mActivity.setTheme(R.style.Theme_AppCompat_DayNight);

        mController.initialize(mTestUtils.createLearnMoreButtonLauncher());
        mController.updateEnforcedAdmin(ENFORCED_ADMIN, ENFORCEMENT_ADMIN_USER_ID);

    }

    @Test
    public void setupLearnMoreButton_negativeButtonSet() {
        mController.setupLearnMoreButton(mContext);

        mTestUtils.assertLearnMoreAction(LEARN_MORE_ACTION_SHOW_ADMIN_POLICIES);
    }

    @Test
    public void getAdminSupportTitleResource_works() {
        assertThat(mController.getAdminSupportTitle(null))
                .isEqualTo(DEFAULT_DISABLED_BY_POLICY_TITLE_FINANCED_DEVICE);
    }

    @Test
    public void getAdminSupportContentString_withSupportMessage_returnsSupportMessage() {
        assertThat(mController.getAdminSupportContentString(mContext, SUPPORT_MESSAGE))
                .isEqualTo(SUPPORT_MESSAGE);
    }

    @Test
    public void getAdminSupportContentString_noSupportMessage_returnsNull() {
        assertThat(mController.getAdminSupportContentString(mContext, /* supportMessage= */ null))
                .isNull();
    }
}
