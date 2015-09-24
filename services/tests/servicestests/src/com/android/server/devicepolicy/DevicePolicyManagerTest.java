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

import android.Manifest.permission;
import android.app.Activity;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for DevicePolicyManager( and DevicePolicyManagerService).
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
    public ComponentName admin1;
    public ComponentName admin2;
    public ComponentName admin3;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();

        when(mContext.packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        dpms = new DevicePolicyManagerServiceTestable(mContext, dataDir);
        dpm = new DevicePolicyManagerTestable(mContext, dpms);

        admin1 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin1.class);
        admin2 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin2.class);
        admin3 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin3.class);

        setUpPackageManagerForAdmin(admin1);
        setUpPackageManagerForAdmin(admin2);
        setUpPackageManagerForAdmin(admin3);

        setUpApplicationInfo(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);
    }

    /**
     * Set up a mock result for {@link PackageManager#queryBroadcastReceivers}.  We'll return
     * the actual ResolveInfo for the admin component, but we need to mock PM so it'll return
     * it for user {@link DpmMockContext#CALLER_USER_HANDLE}.
     */
    private void setUpPackageManagerForAdmin(ComponentName admin) {
        final Intent resolveIntent = new Intent();
        resolveIntent.setComponent(admin);
        final List<ResolveInfo> realResolveInfo =
                mRealTestContext.getPackageManager().queryBroadcastReceivers(
                        resolveIntent,
                        PackageManager.GET_META_DATA);
        assertNotNull(realResolveInfo);
        assertEquals(1, realResolveInfo.size());

        // We need to rewrite the UID in the activity info.
        realResolveInfo.get(0).activityInfo.applicationInfo.uid = DpmMockContext.CALLER_UID;

        doReturn(realResolveInfo).when(mContext.packageManager).queryBroadcastReceivers(
                MockUtils.checkIntentComponent(admin),
                eq(PackageManager.GET_META_DATA
                        | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS),
                eq(DpmMockContext.CALLER_USER_HANDLE)
        );
    }

    /**
     * Set up a mock result for {@link IPackageManager#getApplicationInfo} for user
     * {@link DpmMockContext#CALLER_USER_HANDLE}.
     */
    private void setUpApplicationInfo(int enabledSetting) throws Exception {
        final ApplicationInfo ai = mRealTestContext.getPackageManager().getApplicationInfo(
                admin1.getPackageName(),
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS);

        ai.enabledSetting = enabledSetting;

        doReturn(ai).when(mContext.ipackageManager).getApplicationInfo(
                eq(admin1.getPackageName()),
                eq(PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS),
                eq(DpmMockContext.CALLER_USER_HANDLE));
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
        // 1. Failure cases.

        // Caller doesn't have MANAGE_DEVICE_ADMINS.
        try {
            dpm.setActiveAdmin(admin1, false);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }

        // Caller has MANAGE_DEVICE_ADMINS, but for different user.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        try {
            dpm.setActiveAdmin(admin1, false, DpmMockContext.CALLER_USER_HANDLE + 1);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setActiveAdmin}
     *   with replace=false and replace=true
     * {@link DevicePolicyManager#isAdminActive}
     * {@link DevicePolicyManager#isAdminActiveAsUser}
     * {@link DevicePolicyManager#getActiveAdmins}
     * {@link DevicePolicyManager#getActiveAdminsAsUser}
     */
    public void testSetActiveAdmin() throws Exception {
        // 1. Make sure the caller has proper permissions.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // 2. Call the API.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        // 3. Verify internal calls.

        // Check if the boradcast is sent.
        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));
        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));

        verify(mContext.ipackageManager, times(1)).setApplicationEnabledSetting(
                eq(admin1.getPackageName()),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                eq(PackageManager.DONT_KILL_APP),
                eq(DpmMockContext.CALLER_USER_HANDLE),
                anyString());

        // TODO Verify other calls too.

        // Make sure it's active admin1.
        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isAdminActive(admin2));
        assertFalse(dpm.isAdminActive(admin3));

        // But not admin1 for a different user.

        // For this to work, caller needs android.permission.INTERACT_ACROSS_USERS_FULL.
        // (Because we're checking a different user's status from CALLER_USER_HANDLE.)
        mContext.callerPermissions.add("android.permission.INTERACT_ACROSS_USERS_FULL");

        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE + 1));
        assertFalse(dpm.isAdminActiveAsUser(admin2, DpmMockContext.CALLER_USER_HANDLE + 1));

        mContext.callerPermissions.remove("android.permission.INTERACT_ACROSS_USERS_FULL");

        // Next, add one more admin.
        // Before doing so, update the application info, now it's enabled.
        setUpApplicationInfo(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        dpm.setActiveAdmin(admin2, /* replace =*/ false);

        // Now we have two admins.
        assertTrue(dpm.isAdminActive(admin1));
        assertTrue(dpm.isAdminActive(admin2));
        assertFalse(dpm.isAdminActive(admin3));

        // Admin2 was already enabled, so setApplicationEnabledSetting() shouldn't have called
        // again.  (times(1) because it was previously called for admin1)
        verify(mContext.ipackageManager, times(1)).setApplicationEnabledSetting(
                eq(admin1.getPackageName()),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                eq(PackageManager.DONT_KILL_APP),
                eq(DpmMockContext.CALLER_USER_HANDLE),
                anyString());

        // 4. Add the same admin1 again without replace, which should throw.
        try {
            dpm.setActiveAdmin(admin1, /* replace =*/ false);
            fail("Didn't throw");
        } catch (IllegalArgumentException expected) {
        }

        // 5. Add the same admin1 again with replace, which should succeed.
        dpm.setActiveAdmin(admin1, /* replace =*/ true);

        // TODO make sure it's replaced.

        // 6. Test getActiveAdmins()
        List<ComponentName> admins = dpm.getActiveAdmins();
        assertEquals(2, admins.size());
        assertEquals(admin1, admins.get(0));
        assertEquals(admin2, admins.get(1));

        // Another user has no admins.
        mContext.callerPermissions.add("android.permission.INTERACT_ACROSS_USERS_FULL");

        assertEquals(0, DpmTestUtils.getListSizeAllowingNull(
                dpm.getActiveAdminsAsUser(DpmMockContext.CALLER_USER_HANDLE + 1)));

        mContext.callerPermissions.remove("android.permission.INTERACT_ACROSS_USERS_FULL");
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setActiveAdmin}
     *   with replace=false
     */
    public void testSetActiveAdmin_twiceWithoutReplace() throws Exception {
        // 1. Make sure the caller has proper permissions.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.isAdminActive(admin1));

        // Add the same admin1 again without replace, which should throw.
        try {
            dpm.setActiveAdmin(admin1, /* replace =*/ false);
            fail("Didn't throw");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    public void testRemoveActiveAdmin_SecurityException() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin1));

        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Directly call the DPMS method with a different userid, which should fail.
        try {
            dpms.removeActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE + 1);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }

        // Try to remove active admin with a different caller userid should fail too, without
        // having MANAGE_DEVICE_ADMINS.
        mContext.callerPermissions.clear();

        mContext.binder.callingUid = 1234567;
        try {
            dpm.removeActiveAdmin(admin1);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    public void testRemoveActiveAdmin_fromDifferentUserWithMINTERACT_ACROSS_USERS_FULL() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin1.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Different user, but should work, because caller has proper permissions.
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);
        mContext.binder.callingUid = 1234567;
        dpm.removeActiveAdmin(admin1);

        assertTrue(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // TODO DO Still can't be removed in this case.
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    public void testRemoveActiveAdmin_sameUserNoMANAGE_DEVICE_ADMINS() {
        // Need MANAGE_DEVICE_ADMINS for setActiveAdmin.  We'll remove it later.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin1.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));

        // Remove.  No permissions, but same user, so it'll work.
        mContext.callerPermissions.clear();
        dpm.removeActiveAdmin(admin1);

        final ArgumentCaptor<BroadcastReceiver> brCap =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        // Is removing now, but not removed yet.
        assertTrue(dpm.isAdminActive(admin1));
        assertTrue(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        verify(mContext.spiedContext).sendOrderedBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE),
                isNull(String.class),
                brCap.capture(),
                eq(dpms.mHandler),
                eq(Activity.RESULT_OK),
                isNull(String.class),
                isNull(Bundle.class));

        brCap.getValue().onReceive(mContext, null);

        assertFalse(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Again broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));

        // TODO Check other internal calls.
    }
}


