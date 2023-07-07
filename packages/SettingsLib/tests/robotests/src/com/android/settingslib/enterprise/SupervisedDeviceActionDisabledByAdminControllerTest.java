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

import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ADMIN_COMPONENT;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCED_ADMIN;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCEMENT_ADMIN_USER_ID;
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DEVICE_ADMIN_STRING_PROVIDER;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.UserManager;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowResolveInfo;

@RunWith(RobolectricTestRunner.class)
public class SupervisedDeviceActionDisabledByAdminControllerTest {

    private Context mContext;

    private ActionDisabledByAdminControllerTestUtils mTestUtils;
    private SupervisedDeviceActionDisabledByAdminController mController;

    @Before
    public void setUp() {
        mContext = Robolectric.buildActivity(Activity.class).setup().get();

        mTestUtils = new ActionDisabledByAdminControllerTestUtils();

        mController = new SupervisedDeviceActionDisabledByAdminController(
                DEFAULT_DEVICE_ADMIN_STRING_PROVIDER, UserManager.DISALLOW_ADD_USER);
        mController.initialize(mTestUtils.createLearnMoreButtonLauncher());
        mController.updateEnforcedAdmin(ENFORCED_ADMIN, ENFORCEMENT_ADMIN_USER_ID);
    }

    @Test
    public void buttonClicked() {
        Uri restrictionUri = Uri.parse("policy:/user_restrictions/no_add_user");
        Intent intent = new Intent(Settings.ACTION_MANAGE_SUPERVISOR_RESTRICTED_SETTING)
                .setData(restrictionUri)
                .setPackage(ADMIN_COMPONENT.getPackageName());
        ResolveInfo resolveInfo = ShadowResolveInfo.newResolveInfo("Admin Activity",
                ADMIN_COMPONENT.getPackageName(), "InfoActivity");
        shadowOf(mContext.getPackageManager()).addResolveInfoForIntent(intent, resolveInfo);

        DialogInterface.OnClickListener listener =
                mController.getPositiveButtonListener(mContext, ENFORCED_ADMIN);
        assertNotNull("Supervision controller must supply a non-null listener", listener);
        listener.onClick(mock(DialogInterface.class), 0 /* which */);

        Intent nextIntent = shadowOf(RuntimeEnvironment.application).getNextStartedActivity();
        assertEquals(Settings.ACTION_MANAGE_SUPERVISOR_RESTRICTED_SETTING,
                nextIntent.getAction());
        assertEquals(restrictionUri, nextIntent.getData());
        assertEquals(ADMIN_COMPONENT.getPackageName(), nextIntent.getPackage());
    }

    @Test
    public void noButton() {
        // No supervisor restricted setting Activity
        DialogInterface.OnClickListener listener =
                mController.getPositiveButtonListener(mContext, ENFORCED_ADMIN);
        assertNull("Supervision controller generates null listener", listener);
    }
}
