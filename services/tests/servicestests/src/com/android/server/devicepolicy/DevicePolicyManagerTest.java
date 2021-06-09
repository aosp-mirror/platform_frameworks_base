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

import static android.app.Notification.EXTRA_TEXT;
import static android.app.Notification.EXTRA_TITLE;
import static android.app.admin.DevicePolicyManager.ACTION_CHECK_POLICY_COMPLIANCE;
import static android.app.admin.DevicePolicyManager.DELEGATION_APP_RESTRICTIONS;
import static android.app.admin.DevicePolicyManager.DELEGATION_CERT_INSTALL;
import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_DEFAULT;
import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.app.admin.DevicePolicyManager.ID_TYPE_BASE_INFO;
import static android.app.admin.DevicePolicyManager.ID_TYPE_IMEI;
import static android.app.admin.DevicePolicyManager.ID_TYPE_MEID;
import static android.app.admin.DevicePolicyManager.ID_TYPE_SERIAL;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_LOW;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
import static android.app.admin.DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR;
import static android.app.admin.DevicePolicyManager.WIPE_EUICC;
import static android.app.admin.PasswordMetrics.computeForPasswordOrPin;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE;
import static android.net.InetAddresses.parseNumericAddress;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.EscrowTokenStateChangeCallback;
import static com.android.server.devicepolicy.DevicePolicyManagerService.ACTION_PROFILE_OFF_DEADLINE;
import static com.android.server.devicepolicy.DevicePolicyManagerService.ACTION_TURN_PROFILE_ON_NOTIFICATION;
import static com.android.server.devicepolicy.DpmMockContext.CALLER_USER_HANDLE;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.testutils.TestUtils.assertExpectException;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.clearInvocations;
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

import static java.util.Collections.emptyList;

import android.Manifest.permission;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.FactoryResetProtectionPolicy;
import android.app.admin.PasswordMetrics;
import android.app.admin.SystemUpdatePolicy;
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
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.FlakyTest;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.security.KeyChain;
import android.security.keystore.AttestationUtils;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.test.MoreAsserts; // TODO(b/171932723): replace by Truth
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.devicepolicy.DevicePolicyManagerService.RestrictionsListener;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.internal.util.collections.Sets;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
 *
 * <p>Run this test with:
 *
 * {@code atest FrameworksServicesTests:com.android.server.devicepolicy.DevicePolicyManagerTest}
 *
 */
@SmallTest
@Presubmit
public class DevicePolicyManagerTest extends DpmTestBase {

    private static final String TAG = DevicePolicyManagerTest.class.getSimpleName();

    private static final List<String> OWNER_SETUP_PERMISSIONS = Arrays.asList(
            permission.MANAGE_DEVICE_ADMINS, permission.MANAGE_PROFILE_AND_DEVICE_OWNERS,
            permission.MANAGE_USERS, permission.INTERACT_ACROSS_USERS_FULL);
    public static final String NOT_DEVICE_OWNER_MSG = "does not own the device";
    public static final String NOT_PROFILE_OWNER_MSG = "does not own the profile";
    public static final String NOT_ORG_OWNED_PROFILE_OWNER_MSG =
            "not the profile owner on organization-owned device";
    public static final String INVALID_CALLING_IDENTITY_MSG = "Calling identity is not authorized";
    public static final String ONGOING_CALL_MSG = "ongoing call on the device";

    // TODO replace all instances of this with explicit {@link #mServiceContext}.
    @Deprecated
    private DpmMockContext mContext;

    private DpmMockContext mServiceContext;
    private DpmMockContext mAdmin1Context;
    public DevicePolicyManager dpm;
    public DevicePolicyManager parentDpm;
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

    // Constants for testing setManagedProfileMaximumTimeOff:
    // Profile maximum time off value
    private static final long PROFILE_OFF_TIMEOUT = TimeUnit.DAYS.toMillis(5);
    // Synthetic time at the beginning of test.
    private static final long PROFILE_OFF_START = 1;
    // Time when warning notification should be posted,
    private static final long PROFILE_OFF_WARNING_TIME =
            PROFILE_OFF_START + PROFILE_OFF_TIMEOUT - TimeUnit.DAYS.toMillis(1);
    // Time when the apps should be suspended
    private static final long PROFILE_OFF_DEADLINE = PROFILE_OFF_START + PROFILE_OFF_TIMEOUT;
    // Notification title and text for setManagedProfileMaximumTimeOff tests:
    private static final String PROFILE_OFF_SUSPENSION_TITLE = "suspension_title";
    private static final String PROFILE_OFF_SUSPENSION_TEXT = "suspension_text";
    private static final String PROFILE_OFF_SUSPENSION_SOON_TEXT = "suspension_tomorrow_text";

    @Before
    public void setUp() throws Exception {

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

        // Make createContextAsUser to work.
        mContext.packageName = "com.android.frameworks.servicestests";
        getServices().addPackageContext(UserHandle.of(0), mContext);
        getServices().addPackageContext(UserHandle.of(CALLER_USER_HANDLE), mContext);

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

    @After
    public void tearDown() throws Exception {
        flushTasks(dpms);
        getMockTransferMetadataManager().deleteMetadataFile();
    }

    private void initializeDpms() {
        // Need clearCallingIdentity() to pass permission checks.
        final long ident = mContext.binder.clearCallingIdentity();
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);

        dpms = new DevicePolicyManagerServiceTestable(getServices(), mContext);
        dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
        dpms.systemReady(SystemService.PHASE_BOOT_COMPLETED);

        dpm = new DevicePolicyManagerTestable(mContext, dpms);

        parentDpm = new DevicePolicyManagerTestable(mServiceContext, dpms,
                /* parentInstance= */true);

        mContext.binder.restoreCallingIdentity(ident);
    }

    private void setUpUserManager() {
        // Emulate UserManager.set/getApplicationRestriction().
        final Map<Pair<String, UserHandle>, Bundle> appRestrictions = new HashMap<>();

        // UM.setApplicationRestrictions() will save to appRestrictions.
        doAnswer((Answer<Void>) invocation -> {
            String pkg = (String) invocation.getArguments()[0];
            Bundle bundle = (Bundle) invocation.getArguments()[1];
            UserHandle user = (UserHandle) invocation.getArguments()[2];

            appRestrictions.put(Pair.create(pkg, user), bundle);

            return null;
        }).when(getServices().userManager).setApplicationRestrictions(
                anyString(), nullable(Bundle.class), any(UserHandle.class));

        // UM.getApplicationRestrictions() will read from appRestrictions.
        doAnswer((Answer<Bundle>) invocation -> {
            String pkg = (String) invocation.getArguments()[0];
            UserHandle user = (UserHandle) invocation.getArguments()[1];

            return appRestrictions.get(Pair.create(pkg, user));
        }).when(getServices().userManager).getApplicationRestrictions(
                anyString(), any(UserHandle.class));

        // Emulate UserManager.setUserRestriction/getUserRestrictions
        final Map<UserHandle, Bundle> userRestrictions = new HashMap<>();

        doAnswer((Answer<Void>) invocation -> {
            String key = (String) invocation.getArguments()[0];
            boolean value = (Boolean) invocation.getArguments()[1];
            UserHandle user = (UserHandle) invocation.getArguments()[2];
            Bundle userBundle = userRestrictions.getOrDefault(user, new Bundle());
            userBundle.putBoolean(key, value);

            userRestrictions.put(user, userBundle);
            return null;
        }).when(getServices().userManager).setUserRestriction(
                anyString(), anyBoolean(), any(UserHandle.class));

        doAnswer((Answer<Boolean>) invocation -> {
            String key = (String) invocation.getArguments()[0];
            UserHandle user = (UserHandle) invocation.getArguments()[1];
            Bundle userBundle = userRestrictions.getOrDefault(user, new Bundle());
            return userBundle.getBoolean(key);
        }).when(getServices().userManager).hasUserRestriction(
                anyString(), any(UserHandle.class));

        // Add the first secondary user.
        getServices().addUser(CALLER_USER_HANDLE, 0, UserManager.USER_TYPE_FULL_SECONDARY);
    }

    private void setAsProfileOwner(ComponentName admin) {
        final long ident = mServiceContext.binder.clearCallingIdentity();

        mServiceContext.binder.callingUid =
                UserHandle.getUid(CALLER_USER_HANDLE, DpmMockContext.SYSTEM_UID);
        runAsCaller(mServiceContext, dpms, dpm -> {
            // PO needs to be a DA.
            dpm.setActiveAdmin(admin, /*replace=*/ false);
            // Fire!
            assertThat(dpm.setProfileOwner(admin, "owner-name", CALLER_USER_HANDLE)).isTrue();
            // Check
            assertThat(dpm.getProfileOwnerAsUser(CALLER_USER_HANDLE)).isEqualTo(admin);
        });

        mServiceContext.binder.restoreCallingIdentity(ident);
    }

    @Test
    public void testHasNoFeature() throws Exception {
        when(getServices().packageManager.hasSystemFeature(eq(PackageManager.FEATURE_DEVICE_ADMIN)))
                .thenReturn(false);

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        new DevicePolicyManagerServiceTestable(getServices(), mContext);

        // If the device has no DPMS feature, it shouldn't register the local service.
        assertThat(LocalServices.getService(DevicePolicyManagerInternal.class)).isNull();
    }

