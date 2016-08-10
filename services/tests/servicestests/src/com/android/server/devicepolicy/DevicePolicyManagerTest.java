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

import android.Manifest.permission;
import android.app.Activity;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;
import android.util.Pair;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for DevicePolicyManager( and DevicePolicyManagerService).
 *
 m FrameworksServicesTests &&
 adb install \
   -r ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.devicepolicy.DevicePolicyManagerTest \
   -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 (mmma frameworks/base/services/tests/servicestests/ for non-ninja build)
 */
@SmallTest
public class DevicePolicyManagerTest extends DpmTestBase {
    private static final List<String> OWNER_SETUP_PERMISSIONS = Arrays.asList(
            permission.MANAGE_DEVICE_ADMINS, permission.MANAGE_PROFILE_AND_DEVICE_OWNERS,
            permission.MANAGE_USERS, permission.INTERACT_ACROSS_USERS_FULL);

    private DpmMockContext mContext;
    public DevicePolicyManager dpm;
    public DevicePolicyManagerServiceTestable dpms;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();

        when(mContext.packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);

        // By default, pretend all users are running and unlocked.
        when(mContext.userManager.isUserUnlocked(anyInt())).thenReturn(true);

        initializeDpms();

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin3, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(adminNoPerm, DpmMockContext.CALLER_UID);

