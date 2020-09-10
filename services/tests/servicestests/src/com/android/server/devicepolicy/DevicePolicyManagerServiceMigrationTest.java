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

import static android.os.UserHandle.USER_SYSTEM;

import static com.android.server.devicepolicy.DpmTestUtils.writeInputStreamToFile;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.frameworks.servicestests.R;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Presubmit
public class DevicePolicyManagerServiceMigrationTest extends DpmTestBase {

    private static final String USER_TYPE_EMPTY = "";
    private static final int COPE_ADMIN1_APP_ID = 123;
    private static final int COPE_ANOTHER_ADMIN_APP_ID = 125;
    private static final int COPE_PROFILE_USER_ID = 11;

    private DpmMockContext mContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();

        // Make createContextAsUser to work.
        mContext.packageName = "com.android.frameworks.servicestests";
        getServices().addPackageContext(UserHandle.of(0), mContext);

        when(getServices().packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);
    }

    public void testMigration() throws Exception {
        final File user10dir = getServices().addUser(10, 0, USER_TYPE_EMPTY);
        final File user11dir = getServices().addUser(11, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED);
        getServices().addUser(12, 0, USER_TYPE_EMPTY);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin2, UserHandle.getUid(10, 123));
        setUpPackageManagerForAdmin(admin3, UserHandle.getUid(11, 456));

        // Create the legacy owners & policies file.
        DpmTestUtils.writeToFile(
                (new File(getServices().dataDir, "device_owner.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_owner.xml"));

        DpmTestUtils.writeToFile(
                (new File(getServices().systemUserDataDir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_policies.xml"));

        DpmTestUtils.writeToFile(
                (new File(user10dir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_policies_10.xml"));
        DpmTestUtils.writeToFile(
                (new File(user11dir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest/legacy_device_policies_11.xml"));

        // Set up UserManager
        when(getServices().userManagerInternal.getBaseUserRestrictions(
                eq(USER_SYSTEM))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_RECORD_AUDIO));

        when(getServices().userManagerInternal.getBaseUserRestrictions(
                eq(10))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_OUTGOING_CALLS,
                UserManager.DISALLOW_WALLPAPER,
                UserManager.DISALLOW_RECORD_AUDIO));

        when(getServices().userManagerInternal.getBaseUserRestrictions(
                eq(11))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_OUTGOING_CALLS,
                UserManager.DISALLOW_WALLPAPER,
                UserManager.DISALLOW_RECORD_AUDIO));

        final Map<Integer, Bundle> newBaseRestrictions = new HashMap<>();

        doAnswer(invocation -> {
            Integer userId = (Integer) invocation.getArguments()[0];
            Bundle bundle = (Bundle) invocation.getArguments()[1];

            newBaseRestrictions.put(userId, bundle);

            return null;
        }).when(getServices().userManagerInternal).setBaseUserRestrictionsByDpmsForMigration(
                anyInt(), any(Bundle.class));

        // Initialize DPM/DPMS and let it migrate the persisted information.
        // (Need clearCallingIdentity() to pass permission checks.)

        final DevicePolicyManagerServiceTestable dpms;

        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }

        assertTrue(dpms.mOwners.hasDeviceOwner());
        assertFalse(dpms.mOwners.hasProfileOwner(USER_SYSTEM));
        assertTrue(dpms.mOwners.hasProfileOwner(10));
        assertTrue(dpms.mOwners.hasProfileOwner(11));
        assertFalse(dpms.mOwners.hasProfileOwner(12));

        // Now all information should be migrated.
        assertFalse(dpms.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(
                USER_SYSTEM));
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(10));
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(11));
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(12));

        // Check the new base restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_RECORD_AUDIO
                ),
                newBaseRestrictions.get(USER_SYSTEM));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_SMS,
                        UserManager.DISALLOW_OUTGOING_CALLS,
                        UserManager.DISALLOW_RECORD_AUDIO,
                        UserManager.DISALLOW_WALLPAPER
                ),
                newBaseRestrictions.get(10));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_SMS,
                        UserManager.DISALLOW_OUTGOING_CALLS,
                        UserManager.DISALLOW_WALLPAPER,
                        UserManager.DISALLOW_RECORD_AUDIO
                ),
                newBaseRestrictions.get(11));

        // Check the new owner restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER
                ),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions());

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_REMOVE_USER
                ),
                dpms.getProfileOwnerAdminLocked(10).ensureUserRestrictions());

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_REMOVE_USER
                ),
                dpms.getProfileOwnerAdminLocked(11).ensureUserRestrictions());
    }

    public void testMigration2_profileOwnerOnUser0() throws Exception {
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Create the legacy owners & policies file.
        DpmTestUtils.writeToFile(
                (new File(getServices().dataDir, "device_owner.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest2/legacy_device_owner.xml"));

        DpmTestUtils.writeToFile(
                (new File(getServices().systemUserDataDir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest2/legacy_device_policies.xml"));

        // Set up UserManager
        when(getServices().userManagerInternal.getBaseUserRestrictions(
                eq(USER_SYSTEM))).thenReturn(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_RECORD_AUDIO,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_OUTGOING_CALLS));

        final Map<Integer, Bundle> newBaseRestrictions = new HashMap<>();

        doAnswer(invocation -> {
            Integer userId = (Integer) invocation.getArguments()[0];
            Bundle bundle = (Bundle) invocation.getArguments()[1];

            newBaseRestrictions.put(userId, bundle);

            return null;
        }).when(getServices().userManagerInternal).setBaseUserRestrictionsByDpmsForMigration(
                anyInt(), any(Bundle.class));

        // Initialize DPM/DPMS and let it migrate the persisted information.
        // (Need clearCallingIdentity() to pass permission checks.)

        final DevicePolicyManagerServiceTestable dpms;

        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }
        assertFalse(dpms.mOwners.hasDeviceOwner());
        assertTrue(dpms.mOwners.hasProfileOwner(USER_SYSTEM));

        // Now all information should be migrated.
        assertFalse(dpms.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
        assertFalse(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(
                USER_SYSTEM));

        // Check the new base restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_RECORD_AUDIO
                ),
                newBaseRestrictions.get(USER_SYSTEM));

        // Check the new owner restrictions.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_SMS,
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpms.getProfileOwnerAdminLocked(USER_SYSTEM).ensureUserRestrictions());
    }

    // Test setting default restrictions for managed profile.
    public void testMigration3_managedProfileOwner() throws Exception {
        // Create a managed profile user.
        final File user10dir = getServices().addUser(10, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED);
        // Profile owner package for managed profile user.
        setUpPackageManagerForAdmin(admin1, UserHandle.getUid(10, 123));
        // Set up fake UserManager to make it look like a managed profile.
        when(getServices().userManager.isManagedProfile(eq(10))).thenReturn(true);
        // Set up fake Settings to make it look like INSTALL_NON_MARKET_APPS was reversed.
        when(getServices().settings.settingsSecureGetIntForUser(
                eq(Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED),
                eq(0), eq(10))).thenReturn(1);
        // Write policy and owners files.
        DpmTestUtils.writeToFile(
                (new File(getServices().systemUserDataDir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest3/system_device_policies.xml"));
        DpmTestUtils.writeToFile(
                (new File(user10dir, "device_policies.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest3/profile_device_policies.xml"));
        DpmTestUtils.writeToFile(
                (new File(user10dir, "profile_owner.xml")).getAbsoluteFile(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "DevicePolicyManagerServiceMigrationTest3/profile_owner.xml"));

        final DevicePolicyManagerServiceTestable dpms;

        // Initialize DPM/DPMS and let it migrate the persisted information.
        // (Need clearCallingIdentity() to pass permission checks.)
        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }

        assertFalse(dpms.mOwners.hasDeviceOwner());
        assertTrue(dpms.mOwners.hasProfileOwner(10));

        // Check that default restrictions were applied.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_BLUETOOTH_SHARING
                ),
                dpms.getProfileOwnerAdminLocked(10).ensureUserRestrictions());

        final Set<String> alreadySet =
                dpms.getProfileOwnerAdminLocked(10).defaultEnabledRestrictionsAlreadySet;
        assertEquals(alreadySet.size(), 1);
        assertTrue(alreadySet.contains(UserManager.DISALLOW_BLUETOOTH_SHARING));
    }

    @SmallTest
    public void testCompMigrationUnAffiliated_skipped() throws Exception {
        prepareAdmin1AsDo();
        prepareAdminAnotherPackageAsPo(COPE_PROFILE_USER_ID);

        final DevicePolicyManagerServiceTestable dpms = bootDpmsUp();

        // DO should still be DO since no migration should happen.
        assertTrue(dpms.mOwners.hasDeviceOwner());
    }

    @SmallTest
    public void testCompMigrationAffiliated() throws Exception {
        prepareAdmin1AsDo();
        prepareAdmin1AsPo(COPE_PROFILE_USER_ID, Build.VERSION_CODES.R);

        // Secure lock screen is needed for password policy APIs to work.
        when(getServices().lockPatternUtils.hasSecureLockScreen()).thenReturn(true);

        final DevicePolicyManagerServiceTestable dpms = bootDpmsUp();

        // DO should cease to be DO.
        assertFalse(dpms.mOwners.hasDeviceOwner());

        final DpmMockContext poContext = new DpmMockContext(getServices(), mRealTestContext);
        poContext.binder.callingUid = UserHandle.getUid(COPE_PROFILE_USER_ID, COPE_ADMIN1_APP_ID);

        runAsCaller(poContext, dpms, dpm -> {
            assertEquals("Password history policy wasn't migrated to PO parent instance",
                    33, dpm.getParentProfileInstance(admin1).getPasswordHistoryLength(admin1));
            assertEquals("Password history policy was put into non-parent PO instance",
                    0, dpm.getPasswordHistoryLength(admin1));
            assertTrue("Screen capture restriction wasn't migrated to PO parent instance",
                    dpm.getParentProfileInstance(admin1).getScreenCaptureDisabled(admin1));

            assertArrayEquals("Accounts with management disabled weren't migrated to PO parent",
                    new String[] {"com.google-primary"},
                    dpm.getParentProfileInstance(admin1).getAccountTypesWithManagementDisabled());
            assertArrayEquals("Accounts with management disabled for profile were lost",
                    new String[] {"com.google-profile"},
                    dpm.getAccountTypesWithManagementDisabled());

            assertTrue("User restriction wasn't migrated to PO parent instance",
                    dpm.getParentProfileInstance(admin1).getUserRestrictions(admin1)
                            .containsKey(UserManager.DISALLOW_BLUETOOTH));
            assertFalse("User restriction was put into non-parent PO instance",
                    dpm.getUserRestrictions(admin1).containsKey(UserManager.DISALLOW_BLUETOOTH));

            assertTrue("User restriction wasn't migrated to PO parent instance",
                    dpms.getProfileOwnerAdminLocked(COPE_PROFILE_USER_ID)
                            .getParentActiveAdmin()
                            .getEffectiveRestrictions()
                            .containsKey(UserManager.DISALLOW_CONFIG_DATE_TIME));
            assertFalse("User restriction was put into non-parent PO instance",
                    dpms.getProfileOwnerAdminLocked(COPE_PROFILE_USER_ID)
                            .getEffectiveRestrictions()
                            .containsKey(UserManager.DISALLOW_CONFIG_DATE_TIME));
            assertEquals("Personal apps suspension wasn't migrated",
                    DevicePolicyManager.PERSONAL_APPS_NOT_SUSPENDED,
                    dpm.getPersonalAppsSuspendedReasons(admin1));
        });
    }

    @SmallTest
    public void testCompMigration_keepSuspendedAppsWhenDpcIsRPlus() throws Exception {
        prepareAdmin1AsDo();
        prepareAdmin1AsPo(COPE_PROFILE_USER_ID, Build.VERSION_CODES.R);

        // Pretend some packages are suspended.
        when(getServices().packageManagerInternal.isSuspendingAnyPackages(
                PLATFORM_PACKAGE_NAME, USER_SYSTEM)).thenReturn(true);

        final DevicePolicyManagerServiceTestable dpms = bootDpmsUp();

        verify(getServices().packageManagerInternal, never())
                .unsuspendForSuspendingPackage(PLATFORM_PACKAGE_NAME, USER_SYSTEM);

        sendBroadcastWithUser(dpms, Intent.ACTION_USER_STARTED, USER_SYSTEM);

        // Verify that actual package suspension state is not modified after user start
        verify(getServices().packageManagerInternal, never())
                .unsuspendForSuspendingPackage(PLATFORM_PACKAGE_NAME, USER_SYSTEM);
        verify(getServices().ipackageManager, never()).setPackagesSuspendedAsUser(
                any(), anyBoolean(), any(), any(), any(), any(), anyInt());

        final DpmMockContext poContext = new DpmMockContext(getServices(), mRealTestContext);
        poContext.binder.callingUid = UserHandle.getUid(COPE_PROFILE_USER_ID, COPE_ADMIN1_APP_ID);

        runAsCaller(poContext, dpms, dpm -> {
            assertEquals("Personal apps suspension wasn't migrated",
                    DevicePolicyManager.PERSONAL_APPS_SUSPENDED_EXPLICITLY,
                    dpm.getPersonalAppsSuspendedReasons(admin1));
        });
    }

    @SmallTest
    public void testCompMigration_unsuspendAppsWhenDpcNotRPlus() throws Exception {
        prepareAdmin1AsDo();
        prepareAdmin1AsPo(COPE_PROFILE_USER_ID, Build.VERSION_CODES.Q);

        // Pretend some packages are suspended.
        when(getServices().packageManagerInternal.isSuspendingAnyPackages(
                PLATFORM_PACKAGE_NAME, USER_SYSTEM)).thenReturn(true);

        final DevicePolicyManagerServiceTestable dpms = bootDpmsUp();

        // Verify that apps get unsuspended.
        verify(getServices().packageManagerInternal)
                .unsuspendForSuspendingPackage(PLATFORM_PACKAGE_NAME, USER_SYSTEM);

        final DpmMockContext poContext = new DpmMockContext(getServices(), mRealTestContext);
        poContext.binder.callingUid = UserHandle.getUid(COPE_PROFILE_USER_ID, COPE_ADMIN1_APP_ID);

        runAsCaller(poContext, dpms, dpm -> {
            assertEquals("Personal apps weren't unsuspended",
                    DevicePolicyManager.PERSONAL_APPS_NOT_SUSPENDED,
                    dpm.getPersonalAppsSuspendedReasons(admin1));
        });
    }

    private DevicePolicyManagerServiceTestable bootDpmsUp() {
        DevicePolicyManagerServiceTestable dpms;
        final long ident = mContext.binder.clearCallingIdentity();
        try {
            LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_ACTIVITY_MANAGER_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }
        return dpms;
    }

    private void prepareAdmin1AsDo() throws Exception {
        setUpPackageManagerForAdmin(admin1, UserHandle.getUid(USER_SYSTEM, COPE_ADMIN1_APP_ID));
        final int xmlResource = R.raw.comp_policies_primary;
        writeInputStreamToFile(getRawStream(xmlResource),
                (new File(getServices().systemUserDataDir, "device_policies.xml"))
                        .getAbsoluteFile());
        writeInputStreamToFile(getRawStream(R.raw.comp_device_owner),
                (new File(getServices().dataDir, "device_owner_2.xml"))
                        .getAbsoluteFile());
    }

    private void prepareAdmin1AsPo(int profileUserId, int targetSdk) throws Exception {
        preparePo(profileUserId, admin1, R.raw.comp_profile_owner_same_package,
                R.raw.comp_policies_profile_same_package, COPE_ADMIN1_APP_ID, targetSdk);
    }

    private void prepareAdminAnotherPackageAsPo(int profileUserId) throws Exception {
        preparePo(profileUserId, adminAnotherPackage, R.raw.comp_profile_owner_another_package,
                R.raw.comp_policies_profile_another_package, COPE_ANOTHER_ADMIN_APP_ID,
                Build.VERSION.SDK_INT);
    }

    private void preparePo(int profileUserId, ComponentName admin, int profileOwnerXmlResId,
            int policyXmlResId, int adminAppId, int targetSdk) throws Exception {
        final File profileDir = getServices().addUser(profileUserId, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED, USER_SYSTEM /* profile group */);
        setUpPackageManagerForFakeAdmin(admin, UserHandle.getUid(profileUserId, adminAppId),
                /* enabledSetting =*/ null, targetSdk, admin1);
        writeInputStreamToFile(getRawStream(policyXmlResId),
                (new File(profileDir, "device_policies.xml")).getAbsoluteFile());
        writeInputStreamToFile(getRawStream(profileOwnerXmlResId),
                (new File(profileDir, "profile_owner.xml")).getAbsoluteFile());
    }

}
