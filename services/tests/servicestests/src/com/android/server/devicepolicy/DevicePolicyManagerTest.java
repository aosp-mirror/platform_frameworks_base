/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.devicepolicy;

import com.android.server.LocalServices;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DevicePolicyManager} and {@link DevicePolicyManagerService}.
 *
 m FrameworksServicesTests &&
 adb install \
 -r out/target/product/hammerhead/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.devicepolicy.DevicePolicyManagerTest \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 (mmma frameworks/base/services/tests/servicestests/ for non-ninja build)
 */
public class DevicePolicyManagerTest extends DpmTestBase {

    private DpmMockContext mContext;
    public DevicePolicyManager dpm;
    public DevicePolicyManagerServiceTestable dpms;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();

        when(mContext.packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        dpms = new DevicePolicyManagerServiceTestable(mContext, dataDir);
        dpm = new DevicePolicyManagerTestable(mContext, dpms);
    }

    public void testHasNoFeature() {
        when(mContext.packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(false);

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        new DevicePolicyManagerServiceTestable(mContext, dataDir);

        // If the device has no DPMS feature, it shouldn't register the local service.
        assertNull(LocalServices.getService(DevicePolicyManagerInternal.class));
    }

    /**
     * Caller doesn't have proper permissions.
     */
    public void testSetActiveAdmin_SecurityException() {
        final ComponentName admin = new ComponentName(mRealTestContext, DummyDeviceAdmin.class);

        // 1. Failure cases.

        // Caller doesn't have MANAGE_DEVICE_ADMINS.
        try {
            dpm.setActiveAdmin(admin, false);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }

        // Caller has MANAGE_DEVICE_ADMINS, but for different user.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        try {
            dpm.setActiveAdmin(admin, false, DpmMockContext.CALLER_USER_HANDLE + 1);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }
    }

    public void testSetActiveAdmin() {
        final ComponentName admin = new ComponentName(mRealTestContext, DummyDeviceAdmin.class);

        // 1. Prepare mock package manager (and other mocks)

        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Create ResolveInfo for the admin.
        final Intent resolveIntent = new Intent();
        resolveIntent.setComponent(admin);
        final List<ResolveInfo> realResolveInfo =
                mRealTestContext.getPackageManager().queryBroadcastReceivers(
                        resolveIntent,
                        PackageManager.GET_META_DATA
                            | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS);
        assertNotNull(realResolveInfo);
        assertEquals(1, realResolveInfo.size());

        // We need to rewrite the UID in the activity info.
        realResolveInfo.get(0).activityInfo.applicationInfo.uid = DpmMockContext.CALLER_UID;

        doReturn(realResolveInfo).when(mContext.packageManager).queryBroadcastReceivers(
                any(Intent.class), // TODO check the intent too.
                eq(PackageManager.GET_META_DATA
                        | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS),
                eq(DpmMockContext.CALLER_USER_HANDLE)
        );

        // 2. Everything is ready; call the method.
        dpm.setActiveAdmin(admin, false);

        // 3. Verify internal calls.

        // Check if the boradcast is sent.
        final ArgumentCaptor<Intent> intentCap = ArgumentCaptor.forClass(Intent.class);
        final ArgumentCaptor<UserHandle> uhCap = ArgumentCaptor.forClass(UserHandle.class);

        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                intentCap.capture(),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));

        // First call from saveSettingsLocked().
        assertEquals(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED,
                intentCap.getAllValues().get(0).getAction());

        // Second call from setActiveAdmin/sendAdminCommandLocked()
        assertEquals(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED,
                intentCap.getAllValues().get(1).getAction());

        // TODO Verify other calls too.
    }
}

