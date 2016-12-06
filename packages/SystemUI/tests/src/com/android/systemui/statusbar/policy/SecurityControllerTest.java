/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.os.UserManager;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class SecurityControllerTest extends SysuiTestCase {
    private final DevicePolicyManager mDevicePolicyManager = mock(DevicePolicyManager.class);
    private SecurityControllerImpl mSecurityController;

    @Before
    public void setUp() throws Exception {
        final Context context = mock(Context.class);
        when(context.getSystemService(Context.DEVICE_POLICY_SERVICE))
                .thenReturn(mDevicePolicyManager);
        when(context.getSystemService(Context.CONNECTIVITY_SERVICE))
                .thenReturn(mock(ConnectivityManager.class));
        final UserManager userManager = mock(UserManager.class);
        when(userManager.getUserInfo(anyInt())).thenReturn(mock(UserInfo.class));
        when(context.getSystemService(Context.USER_SERVICE))
                .thenReturn(userManager);
        mSecurityController = new SecurityControllerImpl(context);
    }

    @Test
    public void testIsDeviceManaged() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        assertTrue(mSecurityController.isDeviceManaged());

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        assertFalse(mSecurityController.isDeviceManaged());
    }

    @Test
    public void testGetDeviceOwnerOrganizationName() {
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn("organization");
        assertEquals("organization", mSecurityController.getDeviceOwnerOrganizationName());
    }
}