        setUpUserManager();
    }

    private void initializeDpms() {
        // Need clearCallingIdentity() to pass permission checks.
        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(mContext, dataDir);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);

            dpm = new DevicePolicyManagerTestable(mContext, dpms);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }
    }

    private void setUpUserManager() {
        // Emulate UserManager.set/getApplicationRestriction().
        final Map<Pair<String, UserHandle>, Bundle> appRestrictions = new HashMap<>();

        // UM.setApplicationRestrictions() will save to appRestrictions.
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                String pkg = (String) invocation.getArguments()[0];
                Bundle bundle = (Bundle) invocation.getArguments()[1];
                UserHandle user = (UserHandle) invocation.getArguments()[2];

                appRestrictions.put(Pair.create(pkg, user), bundle);

                return null;
            }
        }).when(mContext.userManager).setApplicationRestrictions(
                anyString(), any(Bundle.class), any(UserHandle.class));

        // UM.getApplicationRestrictions() will read from appRestrictions.
        doAnswer(new Answer<Bundle>() {
            @Override
            public Bundle answer(InvocationOnMock invocation) throws Throwable {
                String pkg = (String) invocation.getArguments()[0];
                UserHandle user = (UserHandle) invocation.getArguments()[1];

                return appRestrictions.get(Pair.create(pkg, user));
            }
        }).when(mContext.userManager).getApplicationRestrictions(
                anyString(), any(UserHandle.class));

        // Add the first secondary user.
        mContext.addUser(DpmMockContext.CALLER_USER_HANDLE, 0);
    }

    private void setAsProfileOwner(ComponentName admin) {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // PO needs to be an DA.
        dpm.setActiveAdmin(admin, /* replace =*/ false);

        // Fire!
        assertTrue(dpm.setProfileOwner(admin, "owner-name", DpmMockContext.CALLER_USER_HANDLE));

        // Check
        assertEquals(admin, dpm.getProfileOwnerAsUser(DpmMockContext.CALLER_USER_HANDLE));
    }

    public void testHasNoFeature() throws Exception {
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
     * with replace=false and replace=true
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
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

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

    public void testSetActiveAdmin_multiUsers() throws Exception {

        final int ANOTHER_USER_ID = 100;
        final int ANOTHER_ADMIN_UID = UserHandle.getUid(ANOTHER_USER_ID, 20456);

        mMockContext.addUser(ANOTHER_USER_ID, 0); // Add one more user.

        // Set up pacakge manager for the other user.
        setUpPackageManagerForAdmin(admin2, ANOTHER_ADMIN_UID);

        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        mMockContext.binder.callingUid = ANOTHER_ADMIN_UID;
        dpm.setActiveAdmin(admin2, /* replace =*/ false);


        mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isAdminActive(admin2));

        mMockContext.binder.callingUid = ANOTHER_ADMIN_UID;
        assertFalse(dpm.isAdminActive(admin1));
        assertTrue(dpm.isAdminActive(admin2));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setActiveAdmin}
     * with replace=false
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
     * {@link DevicePolicyManager#setActiveAdmin} when the admin isn't protected with
     * BIND_DEVICE_ADMIN.
     */
    public void testSetActiveAdmin_permissionCheck() throws Exception {
        // 1. Make sure the caller has proper permissions.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        try {
            dpm.setActiveAdmin(adminNoPerm, /* replace =*/ false);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(permission.BIND_DEVICE_ADMIN));
        }
        assertFalse(dpm.isAdminActive(adminNoPerm));

        // Change the target API level to MNC.  Now it can be set as DA.
        setUpPackageManagerForAdmin(adminNoPerm, DpmMockContext.CALLER_UID, null,
                VERSION_CODES.M);
        dpm.setActiveAdmin(adminNoPerm, /* replace =*/ false);
        assertTrue(dpm.isAdminActive(adminNoPerm));

        // TODO Test the "load from the file" case where DA will still be loaded even without
        // BIND_DEVICE_ADMIN and target API is N.
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

        // Change the caller, and call into DPMS directly with a different user-id.

        mContext.binder.callingUid = 1234567;
        try {
            dpms.removeActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * {@link DevicePolicyManager#removeActiveAdmin} should fail with the user is not unlocked
     * (because we can't send the remove broadcast).
     */
    public void testRemoveActiveAdmin_userNotRunningOrLocked() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Add admin.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin1));

        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // 1. User not unlocked.
        when(mContext.userManager.isUserUnlocked(eq(DpmMockContext.CALLER_USER_HANDLE)))
                .thenReturn(false);
        try {
            dpm.removeActiveAdmin(admin1);
            fail("Didn't throw IllegalStateException");
        } catch (IllegalStateException expected) {
            MoreAsserts.assertContainsRegex(
                    "User must be running and unlocked", expected.getMessage());
        }

        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // 2. User unlocked.
        when(mContext.userManager.isUserUnlocked(eq(DpmMockContext.CALLER_USER_HANDLE)))
                .thenReturn(true);

        dpm.removeActiveAdmin(admin1);
        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    public void testRemoveActiveAdmin_fromDifferentUserWithINTERACT_ACROSS_USERS_FULL() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin1.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Different user, but should work, because caller has proper permissions.
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Change the caller, and call into DPMS directly with a different user-id.
        mContext.binder.callingUid = 1234567;

        dpms.removeActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE);
        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));

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

        verify(mContext.spiedContext).sendOrderedBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE),
                isNull(String.class),
                any(BroadcastReceiver.class),
                eq(dpms.mHandler),
                eq(Activity.RESULT_OK),
                isNull(String.class),
                isNull(Bundle.class));

        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Again broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));

        // TODO Check other internal calls.
    }

    /**
     * Test for: {@link DevicePolicyManager#setDeviceOwner} DO on system user installs successfully.
     */
    public void testSetDeviceOwner() throws Exception {
        setDeviceOwner();

        // Try to set a profile owner on the same user, which should fail.
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin2, /* refreshing= */ true, UserHandle.USER_SYSTEM);
        try {
            dpm.setProfileOwner(admin2, "owner-name", UserHandle.USER_SYSTEM);
            fail("IllegalStateException not thrown");
        } catch (IllegalStateException expected) {
            assertTrue("Message was: " + expected.getMessage(),
                    expected.getMessage().contains("already has a device owner"));
        }

        // DO admin can't be deactivated.
        dpm.removeActiveAdmin(admin1);
        assertTrue(dpm.isAdminActive(admin1));

        // TODO Test getDeviceOwnerName() too. To do so, we need to change
        // DPMS.getApplicationLabel() because Context.createPackageContextAsUser() is not mockable.
    }

    private void setDeviceOwner() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // In this test, change the caller user to "system".
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Check various get APIs.
        checkGetDeviceOwnerInfoApi(dpm, /* hasDeviceOwner =*/ false);

        // DO needs to be an DA.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        // Fire!
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name"));

        // getDeviceOwnerComponent should return the admin1 component.
        assertEquals(admin1, dpm.getDeviceOwnerComponentOnCallingUser());
        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());

        // Check various get APIs.
        checkGetDeviceOwnerInfoApi(dpm, /* hasDeviceOwner =*/ true);

        // getDeviceOwnerComponent should *NOT* return the admin1 component for other users.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());
        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Verify internal calls.
        verify(mContext.iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        // TODO We should check if the caller has called clearCallerIdentity().
        verify(mContext.ibackupManager, times(1)).setBackupServiceActive(
                eq(UserHandle.USER_SYSTEM), eq(false));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));

        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());
    }

    private void checkGetDeviceOwnerInfoApi(DevicePolicyManager dpm, boolean hasDeviceOwner) {
        final int origCallingUser = mContext.binder.callingUid;
        final List origPermissions = new ArrayList(mContext.callerPermissions);
        mContext.callerPermissions.clear();

        mContext.callerPermissions.add(permission.MANAGE_USERS);

        mContext.binder.callingUid = Process.SYSTEM_UID;

        // TODO Test getDeviceOwnerName() too.  To do so, we need to change
        // DPMS.getApplicationLabel() because Context.createPackageContextAsUser() is not mockable.
        if (hasDeviceOwner) {
            assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertTrue(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnCallingUser());

            assertTrue(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_SYSTEM, dpm.getDeviceOwnerUserId());
        } else {
            assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());

            assertFalse(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_NULL, dpm.getDeviceOwnerUserId());
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        if (hasDeviceOwner) {
            assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertTrue(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnCallingUser());

            assertTrue(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_SYSTEM, dpm.getDeviceOwnerUserId());
        } else {
            assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());

            assertFalse(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_NULL, dpm.getDeviceOwnerUserId());
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        // Still with MANAGE_USERS.
        assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
        assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());

        if (hasDeviceOwner) {
            assertTrue(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_SYSTEM, dpm.getDeviceOwnerUserId());
        } else {
            assertFalse(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_NULL, dpm.getDeviceOwnerUserId());
        }

        mContext.binder.callingUid = Process.SYSTEM_UID;
        mContext.callerPermissions.remove(permission.MANAGE_USERS);
        // System can still call "OnAnyUser" without MANAGE_USERS.
        if (hasDeviceOwner) {
            assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertTrue(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnCallingUser());

            assertTrue(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_SYSTEM, dpm.getDeviceOwnerUserId());
        } else {
            assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());

            assertFalse(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnAnyUser());
            assertEquals(UserHandle.USER_NULL, dpm.getDeviceOwnerUserId());
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        // Still no MANAGE_USERS.
        if (hasDeviceOwner) {
            assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertTrue(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(admin1, dpm.getDeviceOwnerComponentOnCallingUser());
        } else {
            assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
            assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());
        }

        try {
            dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName());
            fail();
        } catch (SecurityException expected) {
        }
        try {
            dpm.getDeviceOwnerComponentOnAnyUser();
            fail();
        } catch (SecurityException expected) {
        }
        try {
            dpm.getDeviceOwnerUserId();
            fail();
        } catch (SecurityException expected) {
        }
        try {
            dpm.getDeviceOwnerNameOnAnyUser();
            fail();
        } catch (SecurityException expected) {
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        // Still no MANAGE_USERS.
        assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
        assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());

        try {
            dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName());
            fail();
        } catch (SecurityException expected) {
        }
        try {
            dpm.getDeviceOwnerComponentOnAnyUser();
            fail();
        } catch (SecurityException expected) {
        }
        try {
            dpm.getDeviceOwnerUserId();
            fail();
        } catch (SecurityException expected) {
        }
        try {
            dpm.getDeviceOwnerNameOnAnyUser();
            fail();
        } catch (SecurityException expected) {
        }

        // Restore.
        mContext.binder.callingUid = origCallingUser;
        mContext.callerPermissions.addAll(origPermissions);
    }


    /**
     * Test for: {@link DevicePolicyManager#setDeviceOwner} Package doesn't exist.
     */
    public void testSetDeviceOwner_noSuchPackage() {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Call from a process on the system user.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        try {
            dpm.setDeviceOwner(new ComponentName("a.b.c", ".def"));
            fail("Didn't throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue("Message was: " + expected.getMessage(),
                    expected.getMessage().contains("Invalid component"));
        }
    }

    public void testSetDeviceOwner_failures() throws Exception {
        // TODO Test more failure cases.  Basically test all chacks in enforceCanSetDeviceOwner().
    }

    public void testClearDeviceOwner() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Set admin1 as a DA to the secondary user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        // Set admin 1 as the DO to the system user.

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name"));

        // Verify internal calls.
        verify(mContext.iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);

        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, UserHandle.USER_SYSTEM));

        // Set up other mocks.
        when(mContext.userManager.getUserRestrictions()).thenReturn(new Bundle());

        // Now call clear.
        doReturn(DpmMockContext.CALLER_SYSTEM_USER_UID).when(mContext.packageManager).getPackageUidAsUser(
                eq(admin1.getPackageName()),
                anyInt());

        // But first pretend the user is locked.  Then it should fail.
        when(mContext.userManager.isUserUnlocked(anyInt())).thenReturn(false);
        try {
            dpm.clearDeviceOwnerApp(admin1.getPackageName());
            fail("Didn't throw IllegalStateException");
        } catch (IllegalStateException expected) {
            MoreAsserts.assertContainsRegex(
                    "User must be running and unlocked", expected.getMessage());
        }

        when(mContext.userManager.isUserUnlocked(anyInt())).thenReturn(true);
        reset(mContext.userManagerInternal);
        dpm.clearDeviceOwnerApp(admin1.getPackageName());

        // Now DO shouldn't be set.
        assertNull(dpm.getDeviceOwnerComponentOnAnyUser());

        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions()
        );

        assertFalse(dpm.isAdminActiveAsUser(admin1, UserHandle.USER_SYSTEM));
        // TODO Check other calls.
    }

    public void testClearDeviceOwner_fromDifferentUser() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Set admin1 as a DA to the secondary user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        // Set admin 1 as the DO to the system user.

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name"));

        // Verify internal calls.
        verify(mContext.iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());

        // Now call clear from the secondary user, which should throw.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Now call clear.
        doReturn(DpmMockContext.CALLER_UID).when(mContext.packageManager).getPackageUidAsUser(
                eq(admin1.getPackageName()),
                anyInt());
        try {
            dpm.clearDeviceOwnerApp(admin1.getPackageName());
            fail("Didn't throw");
        } catch (SecurityException e) {
            assertEquals("clearDeviceOwner can only be called by the device owner", e.getMessage());
        }

        // DO shouldn't be removed.
        assertTrue(dpm.isDeviceManaged());
    }

    public void testSetProfileOwner() throws Exception {
        setAsProfileOwner(admin1);

        // PO admin can't be deactivated.
        dpm.removeActiveAdmin(admin1);
        assertTrue(dpm.isAdminActive(admin1));

        // Try setting DO on the same user, which should fail.
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        dpm.setActiveAdmin(admin2, /* refreshing= */ true, DpmMockContext.CALLER_USER_HANDLE);
        try {
            dpm.setDeviceOwner(admin2, "owner-name", DpmMockContext.CALLER_USER_HANDLE);
            fail("IllegalStateException not thrown");
        } catch (IllegalStateException expected) {
            assertTrue("Message was: " + expected.getMessage(),
                    expected.getMessage().contains("already has a profile owner"));
        }
    }

    public void testClearProfileOwner() throws Exception {
        setAsProfileOwner(admin1);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        assertTrue(dpm.isProfileOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // First try when the user is locked, which should fail.
        when(mContext.userManager.isUserUnlocked(anyInt()))
                .thenReturn(false);
        try {
            dpm.clearProfileOwner(admin1);
            fail("Didn't throw IllegalStateException");
        } catch (IllegalStateException expected) {
            MoreAsserts.assertContainsRegex(
                    "User must be running and unlocked", expected.getMessage());
        }
        // Clear, really.
        when(mContext.userManager.isUserUnlocked(anyInt()))
                .thenReturn(true);
        dpm.clearProfileOwner(admin1);

        // Check
        assertFalse(dpm.isProfileOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));
    }

    public void testSetProfileOwner_failures() throws Exception {
        // TODO Test more failure cases.  Basically test all chacks in enforceCanSetProfileOwner().
    }

    public void testGetDeviceOwnerAdminLocked() throws Exception {
        checkDeviceOwnerWithMultipleDeviceAdmins();
    }

    private void checkDeviceOwnerWithMultipleDeviceAdmins() throws Exception {
        // In ths test, we use 3 users (system + 2 secondary users), set some device admins to them,
        // set admin2 on CALLER_USER_HANDLE as DO, then call getDeviceOwnerAdminLocked() to
        // make sure it gets the right component from the right user.

        final int ANOTHER_USER_ID = 100;
        final int ANOTHER_ADMIN_UID = UserHandle.getUid(ANOTHER_USER_ID, 456);

        mMockContext.addUser(ANOTHER_USER_ID, 0); // Add one more user.

        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);

        // Make sure the admin packge is installed to each user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin3, DpmMockContext.CALLER_SYSTEM_USER_UID);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);

        setUpPackageManagerForAdmin(admin2, ANOTHER_ADMIN_UID);


        // Set active admins to the users.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        dpm.setActiveAdmin(admin3, /* replace =*/ false);

        dpm.setActiveAdmin(admin1, /* replace =*/ false, DpmMockContext.CALLER_USER_HANDLE);
        dpm.setActiveAdmin(admin2, /* replace =*/ false, DpmMockContext.CALLER_USER_HANDLE);

        dpm.setActiveAdmin(admin2, /* replace =*/ false, ANOTHER_USER_ID);

        // Set DO on the first non-system user.
        mContext.setUserRunning(DpmMockContext.CALLER_USER_HANDLE, true);
        assertTrue(dpm.setDeviceOwner(admin2, "owner-name", DpmMockContext.CALLER_USER_HANDLE));

        assertEquals(admin2, dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false));

        // Then check getDeviceOwnerAdminLocked().
        assertEquals(admin2, dpms.getDeviceOwnerAdminLocked().info.getComponent());
        assertEquals(DpmMockContext.CALLER_UID, dpms.getDeviceOwnerAdminLocked().getUid());
    }

    /**
     * This essentially tests
     * {@code DevicePolicyManagerService.findOwnerComponentIfNecessaryLocked()}. (which is
     * private.)
     *
     * We didn't use to persist the DO component class name, but now we do, and the above method
     * finds the right component from a package name upon migration.
     */
    public void testDeviceOwnerMigration() throws Exception {
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        checkDeviceOwnerWithMultipleDeviceAdmins();

        // Overwrite the device owner setting and clears the clas name.
        dpms.mOwners.setDeviceOwner(
                new ComponentName(admin2.getPackageName(), ""),
                "owner-name", DpmMockContext.CALLER_USER_HANDLE);
        dpms.mOwners.writeDeviceOwner();

        // Make sure the DO component name doesn't have a class name.
        assertEquals("", dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false).getClassName());

        // Then create a new DPMS to have it load the settings from files.
        when(mContext.userManager.getUserRestrictions(any(UserHandle.class)))
                .thenReturn(new Bundle());
        initializeDpms();

        // Now the DO component name is a full name.
        // *BUT* because both admin1 and admin2 belong to the same package, we think admin1 is the
        // DO.
        assertEquals(admin1, dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false));
    }

    public void testSetGetApplicationRestriction() {
        setAsProfileOwner(admin1);

        {
            Bundle rest = new Bundle();
            rest.putString("KEY_STRING", "Foo1");
            dpm.setApplicationRestrictions(admin1, "pkg1", rest);
        }

        {
            Bundle rest = new Bundle();
            rest.putString("KEY_STRING", "Foo2");
            dpm.setApplicationRestrictions(admin1, "pkg2", rest);
        }

        {
            Bundle returned = dpm.getApplicationRestrictions(admin1, "pkg1");
            assertNotNull(returned);
            assertEquals(returned.size(), 1);
            assertEquals(returned.get("KEY_STRING"), "Foo1");
        }

        {
            Bundle returned = dpm.getApplicationRestrictions(admin1, "pkg2");
            assertNotNull(returned);
            assertEquals(returned.size(), 1);
            assertEquals(returned.get("KEY_STRING"), "Foo2");
        }

        dpm.setApplicationRestrictions(admin1, "pkg2", new Bundle());
        assertEquals(0, dpm.getApplicationRestrictions(admin1, "pkg2").size());
    }

    public void testApplicationRestrictionsManagingApp() throws Exception {
        setAsProfileOwner(admin1);

        final String nonExistAppRestrictionsManagerPackage = "com.google.app.restrictions.manager2";
        final String appRestrictionsManagerPackage = "com.google.app.restrictions.manager";
        final int appRestrictionsManagerAppId = 20987;
        final int appRestrictionsManagerUid = UserHandle.getUid(
                DpmMockContext.CALLER_USER_HANDLE, appRestrictionsManagerAppId);
        doReturn(appRestrictionsManagerUid).when(mContext.packageManager).getPackageUidAsUser(
                eq(appRestrictionsManagerPackage),
                eq(DpmMockContext.CALLER_USER_HANDLE));
        mContext.binder.callingUid = appRestrictionsManagerUid;

        final PackageInfo pi = new PackageInfo();
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;
        doReturn(pi).when(mContext.ipackageManager).getPackageInfo(
                eq(appRestrictionsManagerPackage),
                anyInt(),
                eq(DpmMockContext.CALLER_USER_HANDLE));

        // appRestrictionsManager package shouldn't be able to manage restrictions as the PO hasn't
        // delegated that permission yet.
        assertFalse(dpm.isCallerApplicationRestrictionsManagingPackage());
        Bundle rest = new Bundle();
        rest.putString("KEY_STRING", "Foo1");
        try {
            dpm.setApplicationRestrictions(null, "pkg1", rest);
            fail("Didn't throw expected SecurityException");
        } catch (SecurityException expected) {
            MoreAsserts.assertContainsRegex(
                    "caller cannot manage application restrictions", expected.getMessage());
        }
        try {
            dpm.getApplicationRestrictions(null, "pkg1");
            fail("Didn't throw expected SecurityException");
        } catch (SecurityException expected) {
            MoreAsserts.assertContainsRegex(
                    "caller cannot manage application restrictions", expected.getMessage());
        }

        // Check via the profile owner that no restrictions were set.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertEquals(0, dpm.getApplicationRestrictions(admin1, "pkg1").size());

        // Check the API does not allow setting a non-existent package
        try {
            dpm.setApplicationRestrictionsManagingPackage(admin1,
                    nonExistAppRestrictionsManagerPackage);
            fail("Non-existent app set as app restriction manager.");
        } catch (PackageManager.NameNotFoundException expected) {
            MoreAsserts.assertContainsRegex(
                    nonExistAppRestrictionsManagerPackage, expected.getMessage());
        }

        // Let appRestrictionsManagerPackage manage app restrictions
        dpm.setApplicationRestrictionsManagingPackage(admin1, appRestrictionsManagerPackage);
        assertEquals(appRestrictionsManagerPackage,
                dpm.getApplicationRestrictionsManagingPackage(admin1));

        // Now that package should be able to set and retrieve app restrictions.
        mContext.binder.callingUid = appRestrictionsManagerUid;
        assertTrue(dpm.isCallerApplicationRestrictionsManagingPackage());
        dpm.setApplicationRestrictions(null, "pkg1", rest);
        Bundle returned = dpm.getApplicationRestrictions(null, "pkg1");
        assertEquals(1, returned.size(), 1);
        assertEquals("Foo1", returned.get("KEY_STRING"));

        // The same app running on a separate user shouldn't be able to manage app restrictions.
        mContext.binder.callingUid = UserHandle.getUid(
                UserHandle.USER_SYSTEM, appRestrictionsManagerAppId);
        assertFalse(dpm.isCallerApplicationRestrictionsManagingPackage());
        try {
            dpm.setApplicationRestrictions(null, "pkg1", rest);
            fail("Didn't throw expected SecurityException");
        } catch (SecurityException expected) {
            MoreAsserts.assertContainsRegex(
                    "caller cannot manage application restrictions", expected.getMessage());
        }

        // The DPM is still able to manage app restrictions, even if it allowed another app to do it
        // too.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertEquals(returned, dpm.getApplicationRestrictions(admin1, "pkg1"));
        dpm.setApplicationRestrictions(admin1, "pkg1", null);
        assertEquals(0, dpm.getApplicationRestrictions(admin1, "pkg1").size());

        // Removing the ability for the package to manage app restrictions.
        dpm.setApplicationRestrictionsManagingPackage(admin1, null);
        assertNull(dpm.getApplicationRestrictionsManagingPackage(admin1));
        mContext.binder.callingUid = appRestrictionsManagerUid;
        assertFalse(dpm.isCallerApplicationRestrictionsManagingPackage());
        try {
            dpm.setApplicationRestrictions(null, "pkg1", null);
            fail("Didn't throw expected SecurityException");
        } catch (SecurityException expected) {
            MoreAsserts.assertContainsRegex(
                    "caller cannot manage application restrictions", expected.getMessage());
        }
    }

    public void testSetUserRestriction_asDo() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // First, set DO.

        // Call from a process on the system user.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Call.
        dpm.setActiveAdmin(admin1, /* replace =*/ false, UserHandle.USER_SYSTEM);
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name",
                UserHandle.USER_SYSTEM));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpm.getUserRestrictions(admin1)
        );

        reset(mContext.userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER)
        );
        reset(mContext.userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER)
        );
        reset(mContext.userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_OUTGOING_CALLS),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_OUTGOING_CALLS),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                MockUtils.checkUserRestrictions()
        );
        reset(mContext.userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions()
        );
        reset(mContext.userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpm.getUserRestrictions(admin1)
        );

        // DISALLOW_ADJUST_VOLUME and DISALLOW_UNMUTE_MICROPHONE are PO restrictions, but when
        // DO sets them, the scope is global.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);
        reset(mContext.userManagerInternal);
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE)
        );
        reset(mContext.userManagerInternal);

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);
        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);


        // More tests.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER)
        );
        reset(mContext.userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_FUN);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_ADD_USER)
        );
        reset(mContext.userManagerInternal);

        dpm.setCameraDisabled(admin1, true);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                // DISALLOW_CAMERA will be applied to both local and global.
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_CAMERA),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_CAMERA, UserManager.DISALLOW_ADD_USER)
        );
        reset(mContext.userManagerInternal);

        // Set up another DA and let it disable camera.  Now DISALLOW_CAMERA will only be applied
        // locally.
        dpm.setCameraDisabled(admin1, false);
        reset(mContext.userManagerInternal);

        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin2, /* replace =*/ false, UserHandle.USER_SYSTEM);
        dpm.setCameraDisabled(admin2, true);

        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                // DISALLOW_CAMERA will be applied to both local and global.
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_CAMERA),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_ADD_USER)
        );
        reset(mContext.userManagerInternal);
        // TODO Make sure restrictions are written to the file.
    }

    public void testSetUserRestriction_asPo() {
        setAsProfileOwner(admin1);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                        .ensureUserRestrictions()
        );

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
                isNull(Bundle.class)
        );
        reset(mContext.userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_OUTGOING_CALLS),
                isNull(Bundle.class)
        );
        reset(mContext.userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                        .ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                isNull(Bundle.class)
        );
        reset(mContext.userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                        .ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                isNull(Bundle.class)
        );
        reset(mContext.userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                        .ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpm.getUserRestrictions(admin1)
        );

        // DISALLOW_ADJUST_VOLUME and DISALLOW_UNMUTE_MICROPHONE can be set by PO too, even
        // though when DO sets them they'll be applied globally.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);
        reset(mContext.userManagerInternal);
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE),
                isNull(Bundle.class)
        );
        reset(mContext.userManagerInternal);

        dpm.setCameraDisabled(admin1, true);
        verify(mContext.userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_CAMERA,
                        UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE),
                isNull(Bundle.class)
        );
        reset(mContext.userManagerInternal);

        // TODO Make sure restrictions are written to the file.
    }

    public void testGetMacAddress() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // In this test, change the caller user to "system".
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Test 1. Caller doesn't have DO or DA.
        try {
            dpm.getWifiMacAddress(admin1);
            fail();
        } catch (SecurityException e) {
            MoreAsserts.assertContainsRegex("No active admin", e.getMessage());
        }

        // DO needs to be an DA.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.isAdminActive(admin1));

        // Test 2. Caller has DA, but not DO.
        try {
            dpm.getWifiMacAddress(admin1);
            fail();
        } catch (SecurityException e) {
            MoreAsserts.assertContainsRegex("does not own the device", e.getMessage());
        }

        // Test 3. Caller has PO, but not DO.
        assertTrue(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM));
        try {
            dpm.getWifiMacAddress(admin1);
            fail();
        } catch (SecurityException e) {
            MoreAsserts.assertContainsRegex("does not own the device", e.getMessage());
        }

        // Remove PO.
        dpm.clearProfileOwner(admin1);
        dpm.setActiveAdmin(admin1, false);
        // Test 4, Caller is DO now.
        assertTrue(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM));

        // 4-1.  But no WifiInfo.
        assertNull(dpm.getWifiMacAddress(admin1));

        // 4-2.  Returns WifiInfo, but with the default MAC.
        when(mContext.wifiManager.getConnectionInfo()).thenReturn(new WifiInfo());
        assertNull(dpm.getWifiMacAddress(admin1));

        // 4-3. With a real MAC address.
        final WifiInfo wi = new WifiInfo();
        wi.setMacAddress("11:22:33:44:55:66");
        when(mContext.wifiManager.getConnectionInfo()).thenReturn(wi);
        assertEquals("11:22:33:44:55:66", dpm.getWifiMacAddress(admin1));
    }

    public void testReboot() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // In this test, change the caller user to "system".
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Set admin1 as DA.
        dpm.setActiveAdmin(admin1, false);
        assertTrue(dpm.isAdminActive(admin1));
        try {
            dpm.reboot(admin1);
            fail("DA calls DPM.reboot(), did not throw expected SecurityException");
        } catch (SecurityException expected) {
            MoreAsserts.assertContainsRegex("does not own the device", expected.getMessage());
        }

        // Set admin1 as PO.
        assertTrue(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM));
        try {
            dpm.reboot(admin1);
            fail("PO calls DPM.reboot(), did not throw expected SecurityException");
        } catch (SecurityException expected) {
            MoreAsserts.assertContainsRegex("does not own the device", expected.getMessage());
        }

        // Remove PO and add DO.
        dpm.clearProfileOwner(admin1);
        dpm.setActiveAdmin(admin1, false);
        assertTrue(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM));

        // admin1 is DO.
        // Set current call state of device to ringing.
        when(mContext.telephonyManager.getCallState())
                .thenReturn(TelephonyManager.CALL_STATE_RINGING);
        try {
            dpm.reboot(admin1);
            fail("DPM.reboot() called when receiveing a call, should thrown IllegalStateException");
        } catch (IllegalStateException expected) {
            MoreAsserts.assertContainsRegex("ongoing call on the device", expected.getMessage());
        }

        // Set current call state of device to dialing/active.
        when(mContext.telephonyManager.getCallState())
                .thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);
        try {
            dpm.reboot(admin1);
            fail("DPM.reboot() called when dialing, should thrown IllegalStateException");
        } catch (IllegalStateException expected) {
            MoreAsserts.assertContainsRegex("ongoing call on the device", expected.getMessage());
        }

        // Set current call state of device to idle.
        when(mContext.telephonyManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_IDLE);
        dpm.reboot(admin1);
    }

    public void testSetGetSupportText() {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        dpm.setActiveAdmin(admin1, true);
        dpm.setActiveAdmin(admin2, true);
        mContext.callerPermissions.remove(permission.MANAGE_DEVICE_ADMINS);

        // Null default support messages.
        {
            assertNull(dpm.getLongSupportMessage(admin1));
            assertNull(dpm.getShortSupportMessage(admin1));
            mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
            assertNull(dpm.getShortSupportMessageForUser(admin1,
                    DpmMockContext.CALLER_USER_HANDLE));
            assertNull(dpm.getLongSupportMessageForUser(admin1,
                    DpmMockContext.CALLER_USER_HANDLE));
            mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;
        }

        // Only system can call the per user versions.
        {
            try {
                dpm.getShortSupportMessageForUser(admin1,
                        DpmMockContext.CALLER_USER_HANDLE);
                fail("Only system should be able to call getXXXForUser versions");
            } catch (SecurityException expected) {
                MoreAsserts.assertContainsRegex("message for user", expected.getMessage());
            }
            try {
                dpm.getLongSupportMessageForUser(admin1,
                        DpmMockContext.CALLER_USER_HANDLE);
                fail("Only system should be able to call getXXXForUser versions");
            } catch (SecurityException expected) {
                MoreAsserts.assertContainsRegex("message for user", expected.getMessage());
            }
        }

        // Can't set message for admin in another uid.
        {
            mContext.binder.callingUid = DpmMockContext.CALLER_UID + 1;
            try {
                dpm.setShortSupportMessage(admin1, "Some text");
                fail("Admins should only be able to change their own support text.");
            } catch (SecurityException expected) {
                MoreAsserts.assertContainsRegex("is not owned by uid", expected.getMessage());
            }
            mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        }

        // Set/Get short returns what it sets and other admins text isn't changed.
        {
            final String supportText = "Some text to test with.";
            dpm.setShortSupportMessage(admin1, supportText);
            assertEquals(supportText, dpm.getShortSupportMessage(admin1));
            assertNull(dpm.getLongSupportMessage(admin1));
            assertNull(dpm.getShortSupportMessage(admin2));

            mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
            assertEquals(supportText, dpm.getShortSupportMessageForUser(admin1,
                    DpmMockContext.CALLER_USER_HANDLE));
            assertNull(dpm.getShortSupportMessageForUser(admin2,
                    DpmMockContext.CALLER_USER_HANDLE));
            assertNull(dpm.getLongSupportMessageForUser(admin1,
                    DpmMockContext.CALLER_USER_HANDLE));
            mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;

            dpm.setShortSupportMessage(admin1, null);
            assertNull(dpm.getShortSupportMessage(admin1));
        }

        // Set/Get long returns what it sets and other admins text isn't changed.
        {
            final String supportText = "Some text to test with.\nWith more text.";
            dpm.setLongSupportMessage(admin1, supportText);
            assertEquals(supportText, dpm.getLongSupportMessage(admin1));
            assertNull(dpm.getShortSupportMessage(admin1));
            assertNull(dpm.getLongSupportMessage(admin2));

            mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
            assertEquals(supportText, dpm.getLongSupportMessageForUser(admin1,
                    DpmMockContext.CALLER_USER_HANDLE));
            assertNull(dpm.getLongSupportMessageForUser(admin2,
                    DpmMockContext.CALLER_USER_HANDLE));
            assertNull(dpm.getShortSupportMessageForUser(admin1,
                    DpmMockContext.CALLER_USER_HANDLE));
            mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;

            dpm.setLongSupportMessage(admin1, null);
            assertNull(dpm.getLongSupportMessage(admin1));
        }
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setAffiliationIds}
     * {@link DevicePolicyManager#isAffiliatedUser}
     */
    public void testUserAffiliation() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Check that the system user is unaffiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        assertFalse(dpm.isAffiliatedUser());

        // Set a device owner on the system user. Check that the system user becomes affiliated.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name"));
        assertTrue(dpm.isAffiliatedUser());

        // Install a profile owner whose package name matches the device owner on a test user. Check
        // that the test user is unaffiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin2);
        assertFalse(dpm.isAffiliatedUser());

        // Have the profile owner specify a set of affiliation ids. Check that the test user remains
        // unaffiliated.
        final Set<String> userAffiliationIds = new ArraySet<>();
        userAffiliationIds.add("red");
        userAffiliationIds.add("green");
        userAffiliationIds.add("blue");
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        assertFalse(dpm.isAffiliatedUser());

        // Have the device owner specify a set of affiliation ids that do not intersect with those
        // specified by the profile owner. Check that the test user remains unaffiliated.
        final Set<String> deviceAffiliationIds = new ArraySet<>();
        deviceAffiliationIds.add("cyan");
        deviceAffiliationIds.add("yellow");
        deviceAffiliationIds.add("magenta");
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        dpm.setAffiliationIds(admin1, deviceAffiliationIds);
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertFalse(dpm.isAffiliatedUser());

        // Have the profile owner specify a set of affiliation ids that intersect with those
        // specified by the device owner. Check that the test user becomes affiliated.
        userAffiliationIds.add("yellow");
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        assertTrue(dpm.isAffiliatedUser());

        // Change the profile owner to one whose package name does not match the device owner. Check
        // that the test user is not affiliated anymore.
        dpm.clearProfileOwner(admin2);
        final ComponentName admin = new ComponentName("test", "test");

        setUpPackageManagerForFakeAdmin(admin, DpmMockContext.CALLER_UID,
                /* enabledSetting =*/ PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                /* appTargetSdk = */ null, admin2);

        dpm.setActiveAdmin(admin, /* refreshing =*/ true, DpmMockContext.CALLER_USER_HANDLE);
        assertTrue(dpm.setProfileOwner(admin, "owner-name", DpmMockContext.CALLER_USER_HANDLE));
        assertFalse(dpm.isAffiliatedUser());

        // Check that the system user remains affiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        assertTrue(dpm.isAffiliatedUser());
    }

    public void testGetUserProvisioningState_defaultResult() {
        assertEquals(DevicePolicyManager.STATE_USER_UNMANAGED, dpm.getUserProvisioningState());
    }

    public void testSetUserProvisioningState_permission() throws Exception {
        setupProfileOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_unprivileged() throws Exception {
        setupProfileOwner();
        try {
            dpm.setUserProvisioningState(DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                    DpmMockContext.CALLER_USER_HANDLE);
            fail("Expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    public void testSetUserProvisioningState_noManagement() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        try {
            dpm.setUserProvisioningState(DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                    DpmMockContext.CALLER_USER_HANDLE);
            fail("IllegalStateException expected");
        } catch (IllegalStateException e) {
            MoreAsserts.assertContainsRegex("change provisioning state unless a .* owner is set",
                    e.getMessage());
        }
        assertEquals(DevicePolicyManager.STATE_USER_UNMANAGED, dpm.getUserProvisioningState());
    }

    public void testSetUserProvisioningState_deviceOwnerFromSetupWizard() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_deviceOwnerFromSetupWizardAlternative()
            throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_deviceOwnerWithoutSetupWizard() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_managedProfileFromSetupWizard_primaryUser()
            throws Exception {
        setupProfileOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_PROFILE_COMPLETE,
                DevicePolicyManager.STATE_USER_UNMANAGED);
    }

    public void testSetUserProvisioningState_managedProfileFromSetupWizard_managedProfile()
            throws Exception {
        setupProfileOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_managedProfileWithoutSetupWizard() throws Exception {
        setupProfileOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_illegalTransitionOutOfFinalized1() throws Exception {
        setupProfileOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        try {
            exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                    DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                    DevicePolicyManager.STATE_USER_UNMANAGED);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            MoreAsserts.assertContainsRegex("Cannot move to user provisioning state",
                    e.getMessage());
        }
    }

    public void testSetUserProvisioningState_illegalTransitionToAnotherInProgressState()
            throws Exception {
        setupProfileOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        try {
            exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                    DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                    DevicePolicyManager.STATE_USER_SETUP_COMPLETE);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            MoreAsserts.assertContainsRegex("Cannot move to user provisioning state",
                    e.getMessage());
        }
    }

    private void exerciseUserProvisioningTransitions(int userId, int... states) {
        assertEquals(DevicePolicyManager.STATE_USER_UNMANAGED, dpm.getUserProvisioningState());
        for (int state : states) {
            dpm.setUserProvisioningState(state, userId);
            assertEquals(state, dpm.getUserProvisioningState());
        }
    }

    private void setupProfileOwner() throws Exception {
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        dpm.setActiveAdmin(admin1, false);
        assertTrue(dpm.setProfileOwner(admin1, null, DpmMockContext.CALLER_USER_HANDLE));

        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    private void setupDeviceOwner() throws Exception {
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, false);
        assertTrue(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM));

        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    public void testSetMaximumTimeToLock() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        dpm.setActiveAdmin(admin2, /* replace =*/ false);

        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin1, 0);
        verifyScreenTimeoutCall(null, false);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin1, 1);
        verifyScreenTimeoutCall(1, true);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin2, 10);
        verifyScreenTimeoutCall(null, false);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin1, 5);
        verifyScreenTimeoutCall(5, true);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin2, 4);
        verifyScreenTimeoutCall(4, true);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin1, 0);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin2, Integer.MAX_VALUE);
        verifyScreenTimeoutCall(Integer.MAX_VALUE, true);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin2, Integer.MAX_VALUE + 1);
        verifyScreenTimeoutCall(Integer.MAX_VALUE, true);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        dpm.setMaximumTimeToLock(admin2, 10);
        verifyScreenTimeoutCall(10, true);
        reset(mMockContext.powerManagerInternal);
        reset(mMockContext.settings);

        // There's no restriction; shold be set to MAX.
        dpm.setMaximumTimeToLock(admin2, 0);
        verifyScreenTimeoutCall(Integer.MAX_VALUE, false);
    }

    private void verifyScreenTimeoutCall(Integer expectedTimeout,
            boolean shouldStayOnWhilePluggedInBeCleared) {
        if (expectedTimeout == null) {
            verify(mMockContext.powerManagerInternal, times(0))
                    .setMaximumScreenOffTimeoutFromDeviceAdmin(anyInt());
        } else {
            verify(mMockContext.powerManagerInternal, times(1))
                    .setMaximumScreenOffTimeoutFromDeviceAdmin(eq(expectedTimeout));
        }
        // TODO Verify calls to settingsGlobalPutInt.  Tried but somehow mockito threw
        // UnfinishedVerificationException.
    }

    public void testIsProvisioningAllowed_DeviceAdminFeatureOff() throws Exception {
        when(mContext.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN))
                .thenReturn(false);
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(false);
        initializeDpms();
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, false);
    }

    public void testIsProvisioningAllowed_ManagedProfileFeatureOff() throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(false);
        initializeDpms();
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, false);

        // Test again when split user is on
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, false);
    }

    public void testIsProvisioningAllowed_nonSplitUser_firstBoot_primaryUser() throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false /* because of non-split user */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false /* because of non-split user */);
    }

    public void testIsProvisioningAllowed_nonSplitUser_afterDeviceSetup_primaryUser()
            throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                false/* because of completed device setup */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false/* because of non-split user */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because of non-split user */);
    }

    public void testIsProvisioningAllowed_splitUser_firstBoot_systemUser() throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(false);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                false /* because canAddMoreManagedProfiles returns false */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because calling uid is system user */);

    }

    public void testIsProvisioningAllowed_splitUser_afterDeviceSetup_systemUser() throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(false);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                false /* because canAddMoreManagedProfiles returns false */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because calling uid is system user */);
    }

    public void testIsProvisioningAllowed_splitUser_firstBoot_primaryUser() throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(mContext.userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(false, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, true);

    }

    public void testIsProvisioningAllowed_splitUser_afterDeviceSetup_primaryUser()
            throws Exception {
        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(mContext.userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(true, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because user setup completed */);
    }

    public void testIsProvisioningAllowed_provisionManagedProfileWithDeviceOwner_systemUser()
            throws Exception {
        setDeviceOwner();

        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(mContext.userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(false);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                false /* can't provision managed profile on system user */);
    }

    public void testIsProvisioningAllowed_provisionManagedProfileWithDeviceOwner_primaryUser()
            throws Exception {
        setDeviceOwner();

        when(mContext.ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(mContext.userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(mContext.userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(false, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
    }

    private void setUserSetupCompleteForUser(boolean isUserSetupComplete, int userhandle) {
        when(mContext.settings.settingsSecureGetIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 0,
                userhandle)).thenReturn(isUserSetupComplete ? 1 : 0);
        dpms.notifyChangeToContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), userhandle);
    }

    private void assertProvisioningAllowed(String action, boolean expected) {
        assertEquals("isProvisioningAllowed(" + action + ") returning unexpected result", expected,
                dpm.isProvisioningAllowed(action));
    }
}
