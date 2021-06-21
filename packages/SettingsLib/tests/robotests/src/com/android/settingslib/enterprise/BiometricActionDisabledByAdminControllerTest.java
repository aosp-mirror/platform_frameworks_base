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
import static com.android.settingslib.enterprise.FakeDeviceAdminStringProvider.DEFAULT_DEVICE_ADMIN_STRING_PROVIDER;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.UserHandle;

import com.android.settingslib.RestrictedLockUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BiometricActionDisabledByAdminControllerTest {

    private final ActionDisabledByAdminControllerTestUtils mTestUtils =
            new ActionDisabledByAdminControllerTestUtils();
    private final BiometricActionDisabledByAdminController mController =
            new BiometricActionDisabledByAdminController(DEFAULT_DEVICE_ADMIN_STRING_PROVIDER);

    @Before
    public void setUp() {
        mController.initialize(mTestUtils.createLearnMoreButtonLauncher());
        mController.updateEnforcedAdmin(ENFORCED_ADMIN, ENFORCEMENT_ADMIN_USER_ID);
    }

    @Test
    public void buttonClicked() {
        Context context = mock(Context.class);
        ComponentName componentName = mock(ComponentName.class);
        RestrictedLockUtils.EnforcedAdmin enforcedAdmin = new RestrictedLockUtils.EnforcedAdmin(
                componentName, new UserHandle(UserHandle.myUserId()));

        DialogInterface.OnClickListener listener =
                mController.getPositiveButtonListener(context, enforcedAdmin);
        assertNotNull("Biometric Controller must supply a non-null listener", listener);
        listener.onClick(mock(DialogInterface.class), 0 /* which */);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(context).startActivity(intentCaptor.capture());
        assertEquals("android.settings.LEARN_MORE",
                intentCaptor.getValue().getAction());
        assertTrue("from_biometric_setup", intentCaptor.getValue()
                .getBooleanExtra("from_biometric_setup", false));
        assertEquals(componentName, intentCaptor.getValue().getComponent());
    }

}
