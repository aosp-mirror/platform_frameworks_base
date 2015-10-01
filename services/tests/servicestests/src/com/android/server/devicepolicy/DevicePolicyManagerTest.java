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
import com.android.server.SystemService;

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
import android.content.pm.PackageInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;

import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doAnswer;
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

        initializeDpms();

        admin1 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin1.class);
        admin2 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin2.class);
        admin3 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin3.class);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin3, DpmMockContext.CALLER_UID);

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

    private void setUpPackageManagerForAdmin(ComponentName admin, int packageUid) throws Exception {
        setUpPackageManagerForAdmin(admin, packageUid,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);
    }

    private void setUpPackageManagerForAdmin(ComponentName admin, int packageUid,
            int enabledSetting) throws Exception {

        // Set up queryBroadcastReceivers().

        final Intent resolveIntent = new Intent();
        resolveIntent.setComponent(admin);
        final List<ResolveInfo> realResolveInfo =
                mRealTestContext.getPackageManager().queryBroadcastReceivers(
                        resolveIntent,
                        PackageManager.GET_META_DATA);
        assertNotNull(realResolveInfo);
        assertEquals(1, realResolveInfo.size());

        // We need to change AI, so set a clone.
        realResolveInfo.set(0, DpmTestUtils.cloneParcelable(realResolveInfo.get(0)));

        // We need to rewrite the UID in the activity info.
        realResolveInfo.get(0).activityInfo.applicationInfo.uid = packageUid;

        doReturn(realResolveInfo).when(mContext.packageManager).queryBroadcastReceivers(
                MockUtils.checkIntentComponent(admin),
                eq(PackageManager.GET_META_DATA
                        | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS),
                eq(UserHandle.getUserId(packageUid)));

        // Set up getApplicationInfo().

        final ApplicationInfo ai = DpmTestUtils.cloneParcelable(
                mRealTestContext.getPackageManager().getApplicationInfo(
                        admin1.getPackageName(),
                        PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS));

        ai.enabledSetting = enabledSetting;
        ai.uid = packageUid;

        doReturn(ai).when(mContext.ipackageManager).getApplicationInfo(
                eq(admin1.getPackageName()),
                eq(PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS),
                eq(UserHandle.getUserId(packageUid)));

        // Set up getPackageInfo().

        final PackageInfo pi = DpmTestUtils.cloneParcelable(
                mRealTestContext.getPackageManager().getPackageInfo(
                        admin1.getPackageName(), 0));
        assertTrue(pi.applicationInfo.flags != 0);

        pi.applicationInfo.uid = packageUid;

        doReturn(pi).when(mContext.ipackageManager).getPackageInfo(
                eq(admin1.getPackageName()),
                eq(0),
                eq(UserHandle.getUserId(packageUid)));
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
        assertEquals(admin1, dpm.getProfileOwnerAsUser(DpmMockContext.CALLER_USER_HANDLE));
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

        // Change the caller, and call into DPMS directly with a different user-id.

        mContext.binder.callingUid = 1234567;
        try {
            dpms.removeActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE);
            fail("Didn't throw SecurityException");
        } catch (SecurityException expected) {
        }
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

    /**
     * Test for: {@link DevicePolicyManager#setDeviceOwner} DO on system user installs
     * successfully.
     */
    public void testSetDeviceOwner() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // In this test, change the caller user to "system".
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // DO needs to be an DA.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        // Fire!
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name"));

        // Verify internal calls.
        verify(mContext.iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        // TODO We should check if the caller has called clearCallerIdentity().
        verify(mContext.ibackupManager, times(1)).setBackupServiceActive(
                eq(UserHandle.USER_SYSTEM), eq(false));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));

        assertEquals(admin1.getPackageName(), dpm.getDeviceOwner());

        // TODO Test getDeviceOwnerName() too.  To do so, we need to change
        // DPMS.getApplicationLabel() because Context.createPackageContextAsUser() is not mockable.
    }

    /**
     * Test for: {@link DevicePolicyManager#setDeviceOwner} Package doesn't exist.
     */
    public void testSetDeviceOwner_noSuchPackage() {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Call from a process on the system user.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        try {
            dpm.setDeviceOwner(new ComponentName("a.b.c", ".def"));
            fail("Didn't throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testSetDeviceOwner_failures() throws Exception {
        // TODO Test more failure cases.  Basically test all chacks in enforceCanSetDeviceOwner().
    }

    public void testClearDeviceOwner() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
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

        assertEquals(admin1.getPackageName(), dpm.getDeviceOwner());

        // Set up other mocks.
        when(mContext.userManager.getUserRestrictions()).thenReturn(new Bundle());

        // Now call clear.
        doReturn(DpmMockContext.CALLER_SYSTEM_USER_UID).when(mContext.packageManager).getPackageUid(
                eq(admin1.getPackageName()),
                anyInt());
        dpm.clearDeviceOwnerApp(admin1.getPackageName());

        // Now DO shouldn't be set.
        assertNull(dpm.getDeviceOwner());

        // TODO Check other calls.
    }

    public void testClearDeviceOwner_fromDifferentUser() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
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

        assertEquals(admin1.getPackageName(), dpm.getDeviceOwner());

        // Now call clear from the secondary user, which should throw.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Now call clear.
        doReturn(DpmMockContext.CALLER_UID).when(mContext.packageManager).getPackageUid(
                eq(admin1.getPackageName()),
                anyInt());
        try {
            dpm.clearDeviceOwnerApp(admin1.getPackageName());
            fail("Didn't throw");
        } catch (SecurityException e) {
            assertEquals("clearDeviceOwner can only be called by the device owner", e.getMessage());
        }

        // Now DO shouldn't be set.
        assertNotNull(dpm.getDeviceOwner());
    }

    public void testSetProfileOwner() throws Exception {
        setAsProfileOwner(admin1);
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
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

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

        // Make sure it's set.
        assertEquals(admin2, dpm.getDeviceOwnerComponent());

        // Then check getDeviceOwnerAdminLocked().
        assertEquals(admin2, dpms.getDeviceOwnerAdminLocked().info.getComponent());
        assertEquals(DpmMockContext.CALLER_UID, dpms.getDeviceOwnerAdminLocked().getUid());
    }

    /**
     * This essentially tests
     * {@code DevicePolicyManagerService.findOwnerComponentIfNecessaryLocked()}. (which is private.)
     *
     * We didn't use to persist the DO component class name, but now we do, and the above method
     * finds the right component from a package name upon migration.
     */
    public void testDeviceOwnerMigration() throws Exception {
        checkDeviceOwnerWithMultipleDeviceAdmins();

        // Overwrite the device owner setting and clears the clas name.
        dpms.mOwners.setDeviceOwner(
                new ComponentName(admin2.getPackageName(), ""),
                "owner-name", DpmMockContext.CALLER_USER_HANDLE);
        dpms.mOwners.writeDeviceOwner();

        // Make sure the DO component name doesn't have a class name.
        assertEquals("", dpms.getDeviceOwner().getClassName());

        // Then create a new DPMS to have it load the settings from files.
        initializeDpms();

        // Now the DO component name is a full name.
        // *BUT* because both admin1 and admin2 belong to the same package, we think admin1 is the
        // DO.
        assertEquals(admin1, dpms.getDeviceOwner());
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

    public void testSetUserRestriction_asDo() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
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

        assertFalse(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_SMS));
        assertFalse(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_SMS);
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);

        assertTrue(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_SMS));
        assertTrue(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_SMS);

        assertFalse(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_SMS));
        assertTrue(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);

        assertFalse(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_SMS));
        assertFalse(dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        // TODO Check inner calls.
        // TODO Make sure restrictions are written to the file.
    }

    public void testSetUserRestriction_asPo() {
        setAsProfileOwner(admin1);

        assertFalse(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));
        assertFalse(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);

        assertTrue(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));
        assertTrue(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);

        assertFalse(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));
        assertTrue(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);

        assertFalse(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));
        assertFalse(dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                .ensureUserRestrictions()
                .getBoolean(UserManager.DISALLOW_OUTGOING_CALLS));

        // TODO Check inner calls.
        // TODO Make sure restrictions are written to the file.
    }
}
