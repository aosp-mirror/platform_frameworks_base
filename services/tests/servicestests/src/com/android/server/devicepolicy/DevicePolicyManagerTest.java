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

import static android.app.admin.DevicePolicyManager.DELEGATION_APP_RESTRICTIONS;
import static android.app.admin.DevicePolicyManager.DELEGATION_CERT_INSTALL;
import static android.app.admin.DevicePolicyManager.ID_TYPE_BASE_INFO;
import static android.app.admin.DevicePolicyManager.ID_TYPE_IMEI;
import static android.app.admin.DevicePolicyManager.ID_TYPE_MEID;
import static android.app.admin.DevicePolicyManager.ID_TYPE_SERIAL;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
import static android.app.admin.DevicePolicyManager.WIPE_EUICC;
import static android.os.UserManagerInternal.CAMERA_DISABLED_GLOBALLY;
import static android.os.UserManagerInternal.CAMERA_DISABLED_LOCALLY;
import static android.os.UserManagerInternal.CAMERA_NOT_DISABLED;

import static com.android.internal.widget.LockPatternUtils.EscrowTokenStateChangeCallback;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static org.testng.Assert.assertThrows;

import android.Manifest.permission;
import android.annotation.RawRes;
import android.app.Activity;
import android.app.Notification;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.PasswordMetrics;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.StringParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.security.KeyChain;
import android.security.keystore.AttestationUtils;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.devicepolicy.DevicePolicyManagerService.RestrictionsListener;
import com.android.server.pm.UserRestrictionsUtils;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tests for DevicePolicyManager( and DevicePolicyManagerService).
 * You can run them via:
 m FrameworksServicesTests &&
 adb install \
   -r ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.devicepolicy.DevicePolicyManagerTest \
   -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner

 (mmma frameworks/base/services/tests/servicestests/ for non-ninja build)
 *
 * , or:
 * runtest -c com.android.server.devicepolicy.DevicePolicyManagerTest frameworks-services
 */
@SmallTest
@Presubmit
public class DevicePolicyManagerTest extends DpmTestBase {
    private static final List<String> OWNER_SETUP_PERMISSIONS = Arrays.asList(
            permission.MANAGE_DEVICE_ADMINS, permission.MANAGE_PROFILE_AND_DEVICE_OWNERS,
            permission.MANAGE_USERS, permission.INTERACT_ACROSS_USERS_FULL);
    public static final String NOT_DEVICE_OWNER_MSG = "does not own the device";
    public static final String NOT_PROFILE_OWNER_MSG = "does not own the profile";
    public static final String ONGOING_CALL_MSG = "ongoing call on the device";

    // TODO replace all instances of this with explicit {@link #mServiceContext}.
    @Deprecated
    private DpmMockContext mContext;

    private DpmMockContext mServiceContext;
    private DpmMockContext mAdmin1Context;
    public DevicePolicyManager dpm;
    public DevicePolicyManagerServiceTestable dpms;

    /*
     * The CA cert below is the content of cacert.pem as generated by:
     *
     * openssl req -new -x509 -days 3650 -extensions v3_ca -keyout cakey.pem -out cacert.pem
     */
    private static final String TEST_CA =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIDXTCCAkWgAwIBAgIJAK9Tl/F9V8kSMA0GCSqGSIb3DQEBCwUAMEUxCzAJBgNV\n" +
            "BAYTAkFVMRMwEQYDVQQIDApTb21lLVN0YXRlMSEwHwYDVQQKDBhJbnRlcm5ldCBX\n" +
            "aWRnaXRzIFB0eSBMdGQwHhcNMTUwMzA2MTczMjExWhcNMjUwMzAzMTczMjExWjBF\n" +
            "MQswCQYDVQQGEwJBVTETMBEGA1UECAwKU29tZS1TdGF0ZTEhMB8GA1UECgwYSW50\n" +
            "ZXJuZXQgV2lkZ2l0cyBQdHkgTHRkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n" +
            "CgKCAQEAvItOutsE75WBTgTyNAHt4JXQ3JoseaGqcC3WQij6vhrleWi5KJ0jh1/M\n" +
            "Rpry7Fajtwwb4t8VZa0NuM2h2YALv52w1xivql88zce/HU1y7XzbXhxis9o6SCI+\n" +
            "oVQSbPeXRgBPppFzBEh3ZqYTVhAqw451XhwdA4Aqs3wts7ddjwlUzyMdU44osCUg\n" +
            "kVg7lfPf9sTm5IoHVcfLSCWH5n6Nr9sH3o2ksyTwxuOAvsN11F/a0mmUoPciYPp+\n" +
            "q7DzQzdi7akRG601DZ4YVOwo6UITGvDyuAAdxl5isovUXqe6Jmz2/myTSpAKxGFs\n" +
            "jk9oRoG6WXWB1kni490GIPjJ1OceyQIDAQABo1AwTjAdBgNVHQ4EFgQUH1QIlPKL\n" +
            "p2OQ/AoLOjKvBW4zK3AwHwYDVR0jBBgwFoAUH1QIlPKLp2OQ/AoLOjKvBW4zK3Aw\n" +
            "DAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAcMi4voMMJHeQLjtq8Oky\n" +
            "Azpyk8moDwgCd4llcGj7izOkIIFqq/lyqKdtykVKUWz2bSHO5cLrtaOCiBWVlaCV\n" +
            "DYAnnVLM8aqaA6hJDIfaGs4zmwz0dY8hVMFCuCBiLWuPfiYtbEmjHGSmpQTG6Qxn\n" +
            "ZJlaK5CZyt5pgh5EdNdvQmDEbKGmu0wpCq9qjZImwdyAul1t/B0DrsWApZMgZpeI\n" +
            "d2od0VBrCICB1K4p+C51D93xyQiva7xQcCne+TAnGNy9+gjQ/MyR8MRpwRLv5ikD\n" +
            "u0anJCN8pXo6IMglfMAsoton1J6o5/ae5uhC6caQU8bNUsCK570gpNfjkzo6rbP0\n" +
            "wQ==\n" +
            "-----END CERTIFICATE-----\n";

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();
        mServiceContext = mContext;
        mServiceContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(true);
        doReturn(Collections.singletonList(new ResolveInfo()))
                .when(getServices().packageManager).queryBroadcastReceiversAsUser(
                        any(Intent.class),
                        anyInt(),
                        any(UserHandle.class));

        // By default, pretend all users are running and unlocked.
        when(getServices().userManager.isUserUnlocked(anyInt())).thenReturn(true);

        initializeDpms();

        Mockito.reset(getServices().usageStatsManagerInternal);
        Mockito.reset(getServices().networkPolicyManagerInternal);
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin3, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(adminNoPerm, DpmMockContext.CALLER_UID);

        mAdmin1Context = new DpmMockContext(getServices(), mRealTestContext);
        mAdmin1Context.packageName = admin1.getPackageName();
        mAdmin1Context.applicationInfo = new ApplicationInfo();
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_UID;

        setUpUserManager();

