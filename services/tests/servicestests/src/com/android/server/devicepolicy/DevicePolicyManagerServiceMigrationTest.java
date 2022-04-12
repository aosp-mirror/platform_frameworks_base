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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IpcDataCache;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;
import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class DevicePolicyManagerServiceMigrationTest extends DpmTestBase {

    private static final String USER_TYPE_EMPTY = "";
    private static final int COPE_ADMIN1_APP_ID = 123;
    private static final int COPE_ANOTHER_ADMIN_APP_ID = 125;
    private static final int COPE_PROFILE_USER_ID = 11;

    private DpmMockContext mContext;

    @Before
    public void setUp() throws Exception {

        // Disable caches in this test process. This must happen early, since some of the
        // following initialization steps invalidate caches.
        IpcDataCache.disableForTestMode();

        mContext = getContext();

        // Make createContextAsUser to work.
        mContext.packageName = "com.android.frameworks.servicestests";
        getServices().addPackageContext(UserHandle.of(0), mContext);

        when(getServices().packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);
    }

    @Test
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
            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }

        assertThat(dpms.mOwners.hasDeviceOwner()).isTrue();
        assertThat(dpms.mOwners.hasProfileOwner(USER_SYSTEM)).isFalse();
        assertThat(dpms.mOwners.hasProfileOwner(10)).isTrue();
        assertThat(dpms.mOwners.hasProfileOwner(11)).isTrue();
        assertThat(dpms.mOwners.hasProfileOwner(12)).isFalse();

        // Now all information should be migrated.
        assertThat(dpms.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
        assertThat(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(USER_SYSTEM))
            .isFalse();
        assertThat(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
        assertThat(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
        assertThat(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(12)).isFalse();

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

    @Test
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
            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }
        assertThat(dpms.mOwners.hasDeviceOwner()).isFalse();
        assertThat(dpms.mOwners.hasProfileOwner(USER_SYSTEM)).isTrue();

        // Now all information should be migrated.
        assertThat(dpms.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
        assertThat(dpms.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(USER_SYSTEM))
            .isFalse();

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
    @Test
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
            dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
            dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);
        } finally {
            mContext.binder.restoreCallingIdentity(ident);
        }

        assertThat(dpms.mOwners.hasDeviceOwner()).isFalse();
        assertThat(dpms.mOwners.hasProfileOwner(10)).isTrue();

        // Check that default restrictions were applied.
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_BLUETOOTH_SHARING
                ),
                dpms.getProfileOwnerAdminLocked(10).ensureUserRestrictions());

        final Set<String> alreadySet =
                dpms.getProfileOwnerAdminLocked(10).defaultEnabledRestrictionsAlreadySet;
        assertThat(alreadySet).hasSize(1);
        assertThat(alreadySet.contains(UserManager.DISALLOW_BLUETOOTH_SHARING)).isTrue();
    }

    @SmallTest
    @Test
    public void testCompMigrationUnAffiliated_skipped() throws Exception {
        prepareAdmin1AsDo();
        prepareAdminAnotherPackageAsPo(COPE_PROFILE_USER_ID);

        final DevicePolicyManagerServiceTestable dpms = bootDpmsUp();

        // DO should still be DO since no migration should happen.
        assertThat(dpms.mOwners.hasDeviceOwner()).isTrue();
    }

    @SmallTest
    @Test
    public void testCompMigrationAffiliated() throws Exception {
        prepareAdmin1AsDo();
        prepareAdmin1AsPo(COPE_PROFILE_USER_ID, Build.VERSION_CODES.R);

        // Secure lock screen is needed for password policy APIs to work.
        when(getServices().lockPatternUtils.hasSecureLockScreen()).thenReturn(true);

        final DevicePolicyManagerServiceTestable dpms = bootDpmsUp();

        // DO should cease to be DO.
        assertThat(dpms.mOwners.hasDeviceOwner()).isFalse();

        final DpmMockContext poContext = new DpmMockContext(getServices(), mRealTestContext);
        poContext.binder.callingUid = UserHandle.getUid(COPE_PROFILE_USER_ID, COPE_ADMIN1_APP_ID);

        runAsCaller(poContext, dpms, dpm -> {
            assertWithMessage("Password history policy wasn't migrated to PO parent instance")
                    .that(dpm.getParentProfileInstance(admin1).getPasswordHistoryLength(admin1))
                    .isEqualTo(33);
            assertWithMessage("Password history policy was put into non-parent PO instance")
                    .that(dpm.getPasswordHistoryLength(admin1)).isEqualTo(0);
            assertWithMessage("Screen capture restriction wasn't migrated to PO parent instance")
                    .that(dpm.getParentProfileInstance(admin1).getScreenCaptureDisabled(admin1))
                    .isTrue();

            assertWithMessage("Accounts with management disabled weren't migrated to PO parent")
                    .that(dpm.getParentProfileInstance(admin1)
                            .getAccountTypesWithManagementDisabled()).asList()
                    .containsExactly("com.google-primary");

            assertWithMessage("Accounts with management disabled for profile were lost")
                    .that(dpm.getAccountTypesWithManagementDisabled()).asList()
                    .containsExactly("com.google-profile");

            assertWithMessage("User restriction wasn't migrated to PO parent instance")
                    .that(dpm.getParentProfileInstance(admin1).getUserRestrictions(admin1).keySet())
                    .contains(UserManager.DISALLOW_BLUETOOTH);

            assertWithMessage("User restriction was put into non-parent PO instance").that(
                    dpm.getUserRestrictions(admin1).keySet())
                    .doesNotContain(UserManager.DISALLOW_BLUETOOTH);

            assertWithMessage("User restriction wasn't migrated to PO parent instance")
                    .that(dpms.getProfileOwnerAdminLocked(COPE_PROFILE_USER_ID)
                            .getParentActiveAdmin().getEffectiveRestrictions().keySet())
                    .contains(UserManager.DISALLOW_CONFIG_DATE_TIME);
            assertWithMessage("User restriction was put into non-parent PO instance")
                    .that(dpms.getProfileOwnerAdminLocked(COPE_PROFILE_USER_ID)
                            .getEffectiveRestrictions().keySet())
                    .doesNotContain(UserManager.DISALLOW_CONFIG_DATE_TIME);
            assertWithMessage("Personal apps suspension wasn't migrated")
                    .that(dpm.getPersonalAppsSuspendedReasons(admin1))
                    .isEqualTo(DevicePolicyManager.PERSONAL_APPS_NOT_SUSPENDED);
        });
    }

    @SmallTest
    @Test
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
            assertWithMessage("Personal apps suspension wasn't migrated")
                    .that(dpm.getPersonalAppsSuspendedReasons(admin1))
                    .isEqualTo(DevicePolicyManager.PERSONAL_APPS_SUSPENDED_EXPLICITLY);
        });
    }

    @SmallTest
    @Test
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
            assertWithMessage("Personal apps weren't unsuspended")
                    .that(dpm.getPersonalAppsSuspendedReasons(admin1))
                    .isEqualTo(DevicePolicyManager.PERSONAL_APPS_NOT_SUSPENDED);
        });
    }

    private DevicePolicyManagerServiceTestable bootDpmsUp() {
        DevicePolicyManagerServiceTestable dpms;
        final long ident = mContext.binder.clearCallingIdentity();
        try {
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