    @Test
    public void testLoadAdminData() throws Exception {
        // Device owner in SYSTEM_USER
        setDeviceOwner();
        // Profile owner in CALLER_USER_HANDLE
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        setAsProfileOwner(admin2);
        // Active admin in CALLER_USER_HANDLE
        final int ANOTHER_UID = UserHandle.getUid(CALLER_USER_HANDLE, 1306);
        setUpPackageManagerForFakeAdmin(adminAnotherPackage, ANOTHER_UID, admin2);
        dpm.setActiveAdmin(adminAnotherPackage, /* replace =*/ false, CALLER_USER_HANDLE);
        assertThat(dpm.isAdminActiveAsUser(adminAnotherPackage, CALLER_USER_HANDLE)).isTrue();

        initializeDpms();

        // Verify
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                MockUtils.checkApps(admin1.getPackageName()),
                eq(UserHandle.USER_SYSTEM));
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                MockUtils.checkApps(admin2.getPackageName(),
                        adminAnotherPackage.getPackageName()),
                eq(CALLER_USER_HANDLE));
        verify(getServices().usageStatsManagerInternal).onAdminDataAvailable();
        verify(getServices().networkPolicyManagerInternal).onAdminDataAvailable();
    }

    @Test
    public void testLoadAdminData_noAdmins() throws Exception {
        final int ANOTHER_USER_ID = 15;
        getServices().addUser(ANOTHER_USER_ID, 0, "");

        initializeDpms();

        // Verify
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, ANOTHER_USER_ID);
        verify(getServices().usageStatsManagerInternal).onAdminDataAvailable();
        verify(getServices().networkPolicyManagerInternal).onAdminDataAvailable();
    }

    /**
     * Caller doesn't have proper permissions.
     */
    @Test
    public void testSetActiveAdmin_SecurityException() {
        // 1. Failure cases.

        // Caller doesn't have MANAGE_DEVICE_ADMINS.
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, false));

        // Caller has MANAGE_DEVICE_ADMINS, but for different user.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, false, CALLER_USER_HANDLE + 1));
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
    @Test
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
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));
        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE),
                eq(null),
                any(Bundle.class));

        verify(getServices().ipackageManager, times(1)).setApplicationEnabledSetting(
                eq(admin1.getPackageName()),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                eq(PackageManager.DONT_KILL_APP),
                eq(CALLER_USER_HANDLE),
                anyString());

        verify(getServices().usageStatsManagerInternal).onActiveAdminAdded(
                admin1.getPackageName(), CALLER_USER_HANDLE);

        // TODO Verify other calls too.

        // Make sure it's active admin1.
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isAdminActive(admin2)).isFalse();
        assertThat(dpm.isAdminActive(admin3)).isFalse();

        // But not admin1 for a different user.

        // For this to work, caller needs android.permission.INTERACT_ACROSS_USERS_FULL.
        // (Because we're checking a different user's status from CALLER_USER_HANDLE.)
        mContext.callerPermissions.add("android.permission.INTERACT_ACROSS_USERS_FULL");

        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE + 1)).isFalse();
        assertThat(dpm.isAdminActiveAsUser(admin2, CALLER_USER_HANDLE + 1)).isFalse();

        mContext.callerPermissions.remove("android.permission.INTERACT_ACROSS_USERS_FULL");

        // Next, add one more admin.
        // Before doing so, update the application info, now it's enabled.
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        dpm.setActiveAdmin(admin2, /* replace =*/ false);

        // Now we have two admins.
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isAdminActive(admin2)).isTrue();
        assertThat(dpm.isAdminActive(admin3)).isFalse();

        // Admin2 was already enabled, so setApplicationEnabledSetting() shouldn't have called
        // again.  (times(1) because it was previously called for admin1)
        verify(getServices().ipackageManager, times(1)).setApplicationEnabledSetting(
                eq(admin1.getPackageName()),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                eq(PackageManager.DONT_KILL_APP),
                eq(CALLER_USER_HANDLE),
                anyString());

        // times(2) because it was previously called for admin1 which is in the same package
        // as admin2.
        verify(getServices().usageStatsManagerInternal, times(2)).onActiveAdminAdded(
                admin2.getPackageName(), CALLER_USER_HANDLE);

        // 4. Add the same admin1 again without replace, which should throw.
        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, /* replace =*/ false));

        // 5. Add the same admin1 again with replace, which should succeed.
        dpm.setActiveAdmin(admin1, /* replace =*/ true);

        // TODO make sure it's replaced.

        // 6. Test getActiveAdmins()
        List<ComponentName> admins = dpm.getActiveAdmins();
        assertThat(admins.size()).isEqualTo(2);
        assertThat(admins.get(0)).isEqualTo(admin1);
        assertThat(admins.get(1)).isEqualTo(admin2);

        // There shouldn't be any callback to UsageStatsManagerInternal when the admin is being
        // replaced
        verifyNoMoreInteractions(getServices().usageStatsManagerInternal);

        // Another user has no admins.
        mContext.callerPermissions.add("android.permission.INTERACT_ACROSS_USERS_FULL");

        assertThat(DpmTestUtils.getListSizeAllowingNull(
        dpm.getActiveAdminsAsUser(CALLER_USER_HANDLE + 1))).isEqualTo(0);

        mContext.callerPermissions.remove("android.permission.INTERACT_ACROSS_USERS_FULL");
    }

    @Test
    public void testSetActiveAdmin_multiUsers() throws Exception {

        final int ANOTHER_USER_ID = 100;
        final int ANOTHER_ADMIN_UID = UserHandle.getUid(ANOTHER_USER_ID, 20456);

        getServices().addUser(ANOTHER_USER_ID, 0, ""); // Add one more user.

        // Set up pacakge manager for the other user.
        setUpPackageManagerForAdmin(admin2, ANOTHER_ADMIN_UID);

        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        mMockContext.binder.callingUid = ANOTHER_ADMIN_UID;
        dpm.setActiveAdmin(admin2, /* replace =*/ false);


        mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isAdminActive(admin2)).isFalse();

        mMockContext.binder.callingUid = ANOTHER_ADMIN_UID;
        assertThat(dpm.isAdminActive(admin1)).isFalse();
        assertThat(dpm.isAdminActive(admin2)).isTrue();
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setActiveAdmin}
     * with replace=false
     */
    @Test
    public void testSetActiveAdmin_twiceWithoutReplace() throws Exception {
        // 1. Make sure the caller has proper permissions.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        // Add the same admin1 again without replace, which should throw.
        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ null,
                () -> dpm.setActiveAdmin(admin1, /* replace =*/ false));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setActiveAdmin} when the admin isn't protected with
     * BIND_DEVICE_ADMIN.
     */
    @Test
    public void testSetActiveAdmin_permissionCheck() throws Exception {
        // 1. Make sure the caller has proper permissions.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        assertExpectException(IllegalArgumentException.class,
                /* messageRegex= */ permission.BIND_DEVICE_ADMIN,
                () -> dpm.setActiveAdmin(adminNoPerm, /* replace =*/ false));
        assertThat(dpm.isAdminActive(adminNoPerm)).isFalse();

        // Change the target API level to MNC.  Now it can be set as DA.
        setUpPackageManagerForAdmin(adminNoPerm, DpmMockContext.CALLER_UID, null,
                VERSION_CODES.M);
        dpm.setActiveAdmin(adminNoPerm, /* replace =*/ false);
        assertThat(dpm.isAdminActive(adminNoPerm)).isTrue();

        // TODO Test the "load from the file" case where DA will still be loaded even without
        // BIND_DEVICE_ADMIN and target API is N.
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    @Test
    public void testRemoveActiveAdmin_SecurityException() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertThat(dpm.isAdminActive(admin1)).isTrue();

        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();

        // Directly call the DPMS method with a different userid, which should fail.
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpms.removeActiveAdmin(admin1, CALLER_USER_HANDLE + 1));

        // Try to remove active admin with a different caller userid should fail too, without
        // having MANAGE_DEVICE_ADMINS.
        mContext.callerPermissions.clear();

        // Change the caller, and call into DPMS directly with a different user-id.

        mContext.binder.callingUid = 1234567;
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpms.removeActiveAdmin(admin1, CALLER_USER_HANDLE));
    }

    /**
     * {@link DevicePolicyManager#removeActiveAdmin} should fail with the user is not unlocked
     * (because we can't send the remove broadcast).
     */
    @Test
    public void testRemoveActiveAdmin_userNotRunningOrLocked() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Add admin.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertThat(dpm.isAdminActive(admin1)).isTrue();

        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();

        // 1. User not unlocked.
        setUserUnlocked(CALLER_USER_HANDLE, false);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "User must be running and unlocked",
                () -> dpm.removeActiveAdmin(admin1));

        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal, times(0)).setActiveAdminApps(
                null, CALLER_USER_HANDLE);

        // 2. User unlocked.
        setUserUnlocked(CALLER_USER_HANDLE, true);

        dpm.removeActiveAdmin(admin1);
        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    @Test
    public void testRemoveActiveAdmin_fromDifferentUserWithINTERACT_ACROSS_USERS_FULL() {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);

        // Add admin1.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();

        // Different user, but should work, because caller has proper permissions.
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Change the caller, and call into DPMS directly with a different user-id.
        mContext.binder.callingUid = 1234567;

        dpms.removeActiveAdmin(admin1, CALLER_USER_HANDLE);
        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);

        // TODO DO Still can't be removed in this case.
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#removeActiveAdmin}
     */
    @Test
    public void testRemoveActiveAdmin_sameUserNoMANAGE_DEVICE_ADMINS() {
        // Need MANAGE_DEVICE_ADMINS for setActiveAdmin.  We'll remove it later.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin1.

        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();

        // Broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));

        // Remove.  No permissions, but same user, so it'll work.
        mContext.callerPermissions.clear();
        dpm.removeActiveAdmin(admin1);

        verify(mContext.spiedContext).sendOrderedBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE),
                isNull(String.class),
                eq(AppOpsManager.OP_NONE),
                any(Bundle.class),
                any(BroadcastReceiver.class),
                eq(dpms.mHandler),
                eq(Activity.RESULT_OK),
                isNull(String.class),
                isNull(Bundle.class));

        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);

        // Again broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));

        // TODO Check other internal calls.
    }

    @Test
    public void testRemoveActiveAdmin_multipleAdminsInUser() {
        // Need MANAGE_DEVICE_ADMINS for setActiveAdmin.  We'll remove it later.
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin1.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);

        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();

        // Add admin2.
        dpm.setActiveAdmin(admin2, /* replace =*/ false);

        assertThat(dpm.isAdminActive(admin2)).isTrue();
        assertThat(dpm.isRemovingAdmin(admin2, CALLER_USER_HANDLE)).isFalse();

        // Broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));

        // Remove.  No permissions, but same user, so it'll work.
        mContext.callerPermissions.clear();
        dpm.removeActiveAdmin(admin1);

        verify(mContext.spiedContext).sendOrderedBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE),
                isNull(String.class),
                eq(AppOpsManager.OP_NONE),
                any(Bundle.class),
                any(BroadcastReceiver.class),
                eq(dpms.mHandler),
                eq(Activity.RESULT_OK),
                isNull(String.class),
                isNull(Bundle.class));

        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                MockUtils.checkApps(admin2.getPackageName()),
                eq(CALLER_USER_HANDLE));

        // Again broadcast from saveSettingsLocked().
        verify(mContext.spiedContext, times(3)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#forceRemoveActiveAdmin(ComponentName, int)}
     */
    @Test
    public void testForceRemoveActiveAdmin_nonShellCaller() throws Exception {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin.
        setupPackageInPackageManager(admin1.getPackageName(),
                /* userId= */ CALLER_USER_HANDLE,
                /* appId= */ 10138,
                /* flags= */ ApplicationInfo.FLAG_TEST_ONLY);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        // Calling from a non-shell uid should fail with a SecurityException
        mContext.binder.callingUid = 123456;
        assertExpectException(SecurityException.class,
                /* messageRegex = */ null,
                () -> dpms.forceRemoveActiveAdmin(admin1, CALLER_USER_HANDLE));
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#forceRemoveActiveAdmin(ComponentName, int)}
     */
    @Test
    public void testForceRemoveActiveAdmin_nonShellCallerWithPermission() throws Exception {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin.
        setupPackageInPackageManager(admin1.getPackageName(),
                /* userId= */ CALLER_USER_HANDLE,
                /* appId= */ 10138,
                /* flags= */ ApplicationInfo.FLAG_TEST_ONLY);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        mContext.binder.callingUid = 123456;
        mContext.callerPermissions.add(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        dpms.forceRemoveActiveAdmin(admin1, CALLER_USER_HANDLE);

        mContext.callerPermissions.add(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        // Verify
        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#forceRemoveActiveAdmin(ComponentName, int)}
     */
    @Test
    public void testForceRemoveActiveAdmin_ShellCaller() throws Exception {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);

        // Add admin.
        setupPackageInPackageManager(admin1.getPackageName(),
                /* userId= */ CALLER_USER_HANDLE,
                /* appId= */ 10138,
                /* flags= */ ApplicationInfo.FLAG_TEST_ONLY);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        mContext.binder.callingUid = Process.SHELL_UID;
        dpms.forceRemoveActiveAdmin(admin1, CALLER_USER_HANDLE);

        mContext.callerPermissions.add(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        // Verify
        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);
    }

    /**
     * Test for: {@link DevicePolicyManager#setPasswordHistoryLength(ComponentName, int)}
     *
     * Validates that when the password history length is set, it is persisted after rebooting
     */
    @Test
    public void testSaveAndLoadPasswordHistoryLength_persistedAfterReboot() throws Exception {
        int passwordHistoryLength = 2;

        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Install admin1 on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Set admin1 to active admin and device owner
        dpm.setActiveAdmin(admin1, false);
        dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM);

        // Save password history length
        dpm.setPasswordHistoryLength(admin1, passwordHistoryLength);

        assertThat(passwordHistoryLength).isEqualTo(dpm.getPasswordHistoryLength(admin1));

        initializeDpms();
        reset(mContext.spiedContext);

        // Password history length should persist after rebooted
        assertThat(passwordHistoryLength).isEqualTo(dpm.getPasswordHistoryLength(admin1));
    }

    /**
     * Test for: {@link DevicePolicyManager#reportPasswordChanged}
     *
     * Validates that when the password for a user changes, the notification broadcast intent
     * {@link DeviceAdminReceiver#ACTION_PASSWORD_CHANGED} is sent to managed profile owners, in
     * addition to ones in the original user.
     */
    @Test
    public void testSetActivePasswordState_sendToProfiles() throws Exception {
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);

        final int MANAGED_PROFILE_USER_ID = 78;
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(MANAGED_PROFILE_USER_ID, DpmMockContext.SYSTEM_UID);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.packageName = admin1.getPackageName();

        // Add a managed profile belonging to the system user.
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Change the parent user's password.
        dpm.reportPasswordChanged(UserHandle.USER_SYSTEM);

        // The managed profile owner should receive this broadcast.
        final Intent intent = new Intent(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED);
        intent.setComponent(admin1);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(UserHandle.USER_SYSTEM));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(MANAGED_PROFILE_USER_ID),
                eq(null),
                any(Bundle.class));
    }

    /**
     * Test for: @{link DevicePolicyManager#reportPasswordChanged}
     *
     * Validates that when the password for a managed profile changes, the notification broadcast
     * intent {@link DeviceAdminReceiver#ACTION_PASSWORD_CHANGED} is only sent to the profile, not
     * its parent.
     */
    @Test
    public void testSetActivePasswordState_notSentToParent() throws Exception {
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);

        final int MANAGED_PROFILE_USER_ID = 78;
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(MANAGED_PROFILE_USER_ID, DpmMockContext.SYSTEM_UID);

        // Configure system as having separate profile challenge.
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.packageName = admin1.getPackageName();
        doReturn(true).when(getServices().lockPatternUtils)
                .isSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID);

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
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM),
                eq(null),
                any(Bundle.class));
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(MANAGED_PROFILE_USER_ID),
                eq(null),
                any(Bundle.class));
    }

    /**
     * Test for: {@link DevicePolicyManager#setDeviceOwner} DO on system user installs successfully.
     */
    @Test
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
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        // TODO Test getDeviceOwnerName() too. To do so, we need to change
        // DPMS.getApplicationLabel() because Context.createPackageContextAsUser() is not mockable.
    }

    /**
     * TODO(b/174859111): move to automotive-only section
     * Test for {@link DevicePolicyManager#setDeviceOwner} in headless system user mode.
     */
    @Test
    public void testSetDeviceOwner_headlessSystemUserMode() throws Exception {
        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        setDeviceOwner_headlessSystemUser();

        // Try to set a profile owner on the same user, which should fail.
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        dpm.setActiveAdmin(admin2, /* refreshing= */ true, CALLER_USER_HANDLE);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "profile owner is already set",
                () -> dpm.setProfileOwner(admin2, "owner-name", CALLER_USER_HANDLE));

        // DO admin can't be deactivated.
        dpm.removeActiveAdmin(admin1);
        assertThat(dpm.isAdminActive(admin1)).isTrue();
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
        assertThat(dpm.setDeviceOwner(admin1, "owner-name")).isTrue();

        // getDeviceOwnerComponent should return the admin1 component.
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);
        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);

        // Check various get APIs.
        checkGetDeviceOwnerInfoApi(dpm, /* hasDeviceOwner =*/ true);

        // getDeviceOwnerComponent should *NOT* return the admin1 component for other users.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);
        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Verify internal calls.
        verify(getServices().iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        verify(getServices().userManager, times(1)).setUserRestriction(
                eq(UserManager.DISALLOW_ADD_MANAGED_PROFILE),
                eq(true), eq(UserHandle.SYSTEM));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));

        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);
    }

    // TODO(b/174859111): move to automotive-only section
    private void setDeviceOwner_headlessSystemUser() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);

        when(getServices().iactivityManager.getCurrentUser()).thenReturn(
                new UserInfo(DpmMockContext.CALLER_USER_HANDLE, "caller",  /* flags=*/ 0));
        // Check various get APIs.
        checkGetDeviceOwnerInfoApi(dpm, /* hasDeviceOwner =*/ false);

        final long ident = mServiceContext.binder.clearCallingIdentity();

        mServiceContext.binder.callingUid = DpmMockContext.CALLER_UID;

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);

        // Set device owner
        runAsCaller(mServiceContext, dpms, dpm -> {
            // DO needs to be a DA
            dpm.setActiveAdmin(admin1, /* replace =*/ false, UserHandle.USER_SYSTEM);
            // DO should be set on headless system user
            assertThat(dpm.setDeviceOwner(admin1, "owner-name", UserHandle.USER_SYSTEM)).isTrue();
            // PO should be set on calling user.
            assertThat(dpm.getProfileOwnerAsUser(CALLER_USER_HANDLE)).isEqualTo(admin1);
        });

        mServiceContext.binder.restoreCallingIdentity(ident);

        // Check various get APIs.
        checkGetDeviceOwnerInfoApi(dpm, /* hasDeviceOwner =*/ true);

        // Add MANAGE_USERS or test purpose.
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        // getDeviceOwnerComponent should *NOT* return the admin1 component for calling user.
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isNull();
        // Device owner should be set on system user.
        assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);

        // Set calling user to be system user.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Device owner component should be admin1
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);
        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);

        // Verify internal calls.
        verify(getServices().iactivityManager).updateDeviceOwner(
                eq(admin1.getPackageName()));

        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
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
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isTrue();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);

            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
        } else {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isFalse();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);

            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(null);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        if (hasDeviceOwner) {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isTrue();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);

            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
        } else {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isFalse();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);

            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(null);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        // Still with MANAGE_USERS.
        assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isFalse();
        assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isFalse();
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);

        if (hasDeviceOwner) {
            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
        } else {
            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(null);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
        }

        mContext.binder.callingUid = Process.SYSTEM_UID;
        mContext.callerPermissions.remove(permission.MANAGE_USERS);
        // System can still call "OnAnyUser" without MANAGE_USERS.
        if (hasDeviceOwner) {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isTrue();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);

            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
        } else {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isFalse();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);

            assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(null);
            assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
        }

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        // Still no MANAGE_USERS.
        if (hasDeviceOwner) {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isTrue();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isTrue();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);
        } else {
            assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isFalse();
            assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isFalse();
            assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);
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
        assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isFalse();
        assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isFalse();
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(null);

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
    @Test
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

    @Test
    public void testSetDeviceOwner_failures() throws Exception {
        // TODO Test more failure cases.  Basically test all chacks in enforceCanSetDeviceOwner().
        // Package doesn't exist and caller is not system
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Calling identity is not authorized",
                () -> dpm.setDeviceOwner(admin1, "owner-name", UserHandle.USER_SYSTEM));

        // Package exists, but caller is not system
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Calling identity is not authorized",
                () -> dpm.setDeviceOwner(admin1, "owner-name", UserHandle.USER_SYSTEM));
    }

    @Test
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
        assertThat(dpm.setDeviceOwner(admin1, "owner-name")).isTrue();

        // Verify internal calls.
        verify(getServices().iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        when(getServices().userManager.hasUserRestriction(eq(UserManager.DISALLOW_ADD_USER),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM))).thenReturn(true);

        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isRemovingAdmin(admin1, UserHandle.USER_SYSTEM)).isFalse();

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
        dpm.clearDeviceOwnerApp(admin1.getPackageName());

        // Now DO shouldn't be set.
        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isNull();

        verify(getServices().userManager).setUserRestriction(eq(UserManager.DISALLOW_ADD_USER),
                eq(false),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));

        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM), MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));

        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, UserHandle.USER_SYSTEM);

        assertThat(dpm.isAdminActiveAsUser(admin1, UserHandle.USER_SYSTEM)).isFalse();

        // ACTION_DEVICE_OWNER_CHANGED should be sent twice, once for setting the device owner
        // and once for clearing it.
        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
        // TODO Check other calls.
    }

    @Test
    public void testDeviceOwnerBackupActivateDeactivate() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // Set admin1 as a DA to the secondary user.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertThat(dpm.setDeviceOwner(admin1, "owner-name")).isTrue();

        verify(getServices().ibackupManager, times(1)).setBackupServiceActive(
                eq(UserHandle.USER_SYSTEM), eq(false));

        dpm.clearDeviceOwnerApp(admin1.getPackageName());

        verify(getServices().ibackupManager, times(1)).setBackupServiceActive(
                eq(UserHandle.USER_SYSTEM), eq(true));
    }

    @Test
    public void testProfileOwnerBackupActivateDeactivate() throws Exception {
        setAsProfileOwner(admin1);

        verify(getServices().ibackupManager, times(1)).setBackupServiceActive(
                eq(CALLER_USER_HANDLE), eq(false));

        dpm.clearProfileOwner(admin1);

        verify(getServices().ibackupManager, times(1)).setBackupServiceActive(
                eq(CALLER_USER_HANDLE), eq(true));
    }

    @Test
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
        assertThat(dpm.setDeviceOwner(admin1, "owner-name")).isTrue();

        // Verify internal calls.
        verify(getServices().iactivityManager, times(1)).updateDeviceOwner(
                eq(admin1.getPackageName()));

        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);

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
        assertThat(dpm.isDeviceManaged()).isTrue();
    }

    /**
     * Test for: {@link DevicePolicyManager#clearDeviceOwnerApp(String)}
     *
     * Validates that when the device owner is removed, the reset password token is cleared
     */
    @Test
    public void testClearDeviceOwner_clearResetPasswordToken() throws Exception {
        mContext.callerPermissions.add(android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Install admin1 on system user
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Set admin1 to active admin and device owner
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM);

        // Add reset password token
        final long handle = 12000;
        final byte[] token = new byte[32];
        when(getServices().lockPatternUtils.addEscrowToken(eq(token), eq(UserHandle.USER_SYSTEM),
                nullable(EscrowTokenStateChangeCallback.class)))
                .thenReturn(handle);
        assertThat(dpm.setResetPasswordToken(admin1, token)).isTrue();

        // Assert reset password token is active
        when(getServices().lockPatternUtils.isEscrowTokenActive(eq(handle),
                eq(UserHandle.USER_SYSTEM)))
                .thenReturn(true);
        assertThat(dpm.isResetPasswordTokenActive(admin1)).isTrue();

        // Remove the device owner
        dpm.clearDeviceOwnerApp(admin1.getPackageName());

        // Verify password reset password token was removed
        verify(getServices().lockPatternUtils).removeEscrowToken(eq(handle),
                eq(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testSetProfileOwner() throws Exception {
        setAsProfileOwner(admin1);

        // PO admin can't be deactivated.
        dpm.removeActiveAdmin(admin1);
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        // Try setting DO on the same user, which should fail.
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        runAsCaller(mServiceContext, dpms, dpm -> {
            dpm.setActiveAdmin(admin2, /* refreshing= */ true, CALLER_USER_HANDLE);
            assertExpectException(IllegalStateException.class,
                    /* messageRegex= */ "already has a profile owner",
                    () -> dpm.setDeviceOwner(admin2, "owner-name",
                            CALLER_USER_HANDLE));
        });
    }

    @Test
    public void testClearProfileOwner() throws Exception {
        setAsProfileOwner(admin1);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        assertThat(dpm.isProfileOwnerApp(admin1.getPackageName())).isTrue();
        assertThat(dpm.isRemovingAdmin(admin1, CALLER_USER_HANDLE)).isFalse();

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
        assertThat(dpm.isProfileOwnerApp(admin1.getPackageName())).isFalse();
        assertThat(dpm.isAdminActiveAsUser(admin1, CALLER_USER_HANDLE)).isFalse();
        verify(getServices().usageStatsManagerInternal).setActiveAdminApps(
                null, CALLER_USER_HANDLE);
    }

    @Test
    public void testSetProfileOwner_failures() throws Exception {
        // TODO Test more failure cases.  Basically test all chacks in enforceCanSetProfileOwner().
        // Package doesn't exist and caller is not system
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Calling identity is not authorized",
                () -> dpm.setProfileOwner(admin1, "owner-name", UserHandle.USER_SYSTEM));

        // Package exists, but caller is not system
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Calling identity is not authorized",
                () -> dpm.setProfileOwner(admin1, "owner-name", UserHandle.USER_SYSTEM));
    }

    @Test
    public void testGetDeviceOwnerAdminLocked() throws Exception {
        checkDeviceOwnerWithMultipleDeviceAdmins();
    }

    // This method is used primarily for testDeviceOwnerMigration.
    private void checkDeviceOwnerWithMultipleDeviceAdmins() throws Exception {
        // In ths test, we use 3 users (system + 2 secondary users), set some device admins to them,
        // set admin3 on USER_SYSTEM as DO, then call getDeviceOwnerAdminLocked() to
        // make sure it gets the right component from the right user.

        final int ANOTHER_USER_ID = 100;
        final int ANOTHER_ADMIN_UID = UserHandle.getUid(ANOTHER_USER_ID, 456);

        getServices().addUser(ANOTHER_USER_ID, 0, ""); // Add one more user.

        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure the admin package is installed to each user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin3, DpmMockContext.CALLER_SYSTEM_USER_UID);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        setUpPackageManagerForAdmin(admin2, DpmMockContext.CALLER_UID);

        setUpPackageManagerForAdmin(admin2, ANOTHER_ADMIN_UID);

        // Set active admins to the users.
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        dpm.setActiveAdmin(admin3, /* replace =*/ false);

        dpm.setActiveAdmin(admin1, /* replace =*/ false, CALLER_USER_HANDLE);
        dpm.setActiveAdmin(admin2, /* replace =*/ false, CALLER_USER_HANDLE);

        dpm.setActiveAdmin(admin2, /* replace =*/ false, ANOTHER_USER_ID);

        // Set DO on the system user which is only allowed during first boot.
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);
        assertThat(dpm.setDeviceOwner(admin3, "owner-name", UserHandle.USER_SYSTEM)).isTrue();
        assertThat(dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false)).isEqualTo(admin3);

        // Then check getDeviceOwnerAdminLocked().
        ActiveAdmin deviceOwner = getDeviceOwner();
        assertThat(deviceOwner.info.getComponent()).isEqualTo(admin3);
        assertThat(deviceOwner.getUid()).isEqualTo(DpmMockContext.CALLER_SYSTEM_USER_UID);
    }

    /**
     * This essentially tests
     * {@code DevicePolicyManagerService.findOwnerComponentIfNecessaryLocked()}. (which is
     * private.)
     *
     * We didn't use to persist the DO component class name, but now we do, and the above method
     * finds the right component from a package name upon migration.
     */
    @Test
    public void testDeviceOwnerMigration() throws Exception {
        checkDeviceOwnerWithMultipleDeviceAdmins();

        // Overwrite the device owner setting and clears the class name.
        dpms.mOwners.setDeviceOwner(
                new ComponentName(admin2.getPackageName(), ""),
                "owner-name", CALLER_USER_HANDLE);
        dpms.mOwners.writeDeviceOwner();

        // Make sure the DO component name doesn't have a class name.
        assertThat(dpms.getDeviceOwnerComponent(/* callingUserOnly= */ false).getClassName())
                .isEmpty();

        // Then create a new DPMS to have it load the settings from files.
        when(getServices().userManager.getUserRestrictions(any(UserHandle.class)))
                .thenReturn(new Bundle());
        initializeDpms();

        // Now the DO component name is a full name.
        // *BUT* because both admin1 and admin2 belong to the same package, we think admin1 is the
        // DO.
        assertThat(dpms.getDeviceOwnerComponent(/* callingUserOnly =*/ false)).isEqualTo(admin1);
    }

    @Test
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
            assertThat(returned).isNotNull();
            assertThat(returned.size()).isEqualTo(1);
            assertThat("Foo1").isEqualTo(returned.get("KEY_STRING"));
        }

        {
            Bundle returned = dpm.getApplicationRestrictions(admin1, "pkg2");
            assertThat(returned).isNotNull();
            assertThat(returned.size()).isEqualTo(1);
            assertThat("Foo2").isEqualTo(returned.get("KEY_STRING"));
        }

        dpm.setApplicationRestrictions(admin1, "pkg2", new Bundle());
        assertThat(dpm.getApplicationRestrictions(admin1, "pkg2").size()).isEqualTo(0);
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
        return setupPackageInPackageManager(packageName, CALLER_USER_HANDLE, appId,
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

    @Test
    public void testCertificateDisclosure() throws Exception {
        final int userId = CALLER_USER_HANDLE;
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
        when(getServices().keyChainConnection.getService().getUserCaAliases())
                .thenReturn(fourCerts);
        // when two of them are approved (one of them approved twice hence no action),
        dpms.approveCaCert(fourCerts.getList().get(0), userId, true);
        dpms.approveCaCert(fourCerts.getList().get(1), userId, true);
        // a notification should be shown saying that there are two certificates left to approve.
        verify(getServices().notificationManager, timeout(1000))
                .notifyAsUser(anyString(), anyInt(), argThat(hasExtra(EXTRA_TITLE,
                        TEST_STRING
                )), eq(user));
    }

    @Test
    public void testRemoveCredentialManagementApp() throws Exception {
        final String packageName = "com.test.cred.mng";
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.parse("package:" + packageName));
        dpms.mReceiver.setPendingResult(
                new BroadcastReceiver.PendingResult(Activity.RESULT_OK,
                        "resultData",
                        /* resultExtras= */ null,
                        BroadcastReceiver.PendingResult.TYPE_UNREGISTERED,
                        /* ordered= */ true,
                        /* sticky= */ false,
                        /* token= */ null,
                        CALLER_USER_HANDLE,
                        /* flags= */ 0));
        when(getServices().keyChainConnection.getService().hasCredentialManagementApp())
                .thenReturn(true);
        when(getServices().keyChainConnection.getService().getCredentialManagementAppPackageName())
                .thenReturn(packageName);

        dpms.mReceiver.onReceive(mContext, intent);

        flushTasks(dpms);
        verify(getServices().keyChainConnection.getService()).hasCredentialManagementApp();
        verify(getServices().keyChainConnection.getService()).removeCredentialManagementApp();
    }

    /**
     * Simple test for delegate set/get and general delegation. Tests verifying that delegated
     * privileges can acually be exercised by a delegate are not covered here.
     */
    @Test
    public void testDelegation() throws Exception {
        setAsProfileOwner(admin1);

        final int userHandle = CALLER_USER_HANDLE;

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
        DevicePolicyData policy = dpms.mUserData.get(userHandle);
        assertThat(policy.mDelegationMap.size()).isEqualTo(2);
        MoreAsserts.assertContentsInAnyOrder(policy.mDelegationMap.get(CERT_DELEGATE),
            DELEGATION_CERT_INSTALL);
        MoreAsserts.assertContentsInAnyOrder(dpm.getDelegatedScopes(admin1, CERT_DELEGATE),
            DELEGATION_CERT_INSTALL);
        assertThat(dpm.getCertInstallerPackage(admin1)).isEqualTo(CERT_DELEGATE);
        MoreAsserts.assertContentsInAnyOrder(policy.mDelegationMap.get(RESTRICTIONS_DELEGATE),
            DELEGATION_APP_RESTRICTIONS);
        MoreAsserts.assertContentsInAnyOrder(dpm.getDelegatedScopes(admin1, RESTRICTIONS_DELEGATE),
            DELEGATION_APP_RESTRICTIONS);
        assertThat(dpm.getApplicationRestrictionsManagingPackage(admin1))
                .isEqualTo(RESTRICTIONS_DELEGATE);

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

    @Test
    public void testApplicationRestrictionsManagingApp() throws Exception {
        setAsProfileOwner(admin1);

        final String nonExistAppRestrictionsManagerPackage = "com.google.app.restrictions.manager2";
        final String appRestrictionsManagerPackage = "com.google.app.restrictions.manager";
        final String nonDelegateExceptionMessageRegex =
                "Caller with uid \\d+ is not com.google.app.restrictions.manager";
        final int appRestrictionsManagerAppId = 20987;
        final int appRestrictionsManagerUid = setupPackageInPackageManager(
                appRestrictionsManagerPackage, appRestrictionsManagerAppId);

        // appRestrictionsManager package shouldn't be able to manage restrictions as the PO hasn't
        // delegated that permission yet.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        assertThat(dpm.isCallerApplicationRestrictionsManagingPackage()).isFalse();
        final Bundle rest = new Bundle();
        rest.putString("KEY_STRING", "Foo1");
        assertExpectException(SecurityException.class, INVALID_CALLING_IDENTITY_MSG,
                () -> dpm.setApplicationRestrictions(null, "pkg1", rest));

        // Check via the profile owner that no restrictions were set.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        assertThat(dpm.getApplicationRestrictions(admin1, "pkg1").size()).isEqualTo(0);

        // Check the API does not allow setting a non-existent package
        assertExpectException(PackageManager.NameNotFoundException.class,
                /* messageRegex= */ nonExistAppRestrictionsManagerPackage,
                () -> dpm.setApplicationRestrictionsManagingPackage(
                        admin1, nonExistAppRestrictionsManagerPackage));

        // Let appRestrictionsManagerPackage manage app restrictions
        dpm.setApplicationRestrictionsManagingPackage(admin1, appRestrictionsManagerPackage);
        assertThat(dpm.getApplicationRestrictionsManagingPackage(admin1))
                .isEqualTo(appRestrictionsManagerPackage);

        // Now that package should be able to set and retrieve app restrictions.
        mContext.binder.callingUid = appRestrictionsManagerUid;
        mContext.packageName = appRestrictionsManagerPackage;
        assertThat(dpm.isCallerApplicationRestrictionsManagingPackage()).isTrue();
        dpm.setApplicationRestrictions(null, "pkg1", rest);
        Bundle returned = dpm.getApplicationRestrictions(null, "pkg1");
        assertThat(returned.size()).isEqualTo(1);
        assertThat(returned.get("KEY_STRING")).isEqualTo("Foo1");

        // The same app running on a separate user shouldn't be able to manage app restrictions.
        mContext.binder.callingUid = UserHandle.getUid(
                UserHandle.USER_SYSTEM, appRestrictionsManagerAppId);
        assertThat(dpm.isCallerApplicationRestrictionsManagingPackage()).isFalse();
        assertExpectException(SecurityException.class, nonDelegateExceptionMessageRegex,
                () -> dpm.setApplicationRestrictions(null, "pkg1", rest));

        // The DPM is still able to manage app restrictions, even if it allowed another app to do it
        // too.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        assertThat(dpm.getApplicationRestrictions(admin1, "pkg1")).isEqualTo(returned);
        dpm.setApplicationRestrictions(admin1, "pkg1", null);
        assertThat(dpm.getApplicationRestrictions(admin1, "pkg1").size()).isEqualTo(0);

        // Removing the ability for the package to manage app restrictions.
        dpm.setApplicationRestrictionsManagingPackage(admin1, null);
        assertThat(dpm.getApplicationRestrictionsManagingPackage(admin1)).isNull();
        mContext.binder.callingUid = appRestrictionsManagerUid;
        mContext.packageName = appRestrictionsManagerPackage;
        assertThat(dpm.isCallerApplicationRestrictionsManagingPackage()).isFalse();
        assertExpectException(SecurityException.class, INVALID_CALLING_IDENTITY_MSG,
                () -> dpm.setApplicationRestrictions(null, "pkg1", null));
    }

    @Test
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
        assertThat(dpm.setDeviceOwner(admin1, "owner-name",
        UserHandle.USER_SYSTEM)).isTrue();

        assertNoDeviceOwnerRestrictions();
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM,
                        UserManager.DISALLOW_OUTGOING_CALLS),
                eq(true));
        reset(getServices().userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_OUTGOING_CALLS),
                getDeviceOwner().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_OUTGOING_CALLS),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM,
                        UserManager.DISALLOW_OUTGOING_CALLS),
                eq(true));
        reset(getServices().userManagerInternal);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                getDeviceOwner().ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_OUTGOING_CALLS),
                dpm.getUserRestrictions(admin1)
        );

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));
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
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));
        reset(getServices().userManagerInternal);

        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);
        dpm.clearUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        reset(getServices().userManagerInternal);

        // More tests.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_ADD_USER),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));
        reset(getServices().userManagerInternal);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_FUN);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_ADD_USER),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));
        reset(getServices().userManagerInternal);

        dpm.setCameraDisabled(admin1, true);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(UserHandle.USER_SYSTEM),
                // DISALLOW_CAMERA will be applied globally.
                MockUtils.checkUserRestrictions(UserManager.DISALLOW_FUN,
                        UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_CAMERA),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM), eq(true));
        reset(getServices().userManagerInternal);
    }

    private ActiveAdmin getDeviceOwner() {
        ComponentName component = dpms.mOwners.getDeviceOwnerComponent();
        DevicePolicyData policy =
                dpms.getUserData(dpms.mOwners.getDeviceOwnerUserId());
        for (ActiveAdmin admin : policy.mAdminList) {
            if (component.equals(admin.info.getComponent())) {
                return admin;
            }
        }
        return null;
    }

    @Test
    public void testDaDisallowedPolicies_SecurityException() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false, UserHandle.USER_SYSTEM);

        boolean originalCameraDisabled = dpm.getCameraDisabled(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setCameraDisabled(admin1, true));
        assertThat(dpm.getCameraDisabled(admin1)).isEqualTo(originalCameraDisabled);

        int originalKeyguardDisabledFeatures = dpm.getKeyguardDisabledFeatures(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setKeyguardDisabledFeatures(admin1,
                        DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL));
        assertThat(dpm.getKeyguardDisabledFeatures(admin1))
                .isEqualTo(originalKeyguardDisabledFeatures);

        long originalPasswordExpirationTimeout = dpm.getPasswordExpirationTimeout(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setPasswordExpirationTimeout(admin1, 1234));
        assertThat(dpm.getPasswordExpirationTimeout(admin1))
                .isEqualTo(originalPasswordExpirationTimeout);

        int originalPasswordQuality = dpm.getPasswordQuality(admin1);
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC));
        assertThat(dpm.getPasswordQuality(admin1)).isEqualTo(originalPasswordQuality);
    }

    @Test
    public void testSetUserRestriction_asPo() {
        setAsProfileOwner(admin1);

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE).ensureUserRestrictions()
        );

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE,
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
                eq(false));

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_OUTGOING_CALLS);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE,
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_OUTGOING_CALLS),
                eq(false));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
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
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE,
                        UserManager.DISALLOW_OUTGOING_CALLS),
                eq(false));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(
                        UserManager.DISALLOW_OUTGOING_CALLS
                ),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
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
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE), eq(false));

        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
                        .ensureUserRestrictions()
        );
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpm.getUserRestrictions(admin1)
        );

        // DISALLOW_ADJUST_VOLUME and DISALLOW_UNMUTE_MICROPHONE can be set by PO too, even
        // though when DO sets them they'll be applied globally.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_ADJUST_VOLUME);

        dpm.addUserRestriction(admin1, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE,
                        UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE),
                eq(false));

        dpm.setCameraDisabled(admin1, true);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE,
                        UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_UNMUTE_MICROPHONE,
                        UserManager.DISALLOW_CAMERA),
                eq(false));
        reset(getServices().userManagerInternal);

        // TODO Make sure restrictions are written to the file.
    }

    private static final Set<String> PROFILE_OWNER_ORGANIZATION_OWNED_GLOBAL_RESTRICTIONS =
            Sets.newSet(
                    UserManager.DISALLOW_AIRPLANE_MODE,
                    UserManager.DISALLOW_CONFIG_DATE_TIME,
                    UserManager.DISALLOW_CONFIG_PRIVATE_DNS
            );

    private static final Set<String> PROFILE_OWNER_ORGANIZATION_OWNED_LOCAL_RESTRICTIONS =
            Sets.newSet(
                    UserManager.DISALLOW_CONFIG_BLUETOOTH,
                    UserManager.DISALLOW_CONFIG_LOCATION,
                    UserManager.DISALLOW_CONFIG_WIFI,
                    UserManager.DISALLOW_CONTENT_CAPTURE,
                    UserManager.DISALLOW_CONTENT_SUGGESTIONS,
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    UserManager.DISALLOW_SHARE_LOCATION,
                    UserManager.DISALLOW_OUTGOING_CALLS,
                    UserManager.DISALLOW_BLUETOOTH_SHARING,
                    UserManager.DISALLOW_CONFIG_CELL_BROADCASTS,
                    UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
                    UserManager.DISALLOW_CONFIG_TETHERING,
                    UserManager.DISALLOW_DATA_ROAMING,
                    UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_SMS,
                    UserManager.DISALLOW_USB_FILE_TRANSFER,
                    UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                    UserManager.DISALLOW_UNMUTE_MICROPHONE
            );

    @Test
    public void testSetUserRestriction_asPoOfOrgOwnedDevice() throws Exception {
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(CALLER_USER_HANDLE, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        when(getServices().userManager.getProfileParent(CALLER_USER_HANDLE))
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        for (String restriction : PROFILE_OWNER_ORGANIZATION_OWNED_GLOBAL_RESTRICTIONS) {
            addAndRemoveGlobalUserRestrictionOnParentDpm(restriction);
        }
        for (String restriction : PROFILE_OWNER_ORGANIZATION_OWNED_LOCAL_RESTRICTIONS) {
            addAndRemoveLocalUserRestrictionOnParentDpm(restriction);
        }

        parentDpm.setCameraDisabled(admin1, true);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM,
                        UserManager.DISALLOW_CAMERA),
                eq(false));
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(UserManager.DISALLOW_CAMERA),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
                        .getParentActiveAdmin()
                        .getEffectiveRestrictions()
        );

        parentDpm.setCameraDisabled(admin1, false);
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
                        .getParentActiveAdmin()
                        .getEffectiveRestrictions()
        );
        reset(getServices().userManagerInternal);
    }

    private void addAndRemoveGlobalUserRestrictionOnParentDpm(String restriction) {
        parentDpm.addUserRestriction(admin1, restriction);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(restriction),
                MockUtils.checkUserRestrictions(CALLER_USER_HANDLE),
                eq(false));
        parentDpm.clearUserRestriction(admin1, restriction);
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
                        .getParentActiveAdmin()
                        .getEffectiveRestrictions()
        );
    }

    private void addAndRemoveLocalUserRestrictionOnParentDpm(String restriction) {
        parentDpm.addUserRestriction(admin1, restriction);
        verify(getServices().userManagerInternal).setDevicePolicyUserRestrictions(
                eq(CALLER_USER_HANDLE),
                MockUtils.checkUserRestrictions(),
                MockUtils.checkUserRestrictions(UserHandle.USER_SYSTEM, restriction),
                eq(false));
        parentDpm.clearUserRestriction(admin1, restriction);
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                dpms.getProfileOwnerAdminLocked(CALLER_USER_HANDLE)
                        .getParentActiveAdmin()
                        .getEffectiveRestrictions()
        );
    }

    @Test
    public void testNoDefaultEnabledUserRestrictions() throws Exception {
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
        assertThat(dpm.setDeviceOwner(admin1, "owner-name",
        UserHandle.USER_SYSTEM)).isTrue();

        assertNoDeviceOwnerRestrictions();

        reset(getServices().userManagerInternal);

        // Ensure the DISALLOW_REMOVE_MANAGED_PROFILES restriction doesn't show up as a
        // restriction to the device owner.
        dpm.addUserRestriction(admin1, UserManager.DISALLOW_REMOVE_MANAGED_PROFILE);
        assertNoDeviceOwnerRestrictions();
    }

    private void assertNoDeviceOwnerRestrictions() {
        DpmTestUtils.assertRestrictions(
                DpmTestUtils.newRestrictions(),
                getDeviceOwner().getEffectiveRestrictions()
        );
    }

    @Test
    public void testSetFactoryResetProtectionPolicyWithDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        when(getServices().persistentDataBlockManagerInternal.getAllowedUid()).thenReturn(
                DpmMockContext.CALLER_UID);

        FactoryResetProtectionPolicy policy = new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(new ArrayList<>())
                .setFactoryResetProtectionEnabled(false)
                .build();
        dpm.setFactoryResetProtectionPolicy(admin1, policy);

        FactoryResetProtectionPolicy result = dpm.getFactoryResetProtectionPolicy(admin1);
        assertThat(result).isEqualTo(policy);
        assertPoliciesAreEqual(policy, result);

        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_RESET_PROTECTION_POLICY_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE),
                eq(android.Manifest.permission.MANAGE_FACTORY_RESET_PROTECTION));
    }

    @Test
    public void testSetFactoryResetProtectionPolicyFailWithPO() throws Exception {
        setupProfileOwner();

        FactoryResetProtectionPolicy policy = new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionEnabled(false)
                .build();

        assertExpectException(SecurityException.class, null,
                () -> dpm.setFactoryResetProtectionPolicy(admin1, policy));
    }

    @Test
    public void testSetFactoryResetProtectionPolicyWithPOOfOrganizationOwnedDevice()
            throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        when(getServices().persistentDataBlockManagerInternal.getAllowedUid()).thenReturn(
                DpmMockContext.CALLER_UID);

        List<String> accounts = new ArrayList<>();
        accounts.add("Account 1");
        accounts.add("Account 2");

        FactoryResetProtectionPolicy policy = new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accounts)
                .build();

        dpm.setFactoryResetProtectionPolicy(admin1, policy);

        FactoryResetProtectionPolicy result = dpm.getFactoryResetProtectionPolicy(admin1);
        assertThat(result).isEqualTo(policy);
        assertPoliciesAreEqual(policy, result);

        verify(mContext.spiedContext, times(2)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));
        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));
        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_RESET_PROTECTION_POLICY_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE),
                eq(android.Manifest.permission.MANAGE_FACTORY_RESET_PROTECTION));
    }

    @Test
    public void testGetFactoryResetProtectionPolicyWithFrpManagementAgent()
            throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        when(getServices().persistentDataBlockManagerInternal.getAllowedUid()).thenReturn(
                DpmMockContext.CALLER_UID);

        FactoryResetProtectionPolicy policy = new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(new ArrayList<>())
                .setFactoryResetProtectionEnabled(false)
                .build();

        dpm.setFactoryResetProtectionPolicy(admin1, policy);

        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpm.setActiveAdmin(admin1, /*replace=*/ false);
        FactoryResetProtectionPolicy result = dpm.getFactoryResetProtectionPolicy(null);
        assertThat(result).isEqualTo(policy);
        assertPoliciesAreEqual(policy, result);

        verify(mContext.spiedContext).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_RESET_PROTECTION_POLICY_CHANGED),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE),
                eq(android.Manifest.permission.MANAGE_FACTORY_RESET_PROTECTION));
    }

    private void assertPoliciesAreEqual(FactoryResetProtectionPolicy expectedPolicy,
            FactoryResetProtectionPolicy actualPolicy) {
        assertThat(actualPolicy.isFactoryResetProtectionEnabled()).isEqualTo(
                expectedPolicy.isFactoryResetProtectionEnabled());
        assertAccountsAreEqual(expectedPolicy.getFactoryResetProtectionAccounts(),
                actualPolicy.getFactoryResetProtectionAccounts());
    }

    private void assertAccountsAreEqual(List<String> expectedAccounts,
            List<String> actualAccounts) {
        assertThat(actualAccounts).containsExactlyElementsIn(expectedAccounts);
    }

    @Test
    public void testSetPermittedInputMethodsWithPOOfOrganizationOwnedDevice()
            throws Exception {
        String packageName = "com.google.pkg.one";
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        // Allow all input methods
        parentDpm.setPermittedInputMethods(admin1, null);

        assertThat(parentDpm.getPermittedInputMethods(admin1)).isNull();

        // Allow only system input methods
        parentDpm.setPermittedInputMethods(admin1, new ArrayList<>());

        assertThat(parentDpm.getPermittedInputMethods(admin1)).isEmpty();

        // Don't allow specific third party input methods
        final List<String> inputMethods = Collections.singletonList(packageName);

        assertExpectException(IllegalArgumentException.class, /* messageRegex= */ "Permitted "
                        + "input methods must allow all input methods or only system input methods "
                        + "when called on the parent instance of an organization-owned device",
                () -> parentDpm.setPermittedInputMethods(admin1, inputMethods));
    }

    @Test
    public void testGetProxyParameters() throws Exception {
        assertThat(dpm.getProxyParameters(inetAddrProxy("192.0.2.1", 1234), emptyList()))
                .isEqualTo(new Pair<>("192.0.2.1:1234", ""));
        assertThat(dpm.getProxyParameters(inetAddrProxy("192.0.2.1", 1234),
                listOf("one.example.com  ", "  two.example.com ")))
                .isEqualTo(new Pair<>("192.0.2.1:1234", "one.example.com,two.example.com"));
        assertThat(dpm.getProxyParameters(hostnameProxy("proxy.example.com", 1234), emptyList()))
                .isEqualTo(new Pair<>("proxy.example.com:1234", ""));
        assertThat(dpm.getProxyParameters(hostnameProxy("proxy.example.com", 1234),
                listOf("excluded.example.com")))
                .isEqualTo(new Pair<>("proxy.example.com:1234", "excluded.example.com"));

        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                inetAddrProxy("192.0.2.1", 0), emptyList()));
        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                hostnameProxy("", 1234), emptyList()));
        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                hostnameProxy("", 0), emptyList()));
        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                hostnameProxy("invalid! hostname", 1234), emptyList()));
        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                hostnameProxy("proxy.example.com", 1234), listOf("invalid exclusion")));
        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                hostnameProxy("proxy.example.com", -1), emptyList()));
        assertThrows(IllegalArgumentException.class, () -> dpm.getProxyParameters(
                hostnameProxy("proxy.example.com", 0xFFFF + 1), emptyList()));
    }

    private static Proxy inetAddrProxy(String inetAddr, int port) {
        return new Proxy(
                Proxy.Type.HTTP, new InetSocketAddress(parseNumericAddress(inetAddr), port));
    }

    private static Proxy hostnameProxy(String hostname, int port) {
        return new Proxy(
                Proxy.Type.HTTP, InetSocketAddress.createUnresolved(hostname, port));
    }

    private static List<String> listOf(String... args) {
        return Arrays.asList(args);
    }

    @Test
    public void testSetKeyguardDisabledFeaturesWithDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        dpm.setKeyguardDisabledFeatures(admin1, DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA);

        assertThat(dpm.getKeyguardDisabledFeatures(admin1)).isEqualTo(
                DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA);
    }

    @Test
    public void testSetKeyguardDisabledFeaturesWithPO() throws Exception {
        setupProfileOwner();

        dpm.setKeyguardDisabledFeatures(admin1, DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);

        assertThat(dpm.getKeyguardDisabledFeatures(admin1)).isEqualTo(
                DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
    }

    @Test
    public void testSetKeyguardDisabledFeaturesWithPOOfOrganizationOwnedDevice()
            throws Exception {
        final int MANAGED_PROFILE_USER_ID = CALLER_USER_HANDLE;
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(MANAGED_PROFILE_USER_ID, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        parentDpm.setKeyguardDisabledFeatures(admin1,
                DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA);

        assertThat(parentDpm.getKeyguardDisabledFeatures(admin1)).isEqualTo(
                DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA);
    }

    @Test
    public void testSetApplicationHiddenWithDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        mockEmptyPolicyExemptApps();

        String packageName = "com.google.android.test";

        dpm.setApplicationHidden(admin1, packageName, true);
        verify(getServices().ipackageManager).setApplicationHiddenSettingAsUser(packageName,
                true, UserHandle.USER_SYSTEM);

        dpm.setApplicationHidden(admin1, packageName, false);
        verify(getServices().ipackageManager).setApplicationHiddenSettingAsUser(packageName,
                false, UserHandle.USER_SYSTEM);

        verify(getServices().ipackageManager, never()).getPackageInfo(packageName,
                PackageManager.MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM);
        verify(getServices().ipackageManager, never()).getPackageInfo(packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_SYSTEM_ONLY,
                UserHandle.USER_SYSTEM);
    }

    @Test
    public void testSetApplicationHiddenWithPOOfOrganizationOwnedDevice() throws Exception {
        final int MANAGED_PROFILE_USER_ID = CALLER_USER_HANDLE;
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(MANAGED_PROFILE_USER_ID, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;

        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        mockEmptyPolicyExemptApps();

        String packageName = "com.google.android.test";

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(getServices().userManager.getProfileParent(MANAGED_PROFILE_USER_ID))
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));
        when(getServices().ipackageManager.getApplicationInfo(packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES, UserHandle.USER_SYSTEM)).thenReturn(
                applicationInfo);

        parentDpm.setApplicationHidden(admin1, packageName, true);
        verify(getServices().ipackageManager).setApplicationHiddenSettingAsUser(packageName,
                true, UserHandle.USER_SYSTEM);

        parentDpm.setApplicationHidden(admin1, packageName, false);
        verify(getServices().ipackageManager).setApplicationHiddenSettingAsUser(packageName,
                false, UserHandle.USER_SYSTEM);
    }

    @Test
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
        assertThat(dpm.isAdminActive(admin1)).isTrue();

        // Test 2. Caller has DA, but not DO.
        assertExpectException(SecurityException.class,
                /* messageRegex= */ INVALID_CALLING_IDENTITY_MSG,
                () -> dpm.getWifiMacAddress(admin1));

        // Test 3. Caller has PO, but not DO.
        assertThat(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM)).isTrue();
        assertExpectException(SecurityException.class,
                /* messageRegex= */ INVALID_CALLING_IDENTITY_MSG,
                () -> dpm.getWifiMacAddress(admin1));

        // Remove PO.
        dpm.clearProfileOwner(admin1);
        dpm.setActiveAdmin(admin1, false);
        // Test 4, Caller is DO now.
        assertThat(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM)).isTrue();

        // 4-1.  But WifiManager is not ready.
        assertThat(dpm.getWifiMacAddress(admin1)).isNull();

        // 4-2.  When WifiManager returns an empty array, dpm should also output null.
        when(getServices().wifiManager.getFactoryMacAddresses()).thenReturn(new String[0]);
        assertThat(dpm.getWifiMacAddress(admin1)).isNull();

        // 4-3. With a real MAC address.
        final String[] macAddresses = new String[]{"11:22:33:44:55:66"};
        when(getServices().wifiManager.getFactoryMacAddresses()).thenReturn(macAddresses);
        assertThat(dpm.getWifiMacAddress(admin1)).isEqualTo("11:22:33:44:55:66");
    }

    @Test
    public void testGetMacAddressByOrgOwnedPO() throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        final String[] macAddresses = new String[]{"11:22:33:44:55:66"};
        when(getServices().wifiManager.getFactoryMacAddresses()).thenReturn(macAddresses);
        assertThat(dpm.getWifiMacAddress(admin1)).isEqualTo("11:22:33:44:55:66");
    }

    @Test
    public void testReboot() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // In this test, change the caller user to "system".
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Make sure admin1 is installed on system user.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);

        // Set admin1 as DA.
        dpm.setActiveAdmin(admin1, false);
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertExpectException(SecurityException.class, /* messageRegex= */
                INVALID_CALLING_IDENTITY_MSG, () -> dpm.reboot(admin1));

        // Set admin1 as PO.
        assertThat(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM)).isTrue();
        assertExpectException(SecurityException.class, /* messageRegex= */
                INVALID_CALLING_IDENTITY_MSG, () -> dpm.reboot(admin1));

        // Remove PO and add DO.
        dpm.clearProfileOwner(admin1);
        dpm.setActiveAdmin(admin1, false);
        assertThat(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM)).isTrue();

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
        when(getServices().telephonyManager.getCallState())
                .thenReturn(TelephonyManager.CALL_STATE_IDLE);
        dpm.reboot(admin1);
    }

    @Test
    public void testSetGetSupportText() {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        dpm.setActiveAdmin(admin1, true);
        dpm.setActiveAdmin(admin2, true);
        mContext.callerPermissions.remove(permission.MANAGE_DEVICE_ADMINS);

        // Null default support messages.
        {
            assertThat(dpm.getLongSupportMessage(admin1)).isNull();
            assertThat(dpm.getShortSupportMessage(admin1)).isNull();
            mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
            assertThat(dpm.getShortSupportMessageForUser(admin1, CALLER_USER_HANDLE)).isNull();
            assertThat(dpm.getLongSupportMessageForUser(admin1, CALLER_USER_HANDLE)).isNull();
            mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;
        }

        // Only system can call the per user versions.
        {
            assertExpectException(SecurityException.class, /* messageRegex= */ "message for user",
                    () -> dpm.getShortSupportMessageForUser(admin1, CALLER_USER_HANDLE));
            assertExpectException(SecurityException.class, /* messageRegex= */ "message for user",
                    () -> dpm.getLongSupportMessageForUser(admin1, CALLER_USER_HANDLE));
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
            assertThat(dpm.getShortSupportMessage(admin1)).isEqualTo(supportText);
            assertThat(dpm.getLongSupportMessage(admin1)).isNull();
            assertThat(dpm.getShortSupportMessage(admin2)).isNull();

            mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
            assertThat(dpm.getShortSupportMessageForUser(admin1,
            CALLER_USER_HANDLE)).isEqualTo(supportText);
            assertThat(dpm.getShortSupportMessageForUser(admin2, CALLER_USER_HANDLE)).isNull();
            assertThat(dpm.getLongSupportMessageForUser(admin1, CALLER_USER_HANDLE)).isNull();
            mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;

            dpm.setShortSupportMessage(admin1, null);
            assertThat(dpm.getShortSupportMessage(admin1)).isNull();
        }

        // Set/Get long returns what it sets and other admins text isn't changed.
        {
            final String supportText = "Some text to test with.\nWith more text.";
            dpm.setLongSupportMessage(admin1, supportText);
            assertThat(dpm.getLongSupportMessage(admin1)).isEqualTo(supportText);
            assertThat(dpm.getShortSupportMessage(admin1)).isNull();
            assertThat(dpm.getLongSupportMessage(admin2)).isNull();

            mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
            assertThat(dpm.getLongSupportMessageForUser(admin1,
            CALLER_USER_HANDLE)).isEqualTo(supportText);
            assertThat(dpm.getLongSupportMessageForUser(admin2, CALLER_USER_HANDLE)).isNull();
            assertThat(dpm.getShortSupportMessageForUser(admin1, CALLER_USER_HANDLE)).isNull();
            mMockContext.binder.callingUid = DpmMockContext.CALLER_UID;

            dpm.setLongSupportMessage(admin1, null);
            assertThat(dpm.getLongSupportMessage(admin1)).isNull();
        }
    }

    @Test
    public void testSetGetMeteredDataDisabledPackages() throws Exception {
        setAsProfileOwner(admin1);

        assertThat(dpm.getMeteredDataDisabledPackages(admin1)).isEmpty();

        // Setup
        final ArrayList<String> pkgsToRestrict = new ArrayList<>();
        final String package1 = "com.example.one";
        final String package2 = "com.example.two";
        pkgsToRestrict.add(package1);
        pkgsToRestrict.add(package2);
        setupPackageInPackageManager(package1, CALLER_USER_HANDLE, 123, 0);
        setupPackageInPackageManager(package2, CALLER_USER_HANDLE, 456, 0);
        List<String> excludedPkgs = dpm.setMeteredDataDisabledPackages(admin1, pkgsToRestrict);

        // Verify
        assertThat(excludedPkgs).isEmpty();
        assertThat(dpm.getMeteredDataDisabledPackages(admin1)).isEqualTo(pkgsToRestrict);
        verify(getServices().networkPolicyManagerInternal).setMeteredRestrictedPackages(
                MockUtils.checkApps(pkgsToRestrict.toArray(new String[0])),
                eq(CALLER_USER_HANDLE));

        // Setup
        pkgsToRestrict.remove(package1);
        excludedPkgs = dpm.setMeteredDataDisabledPackages(admin1, pkgsToRestrict);

        // Verify
        assertThat(excludedPkgs).isEmpty();
        assertThat(dpm.getMeteredDataDisabledPackages(admin1)).isEqualTo(pkgsToRestrict);
        verify(getServices().networkPolicyManagerInternal).setMeteredRestrictedPackages(
                MockUtils.checkApps(pkgsToRestrict.toArray(new String[0])),
                eq(CALLER_USER_HANDLE));
    }

    @Test
    public void testSetGetMeteredDataDisabledPackages_deviceAdmin() {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        dpm.setActiveAdmin(admin1, true);
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        mContext.callerPermissions.remove(permission.MANAGE_DEVICE_ADMINS);

        assertExpectException(SecurityException.class,  /* messageRegex= */ NOT_PROFILE_OWNER_MSG,
                () -> dpm.setMeteredDataDisabledPackages(admin1, new ArrayList<>()));
        assertExpectException(SecurityException.class,  /* messageRegex= */ NOT_PROFILE_OWNER_MSG,
                () -> dpm.getMeteredDataDisabledPackages(admin1));
    }

    @Test
    public void testIsMeteredDataDisabledForUserPackage() throws Exception {
        setAsProfileOwner(admin1);

        // Setup
        final ArrayList<String> pkgsToRestrict = new ArrayList<>();
        final String package1 = "com.example.one";
        final String package2 = "com.example.two";
        final String package3 = "com.example.three";
        pkgsToRestrict.add(package1);
        pkgsToRestrict.add(package2);
        setupPackageInPackageManager(package1, CALLER_USER_HANDLE, 123, 0);
        setupPackageInPackageManager(package2, CALLER_USER_HANDLE, 456, 0);
        List<String> excludedPkgs = dpm.setMeteredDataDisabledPackages(admin1, pkgsToRestrict);

        // Verify
        assertThat(excludedPkgs).isEmpty();
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertWithMessage("%s should be restricted", package1)
                .that(dpm.isMeteredDataDisabledPackageForUser(admin1, package1, CALLER_USER_HANDLE))
                .isTrue();
        assertWithMessage("%s should be restricted", package2)
                .that(dpm.isMeteredDataDisabledPackageForUser(admin1, package2, CALLER_USER_HANDLE))
                .isTrue();
        assertWithMessage("%s should not be restricted", package3)
                .that(dpm.isMeteredDataDisabledPackageForUser(admin1, package3, CALLER_USER_HANDLE))
                .isFalse();
    }

    @Test
    public void testIsMeteredDataDisabledForUserPackage_nonSystemUidCaller() throws Exception {
        setAsProfileOwner(admin1);
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Only the system can query restricted pkgs",
                () -> dpm.isMeteredDataDisabledPackageForUser(
                        admin1, "com.example.one", CALLER_USER_HANDLE));
        dpm.clearProfileOwner(admin1);

        setDeviceOwner();
        assertExpectException(SecurityException.class,
                /* messageRegex= */ "Only the system can query restricted pkgs",
                () -> dpm.isMeteredDataDisabledPackageForUser(
                        admin1, "com.example.one", CALLER_USER_HANDLE));
        clearDeviceOwner();
    }

    @Test
    public void testCreateAdminSupportIntent() throws Exception {
        // Setup device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // Nonexisting permission returns null
        Intent intent = dpm.createAdminSupportIntent("disallow_nothing");
        assertThat(intent).isNull();

        // Existing permission that is not set returns null
        intent = dpm.createAdminSupportIntent(UserManager.DISALLOW_ADJUST_VOLUME);
        assertThat(intent).isNull();

        // Existing permission that is not set by device/profile owner returns null
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_ADJUST_VOLUME),
                eq(UserHandle.getUserHandleForUid(mContext.binder.callingUid))))
                .thenReturn(true);
        intent = dpm.createAdminSupportIntent(UserManager.DISALLOW_ADJUST_VOLUME);
        assertThat(intent).isNull();

        // UM.getUserRestrictionSources() will return a list of size 1 with the caller resource.
        doAnswer((Answer<List<UserManager.EnforcingUser>>) invocation -> Collections.singletonList(
                new UserManager.EnforcingUser(
                        UserHandle.USER_SYSTEM,
                        UserManager.RESTRICTION_SOURCE_DEVICE_OWNER))
        ).when(getServices().userManager).getUserRestrictionSources(
                eq(UserManager.DISALLOW_ADJUST_VOLUME),
                eq(UserHandle.of(UserHandle.USER_SYSTEM)));
        intent = dpm.createAdminSupportIntent(UserManager.DISALLOW_ADJUST_VOLUME);
        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
        assertThat(intent.getIntExtra(Intent.EXTRA_USER_ID, -1))
                .isEqualTo(UserHandle.getUserId(DpmMockContext.CALLER_SYSTEM_USER_UID));
        assertThat(
                (ComponentName) intent.getParcelableExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN))
                        .isEqualTo(admin1);
        assertThat(intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION))
                .isEqualTo(UserManager.DISALLOW_ADJUST_VOLUME);

        // Try with POLICY_DISABLE_CAMERA and POLICY_DISABLE_SCREEN_CAPTURE, which are not
        // user restrictions

        // Camera is not disabled
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertThat(intent).isNull();

        // Camera is disabled
        dpm.setCameraDisabled(admin1, true);
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertThat(intent).isNotNull();
        assertThat(intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION))
                .isEqualTo(DevicePolicyManager.POLICY_DISABLE_CAMERA);

        // Screen capture is not disabled
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        assertThat(intent).isNull();

        // Screen capture is disabled
        dpm.setScreenCaptureDisabled(admin1, true);
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        assertThat(intent).isNotNull();
        assertThat(intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION))
                .isEqualTo(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);

        // Same checks for different user
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        // Camera should be disabled by device owner
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertThat(intent).isNotNull();
        assertThat(intent.getStringExtra(DevicePolicyManager.EXTRA_RESTRICTION))
                .isEqualTo(DevicePolicyManager.POLICY_DISABLE_CAMERA);
        assertThat(intent.getIntExtra(Intent.EXTRA_USER_ID, -1))
                .isEqualTo(UserHandle.getUserId(DpmMockContext.CALLER_SYSTEM_USER_UID));
        // ScreenCapture should not be disabled by device owner
        intent = dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        assertThat(intent).isNull();
    }

    /**
     * Test for:
     * {@link DevicePolicyManager#setAffiliationIds}
     * {@link DevicePolicyManager#getAffiliationIds}
     * {@link DevicePolicyManager#isAffiliatedUser}
     */
    @Test
    public void testUserAffiliation() throws Exception {
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS_FULL);
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);

        // Check that the system user is unaffiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        assertThat(dpm.isAffiliatedUser()).isFalse();

        // Set a device owner on the system user. Check that the system user becomes affiliated.
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, /* replace =*/ false);
        assertThat(dpm.setDeviceOwner(admin1, "owner-name")).isTrue();
        assertThat(dpm.isAffiliatedUser()).isTrue();
        assertThat(dpm.getAffiliationIds(admin1).isEmpty()).isTrue();

        // Install a profile owner. Check that the test user is unaffiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin2);
        assertThat(dpm.isAffiliatedUser()).isFalse();
        assertThat(dpm.getAffiliationIds(admin2).isEmpty()).isTrue();

        // Have the profile owner specify a set of affiliation ids. Check that the test user remains
        // unaffiliated.
        final Set<String> userAffiliationIds = new ArraySet<>();
        userAffiliationIds.add("red");
        userAffiliationIds.add("green");
        userAffiliationIds.add("blue");
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        MoreAsserts.assertContentsInAnyOrder(dpm.getAffiliationIds(admin2), "red", "green", "blue");
        assertThat(dpm.isAffiliatedUser()).isFalse();

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
        assertThat(dpm.isAffiliatedUser()).isFalse();

        // Have the profile owner specify a set of affiliation ids that intersect with those
        // specified by the device owner. Check that the test user becomes affiliated.
        userAffiliationIds.add("yellow");
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        MoreAsserts.assertContentsInAnyOrder(
            dpm.getAffiliationIds(admin2), "red", "green", "blue", "yellow");
        assertThat(dpm.isAffiliatedUser()).isTrue();

        // Clear affiliation ids for the profile owner. The user becomes unaffiliated.
        dpm.setAffiliationIds(admin2, Collections.emptySet());
        assertThat(dpm.getAffiliationIds(admin2).isEmpty()).isTrue();
        assertThat(dpm.isAffiliatedUser()).isFalse();

        // Set affiliation ids again, then clear PO to check that the user becomes unaffiliated
        dpm.setAffiliationIds(admin2, userAffiliationIds);
        assertThat(dpm.isAffiliatedUser()).isTrue();
        dpm.clearProfileOwner(admin2);
        assertThat(dpm.isAffiliatedUser()).isFalse();

        // Check that the system user remains affiliated.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        assertThat(dpm.isAffiliatedUser()).isTrue();

        // Clear the device owner - the user becomes unaffiliated.
        clearDeviceOwner();
        assertThat(dpm.isAffiliatedUser()).isFalse();
    }

    @Test
    public void testGetUserProvisioningState_defaultResult() {
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.getUserProvisioningState())
                .isEqualTo(DevicePolicyManager.STATE_USER_UNMANAGED);
    }

    @Test
    public void testSetUserProvisioningState_permission() throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_unprivileged() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.setUserProvisioningState(DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                        CALLER_USER_HANDLE));
    }

    @Test
    public void testSetUserProvisioningState_noManagement() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "change provisioning state unless a .* owner is set",
                () -> dpm.setUserProvisioningState(DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                        CALLER_USER_HANDLE));
        assertThat(dpm.getUserProvisioningState())
                .isEqualTo(DevicePolicyManager.STATE_USER_UNMANAGED);
    }

    @Test
    public void testSetUserProvisioningState_deviceOwnerFromSetupWizard() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_deviceOwnerFromSetupWizardAlternative()
            throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_deviceOwnerWithoutSetupWizard() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        exerciseUserProvisioningTransitions(UserHandle.USER_SYSTEM,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_managedProfileFromSetupWizard_primaryUser()
            throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_PROFILE_COMPLETE,
                DevicePolicyManager.STATE_USER_PROFILE_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_managedProfileFromSetupWizard_managedProfile()
            throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_COMPLETE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_managedProfileWithoutSetupWizard() throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED);
    }

    @Test
    public void testSetUserProvisioningState_illegalTransitionOutOfFinalized1() throws Exception {
        setupProfileOwner();

        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "Cannot move to user provisioning state",
                () -> exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                        DevicePolicyManager.STATE_USER_SETUP_FINALIZED,
                        DevicePolicyManager.STATE_USER_UNMANAGED));
    }

    @Test
    public void testSetUserProvisioningState_profileFinalized_canTransitionToUserUnmanaged()
            throws Exception {
        setupProfileOwner();

        exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                DevicePolicyManager.STATE_USER_PROFILE_FINALIZED,
                DevicePolicyManager.STATE_USER_UNMANAGED);
    }

    @Test
    public void testSetUserProvisioningState_illegalTransitionToAnotherInProgressState()
            throws Exception {
        setupProfileOwner();

        assertExpectException(IllegalStateException.class,
                /* messageRegex= */ "Cannot move to user provisioning state",
                () -> exerciseUserProvisioningTransitions(CALLER_USER_HANDLE,
                        DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE,
                        DevicePolicyManager.STATE_USER_SETUP_COMPLETE));
    }

    private void exerciseUserProvisioningTransitions(int userId, int... states) {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.callerPermissions.add(permission.MANAGE_USERS);

        assertThat(dpm.getUserProvisioningState())
                .isEqualTo(DevicePolicyManager.STATE_USER_UNMANAGED);
        for (int state : states) {
            dpm.setUserProvisioningState(state, userId);
            assertThat(dpm.getUserProvisioningState()).isEqualTo(state);
        }
    }

    private void setupProfileOwner() throws Exception {
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        dpm.setActiveAdmin(admin1, false);
        assertThat(dpm.setProfileOwner(admin1, null, CALLER_USER_HANDLE)).isTrue();

        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    private void setupProfileOwnerOnUser0() throws Exception {
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.SYSTEM_UID);
        dpm.setActiveAdmin(admin1, false);
        assertThat(dpm.setProfileOwner(admin1, null, UserHandle.USER_SYSTEM)).isTrue();

        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    private void setupDeviceOwner() throws Exception {
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);

        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        dpm.setActiveAdmin(admin1, false);
        assertThat(dpm.setDeviceOwner(admin1, null, UserHandle.USER_SYSTEM)).isTrue();

        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    @Test
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

    @Test
    public void testIsActiveSupervisionApp() throws Exception {
        when(mServiceContext.resources
                .getString(R.string.config_defaultSupervisionProfileOwnerComponent))
                .thenReturn(admin1.flattenToString());

        final int PROFILE_USER = 15;
        final int PROFILE_ADMIN = UserHandle.getUid(PROFILE_USER, 19436);
        addManagedProfile(admin1, PROFILE_ADMIN, admin1);
        mContext.binder.callingUid = PROFILE_ADMIN;

        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        assertThat(dpmi.isActiveSupervisionApp(PROFILE_ADMIN)).isTrue();
    }

    // Test if lock timeout on managed profile is handled correctly depending on whether profile
    // uses separate challenge.
    @Test
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

    @Test
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
        assertThat(MAX_MINUS_ONE_MINUTE).isEqualTo(dpm.getRequiredStrongAuthTimeout(admin1));
        assertThat(MAX_MINUS_ONE_MINUTE).isEqualTo(dpm.getRequiredStrongAuthTimeout(null));

        verify(getServices().systemProperties, never()).getLong(anyString(), anyLong());

        // restore to the debuggable build state
        getServices().buildMock.isDebuggable = true;

        // reset to default (0 means the admin is not participating, so default should be returned)
        dpm.setRequiredStrongAuthTimeout(admin1, 0);

        // aggregation should be the default if unset by any admin
        assertThat(DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS)
                .isEqualTo(dpm.getRequiredStrongAuthTimeout(null));

        // admin not participating by default
        assertThat(dpm.getRequiredStrongAuthTimeout(admin1)).isEqualTo(0);

        //clamping from the top
        dpm.setRequiredStrongAuthTimeout(admin1,
                DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS + ONE_MINUTE);
        assertThat(DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS)
                .isEqualTo(dpm.getRequiredStrongAuthTimeout(admin1));
        assertThat(DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS)
                .isEqualTo(dpm.getRequiredStrongAuthTimeout(null));

        // 0 means the admin is not participating, so default should be returned
        dpm.setRequiredStrongAuthTimeout(admin1, 0);
        assertThat(dpm.getRequiredStrongAuthTimeout(admin1)).isEqualTo(0);
        assertThat(DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS)
                .isEqualTo(dpm.getRequiredStrongAuthTimeout(null));

        // clamping from the bottom
        dpm.setRequiredStrongAuthTimeout(admin1, MINIMUM_STRONG_AUTH_TIMEOUT_MS - ONE_MINUTE);
        assertThat(dpm.getRequiredStrongAuthTimeout(admin1))
                .isEqualTo(MINIMUM_STRONG_AUTH_TIMEOUT_MS);
        assertThat(dpm.getRequiredStrongAuthTimeout(null))
                .isEqualTo(MINIMUM_STRONG_AUTH_TIMEOUT_MS);

        // values within range
        dpm.setRequiredStrongAuthTimeout(admin1, MIN_PLUS_ONE_MINUTE);
        assertThat(dpm.getRequiredStrongAuthTimeout(admin1)).isEqualTo(MIN_PLUS_ONE_MINUTE);
        assertThat(dpm.getRequiredStrongAuthTimeout(null)).isEqualTo(MIN_PLUS_ONE_MINUTE);

        dpm.setRequiredStrongAuthTimeout(admin1, MAX_MINUS_ONE_MINUTE);
        assertThat(dpm.getRequiredStrongAuthTimeout(admin1)).isEqualTo(MAX_MINUS_ONE_MINUTE);
        assertThat(dpm.getRequiredStrongAuthTimeout(null)).isEqualTo(MAX_MINUS_ONE_MINUTE);

        // reset to default
        dpm.setRequiredStrongAuthTimeout(admin1, 0);
        assertThat(dpm.getRequiredStrongAuthTimeout(admin1)).isEqualTo(0);
        assertThat(DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS)
                .isEqualTo(dpm.getRequiredStrongAuthTimeout(null));

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
        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(false);
        initializeDpms();
        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    @Test
    public void testIsProvisioningAllowed_DeviceAdminFeatureOff() throws Exception {
        setup_DeviceAdminFeatureOff();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);

        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);
    }

    @Test
    public void testCheckProvisioningPreCondition_DeviceAdminFeatureOff() throws Exception {
        setup_DeviceAdminFeatureOff();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED);
    }

    private void setup_ManagedProfileFeatureOff() throws Exception {
        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(false);
        initializeDpms();
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, true))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    @Test
    public void testIsProvisioningAllowed_ManagedProfileFeatureOff() throws Exception {
        setup_ManagedProfileFeatureOff();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);

        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
    }

    @Test
    public void testCheckProvisioningPreCondition_ManagedProfileFeatureOff() throws Exception {
        setup_ManagedProfileFeatureOff();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_MANAGED_USERS_NOT_SUPPORTED);
    }

    private void setup_firstBoot_systemUser() throws Exception {
        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, false))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);
        when(getServices().userManager.getProfileParent(UserHandle.USER_SYSTEM)).thenReturn(null);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    /* Tests provisions from system user during first boot. */
    @Test
    public void testIsProvisioningAllowed_firstBoot_systemUser() throws Exception {
        setup_firstBoot_systemUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);

        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE, true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);

        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
    }

    @Test
    public void testCheckProvisioningPreCondition_firstBoot_systemUser()
            throws Exception {
        setup_firstBoot_systemUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(false);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE,
                DevicePolicyManager.CODE_OK);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);

        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_OK);
    }

    private void setup_systemUserSetupComplete_systemUser() throws Exception {
        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, false))
                .thenReturn(true);
        setUserSetupCompleteForUser(true, UserHandle.USER_SYSTEM);
        when(getServices().userManager.getProfileParent(UserHandle.USER_SYSTEM)).thenReturn(null);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
    }

    private void setup_withDo_systemUser() throws Exception {
        setDeviceOwner();
        setup_systemUserSetupComplete_systemUser();
        setUpPackageManagerForFakeAdmin(adminAnotherPackage, DpmMockContext.ANOTHER_UID, admin2);
    }

    private void setup_withDo_systemUser_ManagedProfile() throws Exception {
        setup_withDo_systemUser();
        final int MANAGED_PROFILE_USER_ID = 18;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 1308);
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM,
                false /* we can't remove a managed profile */)).thenReturn(false);
    }

    @Test
    public void testIsProvisioningAllowed_systemUserSetupComplete_systemUser()
            throws Exception {
        setup_systemUserSetupComplete_systemUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                false/* because of completed device setup */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE,
                false/* because of completed device setup */);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);
    }

    @Test
    public void testCheckProvisioningPreCondition_systemUserSetupComplete_systemUser()
            throws Exception {
        setup_systemUserSetupComplete_systemUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_USER_SETUP_COMPLETED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE,
                DevicePolicyManager.CODE_USER_SETUP_COMPLETED);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_OK);
    }

    @Test
    public void testProvisioning_withDo_systemUser() throws Exception {
        setup_withDo_systemUser();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE,
                DevicePolicyManager.CODE_HAS_DEVICE_OWNER);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE,
                DevicePolicyManager.CODE_HAS_DEVICE_OWNER);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE, false);

        // COMP mode NOT is allowed.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);

        // And other DPCs can NOT provision a managed profile.
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    @Test
    public void testProvisioning_withDo_systemUser_restrictedBySystem()
            throws Exception {
        setup_withDo_systemUser();
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
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);

        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    @Test
    public void testCheckCannotSetProfileOwnerWithDeviceOwner() throws Exception {
        setup_withDo_systemUser();
        final int managedProfileUserId = 18;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 1308);

        final int userId = UserHandle.getUserId(managedProfileAdminUid);
        getServices().addUser(userId, 0, UserManager.USER_TYPE_PROFILE_MANAGED,
                UserHandle.USER_SYSTEM);
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);
        setUpPackageManagerForFakeAdmin(admin1, managedProfileAdminUid, admin1);
        dpm.setActiveAdmin(admin1, false, userId);
        assertThat(dpm.setProfileOwner(admin1, null, userId)).isFalse();
        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    @Test
    public void testCheckProvisioningPreCondition_attemptingComp() throws Exception {
        setup_withDo_systemUser_ManagedProfile();
        mContext.packageName = admin1.getPackageName();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // We can delete the managed profile to create a new one, so provisioning is allowed.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
        assertCheckProvisioningPreCondition(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DpmMockContext.ANOTHER_PACKAGE_NAME,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false,
                DpmMockContext.ANOTHER_PACKAGE_NAME, DpmMockContext.ANOTHER_UID);
    }

    @Test
    public void testCheckProvisioningPreCondition_comp_cannot_remove_profile()
            throws Exception {
        setup_withDo_systemUser_ManagedProfile();
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
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
    }

    // TODO(b/174859111): move to automotive-only section
    private void setup_firstBoot_headlessSystemUserMode() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().userManager.canAddMoreManagedProfiles(UserHandle.USER_SYSTEM, false))
                .thenReturn(true);
        setUserSetupCompleteForUser(false, UserHandle.USER_SYSTEM);
        when(getServices().userManager.getProfileParent(UserHandle.USER_SYSTEM)).thenReturn(null);
    }

    /**
     * TODO(b/174859111): move to automotive-only section
     * Tests provision from secondary user during first boot.
    **/
    @Test
    public void testIsProvisioningAllowed_firstBoot_secondaryUser() throws Exception {
        setup_firstBoot_headlessSystemUserMode();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        // Provisioning device from secondary user should fail in non-headless system user mode.
        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, false);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_FINANCED_DEVICE, false);

        // Required for ACTION_PROVISION_MANAGED_PROFILE if allowed to add managed profile from
        // secondary user
        when(getServices().userManager.canAddMoreManagedProfiles(CALLER_USER_HANDLE, false))
                .thenReturn(true);
        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, true);

        // Provisioning device from secondary user should be allowed in headless system user mode.
        when(getServices().userManagerForMock.isHeadlessSystemUserMode()).thenReturn(true);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, true);
    }

    private void setup_provisionManagedProfileWithDeviceOwner_primaryUser() throws Exception {
        setDeviceOwner();

        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(true);
        when(getServices().userManager.getProfileParent(CALLER_USER_HANDLE))
            .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));
        when(getServices().userManager.canAddMoreManagedProfiles(CALLER_USER_HANDLE,
                false)).thenReturn(true);
        setUserSetupCompleteForUser(false, CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
    }

    @Test
    public void testIsProvisioningAllowed_provisionManagedProfileWithDeviceOwner_primaryUser()
            throws Exception {
        setup_provisionManagedProfileWithDeviceOwner_primaryUser();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        mContext.packageName = admin1.getPackageName();
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
    }

    @Test
    public void testCheckProvisioningPreCondition_provisionManagedProfileWithDeviceOwner_primaryUser()
            throws Exception {
        setup_provisionManagedProfileWithDeviceOwner_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);

        // COMP mode is NOT allowed.
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
    }

    private void setup_provisionManagedProfileOneAlreadyExist_primaryUser() throws Exception {
        setDeviceOwner();

        when(getServices().ipackageManager
                .hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0)).thenReturn(true);
        when(getServices().userManager.hasUserRestriction(
                eq(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE),
                eq(UserHandle.of(CALLER_USER_HANDLE))))
                .thenReturn(true);
        when(getServices().userManager.canAddMoreManagedProfiles(CALLER_USER_HANDLE,
                false /* we can't remove a managed profile */)).thenReturn(false);
        setUserSetupCompleteForUser(false, CALLER_USER_HANDLE);

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
    }

    @Test
    public void testIsProvisioningAllowed_provisionManagedProfile_oneAlreadyExists_primaryUser()
            throws Exception {
        setup_provisionManagedProfileOneAlreadyExist_primaryUser();
        mContext.packageName = admin1.getPackageName();
        setUpPackageManagerForAdmin(admin1, mContext.binder.callingUid);
        assertProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, false);
    }

    @Test
    public void testCheckProvisioningPreCondition_provisionManagedProfile_oneAlreadyExists_primaryUser()
            throws Exception {
        setup_provisionManagedProfileOneAlreadyExist_primaryUser();
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        assertCheckProvisioningPreCondition(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE);
    }

    @Test
    public void testCheckProvisioningPreCondition_permission() {
        // GIVEN the permission MANAGE_PROFILE_AND_DEVICE_OWNERS is not granted
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.checkProvisioningPreCondition(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE, "some.package"));
    }

    @Test
    public void testForceUpdateUserSetupComplete_permission() {
        // GIVEN the permission MANAGE_PROFILE_AND_DEVICE_OWNERS is not granted
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.forceUpdateUserSetupComplete(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testForceUpdateUserSetupComplete_forcesUpdate() {
        mContext.callerPermissions.add(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        final int userId = UserHandle.getUserId(mContext.binder.callingUid);

        // GIVEN userComplete is false in SettingsProvider
        setUserSetupCompleteForUser(false, userId);

        // GIVEN userComplete is true in DPM
        DevicePolicyData userData = new DevicePolicyData(userId);
        userData.mUserSetupComplete = true;
        dpms.mUserData.put(userId, userData);

        assertThat(dpms.hasUserSetupCompleted()).isTrue();

        dpm.forceUpdateUserSetupComplete(userId);

        // THEN the state in dpms is changed
        assertThat(dpms.hasUserSetupCompleted()).isFalse();
    }

    private void clearDeviceOwner() throws Exception {
        doReturn(DpmMockContext.CALLER_SYSTEM_USER_UID).when(getServices().packageManager)
                .getPackageUidAsUser(eq(admin1.getPackageName()), anyInt());

        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        runAsCaller(mAdmin1Context, dpms, dpm -> {
            dpm.clearDeviceOwnerApp(admin1.getPackageName());
        });
    }

    @Test
    public void testGetLastSecurityLogRetrievalTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(CALLER_USER_HANDLE);
        when(mContext.resources.getBoolean(R.bool.config_supportPreRebootSecurityLogs))
                .thenReturn(true);

        // No logs were retrieved so far.
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(-1);

        // Enabling logging should not change the timestamp.
        dpm.setSecurityLoggingEnabled(admin1, true);
        verify(getServices().settings).securityLogSetLoggingEnabledProperty(true);

        when(getServices().settings.securityLogGetLoggingEnabledProperty()).thenReturn(true);
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(-1);

        // Retrieving the logs should update the timestamp.
        final long beforeRetrieval = System.currentTimeMillis();
        dpm.retrieveSecurityLogs(admin1);
        final long firstSecurityLogRetrievalTime = dpm.getLastSecurityLogRetrievalTime();
        final long afterRetrieval = System.currentTimeMillis();
        assertThat(firstSecurityLogRetrievalTime >= beforeRetrieval).isTrue();
        assertThat(firstSecurityLogRetrievalTime <= afterRetrieval).isTrue();

        // Retrieving the pre-boot logs should update the timestamp.
        Thread.sleep(2);
        dpm.retrievePreRebootSecurityLogs(admin1);
        final long secondSecurityLogRetrievalTime = dpm.getLastSecurityLogRetrievalTime();
        assertThat(secondSecurityLogRetrievalTime > firstSecurityLogRetrievalTime).isTrue();

        // Checking the timestamp again should not change it.
        Thread.sleep(2);
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(secondSecurityLogRetrievalTime);

        // Retrieving the logs again should update the timestamp.
        dpm.retrieveSecurityLogs(admin1);
        final long thirdSecurityLogRetrievalTime = dpm.getLastSecurityLogRetrievalTime();
        assertThat(thirdSecurityLogRetrievalTime > secondSecurityLogRetrievalTime).isTrue();

        // Disabling logging should not change the timestamp.
        Thread.sleep(2);
        dpm.setSecurityLoggingEnabled(admin1, false);
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(thirdSecurityLogRetrievalTime);

        // Restarting the DPMS should not lose the timestamp.
        initializeDpms();
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(thirdSecurityLogRetrievalTime);

        // Any uid holding MANAGE_USERS permission can retrieve the timestamp.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(thirdSecurityLogRetrievalTime);
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve the timestamp.
        mContext.binder.clearCallingIdentity();
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(thirdSecurityLogRetrievalTime);

        // Removing the device owner should clear the timestamp.
        clearDeviceOwner();
        assertThat(dpm.getLastSecurityLogRetrievalTime()).isEqualTo(-1);
    }

    @Test
    public void testSetConfiguredNetworksLockdownStateWithDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setConfiguredNetworksLockdownState(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 1);

        dpm.setConfiguredNetworksLockdownState(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0);
    }

    @Test
    public void testSetConfiguredNetworksLockdownStateWithPO() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null,
                () -> dpm.setConfiguredNetworksLockdownState(admin1, false));
        verify(getServices().settings, never()).settingsGlobalPutInt(
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0);
    }

    @Test
    public void testSetConfiguredNetworksLockdownStateWithPOOfOrganizationOwnedDevice()
            throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);
        dpm.setConfiguredNetworksLockdownState(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 1);

        dpm.setConfiguredNetworksLockdownState(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0);
    }

    @Test
    public void testUpdateNetworkPreferenceOnStartUser() throws Exception {
        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);
        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        mContext.binder.callingUid = managedProfileAdminUid;
        mServiceContext.permissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        dpms.handleStartUser(managedProfileUserId);
        verify(getServices().connectivityManager, times(1)).setProfileNetworkPreference(
                eq(UserHandle.of(managedProfileUserId)),
                anyInt(),
                any(),
                any()
        );
    }

    @Test
    public void testUpdateNetworkPreferenceOnStopUser() throws Exception {
        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);
        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        mContext.binder.callingUid = managedProfileAdminUid;
        mServiceContext.permissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        dpms.handleStopUser(managedProfileUserId);
        verify(getServices().connectivityManager, times(1)).setProfileNetworkPreference(
                eq(UserHandle.of(managedProfileUserId)),
                eq(ConnectivityManager.PROFILE_NETWORK_PREFERENCE_DEFAULT),
                any(),
                any()
        );
    }

    @Test
    public void testGetSetPreferentialNetworkService() throws Exception {
        assertExpectException(SecurityException.class, null,
                () -> dpm.setPreferentialNetworkServiceEnabled(false));

        assertExpectException(SecurityException.class, null,
                () -> dpm.isPreferentialNetworkServiceEnabled());

        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);
        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        mContext.binder.callingUid = managedProfileAdminUid;

        dpm.setPreferentialNetworkServiceEnabled(false);
        assertThat(dpm.isPreferentialNetworkServiceEnabled()).isFalse();
        verify(getServices().connectivityManager, times(1)).setProfileNetworkPreference(
                eq(UserHandle.of(managedProfileUserId)),
                eq(ConnectivityManager.PROFILE_NETWORK_PREFERENCE_DEFAULT),
                any(),
                any()
        );

        dpm.setPreferentialNetworkServiceEnabled(true);
        assertThat(dpm.isPreferentialNetworkServiceEnabled()).isTrue();
        verify(getServices().connectivityManager, times(1)).setProfileNetworkPreference(
                eq(UserHandle.of(managedProfileUserId)),
                eq(ConnectivityManager.PROFILE_NETWORK_PREFERENCE_ENTERPRISE),
                any(),
                any()
        );
    }

    @Test
    public void testSetSystemSettingFailWithNonWhitelistedSettings() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        assertExpectException(SecurityException.class, null, () ->
                dpm.setSystemSetting(admin1, Settings.System.SCREEN_BRIGHTNESS_FOR_VR, "0"));
    }

    @Test
    public void testSetSystemSettingWithDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setSystemSetting(admin1, Settings.System.SCREEN_BRIGHTNESS, "0");
        verify(getServices().settings).settingsSystemPutStringForUser(
                Settings.System.SCREEN_BRIGHTNESS, "0", UserHandle.USER_SYSTEM);
    }

    @Test
    public void testSetSystemSettingWithPO() throws Exception {
        setupProfileOwner();
        dpm.setSystemSetting(admin1, Settings.System.SCREEN_BRIGHTNESS, "0");
        verify(getServices().settings).settingsSystemPutStringForUser(
            Settings.System.SCREEN_BRIGHTNESS, "0", CALLER_USER_HANDLE);
    }

    @Test
    public void testSetAutoTimeEnabledModifiesSetting() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setAutoTimeEnabled(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 1);

        dpm.setAutoTimeEnabled(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 0);
    }

    @Test
    public void testSetAutoTimeEnabledWithPOOnUser0() throws Exception {
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        setupProfileOwnerOnUser0();
        dpm.setAutoTimeEnabled(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 1);

        dpm.setAutoTimeEnabled(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 0);
    }

    @Test
    public void testSetAutoTimeEnabledFailWithPONotOnUser0() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null,
                () -> dpm.setAutoTimeEnabled(admin1, false));
        verify(getServices().settings, never()).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 0);
    }

    @Test
    public void testSetAutoTimeEnabledWithPOOfOrganizationOwnedDevice() throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        dpm.setAutoTimeEnabled(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 1);

        dpm.setAutoTimeEnabled(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME, 0);
    }

    @Test
    public void testSetAutoTimeZoneEnabledModifiesSetting() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setAutoTimeZoneEnabled(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE, 1);

        dpm.setAutoTimeZoneEnabled(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE, 0);
    }

    @Test
    public void testSetAutoTimeZoneEnabledWithPOOnUser0() throws Exception {
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        setupProfileOwnerOnUser0();
        dpm.setAutoTimeZoneEnabled(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE, 1);

        dpm.setAutoTimeZoneEnabled(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE, 0);
    }

    @Test
    public void testSetAutoTimeZoneEnabledFailWithPONotOnUser0() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null,
                () -> dpm.setAutoTimeZoneEnabled(admin1, false));
        verify(getServices().settings, never()).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE,
                0);
    }

    @Test
    public void testSetAutoTimeZoneEnabledWithPOOfOrganizationOwnedDevice() throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        dpm.setAutoTimeZoneEnabled(admin1, true);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE, 1);

        dpm.setAutoTimeZoneEnabled(admin1, false);
        verify(getServices().settings).settingsGlobalPutInt(Settings.Global.AUTO_TIME_ZONE, 0);
    }

    @Test
    public void testIsOrganizationOwnedDevice() throws Exception {
        // Set up the user manager to return correct user info
        addManagedProfile(admin1, DpmMockContext.CALLER_UID, admin1);

        // Any caller should be able to call this method.
        assertThat(dpm.isOrganizationOwnedDeviceWithManagedProfile()).isFalse();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        verify(getServices().userManager).setUserRestriction(
                eq(UserManager.DISALLOW_ADD_USER),
                eq(true),
                eq(UserHandle.of(UserHandle.USER_SYSTEM)));

        assertThat(dpm.isOrganizationOwnedDeviceWithManagedProfile()).isTrue();

        // A random caller from another user should also be able to get the right result.
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertThat(dpm.isOrganizationOwnedDeviceWithManagedProfile()).isTrue();
    }

    @Test
    public void testMarkOrganizationOwnedDevice_baseRestrictionsAdded() throws Exception {
        addManagedProfile(admin1, DpmMockContext.CALLER_UID, admin1);

        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        // Base restriction DISALLOW_REMOVE_MANAGED_PROFILE added
        verify(getServices().userManager).setUserRestriction(
                eq(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE),
                eq(true),
                eq(UserHandle.of(UserHandle.USER_SYSTEM)));

        // Base restriction DISALLOW_ADD_USER added
        verify(getServices().userManager).setUserRestriction(
                eq(UserManager.DISALLOW_ADD_USER),
                eq(true),
                eq(UserHandle.of(UserHandle.USER_SYSTEM)));

        // Assert base restrictions cannot be added or removed by admin
        assertExpectException(SecurityException.class, null, () ->
                parentDpm.addUserRestriction(admin1, UserManager.DISALLOW_REMOVE_MANAGED_PROFILE));
        assertExpectException(SecurityException.class, null, () ->
                parentDpm.clearUserRestriction(admin1,
                        UserManager.DISALLOW_REMOVE_MANAGED_PROFILE));
        assertExpectException(SecurityException.class, null, () ->
                parentDpm.addUserRestriction(admin1, UserManager.DISALLOW_ADD_USER));
        assertExpectException(SecurityException.class, null, () ->
                parentDpm.clearUserRestriction(admin1, UserManager.DISALLOW_ADD_USER));
    }

    @Test
    public void testSetTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setTime(admin1, 0);
        verify(getServices().alarmManager).setTime(0);
    }

    @Test
    public void testSetTimeFailWithPO() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null, () -> dpm.setTime(admin1, 0));
    }

    @Test
    public void testSetTimeWithPOOfOrganizationOwnedDevice() throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);
        dpm.setTime(admin1, 0);
        verify(getServices().alarmManager).setTime(0);
    }

    @Test
    public void testSetTimeWithAutoTimeOn() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        when(getServices().settings.settingsGlobalGetInt(Settings.Global.AUTO_TIME, 0))
                .thenReturn(1);
        assertThat(dpm.setTime(admin1, 0)).isFalse();
    }

    @Test
    public void testSetTimeZone() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        dpm.setTimeZone(admin1, "Asia/Shanghai");
        verify(getServices().alarmManager).setTimeZone("Asia/Shanghai");
    }

    @Test
    public void testSetTimeZoneFailWithPO() throws Exception {
        setupProfileOwner();
        assertExpectException(SecurityException.class, null,
                () -> dpm.setTimeZone(admin1, "Asia/Shanghai"));
    }

    @Test
    public void testSetTimeZoneWithPOOfOrganizationOwnedDevice() throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);
        dpm.setTimeZone(admin1, "Asia/Shanghai");
        verify(getServices().alarmManager).setTimeZone("Asia/Shanghai");
    }

    @Test
    public void testSetTimeZoneWithAutoTimeZoneOn() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        when(getServices().settings.settingsGlobalGetInt(Settings.Global.AUTO_TIME_ZONE, 0))
                .thenReturn(1);
        assertThat(dpm.setTimeZone(admin1, "Asia/Shanghai")).isFalse();
    }

    @Test
    public void testGetLastBugReportRequestTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        mContext.packageName = admin1.getPackageName();
        mContext.applicationInfo = new ApplicationInfo();
        when(mContext.resources.getColor(anyInt(), anyObject())).thenReturn(Color.WHITE);

        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(CALLER_USER_HANDLE);

        // No bug reports were requested so far.
        assertThat(dpm.getLastBugReportRequestTime()).isEqualTo(-1);

        // Requesting a bug report should update the timestamp.
        final long beforeRequest = System.currentTimeMillis();
        dpm.requestBugreport(admin1);
        final long bugReportRequestTime = dpm.getLastBugReportRequestTime();
        final long afterRequest = System.currentTimeMillis();
        assertThat(bugReportRequestTime).isAtLeast(beforeRequest);
        assertThat(bugReportRequestTime).isAtMost(afterRequest);

        // Checking the timestamp again should not change it.
        Thread.sleep(2);
        assertThat(dpm.getLastBugReportRequestTime()).isEqualTo(bugReportRequestTime);

        // Restarting the DPMS should not lose the timestamp.
        initializeDpms();
        assertThat(dpm.getLastBugReportRequestTime()).isEqualTo(bugReportRequestTime);

        // Any uid holding MANAGE_USERS permission can retrieve the timestamp.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.getLastBugReportRequestTime()).isEqualTo(bugReportRequestTime);
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve the timestamp.
        mContext.binder.clearCallingIdentity();
        assertThat(dpm.getLastBugReportRequestTime()).isEqualTo(bugReportRequestTime);

        // Removing the device owner should clear the timestamp.
        clearDeviceOwner();
        assertThat(dpm.getLastBugReportRequestTime()).isEqualTo(-1);
    }

    @Test
    public void testGetLastNetworkLogRetrievalTime() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        mContext.packageName = admin1.getPackageName();
        mContext.applicationInfo = new ApplicationInfo();
        when(mContext.resources.getColor(anyInt(), anyObject())).thenReturn(Color.WHITE);

        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(CALLER_USER_HANDLE);
        when(getServices().iipConnectivityMetrics.addNetdEventCallback(anyInt(), anyObject()))
                .thenReturn(true);

        // No logs were retrieved so far.
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Attempting to retrieve logs without enabling logging should not change the timestamp.
        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Enabling logging should not change the timestamp.
        dpm.setNetworkLoggingEnabled(admin1, true);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Retrieving the logs should update the timestamp.
        final long beforeRetrieval = System.currentTimeMillis();
        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);
        final long firstNetworkLogRetrievalTime = dpm.getLastNetworkLogRetrievalTime();
        final long afterRetrieval = System.currentTimeMillis();
        assertThat(firstNetworkLogRetrievalTime >= beforeRetrieval).isTrue();
        assertThat(firstNetworkLogRetrievalTime <= afterRetrieval).isTrue();

        // Checking the timestamp again should not change it.
        Thread.sleep(2);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(firstNetworkLogRetrievalTime);

        // Retrieving the logs again should update the timestamp.
        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);
        final long secondNetworkLogRetrievalTime = dpm.getLastNetworkLogRetrievalTime();
        assertThat(secondNetworkLogRetrievalTime > firstNetworkLogRetrievalTime).isTrue();

        // Disabling logging should not change the timestamp.
        Thread.sleep(2);
        dpm.setNetworkLoggingEnabled(admin1, false);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(secondNetworkLogRetrievalTime);

        // Restarting the DPMS should not lose the timestamp.
        initializeDpms();
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(secondNetworkLogRetrievalTime);

        // Any uid holding MANAGE_USERS permission can retrieve the timestamp.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(secondNetworkLogRetrievalTime);
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve the timestamp.
        mContext.binder.clearCallingIdentity();
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(secondNetworkLogRetrievalTime);

        // Removing the device owner should clear the timestamp.
        clearDeviceOwner();
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);
    }

    @Test
    public void testSetNetworkLoggingEnabled_asPo() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        mContext.applicationInfo = new ApplicationInfo();
        mContext.packageName = admin1.getPackageName();
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.S);
        when(getServices().iipConnectivityMetrics
                .addNetdEventCallback(anyInt(), anyObject())).thenReturn(true);

        // Check no logs have been retrieved so far.
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Enable network logging
        dpm.setNetworkLoggingEnabled(admin1, true);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Retrieve the network logs and verify timestamp has been updated.
        final long beforeRetrieval = System.currentTimeMillis();

        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);

        final long networkLogRetrievalTime = dpm.getLastNetworkLogRetrievalTime();
        final long afterRetrieval = System.currentTimeMillis();
        assertThat(networkLogRetrievalTime >= beforeRetrieval).isTrue();
        assertThat(networkLogRetrievalTime <= afterRetrieval).isTrue();
    }

    @Test
    public void testSetNetworkLoggingEnabled_asPoOfOrgOwnedDevice() throws Exception {
        // Setup profile owner on organization-owned device
        final int MANAGED_PROFILE_ADMIN_UID =
                UserHandle.getUid(CALLER_USER_HANDLE, DpmMockContext.SYSTEM_UID);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        mContext.packageName = admin1.getPackageName();
        mContext.applicationInfo = new ApplicationInfo();
        when(getServices().iipConnectivityMetrics
                .addNetdEventCallback(anyInt(), anyObject())).thenReturn(true);

        // Check no logs have been retrieved so far.
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Enable network logging
        dpm.setNetworkLoggingEnabled(admin1, true);
        assertThat(dpm.getLastNetworkLogRetrievalTime()).isEqualTo(-1);

        // Retrieve the network logs and verify timestamp has been updated.
        final long beforeRetrieval = System.currentTimeMillis();

        dpm.retrieveNetworkLogs(admin1, 0 /* batchToken */);

        final long networkLogRetrievalTime = dpm.getLastNetworkLogRetrievalTime();
        final long afterRetrieval = System.currentTimeMillis();
        assertThat(networkLogRetrievalTime >= beforeRetrieval).isTrue();
        assertThat(networkLogRetrievalTime <= afterRetrieval).isTrue();
    }

    @Test
    public void testGetBindDeviceAdminTargetUsers() throws Exception {
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);

        // Setup device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // Only device owner is setup, the result list should be empty.
        List<UserHandle> targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        // Add a secondary user, it should never talk with.
        final int ANOTHER_USER_ID = 36;
        getServices().addUser(ANOTHER_USER_ID, 0, UserManager.USER_TYPE_FULL_SECONDARY);

        // Since the managed profile is not affiliated, they should not be allowed to talk to each
        // other.
        targetUsers = dpm.getBindDeviceAdminTargetUsers(admin1);
        MoreAsserts.assertEmpty(targetUsers);

        // Setting affiliation ids
        final Set<String> userAffiliationIds = Collections.singleton("some.affiliation-id");
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        dpm.setAffiliationIds(admin1, userAffiliationIds);

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
            assertThat(dpm.isLockTaskPermitted(p)).isTrue();
        }
        assertThat(dpm.isLockTaskPermitted("anotherPackage")).isFalse();
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
        assertThat(dpm.isLockTaskPermitted("doPackage1")).isFalse();
        assertExpectException(SecurityException.class, /* messageRegex =*/ null,
                () -> dpm.setLockTaskFeatures(who, flags));
    }

    @Test
    public void testLockTaskPolicyForProfileOwner() throws Exception {
        mockPolicyExemptApps();
        mockVendorPolicyExemptApps();

        // Setup a PO
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin1);
        verifyLockTaskState(CALLER_USER_HANDLE);

        final String[] poPackages = {"poPackage1", "poPackage2"};
        final int poFlags = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        verifyCanSetLockTask(DpmMockContext.CALLER_UID, CALLER_USER_HANDLE, admin1,
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
        verifyCanNotSetLockTask(MANAGED_PROFILE_ADMIN_UID, adminDifferentPackage, mpoPackages,
                mpoFlags);
    }

    @Test
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

    @Test
    public void testSecondaryLockscreen_profileOwner() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Initial state is disabled.
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(
        CALLER_USER_HANDLE))).isFalse();

        // Profile owner can set enabled state.
        setAsProfileOwner(admin1);
        when(mServiceContext.resources
                .getString(R.string.config_defaultSupervisionProfileOwnerComponent))
                .thenReturn(admin1.flattenToString());
        dpm.setSecondaryLockscreenEnabled(admin1, true);
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(
        CALLER_USER_HANDLE))).isTrue();

        // Managed profile managed by different package is unaffiliated - cannot set enabled.
        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 20456);
        final ComponentName adminDifferentPackage =
                new ComponentName("another.package", "whatever.class");
        addManagedProfile(adminDifferentPackage, managedProfileAdminUid, admin2);
        mContext.binder.callingUid = managedProfileAdminUid;
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setSecondaryLockscreenEnabled(adminDifferentPackage, false));
    }

    @Test
    public void testSecondaryLockscreen_deviceOwner() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;

        // Initial state is disabled.
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(UserHandle.USER_SYSTEM)))
                .isFalse();

        // Device owners can set enabled state.
        setupDeviceOwner();
        when(mServiceContext.resources
                .getString(R.string.config_defaultSupervisionProfileOwnerComponent))
                .thenReturn(admin1.flattenToString());
        dpm.setSecondaryLockscreenEnabled(admin1, true);
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(UserHandle.USER_SYSTEM)))
                .isTrue();
    }

    @Test
    public void testSecondaryLockscreen_nonOwner() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Initial state is disabled.
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(CALLER_USER_HANDLE))).isFalse();

        // Non-DO/PO cannot set enabled state.
        when(mServiceContext.resources
                .getString(R.string.config_defaultSupervisionProfileOwnerComponent))
                .thenReturn(admin1.flattenToString());
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setSecondaryLockscreenEnabled(admin1, true));
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(CALLER_USER_HANDLE))).isFalse();
    }

    @Test
    public void testSecondaryLockscreen_nonSupervisionApp() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;

        // Ensure packages are *not* flagged as test_only.
        doReturn(new ApplicationInfo()).when(getServices().ipackageManager).getApplicationInfo(
                eq(admin1.getPackageName()),
                anyInt(),
                eq(CALLER_USER_HANDLE));
        doReturn(new ApplicationInfo()).when(getServices().ipackageManager).getApplicationInfo(
                eq(admin2.getPackageName()),
                anyInt(),
                eq(CALLER_USER_HANDLE));

        // Initial state is disabled.
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(CALLER_USER_HANDLE))).isFalse();

        // Caller is Profile Owner, but no supervision app is configured.
        setAsProfileOwner(admin1);
        assertExpectException(SecurityException.class, "is not the default supervision component",
                () -> dpm.setSecondaryLockscreenEnabled(admin1, true));
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(CALLER_USER_HANDLE))).isFalse();

        // Caller is Profile Owner, but is not the default configured supervision app.
        when(mServiceContext.resources
                .getString(R.string.config_defaultSupervisionProfileOwnerComponent))
                .thenReturn(admin2.flattenToString());
        assertExpectException(SecurityException.class, "is not the default supervision component",
                () -> dpm.setSecondaryLockscreenEnabled(admin1, true));
        assertThat(dpm.isSecondaryLockscreenEnabled(UserHandle.of(CALLER_USER_HANDLE))).isFalse();
    }

    @Test
    public void testIsDeviceManaged() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        // The device owner itself, any uid holding MANAGE_USERS permission and the system can
        // find out that the device has a device owner.
        assertThat(dpm.isDeviceManaged()).isTrue();
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.isDeviceManaged()).isTrue();
        mContext.callerPermissions.remove(permission.MANAGE_USERS);
        mContext.binder.clearCallingIdentity();
        assertThat(dpm.isDeviceManaged()).isTrue();

        clearDeviceOwner();

        // Any uid holding MANAGE_USERS permission and the system can find out that the device does
        // not have a device owner.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.isDeviceManaged()).isFalse();
        mContext.callerPermissions.remove(permission.MANAGE_USERS);
        mContext.binder.clearCallingIdentity();
        assertThat(dpm.isDeviceManaged()).isFalse();
    }

    @Test
    public void testDeviceOwnerOrganizationName() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();

        dpm.setOrganizationName(admin1, "organization");

        // Device owner can retrieve organization managing the device.
        assertThat(dpm.getDeviceOwnerOrganizationName()).isEqualTo("organization");

        // Any uid holding MANAGE_USERS permission can retrieve organization managing the device.
        mContext.binder.callingUid = 1234567;
        mContext.callerPermissions.add(permission.MANAGE_USERS);
        assertThat(dpm.getDeviceOwnerOrganizationName()).isEqualTo("organization");
        mContext.callerPermissions.remove(permission.MANAGE_USERS);

        // System can retrieve organization managing the device.
        mContext.binder.clearCallingIdentity();
        assertThat(dpm.getDeviceOwnerOrganizationName()).isEqualTo("organization");

        // Removing the device owner clears the organization managing the device.
        clearDeviceOwner();
        assertThat(dpm.getDeviceOwnerOrganizationName()).isNull();
    }

    @Test
    public void testWipeDataManagedProfile() throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        mServiceContext.permissions.add(permission.INTERACT_ACROSS_USERS_FULL);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));
        // Get mock reason string since we throw an IAE with empty string input.
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe))
                .thenReturn("Just a test string.");

        dpm.wipeData(0);
        verify(getServices().userManagerInternal).removeUserEvenWhenDisallowed(
                MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testWipeDataManagedProfileOnOrganizationOwnedDevice() throws Exception {
        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));
        // Get mock reason string since we throw an IAE with empty string input.
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe))
                .thenReturn("Just a test string.");
        when(getServices().userManager.getProfileParent(CALLER_USER_HANDLE))
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));
        when(getServices().userManager.getPrimaryUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        // Set some device-wide policies:
        // Security logging
        when(getServices().settings.securityLogGetLoggingEnabledProperty()).thenReturn(true);
        // System update policy
        dpms.mOwners.setSystemUpdatePolicy(SystemUpdatePolicy.createAutomaticInstallPolicy());
        // Make it look as if FRP agent is present.
        when(dpms.mMockInjector.getPersistentDataBlockManagerInternal().getAllowedUid())
                .thenReturn(12345 /* some UID in user 0 */);
        // Make personal apps look suspended
        dpms.getUserData(UserHandle.USER_SYSTEM).mAppsSuspended = true;

        clearInvocations(getServices().iwindowManager);

        dpm.wipeData(0);
        verify(getServices().userManagerInternal).removeUserEvenWhenDisallowed(CALLER_USER_HANDLE);

        // Make sure COPE restrictions are lifted:
        verify(getServices().userManager).setUserRestriction(
                UserManager.DISALLOW_REMOVE_MANAGED_PROFILE, false, UserHandle.SYSTEM);
        verify(getServices().userManager).setUserRestriction(
                UserManager.DISALLOW_ADD_USER, false, UserHandle.SYSTEM);

        // Some device-wide policies are getting cleaned-up after the user is removed.
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_REMOVED, CALLER_USER_HANDLE);

        // Screenlock info should be removed
        verify(getServices().lockPatternUtils).setDeviceOwnerInfo(null);
        // Wifi config lockdown should be lifted
        verify(getServices().settings).settingsGlobalPutInt(
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0);
        // System update policy should be removed
        assertThat(dpms.mOwners.getSystemUpdatePolicy()).isNull();
        // FRP agent should be notified
        verify(mContext.spiedContext, times(0)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_RESET_PROTECTION_POLICY_CHANGED),
                MockUtils.checkUserHandle(UserHandle.USER_SYSTEM));
        // Refresh strong auth timeout and screen capture
        verify(getServices().lockSettingsInternal).refreshStrongAuthTimeout(UserHandle.USER_SYSTEM);
        verify(getServices().iwindowManager).refreshScreenCaptureDisabled(UserHandle.USER_SYSTEM);
        // Unsuspend personal apps
        verify(getServices().packageManagerInternal)
                .unsuspendForSuspendingPackage(PLATFORM_PACKAGE_NAME, UserHandle.USER_SYSTEM);
    }

    @Test
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

    @Test
    public void testWipeDataDeviceOwner() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
                UserManager.DISALLOW_FACTORY_RESET,
                UserHandle.SYSTEM))
                .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");

        dpm.wipeData(0);

        verifyRebootWipeUserData(/* wipeEuicc= */ false);
    }

    @Test
    public void testWipeEuiccDataEnabled() throws Exception {
        setDeviceOwner();
        when(getServices().userManager.getUserRestrictionSource(
            UserManager.DISALLOW_FACTORY_RESET,
            UserHandle.SYSTEM))
            .thenReturn(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER);
        when(mContext.getResources().getString(R.string.work_profile_deleted_description_dpm_wipe)).
                thenReturn("Just a test string.");

        dpm.wipeData(WIPE_EUICC);

        verifyRebootWipeUserData(/* wipeEuicc= */ true);
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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
        verifyRebootWipeUserData(/* wipeEuicc= */ false);
    }

    @Test
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

    @Test
    public void testMaximumFailedDevicePasswordAttemptsReachedOrgOwnedManagedProfile()
            throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        configureProfileOwnerOfOrgOwnedDevice(admin1, MANAGED_PROFILE_USER_ID);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setMaximumFailedPasswordsForWipe(admin1, 3);

        assertThat(dpm.getMaximumFailedPasswordsForWipe(admin1)).isEqualTo(3);
        assertThat(dpm.getMaximumFailedPasswordsForWipe(null)).isEqualTo(3);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);

        assertThat(dpm.getMaximumFailedPasswordsForWipe(null, UserHandle.USER_SYSTEM)).isEqualTo(3);
        // Check that primary will be wiped as a result of failed primary user unlock attempts.
        assertThat(dpm.getProfileWithMinimumFailedPasswordsForWipe(UserHandle.USER_SYSTEM))
                .isEqualTo(UserHandle.USER_SYSTEM);

        // Failed password attempts on the parent user are taken into account, as there isn't a
        // separate work challenge.
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);
        dpm.reportFailedPasswordAttempt(UserHandle.USER_SYSTEM);

        // For managed profile on an organization owned device, the whole device should be wiped.
        verifyRebootWipeUserData(/* wipeEuicc= */ false);
    }

    @Test
    public void testMaximumFailedProfilePasswordAttemptsReachedOrgOwnedManagedProfile()
            throws Exception {
        final int MANAGED_PROFILE_USER_ID = 15;
        final int MANAGED_PROFILE_ADMIN_UID = UserHandle.getUid(MANAGED_PROFILE_USER_ID, 19436);
        addManagedProfile(admin1, MANAGED_PROFILE_ADMIN_UID, admin1);

        // Even if the caller is the managed profile, the current user is the user 0
        when(getServices().iactivityManager.getCurrentUser())
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        doReturn(true).when(getServices().lockPatternUtils)
                .isSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID);

        // Configure separate challenge.
        configureProfileOwnerOfOrgOwnedDevice(admin1, MANAGED_PROFILE_USER_ID);

        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        dpm.setMaximumFailedPasswordsForWipe(admin1, 3);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.callerPermissions.add(permission.BIND_DEVICE_ADMIN);

        assertThat(dpm.getMaximumFailedPasswordsForWipe(null, UserHandle.USER_SYSTEM)).isEqualTo(0);
        assertThat(dpm.getMaximumFailedPasswordsForWipe(null, MANAGED_PROFILE_USER_ID))
                .isEqualTo(3);
        // Check that the policy is not affecting primary profile challenge.
        assertThat(dpm.getProfileWithMinimumFailedPasswordsForWipe(UserHandle.USER_SYSTEM))
                .isEqualTo(UserHandle.USER_NULL);
        // Check that primary will be wiped as a result of failed profile unlock attempts.
        assertThat(dpm.getProfileWithMinimumFailedPasswordsForWipe(MANAGED_PROFILE_USER_ID))
                .isEqualTo(UserHandle.USER_SYSTEM);

        // Simulate three failed attempts at solving the separate challenge.
        dpm.reportFailedPasswordAttempt(MANAGED_PROFILE_USER_ID);
        dpm.reportFailedPasswordAttempt(MANAGED_PROFILE_USER_ID);
        dpm.reportFailedPasswordAttempt(MANAGED_PROFILE_USER_ID);

        // For managed profile on an organization owned device, the whole device should be wiped.
        verifyRebootWipeUserData(/* wipeEuicc= */ false);
    }

    @Test
    public void testGetPermissionGrantState() throws Exception {
        final String permission = "some.permission";
        final String app1 = "com.example.app1";
        final String app2 = "com.example.app2";

        when(getServices().ipackageManager.checkPermission(eq(permission), eq(app1), anyInt()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        doReturn(PackageManager.FLAG_PERMISSION_POLICY_FIXED).when(getServices().packageManager)
                .getPermissionFlags(permission, app1, UserHandle.SYSTEM);
        when(getServices().packageManager.getPermissionFlags(permission, app1,
                UserHandle.of(CALLER_USER_HANDLE)))
                .thenReturn(PackageManager.FLAG_PERMISSION_POLICY_FIXED);
        when(getServices().ipackageManager.checkPermission(eq(permission), eq(app2), anyInt()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        doReturn(0).when(getServices().packageManager).getPermissionFlags(permission, app2,
                UserHandle.SYSTEM);
        when(getServices().packageManager.getPermissionFlags(permission, app2,
                UserHandle.of(CALLER_USER_HANDLE))).thenReturn(0);

        // System can retrieve permission grant state.
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mContext.packageName = "android";
        assertThat(dpm.getPermissionGrantState(null, app1, permission))
                .isEqualTo(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        assertThat(dpm.getPermissionGrantState(null, app2, permission))
                .isEqualTo(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT);

        // A regular app cannot retrieve permission grant state.
        mContext.binder.callingUid = setupPackageInPackageManager(app1, 1);
        mContext.packageName = app1;
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.getPermissionGrantState(null, app1, permission));

        // Profile owner can retrieve permission grant state.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        mContext.packageName = admin1.getPackageName();
        setAsProfileOwner(admin1);
        assertThat(dpm.getPermissionGrantState(admin1, app1, permission))
                .isEqualTo(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        assertThat(dpm.getPermissionGrantState(admin1, app2, permission))
                .isEqualTo(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT);
    }

    @Test
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
        assertThat(dpm.setResetPasswordToken(admin1, token)).isTrue();

        // test password activation
        when(getServices().lockPatternUtils.isEscrowTokenActive(handle, UserHandle.USER_SYSTEM))
                .thenReturn(true);
        assertThat(dpm.isResetPasswordTokenActive(admin1)).isTrue();

        // test reset password with token
        when(getServices().lockPatternUtils.setLockCredentialWithToken(
                LockscreenCredential.createPassword(password), handle, token,
                UserHandle.USER_SYSTEM)).thenReturn(true);
        assertThat(dpm.resetPasswordWithToken(admin1, password, token, 0)).isTrue();

        // test removing a token
        when(getServices().lockPatternUtils.removeEscrowToken(handle, UserHandle.USER_SYSTEM))
                .thenReturn(true);
        assertThat(dpm.clearResetPasswordToken(admin1)).isTrue();
    }

    @Test
    public void resetPasswordWithToken_NumericPin() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        // adding a token
        final byte[] token = new byte[32];
        final long handle = 123456;
        when(getServices().lockPatternUtils.addEscrowToken(eq(token), eq(UserHandle.USER_SYSTEM),
                nullable(EscrowTokenStateChangeCallback.class)))
                .thenReturn(handle);
        assertThat(dpm.setResetPasswordToken(admin1, token)).isTrue();

        // Test resetting with a numeric pin
        final String pin = "123456";
        when(getServices().lockPatternUtils.setLockCredentialWithToken(
                LockscreenCredential.createPin(pin), handle, token,
                UserHandle.USER_SYSTEM)).thenReturn(true);
        assertThat(dpm.resetPasswordWithToken(admin1, pin, token, 0)).isTrue();
    }

    @Test
    public void resetPasswordWithToken_EmptyPassword() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        // adding a token
        final byte[] token = new byte[32];
        final long handle = 123456;
        when(getServices().lockPatternUtils.addEscrowToken(eq(token), eq(UserHandle.USER_SYSTEM),
                nullable(EscrowTokenStateChangeCallback.class)))
                .thenReturn(handle);
        assertThat(dpm.setResetPasswordToken(admin1, token)).isTrue();

        // Test resetting with an empty password
        final String password = "";
        when(getServices().lockPatternUtils.setLockCredentialWithToken(
                LockscreenCredential.createNone(), handle, token,
                UserHandle.USER_SYSTEM)).thenReturn(true);
        assertThat(dpm.resetPasswordWithToken(admin1, password, token, 0)).isTrue();
    }

    @Test
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

        PasswordMetrics passwordMetricsNoSymbols = computeForPasswordOrPin(
                "abcdXYZ5".getBytes(), /* isPin */ false);

        setActivePasswordState(passwordMetricsNoSymbols);
        assertThat(dpm.isActivePasswordSufficient()).isTrue();

        initializeDpms();
        reset(mContext.spiedContext);
        assertThat(dpm.isActivePasswordSufficient()).isTrue();

        // This call simulates the user entering the password for the first time after a reboot.
        // This causes password metrics to be reloaded into memory.  Until this happens,
        // dpm.isActivePasswordSufficient() will continue to return its last checkpointed value,
        // even if the DPC changes password requirements so that the password no longer meets the
        // requirements.  This is a known limitation of the current implementation of
        // isActivePasswordSufficient() - see b/34218769.
        setActivePasswordState(passwordMetricsNoSymbols);
        assertThat(dpm.isActivePasswordSufficient()).isTrue();

        dpm.setPasswordMinimumSymbols(admin1, 1);
        // This assertion would fail if we had not called setActivePasswordState() again after
        // initializeDpms() - see previous comment.
        assertThat(dpm.isActivePasswordSufficient()).isFalse();

        initializeDpms();
        reset(mContext.spiedContext);
        assertThat(dpm.isActivePasswordSufficient()).isFalse();

        PasswordMetrics passwordMetricsWithSymbols = computeForPasswordOrPin(
                "abcd.XY5".getBytes(), /* isPin */ false);

        setActivePasswordState(passwordMetricsWithSymbols);
        assertThat(dpm.isActivePasswordSufficient()).isTrue();
    }

    @Test
    public void testIsActivePasswordSufficient_noLockScreen() throws Exception {
        // If there is no lock screen, the password is considered empty no matter what, because
        // it provides no security.
        when(getServices().lockPatternUtils.hasSecureLockScreen()).thenReturn(false);

        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        mContext.packageName = admin1.getPackageName();
        setupDeviceOwner();
        final int userHandle = UserHandle.getUserId(mContext.binder.callingUid);
        // When there is no lockscreen, user password metrics is always empty.
        when(getServices().lockSettingsInternal.getUserPasswordMetrics(userHandle))
                .thenReturn(new PasswordMetrics(CREDENTIAL_TYPE_NONE));

        // If no password requirements are set, isActivePasswordSufficient should succeed.
        assertThat(dpm.isActivePasswordSufficient()).isTrue();

        // Now set some password quality requirements.
        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);

        reset(mContext.spiedContext);
        // This should be ignored, as there is no lock screen.
        dpm.reportPasswordChanged(userHandle);

        // No broadcast should be sent.
        verify(mContext.spiedContext, times(0)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED),
                MockUtils.checkUserHandle(userHandle));

        // The active (nonexistent) password doesn't comply with the requirements.
        assertThat(dpm.isActivePasswordSufficient()).isFalse();
    }

    @Test
    public void testIsPasswordSufficientAfterProfileUnification() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;

        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        doReturn(true).when(getServices().lockPatternUtils)
                .isSeparateProfileChallengeEnabled(managedProfileUserId);

        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH);
        parentDpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_MEDIUM);

        when(getServices().lockSettingsInternal.getUserPasswordMetrics(UserHandle.USER_SYSTEM))
                .thenReturn(computeForPasswordOrPin("184342".getBytes(), /* isPin */ true));

        // Numeric password is compliant with current requirement (QUALITY_NUMERIC set explicitly
        // on the parent admin)
        assertThat(dpm.isPasswordSufficientAfterProfileUnification(UserHandle.USER_SYSTEM,
        UserHandle.USER_NULL)).isTrue();
        // Numeric password is not compliant if profile is to be unified: the profile has a
        // QUALITY_ALPHABETIC policy on itself which will be enforced on the password after
        // unification.
        assertThat(dpm.isPasswordSufficientAfterProfileUnification(UserHandle.USER_SYSTEM,
        managedProfileUserId)).isFalse();
    }

    @Test
    public void testGetAggregatedPasswordComplexity_IgnoreProfileRequirement()
            throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);

        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH);
        parentDpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW);

        assertThat(dpms.getAggregatedPasswordComplexityForUser(UserHandle.USER_SYSTEM, true))
                .isEqualTo(PASSWORD_COMPLEXITY_LOW);
        assertThat(dpms.getAggregatedPasswordComplexityForUser(UserHandle.USER_SYSTEM, false))
                .isEqualTo(PASSWORD_COMPLEXITY_HIGH);
    }

    @Test
    public void testGetAggregatedPasswordMetrics_IgnoreProfileRequirement()
            throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);

        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
        dpm.setPasswordMinimumLength(admin1, 8);
        dpm.setPasswordMinimumLetters(admin1, 1);
        dpm.setPasswordMinimumNumeric(admin1, 2);
        dpm.setPasswordMinimumSymbols(admin1, 3);

        parentDpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);

        PasswordMetrics deviceMetrics =
                dpms.getPasswordMinimumMetrics(UserHandle.USER_SYSTEM, true);
        assertThat(deviceMetrics.credType).isEqualTo(LockPatternUtils.CREDENTIAL_TYPE_PATTERN);

        PasswordMetrics allMetrics =
                dpms.getPasswordMinimumMetrics(UserHandle.USER_SYSTEM, false);
        assertThat(allMetrics.credType).isEqualTo(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD);
        assertThat(allMetrics.length).isEqualTo(8);
        assertThat(allMetrics.letters).isEqualTo(1);
        assertThat(allMetrics.numeric).isEqualTo(2);
        assertThat(allMetrics.symbols).isEqualTo(3);
    }

    @Test
    public void testCanSetPasswordRequirementOnParentPreS() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);
        dpms.mMockInjector.setChangeEnabledForPackage(165573442L, false,
                admin1.getPackageName(), managedProfileUserId);

        parentDpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
        assertThat(parentDpm.getPasswordQuality(admin1))
                .isEqualTo(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
    }

    @Test
    public void testCannotSetPasswordRequirementOnParent() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        dpms.mMockInjector.setChangeEnabledForPackage(165573442L, true,
                admin1.getPackageName(), managedProfileUserId);

        try {
            assertExpectException(SecurityException.class, null, () ->
                    parentDpm.setPasswordQuality(
                            admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX));
        } finally {
            dpms.mMockInjector.clearEnabledChanges();
        }
    }

    @Test
    public void testPasswordQualityAppliesToParentPreS() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);
        when(getServices().userManager.getProfileParent(CALLER_USER_HANDLE))
                .thenReturn(new UserInfo(UserHandle.USER_SYSTEM, "user system", 0));

        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
        assertThat(parentDpm.getPasswordQuality(null))
                .isEqualTo(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
    }

    @Test
    public void testPasswordQualityDoesNotApplyToParentPostS() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);

        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
        assertThat(parentDpm.getPasswordQuality(admin1))
                .isEqualTo(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
    }

    private void setActivePasswordState(PasswordMetrics passwordMetrics)
            throws Exception {
        final int userHandle = UserHandle.getUserId(mContext.binder.callingUid);
        final long ident = mContext.binder.clearCallingIdentity();

        when(getServices().lockSettingsInternal.getUserPasswordMetrics(userHandle))
                .thenReturn(passwordMetrics);
        dpm.reportPasswordChanged(userHandle);

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntentAction(
                        DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                MockUtils.checkUserHandle(userHandle));

        final Intent intent = new Intent(DeviceAdminReceiver.ACTION_PASSWORD_CHANGED);
        intent.setComponent(admin1);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(userHandle));

        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(intent),
                MockUtils.checkUserHandle(userHandle),
                eq(null),
                any());

        // CertificateMonitor.updateInstalledCertificates is called on the background thread,
        // let it finish with system uid, otherwise it will throw and crash.
        flushTasks(dpms);

        mContext.binder.restoreCallingIdentity(ident);
    }

    @Test
    public void testIsCurrentInputMethodSetByOwnerForDeviceOwner() throws Exception {
        final String currentIme = Settings.Secure.DEFAULT_INPUT_METHOD;
        final Uri currentImeUri = Settings.Secure.getUriFor(currentIme);
        final int deviceOwnerUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        final int firstUserSystemUid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                DpmMockContext.SYSTEM_UID);
        final int secondUserSystemUid = UserHandle.getUid(CALLER_USER_HANDLE,
                DpmMockContext.SYSTEM_UID);

        // Set up a device owner.
        mContext.binder.callingUid = deviceOwnerUid;
        setupDeviceOwner();

        // First and second user set IMEs manually.
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Device owner changes IME for first user.
        mContext.binder.callingUid = deviceOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme,
                UserHandle.USER_SYSTEM)).thenReturn("ime1");
        dpm.setSecureSetting(admin1, currentIme, "ime2");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime2",
                UserHandle.USER_SYSTEM);
        reset(getServices().settings);
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Second user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // First user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Device owner changes IME for first user again.
        mContext.binder.callingUid = deviceOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme,
                UserHandle.USER_SYSTEM)).thenReturn("ime2");
        dpm.setSecureSetting(admin1, currentIme, "ime3");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime3",
                UserHandle.USER_SYSTEM);
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Restarting the DPMS should not lose information.
        initializeDpms();
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Device owner can find out whether it set the current IME itself.
        mContext.binder.callingUid = deviceOwnerUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();

        // Removing the device owner should clear the information that it set the current IME.
        clearDeviceOwner();
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
    }

    @Test
    public void testIsCurrentInputMethodSetByOwnerForProfileOwner() throws Exception {
        final String currentIme = Settings.Secure.DEFAULT_INPUT_METHOD;
        final Uri currentImeUri = Settings.Secure.getUriFor(currentIme);
        final int profileOwnerUid = DpmMockContext.CALLER_UID;
        final int firstUserSystemUid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                DpmMockContext.SYSTEM_UID);
        final int secondUserSystemUid = UserHandle.getUid(CALLER_USER_HANDLE,
                DpmMockContext.SYSTEM_UID);

        // Set up a profile owner.
        mContext.binder.callingUid = profileOwnerUid;
        setupProfileOwner();

        // First and second user set IMEs manually.
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Profile owner changes IME for second user.
        mContext.binder.callingUid = profileOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme,
                CALLER_USER_HANDLE)).thenReturn("ime1");
        dpm.setSecureSetting(admin1, currentIme, "ime2");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime2",
                CALLER_USER_HANDLE);
        reset(getServices().settings);
        dpms.notifyChangeToContentObserver(currentImeUri, CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();

        // First user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, UserHandle.USER_SYSTEM);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();

        // Second user changes IME manually.
        dpms.notifyChangeToContentObserver(currentImeUri, CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();

        // Profile owner changes IME for second user again.
        mContext.binder.callingUid = profileOwnerUid;
        when(getServices().settings.settingsSecureGetStringForUser(currentIme,
                CALLER_USER_HANDLE)).thenReturn("ime2");
        dpm.setSecureSetting(admin1, currentIme, "ime3");
        verify(getServices().settings).settingsSecurePutStringForUser(currentIme, "ime3",
                CALLER_USER_HANDLE);
        dpms.notifyChangeToContentObserver(currentImeUri, CALLER_USER_HANDLE);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();

        // Restarting the DPMS should not lose information.
        initializeDpms();
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();

        // Profile owner can find out whether it set the current IME itself.
        mContext.binder.callingUid = profileOwnerUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isTrue();

        // Removing the profile owner should clear the information that it set the current IME.
        dpm.clearProfileOwner(admin1);
        mContext.binder.callingUid = firstUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
        mContext.binder.callingUid = secondUserSystemUid;
        assertThat(dpm.isCurrentInputMethodSetByOwner()).isFalse();
    }

    @Test
    public void testSetPermittedCrossProfileNotificationListeners_unavailableForDo()
            throws Exception {
        // Set up a device owner.
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        assertSetPermittedCrossProfileNotificationListenersUnavailable(mContext.binder.callingUid);
    }

    @Test
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
        assertThat(dpms.setPermittedCrossProfileNotificationListeners(
        admin1, Collections.singletonList(packageName))).isFalse();
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1)).isNull();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(packageName, userId)).isTrue();

        // Attempt to set to empty list (which means no listener is allowlisted)
        mContext.binder.callingUid = adminUid;
        assertThat(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, emptyList())).isFalse();
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1)).isNull();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(packageName, userId)).isTrue();
    }

    @Test
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

        assertThat(dpms.setPermittedCrossProfileNotificationListeners(
        admin1, Collections.singletonList(permittedListener))).isTrue();

        // isNotificationListenerServicePermitted should throw if not called from System.
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpms.isNotificationListenerServicePermitted(
                        permittedListener, MANAGED_PROFILE_USER_ID));

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        permittedListener, MANAGED_PROFILE_USER_ID)).isTrue();
    }

    @Test
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
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1)).isNull();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        permittedListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        notPermittedListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, MANAGED_PROFILE_USER_ID)).isTrue();

        // Setting only one package in the allowlist
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertThat(dpms.setPermittedCrossProfileNotificationListeners(
        admin1, Collections.singletonList(permittedListener))).isTrue();
        final List<String> permittedListeners =
                dpms.getPermittedCrossProfileNotificationListeners(admin1);
        assertThat(permittedListeners.size()).isEqualTo(1);
        assertThat(permittedListeners.get(0)).isEqualTo(permittedListener);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        permittedListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        notPermittedListener, MANAGED_PROFILE_USER_ID)).isFalse();
        // System packages are always allowed (even if not in the allowlist)
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, MANAGED_PROFILE_USER_ID)).isTrue();

        // Setting an empty allowlist - only system listeners allowed
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertThat(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, emptyList())).isTrue();
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1).size()).isEqualTo(0);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        permittedListener, MANAGED_PROFILE_USER_ID)).isFalse();
        assertThat(dpms.isNotificationListenerServicePermitted(
        notPermittedListener, MANAGED_PROFILE_USER_ID)).isFalse();
        // System packages are always allowed (even if not in the allowlist)
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, MANAGED_PROFILE_USER_ID)).isTrue();

        // Setting a null allowlist - all listeners allowed
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertThat(dpms.setPermittedCrossProfileNotificationListeners(admin1, null)).isTrue();
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1)).isNull();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        permittedListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        notPermittedListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, MANAGED_PROFILE_USER_ID)).isTrue();
    }

    @Test
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
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1)).isNull();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        nonSystemPackage, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        nonSystemPackage, UserHandle.USER_SYSTEM)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, UserHandle.USER_SYSTEM)).isTrue();

        // Setting an empty allowlist - only system listeners allowed in managed profile, but
        // all allowed in primary profile
        mContext.binder.callingUid = MANAGED_PROFILE_ADMIN_UID;
        assertThat(dpms.setPermittedCrossProfileNotificationListeners(
                admin1, emptyList())).isTrue();
        assertThat(dpms.getPermittedCrossProfileNotificationListeners(admin1).size()).isEqualTo(0);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpms.isNotificationListenerServicePermitted(
        nonSystemPackage, MANAGED_PROFILE_USER_ID)).isFalse();
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, MANAGED_PROFILE_USER_ID)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        nonSystemPackage, UserHandle.USER_SYSTEM)).isTrue();
        assertThat(dpms.isNotificationListenerServicePermitted(
        systemListener, UserHandle.USER_SYSTEM)).isTrue();
    }

    @Test
    public void testGetOwnerInstalledCaCertsForDeviceOwner() throws Exception {
        mServiceContext.packageName = mRealTestContext.getPackageName();
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setDeviceOwner();

        verifyCanGetOwnerInstalledCaCerts(admin1, mAdmin1Context);
    }

    @Test
    public void testGetOwnerInstalledCaCertsForProfileOwner() throws Exception {
        mServiceContext.packageName = mRealTestContext.getPackageName();
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin1);

        verifyCanGetOwnerInstalledCaCerts(admin1, mAdmin1Context);
        verifyCantGetOwnerInstalledCaCertsProfileOwnerRemoval(admin1, mAdmin1Context);
    }

    @Test
    public void testGetOwnerInstalledCaCertsForDelegate() throws Exception {
        mServiceContext.packageName = mRealTestContext.getPackageName();
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        mAdmin1Context.binder.callingUid = DpmMockContext.CALLER_UID;
        setAsProfileOwner(admin1);

        final DpmMockContext caller = new DpmMockContext(getServices(), mRealTestContext);
        caller.packageName = "com.example.delegate";
        caller.binder.callingUid = setupPackageInPackageManager(caller.packageName,
                CALLER_USER_HANDLE, 20988, ApplicationInfo.FLAG_HAS_CODE);

        // Make caller a delegated cert installer.
        runAsCaller(mAdmin1Context, dpms,
                dpm -> dpm.setCertInstallerPackage(admin1, caller.packageName));

        verifyCanGetOwnerInstalledCaCerts(null, caller);
        verifyCantGetOwnerInstalledCaCertsProfileOwnerRemoval(null, caller);
    }

    @Test
    public void testDisallowSharingIntoProfileSetRestriction() {
        when(mServiceContext.resources.getString(R.string.config_managed_provisioning_package))
                .thenReturn("com.android.managedprovisioning");
        when(getServices().userManagerInternal.getProfileParentId(anyInt()))
                .thenReturn(UserHandle.USER_SYSTEM);
        mServiceContext.binder.callingPid = DpmMockContext.SYSTEM_PID;
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        Bundle restriction = new Bundle();
        restriction.putBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, true);

        RestrictionsListener listener = new RestrictionsListener(
                mServiceContext, getServices().userManagerInternal, dpms);
        listener.onUserRestrictionsChanged(CALLER_USER_HANDLE, restriction, new Bundle());

        verifyDataSharingAppliedBroadcast();
    }

    @Test
    public void testDisallowSharingIntoProfileClearRestriction() {
        when(mServiceContext.resources.getString(R.string.config_managed_provisioning_package))
                .thenReturn("com.android.managedprovisioning");
        when(getServices().userManagerInternal.getProfileParentId(anyInt()))
                .thenReturn(UserHandle.USER_SYSTEM);
        mServiceContext.binder.callingPid = DpmMockContext.SYSTEM_PID;
        mServiceContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        Bundle restriction = new Bundle();
        restriction.putBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, true);

        RestrictionsListener listener = new RestrictionsListener(
                mServiceContext, getServices().userManagerInternal, dpms);
        listener.onUserRestrictionsChanged(CALLER_USER_HANDLE, new Bundle(), restriction);

        verifyDataSharingAppliedBroadcast();
    }

    @Test
    public void testDisallowSharingIntoProfileUnchanged() {
        RestrictionsListener listener = new RestrictionsListener(
                mContext, getServices().userManagerInternal, dpms);
        listener.onUserRestrictionsChanged(CALLER_USER_HANDLE, new Bundle(), new Bundle());
        verify(mContext.spiedContext, never()).sendBroadcastAsUser(any(), any());
    }

    private void verifyDataSharingAppliedBroadcast() {
        Intent expectedIntent = new Intent(
                DevicePolicyManager.ACTION_DATA_SHARING_RESTRICTION_APPLIED);
        verify(mContext.spiedContext, times(1)).sendBroadcastAsUser(
                MockUtils.checkIntent(expectedIntent),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));
    }

    @Test
    public void testOverrideApnAPIsFailWithPO() throws Exception {
        when(getServices().packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
                .thenReturn(true);
        // FEATURE_TELEPHONY is set in DPMS's constructor and therefore a new DPMS instance
        // is created after turning on the feature.
        initializeDpms();
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
            assertThat(dpm.installCaCert(caller, caCert)).isTrue();
            when(getServices().keyChainConnection.getService().getUserCaAliases())
                    .thenReturn(asSlice(new String[] {alias}));
        });

        getServices().injectBroadcast(mServiceContext,
                new Intent(KeyChain.ACTION_TRUST_STORE_CHANGED)
                        .putExtra(Intent.EXTRA_USER_HANDLE, callerUser.getIdentifier()),
                callerUser.getIdentifier());
        flushTasks(dpms);

        final List<String> ownerInstalledCaCerts = new ArrayList<>();

        // Device Owner / Profile Owner can find out which CA certs were installed by itself.
        runAsCaller(admin1Context, dpms, (dpm) -> {
            final List<String> installedCaCerts = dpm.getOwnerInstalledCaCerts(callerUser);
            assertThat(installedCaCerts).isEqualTo(Collections.singletonList(alias));
            ownerInstalledCaCerts.addAll(installedCaCerts);
        });

        // Restarting the DPMS should not lose information.
        initializeDpms();
        runAsCaller(admin1Context, dpms,
                (dpm) -> assertThat(dpm.getOwnerInstalledCaCerts(callerUser))
                        .isEqualTo(ownerInstalledCaCerts));

        // System can find out which CA certs were installed by the Device Owner / Profile Owner.
        runAsCaller(serviceContext, dpms, (dpm) -> {
            assertThat(dpm.getOwnerInstalledCaCerts(callerUser)).isEqualTo(ownerInstalledCaCerts);

            // Remove the CA cert.
            reset(getServices().keyChainConnection.getService());
        });

        getServices().injectBroadcast(mServiceContext,
                new Intent(KeyChain.ACTION_TRUST_STORE_CHANGED)
                        .putExtra(Intent.EXTRA_USER_HANDLE, callerUser.getIdentifier()),
                callerUser.getIdentifier());
        flushTasks(dpms);

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
            assertThat(dpm.installCaCert(callerName, caCert)).isTrue();
        });

        // Fake the CA cert as having been installed
        when(getServices().keyChainConnection.getService().getUserCaAliases())
                .thenReturn(asSlice(new String[] {alias}));
        getServices().injectBroadcast(mServiceContext,
                new Intent(KeyChain.ACTION_TRUST_STORE_CHANGED)
                        .putExtra(Intent.EXTRA_USER_HANDLE, callerUser.getIdentifier()),
                callerUser.getIdentifier());
        flushTasks(dpms);

        // Removing the Profile Owner should clear the information on which CA certs were installed
        runAsCaller(admin1Context, dpms, dpm -> dpm.clearProfileOwner(admin1));

        runAsCaller(serviceContext, dpms, (dpm) -> {
            final List<String> ownerInstalledCaCerts = dpm.getOwnerInstalledCaCerts(callerUser);
            assertThat(ownerInstalledCaCerts).isNotNull();
            assertThat(ownerInstalledCaCerts.isEmpty()).isTrue();
        });
    }

    private void verifyRebootWipeUserData(boolean wipeEuicc) throws Exception {
        verify(getServices().recoverySystem).rebootWipeUserData(/*shutdown=*/ eq(false),
                /* reason= */ anyString(), /*force=*/ eq(true), eq(wipeEuicc),
                /* wipeAdoptableStorage= */ eq(false), /* wipeFactoryResetProtection= */ eq(false));
    }

    private void assertAttestationFlags(int attestationFlags, int[] expectedFlags) {
        int[] gotFlags = DevicePolicyManagerService.translateIdAttestationFlags(attestationFlags);
        Arrays.sort(gotFlags);
        Arrays.sort(expectedFlags);
        assertThat(Arrays.equals(expectedFlags, gotFlags)).isTrue();
    }

    @Test
    public void testTranslationOfIdAttestationFlag() {
        int[] allIdTypes = new int[]{ID_TYPE_SERIAL, ID_TYPE_IMEI, ID_TYPE_MEID};
        int[] correspondingAttUtilsTypes = new int[]{
            AttestationUtils.ID_TYPE_SERIAL, AttestationUtils.ID_TYPE_IMEI,
            AttestationUtils.ID_TYPE_MEID};

        // Test translation of zero flags
        assertThat(DevicePolicyManagerService.translateIdAttestationFlags(0)).isNull();

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

    @Test
    public void testRevertDeviceOwnership_noMetadataFile() throws Exception {
        setDeviceOwner();
        initializeDpms();
        assertThat(getMockTransferMetadataManager().metadataFileExists()).isFalse();
        assertThat(dpms.isDeviceOwner(admin1, UserHandle.USER_SYSTEM)).isTrue();
        assertThat(dpms.isAdminActive(admin1, UserHandle.USER_SYSTEM)).isTrue();
    }

    @FlakyTest(bugId = 148934649)
    @Test
    public void testRevertDeviceOwnership_adminAndDeviceMigrated() throws Exception {
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getDeviceOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.device_owner_migrated),
                getDeviceOwnerFile());
        assertDeviceOwnershipRevertedWithFakeTransferMetadata();
    }

    @FlakyTest(bugId = 148934649)
    @Test
    public void testRevertDeviceOwnership_deviceNotMigrated() throws Exception {
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getDeviceOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.device_owner_not_migrated),
                getDeviceOwnerFile());
        assertDeviceOwnershipRevertedWithFakeTransferMetadata();
    }

    @Test
    public void testRevertDeviceOwnership_adminAndDeviceNotMigrated() throws Exception {
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_not_migrated),
                getDeviceOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.device_owner_not_migrated),
                getDeviceOwnerFile());
        assertDeviceOwnershipRevertedWithFakeTransferMetadata();
    }

    @Test
    public void testRevertProfileOwnership_noMetadataFile() throws Exception {
        setupProfileOwner();
        initializeDpms();
        assertThat(getMockTransferMetadataManager().metadataFileExists()).isFalse();
        assertThat(dpms.isProfileOwner(admin1, CALLER_USER_HANDLE)).isTrue();
        assertThat(dpms.isAdminActive(admin1, CALLER_USER_HANDLE)).isTrue();
    }

    @FlakyTest(bugId = 148934649)
    @Test
    public void testRevertProfileOwnership_adminAndProfileMigrated() throws Exception {
        getServices().addUser(DpmMockContext.CALLER_USER_HANDLE, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED, UserHandle.USER_SYSTEM);
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getProfileOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.profile_owner_migrated),
                getProfileOwnerFile());
        assertProfileOwnershipRevertedWithFakeTransferMetadata();
    }

    @FlakyTest(bugId = 148934649)
    @Test
    public void testRevertProfileOwnership_profileNotMigrated() throws Exception {
        getServices().addUser(DpmMockContext.CALLER_USER_HANDLE, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED, UserHandle.USER_SYSTEM);
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_migrated),
                getProfileOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.profile_owner_not_migrated),
                getProfileOwnerFile());
        assertProfileOwnershipRevertedWithFakeTransferMetadata();
    }

    @Test
    public void testRevertProfileOwnership_adminAndProfileNotMigrated() throws Exception {
        getServices().addUser(CALLER_USER_HANDLE, 0,
                UserManager.USER_TYPE_PROFILE_MANAGED, UserHandle.USER_SYSTEM);
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.active_admin_not_migrated),
                getProfileOwnerPoliciesFile());
        DpmTestUtils.writeInputStreamToFile(
                getRawStream(com.android.frameworks.servicestests.R.raw.profile_owner_not_migrated),
                getProfileOwnerFile());
        assertProfileOwnershipRevertedWithFakeTransferMetadata();
    }

    @Test
    public void testGrantDeviceIdsAccess_notToProfileOwner() throws Exception {
        setupProfileOwner();
        configureContextForAccess(mContext, false);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.markProfileOwnerOnOrganizationOwnedDevice(admin2));
    }

    @Test
    public void testGrantDeviceIdsAccess_notByAuthorizedCaller() throws Exception {
        setupProfileOwner();
        configureContextForAccess(mContext, false);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.markProfileOwnerOnOrganizationOwnedDevice(admin1));
    }

    @Test
    public void testGrantDeviceIdsAccess_byAuthorizedSystemCaller() throws Exception {
        setupProfileOwner();

        // This method will throw if the system context could not call
        // markProfileOwnerOfOrganizationOwnedDevice successfully.
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);
    }

    private void configureContextForAccess(DpmMockContext context, boolean granted) {
        when(context.spiedContext.checkCallingPermission(
                permission.MARK_DEVICE_ORGANIZATION_OWNED))
                .thenReturn(granted ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED);

        when(getServices().userManager.getProfileParent(any()))
                .thenReturn(UserHandle.SYSTEM);
    }

    @Test
    public void testGrantDeviceIdsAccess_byAuthorizedManagedProvisioning() throws Exception {
        setupProfileOwner();

        final long ident = mServiceContext.binder.clearCallingIdentity();
        configureContextForAccess(mServiceContext, true);
        mServiceContext.permissions.add(permission.MARK_DEVICE_ORGANIZATION_OWNED);

        mServiceContext.binder.callingUid =
                UserHandle.getUid(CALLER_USER_HANDLE,
                        DpmMockContext.CALLER_MANAGED_PROVISIONING_UID);
        try {
            runAsCaller(mServiceContext, dpms, dpm -> {
                dpm.markProfileOwnerOnOrganizationOwnedDevice(admin1);
            });
        } finally {
            mServiceContext.binder.restoreCallingIdentity(ident);
        }
    }

    @Test
    public void testEnforceCallerCanRequestDeviceIdAttestation_deviceOwnerCaller()
            throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        configureContextForAccess(mContext, false);

        // Device owner should be allowed to request Device ID attestation.
        dpms.enforceCallerCanRequestDeviceIdAttestation(dpms.getCallerIdentity(admin1));

        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        // Another package must not be allowed to request Device ID attestation.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                        dpms.getCallerIdentity(null, admin2.getPackageName())));

        // Another component that is not the admin must not be allowed to request Device ID
        // attestation.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                        dpms.getCallerIdentity(admin2)));
    }

    @Test
    public void testEnforceCallerCanRequestDeviceIdAttestation_profileOwnerCaller()
            throws Exception {
        configureContextForAccess(mContext, false);

        // Make sure a security exception is thrown if the device has no profile owner.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                        dpms.getCallerIdentity(admin1)));

        setupProfileOwner();
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        // The profile owner is allowed to request Device ID attestation.
        mServiceContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpms.enforceCallerCanRequestDeviceIdAttestation(dpms.getCallerIdentity(admin1));

        // But not another package.
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                        dpms.getCallerIdentity(null, admin2.getPackageName())));

        // Or another component which is not the admin.
        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                        dpms.getCallerIdentity(admin2, admin2.getPackageName())));
    }

    public void runAsDelegatedCertInstaller(DpmRunnable action) throws Exception {
        final long ident = mServiceContext.binder.clearCallingIdentity();

        mServiceContext.binder.callingUid = UserHandle.getUid(CALLER_USER_HANDLE,
                DpmMockContext.DELEGATE_CERT_INSTALLER_UID);
        try {
            runAsCaller(mServiceContext, dpms, action);
        } finally {
            mServiceContext.binder.restoreCallingIdentity(ident);
        }
    }

    @Test
    public void testEnforceCallerCanRequestDeviceIdAttestation_delegateCaller() throws Exception {
        setupProfileOwner();
        markDelegatedCertInstallerAsInstalled();

        // Configure a delegated cert installer.
        runAsCaller(mServiceContext, dpms,
                dpm -> dpm.setDelegatedScopes(admin1, DpmMockContext.DELEGATE_PACKAGE_NAME,
                        Arrays.asList(DELEGATION_CERT_INSTALL)));

        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        // Make sure that the profile owner can still request Device ID attestation.
        mServiceContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpms.enforceCallerCanRequestDeviceIdAttestation(dpms.getCallerIdentity(admin1));

        runAsDelegatedCertInstaller(dpm -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                dpms.getCallerIdentity(null, DpmMockContext.DELEGATE_PACKAGE_NAME)));
    }

    @Test
    public void testEnforceCallerCanRequestDeviceIdAttestation_delegateCallerWithoutPermissions()
            throws Exception {
        setupProfileOwner();
        markDelegatedCertInstallerAsInstalled();

        // Configure a delegated cert installer.
        runAsCaller(mServiceContext, dpms,
                dpm -> dpm.setDelegatedScopes(admin1, DpmMockContext.DELEGATE_PACKAGE_NAME,
                        Arrays.asList(DELEGATION_CERT_INSTALL)));

        assertExpectException(SecurityException.class, null,
                () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                        dpms.getCallerIdentity(admin1)));

        runAsDelegatedCertInstaller(dpm -> {
            assertExpectException(SecurityException.class, /* messageRegex= */ null,
                    () -> dpms.enforceCallerCanRequestDeviceIdAttestation(
                            dpms.getCallerIdentity(null, DpmMockContext.DELEGATE_PACKAGE_NAME)));
        });
    }

    @Test
    public void testGetPasswordComplexity_securityExceptionNotThrownForParentInstance() {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.getPackagesForUid(DpmMockContext.CALLER_UID)).thenReturn(
                new String[0]);
        mServiceContext.permissions.add(permission.REQUEST_PASSWORD_COMPLEXITY);
        setAsProfileOwner(admin1);

        parentDpm.getPasswordComplexity();

        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_NONE);
    }

    @Test
    public void testGetPasswordComplexity_illegalStateExceptionIfLocked() {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.getPackagesForUid(DpmMockContext.CALLER_UID)).thenReturn(
                new String[0]);
        when(getServices().userManager.isUserUnlocked(CALLER_USER_HANDLE)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> dpm.getPasswordComplexity());
    }

    @Test
    public void testGetPasswordComplexity_securityExceptionWithoutPermissions() {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.getPackagesForUid(DpmMockContext.CALLER_UID)).thenReturn(
                new String[0]);
        when(getServices().userManager.isUserUnlocked(CALLER_USER_HANDLE)).thenReturn(true);
        assertThrows(SecurityException.class, () -> dpm.getPasswordComplexity());
    }


    @Test
    public void testGetPasswordComplexity_currentUserNoPassword() {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.getPackagesForUid(DpmMockContext.CALLER_UID)).thenReturn(
                new String[0]);
        when(getServices().userManager.isUserUnlocked(CALLER_USER_HANDLE)).thenReturn(true);
        mServiceContext.permissions.add(permission.REQUEST_PASSWORD_COMPLEXITY);
        when(getServices().userManager.getCredentialOwnerProfile(CALLER_USER_HANDLE))
                .thenReturn(CALLER_USER_HANDLE);

        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_NONE);
    }

    @Test
    public void testGetPasswordComplexity_currentUserHasPassword() {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.getPackagesForUid(DpmMockContext.CALLER_UID)).thenReturn(
                new String[0]);
        when(getServices().userManager.isUserUnlocked(CALLER_USER_HANDLE)).thenReturn(true);
        mServiceContext.permissions.add(permission.REQUEST_PASSWORD_COMPLEXITY);
        when(getServices().userManager.getCredentialOwnerProfile(CALLER_USER_HANDLE))
                .thenReturn(CALLER_USER_HANDLE);
        when(getServices().lockSettingsInternal
                .getUserPasswordMetrics(CALLER_USER_HANDLE))
                .thenReturn(computeForPasswordOrPin("asdf".getBytes(), /* isPin */ false));

        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_MEDIUM);
    }

    @Test
    public void testGetPasswordComplexity_unifiedChallengeReturnsParentUserPassword() {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        when(getServices().packageManager.getPackagesForUid(DpmMockContext.CALLER_UID)).thenReturn(
                new String[0]);
        when(getServices().userManager.isUserUnlocked(CALLER_USER_HANDLE)).thenReturn(true);
        mServiceContext.permissions.add(permission.REQUEST_PASSWORD_COMPLEXITY);

        UserInfo parentUser = new UserInfo();
        parentUser.id = CALLER_USER_HANDLE + 10;
        when(getServices().userManager.getCredentialOwnerProfile(CALLER_USER_HANDLE))
                .thenReturn(parentUser.id);

        when(getServices().lockSettingsInternal
                .getUserPasswordMetrics(CALLER_USER_HANDLE))
                .thenReturn(computeForPasswordOrPin("asdf".getBytes(), /* isPin */ false));
        when(getServices().lockSettingsInternal
                .getUserPasswordMetrics(parentUser.id))
                .thenReturn(computeForPasswordOrPin("parentUser".getBytes(), /* isPin */ false));

        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_HIGH);
    }

    @Test
    public void testCrossProfileCalendarPackages_initiallyEmpty() {
        setAsProfileOwner(admin1);
        final Set<String> packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertCrossProfileCalendarPackagesEqual(packages, Collections.emptySet());
    }

    @Test
    public void testCrossProfileCalendarPackages_reopenDpms() {
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, null);
        Set<String> packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertThat(packages == null).isTrue();
        initializeDpms();
        packages = dpm.getCrossProfileCalendarPackages(admin1);
        assertThat(packages == null).isTrue();

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
        assertThat(expected).isNotNull();
        assertThat(actual).isNotNull();
        assertThat(actual).containsExactlyElementsIn(expected);
    }

    @Test
    public void testIsPackageAllowedToAccessCalendar_adminNotAllowed() {
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, Collections.emptySet());
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, CALLER_USER_HANDLE)).thenReturn(1);
        mContext.permissions.add(permission.INTERACT_ACROSS_USERS);

        assertThat(dpm.isPackageAllowedToAccessCalendar("TEST_PACKAGE")).isFalse();
    }

    @Test
    public void testIsPackageAllowedToAccessCalendar_settingOff() {
        final String testPackage = "TEST_PACKAGE";
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, Collections.singleton(testPackage));
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, CALLER_USER_HANDLE)).thenReturn(0);
        mContext.permissions.add(permission.INTERACT_ACROSS_USERS);

        assertThat(dpm.isPackageAllowedToAccessCalendar(testPackage)).isFalse();
    }

    @Test
    public void testIsPackageAllowedToAccessCalendar_bothAllowed() {
        final String testPackage = "TEST_PACKAGE";
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, null);
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, CALLER_USER_HANDLE)).thenReturn(1);
        mContext.permissions.add(permission.INTERACT_ACROSS_USERS);

        assertThat(dpm.isPackageAllowedToAccessCalendar(testPackage)).isTrue();
    }

    @Test
    public void testIsPackageAllowedToAccessCalendar_requiresPermission() {
        final String testPackage = "TEST_PACKAGE";

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.isPackageAllowedToAccessCalendar(testPackage));
    }

    @Test
    public void testIsPackageAllowedToAccessCalendar_samePackageAndSameUser_noPermissionRequired()
            throws Exception {
        final String testPackage = "TEST_PACKAGE";
        setAsProfileOwner(admin1);
        dpm.setCrossProfileCalendarPackages(admin1, null);
        when(getServices().settings.settingsSecureGetIntForUser(
                Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                0, CALLER_USER_HANDLE)).thenReturn(1);
        doReturn(mContext.binder.callingUid)
                .when(getServices().packageManager).getPackageUidAsUser(
                eq(testPackage),
                anyInt());

        assertThat(dpm.isPackageAllowedToAccessCalendar(testPackage)).isTrue();
    }

    @Test
    public void testSetUserControlDisabledPackages_asDO() throws Exception {
        final List<String> testPackages = new ArrayList<>();
        testPackages.add("package_1");
        testPackages.add("package_2");
        mServiceContext.permissions.add(permission.MANAGE_DEVICE_ADMINS);
        setDeviceOwner();

        dpm.setUserControlDisabledPackages(admin1, testPackages);

        verify(getServices().packageManagerInternal)
                .setDeviceOwnerProtectedPackages(admin1.getPackageName(), testPackages);
        assertThat(dpm.getUserControlDisabledPackages(admin1)).isEqualTo(testPackages);
    }

    @Test
    public void testSetUserControlDisabledPackages_failingAsPO() {
        final List<String> testPackages = new ArrayList<>();
        testPackages.add("package_1");
        testPackages.add("package_2");
        mServiceContext.permissions.add(permission.MANAGE_DEVICE_ADMINS);
        setAsProfileOwner(admin1);

        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.setUserControlDisabledPackages(admin1, testPackages));
        assertExpectException(SecurityException.class, /* messageRegex= */ null,
                () -> dpm.getUserControlDisabledPackages(admin1));
    }

    private void configureProfileOwnerOfOrgOwnedDevice(ComponentName who, int userId) {
        final long ident = mServiceContext.binder.clearCallingIdentity();
        mServiceContext.binder.callingUid = UserHandle.getUid(userId, DpmMockContext.SYSTEM_UID);

        configureContextForAccess(mServiceContext, true);
        runAsCaller(mServiceContext, dpms, dpm -> {
            dpm.markProfileOwnerOnOrganizationOwnedDevice(who);
        });
        mServiceContext.binder.restoreCallingIdentity(ident);
    }

    @Test
    public void testGetCrossProfilePackages_notSet_returnsEmpty() {
        setAsProfileOwner(admin1);
        assertThat(dpm.getCrossProfilePackages(admin1).isEmpty()).isTrue();
    }

    @Test
    public void testGetCrossProfilePackages_notSet_dpmsReinitialized_returnsEmpty() {
        setAsProfileOwner(admin1);

        initializeDpms();

        assertThat(dpm.getCrossProfilePackages(admin1).isEmpty()).isTrue();
    }

    @Test
    public void testGetCrossProfilePackages_whenSet_returnsEqual() {
        setAsProfileOwner(admin1);
        Set<String> packages = Collections.singleton("TEST_PACKAGE");

        dpm.setCrossProfilePackages(admin1, packages);

        assertThat(dpm.getCrossProfilePackages(admin1)).isEqualTo(packages);
    }

    @Test
    public void testGetCrossProfilePackages_whenSet_dpmsReinitialized_returnsEqual() {
        setAsProfileOwner(admin1);
        Set<String> packages = Collections.singleton("TEST_PACKAGE");

        dpm.setCrossProfilePackages(admin1, packages);
        initializeDpms();

        assertThat(dpm.getCrossProfilePackages(admin1)).isEqualTo(packages);
    }

    @Test
    public void testGetAllCrossProfilePackages_notSet_returnsEmpty() throws Exception {
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);
        addManagedProfile(admin1, mServiceContext.binder.callingUid, admin1);
        mContext.packageName = admin1.getPackageName();

        setCrossProfileAppsList();
        setVendorCrossProfileAppsList();

        assertThat(dpm.getAllCrossProfilePackages().isEmpty()).isTrue();
    }

    @Test
    public void testGetAllCrossProfilePackages_notSet_dpmsReinitialized_returnsEmpty()
            throws Exception {
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);
        addManagedProfile(admin1, mServiceContext.binder.callingUid, admin1);
        mContext.packageName = admin1.getPackageName();

        setCrossProfileAppsList();
        setVendorCrossProfileAppsList();
        initializeDpms();

        assertThat(dpm.getAllCrossProfilePackages().isEmpty()).isTrue();
    }

    @Test
    public void testGetAllCrossProfilePackages_whenSet_returnsCombinedSet() throws Exception {
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);
        addManagedProfile(admin1, mServiceContext.binder.callingUid, admin1);
        final Set<String> packages = Sets.newSet("TEST_PACKAGE", "TEST_COMMON_PACKAGE");
        mContext.packageName = admin1.getPackageName();

        dpm.setCrossProfilePackages(admin1, packages);
        setCrossProfileAppsList("TEST_DEFAULT_PACKAGE", "TEST_COMMON_PACKAGE");
        setVendorCrossProfileAppsList("TEST_VENDOR_DEFAULT_PACKAGE");

        assertThat(dpm.getAllCrossProfilePackages()).containsExactly(
                "TEST_PACKAGE", "TEST_DEFAULT_PACKAGE", "TEST_COMMON_PACKAGE",
                "TEST_VENDOR_DEFAULT_PACKAGE");
    }

    @Test
    public void testGetAllCrossProfilePackages_whenSet_dpmsReinitialized_returnsCombinedSet()
            throws Exception {
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);
        addManagedProfile(admin1, mServiceContext.binder.callingUid, admin1);
        final Set<String> packages = Sets.newSet("TEST_PACKAGE", "TEST_COMMON_PACKAGE");
        mContext.packageName = admin1.getPackageName();

        dpm.setCrossProfilePackages(admin1, packages);
        setCrossProfileAppsList("TEST_DEFAULT_PACKAGE", "TEST_COMMON_PACKAGE");
        setVendorCrossProfileAppsList("TEST_VENDOR_DEFAULT_PACKAGE");
        initializeDpms();

        assertThat(dpm.getAllCrossProfilePackages()).containsExactly(
                "TEST_PACKAGE", "TEST_DEFAULT_PACKAGE", "TEST_COMMON_PACKAGE",
                "TEST_VENDOR_DEFAULT_PACKAGE");
    }

    @Test
    public void testGetDefaultCrossProfilePackages_noPackagesSet_returnsEmpty() {
        setCrossProfileAppsList();
        setVendorCrossProfileAppsList();

        assertThat(dpm.getDefaultCrossProfilePackages()).isEmpty();
    }

    @Test
    public void testGetDefaultCrossProfilePackages_packagesSet_returnsCombinedSet() {
        setCrossProfileAppsList("TEST_DEFAULT_PACKAGE", "TEST_COMMON_PACKAGE");
        setVendorCrossProfileAppsList("TEST_VENDOR_DEFAULT_PACKAGE");

        assertThat(dpm.getDefaultCrossProfilePackages()).containsExactly(
                "TEST_DEFAULT_PACKAGE", "TEST_COMMON_PACKAGE", "TEST_VENDOR_DEFAULT_PACKAGE");
    }

    @Test
    public void testSetCommonCriteriaMode_asDeviceOwner() throws Exception {
        setDeviceOwner();

        assertThat(dpm.isCommonCriteriaModeEnabled(admin1)).isFalse();
        assertThat(dpm.isCommonCriteriaModeEnabled(null)).isFalse();

        dpm.setCommonCriteriaModeEnabled(admin1, true);

        assertThat(dpm.isCommonCriteriaModeEnabled(admin1)).isTrue();
        assertThat(dpm.isCommonCriteriaModeEnabled(null)).isTrue();
    }

    @Test
    public void testSetCommonCriteriaMode_asPoOfOrgOwnedDevice() throws Exception {
        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);
        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, managedProfileUserId);
        mContext.binder.callingUid = managedProfileAdminUid;

        assertThat(dpm.isCommonCriteriaModeEnabled(admin1)).isFalse();
        assertThat(dpm.isCommonCriteriaModeEnabled(null)).isFalse();

        dpm.setCommonCriteriaModeEnabled(admin1, true);

        assertThat(dpm.isCommonCriteriaModeEnabled(admin1)).isTrue();
        assertThat(dpm.isCommonCriteriaModeEnabled(null)).isTrue();
    }

    @Test
    public void testCanProfileOwnerResetPasswordWhenLocked_nonDirectBootAwarePo()
            throws Exception {
        setDeviceEncryptionPerUser();
        setupProfileOwner();
        setupPasswordResetToken();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertWithMessage("po is not direct boot aware")
                .that(dpm.canProfileOwnerResetPasswordWhenLocked(CALLER_USER_HANDLE)).isFalse();
    }

    @Test
    public void testCanProfileOwnerResetPasswordWhenLocked_noActiveToken() throws Exception {
        setDeviceEncryptionPerUser();
        setupProfileOwner();
        makeAdmin1DirectBootAware();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertWithMessage("po doesn't have an active password reset token")
                .that(dpm.canProfileOwnerResetPasswordWhenLocked(CALLER_USER_HANDLE)).isFalse();
    }

    @Test
    public void testCanProfileOwnerResetPasswordWhenLocked_nonFbeDevice() throws Exception {
        setupProfileOwner();
        makeAdmin1DirectBootAware();
        setupPasswordResetToken();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertWithMessage("device is not FBE")
                .that(dpm.canProfileOwnerResetPasswordWhenLocked(CALLER_USER_HANDLE)).isFalse();
    }

    @Test
    public void testCanProfileOwnerResetPasswordWhenLocked() throws Exception {
        setDeviceEncryptionPerUser();
        setupProfileOwner();
        makeAdmin1DirectBootAware();
        setupPasswordResetToken();

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertWithMessage("direct boot aware po with active password reset token")
                .that(dpm.canProfileOwnerResetPasswordWhenLocked(CALLER_USER_HANDLE)).isTrue();
    }

    private void setupPasswordResetToken() {
        final byte[] token = new byte[32];
        final long handle = 123456;

        when(getServices().lockPatternUtils
                .addEscrowToken(eq(token), eq(CALLER_USER_HANDLE),
                        nullable(EscrowTokenStateChangeCallback.class)))
                .thenReturn(handle);

        dpm.setResetPasswordToken(admin1, token);

        when(getServices().lockPatternUtils
                .isEscrowTokenActive(eq(handle), eq(CALLER_USER_HANDLE)))
                .thenReturn(true);

        assertWithMessage("failed to activate token").that(dpm.isResetPasswordTokenActive(admin1))
                .isTrue();
    }

    private void makeAdmin1DirectBootAware()
            throws PackageManager.NameNotFoundException, android.os.RemoteException {
        Mockito.reset(getServices().ipackageManager);

        final ApplicationInfo ai = DpmTestUtils.cloneParcelable(
                mRealTestContext.getPackageManager().getApplicationInfo(
                        admin1.getPackageName(),
                        PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS));
        ai.privateFlags = PRIVATE_FLAG_DIRECT_BOOT_AWARE;

        doReturn(ai).when(getServices().ipackageManager).getApplicationInfo(
                eq(admin1.getPackageName()),
                anyInt(),
                eq(CALLER_USER_HANDLE));
    }

    private void setDeviceEncryptionPerUser() {
        when(getServices().storageManager.isFileBasedEncryptionEnabled()).thenReturn(true);
    }

    private void setCrossProfileAppsList(String... packages) {
        when(mContext.getResources()
                .getStringArray(eq(R.array.cross_profile_apps)))
                .thenReturn(packages);
    }

    private void setVendorCrossProfileAppsList(String... packages) {
        when(mContext.getResources()
                .getStringArray(eq(R.array.vendor_cross_profile_apps)))
                .thenReturn(packages);
    }

    @Test
    public void testSetAccountTypesWithManagementDisabledOnManagedProfile() throws Exception {
        setupProfileOwner();

        final String accountType = "com.example.account.type";
        int originalUid = mContext.binder.callingUid;
        dpm.setAccountManagementDisabled(admin1, accountType, true);
        assertThat(dpm.getAccountTypesWithManagementDisabled()).asList().containsExactly(
                accountType);
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertThat(dpm.getAccountTypesWithManagementDisabled()).isEmpty();
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpm.getAccountTypesWithManagementDisabled()).isEmpty();

        mContext.binder.callingUid = originalUid;
        dpm.setAccountManagementDisabled(admin1, accountType, false);
        assertThat(dpm.getAccountTypesWithManagementDisabled()).isEmpty();
    }

    @Test
    public void testSetAccountTypesWithManagementDisabledOnOrgOwnedManagedProfile()
            throws Exception {
        mContext.callerPermissions.add(permission.INTERACT_ACROSS_USERS);

        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);

        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        mContext.binder.callingUid = managedProfileAdminUid;

        configureProfileOwnerOfOrgOwnedDevice(admin1, managedProfileUserId);

        int originalUid = mContext.binder.callingUid;
        final String accountType = "com.example.account.type";
        dpm.getParentProfileInstance(admin1).setAccountManagementDisabled(admin1, accountType,
                true);
        assertThat(dpm.getAccountTypesWithManagementDisabled()).isEmpty();
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertThat(dpm.getAccountTypesWithManagementDisabled()).asList().containsExactly(
                accountType);
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpm.getAccountTypesWithManagementDisabled()).asList().containsExactly(
                accountType);

        mContext.binder.callingUid = originalUid;
        dpm.getParentProfileInstance(admin1).setAccountManagementDisabled(admin1, accountType,
                false);
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertThat(dpm.getAccountTypesWithManagementDisabled()).isEmpty();
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        assertThat(dpm.getAccountTypesWithManagementDisabled()).isEmpty();
    }

    /**
     * Tests the case when the user doesn't turn the profile on in time, verifies that the user is
     * warned with a notification and then the apps get suspended.
     */
    @Test
    public void testMaximumProfileTimeOff_profileOffTimeExceeded() throws Exception {
        prepareMocksForSetMaximumProfileTimeOff();

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpm.setManagedProfileMaximumTimeOff(admin1, PROFILE_OFF_TIMEOUT);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        // The profile is running, neither alarm nor notification should be posted.
        verify(getServices().alarmManager, never())
                .set(anyInt(), anyLong(), any(PendingIntent.class));
        verify(getServices().notificationManager, never())
                .notify(anyInt(), any(Notification.class));
        // Apps shouldn't be suspended.
        verifyZeroInteractions(getServices().ipackageManager);
        clearInvocations(getServices().alarmManager);

        setUserUnlocked(CALLER_USER_HANDLE, false);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_STOPPED, CALLER_USER_HANDLE);

        // Verify the alarm was scheduled for time when the warning should be shown.
        verify(getServices().alarmManager, times(1))
                .set(anyInt(), eq(PROFILE_OFF_WARNING_TIME), any());
        // But still no notification should be posted at this point.
        verify(getServices().notificationManager, never())
                .notify(anyInt(), any(Notification.class));
        // Apps shouldn't be suspended.
        verifyZeroInteractions(getServices().ipackageManager);
        clearInvocations(getServices().alarmManager);

        // Pretend the alarm went off.
        dpms.mMockInjector.setSystemCurrentTimeMillis(PROFILE_OFF_WARNING_TIME + 10);
        sendBroadcastWithUser(dpms, ACTION_PROFILE_OFF_DEADLINE, CALLER_USER_HANDLE);

        // Verify the alarm was scheduled for the actual deadline this time.
        verify(getServices().alarmManager, times(1)).set(anyInt(), eq(PROFILE_OFF_DEADLINE), any());
        // Now the user should see a warning notification.
        verify(getServices().notificationManager, times(1))
                .notify(anyInt(), argThat(hasExtra(EXTRA_TITLE, PROFILE_OFF_SUSPENSION_TITLE,
                        EXTRA_TEXT, PROFILE_OFF_SUSPENSION_SOON_TEXT)));
        // Apps shouldn't be suspended yet.
        verifyZeroInteractions(getServices().ipackageManager);
        clearInvocations(getServices().alarmManager);
        clearInvocations(getServices().notificationManager);

        // Pretend the alarm went off.
        dpms.mMockInjector.setSystemCurrentTimeMillis(PROFILE_OFF_DEADLINE + 10);
        sendBroadcastWithUser(dpms, ACTION_PROFILE_OFF_DEADLINE, CALLER_USER_HANDLE);

        // Verify the alarm was not set.
        verifyZeroInteractions(getServices().alarmManager);
        // Now the user should see a notification about suspended apps.
        verify(getServices().notificationManager, times(1))
                .notify(anyInt(), argThat(hasExtra(EXTRA_TITLE, PROFILE_OFF_SUSPENSION_TITLE,
                        EXTRA_TEXT, PROFILE_OFF_SUSPENSION_TEXT)));
        // Verify that the apps are suspended.
        verify(getServices().ipackageManager, times(1)).setPackagesSuspendedAsUser(
                any(), eq(true), any(), any(), any(), any(), anyInt());
    }

    /**
     * Tests the case when the user turns the profile back on long before the deadline (> 1 day).
     */
    @Test
    public void testMaximumProfileTimeOff_turnOnBeforeWarning() throws Exception {
        prepareMocksForSetMaximumProfileTimeOff();

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpm.setManagedProfileMaximumTimeOff(admin1, PROFILE_OFF_TIMEOUT);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        setUserUnlocked(CALLER_USER_HANDLE, false);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_STOPPED, CALLER_USER_HANDLE);
        clearInvocations(getServices().alarmManager);
        setUserUnlocked(CALLER_USER_HANDLE, true);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_UNLOCKED, CALLER_USER_HANDLE);

        // Verify that the alarm got discharged.
        verify(getServices().alarmManager, times(1)).cancel((PendingIntent) null);
    }

    /**
     * Tests the case when the user turns the profile back on after the warning notification.
     */
    @Test
    public void testMaximumProfileTimeOff_turnOnAfterWarning() throws Exception {
        prepareMocksForSetMaximumProfileTimeOff();

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpm.setManagedProfileMaximumTimeOff(admin1, PROFILE_OFF_TIMEOUT);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        setUserUnlocked(CALLER_USER_HANDLE, false);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_STOPPED, CALLER_USER_HANDLE);

        // Pretend the alarm went off.
        dpms.mMockInjector.setSystemCurrentTimeMillis(PROFILE_OFF_WARNING_TIME + 10);
        sendBroadcastWithUser(dpms, ACTION_PROFILE_OFF_DEADLINE, CALLER_USER_HANDLE);

        clearInvocations(getServices().alarmManager);
        clearInvocations(getServices().notificationManager);
        setUserUnlocked(CALLER_USER_HANDLE, true);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_UNLOCKED, CALLER_USER_HANDLE);

        // Verify that the alarm got discharged.
        verify(getServices().alarmManager, times(1)).cancel((PendingIntent) null);
        // Verify that the notification is removed.
        verify(getServices().notificationManager, times(1))
                .cancel(eq(SystemMessageProto.SystemMessage.NOTE_PERSONAL_APPS_SUSPENDED));
    }

    /**
     * Tests the case when the user turns the profile back on when the apps are already suspended.
     */
    @Test
    public void testMaximumProfileTimeOff_turnOnAfterDeadline() throws Exception {
        prepareMocksForSetMaximumProfileTimeOff();

        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        dpm.setManagedProfileMaximumTimeOff(admin1, PROFILE_OFF_TIMEOUT);

        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;
        setUserUnlocked(CALLER_USER_HANDLE, false);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_STOPPED, CALLER_USER_HANDLE);

        // Pretend the alarm went off after the deadline.
        dpms.mMockInjector.setSystemCurrentTimeMillis(PROFILE_OFF_DEADLINE + 10);
        sendBroadcastWithUser(dpms, ACTION_PROFILE_OFF_DEADLINE, CALLER_USER_HANDLE);

        clearInvocations(getServices().alarmManager);
        clearInvocations(getServices().notificationManager);
        clearInvocations(getServices().ipackageManager);

        // Pretend the user clicked on the "apps suspended" notification to turn the profile on.
        sendBroadcastWithUser(dpms, ACTION_TURN_PROFILE_ON_NOTIFICATION, CALLER_USER_HANDLE);
        // Verify that the profile is turned on.
        verify(getServices().userManager, times(1))
                .requestQuietModeEnabled(eq(false), eq(UserHandle.of(CALLER_USER_HANDLE)));

        setUserUnlocked(CALLER_USER_HANDLE, true);
        sendBroadcastWithUser(dpms, Intent.ACTION_USER_UNLOCKED, CALLER_USER_HANDLE);

        // Verify that the notification is removed (at this point DPC should show it).
        verify(getServices().notificationManager, times(1))
                .cancel(eq(SystemMessageProto.SystemMessage.NOTE_PERSONAL_APPS_SUSPENDED));
        // Verify that the apps are NOT unsuspeded.
        verify(getServices().ipackageManager, never()).setPackagesSuspendedAsUser(
                any(), eq(false), any(), any(), any(), any(), anyInt());
        // Verify that DPC is invoked to check policy compliance.
        verify(mContext.spiedContext).startActivityAsUser(
                MockUtils.checkIntentAction(ACTION_CHECK_POLICY_COMPLIANCE),
                MockUtils.checkUserHandle(CALLER_USER_HANDLE));

        // Verify that correct suspension reason is reported to the DPC.
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        assertThat(dpm.getPersonalAppsSuspendedReasons(admin1))
                .isEqualTo(DevicePolicyManager.PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT);

        // Verify that rolling time back doesn't change the status.
        dpms.mMockInjector.setSystemCurrentTimeMillis(PROFILE_OFF_START);
        assertThat(dpm.getPersonalAppsSuspendedReasons(admin1))
                .isEqualTo(DevicePolicyManager.PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT);
    }

    @Test
    public void testSetRequiredPasswordComplexity_UnauthorizedCallersOnDO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        setupDeviceOwner();
        // DO must be able to set it.
        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW);
        // But not on the parent DPM.
        assertExpectException(IllegalArgumentException.class, null,
                () -> parentDpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW));
        // Another package must not be allowed to set password complexity.
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertExpectException(SecurityException.class, null,
                () -> dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW));
    }

    @Test
    public void testSetRequiredPasswordComplexity_UnauthorizedCallersOnPO() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setupProfileOwner();
        // PO must be able to set it.
        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW);
        // And on the parent profile DPM instance.
        parentDpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW);
        // Another package must not be allowed to set password complexity.
        mContext.binder.callingUid = DpmMockContext.ANOTHER_UID;
        assertExpectException(SecurityException.class, null,
                () -> dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_LOW));
    }

    @Test
    public void testSetRequiredPasswordComplexity_validValuesOnly() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setupProfileOwner();

        // Cannot set value other than password_complexity none/low/medium/high
        assertExpectException(IllegalArgumentException.class, null, () ->
                dpm.setRequiredPasswordComplexity(-1));
        assertExpectException(IllegalArgumentException.class, null, () ->
                dpm.setRequiredPasswordComplexity(7));
        assertExpectException(IllegalArgumentException.class, null, () ->
                dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH + 1));

        final Set<Integer> allowedModes = Set.of(PASSWORD_COMPLEXITY_NONE, PASSWORD_COMPLEXITY_LOW,
                PASSWORD_COMPLEXITY_MEDIUM, PASSWORD_COMPLEXITY_HIGH);
        for (int complexity : allowedModes) {
            // Ensure exception is not thrown.
            dpm.setRequiredPasswordComplexity(complexity);
        }
    }

    @Test
    public void testSetRequiredPasswordComplexity_setAndGet() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setupProfileOwner();

        final Set<Integer> allowedModes = Set.of(PASSWORD_COMPLEXITY_NONE, PASSWORD_COMPLEXITY_LOW,
                PASSWORD_COMPLEXITY_MEDIUM, PASSWORD_COMPLEXITY_HIGH);
        for (int complexity : allowedModes) {
            dpm.setRequiredPasswordComplexity(complexity);
            assertThat(dpm.getRequiredPasswordComplexity()).isEqualTo(complexity);
        }
    }

    @Test
    public void testSetRequiredPasswordComplexityOnParent_setAndGet() throws Exception {
        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);

        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        mContext.binder.callingUid = managedProfileAdminUid;

        final Set<Integer> allowedModes = Set.of(PASSWORD_COMPLEXITY_NONE, PASSWORD_COMPLEXITY_LOW,
                PASSWORD_COMPLEXITY_MEDIUM, PASSWORD_COMPLEXITY_HIGH);
        for (int complexity : allowedModes) {
            dpm.getParentProfileInstance(admin1).setRequiredPasswordComplexity(complexity);
            assertThat(dpm.getParentProfileInstance(admin1).getRequiredPasswordComplexity())
                    .isEqualTo(complexity);
            assertThat(dpm.getRequiredPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_NONE);
        }
    }

    @Test
    public void testSetRequiredPasswordComplexity_isSufficient() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_SYSTEM_USER_UID;
        mContext.packageName = admin1.getPackageName();
        setupDeviceOwner();

        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH);
        assertThat(dpm.getRequiredPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_HIGH);
        when(getServices().packageManager.getPackagesForUid(
                DpmMockContext.CALLER_SYSTEM_USER_UID)).thenReturn(new String[0]);
        mServiceContext.permissions.add(permission.REQUEST_PASSWORD_COMPLEXITY);
        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_NONE);

        reset(mContext.spiedContext);
        PasswordMetrics passwordMetricsNoSymbols = computeForPasswordOrPin(
                "1234".getBytes(), /* isPin */ true);
        setActivePasswordState(passwordMetricsNoSymbols);
        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_LOW);
        assertThat(dpm.isActivePasswordSufficient()).isFalse();

        reset(mContext.spiedContext);
        passwordMetricsNoSymbols = computeForPasswordOrPin(
                "84125312943a".getBytes(), /* isPin */ false);
        setActivePasswordState(passwordMetricsNoSymbols);
        assertThat(dpm.getPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_HIGH);
        // using isActivePasswordSufficient
        assertThat(dpm.isActivePasswordSufficient()).isTrue();
    }

    @Test
    public void testSetRequiredPasswordComplexity_resetBySettingQuality() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setupProfileOwner();

        // Test that calling setPasswordQuality resets complexity to none.
        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH);
        assertThat(dpm.getRequiredPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_HIGH);
        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX);
        assertThat(dpm.getRequiredPasswordComplexity()).isEqualTo(PASSWORD_COMPLEXITY_NONE);
    }

    @Test
    public void testSetRequiredPasswordComplexity_overridesQuality() throws Exception {
        mContext.binder.callingUid = DpmMockContext.CALLER_UID;
        setupProfileOwner();

        // Test that calling setRequiredPasswordComplexity resets password quality.
        dpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX);
        assertThat(dpm.getPasswordQuality(admin1)).isEqualTo(
                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX);
        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH);
        assertThat(dpm.getPasswordQuality(admin1)).isEqualTo(
                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
    }

    @Test
    public void testSetRequiredPasswordComplexityFailsWithQualityOnParent() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);

        parentDpm.setPasswordQuality(admin1, DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);

        assertThrows(IllegalStateException.class,
                () -> dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH));
    }

    @Test
    public void testSetQualityOnParentFailsWithComplexityOnProfile() throws Exception {
        final int managedProfileUserId = CALLER_USER_HANDLE;
        final int managedProfileAdminUid =
                UserHandle.getUid(managedProfileUserId, DpmMockContext.SYSTEM_UID);
        mContext.binder.callingUid = managedProfileAdminUid;
        addManagedProfile(admin1, managedProfileAdminUid, admin1, VERSION_CODES.R);

        dpm.setRequiredPasswordComplexity(PASSWORD_COMPLEXITY_HIGH);

        assertThrows(IllegalStateException.class,
                () -> parentDpm.setPasswordQuality(admin1,
                        DevicePolicyManager.PASSWORD_QUALITY_COMPLEX));
    }

    @Test
    public void testSetDeviceOwnerType_throwsExceptionWhenCallerNotAuthorized() {
        assertThrows(SecurityException.class,
                () -> dpm.setDeviceOwnerType(admin1, DEVICE_OWNER_TYPE_DEFAULT));
    }

    @Test
    public void testSetDeviceOwnerType_throwsExceptionWhenThereIsNoDeviceOwner() {
        mContext.binder.clearCallingIdentity();
        assertThrows(IllegalStateException.class,
                () -> dpm.setDeviceOwnerType(admin1, DEVICE_OWNER_TYPE_DEFAULT));
    }

    @Test
    public void testSetDeviceOwnerType_throwsExceptionWhenNotAsDeviceOwnerAdmin() throws Exception {
        setDeviceOwner();

        assertThrows(IllegalStateException.class,
                () -> dpm.setDeviceOwnerType(admin2, DEVICE_OWNER_TYPE_FINANCED));
    }

    @Test
    public void testSetDeviceOwnerType_asDeviceOwner_toFinancedDevice() throws Exception {
        setDeviceOwner();

        dpm.setDeviceOwnerType(admin1, DEVICE_OWNER_TYPE_FINANCED);

        int returnedDeviceOwnerType = dpm.getDeviceOwnerType(admin1);
        assertThat(dpms.mOwners.hasDeviceOwner()).isTrue();
        assertThat(returnedDeviceOwnerType).isEqualTo(DEVICE_OWNER_TYPE_FINANCED);

        initializeDpms();

        returnedDeviceOwnerType = dpm.getDeviceOwnerType(admin1);
        assertThat(dpms.mOwners.hasDeviceOwner()).isTrue();
        assertThat(returnedDeviceOwnerType).isEqualTo(DEVICE_OWNER_TYPE_FINANCED);
    }

    @Test
    public void testSetDeviceOwnerType_asDeviceOwner_throwsExceptionWhenSetDeviceOwnerTypeAgain()
            throws Exception {
        setDeviceOwner();

        dpm.setDeviceOwnerType(admin1, DEVICE_OWNER_TYPE_FINANCED);

        int returnedDeviceOwnerType = dpm.getDeviceOwnerType(admin1);
        assertThat(dpms.mOwners.hasDeviceOwner()).isTrue();
        assertThat(returnedDeviceOwnerType).isEqualTo(DEVICE_OWNER_TYPE_FINANCED);

        assertThrows(IllegalStateException.class,
                () -> dpm.setDeviceOwnerType(admin1, DEVICE_OWNER_TYPE_DEFAULT));
    }

    @Test
    public void testGetDeviceOwnerType_throwsExceptionWhenThereIsNoDeviceOwner() {
        assertThrows(IllegalStateException.class, () -> dpm.getDeviceOwnerType(admin1));
    }

    @Test
    public void testGetDeviceOwnerType_throwsExceptionWhenNotAsDeviceOwnerAdmin() throws Exception {
        setDeviceOwner();

        assertThrows(IllegalStateException.class, () -> dpm.getDeviceOwnerType(admin2));
    }

    @Test
    public void testSetUsbDataSignalingEnabled_noDeviceOwnerOrPoOfOrgOwnedDevice() {
        assertThrows(SecurityException.class,
                () -> dpm.setUsbDataSignalingEnabled(true));
    }

    @Test
    public void testSetUsbDataSignalingEnabled_asDeviceOwner() throws Exception {
        setDeviceOwner();
        when(getServices().usbManager.enableUsbDataSignal(false)).thenReturn(true);
        when(getServices().usbManager.getUsbHalVersion()).thenReturn(UsbManager.USB_HAL_V1_3);

        assertThat(dpm.isUsbDataSignalingEnabled()).isTrue();

        dpm.setUsbDataSignalingEnabled(false);

        assertThat(dpm.isUsbDataSignalingEnabled()).isFalse();
    }

    @Test
    public void testIsUsbDataSignalingEnabledForUser_systemUser() throws Exception {
        when(getServices().usbManager.enableUsbDataSignal(false)).thenReturn(true);
        when(getServices().usbManager.getUsbHalVersion()).thenReturn(UsbManager.USB_HAL_V1_3);
        setDeviceOwner();
        dpm.setUsbDataSignalingEnabled(false);
        mContext.binder.callingUid = DpmMockContext.SYSTEM_UID;

        assertThat(dpm.isUsbDataSignalingEnabledForUser(UserHandle.myUserId())).isFalse();
    }

    @Test
    public void testSetUsbDataSignalingEnabled_asPoOfOrgOwnedDevice() throws Exception {
        final int managedProfileUserId = 15;
        final int managedProfileAdminUid = UserHandle.getUid(managedProfileUserId, 19436);
        addManagedProfile(admin1, managedProfileAdminUid, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, managedProfileUserId);
        mContext.binder.callingUid = managedProfileAdminUid;
        when(getServices().usbManager.enableUsbDataSignal(false)).thenReturn(true);
        when(getServices().usbManager.getUsbHalVersion()).thenReturn(UsbManager.USB_HAL_V1_3);

        assertThat(dpm.isUsbDataSignalingEnabled()).isTrue();

        dpm.setUsbDataSignalingEnabled(false);

        assertThat(dpm.isUsbDataSignalingEnabled()).isFalse();
    }

    @Test
    public void testCanUsbDataSignalingBeDisabled_canBeDisabled() throws Exception {
        when(getServices().usbManager.getUsbHalVersion()).thenReturn(UsbManager.USB_HAL_V1_3);

        assertThat(dpm.canUsbDataSignalingBeDisabled()).isTrue();
    }

    @Test
    public void testCanUsbDataSignalingBeDisabled_cannotBeDisabled() throws Exception {
        setDeviceOwner();
        when(getServices().usbManager.getUsbHalVersion()).thenReturn(UsbManager.USB_HAL_V1_2);

        assertThat(dpm.canUsbDataSignalingBeDisabled()).isFalse();
        assertThrows(IllegalStateException.class,
                () -> dpm.setUsbDataSignalingEnabled(true));
    }

    @Test
    public void testSetUsbDataSignalingEnabled_noChangeToActiveAdmin()
            throws Exception {
        setDeviceOwner();
        when(getServices().usbManager.getUsbHalVersion()).thenReturn(UsbManager.USB_HAL_V1_3);
        boolean enabled = dpm.isUsbDataSignalingEnabled();

        dpm.setUsbDataSignalingEnabled(true);

        assertThat(dpm.isUsbDataSignalingEnabled()).isEqualTo(enabled);
    }

    @Test
    public void testGetPolicyExemptApps_noPermission() {
        assertThrows(SecurityException.class, () -> dpm.getPolicyExemptApps());
    }

    @Test
    public void testGetPolicyExemptApps_empty() {
        grantManageDeviceAdmins();
        mockPolicyExemptApps();
        mockVendorPolicyExemptApps();

        assertThat(dpm.getPolicyExemptApps()).isEmpty();
    }

    @Test
    public void testGetPolicyExemptApps_baseOnly() {
        grantManageDeviceAdmins();
        mockPolicyExemptApps("foo");
        mockVendorPolicyExemptApps();

        assertThat(dpm.getPolicyExemptApps()).containsExactly("foo");
    }

    @Test
    public void testGetPolicyExemptApps_vendorOnly() {
        grantManageDeviceAdmins();
        mockPolicyExemptApps();
        mockVendorPolicyExemptApps("bar");

        assertThat(dpm.getPolicyExemptApps()).containsExactly("bar");
    }

    @Test
    public void testGetPolicyExemptApps_baseAndVendor() {
        grantManageDeviceAdmins();
        mockPolicyExemptApps("4", "23", "15", "42", "8");
        mockVendorPolicyExemptApps("16", "15", "4");

        assertThat(dpm.getPolicyExemptApps()).containsExactly("4", "8", "15", "16", "23", "42");
    }

    @Test
    public void testSetGlobalPrivateDnsModeOpportunistic_asDeviceOwner() throws Exception {
        setDeviceOwner();
        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(CALLER_USER_HANDLE);
        clearInvocations(getServices().settings);

        int result = dpm.setGlobalPrivateDnsModeOpportunistic(admin1);

        assertThat(result).isEqualTo(PRIVATE_DNS_SET_NO_ERROR);
    }

    @Test
    public void testSetGlobalPrivateDnsModeOpportunistic_hasUnaffiliatedUsers() throws Exception {
        setDeviceOwner();
        setAsProfileOwner(admin2);

        assertThrows(SecurityException.class,
                () -> dpm.setGlobalPrivateDnsModeOpportunistic(admin1));
    }

    @Test
    public void testSetRecommendedGlobalProxy_asDeviceOwner() throws Exception {
        setDeviceOwner();
        // setUp() adds a secondary user for CALLER_USER_HANDLE. Remove it as otherwise the
        // feature is disabled because there are non-affiliated secondary users.
        getServices().removeUser(CALLER_USER_HANDLE);

        dpm.setRecommendedGlobalProxy(admin1, null);

        verify(getServices().connectivityManager).setGlobalProxy(null);
    }

    @Test
    public void testSetRecommendedGlobalProxy_hasUnaffiliatedUsers() throws Exception {
        setDeviceOwner();
        setAsProfileOwner(admin2);

        assertThrows(SecurityException.class, () -> dpm.setRecommendedGlobalProxy(admin1, null));
    }

    private void setUserUnlocked(int userHandle, boolean unlocked) {
        when(getServices().userManager.isUserUnlocked(eq(userHandle))).thenReturn(unlocked);
    }

    private void prepareMocksForSetMaximumProfileTimeOff() throws Exception {
        addManagedProfile(admin1, DpmMockContext.CALLER_UID, admin1);
        configureProfileOwnerOfOrgOwnedDevice(admin1, CALLER_USER_HANDLE);

        when(getServices().userManager.isUserUnlocked()).thenReturn(true);

        doReturn(Collections.singletonList(new ResolveInfo()))
                .when(getServices().packageManager).queryIntentActivitiesAsUser(
                        any(Intent.class), anyInt(), eq(CALLER_USER_HANDLE));

        dpms.mMockInjector.setSystemCurrentTimeMillis(PROFILE_OFF_START);
        // To allow creation of Notification via Notification.Builder
        mContext.applicationInfo = mRealTestContext.getApplicationInfo();

        // Setup resources to render notification titles and texts.
        when(mServiceContext.resources
                .getString(R.string.personal_apps_suspension_title))
                .thenReturn(PROFILE_OFF_SUSPENSION_TITLE);
        when(mServiceContext.resources
                .getString(R.string.personal_apps_suspension_text))
                .thenReturn(PROFILE_OFF_SUSPENSION_TEXT);
        when(mServiceContext.resources
                .getString(eq(R.string.personal_apps_suspension_soon_text),
                        anyString(), anyString(), anyInt()))
                .thenReturn(PROFILE_OFF_SUSPENSION_SOON_TEXT);

        // Make locale available for date formatting:
        when(mServiceContext.resources.getConfiguration())
                .thenReturn(mRealTestContext.getResources().getConfiguration());

        clearInvocations(getServices().ipackageManager);
    }

    private static Matcher<Notification> hasExtra(String... extras) {
        assertWithMessage("Odd number of extra key-values").that(extras.length % 2).isEqualTo(0);
        return new BaseMatcher<Notification>() {
            @Override
            public boolean matches(Object item) {
                final Notification notification = (Notification) item;
                for (int i = 0; i < extras.length / 2; i++) {
                    if (!extras[i * 2 + 1].equals(notification.extras.getString(extras[i * 2]))) {
                        return false;
                    }
                }
                return true;
            }
            @Override
            public void describeTo(Description description) {
                description.appendText("Notification{");
                for (int i = 0; i < extras.length / 2; i++) {
                    if (i > 0) {
                        description.appendText(",");
                    }
                    description.appendText(extras[i * 2] + "=\"" + extras[i * 2 + 1] + "\"");
                }
                description.appendText("}");
            }
        };
    }

    // admin1 is the outgoing DPC, adminAnotherPackage is the incoming one.
    private void assertDeviceOwnershipRevertedWithFakeTransferMetadata() throws Exception {
        writeFakeTransferMetadataFile(UserHandle.USER_SYSTEM,
                TransferOwnershipMetadataManager.ADMIN_TYPE_DEVICE_OWNER);

        final long ident = mServiceContext.binder.clearCallingIdentity();
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForFakeAdmin(adminAnotherPackage,
                DpmMockContext.CALLER_SYSTEM_USER_UID, admin1);
        // To simulate a reboot, we just reinitialize dpms and call systemReady
        initializeDpms();

        assertThat(dpm.isDeviceOwnerApp(admin1.getPackageName())).isTrue();
        assertThat(dpm.isDeviceOwnerApp(adminAnotherPackage.getPackageName())).isFalse();
        assertThat(dpm.isAdminActive(adminAnotherPackage)).isFalse();
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isDeviceOwnerAppOnCallingUser(admin1.getPackageName())).isTrue();
        assertThat(dpm.getDeviceOwnerComponentOnCallingUser()).isEqualTo(admin1);

        assertThat(dpm.isDeviceOwnerAppOnAnyUser(admin1.getPackageName())).isTrue();
        assertThat(dpm.getDeviceOwnerComponentOnAnyUser()).isEqualTo(admin1);
        assertThat(dpm.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
        assertThat(getMockTransferMetadataManager().metadataFileExists()).isFalse();

        mServiceContext.binder.restoreCallingIdentity(ident);
    }

    // admin1 is the outgoing DPC, adminAnotherPackage is the incoming one.
    private void assertProfileOwnershipRevertedWithFakeTransferMetadata() throws Exception {
        writeFakeTransferMetadataFile(CALLER_USER_HANDLE,
                TransferOwnershipMetadataManager.ADMIN_TYPE_PROFILE_OWNER);

        int uid = UserHandle.getUid(CALLER_USER_HANDLE,
                DpmMockContext.CALLER_SYSTEM_USER_UID);
        setUpPackageManagerForAdmin(admin1, uid);
        setUpPackageManagerForFakeAdmin(adminAnotherPackage, uid, admin1);
        // To simulate a reboot, we just reinitialize dpms and call systemReady
        initializeDpms();

        assertThat(dpm.isProfileOwnerApp(admin1.getPackageName())).isTrue();
        assertThat(dpm.isAdminActive(admin1)).isTrue();
        assertThat(dpm.isProfileOwnerApp(adminAnotherPackage.getPackageName())).isFalse();
        assertThat(dpm.isAdminActive(adminAnotherPackage)).isFalse();
        assertThat(admin1).isEqualTo(dpm.getProfileOwnerAsUser(CALLER_USER_HANDLE));
        assertThat(getMockTransferMetadataManager().metadataFileExists()).isFalse();
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
        return dpms.mOwners.getProfileOwnerFile(CALLER_USER_HANDLE);
    }

    private File getProfileOwnerPoliciesFile() {
        File parentDir = dpms.mMockInjector.environmentGetUserSystemDirectory(
                CALLER_USER_HANDLE);
        return getPoliciesFile(parentDir);
    }

    private File getDeviceOwnerPoliciesFile() {
        return getPoliciesFile(getServices().systemUserDataDir);
    }

    private File getPoliciesFile(File parentDir) {
        return new File(parentDir, "device_policies.xml");
    }

    private void setUserSetupCompleteForUser(boolean isUserSetupComplete, int userhandle) {
        when(getServices().settings.settingsSecureGetIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 0,
                userhandle)).thenReturn(isUserSetupComplete ? 1 : 0);
        dpms.notifyChangeToContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), userhandle);
    }

    private void assertProvisioningAllowed(String action, boolean expected) {
        assertWithMessage("isProvisioningAllowed(%s) returning unexpected result", action)
                .that(dpm.isProvisioningAllowed(action)).isEqualTo(expected);
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
        assertWithMessage("checkProvisioningPreCondition(%s, %s) returning unexpected result",
                action, packageName).that(dpm.checkProvisioningPreCondition(action, packageName))
                        .isEqualTo(provisioningCondition);
    }

    /**
     * Setup a managed profile with the specified admin and its uid.
     * @param admin ComponentName that's visible to the test code, which doesn't have to exist.
     * @param adminUid uid of the admin package.
     * @param copyFromAdmin package information for {@code admin} will be built based on this
     *     component's information.
     * @param appTargetSdk admin's target SDK level
     */
    private void addManagedProfile(
            ComponentName admin, int adminUid, ComponentName copyFromAdmin, int appTargetSdk)
            throws Exception {
        final int userId = UserHandle.getUserId(adminUid);
        getServices().addUser(userId, 0, UserManager.USER_TYPE_PROFILE_MANAGED,
                UserHandle.USER_SYSTEM);
        mContext.callerPermissions.addAll(OWNER_SETUP_PERMISSIONS);
        setUpPackageManagerForFakeAdmin(admin, adminUid, /* enabledSetting= */ null,
                appTargetSdk, copyFromAdmin);
        dpm.setActiveAdmin(admin, false, userId);
        assertThat(dpm.setProfileOwner(admin, null, userId)).isTrue();
        mContext.callerPermissions.removeAll(OWNER_SETUP_PERMISSIONS);
    }

    /**
     * Same as {@code addManagedProfile} above, except using development API level as the API
     * level of the admin.
     */
    private void addManagedProfile(
            ComponentName admin, int adminUid, ComponentName copyFromAdmin) throws Exception {
        addManagedProfile(admin, adminUid, copyFromAdmin, VERSION_CODES.CUR_DEVELOPMENT);
    }

    /**
     * Convert String[] to StringParceledListSlice.
     */
    private static StringParceledListSlice asSlice(String[] s) {
        return new StringParceledListSlice(Arrays.asList(s));
    }

    private void grantManageDeviceAdmins() {
        Log.d(TAG, "Granting " + permission.MANAGE_DEVICE_ADMINS);
        mContext.callerPermissions.add(permission.MANAGE_DEVICE_ADMINS);
    }

    private void mockPolicyExemptApps(String... apps) {
        Log.d(TAG, "Mocking R.array.policy_exempt_apps to return " + Arrays.toString(apps));
        when(mContext.resources.getStringArray(R.array.policy_exempt_apps)).thenReturn(apps);
    }

    private void mockVendorPolicyExemptApps(String... apps) {
        Log.d(TAG, "Mocking R.array.vendor_policy_exempt_apps to return " + Arrays.toString(apps));
        when(mContext.resources.getStringArray(R.array.vendor_policy_exempt_apps)).thenReturn(apps);
    }

    private void mockEmptyPolicyExemptApps() {
        when(mContext.getResources().getStringArray(R.array.policy_exempt_apps))
                .thenReturn(new String[0]);
        when(mContext.getResources().getStringArray(R.array.vendor_policy_exempt_apps))
                .thenReturn(new String[0]);
    }
}