        when(getServices().lockPatternUtils.hasSecureLockScreen()).thenReturn(true);
    }

    private TransferOwnershipMetadataManager getMockTransferMetadataManager() {
        return dpms.mTransferOwnershipMetadataManager;
    }

    @Override
    protected void tearDown() throws Exception {
        flushTasks();
        getMockTransferMetadataManager().deleteMetadataFile();
        super.tearDown();
    }

    private void initializeDpms() {
        // Need clearCallingIdentity() to pass permission checks.
        final long ident = mContext.binder.clearCallingIdentity();
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);
        dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
        dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);

        dpm = new DevicePolicyManagerTestable(mContext, dpms);

        mContext.binder.restoreCallingIdentity(ident);
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
        }).when(getServices().userManager).setApplicationRestrictions(
                anyString(), nullable(Bundle.class), any(UserHandle.class));

        // UM.getApplicationRestrictions() will read from appRestrictions.
        doAnswer(new Answer<Bundle>() {
            @Override
            public Bundle answer(InvocationOnMock invocation) throws Throwable {
                String pkg = (String) invocation.getArguments()[0];
                UserHandle user = (UserHandle) invocation.getArguments()[1];

                return appRestrictions.get(Pair.create(pkg, user));
            }
        }).when(getServices().userManager).getApplicationRestrictions(
                anyString(), any(UserHandle.class));

        // Add the first secondary user.
        getServices().addUser(DpmMockContext.CALLER_USER_HANDLE, 0);
    }

    private void setAsProfileOwner(ComponentName admin) {
        final long ident = mServiceContext.binder.clearCallingIdentity();

        mServiceContext.binder.callingUid =
                UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE, DpmMockContext.SYSTEM_UID);
        runAsCaller(mServiceContext, dpms, dpm -> {
            // PO needs to be a DA.
            dpm.setActiveAdmin(admin, /*replace=*/ false);
            // Fire!
            assertTrue(dpm.setProfileOwner(admin, "owner-name", DpmMockContext.CALLER_USER_HANDLE));
            // Check
            assertEquals(admin, dpm.getProfileOwnerAsUser(DpmMockContext.CALLER_USER_HANDLE));
        });

        mServiceContext.binder.restoreCallingIdentity(ident);
    }

    public void testHasNoFeature() throws Exception {
        when(getServices().packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(false);

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        new DevicePolicyManagerServiceTestable(getServices(), mContext);

        // If the device has no DPMS feature, it shouldn't register the local service.
        assertNull(LocalServices.getService(DevicePolicyManagerInternal.class));
    }

    public void testLoadAdminData() throws Exception {
        // Device owner in SYSTEM_USER
        setDeviceOwner();
        // Profile owner in CALLER_USER_HANDLE
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        setAsProfileOwner(admin2);
        // Active admin in CALLER_USER_HANDLE
        final int ANOTHER_UID = UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE, 1306);
        setUpPackageManagerForFakeAdmin(adminAnotherPackage, ANOTHER_UID, admin2);
        dpm.setActiveAdmin(adminAnotherPackage, /* replace =*/ false,
                DpmMockContext.CALLER_USER_HANDLE);
        assertTrue(dpm.isAdminActiveAsUser(adminAnotherPackage,
                DpmMockContext.CALLER_USER_HANDLE));

        initializeDpms();

        // Verify
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                MockUtils.checkApps(admin1.getPackageName()),
                eq(UserHandle.USER_SYSTEM));
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                MockUtils.checkApps(admin2.getPackageName(),
                        adminAnotherPackage.getPackageName()),
                eq(DpmMockContext.CALLER_USER_HANDLE));
        verify(getServices().usageStatsManagerInternal).onAdminDataAvailable();
        verify(getServices().networkPolicyManagerInternal).onAdminDataAvailable();
    }

    public void testLoadAdminData_noAdmins() throws Exception {
        final int ANOTHER_USER_ID = 15;
        getServices().addUser(ANOTHER_USER_ID, 0);

        initializeDpms();

        // Verify
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, ANOTHER_USER_ID);
        verify(getServices().usageStatsManagerInternal).onAdminDataAvailable();
        verify(getServices().networkPolicyManagerInternal).onAdminDataAvailable();
    }

    /**
     * Caller doesn't have proper permissions.
     */
    public void testSetActiveAdmin_SecurityException() {
        // 1. Failure cases.

        // Caller doesn't have MANAGE_DEVICE_ADMINS.
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, false));

        // Caller has MANAGE_DEVICE_ADMINS, but for different user.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, false, DpmMockContext.CALLER_USER_HANDLE + 1));
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

        verify(getServices().ipackageManager, times(1)).setApplicationEnabledSetting(
                eq(admin1.getPackageName()),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                eq(PackageManager.DONT_KILL_APP),
                eq(DpmMockContext.CALLER_USER_HANDLE),
                anyString());

        verify(getServices().usageStatsManagerInternal).onActiveAdminAdded(
                admin1.getPackageName(), DpmMockContext.CALLER_USER_HANDLE);

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
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        dpm.setActiveAdmin(admin2, /* replace =*/ false);

        // Now we have two admins.
        assertTrue(dpm.isAdminActive(admin1));
        assertTrue(dpm.isAdminActive(admin2));
        assertFalse(dpm.isAdminActive(admin3));

        // Admin2 was already enabled, so setApplicationEnabledSetting() shouldn't have called
        // again.  (times(1) because it was previously called for admin1)
        verify(getServices().ipackageManager, times(1)).setApplicationEnabledSetting(
                eq(admin1.getPackageName()),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                eq(PackageManager.DONT_KILL_APP),
                eq(DpmMockContext.CALLER_USER_HANDLE),
                anyString());

        // times(2) because it was previously called for admin1 which is in the same package
        // as admin2.
        verify(getServices().usageStatsManagerInternal, times(2)).onActiveAdminAdded(
                admin2.getPackageName(), DpmMockContext.CALLER_USER_HANDLE);

        // 4. Add the same admin1 again without replace, which should throw.
        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, /* replace =*/ false));

        // 5. Add the same admin1 again with replace, which should succeed.
        dpm.setActiveAdmin(admin1, /* replace =*/ true);

        // TODO make sure it's replaced.

        // 6. Test getActiveAdmins()
        List<ComponentName> admins = dpm.getActiveAdmins();
        assertEquals(2, admins.size());
        assertEquals(admin1, admins.get(0));
        assertEquals(admin2, admins.get(1));

        // There shouldn't be any callback to UsageStatsManagerInternal when the admin is being
        // replaced
        verifyNoMoreInteractions(getServices().usageStatsManagerInternal);

        // Another user has no admins.
        mContext.callerPermissions.add("android.permission.INTERACT_ACROSS_USERS_FULL");

        assertEquals(0, DpmTestUtils.getListSizeAllowingNull(
                dpm.getActiveAdminsAsUser(DpmMockContext.CALLER_USER_HANDLE + 1)));

        mContext.callerPermissions.remove("android.permission.INTERACT_ACROSS_USERS_FULL");
    }

    public void testSetActiveAdmin_multiUsers() throws Exception {

        final int ANOTHER_USER_ID = 100;
        final int ANOTHER_ADMIN_UID = UserHandle.getUid(ANOTHER_USER_ID, 20456);

        getServices().addUser(ANOTHER_USER_ID, 0); // Add one more user.

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
        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, /* replace =*/ false));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setActiveAdmin} when the admin isn't protected with
     * BIND_DEVICE_ADMIN.
     */
    public void testSetActiveAdmin_permissionCheck() throws Exception {
        // 1. Make sure the caller has proper permissions.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        assertExpectException(IllegalArgumentException.class,
                /* messageRegex= */ permission.BIND_DEVICE_ADMIN,
                () -> dpm.setActiveAdmin(adminNoPerm, /* replace =*/ false));
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
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpms.removeActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE + 1));

        // Try to remove active admin with a different caller userid should fail too, without
        // having MANAGE_DEVICE_ADMINS.
        mContext.callerPermissions.clear();

        // Change the caller, and call into DPMS directly with a different user-id.

        mContext.binder.callingUid = 1234567;
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpms.removeActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));
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
        when(getServices().userManager.isUserUnlocked(eq(DpmMockContext.CALLER_USER_HANDLE)))
                .thenReturn(false);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "User must be running and unlocked",
                () -> dpm.removeActiveAdmin(admin1));

        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));
        verify(getServices().usageStatsManagerInternal, times(0)).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);

        // 2. User unlocked.
        when(getServices().userManager.isUserUnlocked(eq(DpmMockContext.CALLER_USER_HANDLE)))
                .thenReturn(true);

        dpm.removeActiveAdmin(admin1);
        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);
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
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);

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
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);

        // Again broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));

        // TODO Check other internal calls.
    }

    public void testRemoveActiveAdmin_multipleAdminsInUser() {
        // Need MANAGE_DEVICE_ADMINS for setActiveAdmin.  We'll remove it later.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin1.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // Add admin2.
        dpm.setActiveAdmin(admin2, /* replace =*/ false);

        assertTrue(dpm.isAdminActive(admin2));
        assertFalse(dpm.isRemovingAdmin(admin2, DpmMockContext.CALLER_USER_HANDLE));

        // Broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
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
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                MockUtils.checkApps(admin2.getPackageName()),
                eq(DpmMockContext.CALLER_USER_HANDLE));

        // Again broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(3)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(DpmMockContext.CALLER_USER_HANDLE));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#forceRemoveActiveAdmin(ComponentName, int)}
     */
    public void testForceRemoveActiveAdmin() throws Exception {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin.
        setupPackageInPackageManager(admin1.getPackageName(),
                /* userId= */ DpmMockContext.CALLER_USER_HANDLE,
                /* appId= */ 10138,
                /* flags= */ ApplicationInfo.FLAG_TEST_ONLY);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.isAdminActive(admin1));

        // Calling from a non-shell uid should fail with a SecurityException
        mContext.binder.callingUid = 123456;
        assertExpectException(SecurityException.class,
                /* messageRegex =*/ "Non-shell user attempted to call",
                () -> dpms.forceRemoveActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        mContext.binder.callingUid = Process.SHELL_UID;
        dpms.forceRemoveActiveAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE);

        mContext.callerPermissions.add(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        // Verify
        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);
    }

    /**
     * Test for: @{link DevicePolicyManager#setActivePasswordState}
     *
     * Validates that when the password for a user changes, the notification broadcast intent
     * {@link DeviceAdminReceiver#ACTION_PASSWORD_CHANGED} is sent to managed profile owners, in
     * addition to ones in the original user.
     */
    public void testSetActivePasswordState_sendToProfiles() throws Exception {
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);

        final int MANAGED_PROFILE_USER_ID = 78;
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(MANAGED_PROFILE_USER_ID, DpmMockContext.SYSTEM_UID);

        // Setup device owner.
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.packageName = admin1.getPackageName();
        setupDeviceOwner();

        // Add a managed profile belonging to the system user.
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Change the parent user's password.
        dpm.reportPasswordChanged(UserHandle.USER_SYSTEM);

        // Both the device owner and the managed profile owner should receive this broadcast.
        final Intent intent = new Intent(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED);
        intent.setComponent(admin1);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(UserHandle.USER_SYSTEM));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(MANAGED_PROFILE_USER_ID));
    }

    /**
     * Test for: @{link DevicePolicyManager#setActivePasswordState}
     *
     * Validates that when the password for a managed profile changes, the notification broadcast
     * intent {@link DeviceAdminReceiver#ACTION_PASSWORD_CHANGED} is only sent to the profile, not
     * its parent.
     */
    public void testSetActivePasswordState_notSentToParent() throws Exception {
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);

        final int MANAGED_PROFILE_USER_ID = 78;
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(MANAGED_PROFILE_USER_ID, DpmMockContext.SYSTEM_UID);

        // Setup device owner.
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.packageName = admin1.getPackageName();
        doReturn(true).when(getServices().lockPatternUtils)
                .isSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID);
        setupDeviceOwner();

        // Add a managed profile belonging to the system user.
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Change the profile's password.
        dpm.reportPasswordChanged(MANAGED_PROFILE_USER_ID);

        // Both the device owner and the managed profile owner should receive this broadcast.
        final Intent intent = new Intent(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED);
        intent.setComponent(admin1);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(MANAGED_PROFILE_USER_ID));

        verify(mContext.spiedContext, never()).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(MANAGED_PROFILE_USER_ID));
    }

    /**
     * Test for: {@link DevicePolicyManager#setDeviceOwner} DO on system user installs successfully.
     */
    public void testSetDeviceOwner() throws Exception {
        setDeviceOwner();

        // Try to set a profile owner on the same user, which should fail.
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin2, /* refreshing= */ true, UserHandle.USER_SYSTEM);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "already has a device owner",
                () -> dpm.setProfileOwner(admin2, "owner-name", UserHandle.USER_SYSTEM));

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
        verify(getServices().iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        // TODO We should check if the caller has called clearCallerIdentity().
        verify(getServices().ibackupManager, times(1)).setBackupServiceActive(
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

        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                dpm::getDeviceOwnerComponentOnAnyUser);
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                dpm::getDeviceOwnerUserId);
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                dpm::getDeviceOwnerNameOnAnyUser);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        // Still no MANAGE_USERS.
        assertFalse(dpm.isDeviceOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
        assertEquals(null, dpm.getDeviceOwnerComponentOnCallingUser());

        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                dpm::getDeviceOwnerComponentOnAnyUser);
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                dpm::getDeviceOwnerUserId);
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                dpm::getDeviceOwnerNameOnAnyUser);

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

        assertExpectException(IllegalArgumentException.class,
                /* messageRegex= */ "Invalid component",
                () -> dpm.setDeviceOwner(new ComponentName("a.b.c", ".def")));
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
        verify(getServices().iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        when(getServices().userManager.hasUserRestriction(eq(UserManager.DISALLOW_ADD_USER),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM))).thenReturn(true);

        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isRemovingAdmin(admin1, UserHandle.USER_SYSTEM));

        // Set up other mocks.
        when(getServices().userManager.getUserRestrictions()).thenReturn(new Bundle());

        // Now call clear.
        doReturn(DpmMockContext.CALLER_SYSTEM_USER_UID).when(getServices().packageManager).
                getPackageUidAsUser(eq(admin1.getPackageName()), anyInt());

        // But first pretend the user is locked.  Then it should fail.
        when(getServices().userManager.isUserUnlocked(anyInt())).thenReturn(false);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "User must be running and unlocked",
                () -> dpm.clearDeviceOwnerApp(admin1.getPackageName()));

        when(getServices().userManager.isUserUnlocked(anyInt())).thenReturn(true);
        reset(getServices().userManagerInternal);
        dpm.clearDeviceOwnerApp(admin1.getPackageName());

        // Now DO shouldn't be set.
        assertNull(dpm.getDeviceOwnerComponentOnAnyUser());

        verify(getServices().userManager).setUserRestriction(eq(UserManager.DISALLOW_ADD_USER),
                eq(false),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));

        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                eq(null),
                eq(true), eq(CAMERA_NOT_DISABLED));

        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, UserHandle.USER_SYSTEM);

        assertFalse(dpm.isAdminActiveAsUser(admin1, UserHandle.USER_SYSTEM));

        // ACTION_DEVICE_OWNER_CHANGED should be sent twice, once for setting the device owner
        // and once for clearing it.
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
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
        verify(getServices().iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());

        // Now call clear from the secondary user, which should throw.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Now call clear.
        doReturn(DpmMockContext.CALLER_UID).when(getServices().packageManager).getPackageUidAsUser(
                eq(admin1.getPackageName()),
                anyInt());
        assertExpectException(SecurityException.class,
                /* messageRegex =*/ "clearDeviceOwner can only be called by the device owner",
                () -> dpm.clearDeviceOwnerApp(admin1.getPackageName()));

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
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        runAsCaller(mServiceContext, dpms, dpm -> {
            dpm.setActiveAdmin(admin2, /* refreshing= */ true, DpmMockContext.CALLER_USER_HANDLE);
            assertExpectException(IllegalStateException.class,
                    /* messageRegex= */ "already has a profile owner",
                    () -> dpm.setDeviceOwner(admin2, "owner-name",
                            DpmMockContext.CALLER_USER_HANDLE));
        });
    }

    public void testClearProfileOwner() throws Exception {
        setAsProfileOwner(admin1);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        assertTrue(dpm.isProfileOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isRemovingAdmin(admin1, DpmMockContext.CALLER_USER_HANDLE));

        // First try when the user is locked, which should fail.
        when(getServices().userManager.isUserUnlocked(anyInt()))
                .thenReturn(false);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "User must be running and unlocked",
                () -> dpm.clearProfileOwner(admin1));

        // Clear, really.
        when(getServices().userManager.isUserUnlocked(anyInt())).thenReturn(true);
        dpm.clearProfileOwner(admin1);

        // Check
        assertFalse(dpm.isProfileOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isAdminActiveAsUser(admin1, DpmMockContext.CALLER_USER_HANDLE));
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, DpmMockContext.CALLER_USER_HANDLE);
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

        getServices().addUser(ANOTHER_USER_ID, 0); // Add one more user.

        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);

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
        getServices().setUserRunning(DpmMockContext.CALLER_USER_HANDLE, true);
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
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        checkDeviceOwnerWithMultipleDeviceAdmins();

        // Overwrite the device owner setting and clears the clas name.
        dpms.mOwners.setDeviceOwner(
                new ComponentName(admin2.getPackageName(), ""),
                "owner-name", DpmMockContext.CALLER_USER_HANDLE);
        dpms.mOwners.writeDeviceOwner();

        // Make sure the DO component name doesn't have a class name.
        assertEquals("", dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false).getClassName());

        // Then create a new DPMS to have it load the settings from files.
        when(getServices().userManager.getUserRestrictions(any(UserHandle.class)))
                .thenReturn(new Bundle());
        initializeDpms();

        // Now the DO component name is a full name.
        // *BUT* because both admin1 and admin2 belong to the same package, we think admin1 is the
        // DO.
        assertEquals(admin1, dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false));
    }

    public void testSetGetApplicationRestriction() {
        setAsProfileOwner(admin1);
        mContext.packageName = admin1.getPackageName();

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

    /**
     * Setup a package in the package manager mock for {@link DpmMockContext#CALLER_USER_HANDLE}.
     * Useful for faking installed applications.
     *
     * @param packageName the name of the package to be setup
     * @param appId the application ID to be given to the package
     * @return the UID of the package as known by the mock package manager
     */
    private int setupPackageInPackageManager(final String packageName, final int appId)
            throws Exception {
        return setupPackageInPackageManager(packageName, DpmMockContext.CALLER_USER_HANDLE, appId,
                ApplicationInfo.FLAG_HAS_CODE);
    }

    /**
     * Setup a package in the package manager mock. Useful for faking installed applications.
     *
     * @param packageName the name of the package to be setup
     * @param userId the user id where the package will be "installed"
     * @param appId the application ID to be given to the package
     * @param flags flags to set in the ApplicationInfo for this package
     * @return the UID of the package as known by the mock package manager
     */
    private int setupPackageInPackageManager(final String packageName, int userId, final int appId,
            int flags) throws Exception {
        final int uid = UserHandle.getUid(userId, appId);
        // Make the PackageManager return the package instead of throwing NameNotFoundException
        final PackageInfo pi = new PackageInfo();
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.flags = flags;
        doReturn(pi).when(getServices().ipackageManager).getPackageInfo(
                eq(packageName),
                anyInt(),
                eq(userId));
        doReturn(pi.applicationInfo).when(getServices().ipackageManager).getApplicationInfo(
                eq(packageName),
                anyInt(),
                eq(userId));
        doReturn(true).when(getServices().ipackageManager).isPackageAvailable(packageName, userId);
        // Setup application UID with the PackageManager
        doReturn(uid).when(getServices().packageManager).getPackageUidAsUser(
                eq(packageName),
                eq(userId));
        // Associate packageName to uid
        doReturn(packageName).when(getServices().ipackageManager).getNameForUid(eq(uid));
        doReturn(new String[]{packageName})
                .when(getServices().ipackageManager).getPackagesForUid(eq(uid));
        return uid;
    }

    public void testCertificateDisclosure() throws Exception {
        final int userId = DpmMockContext.CALLER_USER_HANDLE;
        final UserHandle user = UserHandle.of(userId);

        mContext.applicationInfo = new ApplicationInfo();
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.packageName = "com.android.frameworks.servicestests";
        getServices().addPackageContext(user, mContext);
        when(mContext.resources.getColor(anyInt(), anyObject())).thenReturn(Color.WHITE);

        StringParceledListSlice oneCert = asSlice(new String[] {"1"});
        StringParceledListSlice fourCerts = asSlice(new String[] {"1", "2", "3", "4"});

        final String TEST_STRING = "Test for exactly 2 certs out of 4";
        doReturn(TEST_STRING).when(mContext.resources).getQuantityText(anyInt(), eq(2));

        // Given that we have exactly one certificate installed,
        when(getServices().keyChainConnection.getService().getUserCaAliases()).thenReturn(oneCert);
        // when that certificate is approved,
        dpms.approveCaCert(oneCert.getList().get(0), userId, true);
        // a notification should not be shown.
        verify(getServices().notificationManager, timeout(1000))
                .cancelAsUser(anyString(), anyInt(), eq(user));

        // Given that we have four certificates installed,
        when(getServices().keyChainConnection.getService().getUserCaAliases()).thenReturn(fourCerts);
        // when two of them are approved (one of them approved twice hence no action),
        dpms.approveCaCert(fourCerts.getList().get(0), userId, true);
        dpms.approveCaCert(fourCerts.getList().get(1), userId, true);
        // a notification should be shown saying that there are two certificates left to approve.
        verify(getServices().notificationManager, timeout(1000))
                .notifyAsUser(anyString(), anyInt(), argThat(
                        new BaseMatcher<Notification>() {
                            @Override
                            public boolean matches(Object item) {
                                final Notification noti = (Notification) item;
                                return TEST_STRING.equals(
                                        noti.extras.getString(Notification.EXTRA_TITLE));
                            }
                            @Override
                            public void describeTo(Description description) {
                                description.appendText(
                                        "Notification{title=\"" + TEST_STRING + "\"}");
                            }
                        }), eq(user));
    }

    /**
     * Simple test for delegate set/get and general delegation. Tests verifying that delegated
     * privileges can acually be exercised by a delegate are not covered here.
     */
    public void testDelegation() throws Exception {
        setAsProfileOwner(admin1);

        final int userHandle = DpmMockContext.CALLER_USER_HANDLE;

        // Given two packages
        final String CERT_DELEGATE = "com.delegate.certs";
        final String RESTRICTIONS_DELEGATE = "com.delegate.apprestrictions";
        final int CERT_DELEGATE_UID = setupPackageInPackageManager(CERT_DELEGATE, 20988);
        final int RESTRICTIONS_DELEGATE_UID = setupPackageInPackageManager(RESTRICTIONS_DELEGATE,
                20989);

        // On delegation
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        dpm.setCertInstallerPackage(admin1, CERT_DELEGATE);
        dpm.setApplicationRestrictionsManagingPackage(admin1, RESTRICTIONS_DELEGATE);

        // DPMS correctly stores and retrieves the delegates
        DevicePolicyManagerService.DevicePolicyData policy = dpms.mUserData.get(userHandle);
        assertEquals(2, policy.mDelegationMap.size());
        MoreAsserts.assertContentsInAnyOrder(policy.mDelegationMap.get(CERT_DELEGATE),
            DELEGATION_CERT_INSTALL);
        MoreAsserts.assertContentsInAnyOrder(dpm.getDelegatedScopes(admin1, CERT_DELEGATE),
            DELEGATION_CERT_INSTALL);
        assertEquals(CERT_DELEGATE, dpm.getCertInstallerPackage(admin1));
        MoreAsserts.assertContentsInAnyOrder(policy.mDelegationMap.get(RESTRICTIONS_DELEGATE),
            DELEGATION_APP_RESTRICTIONS);
        MoreAsserts.assertContentsInAnyOrder(dpm.getDelegatedScopes(admin1, RESTRICTIONS_DELEGATE),
            DELEGATION_APP_RESTRICTIONS);
        assertEquals(RESTRICTIONS_DELEGATE, dpm.getApplicationRestrictionsManagingPackage(admin1));

        // On calling install certificate APIs from an unauthorized process
        mContext.binder.callingUid = RESTRICTIONS_DELEGATE_UID;
        mContext.packageName = RESTRICTIONS_DELEGATE;

        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.installCaCert(null, null));

        // On calling install certificate APIs from an authorized process
        mContext.binder.callingUid = CERT_DELEGATE_UID;
        mContext.packageName = CERT_DELEGATE;

        // DPMS executes without a SecurityException
        try {
            dpm.installCaCert(null, null);
        } catch (SecurityException unexpected) {
            fail("Threw SecurityException on authorized access");
        } catch (NullPointerException expected) {
        }

        // On removing a delegate
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        dpm.setCertInstallerPackage(admin1, null);

        // DPMS does not allow access to ex-delegate
        mContext.binder.callingUid = CERT_DELEGATE_UID;
        mContext.packageName = CERT_DELEGATE;
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.installCaCert(null, null));

        // But still allows access to other existing delegates
        mContext.binder.callingUid = RESTRICTIONS_DELEGATE_UID;
        mContext.packageName = RESTRICTIONS_DELEGATE;
        try {
            dpm.getApplicationRestrictions(null, "pkg");
        } catch (SecurityException expected) {
            fail("Threw SecurityException on authorized access");
        }
    }

    public void testApplicationRestrictionsManagingApp() throws Exception {
        setAsProfileOwner(admin1);

        final String nonExistAppRestrictionsManagerPackage = "com.google.app.restrictions.manager2";
        final String appRestrictionsManagerPackage = "com.google.app.restrictions.manager";
        final String nonDelegateExceptionMessageRegex =
                "Caller with uid \\d+ is not a delegate of scope delegation-app-restrictions.";
        final int appRestrictionsManagerAppId = 20987;
        final int appRestrictionsManagerUid = setupPackageInPackageManager(
                appRestrictionsManagerPackage, appRestrictionsManagerAppId);

        // appRestrictionsManager package shouldn't be able to manage restrictions as the PO hasn't
        // delegated that permission yet.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        assertFalse(dpm.isCallerApplicationRestrictionsManagingPackage());
        final Bundle rest = new Bundle();
        rest.putString("KEY_STRING", "Foo1");
        assertExpectException(SecurityException.class, nonDelegateExceptionMessageRegex,
                () -> dpm.setApplicationRestrictions(null, "pkg1", rest));

        // Check via the profile owner that no restrictions were set.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        assertEquals(0, dpm.getApplicationRestrictions(admin1, "pkg1").size());

        // Check the API does not allow setting a non-existent package
        assertExpectException(PackageManager.NameNotFoundException.class,
                /* messageRegex= */ nonExistAppRestrictionsManagerPackage,
                () -> dpm.setApplicationRestrictionsManagingPackage(
                        admin1, nonExistAppRestrictionsManagerPackage));

        // Let appRestrictionsManagerPackage manage app restrictions
        dpm.setApplicationRestrictionsManagingPackage(admin1, appRestrictionsManagerPackage);
        assertEquals(appRestrictionsManagerPackage,
                dpm.getApplicationRestrictionsManagingPackage(admin1));

        // Now that package should be able to set and retrieve app restrictions.
        mContext.binder.callingUid = appRestrictionsManagerUid;
        mContext.packageName = appRestrictionsManagerPackage;
        assertTrue(dpm.isCallerApplicationRestrictionsManagingPackage());
        dpm.setApplicationRestrictions(null, "pkg1", rest);
        Bundle returned = dpm.getApplicationRestrictions(null, "pkg1");
        assertEquals(1, returned.size(), 1);
        assertEquals("Foo1", returned.get("KEY_STRING"));

        // The same app running on a separate user shouldn't be able to manage app restrictions.
        mContext.binder.callingUid = UserHandle.getUid(
                UserHandle.USER_SYSTEM, appRestrictionsManagerAppId);
        assertFalse(dpm.isCallerApplicationRestrictionsManagingPackage());
        assertExpectException(SecurityException.class, nonDelegateExceptionMessageRegex,
                () -> dpm.setApplicationRestrictions(null, "pkg1", rest));

        // The DPM is still able to manage app restrictions, even if it allowed another app to do it
        // too.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        assertEquals(returned, dpm.getApplicationRestrictions(admin1, "pkg1"));
        dpm.setApplicationRestrictions(admin1, "pkg1", null);
        assertEquals(0, dpm.getApplicationRestrictions(admin1, "pkg1").size());

        // Removing the ability for the package to manage app restrictions.
        dpm.setApplicationRestrictionsManagingPackage(admin1, null);
        assertNull(dpm.getApplicationRestrictionsManagingPackage(admin1));
        mContext.binder.callingUid = appRestrictionsManagerUid;
        mContext.packageName = appRestrictionsManagerPackage;
        assertFalse(dpm.isCallerApplicationRestrictionsManagingPackage());
        assertExpectException(SecurityException.class, nonDelegateExceptionMessageRegex,
                () -> dpm.setApplicationRestrictions(null, "pkg1", null));
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

        // Check that the user restrictions that are enabled by default are set. Then unset them.
        final String[] defaultRestrictions = UserRestrictionsUtils
                .getDefaultEnabledForDeviceOwner().toArray(new String[0]);
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(defaultRestrictions),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(defaultRestrictions),
                dpm.getUserRestrictions(admin1)
        );
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(defaultRestrictions),
                eq(true) /* isDeviceOwner */,
                eq(CAMERA_NOT_DISABLED)
        );
        reset(getServices().userManagerInternal);

        for (String restriction : defaultRestrictions) {
            dpm.clearUserRestriction(admin1, restriction);
        }

        assertNoDeviceOwnerRestrictions();
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_OUTGOING_CALLS,
                        UserManager.DISALLOW_ADD_USER),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

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
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        assertNoDeviceOwnerRestrictions();

        // DISALLOW_ADJUST_VOLUME and DISALLOW_UNMUTE_MICROPHONE are PO restrictions, but when
        // DO sets them, the scope is global.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);
        reset(getServices().userManagerInternal);
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);
        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        reset(getServices().userManagerInternal);

        // More tests.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_FUN);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_ADD_USER),
                eq(true), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        dpm.setCameraDisabled(admin1, true);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                // DISALLOW_CAMERA will be applied to both local and global.
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_ADD_USER),
                eq(true), eq(CAMERA_DISABLED_GLOBALLY));
        reset(getServices().userManagerInternal);
    }

    public void testDaDisallowedPolicies_SecurityException() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false, UserHandle.USER_SYSTEM);

        boolean originalCameraDisabled = dpm.getCameraDisabled(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setCameraDisabled(admin1, true));
        assertEquals(originalCameraDisabled, dpm.getCameraDisabled(admin1));

        int originalKeyguardDisabledFeatures = dpm.getKeyguardDisabledFeatures(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setKeyguardDisabledFeatures(admin1,
                        DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL));
        assertEquals(originalKeyguardDisabledFeatures, dpm.getKeyguardDisabledFeatures(admin1));

        long originalPasswordExpirationTimeout = dpm.getPasswordExpirationTimeout(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setPasswordExpirationTimeout(admin1, 1234));
        assertEquals(originalPasswordExpirationTimeout, dpm.getPasswordExpirationTimeout(admin1));

        int originalPasswordQuality = dpm.getPasswordQuality(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC));
        assertEquals(originalPasswordQuality, dpm.getPasswordQuality(admin1));
    }

    public void testSetUserRestriction_asPo() {
        setAsProfileOwner(admin1);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(DpmMockContext.CALLER_USER_HANDLE)
                        .ensureUserRestrictions()
        );

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
                eq(false), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_OUTGOING_CALLS),
                eq(false), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

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
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                eq(false), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

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
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                eq(false), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

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
        reset(getServices().userManagerInternal);
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE),
                eq(false), eq(CAMERA_NOT_DISABLED));
        reset(getServices().userManagerInternal);

        dpm.setCameraDisabled(admin1, true);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(DpmMockContext.CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE),
                eq(false), eq(CAMERA_DISABLED_LOCALLY));
        reset(getServices().userManagerInternal);

        // TODO Make sure restrictions are written to the file.
    }


    public void testDefaultEnabledUserRestrictions() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // First, set DO.

        // Call from a process on the system user.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        dpm.setActiveAdmin(admin1, /* replace =*/ false, UserHandle.USER_SYSTEM);
        assertTrue(dpm.setDeviceOwner(admin1, "owner-name",
                UserHandle.USER_SYSTEM));

        // Check that the user restrictions that are enabled by default are set. Then unset them.
        String[] defaultRestrictions = UserRestrictionsUtils
                .getDefaultEnabledForDeviceOwner().toArray(new String[0]);
        assertTrue(defaultRestrictions.length > 0);
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(defaultRestrictions),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(defaultRestrictions),
                dpm.getUserRestrictions(admin1)
        );
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(defaultRestrictions),
                eq(true) /* isDeviceOwner */,
                eq(CAMERA_NOT_DISABLED)
        );
        reset(getServices().userManagerInternal);

        for (String restriction : defaultRestrictions) {
            dpm.clearUserRestriction(admin1, restriction);
        }

        assertNoDeviceOwnerRestrictions();

        // Initialize DPMS again and check that the user restriction wasn't enabled again.
        reset(getServices().userManagerInternal);
        initializeDpms();
        assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
        assertNotNull(dpms.getDeviceOwnerAdminLocked());

        assertNoDeviceOwnerRestrictions();

        // Add a new restriction to the default set, initialize DPMS, and check that the restriction
        // is set as it wasn't enabled during setDeviceOwner.
        final String newDefaultEnabledRestriction = UserManager.DISALLOW_REMOVE_MANAGED_PROFILE;
        assertFalse(UserRestrictionsUtils
                .getDefaultEnabledForDeviceOwner().contains(newDefaultEnabledRestriction));
        UserRestrictionsUtils
                .getDefaultEnabledForDeviceOwner().add(newDefaultEnabledRestriction);
        try {
            reset(getServices().userManagerInternal);
            initializeDpms();
            assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertNotNull(dpms.getDeviceOwnerAdminLocked());

            DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(newDefaultEnabledRestriction),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
            );
            DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(newDefaultEnabledRestriction),
                dpm.getUserRestrictions(admin1)
            );
            verify(getServices().userManagerInternal, atLeast(1)).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(newDefaultEnabledRestriction),
                eq(true) /* isDeviceOwner */,
                eq(CAMERA_NOT_DISABLED)
            );
            reset(getServices().userManagerInternal);

            // Remove the restriction.
            dpm.clearUserRestriction(admin1, newDefaultEnabledRestriction);

            // Initialize DPMS again. The restriction shouldn't be enabled for a second time.
            initializeDpms();
            assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
            assertNotNull(dpms.getDeviceOwnerAdminLocked());
            assertNoDeviceOwnerRestrictions();
        } finally {
            UserRestrictionsUtils
                .getDefaultEnabledForDeviceOwner().remove(newDefaultEnabledRestriction);
        }
    }

    private void assertNoDeviceOwnerRestrictions() {
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getDeviceOwnerAdminLocked().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpm.getUserRestrictions(admin1)
        );
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
        assertExpectException(SecurityException.class, /* messageRegex= */ "No active admin",
                () -> dpm.getWifiMacAddress(admin1));

        // DO needs to be an DA.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertTrue(dpm.isAdminActive(admin1));

        // Test 2. Caller has DA, but not DO.
        assertExpectException(SecurityException.class, /* messageRegex= */ NOT_DEVICE_OWNER_MSG,
                () -> dpm.getWifiMacAddress(admin1));

        // Test 3. Caller has PO, but not DO.
        assertTrue(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM));
        assertExpectException(SecurityException.class, /* messageRegex= */ NOT_DEVICE_OWNER_MSG,
                () -> dpm.getWifiMacAddress(admin1));

        // Remove PO.
        dpm.clearProfileOwner(admin1);
        dpm.setActiveAdmin(admin1, false);
        // Test 4, Caller is DO now.
        assertTrue(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM));

        // 4-1.  But WifiManager is not ready.
        assertNull(dpm.getWifiMacAddress(admin1));

        // 4-2.  When WifiManager returns an empty array, dpm should also output null.
        when(getServices().wifiManager.getFactoryMacAddresses()).thenReturn(new String[0]);
        assertNull(dpm.getWifiMacAddress(admin1));

        // 4-3. With a real MAC address.
        final String[] macAddresses = new String[]{"11:22:33:44:55:66"};
        when(getServices().wifiManager.getFactoryMacAddresses()).thenReturn(macAddresses);
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
        assertExpectException(SecurityException.class, /* messageRegex= */ NOT_DEVICE_OWNER_MSG,
                () -> dpm.reboot(admin1));

        // Set admin1 as PO.
        assertTrue(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM));
        assertExpectException(SecurityException.class, /* messageRegex= */ NOT_DEVICE_OWNER_MSG,
                () -> dpm.reboot(admin1));

        // Remove PO and add DO.
        dpm.clearProfileOwner(admin1);
        dpm.setActiveAdmin(admin1, false);
        assertTrue(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM));

        // admin1 is DO.
        // Set current call state of device to ringing.
        when(getServices().telephonyManager.getCallState())
                .thenReturn(TelephonyManager.CALL_STATE_RINGING);
        assertExpectException(IllegalStateException.class, /* messageRegex= */ ONGOING_CALL_MSG,
                () -> dpm.reboot(admin1));

        // Set current call state of device to dialing/active.
        when(getServices().telephonyManager.getCallState())
                .thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);
        assertExpectException(IllegalStateException.class, /* messageRegex= */ ONGOING_CALL_MSG,
                () -> dpm.reboot(admin1));

        // Set current call state of device to idle.
        when(getServices().telephonyManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_IDLE);
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
            assertExpectException(SecurityException.class, /* messageRegex= */ "message for user",
                    () -> dpm.getShortSupportMessageForUser(admin1,
                            DpmMockContext.CALLER_USER_HANDLE));
            assertExpectException(SecurityException.class, /* messageRegex= */ "message for user",
                    () -> dpm.getLongSupportMessageForUser(admin1,
                        DpmMockContext.CALLER_USER_HANDLE));
        }

        // Can't set message for admin in another uid.
        {
            mContext.binder.callingUid = DpmMockContext.CALLER_UID + 1;
            assertExpectException(SecurityException.class,
                    /* messageRegex= */ "is not owned by uid",
                    () -> dpm.setShortSupportMessage(admin1, "Some text"));
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

    public void testSetGetMeteredDataDisabledPackages() throws Exception {
        setAsProfileOwner(admin1);

        final ArrayList<String> emptyList = new ArrayList<>();
        assertEquals(emptyList, dpm.getMeteredDataDisabledPackages(admin1));

        // Setup
        final ArrayList<String> pkgsToRestrict = new ArrayList<>();
        final String package1 = "com.example.one";
        final String package2 = "com.example.two";
        pkgsToRestrict.add(package1);
        pkgsToRestrict.add(package2);
        setupPackageInPackageManager(package1, DpmMockContext.CALLER_USER_HANDLE, 123, 0);
        setupPackageInPackageManager(package2, DpmMockContext.CALLER_USER_HANDLE, 456, 0);
        List<String> excludedPkgs = dpm.setMeteredDataDisabledPackages(admin1, pkgsToRestrict);

        // Verify
        assertEquals(emptyList, excludedPkgs);
        assertEquals(pkgsToRestrict, dpm.getMeteredDataDisabledPackages(admin1));
        verify(getServices().networkPolicyManagerInternal).setMeteredRestrictedPackages(
                MockUtils.checkApps(pkgsToRestrict.toArray(new String[0])),
                eq(DpmMockContext.CALLER_USER_HANDLE));

        // Setup
        pkgsToRestrict.remove(package1);
        excludedPkgs = dpm.setMeteredDataDisabledPackages(admin1, pkgsToRestrict);

        // Verify
        assertEquals(emptyList, excludedPkgs);
        assertEquals(pkgsToRestrict, dpm.getMeteredDataDisabledPackages(admin1));
        verify(getServices().networkPolicyManagerInternal).setMeteredRestrictedPackages(
                MockUtils.checkApps(pkgsToRestrict.toArray(new String[0])),
                eq(DpmMockContext.CALLER_USER_HANDLE));
    }

    public void testSetGetMeteredDataDisabledPackages_deviceAdmin() {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        dpm.setActiveAdmin(admin1, true);
        assertTrue(dpm.isAdminActive(admin1));
        mContext.callerPermissions.remove(permission.MANAGE_DEVICE_ADMINS);

        assertExpectException(SecurityException.class,  /* messageRegex= */ NOT_PROFILE_OWNER_MSG,
                () -> dpm.setMeteredDataDisabledPackages(admin1, new ArrayList<>()));
        assertExpectException(SecurityException.class,  /* messageRegex= */ NOT_PROFILE_OWNER_MSG,
                () -> dpm.getMeteredDataDisabledPackages(admin1));
    }

    public void testIsMeteredDataDisabledForUserPackage() throws Exception {
        setAsProfileOwner(admin1);

        // Setup
        final ArrayList<String> emptyList = new ArrayList<>();
        final ArrayList<String> pkgsToRestrict = new ArrayList<>();
        final String package1 = "com.example.one";
        final String package2 = "com.example.two";
        final String package3 = "com.example.three";
        pkgsToRestrict.add(package1);
        pkgsToRestrict.add(package2);
        setupPackageInPackageManager(package1, DpmMockContext.CALLER_USER_HANDLE, 123, 0);
        setupPackageInPackageManager(package2, DpmMockContext.CALLER_USER_HANDLE, 456, 0);
        List<String> excludedPkgs = dpm.setMeteredDataDisabledPackages(admin1, pkgsToRestrict);

        // Verify
        assertEquals(emptyList, excludedPkgs);
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(package1 + "should be restricted",
                dpm.isMeteredDataDisabledPackageForUser(admin1, package1,
                        DpmMockContext.CALLER_USER_HANDLE));
        assertTrue(package2 + "should be restricted",
                dpm.isMeteredDataDisabledPackageForUser(admin1, package2,
                        DpmMockContext.CALLER_USER_HANDLE));
        assertFalse(package3 + "should not be restricted",
                dpm.isMeteredDataDisabledPackageForUser(admin1, package3,
                        DpmMockContext.CALLER_USER_HANDLE));
    }

    public void testIsMeteredDataDisabledForUserPackage_nonSystemUidCaller() throws Exception {
        setAsProfileOwner(admin1);
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Only the system can query restricted pkgs",
                () -> dpm.isMeteredDataDisabledPackageForUser(
                        admin1, "com.example.one", DpmMockContext.CALLER_USER_HANDLE));
        dpm.clearProfileOwner(admin1);

        setDeviceOwner();
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Only the system can query restricted pkgs",
                () -> dpm.isMeteredDataDisabledPackageForUser(
                        admin1, "com.example.one", DpmMockContext.CALLER_USER_HANDLE));
        clearDeviceOwner();
    }

    public void testCreateAdminSupportIntent() throws Exception {
        // Setup device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // Nonexisting permission returns null
        Intent intent = dpm.createAdminSupportIntent("disallow_nothing");
        assertNull(intent);

        // Existing permission that is not set returns null
        intent = dpm.createAdminSupportIntent(UserManager.DISALLOW_ADJUST_VOLUME);
        assertNull(intent);

        // Existing permission that is not set by device/profile owner returns null
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_ADJUST_VOLUME),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(true);
        intent = dpm.createAdminSupportIntent(UserManager.DISALLOW_ADJUST_VOLUME);
        assertNull(intent);

        // Permission that is set by device owner returns correct intent
        when(getServices().userManager.getUserRestrictionSource(
                eq(UserManager.DISALLOW_ADJUST_VOLUME),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        intent = dpm.createAdminSupportIntent(UserManager.DISALLOW_ADJUST_VOLUME);
        assertNotNull(intent);
        assertEquals(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS, intent.getAction());
        assertEquals(UserHandle.getUserId(DpmMockContext.CALLER_SYSTEM_USER_UID),
                intent.getIntExtra(Intent.EXTRA_USER_ID, -1));
        assertEquals(admin1, intent.getParcelableExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN));
        assertEquals(UserManager.DISALLOW_ADJUST_VOLUME,
                intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION));

        // Try with POLICY_DISABLE_CAMERA and POLICY_DISABLE_SCREEN_CAPTURE, which are not
        // user restrictions

        // Camera is not disabled
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertNull(intent);

        // Camera is disabled
        dpm.setCameraDisabled(admin1, true);
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertNotNull(intent);
        assertEquals(DevicePolicyManager.POLICY_DISABLE_CAMERA,
                intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION));

        // Screen capture is not disabled
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        assertNull(intent);

        // Screen capture is disabled
        dpm.setScreenCaptureDisabled(admin1, true);
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        assertNotNull(intent);
        assertEquals(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE,
                intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION));

        // Same checks for different user
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        // Camera should be disabled by device owner
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertNotNull(intent);
        assertEquals(DevicePolicyManager.POLICY_DISABLE_CAMERA,
                intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION));
        assertEquals(UserHandle.getUserId(DpmMockContext.CALLER_SYSTEM_USER_UID),
                intent.getIntExtra(Intent.EXTRA_USER_ID, -1));
        // ScreenCapture should not be disabled by device owner
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        assertNull(intent);
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setAffiliationIds}
     * {@link DevicePolicyManager#getAffiliationIds}
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
        assertTrue(dpm.getAffiliationIds(admin1).isEmpty());

        // Install a profile owner. Check that the test user is unaffiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin2);
        assertFalse(dpm.isAffiliatedUser());
        assertTrue(dpm.getAffiliationIds(admin2).isEmpty());

        // Have the profile owner specify a set of affiliation ids. Check that the test user remains
        // unaffiliated.
        final Set<String> userAffiliationIds = new ArraySet<>();
        userAffiliationIds.add("red");
        userAffiliationIds.add("green");
        userAffiliationIds.add("blue");
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        MoreAsserts.assertContentsInAnyOrder(dpm.getAffiliationIds(admin2), "red", "green", "blue");
        assertFalse(dpm.isAffiliatedUser());

        // Have the device owner specify a set of affiliation ids that do not intersect with those
        // specified by the profile owner. Check that the test user remains unaffiliated.
        final Set<String> deviceAffiliationIds = new ArraySet<>();
        deviceAffiliationIds.add("cyan");
        deviceAffiliationIds.add("yellow");
        deviceAffiliationIds.add("magenta");
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        dpm.setAffiliationIds(admin1, deviceAffiliationIds);
        MoreAsserts.assertContentsInAnyOrder(
            dpm.getAffiliationIds(admin1), "cyan", "yellow", "magenta");
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertFalse(dpm.isAffiliatedUser());

        // Have the profile owner specify a set of affiliation ids that intersect with those
        // specified by the device owner. Check that the test user becomes affiliated.
        userAffiliationIds.add("yellow");
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        MoreAsserts.assertContentsInAnyOrder(
            dpm.getAffiliationIds(admin2), "red", "green", "blue", "yellow");
        assertTrue(dpm.isAffiliatedUser());

        // Clear affiliation ids for the profile owner. The user becomes unaffiliated.
        dpm.setAffiliationIds(admin2, Collections.emptySet());
        assertTrue(dpm.getAffiliationIds(admin2).isEmpty());
        assertFalse(dpm.isAffiliatedUser());

        // Set affiliation ids again, then clear PO to check that the user becomes unaffiliated
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        assertTrue(dpm.isAffiliatedUser());
        dpm.clearProfileOwner(admin2);
        assertFalse(dpm.isAffiliatedUser());

        // Check that the system user remains affiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        assertTrue(dpm.isAffiliatedUser());

        // Clear the device owner - the user becomes unaffiliated.
        clearDeviceOwner();
        assertFalse(dpm.isAffiliatedUser());
    }

    public void testGetUserProvisioningState_defaultResult() {
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertEquals(DevicePolicyManager.STATE_USER_UNMANAGED, dpm.getUserProvisioningState());
    }

    public void testSetUserProvisioningState_permission() throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_unprivileged() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.setUserProvisioningState(DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                        DpmMockContext.CALLER_USER_HANDLE));
    }

    public void testSetUserProvisioningState_noManagement() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "change provisioning state unless a .* owner is set",
                () -> dpm.setUserProvisioningState(DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                        DpmMockContext.CALLER_USER_HANDLE));
        assertEquals(DevicePolicyManager.STATE_USER_UNMANAGED, dpm.getUserProvisioningState());
    }

    public void testSetUserProvisioningState_deviceOwnerFromSetupWizard() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_deviceOwnerFromSetupWizardAlternative()
            throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_deviceOwnerWithoutSetupWizard() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_managedProfileFromSetupWizard_primaryUser()
            throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_PROFILE_COMPLETE,
                DevicePolicyManager.STATE_USER_UNMANAGED);
    }

    public void testSetUserProvisioningState_managedProfileFromSetupWizard_managedProfile()
            throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_managedProfileWithoutSetupWizard() throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    public void testSetUserProvisioningState_illegalTransitionOutOfFinalized1() throws Exception {
        setupProfileOwner();

        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "Cannot move to user provisioning state",
                () -> exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                        DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                        DevicePolicyManager.STATE_USER_UNMANAGED));
    }

    public void testSetUserProvisioningState_illegalTransitionToAnotherInProgressState()
            throws Exception {
        setupProfileOwner();

        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "Cannot move to user provisioning state",
                () -> exerciseUserProvisioningTransitions(DpmMockContext.CALLER_USER_HANDLE,
                        DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                        DevicePolicyManager.STATE_USER_SETUP_COMPLETE));
    }

    private void exerciseUserProvisioningTransitions(int userId, int... states) {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);

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

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin1, 0);
        verifyScreenTimeoutCall(null, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(false);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin1, 1);
        verifyScreenTimeoutCall(1L, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(true);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin2, 10);
        verifyScreenTimeoutCall(null, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(false);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin1, 5);
        verifyScreenTimeoutCall(5L, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(true);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin2, 4);
        verifyScreenTimeoutCall(4L, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(true);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin1, 0);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin2, Long.MAX_VALUE);
        verifyScreenTimeoutCall(Long.MAX_VALUE, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(true);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        dpm.setMaximumTimeToLock(admin2, 10);
        verifyScreenTimeoutCall(10L, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(true);
        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // There's no restriction; should be set to MAX.
        dpm.setMaximumTimeToLock(admin2, 0);
        verifyScreenTimeoutCall(Long.MAX_VALUE, UserHandle.USER_SYSTEM);
        verifyStayOnWhilePluggedCleared(false);
    }

    // Test if lock timeout on managed profile is handled correctly depending on whether profile
    // uses separate challenge.
    public void testSetMaximumTimeToLockProfile() throws Exception {
        final int PROFILE_USER = 15;
        final int PROFILE_ADMIN = UserHandle.getUid(PROFILE_USER, 19436);
        addManagedProfile(admin1, PROFILE_ADMIN, admin1);
        mContext.binder.callingUid = PROFILE_ADMIN;
        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);

        dpm.setMaximumTimeToLock(admin1, 0);

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // First add timeout for the profile.
        dpm.setMaximumTimeToLock(admin1, 10);
        verifyScreenTimeoutCall(10L, UserHandle.USER_SYSTEM);

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // Add separate challenge
        when(getServices().lockPatternUtils
                .isSeparateProfileChallengeEnabled(eq(PROFILE_USER))).thenReturn(true);
        dpmi.reportSeparateProfileChallengeChanged(PROFILE_USER);

        verifyScreenTimeoutCall(10L, PROFILE_USER);
        verifyScreenTimeoutCall(Long.MAX_VALUE, UserHandle.USER_SYSTEM);

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // Remove the timeout.
        dpm.setMaximumTimeToLock(admin1, 0);
        verifyScreenTimeoutCall(Long.MAX_VALUE, PROFILE_USER);
        verifyScreenTimeoutCall(null , UserHandle.USER_SYSTEM);

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // Add it back.
        dpm.setMaximumTimeToLock(admin1, 10);
        verifyScreenTimeoutCall(10L, PROFILE_USER);
        verifyScreenTimeoutCall(null, UserHandle.USER_SYSTEM);

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // Remove separate challenge.
        reset(getServices().lockPatternUtils);
        when(getServices().lockPatternUtils
                .isSeparateProfileChallengeEnabled(eq(PROFILE_USER))).thenReturn(false);
        dpmi.reportSeparateProfileChallengeChanged(PROFILE_USER);
        when(getServices().lockPatternUtils.hasSecureLockScreen()).thenReturn(true);

        verifyScreenTimeoutCall(Long.MAX_VALUE, PROFILE_USER);
        verifyScreenTimeoutCall(10L , UserHandle.USER_SYSTEM);

        reset(getServices().powerManagerInternal);
        reset(getServices().settings);

        // Remove the timeout.
        dpm.setMaximumTimeToLock(admin1, 0);
        verifyScreenTimeoutCall(null, PROFILE_USER);
        verifyScreenTimeoutCall(Long.MAX_VALUE, UserHandle.USER_SYSTEM);
    }

    public void testSetRequiredStrongAuthTimeout_DeviceOwner() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        final long MINIMUM_STRONG_AUTH_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1);
        final long ONE_MINUTE = TimeUnit.MINUTES.toMillis(1);
        final long MIN_PLUS_ONE_MINUTE = MINIMUM_STRONG_AUTH_TIMEOUT_MS + ONE_MINUTE;
        final long MAX_MINUS_ONE_MINUTE = DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS
                - ONE_MINUTE;

        // verify that the minimum timeout cannot be modified on user builds (system property is
        // not being read)
        getServices().buildMock.isDebuggable = false;

        dpm.setRequiredStrongAuthTimeout(admin1, MAX_MINUS_ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), MAX_MINUS_ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null), MAX_MINUS_ONE_MINUTE);

        verify(getServices().systemProperties, never()).getLong(anyString(), anyLong());

        // restore to the debuggable build state
        getServices().buildMock.isDebuggable = true;

        // reset to default (0 means the admin is not participating, so default should be returned)
        dpm.setRequiredStrongAuthTimeout(admin1, 0);

        // aggregation should be the default if unset by any admin
        assertEquals(dpm.getRequiredStrongAuthTimeout(null),
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS);

        // admin not participating by default
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), 0);

        //clamping from the top
        dpm.setRequiredStrongAuthTimeout(admin1,
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS + ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1),
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null),
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS);

        // 0 means the admin is not participating, so default should be returned
        dpm.setRequiredStrongAuthTimeout(admin1, 0);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), 0);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null),
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS);

        // clamping from the bottom
        dpm.setRequiredStrongAuthTimeout(admin1, MINIMUM_STRONG_AUTH_TIMEOUT_MS - ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), MINIMUM_STRONG_AUTH_TIMEOUT_MS);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null), MINIMUM_STRONG_AUTH_TIMEOUT_MS);

        // values within range
        dpm.setRequiredStrongAuthTimeout(admin1, MIN_PLUS_ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), MIN_PLUS_ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null), MIN_PLUS_ONE_MINUTE);

        dpm.setRequiredStrongAuthTimeout(admin1, MAX_MINUS_ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), MAX_MINUS_ONE_MINUTE);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null), MAX_MINUS_ONE_MINUTE);

        // reset to default
        dpm.setRequiredStrongAuthTimeout(admin1, 0);
        assertEquals(dpm.getRequiredStrongAuthTimeout(admin1), 0);
        assertEquals(dpm.getRequiredStrongAuthTimeout(null),
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS);

        // negative value
        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ null,
                () -> dpm.setRequiredStrongAuthTimeout(admin1, -ONE_MINUTE));
    }

    private void verifyScreenTimeoutCall(Long expectedTimeout, int userId) {
        if (expectedTimeout == null) {
            verify(getServices().powerManagerInternal, times(0))
                    .setMaximumScreenOffTimeoutFromDeviceAdmin(eq(userId), anyLong());
        } else {
            verify(getServices().powerManagerInternal, times(1))
                    .setMaximumScreenOffTimeoutFromDeviceAdmin(eq(userId), eq(expectedTimeout));
        }
    }

    private void verifyStayOnWhilePluggedCleared(boolean cleared) {
        // TODO Verify calls to settingsGlobalPutInt.  Tried but somehow mockito threw
        // UnfinishedVerificationException.
    }

    private void setup_DeviceAdminFeatureOff() throws Exception {
        when(getServices().packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN))
                .thenReturn(false);
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(false);
        initializeDpms();
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    public void testIsProvisioningAllowed_DeviceAdminFeatureOff() throws Exception {
        setup_DeviceAdminFeatureOff();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, false);
    }

    public void testCheckProvisioningPreCondition_DeviceAdminFeatureOff() throws Exception {
        setup_DeviceAdminFeatureOff();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
    }

    private void setup_ManagedProfileFeatureOff() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(false);
        initializeDpms();
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    public void testIsProvisioningAllowed_ManagedProfileFeatureOff() throws Exception {
        setup_ManagedProfileFeatureOff();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, false);

        // Test again when split user is on
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, false);
    }

    public void testCheckProvisioningPreCondition_ManagedProfileFeatureOff() throws Exception {
        setup_ManagedProfileFeatureOff();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_MANAGED_USERS_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_NOT_SYSTEM_USER_SPLIT);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_MANAGED_USERS_NOT_SUPPORTED);

        // Test again when split user is on
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_MANAGED_USERS_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_MANAGED_USERS_NOT_SUPPORTED);
    }

    private void setup_nonSplitUser_firstBoot_primaryUser() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    public void testIsProvisioningAllowed_nonSplitUser_firstBoot_primaryUser() throws Exception {
        setup_nonSplitUser_firstBoot_primaryUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false /* because of non-split user */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false /* because of non-split user */);
    }

    public void testCheckProvisioningPreCondition_nonSplitUser_firstBoot_primaryUser()
            throws Exception {
        setup_nonSplitUser_firstBoot_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_NOT_SYSTEM_USER_SPLIT);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_NOT_SYSTEM_USER_SPLIT);
    }

    private void setup_nonSplitUser_afterDeviceSetup_primaryUser() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(false);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    private void setup_nonSplitUser_withDo_primaryUser() throws Exception {
        setDeviceOwner();
        setup_nonSplitUser_afterDeviceSetup_primaryUser();
        setUpPackageManagerForFakeAdmin(adminAnotherPackage, DpmMockContext.ANOTHER_UID, admin2);
    }

    private void setup_nonSplitUser_withDo_primaryUser_ManagedProfile() throws Exception {
        setup_nonSplitUser_withDo_primaryUser();
        final int MANAGED_PROFILE_USER_ID = 18;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 1308);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                false /* we can't remove a managed profile */)).thenReturn(false);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                true)).thenReturn(true);
    }

    public void testIsProvisioningAllowed_nonSplitUser_afterDeviceSetup_primaryUser()
            throws Exception {
        setup_nonSplitUser_afterDeviceSetup_primaryUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                false/* because of completed device setup */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                false/* because of non-split user */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because of non-split user */);
    }

    public void testCheckProvisioningPreCondition_nonSplitUser_afterDeviceSetup_primaryUser()
            throws Exception {
        setup_nonSplitUser_afterDeviceSetup_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_USER_SETUP_COMPLETED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_NOT_SYSTEM_USER_SPLIT);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_NOT_SYSTEM_USER_SPLIT);
    }

    public void testProvisioning_nonSplitUser_withDo_primaryUser() throws Exception {
        setup_nonSplitUser_withDo_primaryUser();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_HAS_DEVICE_OWNER);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);

        // COMP mode is allowed.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);

        // And other DPCs can also provision a managed profile (DO + BYOD case).
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_OK);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    public void testProvisioning_nonSplitUser_withDo_primaryUser_restrictedByDo() throws Exception {
        setup_nonSplitUser_withDo_primaryUser();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        // The DO should be allowed to initiate provisioning if it set the restriction itself, but
        // other packages should be forbidden.
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_ADD_MANAGED_PROFILE),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(true);
        when(getServices().userManager.getUserRestrictionSource(
                eq(UserManager.DISALLOW_ADD_MANAGED_PROFILE),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_ADD_MANAGED_PROFILE_DISALLOWED);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    public void testProvisioning_nonSplitUser_withDo_primaryUser_restrictedBySystem()
            throws Exception {
        setup_nonSplitUser_withDo_primaryUser();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        // The DO should not be allowed to initiate provisioning if the restriction is set by
        // another entity.
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_ADD_MANAGED_PROFILE),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(true);
        when(getServices().userManager.getUserRestrictionSource(
                eq(UserManager.DISALLOW_ADD_MANAGED_PROFILE),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(UserManager.RESTRICTION_SOURCE_SYSTEM);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_ADD_MANAGED_PROFILE_DISALLOWED);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);

        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_ADD_MANAGED_PROFILE_DISALLOWED);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    public void testCheckProvisioningPreCondition_nonSplitUser_comp() throws Exception {
        setup_nonSplitUser_withDo_primaryUser_ManagedProfile();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // We can delete the managed profile to create a new one, so provisioning is allowed.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_OK);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    public void testCheckProvisioningPreCondition_nonSplitUser_comp_cannot_remove_profile()
            throws Exception {
        setup_nonSplitUser_withDo_primaryUser_ManagedProfile();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE),
                eq(UserHandle.SYSTEM)))
                .thenReturn(true);
        when(getServices().userManager.getUserRestrictionSource(
                eq(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE),
                eq(UserHandle.SYSTEM)))
                .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);

        // We can't remove the profile to create a new one.
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);

        // But the device owner can still do it because it has set the restriction itself.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
    }

    private void setup_splitUser_firstBoot_systemUser() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(false);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    public void testIsProvisioningAllowed_splitUser_firstBoot_systemUser() throws Exception {
        setup_splitUser_firstBoot_systemUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                false /* because canAddMoreManagedProfiles returns false */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because calling uid is system user */);
    }

    public void testCheckProvisioningPreCondition_splitUser_firstBoot_systemUser()
            throws Exception {
        setup_splitUser_firstBoot_systemUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_SYSTEM_USER);
    }

    private void setup_splitUser_afterDeviceSetup_systemUser() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(false);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    public void testIsProvisioningAllowed_splitUser_afterDeviceSetup_systemUser() throws Exception {
        setup_splitUser_afterDeviceSetup_systemUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                false /* because canAddMoreManagedProfiles returns false */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because calling uid is system user */);
    }

    public void testCheckProvisioningPreCondition_splitUser_afterDeviceSetup_systemUser()
            throws Exception {
        setup_splitUser_afterDeviceSetup_systemUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_SYSTEM_USER);
    }

    private void setup_splitUser_firstBoot_primaryUser() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(false, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
    }

    public void testIsProvisioningAllowed_splitUser_firstBoot_primaryUser() throws Exception {
        setup_splitUser_firstBoot_primaryUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER, true);
    }

    public void testCheckProvisioningPreCondition_splitUser_firstBoot_primaryUser()
            throws Exception {
        setup_splitUser_firstBoot_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_OK);
    }

    private void setup_splitUser_afterDeviceSetup_primaryUser() throws Exception {
        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(true, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
    }

    public void testIsProvisioningAllowed_splitUser_afterDeviceSetup_primaryUser()
            throws Exception {
        setup_splitUser_afterDeviceSetup_primaryUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                true/* it's undefined behavior. Can be changed into false in the future */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                false/* because user setup completed */);
    }

    public void testCheckProvisioningPreCondition_splitUser_afterDeviceSetup_primaryUser()
            throws Exception {
        setup_splitUser_afterDeviceSetup_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_USER,
                DevicePolicyManager.CODE_USER_SETUP_COMPLETED);
    }

    private void setup_provisionManagedProfileWithDeviceOwner_systemUser() throws Exception {
        setDeviceOwner();

        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(false);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    public void testIsProvisioningAllowed_provisionManagedProfileWithDeviceOwner_systemUser()
            throws Exception {
        setup_provisionManagedProfileWithDeviceOwner_systemUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                false /* can't provision managed profile on system user */);
    }

    public void testCheckProvisioningPreCondition_provisionManagedProfileWithDeviceOwner_systemUser()
            throws Exception {
        setup_provisionManagedProfileWithDeviceOwner_systemUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER);
    }

    private void setup_provisionManagedProfileWithDeviceOwner_primaryUser() throws Exception {
        setDeviceOwner();

        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(false, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
    }

    public void testIsProvisioningAllowed_provisionManagedProfileWithDeviceOwner_primaryUser()
            throws Exception {
        setup_provisionManagedProfileWithDeviceOwner_primaryUser();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        mContext.packageName = admin1.getPackageName();
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
    }

    public void testCheckProvisioningPreCondition_provisionManagedProfileWithDeviceOwner_primaryUser()
            throws Exception {
        setup_provisionManagedProfileWithDeviceOwner_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // COMP mode is allowed.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
    }

    private void setup_provisionManagedProfileCantRemoveUser_primaryUser() throws Exception {
        setDeviceOwner();

        when(getServices().ipackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0))
                .thenReturn(true);
        when(getServices().userManagerForMock.isSplitSystemUser()).thenReturn(true);
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE),
                eq(UserHandle.of(DpmMockContext.CALLER_USER_HANDLE))))
                .thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                false /* we can't remove a managed profile */)).thenReturn(false);
        when(getServices().userManager.canAddMoreManagedProfiles(DpmMockContext.CALLER_USER_HANDLE,
                true)).thenReturn(true);
        setUserSetupCompleteForUser(false, DpmMockContext.CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
    }

    public void testIsProvisioningAllowed_provisionManagedProfileCantRemoveUser_primaryUser()
            throws Exception {
        setup_provisionManagedProfileCantRemoveUser_primaryUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
    }

    public void testCheckProvisioningPreCondition_provisionManagedProfileCantRemoveUser_primaryUser()
            throws Exception {
        setup_provisionManagedProfileCantRemoveUser_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
    }

    public void testCheckProvisioningPreCondition_permission() {
        // GIVEN the permission MANAGE_PROFILE_AND_DEVICE_OWNERS is not granted
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.checkProvisioningPreCondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, "some.package"));
    }

    public void testForceUpdateUserSetupComplete_permission() {
        // GIVEN the permission MANAGE_PROFILE_AND_DEVICE_OWNERS is not granted
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.forceUpdateUserSetupComplete());
    }

    public void testForceUpdateUserSetupComplete_systemUser() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        // GIVEN calling from user 20
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.forceUpdateUserSetupComplete());
    }

    public void testForceUpdateUserSetupComplete_userbuild() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        final int userId = UserHandle.USER_SYSTEM;
        // GIVEN userComplete is false in SettingsProvider
        setUserSetupCompleteForUser(false, userId);

        // GIVEN userComplete is true in DPM
        DevicePolicyManagerService.DevicePolicyData userData =
                new DevicePolicyManagerService.DevicePolicyData(userId);
        userData.mUserSetupComplete = true;
        dpms.mUserData.put(UserHandle.USER_SYSTEM, userData);

        // GIVEN it's user build
        getServices().buildMock.isDebuggable = false;

        assertTrue(dpms.hasUserSetupCompleted());

        dpm.forceUpdateUserSetupComplete();

        // THEN the state in dpms is not changed
        assertTrue(dpms.hasUserSetupCompleted());
    }

    public void testForceUpdateUserSetupComplete_userDebugbuild() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        final int userId = UserHandle.USER_SYSTEM;
        // GIVEN userComplete is false in SettingsProvider
        setUserSetupCompleteForUser(false, userId);

        // GIVEN userComplete is true in DPM
        DevicePolicyManagerService.DevicePolicyData userData =
                new DevicePolicyManagerService.DevicePolicyData(userId);
        userData.mUserSetupComplete = true;
        dpms.mUserData.put(UserHandle.USER_SYSTEM, userData);

        // GIVEN it's userdebug build
        getServices().buildMock.isDebuggable = true;

        assertTrue(dpms.hasUserSetupCompleted());

        dpm.forceUpdateUserSetupComplete();

        // THEN the state in dpms is not changed
        assertFalse(dpms.hasUserSetupCompleted());
    }

    private void clearDeviceOwner() throws Exception {
        doReturn(DpmMockContext.CALLER_SYSTEM_USER_UID).when(getServices().packageManager)
                .getPackageUidAsUser(eq(admin1.getPackageName()), anyInt());

        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        runAsCaller(mAdmin1Context, dpms, dpm -> {
            dpm.clearDeviceOwnerApp(admin1.getPackageName());
        });
    }

    public void testGetLastSecurityLogRetrievalTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(DpmMockContext.CALLER_USER_HANDLE);
        when(mContext.resources.getBoolean(R.bool.config_supportPreRebootSecurityLogs))
                .thenReturn(true);

        // No logs were retrieved so far.
        assertEquals(-1, dpm.getLastSecurityLogRetrievalTime());

        // Enabling logging should not change the timestamp.
        dpm.setSecurityLoggingEnabled(admin1, true);
        verify(getServices().settings)
                .securityLogSetLoggingEnabledProperty(true);
        when(getServices().settings.securityLogGetLoggingEnabledProperty())
                .thenReturn(true);
        assertEquals(-1, dpm.getLastSecurityLogRetrievalTime());

        // Retrieving the logs should update the timestamp.
        final long beforeRetrieval = System.currentTimeMillis();
        dpm.retrieveSecurityLogs(admin1);
        final long firstSecurityLogRetrievalTime = dpm.getLastSecurityLogRetrievalTime();
        final long afterRetrieval = System.currentTimeMillis();
        assertTrue(firstSecurityLogRetrievalTime >= beforeRetrieval);
        assertTrue(firstSecurityLogRetrievalTime <= afterRetrieval);

        // Retrieving the pre-boot logs should update the timestamp.
        Thread.sleep(2);
        dpm.retrievePreRebootSecurityLogs(admin1);
        final long secondSecurityLogRetrievalTime = dpm.getLastSecurityLogRetrievalTime();
        assertTrue(secondSecurityLogRetrievalTime > firstSecurityLogRetrievalTime);

        // Checking the timestamp again should not change it.
        Thread.sleep(2);
        assertEquals(secondSecurityLogRetrievalTime, dpm.getLastSecurityLogRetrievalTime());

        // Retrieving the logs again should update the timestamp.
        dpm.retrieveSecurityLogs(admin1);
        final long thirdSecurityLogRetrievalTime = dpm.getLastSecurityLogRetrievalTime();
        assertTrue(thirdSecurityLogRetrievalTime > secondSecurityLogRetrievalTime);

        // Disabling logging should not change the timestamp.
        Thread.sleep(2);
        dpm.setSecurityLoggingEnabled(admin1, false);
        assertEquals(thirdSecurityLogRetrievalTime, dpm.getLastSecurityLogRetrievalTime());

        // Restarting the DPMS should not lose the timestamp.
        initializeDpms();
        assertEquals(thirdSecurityLogRetrievalTime, dpm.getLastSecurityLogRetrievalTime());

        // Any uid holding MANAGE_USERS permission can retrieve the timestamp.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertEquals(thirdSecurityLogRetrievalTime, dpm.getLastSecurityLogRetrievalTime());
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve the timestamp.
        mContext.binder.clearCallingIdentity();
        assertEquals(thirdSecurityLogRetrievalTime, dpm.getLastSecurityLogRetrievalTime());

        // Removing the device owner should clear the timestamp.
        clearDeviceOwner();
        assertEquals(-1, dpm.getLastSecurityLogRetrievalTime());
    }

    public void testSetSystemSettingFailWithNonWhitelistedSettings() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        assertExpectException(SecurityException.class, null, () ->
                dpm.setSystemSetting(admin1, Settings.System.SCREEN_BRIGHTNESS_FOR_VR, "0"));
    }

    public void testSetSystemSettingWithDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setSystemSetting(admin1, Settings.System.SCREEN_BRIGHTNESS, "0");
        verify(getServices().settings).settingsSystemPutStringForUser(
                Settings.System.SCREEN_BRIGHTNESS, "0", UserHandle.USER_SYSTEM);
    }

    public void testSetSystemSettingWithPO() throws Exception {
        setupProfileOwner();
        dpm.setSystemSetting(admin1, Settings.System.SCREEN_BRIGHTNESS, "0");
        verify(getServices().settings).settingsSystemPutStringForUser(
            Settings.System.SCREEN_BRIGHTNESS, "0", DpmMockContext.CALLER_USER_HANDLE);
    }

    public void testSetTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setTime(admin1, 0);
        verify(getServices().alarmManager).setTime(0);
    }

    public void testSetTimeFailWithPO() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null, () -> dpm.setTime(admin1, 0));
    }

    public void testSetTimeWithAutoTimeOn() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        when(getServices().settings.settingsGlobalGetInt(Settings.Global.AUTO_TIME, 0))
                .thenReturn(1);
        assertFalse(dpm.setTime(admin1, 0));
    }

    public void testSetTimeZone() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setTimeZone(admin1, "Asia/Shanghai");
        verify(getServices().alarmManager).setTimeZone("Asia/Shanghai");
    }

    public void testSetTimeZoneFailWithPO() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null,
                () -> dpm.setTimeZone(admin1, "Asia/Shanghai"));
    }

    public void testSetTimeZoneWithAutoTimeZoneOn() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        when(getServices().settings.settingsGlobalGetInt(Settings.Global.AUTO_TIME_ZONE, 0))
                .thenReturn(1);
        assertFalse(dpm.setTimeZone(admin1, "Asia/Shanghai"));
    }

    public void testGetLastBugReportRequestTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        mContext.packageName = admin1.getPackageName();
        mContext.applicationInfo = new ApplicationInfo();
        when(mContext.resources.getColor(eq(R.color.notification_action_list), anyObject()))
                .thenReturn(Color.WHITE);
        when(mContext.resources.getColor(eq(R.color.notification_material_background_color),
                anyObject())).thenReturn(Color.WHITE);

        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(DpmMockContext.CALLER_USER_HANDLE);

        // No bug reports were requested so far.
        assertEquals(-1, dpm.getLastBugReportRequestTime());

        // Requesting a bug report should update the timestamp.
        final long beforeRequest = System.currentTimeMillis();
        dpm.requestBugreport(admin1);
        final long bugReportRequestTime = dpm.getLastBugReportRequestTime();
        final long afterRequest = System.currentTimeMillis();
        assertTrue(bugReportRequestTime >= beforeRequest);
        assertTrue(bugReportRequestTime <= afterRequest);

        // Checking the timestamp again should not change it.
        Thread.sleep(2);
        assertEquals(bugReportRequestTime, dpm.getLastBugReportRequestTime());

        // Restarting the DPMS should not lose the timestamp.
        initializeDpms();
        assertEquals(bugReportRequestTime, dpm.getLastBugReportRequestTime());

        // Any uid holding MANAGE_USERS permission can retrieve the timestamp.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertEquals(bugReportRequestTime, dpm.getLastBugReportRequestTime());
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve the timestamp.
        mContext.binder.clearCallingIdentity();
        assertEquals(bugReportRequestTime, dpm.getLastBugReportRequestTime());

        // Removing the device owner should clear the timestamp.
        clearDeviceOwner();
        assertEquals(-1, dpm.getLastBugReportRequestTime());
    }

    public void testGetLastNetworkLogRetrievalTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.packageName = admin1.getPackageName();
        mContext.applicationInfo = new ApplicationInfo();
        when(mContext.resources.getColor(eq(R.color.notification_action_list), anyObject()))
                .thenReturn(Color.WHITE);
        when(mContext.resources.getColor(eq(R.color.notification_material_background_color),
                anyObject())).thenReturn(Color.WHITE);

        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(DpmMockContext.CALLER_USER_HANDLE);
        when(getServices().iipConnectivityMetrics.addNetdEventCallback(anyInt(), anyObject()))
                .thenReturn(true);

        // No logs were retrieved so far.
        assertEquals(-1, dpm.getLastNetworkLogRetrievalTime());

        // Attempting to retrieve logs without enabling logging should not change the timestamp.
        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);
        assertEquals(-1, dpm.getLastNetworkLogRetrievalTime());

        // Enabling logging should not change the timestamp.
        dpm.setNetworkLoggingEnabled(admin1, true);
        assertEquals(-1, dpm.getLastNetworkLogRetrievalTime());

        // Retrieving the logs should update the timestamp.
        final long beforeRetrieval = System.currentTimeMillis();
        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);
        final long firstNetworkLogRetrievalTime = dpm.getLastNetworkLogRetrievalTime();
        final long afterRetrieval = System.currentTimeMillis();
        assertTrue(firstNetworkLogRetrievalTime >= beforeRetrieval);
        assertTrue(firstNetworkLogRetrievalTime <= afterRetrieval);

        // Checking the timestamp again should not change it.
        Thread.sleep(2);
        assertEquals(firstNetworkLogRetrievalTime, dpm.getLastNetworkLogRetrievalTime());

        // Retrieving the logs again should update the timestamp.
        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);
        final long secondNetworkLogRetrievalTime = dpm.getLastNetworkLogRetrievalTime();
        assertTrue(secondNetworkLogRetrievalTime > firstNetworkLogRetrievalTime);

        // Disabling logging should not change the timestamp.
        Thread.sleep(2);
        dpm.setNetworkLoggingEnabled(admin1, false);
        assertEquals(secondNetworkLogRetrievalTime, dpm.getLastNetworkLogRetrievalTime());

        // Restarting the DPMS should not lose the timestamp.
        initializeDpms();
        assertEquals(secondNetworkLogRetrievalTime, dpm.getLastNetworkLogRetrievalTime());

        // Any uid holding MANAGE_USERS permission can retrieve the timestamp.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertEquals(secondNetworkLogRetrievalTime, dpm.getLastNetworkLogRetrievalTime());
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve the timestamp.
        mContext.binder.clearCallingIdentity();
        assertEquals(secondNetworkLogRetrievalTime, dpm.getLastNetworkLogRetrievalTime());

        // Removing the device owner should clear the timestamp.
        clearDeviceOwner();
        assertEquals(-1, dpm.getLastNetworkLogRetrievalTime());
    }

    public void testGetBindDeviceAdminTargetUsers() throws Exception {
        // Setup device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // Only device owner is setup, the result list should be empty.
        List<UserHandle> targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        // Setup a managed profile managed by the same admin.
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 20456);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Add a secondary user, it should never talk with.
        final int ANOTHER_USER_ID = 36;
        getServices().addUser(ANOTHER_USER_ID, 0);

        // Since the managed profile is not affiliated, they should not be allowed to talk to each
        // other.
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        // Setting affiliation ids
        final Set<String> userAffiliationIds = Collections.singleton("some.affiliation-id");
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        dpm.setAffiliationIds(admin1, userAffiliationIds);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setAffiliationIds(admin1, userAffiliationIds);

        // Calling from device owner admin, the result list should just contain the managed
        // profile user id.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertContentsInAnyOrder(targetUsers, UserHandle.of(MANAGED_PROFILE_USER_ID));

        // Calling from managed profile admin, the result list should just contain the system
        // user id.
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertContentsInAnyOrder(targetUsers, UserHandle.SYSTEM);

        // Changing affiliation ids in one
        dpm.setAffiliationIds(admin1, Collections.singleton("some-different-affiliation-id"));

        // Since the managed profile is not affiliated any more, they should not be allowed to talk
        // to each other.
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);
    }

    public void testGetBindDeviceAdminTargetUsers_differentPackage() throws Exception {
        // Setup a device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // Set up a managed profile managed by different package.
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 20456);
        final ComponentName adminDifferentPackage =
                new ComponentName("another.package", "whatever.class");
        addManagedProfile(adminDifferentPackage, MANAGED_PROFILE_ADMIN_UID, admin2);

        // Setting affiliation ids
        final Set<String> userAffiliationIds = Collections.singleton("some-affiliation-id");
        dpm.setAffiliationIds(admin1, userAffiliationIds);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setAffiliationIds(adminDifferentPackage, userAffiliationIds);

        // Calling from device owner admin, we should get zero bind device admin target users as
        // their packages are different.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        List<UserHandle> targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        // Calling from managed profile admin, we should still get zero target users for the same
        // reason.
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        targetUsers = dpm.getBindDeviceAdminTargetUsers(adminDifferentPackage);
        MoreAsserts.assertEmpty(targetUsers);
    }

    private void verifyLockTaskState(int userId) throws Exception {
        verifyLockTaskState(userId, new String[0],
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
    }

    private void verifyLockTaskState(int userId, String[] packages, int flags) throws Exception {
        verify(getServices().iactivityManager).updateLockTaskPackages(userId, packages);
        verify(getServices().iactivityTaskManager).updateLockTaskFeatures(userId, flags);
    }

    private void verifyCanSetLockTask(int uid, int userId, ComponentName who, String[] packages,
            int flags) throws Exception {
        mContext.binder.callingUid = uid;
        dpm.setLockTaskPackages(who, packages);
        MoreAsserts.assertEquals(packages, dpm.getLockTaskPackages(who));
        for (String p : packages) {
            assertTrue(dpm.isLockTaskPermitted(p));
        }
        assertFalse(dpm.isLockTaskPermitted("anotherPackage"));
        // Test to see if set lock task features can be set
        dpm.setLockTaskFeatures(who, flags);
        verifyLockTaskState(userId, packages, flags);
    }

    private void verifyCanNotSetLockTask(int uid, ComponentName who, String[] packages,
            int flags) throws Exception {
        mContext.binder.callingUid = uid;
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.setLockTaskPackages(who, packages));
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.getLockTaskPackages(who));
        assertFalse(dpm.isLockTaskPermitted("doPackage1"));
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.setLockTaskFeatures(who, flags));
    }

    public void testLockTaskPolicyAllowedForAffiliatedUsers() throws Exception {
        // Setup a device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        // Lock task policy is updated when loading user data.
        verifyLockTaskState(UserHandle.USER_SYSTEM);

        // Set up a managed profile managed by different package (package name shouldn't matter)
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 20456);
        final ComponentName adminDifferentPackage =
                new ComponentName("another.package", "whatever.class");
        addManagedProfile(adminDifferentPackage, MANAGED_PROFILE_ADMIN_UID, admin2);
        verifyLockTaskState(MANAGED_PROFILE_USER_ID);

        // Setup a PO on the secondary user
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin3);
        verifyLockTaskState(DpmMockContext.CALLER_USER_HANDLE);

        // The DO can still set lock task packages
        final String[] doPackages = {"doPackage1", "doPackage2"};
        final int flags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        verifyCanSetLockTask(DpmMockContext.CALLER_SYSTEM_USER_UID, UserHandle.USER_SYSTEM, admin1, doPackages, flags);

        final String[] secondaryPoPackages = {"secondaryPoPackage1", "secondaryPoPackage2"};
        final int secondaryPoFlags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        verifyCanNotSetLockTask(DpmMockContext.CALLER_UID, admin3, secondaryPoPackages, secondaryPoFlags);

        // Managed profile is unaffiliated - shouldn't be able to setLockTaskPackages.
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        final String[] poPackages = {"poPackage1", "poPackage2"};
        final int poFlags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        verifyCanNotSetLockTask(MANAGED_PROFILE_ADMIN_UID, adminDifferentPackage, poPackages, poFlags);

        // Setting same affiliation ids
        final Set<String> userAffiliationIds = Collections.singleton("some-affiliation-id");
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        dpm.setAffiliationIds(admin1, userAffiliationIds);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setAffiliationIds(adminDifferentPackage, userAffiliationIds);

        // Now the managed profile can set lock task packages.
        dpm.setLockTaskPackages(adminDifferentPackage, poPackages);
        MoreAsserts.assertEquals(poPackages, dpm.getLockTaskPackages(adminDifferentPackage));
        assertTrue(dpm.isLockTaskPermitted("poPackage1"));
        assertFalse(dpm.isLockTaskPermitted("doPackage2"));
        // And it can set lock task features.
        dpm.setLockTaskFeatures(adminDifferentPackage, poFlags);
        verifyLockTaskState(MANAGED_PROFILE_USER_ID, poPackages, poFlags);

        // Unaffiliate the profile, lock task mode no longer available on the profile.
        dpm.setAffiliationIds(adminDifferentPackage, Collections.emptySet());
        assertFalse(dpm.isLockTaskPermitted("poPackage1"));
        // Lock task packages cleared when loading user data and when the user becomes unaffiliated.
        verify(getServices().iactivityManager, times(2)).updateLockTaskPackages(
                MANAGED_PROFILE_USER_ID, new String[0]);
        verify(getServices().iactivityTaskManager, times(2)).updateLockTaskFeatures(
                MANAGED_PROFILE_USER_ID, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);

        // Verify that lock task packages were not cleared for the DO
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        assertTrue(dpm.isLockTaskPermitted("doPackage1"));

    }

    public void testLockTaskPolicyForProfileOwner() throws Exception {
        // Setup a PO
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin1);
        verifyLockTaskState(DpmMockContext.CALLER_USER_HANDLE);

        final String[] poPackages = {"poPackage1", "poPackage2"};
        final int poFlags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        verifyCanSetLockTask(DpmMockContext.CALLER_UID, DpmMockContext.CALLER_USER_HANDLE, admin1,
                poPackages, poFlags);

        // Set up a managed profile managed by different package (package name shouldn't matter)
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 20456);
        final ComponentName adminDifferentPackage =
                new ComponentName("another.package", "whatever.class");
        addManagedProfile(adminDifferentPackage, MANAGED_PROFILE_ADMIN_UID, admin2);
        verifyLockTaskState(MANAGED_PROFILE_USER_ID);

        // Managed profile is unaffiliated - shouldn't be able to setLockTaskPackages.
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        final String[] mpoPackages = {"poPackage1", "poPackage2"};
        final int mpoFlags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        verifyCanNotSetLockTask(MANAGED_PROFILE_ADMIN_UID, adminDifferentPackage, mpoPackages, mpoFlags);
    }

    public void testLockTaskFeatures_IllegalArgumentException() throws Exception {
        // Setup a device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        // Lock task policy is updated when loading user data.
        verifyLockTaskState(UserHandle.USER_SYSTEM);

        final int flags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        assertExpectException(IllegalArgumentException.class,
                "Cannot use LOCK_TASK_FEATURE_OVERVIEW without LOCK_TASK_FEATURE_HOME",
                () -> dpm.setLockTaskFeatures(admin1, flags));
    }

    public void testIsDeviceManaged() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // The device owner itself, any uid holding MANAGE_USERS permission and the system can
        // find out that the device has a device owner.
        assertTrue(dpm.isDeviceManaged());
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertTrue(dpm.isDeviceManaged());
        mContext.callerPermissions.remove(permission.MANAGE_USERS);
        mContext.binder.clearCallingIdentity();
        assertTrue(dpm.isDeviceManaged());

        clearDeviceOwner();

        // Any uid holding MANAGE_USERS permission and the system can find out that the device does
        // not have a device owner.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertFalse(dpm.isDeviceManaged());
        mContext.callerPermissions.remove(permission.MANAGE_USERS);
        mContext.binder.clearCallingIdentity();
        assertFalse(dpm.isDeviceManaged());
    }

    public void testDeviceOwnerOrganizationName() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        dpm.setOrganizationName(admin1, "organization");

        // Device owner can retrieve organization managing the device.
        assertEquals("organization", dpm.getDeviceOwnerOrganizationName());

        // Any uid holding MANAGE_USERS permission can retrieve organization managing the device.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertEquals("organization", dpm.getDeviceOwnerOrganizationName());
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve organization managing the device.
        mContext.binder.clearCallingIdentity();
        assertEquals("organization", dpm.getDeviceOwnerOrganizationName());

        // Removing the device owner clears the organization managing the device.
        clearDeviceOwner();
        assertNull(dpm.getDeviceOwnerOrganizationName());
    }

    public void testWipeDataManagedProfile() throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));
        // Get mock reason string since we throw an IAE with empty string input.
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");

        dpm.wipeData(0);
        verify(getServices().userManagerInternal).removeUserEvenWhenDisallowed(
                MANAGED_PROFILE_USER_ID);
    }

    public void testWipeDataManagedProfileDisallowed() throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
                UserHandle.of(MANAGED_PROFILE_USER_ID)))
                .thenReturn(UserManager.RESTRICTION_SOURCE_SYSTEM);
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        // The PO is not allowed to remove the profile if the user restriction was set on the
        // profile by the system
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.wipeData(0));
    }

    public void testWipeDataDeviceOwner() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_FACTORY_RESET,
                UserHandle.SYSTEM))
                .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");

        dpm.wipeData(0);
        verify(getServices().recoverySystem).rebootWipeUserData(
                /*shutdown=*/ eq(false), anyString(), /*force=*/ eq(true),
                /*wipeEuicc=*/ eq(false));
    }

    public void testWipeEuiccDataEnabled() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
            UserManager.DISALLOW_FACTORY_RESET,
            UserHandle.SYSTEM))
            .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");

        dpm.wipeData(WIPE_EUICC);
        verify(getServices().recoverySystem).rebootWipeUserData(
                /*shutdown=*/ eq(false), anyString(), /*force=*/ eq(true),
                /*wipeEuicc=*/ eq(true));
    }

    public void testWipeDataDeviceOwnerDisallowed() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_FACTORY_RESET,
                UserHandle.SYSTEM))
                .thenReturn(UserManager.RESTRICTION_SOURCE_SYSTEM);
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");
        // The DO is not allowed to wipe the device if the user restriction was set
        // by the system
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.wipeData(0));
    }

    public void testMaximumFailedPasswordAttemptsReachedManagedProfile() throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
                UserHandle.of(MANAGED_PROFILE_USER_ID)))
                .thenReturn(UserManager.RESTRICTION_SOURCE_PROFILE_OWNER);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setMaximumFailedPasswordsForWipe(admin1, 3);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);
        // Failed password attempts on the parent user are taken into account, as there isn't a
        // separate work challenge.
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);

        // The profile should be wiped even if DISALLOW_REMOVE_MANAGED_PROFILE is enabled, because
        // both the user restriction and the policy were set by the PO.
        verify(getServices().userManagerInternal).removeUserEvenWhenDisallowed(
                MANAGED_PROFILE_USER_ID);
        verifyZeroInteractions(getServices().recoverySystem);
    }

    public void testMaximumFailedPasswordAttemptsReachedManagedProfileDisallowed()
            throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
                UserHandle.of(MANAGED_PROFILE_USER_ID)))
                .thenReturn(UserManager.RESTRICTION_SOURCE_SYSTEM);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setMaximumFailedPasswordsForWipe(admin1, 3);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);
        // Failed password attempts on the parent user are taken into account, as there isn't a
        // separate work challenge.
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);

        // DISALLOW_REMOVE_MANAGED_PROFILE was set by the system, not the PO, so the profile is
        // not wiped.
        verify(getServices().userManagerInternal, never())
                .removeUserEvenWhenDisallowed(anyInt());
        verifyZeroInteractions(getServices().recoverySystem);
    }

    public void testMaximumFailedPasswordAttemptsReachedDeviceOwner() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_FACTORY_RESET,
                UserHandle.SYSTEM))
                .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);

        dpm.setMaximumFailedPasswordsForWipe(admin1, 3);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);

        // The device should be wiped even if DISALLOW_FACTORY_RESET is enabled, because both the
        // user restriction and the policy were set by the DO.
        verify(getServices().recoverySystem).rebootWipeUserData(
                /*shutdown=*/ eq(false), anyString(), /*force=*/ eq(true),
                /*wipeEuicc=*/ eq(false));
    }

    public void testMaximumFailedPasswordAttemptsReachedDeviceOwnerDisallowed() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_FACTORY_RESET,
                UserHandle.SYSTEM))
                .thenReturn(UserManager.RESTRICTION_SOURCE_SYSTEM);

        dpm.setMaximumFailedPasswordsForWipe(admin1, 3);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);

        // DISALLOW_FACTORY_RESET was set by the system, not the DO, so the device is not wiped.
        verifyZeroInteractions(getServices().recoverySystem);
        verify(getServices().userManagerInternal, never())
                .removeUserEvenWhenDisallowed(anyInt());
    }

    public void testGetPermissionGrantState() throws Exception {
        final String permission = "some.permission";
        final String app1 = "com.example.app1";
        final String app2 = "com.example.app2";

        when(getServices().ipackageManager.checkPermission(eq(permission), eq(app1), anyInt()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        doReturn(PackageManager.FLAG_PERMISSION_POLICY_FIXED).when(getServices().packageManager)
                .getPermissionFlags(permission, app1, UserHandle.SYSTEM);
        when(getServices().packageManager.getPermissionFlags(permission, app1,
                UserHandle.of(DpmMockContext.CALLER_USER_HANDLE)))
                .thenReturn(PackageManager.FLAG_PERMISSION_POLICY_FIXED);
        when(getServices().ipackageManager.checkPermission(eq(permission), eq(app2), anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        doReturn(0).when(getServices().packageManager).getPermissionFlags(permission, app2,
                UserHandle.SYSTEM);
        when(getServices().packageManager.getPermissionFlags(permission, app2,
                UserHandle.of(DpmMockContext.CALLER_USER_HANDLE))).thenReturn(0);

        // System can retrieve permission grant state.
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.packageName = "com.example.system";
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                dpm.getPermissionGrantState(null, app1, permission));
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
                dpm.getPermissionGrantState(null, app2, permission));

        // A regular app cannot retrieve permission grant state.
        mContext.binder.callingUid = setupPackageInPackageManager(app1, 1);
        mContext.packageName = app1;
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.getPermissionGrantState(null, app1, permission));

        // Profile owner can retrieve permission grant state.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        setAsProfileOwner(admin1);
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                dpm.getPermissionGrantState(admin1, app1, permission));
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
                dpm.getPermissionGrantState(admin1, app2, permission));
    }

    public void testResetPasswordWithToken() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        // test token validation
        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ null,
                () -> dpm.setResetPasswordToken(admin1, new byte[31]));

        // test adding a token
        final byte[] token = new byte[32];
        final long handle = 123456;
        final String password = "password";
        when(getServices().lockPatternUtils.addEscrowToken(eq(token), eq(UserHandle.USER_SYSTEM),
                nullable(EscrowTokenStateChangeCallback.class)))
                .thenReturn(handle);
        assertTrue(dpm.setResetPasswordToken(admin1, token));

        // test password activation
        when(getServices().lockPatternUtils.isEscrowTokenActive(eq(handle), eq(UserHandle.USER_SYSTEM)))
            .thenReturn(true);
        assertTrue(dpm.isResetPasswordTokenActive(admin1));

        // test reset password with token
        when(getServices().lockPatternUtils.setLockCredentialWithToken(eq(password.getBytes()),
                eq(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD),
                eq(DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC), eq(handle), eq(token),
                eq(UserHandle.USER_SYSTEM)))
                .thenReturn(true);
        assertTrue(dpm.resetPasswordWithToken(admin1, password, token, 0));

        // test removing a token
        when(getServices().lockPatternUtils.removeEscrowToken(eq(handle), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(true);
        assertTrue(dpm.clearResetPasswordToken(admin1));
    }

    public void testIsActivePasswordSufficient() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        mContext.packageName = admin1.getPackageName();
        setupDeviceOwner();

        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
        dpm.setPasswordMinimumLength(admin1, 8);
        dpm.setPasswordMinimumLetters(admin1, 6);
        dpm.setPasswordMinimumLowerCase(admin1, 3);
        dpm.setPasswordMinimumUpperCase(admin1, 1);
        dpm.setPasswordMinimumNonLetter(admin1, 1);
        dpm.setPasswordMinimumNumeric(admin1, 1);
        dpm.setPasswordMinimumSymbols(admin1, 0);

        reset(mContext.spiedContext);

        PasswordMetrics passwordMetricsNoSymbols = new PasswordMetrics(
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, 9,
                8, 2,
                6, 1,
                0, 1);

        setActivePasswordState(passwordMetricsNoSymbols);
        assertTrue(dpm.isActivePasswordSufficient());

        initializeDpms();
        reset(mContext.spiedContext);
        assertTrue(dpm.isActivePasswordSufficient());

        // This call simulates the user entering the password for the first time after a reboot.
        // This causes password metrics to be reloaded into memory.  Until this happens,
        // dpm.isActivePasswordSufficient() will continue to return its last checkpointed value,
        // even if the DPC changes password requirements so that the password no longer meets the
        // requirements.  This is a known limitation of the current implementation of
        // isActivePasswordSufficient() - see b/34218769.
        setActivePasswordState(passwordMetricsNoSymbols);
        assertTrue(dpm.isActivePasswordSufficient());

        dpm.setPasswordMinimumSymbols(admin1, 1);
        // This assertion would fail if we had not called setActivePasswordState() again after
        // initializeDpms() - see previous comment.
        assertFalse(dpm.isActivePasswordSufficient());

        initializeDpms();
        reset(mContext.spiedContext);
        assertFalse(dpm.isActivePasswordSufficient());

        PasswordMetrics passwordMetricsWithSymbols = new PasswordMetrics(
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, 9,
                7, 2,
                5, 1,
                1, 2);

        setActivePasswordState(passwordMetricsWithSymbols);
        assertTrue(dpm.isActivePasswordSufficient());
    }

    public void testIsActivePasswordSufficient_noLockScreen() throws Exception {
        // If there is no lock screen, the password is considered empty no matter what, because
        // it provides no security.
        when(getServices().lockPatternUtils.hasSecureLockScreen()).thenReturn(false);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        mContext.packageName = admin1.getPackageName();
        setupDeviceOwner();

        // If no password requirements are set, isActivePasswordSufficient should succeed.
        assertTrue(dpm.isActivePasswordSufficient());

        // Now set some password quality requirements.
        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);

        reset(mContext.spiedContext);
        final int userHandle = UserHandle.getUserId(mContext.binder.callingUid);
        PasswordMetrics passwordMetricsNoSymbols = new PasswordMetrics(
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, 9,
                8, 2,
                6, 1,
                0, 1);
        // This should be ignored, as there is no lock screen.
        dpm.setActivePasswordState(passwordMetricsNoSymbols, userHandle);
        dpm.reportPasswordChanged(userHandle);

        // No broadcast should be sent.
        verify(mContext.spiedContext, times(0)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED),
                MockUtils.checkUserHandle(userHandle));

        // The active (nonexistent) password doesn't comply with the requirements.
        assertFalse(dpm.isActivePasswordSufficient());
    }

    private void setActivePasswordState(PasswordMetrics passwordMetrics)
            throws Exception {
        final int userHandle = UserHandle.getUserId(mContext.binder.callingUid);
        final long ident = mContext.binder.clearCallingIdentity();

        dpm.setActivePasswordState(passwordMetrics, userHandle);
        dpm.reportPasswordChanged(userHandle);

        // Drain ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED broadcasts as part of
        // reportPasswordChanged()
        // This broadcast should be sent 4 times:
        // * Twice from calls to DevicePolicyManagerService.updatePasswordExpirationsLocked,
        //   once for each affected user, in DevicePolicyManagerService.reportPasswordChanged.
        // * Twice from calls to DevicePolicyManagerService.saveSettingsLocked
        //   in DevicePolicyManagerService.reportPasswordChanged, once with the userId
        //   the password change is relevant to and another with the credential owner of said
        //   userId.
        verify(mContext.spiedContext, times(4)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(userHandle));

        final Intent intent = new Intent(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED);
        intent.setComponent(admin1);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(userHandle));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(userHandle));

        // CertificateMonitor.updateInstalledCertificates is called on the background thread,
        // let it finish with system uid, otherwise it will throw and crash.
        flushTasks();

        mContext.binder.restoreCallingIdentity(ident);
    }

    public void testIsCurrentInputMethodSetByOwnerForDeviceOwner() throws Exception {
        final String currentIme = Settings.Secure.DEFAULT_INPUT_METHOD;
        final Uri currentImeUri = Settings.Secure.getUriFor(currentIme);
        final int deviceOwnerUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        final int firstUserSystemUid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                DpmMockContext.SYSTEM_UID);
        final int secondUserSystemUid = UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                DpmMockContext.SYSTEM_UID);

        // Set up a device owner.
        mContext.binder.callingUid = deviceOwnerUid;
        setupDeviceOwner();

        // First and second user set IMEs manually.
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Device owner changes IME for first user.
        mContext.binder.callingUid = deviceOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme, UserHandle.USER_SYSTEM))
                .thenReturn("ime1");
        dpm.setSecureSetting(admin1, currentIme, "ime2");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime2",
                UserHandle.USER_SYSTEM);
        reset(getServices().settings);
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Second user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, DpmMockContext.CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // First user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Device owner changes IME for first user again.
        mContext.binder.callingUid = deviceOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme, UserHandle.USER_SYSTEM))
                .thenReturn("ime2");
        dpm.setSecureSetting(admin1, currentIme, "ime3");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime3",
                UserHandle.USER_SYSTEM);
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Restarting the DPMS should not lose information.
        initializeDpms();
        mContext.binder.callingUid = firstUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Device owner can find out whether it set the current IME itself.
        mContext.binder.callingUid = deviceOwnerUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());

        // Removing the device owner should clear the information that it set the current IME.
        clearDeviceOwner();
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
    }

    public void testIsCurrentInputMethodSetByOwnerForProfileOwner() throws Exception {
        final String currentIme = Settings.Secure.DEFAULT_INPUT_METHOD;
        final Uri currentImeUri = Settings.Secure.getUriFor(currentIme);
        final int profileOwnerUid = DpmMockContext.CALLER_UID;
        final int firstUserSystemUid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                DpmMockContext.SYSTEM_UID);
        final int secondUserSystemUid = UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                DpmMockContext.SYSTEM_UID);

        // Set up a profile owner.
        mContext.binder.callingUid = profileOwnerUid;
        setupProfileOwner();

        // First and second user set IMEs manually.
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Profile owner changes IME for second user.
        mContext.binder.callingUid = profileOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme,
                DpmMockContext.CALLER_USER_HANDLE)).thenReturn("ime1");
        dpm.setSecureSetting(admin1, currentIme, "ime2");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime2",
                DpmMockContext.CALLER_USER_HANDLE);
        reset(getServices().settings);
        dpms.notifyChangeToContentObserver(currentImeUri, DpmMockContext.CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());

        // First user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());

        // Second user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, DpmMockContext.CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());

        // Profile owner changes IME for second user again.
        mContext.binder.callingUid = profileOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme,
                DpmMockContext.CALLER_USER_HANDLE)).thenReturn("ime2");
        dpm.setSecureSetting(admin1, currentIme, "ime3");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime3",
                DpmMockContext.CALLER_USER_HANDLE);
        dpms.notifyChangeToContentObserver(currentImeUri, DpmMockContext.CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());

        // Restarting the DPMS should not lose information.
        initializeDpms();
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());

        // Profile owner can find out whether it set the current IME itself.
        mContext.binder.callingUid = profileOwnerUid;
        assertTrue(dpm.isCurrentInputMethodSetByOwner());

        // Removing the profile owner should clear the information that it set the current IME.
        dpm.clearProfileOwner(admin1);
        mContext.binder.callingUid = firstUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
        mContext.binder.callingUid = secondUserSystemUid;
        assertFalse(dpm.isCurrentInputMethodSetByOwner());
    }

    public void testSetPermittedCrossProfileNotificationListeners_unavailableForDo()
            throws Exception {
        // Set up a device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        assertSetPermittedCrossProfileNotificationListenersUnavailable(mContext.binder.callingUid);
    }

    public void testSetPermittedCrossProfileNotificationListeners_unavailableForPoOnUser()
            throws Exception {
        // Set up a profile owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setupProfileOwner();
        assertSetPermittedCrossProfileNotificationListenersUnavailable(mContext.binder.callingUid);
    }

    private void assertSetPermittedCrossProfileNotificationListenersUnavailable(
            int adminUid) throws Exception {
        mContext.binder.callingUid = adminUid;
        final int userId = UserHandle.getUserId(adminUid);

        final String packageName = "some.package";
        assertFalse(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, Collections.singletonList(packageName)));
        assertNull(dpms.getPermittedCrossProfileNotificationListeners(admin1));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(packageName, userId));

        // Attempt to set to empty list (which means no listener is whitelisted)
        mContext.binder.callingUid = adminUid;
        assertFalse(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, Collections.emptyList()));
        assertNull(dpms.getPermittedCrossProfileNotificationListeners(admin1));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(packageName, userId));
    }

    public void testIsNotificationListenerServicePermitted_onlySystemCanCall() throws Exception {
        // Set up a managed profile
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        final String permittedListener = "some.package";
        setupPackageInPackageManager(
                permittedListener,
                UserHandle.USER_SYSTEM, // We check the packageInfo from the primary user.
                /*appId=*/ 12345, /*flags=*/ 0);

        assertTrue(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, Collections.singletonList(permittedListener)));

        // isNotificationListenerServicePermitted should throw if not called from System.
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpms.isNotificationListenerServicePermitted(
                        permittedListener, MANAGED_PROFILE_USER_ID));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(
                permittedListener, MANAGED_PROFILE_USER_ID));
    }

    public void testSetPermittedCrossProfileNotificationListeners_managedProfile()
            throws Exception {
        // Set up a managed profile
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        final String permittedListener = "permitted.package";
        int appId = 12345;
        setupPackageInPackageManager(
                permittedListener,
                UserHandle.USER_SYSTEM,  // We check the packageInfo from the primary user.
                appId, /*flags=*/ 0);

        final String notPermittedListener = "not.permitted.package";
        setupPackageInPackageManager(
                notPermittedListener,
                UserHandle.USER_SYSTEM,  // We check the packageInfo from the primary user.
                ++appId, /*flags=*/ 0);

        final String systemListener = "system.package";
        setupPackageInPackageManager(
                systemListener,
                UserHandle.USER_SYSTEM,  // We check the packageInfo from the primary user.
                ++appId, ApplicationInfo.FLAG_SYSTEM);

        // By default all packages are allowed
        assertNull(dpms.getPermittedCrossProfileNotificationListeners(admin1));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(
                permittedListener, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                notPermittedListener, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, MANAGED_PROFILE_USER_ID));

        // Setting only one package in the whitelist
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertTrue(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, Collections.singletonList(permittedListener)));
        final List<String> permittedListeners =
                dpms.getPermittedCrossProfileNotificationListeners(admin1);
        assertEquals(1, permittedListeners.size());
        assertEquals(permittedListener, permittedListeners.get(0));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(
                permittedListener, MANAGED_PROFILE_USER_ID));
        assertFalse(dpms.isNotificationListenerServicePermitted(
                notPermittedListener, MANAGED_PROFILE_USER_ID));
        // System packages are always allowed (even if not in the whitelist)
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, MANAGED_PROFILE_USER_ID));

        // Setting an empty whitelist - only system listeners allowed
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertTrue(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, Collections.emptyList()));
        assertEquals(0, dpms.getPermittedCrossProfileNotificationListeners(admin1).size());

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertFalse(dpms.isNotificationListenerServicePermitted(
                permittedListener, MANAGED_PROFILE_USER_ID));
        assertFalse(dpms.isNotificationListenerServicePermitted(
                notPermittedListener, MANAGED_PROFILE_USER_ID));
        // System packages are always allowed (even if not in the whitelist)
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, MANAGED_PROFILE_USER_ID));

        // Setting a null whitelist - all listeners allowed
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertTrue(dpms.setPermittedCrossProfileNotificationListeners(admin1, null));
        assertNull(dpms.getPermittedCrossProfileNotificationListeners(admin1));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(
                permittedListener, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                notPermittedListener, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, MANAGED_PROFILE_USER_ID));
    }

    public void testSetPermittedCrossProfileNotificationListeners_doesNotAffectPrimaryProfile()
            throws Exception {
        // Set up a managed profile
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        final String nonSystemPackage = "non.system.package";
        int appId = 12345;
        setupPackageInPackageManager(
                nonSystemPackage,
                UserHandle.USER_SYSTEM,  // We check the packageInfo from the primary user.
                appId, /*flags=*/ 0);

        final String systemListener = "system.package";
        setupPackageInPackageManager(
                systemListener,
                UserHandle.USER_SYSTEM,  // We check the packageInfo from the primary user.
                ++appId, ApplicationInfo.FLAG_SYSTEM);

        // By default all packages are allowed (for all profiles)
        assertNull(dpms.getPermittedCrossProfileNotificationListeners(admin1));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertTrue(dpms.isNotificationListenerServicePermitted(
                nonSystemPackage, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                nonSystemPackage, UserHandle.USER_SYSTEM));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, UserHandle.USER_SYSTEM));

        // Setting an empty whitelist - only system listeners allowed in managed profile, but
        // all allowed in primary profile
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertTrue(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, Collections.emptyList()));
        assertEquals(0, dpms.getPermittedCrossProfileNotificationListeners(admin1).size());

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertFalse(dpms.isNotificationListenerServicePermitted(
                nonSystemPackage, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, MANAGED_PROFILE_USER_ID));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                nonSystemPackage, UserHandle.USER_SYSTEM));
        assertTrue(dpms.isNotificationListenerServicePermitted(
                systemListener, UserHandle.USER_SYSTEM));
    }

    public void testGetOwnerInstalledCaCertsForDeviceOwner() throws Exception {
        mServiceContext.packageName = mRealTestContext.getPackageName();
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setDeviceOwner();

        verifyCanGetOwnerInstalledCaCerts(admin1, mAdmin1Context);
    }

    public void testGetOwnerInstalledCaCertsForProfileOwner() throws Exception {
        mServiceContext.packageName = mRealTestContext.getPackageName();
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin1);

        verifyCanGetOwnerInstalledCaCerts(admin1, mAdmin1Context);
        verifyCantGetOwnerInstalledCaCertsProfileOwnerRemoval(admin1, mAdmin1Context);
    }

    public void testGetOwnerInstalledCaCertsForDelegate() throws Exception {
        mServiceContext.packageName = mRealTestContext.getPackageName();
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin1);

        final DpmMockContext caller = new DpmMockContext(getServices(), mRealTestContext);
        caller.packageName = "com.example.delegate";
        caller.binder.callingUid = setupPackageInPackageManager(caller.packageName,
                DpmMockContext.CALLER_USER_HANDLE, 20988, ApplicationInfo.FLAG_HAS_CODE);

        // Make caller a delegated cert installer.
        runAsCaller(mAdmin1Context, dpms,
                dpm -> dpm.setCertInstallerPackage(admin1, caller.packageName));

        verifyCanGetOwnerInstalledCaCerts(null, caller);
        verifyCantGetOwnerInstalledCaCertsProfileOwnerRemoval(null, caller);
    }

    public void testDisallowSharingIntoProfileSetRestriction() {
        when(mServiceContext.resources.getString(R.string.config_managed_provisioning_package))
                .thenReturn("com.android.managedprovisioning");
        Bundle restriction = new Bundle();
        restriction.putBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, true);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        RestrictionsListener listener = new RestrictionsListener(mContext);
        listener.onUserRestrictionsChanged(DpmMockContext.CALLER_USER_HANDLE, restriction,
                new Bundle());
        verifyDataSharingChangedBroadcast();
    }

    public void testDisallowSharingIntoProfileClearRestriction() {
        when(mServiceContext.resources.getString(R.string.config_managed_provisioning_package))
                .thenReturn("com.android.managedprovisioning");
        Bundle restriction = new Bundle();
        restriction.putBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, true);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        RestrictionsListener listener = new RestrictionsListener(mContext);
        listener.onUserRestrictionsChanged(DpmMockContext.CALLER_USER_HANDLE, new Bundle(),
                restriction);
        verifyDataSharingChangedBroadcast();
    }

    public void testDisallowSharingIntoProfileUnchanged() {
        RestrictionsListener listener = new RestrictionsListener(mContext);
        listener.onUserRestrictionsChanged(DpmMockContext.CALLER_USER_HANDLE, new Bundle(),
                new Bundle());
        verify(mContext.spiedContext, never()).sendBroadcastAsUser(any(), any());
    }

    private void verifyDataSharingChangedBroadcast() {
        Intent expectedIntent = new Intent(
                DevicePolicyManager.ACTION_DATA_SHARING_RESTRICTION_CHANGED);
        expectedIntent.setPackage("com.android.managedprovisioning");
        expectedIntent.putExtra(Intent.EXTRA_USER_ID, DpmMockContext.CALLER_USER_HANDLE);
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(expectedIntent),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
    }

    public void testOverrideApnAPIsFailWithPO() throws Exception {
        setupProfileOwner();
        ApnSetting apn = (new ApnSetting.Builder())
            .setApnName("test")
            .setEntryName("test")
            .setApnTypeBitmask(ApnSetting.TYPE_DEFAULT)
            .build();
        assertExpectException(SecurityException.class, null, () ->
                dpm.addOverrideApn(admin1, apn));
        assertExpectException(SecurityException.class, null, () ->
                dpm.updateOverrideApn(admin1, 0, apn));
        assertExpectException(SecurityException.class, null, () ->
                dpm.removeOverrideApn(admin1, 0));
        assertExpectException(SecurityException.class, null, () ->
                dpm.getOverrideApns(admin1));
        assertExpectException(SecurityException.class, null, () ->
                dpm.setOverrideApnsEnabled(admin1, false));
        assertExpectException(SecurityException.class, null, () ->
                dpm.isOverrideApnEnabled(admin1));
    }

    private void verifyCanGetOwnerInstalledCaCerts(
            final ComponentName caller, final DpmMockContext callerContext) throws Exception {
        final String alias = "cert";
        final byte[] caCert = TEST_CA.getBytes();

        // device admin (used for posting the tls notification)
        DpmMockContext admin1Context = mAdmin1Context;
        if (admin1.getPackageName().equals(callerContext.getPackageName())) {
            admin1Context = callerContext;
        }
        when(admin1Context.resources.getColor(anyInt(), anyObject())).thenReturn(Color.WHITE);

        // caller: device admin or delegated certificate installer
        callerContext.applicationInfo = new ApplicationInfo();
        final UserHandle callerUser = callerContext.binder.getCallingUserHandle();

        // system_server
        final DpmMockContext serviceContext = mContext;
        serviceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        getServices().addPackageContext(callerUser, admin1Context);
        getServices().addPackageContext(callerUser, callerContext);

        // Install a CA cert.
        runAsCaller(callerContext, dpms, (dpm) -> {
            when(getServices().keyChainConnection.getService().installCaCertificate(caCert))
                        .thenReturn(alias);
            assertTrue(dpm.installCaCert(caller, caCert));
            when(getServices().keyChainConnection.getService().getUserCaAliases())
                    .thenReturn(asSlice(new String[] {alias}));
        });

        getServices().injectBroadcast(mServiceContext, new Intent(KeyChain.ACTION_TRUST_STORE_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, callerUser.getIdentifier()),
                callerUser.getIdentifier());
        flushTasks();

        final List<String> ownerInstalledCaCerts = new ArrayList<>();

        // Device Owner / Profile Owner can find out which CA certs were installed by itself.
        runAsCaller(admin1Context, dpms, (dpm) -> {
            final List<String> installedCaCerts = dpm.getOwnerInstalledCaCerts(callerUser);
            assertEquals(Collections.singletonList(alias), installedCaCerts);
            ownerInstalledCaCerts.addAll(installedCaCerts);
        });

        // Restarting the DPMS should not lose information.
        initializeDpms();
        runAsCaller(admin1Context, dpms, (dpm) ->
                assertEquals(ownerInstalledCaCerts, dpm.getOwnerInstalledCaCerts(callerUser)));

        // System can find out which CA certs were installed by the Device Owner / Profile Owner.
        runAsCaller(serviceContext, dpms, (dpm) -> {
            assertEquals(ownerInstalledCaCerts, dpm.getOwnerInstalledCaCerts(callerUser));

            // Remove the CA cert.
            reset(getServices().keyChainConnection.getService());
        });

        getServices().injectBroadcast(mServiceContext, new Intent(KeyChain.ACTION_TRUST_STORE_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, callerUser.getIdentifier()),
                callerUser.getIdentifier());
        flushTasks();

        // Verify that the CA cert is no longer reported as installed by the Device Owner / Profile
        // Owner.
        runAsCaller(admin1Context, dpms, (dpm) -> {
            MoreAsserts.assertEmpty(dpm.getOwnerInstalledCaCerts(callerUser));
        });
    }

    private void verifyCantGetOwnerInstalledCaCertsProfileOwnerRemoval(
            final ComponentName callerName, final DpmMockContext callerContext) throws Exception {
        final String alias = "cert";
        final byte[] caCert = TEST_CA.getBytes();

        // device admin (used for posting the tls notification)
        DpmMockContext admin1Context = mAdmin1Context;
        if (admin1.getPackageName().equals(callerContext.getPackageName())) {
            admin1Context = callerContext;
        }
        when(admin1Context.resources.getColor(anyInt(), anyObject())).thenReturn(Color.WHITE);

        // caller: device admin or delegated certificate installer
        callerContext.applicationInfo = new ApplicationInfo();
        final UserHandle callerUser = callerContext.binder.getCallingUserHandle();

        // system_server
        final DpmMockContext serviceContext = mContext;
        serviceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        getServices().addPackageContext(callerUser, admin1Context);
        getServices().addPackageContext(callerUser, callerContext);

        // Install a CA cert as caller
        runAsCaller(callerContext, dpms, (dpm) -> {
            when(getServices().keyChainConnection.getService().installCaCertificate(caCert))
                    .thenReturn(alias);
            assertTrue(dpm.installCaCert(callerName, caCert));
        });

        // Fake the CA cert as having been installed
        when(getServices().keyChainConnection.getService().getUserCaAliases())
                .thenReturn(asSlice(new String[] {alias}));
        getServices().injectBroadcast(mServiceContext, new Intent(KeyChain.ACTION_TRUST_STORE_CHANGED)
                .putExtra(Intent.EXTRA_USER_HANDLE, callerUser.getIdentifier()),
                callerUser.getIdentifier());
        flushTasks();

        // Removing the Profile Owner should clear the information on which CA certs were installed
        runAsCaller(admin1Context, dpms, dpm -> dpm.clearProfileOwner(admin1));

        runAsCaller(serviceContext, dpms, (dpm) -> {
            final List<String> ownerInstalledCaCerts = dpm.getOwnerInstalledCaCerts(callerUser);
            assertNotNull(ownerInstalledCaCerts);
            assertTrue(ownerInstalledCaCerts.isEmpty());
        });
    }

    private void assertAttestationFlags(int attestationFlags, int[] expectedFlags) {
        int[] gotFlags = DevicePolicyManagerService.translateIdAttestationFlags(attestationFlags);
        Arrays.sort(gotFlags);
        Arrays.sort(expectedFlags);
        assertTrue(Arrays.equals(expectedFlags, gotFlags));
    }

    public void testTranslationOfIdAttestationFlag() {
        int[] allIdTypes = new int[]{ID_TYPE_SERIAL, ID_TYPE_IMEI, ID_TYPE_MEID};
        int[] correspondingAttUtilsTypes = new int[]{
            AttestationUtils.ID_TYPE_SERIAL, AttestationUtils.ID_TYPE_IMEI,
            AttestationUtils.ID_TYPE_MEID};

        // Test translation of zero flags
        assertNull(DevicePolicyManagerService.translateIdAttestationFlags(0));

        // Test translation of the ID_TYPE_BASE_INFO flag, which should yield an empty, but
        // non-null array
        assertAttestationFlags(ID_TYPE_BASE_INFO, new int[] {});

        // Test translation of a single flag
        assertAttestationFlags(ID_TYPE_BASE_INFO | ID_TYPE_SERIAL,
                new int[] {AttestationUtils.ID_TYPE_SERIAL});
        assertAttestationFlags(ID_TYPE_SERIAL, new int[] {AttestationUtils.ID_TYPE_SERIAL});

        // Test translation of two flags
        assertAttestationFlags(ID_TYPE_SERIAL | ID_TYPE_IMEI,
                new int[] {AttestationUtils.ID_TYPE_IMEI, AttestationUtils.ID_TYPE_SERIAL});
        assertAttestationFlags(ID_TYPE_BASE_INFO | ID_TYPE_MEID | ID_TYPE_SERIAL,
                new int[] {AttestationUtils.ID_TYPE_MEID, AttestationUtils.ID_TYPE_SERIAL});

        // Test translation of all three flags
        assertAttestationFlags(ID_TYPE_SERIAL | ID_TYPE_IMEI | ID_TYPE_MEID,
                new int[] {AttestationUtils.ID_TYPE_IMEI, AttestationUtils.ID_TYPE_SERIAL,
                    AttestationUtils.ID_TYPE_MEID});
        // Test translation of all three flags
        assertAttestationFlags(ID_TYPE_SERIAL | ID_TYPE_IMEI | ID_TYPE_MEID | ID_TYPE_BASE_INFO,
                new int[] {AttestationUtils.ID_TYPE_IMEI, AttestationUtils.ID_TYPE_SERIAL,
                    AttestationUtils.ID_TYPE_MEID});
    }

    public void testRevertDeviceOwnership_noMetadataFile() throws Exception {
        setDeviceOwner();
        initializeDpms();
        assertFalse(getMockTransferMetadataManager().metadataFileExists());
        assertTrue(dpms.isDeviceOwner(admin1, UserHandle.USER_SYSTEM));
        assertTrue(dpms.isAdminActive(admin1, UserHandle.USER_SYSTEM));
    }

    public void testRevertDeviceOwnership_adminAndDeviceMigrated() throws Exception {
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getDeviceOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.device_owner_migrated),
                getDeviceOwnerFile());
        assertDeviceOwnershipRevertedWithFakeTransferMetadata();
    }

    public void testRevertDeviceOwnership_deviceNotMigrated()
            throws Exception {
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getDeviceOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.device_owner_not_migrated),
                getDeviceOwnerFile());
        assertDeviceOwnershipRevertedWithFakeTransferMetadata();
    }

    public void testRevertDeviceOwnership_adminAndDeviceNotMigrated()
            throws Exception {
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_not_migrated),
                getDeviceOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.device_owner_not_migrated),
                getDeviceOwnerFile());
        assertDeviceOwnershipRevertedWithFakeTransferMetadata();
    }

    public void testRevertProfileOwnership_noMetadataFile() throws Exception {
        setupProfileOwner();
        initializeDpms();
        assertFalse(getMockTransferMetadataManager().metadataFileExists());
        assertTrue(dpms.isProfileOwner(admin1, DpmMockContext.CALLER_USER_HANDLE));
        assertTrue(dpms.isAdminActive(admin1, DpmMockContext.CALLER_USER_HANDLE));
        UserHandle userHandle = UserHandle.of(DpmMockContext.CALLER_USER_HANDLE);
    }

    public void testRevertProfileOwnership_adminAndProfileMigrated() throws Exception {
        getServices().addUser(DpmMockContext.CALLER_USER_HANDLE, UserInfo.FLAG_MANAGED_PROFILE,
                UserHandle.USER_SYSTEM);
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getProfileOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.profile_owner_migrated),
                getProfileOwnerFile());
        assertProfileOwnershipRevertedWithFakeTransferMetadata();
    }

    public void testRevertProfileOwnership_profileNotMigrated() throws Exception {
        getServices().addUser(DpmMockContext.CALLER_USER_HANDLE, UserInfo.FLAG_MANAGED_PROFILE,
                UserHandle.USER_SYSTEM);
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getProfileOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.profile_owner_not_migrated),
                getProfileOwnerFile());
        assertProfileOwnershipRevertedWithFakeTransferMetadata();
    }

    public void testRevertProfileOwnership_adminAndProfileNotMigrated() throws Exception {
        getServices().addUser(DpmMockContext.CALLER_USER_HANDLE, UserInfo.FLAG_MANAGED_PROFILE,
                UserHandle.USER_SYSTEM);
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_not_migrated),
                getProfileOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.profile_owner_not_migrated),
                getProfileOwnerFile());
        assertProfileOwnershipRevertedWithFakeTransferMetadata();
    }

    public void testGrantDeviceIdsAccess_notToProfileOwner() throws Exception {
        setupProfileOwner();
        configureContextForAccess(mContext, false);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setProfileOwnerCanAccessDeviceIdsForUser(admin2,
                        UserHandle.of(DpmMockContext.CALLER_UID)));
    }

    public void testGrantDeviceIdsAccess_notByAuthorizedCaller() throws Exception {
        setupProfileOwner();
        configureContextForAccess(mContext, false);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setProfileOwnerCanAccessDeviceIdsForUser(admin1,
                        UserHandle.of(DpmMockContext.CALLER_UID)));
    }

    public void testGrantDeviceIdsAccess_byAuthorizedSystemCaller() throws Exception {
        setupProfileOwner();

        // This method will throw if the system context could not call
        // setProfileOwnerCanAccessDeviceIds successfully.
        configureProfileOwnerForDeviceIdAccess(admin1, DpmMockContext.CALLER_USER_HANDLE);
    }

    private static void configureContextForAccess(DpmMockContext context, boolean granted) {
        when(context.spiedContext.checkCallingPermission(
                android.Manifest.permission.GRANT_PROFILE_OWNER_DEVICE_IDS_ACCESS))
                .thenReturn(granted ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED);
    }

    public void testGrantDeviceIdsAccess_byAuthorizedManagedProvisioning() throws Exception {
        setupProfileOwner();

        final long ident = mServiceContext.binder.clearCallingIdentity();
        configureContextForAccess(mServiceContext, true);

        mServiceContext.binder.callingUid =
                UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                        DpmMockContext.CALLER_MANAGED_PROVISIONING_UID);
        try {
            runAsCaller(mServiceContext, dpms, dpm -> {
                dpm.setProfileOwnerCanAccessDeviceIdsForUser(admin1,
                        UserHandle.of(DpmMockContext.CALLER_USER_HANDLE));
            });
        } finally {
            mServiceContext.binder.restoreCallingIdentity(ident);
        }
    }

    public void testEnforceCallerCanRequestDeviceIdAttestation_deviceOwnerCaller()
            throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        configureContextForAccess(mContext, false);

        // Device owner should be allowed to request Device ID attestation.
        dpms.enforceCallerCanRequestDeviceIdAttestation(admin1, admin1.getPackageName(),
                DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Another package must not be allowed to request Device ID attestation.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(null,
                        admin2.getPackageName(), DpmMockContext.CALLER_UID));
        // Another component that is not the admin must not be allowed to request Device ID
        // attestation.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(admin2,
                        admin1.getPackageName(), DpmMockContext.CALLER_UID));
    }

    public void testEnforceCallerCanRequestDeviceIdAttestation_profileOwnerCaller()
            throws Exception {
        configureContextForAccess(mContext, false);

        // Make sure a security exception is thrown if the device has no profile owner.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(admin1,
                        admin1.getPackageName(), DpmMockContext.CALLER_SYSTEM_USER_UID));

        setupProfileOwner();
        configureProfileOwnerForDeviceIdAccess(admin1, DpmMockContext.CALLER_USER_HANDLE);

        // The profile owner is allowed to request Device ID attestation.
        mServiceContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpms.enforceCallerCanRequestDeviceIdAttestation(admin1, admin1.getPackageName(),
                DpmMockContext.CALLER_UID);
        // But not another package.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(null,
                        admin2.getPackageName(), DpmMockContext.CALLER_UID));
        // Or another component which is not the admin.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(admin2,
                        admin2.getPackageName(), DpmMockContext.CALLER_UID));
    }

    public void runAsDelegatedCertInstaller(DpmRunnable action) throws Exception {
        final long ident = mServiceContext.binder.clearCallingIdentity();

        mServiceContext.binder.callingUid = UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                DpmMockContext.DELEGATE_CERT_INSTALLER_UID);
        try {
            runAsCaller(mServiceContext, dpms, action);
        } finally {
            mServiceContext.binder.restoreCallingIdentity(ident);
        }
    }

    public void testEnforceCallerCanRequestDeviceIdAttestation_delegateCaller() throws Exception {
        setupProfileOwner();
        markDelegatedCertInstallerAsInstalled();

        // Configure a delegated cert installer.
        runAsCaller(mServiceContext, dpms,
                dpm -> dpm.setDelegatedScopes(admin1, DpmMockContext.DELEGATE_PACKAGE_NAME,
                        Arrays.asList(DELEGATION_CERT_INSTALL)));

        configureProfileOwnerForDeviceIdAccess(admin1, DpmMockContext.CALLER_USER_HANDLE);

        // Make sure that the profile owner can still request Device ID attestation.
        mServiceContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpms.enforceCallerCanRequestDeviceIdAttestation(admin1, admin1.getPackageName(),
                DpmMockContext.CALLER_UID);

        runAsDelegatedCertInstaller(dpm -> {
            dpms.enforceCallerCanRequestDeviceIdAttestation(null,
                    DpmMockContext.DELEGATE_PACKAGE_NAME,
                    UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                            DpmMockContext.DELEGATE_CERT_INSTALLER_UID));
        });
    }

    public void testEnforceCallerCanRequestDeviceIdAttestation_delegateCallerWithoutPermissions()
            throws Exception {
        setupProfileOwner();
        markDelegatedCertInstallerAsInstalled();

        // Configure a delegated cert installer.
        runAsCaller(mServiceContext, dpms,
                dpm -> dpm.setDelegatedScopes(admin1, DpmMockContext.DELEGATE_PACKAGE_NAME,
                        Arrays.asList(DELEGATION_CERT_INSTALL)));


        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(admin1,
                        admin1.getPackageName(),
                        DpmMockContext.CALLER_UID));

        runAsDelegatedCertInstaller(dpm -> {
            assertExpectException(SecurityException.class, /* messageRegex= */ null,
                    () -> dpms.enforceCallerCanRequestDeviceIdAttestation(null,
                            DpmMockContext.DELEGATE_PACKAGE_NAME,
                            UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                                    DpmMockContext.DELEGATE_CERT_INSTALLER_UID)));
        });
    }

    public void testGetPasswordComplexity_securityExceptionIfParentInstance() {
        assertThrows(SecurityException.class,
                () -> new DevicePolicyManagerTestable(
                        mServiceContext,
                        dpms,
                        /* parentInstance= */ true)
                        .getPasswordComplexity());
    }

    public void testGetPasswordComplexity_illegalStateExceptionIfLocked() {
        when(getServices().userManager.isUserUnlocked(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(false);
        assertThrows(IllegalStateException.class, () -> dpm.getPasswordComplexity());
    }

    public void testGetPasswordComplexity_securityExceptionWithoutPermissions() {
        when(getServices().userManager.isUserUnlocked(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(true);
        assertThrows(SecurityException.class, () -> dpm.getPasswordComplexity());
    }


    public void testGetPasswordComplexity_currentUserNoPassword() {
        when(getServices().userManager.isUserUnlocked(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(true);
        mServiceContext.permissions.add(permission.REQUEST_SCREEN_LOCK_COMPLEXITY);
        when(getServices().userManager.getCredentialOwnerProfile(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(DpmMockContext.CALLER_USER_HANDLE);

        assertEquals(PASSWORD_COMPLEXITY_NONE, dpm.getPasswordComplexity());
    }

    public void testGetPasswordComplexity_currentUserHasPassword() {
        when(getServices().userManager.isUserUnlocked(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(true);
        mServiceContext.permissions.add(permission.REQUEST_SCREEN_LOCK_COMPLEXITY);
        when(getServices().userManager.getCredentialOwnerProfile(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(DpmMockContext.CALLER_USER_HANDLE);
        dpms.mUserPasswordMetrics.put(
                DpmMockContext.CALLER_USER_HANDLE,
                PasswordMetrics.computeForPassword("asdf".getBytes()));

        assertEquals(PASSWORD_COMPLEXITY_MEDIUM, dpm.getPasswordComplexity());
    }

    public void testGetPasswordComplexity_unifiedChallengeReturnsParentUserPassword() {
        when(getServices().userManager.isUserUnlocked(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(true);
        mServiceContext.permissions.add(permission.REQUEST_SCREEN_LOCK_COMPLEXITY);

        UserInfo parentUser = new UserInfo();
        parentUser.id = DpmMockContext.CALLER_USER_HANDLE + 10;
        when(getServices().userManager.getCredentialOwnerProfile(DpmMockContext.CALLER_USER_HANDLE))
                .thenReturn(parentUser.id);

        dpms.mUserPasswordMetrics.put(
                DpmMockContext.CALLER_USER_HANDLE,
                PasswordMetrics.computeForPassword("asdf".getBytes()));
        dpms.mUserPasswordMetrics.put(
                parentUser.id,
                PasswordMetrics.computeForPassword("parentUser".getBytes()));

        assertEquals(PASSWORD_COMPLEXITY_HIGH, dpm.getPasswordComplexity());
    }

    public void testCrossProfileCalendarPackages_initiallyEmpty() {
        setAsProfileOwner(admin1);
        final Set<String> packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertCrossProfileCalendarPackagesEqual(packages, Collections.emptySet());
    }

    public void testCrossProfileCalendarPackages_reopenDpms() {
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, null);
        Set<String> packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertTrue(packages == null);
        initializeDpms();
        packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertTrue(packages == null);

        dpm.setCrossProfileCalendarPackages(admin1, Collections.emptySet());
        packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertCrossProfileCalendarPackagesEqual(packages, Collections.emptySet());
        initializeDpms();
        packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertCrossProfileCalendarPackagesEqual(packages, Collections.emptySet());

        final String dummyPackageName = "test";
        final Set<String> testPackages = new ArraySet<String>(Arrays.asList(dummyPackageName));
        dpm.setCrossProfileCalendarPackages(admin1, testPackages);
        packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertCrossProfileCalendarPackagesEqual(packages, testPackages);
        initializeDpms();
        packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertCrossProfileCalendarPackagesEqual(packages, testPackages);
    }

    private void assertCrossProfileCalendarPackagesEqual(Set<String> expected, Set<String> actual) {
        assertTrue(expected != null);
        assertTrue(actual != null);
        assertTrue(expected.containsAll(actual));
        assertTrue(actual.containsAll(expected));
    }

    public void testIsPackageAllowedToAccessCalendar_adminNotAllowed() {
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, Collections.emptySet());
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, DpmMockContext.CALLER_USER_HANDLE)).thenReturn(1);
        assertFalse(dpm.isPackageAllowedToAccessCalendar("TEST_PACKAGE"));
    }

    public void testIsPackageAllowedToAccessCalendar_settingOff() {
        final String testPackage = "TEST_PACKAGE";
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, Collections.singleton(testPackage));
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, DpmMockContext.CALLER_USER_HANDLE)).thenReturn(0);
        assertFalse(dpm.isPackageAllowedToAccessCalendar(testPackage));
    }

    public void testIsPackageAllowedToAccessCalendar_bothAllowed() {
        final String testPackage = "TEST_PACKAGE";
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, null);
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, DpmMockContext.CALLER_USER_HANDLE)).thenReturn(1);
        assertTrue(dpm.isPackageAllowedToAccessCalendar(testPackage));
    }

    private void configureProfileOwnerForDeviceIdAccess(ComponentName who, int userId) {
        final long ident = mServiceContext.binder.clearCallingIdentity();
        mServiceContext.binder.callingUid =
                UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE, DpmMockContext.SYSTEM_UID);
        runAsCaller(mServiceContext, dpms, dpm -> {
            dpm.setProfileOwnerCanAccessDeviceIdsForUser(who, UserHandle.of(userId));
        });
        mServiceContext.binder.restoreCallingIdentity(ident);
    }

    // admin1 is the outgoing DPC, adminAnotherPakcage is the incoming one.
    private void assertDeviceOwnershipRevertedWithFakeTransferMetadata() throws Exception {
        writeFakeTransferMetadataFile(UserHandle.USER_SYSTEM,
                TransferOwnershipMetadataManager.ADMIN_TYPE_DEVICE_OWNER);

        final long ident = mServiceContext.binder.clearCallingIdentity();
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForFakeAdmin(adminAnotherPackage,
                DpmMockContext.CALLER_SYSTEM_USER_UID, admin1);
        // To simulate a reboot, we just reinitialize dpms and call systemReady
        initializeDpms();

        assertTrue(dpm.isDeviceOwnerApp(admin1.getPackageName()));
        assertFalse(dpm.isDeviceOwnerApp(adminAnotherPackage.getPackageName()));
        assertFalse(dpm.isAdminActive(adminAnotherPackage));
        assertTrue(dpm.isAdminActive(admin1));
        assertTrue(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName()));
        assertEquals(admin1, dpm.getDeviceOwnerComponentOnCallingUser());

        assertTrue(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName()));
        assertEquals(admin1, dpm.getDeviceOwnerComponentOnAnyUser());
        assertEquals(UserHandle.USER_SYSTEM, dpm.getDeviceOwnerUserId());
        assertFalse(getMockTransferMetadataManager().metadataFileExists());

        mServiceContext.binder.restoreCallingIdentity(ident);
    }

    // admin1 is the outgoing DPC, adminAnotherPakcage is the incoming one.
    private void assertProfileOwnershipRevertedWithFakeTransferMetadata() throws Exception {
        writeFakeTransferMetadataFile(DpmMockContext.CALLER_USER_HANDLE,
                TransferOwnershipMetadataManager.ADMIN_TYPE_PROFILE_OWNER);

        int uid = UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin1, uid);
        setUpPackageManagerForFakeAdmin(adminAnotherPackage, uid, admin1);
        // To simulate a reboot, we just reinitialize dpms and call systemReady
        initializeDpms();

        assertTrue(dpm.isProfileOwnerApp(admin1.getPackageName()));
        assertTrue(dpm.isAdminActive(admin1));
        assertFalse(dpm.isProfileOwnerApp(adminAnotherPackage.getPackageName()));
        assertFalse(dpm.isAdminActive(adminAnotherPackage));
        assertEquals(dpm.getProfileOwnerAsUser(DpmMockContext.CALLER_USER_HANDLE), admin1);
        assertFalse(getMockTransferMetadataManager().metadataFileExists());
    }

    private void writeFakeTransferMetadataFile(int callerUserHandle, String adminType) {
        TransferOwnershipMetadataManager metadataManager = getMockTransferMetadataManager();
        metadataManager.deleteMetadataFile();

        final TransferOwnershipMetadataManager.Metadata metadata =
                new TransferOwnershipMetadataManager.Metadata(
                        admin1.flattenToString(), adminAnotherPackage.flattenToString(),
                        callerUserHandle,
                        adminType);
        metadataManager.saveMetadataFile(metadata);
    }

    private File getDeviceOwnerFile() {
        return dpms.mOwners.getDeviceOwnerFile();
    }

    private File getProfileOwnerFile() {
        return dpms.mOwners.getProfileOwnerFile(DpmMockContext.CALLER_USER_HANDLE);
    }

    private File getProfileOwnerPoliciesFile() {
        File parentDir = dpms.mMockInjector.environmentGetUserSystemDirectory(
                DpmMockContext.CALLER_USER_HANDLE);
        return getPoliciesFile(parentDir);
    }

    private File getDeviceOwnerPoliciesFile() {
        return getPoliciesFile(getServices().systemUserDataDir);
    }

    private File getPoliciesFile(File parentDir) {
        return new File(parentDir, "device_policies.xml");
    }

    private InputStream getRawStream(@RawRes int id) {
        return mRealTestContext.getResources().openRawResource(id);
    }

    private void setUserSetupCompleteForUser(boolean isUserSetupComplete, int userhandle) {
        when(getServices().settings.settingsSecureGetIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 0,
                userhandle)).thenReturn(isUserSetupComplete ? 1 : 0);
        dpms.notifyChangeToContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), userhandle);
    }

    private void assertProvisioningAllowed(String action, boolean expected) {
        assertEquals("isProvisioningAllowed(" + action + ") returning unexpected result", expected,
                dpm.isProvisioningAllowed(action));
    }

    private void assertProvisioningAllowed(String action, boolean expected, String packageName,
            int uid) {
        final String previousPackageName = mContext.packageName;
        final int previousUid = mMockContext.binder.callingUid;

        // Call assertProvisioningAllowed with the packageName / uid passed as arguments.
        mContext.packageName = packageName;
        mMockContext.binder.callingUid = uid;
        assertProvisioningAllowed(action, expected);

        // Set the previous package name / calling uid to go back to the initial state.
        mContext.packageName = previousPackageName;
        mMockContext.binder.callingUid = previousUid;
    }

    private void assertCheckProvisioningPreCondition(String action, int provisioningCondition) {
        assertCheckProvisioningPreCondition(action, admin1.getPackageName(), provisioningCondition);
    }

    private void assertCheckProvisioningPreCondition(
            String action, String packageName, int provisioningCondition) {
        assertEquals("checkProvisioningPreCondition("
                        + action + ", " + packageName + ") returning unexpected result",
                provisioningCondition, dpm.checkProvisioningPreCondition(action, packageName));
    }

    /**
     * Setup a managed profile with the specified admin and its uid.
     * @param admin ComponentName that's visible to the test code, which doesn't have to exist.
     * @param adminUid uid of the admin package.
     * @param copyFromAdmin package information for {@code admin} will be built based on this
     *     component's information.
     */
    private void addManagedProfile(
            ComponentName admin, int adminUid, ComponentName copyFromAdmin) throws Exception {
        final int userId = UserHandle.getUserId(adminUid);
        getServices().addUser(userId, UserInfo.FLAG_MANAGED_PROFILE, UserHandle.USER_SYSTEM);
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);
        setUpPackageManagerForFakeAdmin(admin, adminUid, copyFromAdmin);
        dpm.setActiveAdmin(admin, false, userId);
        assertTrue(dpm.setProfileOwner(admin, null, userId));
        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    /**
     * Convert String[] to StringParceledListSlice.
     */
    private static StringParceledListSlice asSlice(String[] s) {
        return new StringParceledListSlice(Arrays.asList(s));
    }

    private void flushTasks() throws Exception {
        dpms.mHandler.runWithScissors(() -> {}, 0 /*now*/);
        dpms.mBackgroundHandler.runWithScissors(() -> {}, 0 /*now*/);

        // We can't let exceptions happen on the background thread. Throw them here if they happen
        // so they still cause the test to fail despite being suppressed.
        getServices().rethrowBackgroundBroadcastExceptions();
    }
}
