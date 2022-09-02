/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.permission;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class LegacyPermissionManagerServiceTest {
    private static final int SYSTEM_UID = 1000;
    private static final int SYSTEM_PID = 1234;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID;
    private static final int APP_PID = 5678;

    private static final String CHECK_DEVICE_IDENTIFIER_MESSAGE = "testCheckDeviceIdentifierAccess";
    private static final String CHECK_PHONE_NUMBER_MESSAGE = "testCheckPhoneNumberAccess";

    private LegacyPermissionManagerService mLegacyPermissionManagerService;
    private Context mContext;
    private String mPackageName;

    @Mock
    private LegacyPermissionManagerService.Injector mInjector;

    @Mock
    private AppOpsManager mAppOpsManager;

    @Mock
    private DevicePolicyManager mDevicePolicyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getContext();
        mPackageName = mContext.getPackageName();
        mLegacyPermissionManagerService = new LegacyPermissionManagerService(mContext, mInjector);
    }

    @Test
    public void checkDeviceIdentifierAccess_callingAppUidMismatch_throwsException() {
        // An application should only be able to query its own device identifier access, querying
        // of any other UIDs should result in a SecurityException.
        setupCheckDeviceIdentifierAccessTest(APP_PID, APP_UID);

        assertThrows(SecurityException.class,
                () -> mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                        mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null,
                        APP_PID, SYSTEM_UID));
    }

    @Test
    public void checkDeviceIdentifierAccess_callingAppPidMismatch_throwsException() {
        // Similar to above an app can only specify its own pid, a mismatch should result in a
        // SecurityException.
        setupCheckDeviceIdentifierAccessTest(APP_PID, APP_UID);

        assertThrows(SecurityException.class,
                () -> mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                        mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null,
                        SYSTEM_PID, APP_UID));
    }

    @Test
    public void checkDeviceIdentifierAccess_callingAppIdWithoutAccess_returnsDenied() {
        // An application can query its own device identifier access; this test verifies that all
        // checks can run through completion and return denied.
        setupCheckDeviceIdentifierAccessTest(APP_PID, APP_UID);

        int result = mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_systemUid_returnsGranted() {
        // The system UID should always have access to device identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID);
        int result = mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null, SYSTEM_PID,
                SYSTEM_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_hasPrivilegedPermission_returnsGranted() {
        // Apps with the READ_PRIVILEGED_PHONE_STATE permission should have access to device
        // identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID, APP_UID);
        when(mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                APP_PID, APP_UID)).thenReturn(PackageManager.PERMISSION_GRANTED);

        int result = mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_hasAppOp_returnsGranted() {
        // Apps that have been granted the READ_DEVICE_IDENTIFIERS appop should have access to
        // device identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID, APP_UID);
        when(mAppOpsManager.noteOpNoThrow(eq(AppOpsManager.OPSTR_READ_DEVICE_IDENTIFIERS),
                eq(APP_UID), eq(mPackageName), any(), any())).thenReturn(
                AppOpsManager.MODE_ALLOWED);

        int result = mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_hasDpmAccess_returnsGranted() {
        // Apps that pass a DevicePolicyManager device / profile owner check should have access to
        // device identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID, APP_UID);
        when(mDevicePolicyManager.hasDeviceIdentifierAccess(mPackageName, APP_PID,
                APP_UID)).thenReturn(true);

        int result = mLegacyPermissionManagerService.checkDeviceIdentifierAccess(
                mPackageName, CHECK_DEVICE_IDENTIFIER_MESSAGE, null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkPhoneNumberAccess_callingAppUidMismatch_throwsException() throws Exception {
        // An app can check its own phone number access, but an exception should be
        // thrown if an app attempts to check the phone number access of another app's UID.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);

        assertThrows(SecurityException.class,
                () -> mLegacyPermissionManagerService.checkPhoneNumberAccess(
                        mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID,
                        SYSTEM_UID));
    }

    @Test
    public void checkPhoneNumberAccess_callingAppPidMismatch_throwsException() throws Exception {
        // An app can check its own phone number access, but an exception should be
        // thrown if an app attempts to check the phone number access of another app's PID.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);

        assertThrows(SecurityException.class,
                () -> mLegacyPermissionManagerService.checkPhoneNumberAccess(
                        mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, SYSTEM_PID,
                        APP_UID));
    }

    @Test
    public void checkPhoneNumberAccess_callingAppIdWithoutAccess_returnsDenied() throws Exception {
        // Since an app can check its own phone number access, this test verifies all checks
        // are performed and the expected result is returned when an app does not have access.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);

        int result = mLegacyPermissionManagerService.checkPhoneNumberAccess(
                mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, result);
    }

    @Test
    public void checkPhoneNumberAccess_nullPackageName_returnsDenied() throws Exception {
        // While a null value can be provided for the package name only the privileged
        // permission test would be able to proceed. Verify that a null value results in a
        // denied response instead of a NullPointerException.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);

        int result = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, result);
    }

    @Test
    public void checkPhoneNumberAccess_hasPrivilegedPermission_returnsGranted() throws Exception {
        // An app with the READ_PRIVILEGED_PHONE_STATE should have access to the phone number.
        setupCheckPhoneNumberAccessTest(SYSTEM_PID, SYSTEM_UID);
        when(mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                APP_PID, APP_UID)).thenReturn(PackageManager.PERMISSION_GRANTED);

        int result = mLegacyPermissionManagerService.checkPhoneNumberAccess(
                null, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkPhoneNumberAccess_targetPreRWithReadPhoneState_returnsGranted()
            throws Exception {
        // Prior to R an app could access the phone number with the READ_PHONE_STATE permission, but
        // both the permission and the appop must be granted. If the permission is granted but the
        // appop is not then AppOpsManager#MODE_IGNORED should be returned to indicate that this
        // should be a silent failure.
        setupCheckPhoneNumberAccessTest(SYSTEM_PID, SYSTEM_UID, APP_UID);
        setPackageTargetSdk(Build.VERSION_CODES.Q);

        grantPermissionAndAppop(android.Manifest.permission.READ_PHONE_STATE, null);
        int resultWithoutAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(
                mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);
        grantPermissionAndAppop(android.Manifest.permission.READ_PHONE_STATE,
                AppOpsManager.OPSTR_READ_PHONE_STATE);
        int resultWithAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(AppOpsManager.MODE_IGNORED, resultWithoutAppop);
        assertEquals(PackageManager.PERMISSION_GRANTED, resultWithAppop);
    }

    @Test
    public void checkPhoneNumberAccess_targetRWithReadPhoneState_returnsDenied() throws Exception {
        // Apps targeting R+ with just the READ_PHONE_STATE permission granted should not have
        // access to the phone number; PERMISSION_DENIED should be returned both with and without
        // the appop granted since this check should be skipped for target SDK R+.
        setupCheckPhoneNumberAccessTest(SYSTEM_PID, SYSTEM_UID, APP_UID);

        grantPermissionAndAppop(android.Manifest.permission.READ_PHONE_STATE, null);
        int resultWithoutAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(
                mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);
        grantPermissionAndAppop(android.Manifest.permission.READ_PHONE_STATE,
                AppOpsManager.OPSTR_READ_PHONE_STATE);
        int resultWithAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, resultWithoutAppop);
        assertEquals(PackageManager.PERMISSION_DENIED, resultWithAppop);
    }

    @Test
    public void checkPhoneNumberAccess_smsHandler_returnsGranted() throws Exception {
        // The default SMS handler should have the WRITE_SMS appop granted and have access to the
        // phone number.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);
        grantPermissionAndAppop(null, AppOpsManager.OPSTR_WRITE_SMS);

        int result = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkPhoneNumberAccess_readPhoneNumbersGranted_returnsGranted()
            throws Exception {
        // Access to the phone number should be granted to an app with the READ_PHONE_NUMBERS
        // permission and appop set.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);

        grantPermissionAndAppop(android.Manifest.permission.READ_PHONE_NUMBERS, null);
        int resultWithoutAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(
                mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);
        grantPermissionAndAppop(android.Manifest.permission.READ_PHONE_NUMBERS,
                AppOpsManager.OPSTR_READ_PHONE_NUMBERS);
        int resultWithAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, resultWithoutAppop);
        assertEquals(PackageManager.PERMISSION_GRANTED, resultWithAppop);
    }

    @Test
    public void checkPhoneNumberAccess_readSmsGranted_returnsGranted() throws Exception {
        // Access to the phone number should be granted to an app with the READ_SMS permission and
        // appop set.
        setupCheckPhoneNumberAccessTest(APP_PID, APP_UID);

        grantPermissionAndAppop(android.Manifest.permission.READ_SMS, null);
        int resultWithoutAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(
                mPackageName, CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);
        grantPermissionAndAppop(android.Manifest.permission.READ_SMS, AppOpsManager.OPSTR_READ_SMS);
        int resultWithAppop = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, resultWithoutAppop);
        assertEquals(PackageManager.PERMISSION_GRANTED, resultWithAppop);
    }

    @Test
    public void checkPhoneNumberAccess_providedUidDoesNotMatchPackageUid_throwsException()
            throws Exception {
        // An app can directly interact with one of the services that accepts a package name and
        // returns a protected resource via a direct binder transact. This app could then provide
        // the name of another app that targets pre-R, then determine if the app is installed based
        // on whether the service throws an exception or not. While the app can provide the package
        // name of another app, it cannot specify the package uid which is passed to the
        // LegacyPermissionManager using Binder#getCallingUid. Ultimately this uid should then be
        // compared against the actual uid of the package to ensure information about packages
        // installed on the device is not leaked.
        setupCheckPhoneNumberAccessTest(SYSTEM_PID, SYSTEM_UID, APP_UID + 1);

        assertThrows(SecurityException.class,
                () -> mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                        CHECK_PHONE_NUMBER_MESSAGE, null, APP_PID, APP_UID));
    }

    @Test
    public void checkPhoneNumberAccess_nullPackageNameSystemUid_returnsGranted() throws Exception {
        // The platform can pass a null package name when checking if the platform itself has
        // access to the device phone number(s) / identifier(s). This test ensures if a null package
        // is provided, then the package uid check is skipped and the test is based on whether the
        // the provided uid / pid has been granted the privileged permission.
        setupCheckPhoneNumberAccessTest(SYSTEM_PID, SYSTEM_UID, -1);
        when(mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                SYSTEM_PID, SYSTEM_UID)).thenReturn(PackageManager.PERMISSION_GRANTED);

        int result = mLegacyPermissionManagerService.checkPhoneNumberAccess(null,
                CHECK_PHONE_NUMBER_MESSAGE, null, SYSTEM_PID, SYSTEM_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkPhoneNumberAccess_systemUidMismatchPackageUid_returnsGranted()
            throws Exception {
        // When the platform is checking device phone number / identifier access checks for other
        // components on the platform, a uid less than the first application UID is provided; this
        // test verifies the package uid check is skipped and access is still granted with the
        // privileged permission.
        int telephonyUid = SYSTEM_UID + 1;
        int telephonyPid = SYSTEM_PID + 1;
        setupCheckPhoneNumberAccessTest(SYSTEM_PID, SYSTEM_UID, -1);
        when(mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                telephonyPid, telephonyUid)).thenReturn(PackageManager.PERMISSION_GRANTED);

        int result = mLegacyPermissionManagerService.checkPhoneNumberAccess(mPackageName,
                CHECK_PHONE_NUMBER_MESSAGE, null, telephonyPid, telephonyUid);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    /**
     * Configures device identifier access tests to fail; tests verifying access should individually
     * set an access check to succeed to verify access when that condition is met.
     */
    private void setupCheckDeviceIdentifierAccessTest(int callingPid, int callingUid) {
        setupCheckDeviceIdentifierAccessTest(callingPid, callingUid, callingUid);
    }

    /**
     * Configures device identifier access tests to fail; tests verifying access should individually
     * set an access check to succeed to verify access when that condition is met.
     *
     * <p>To prevent leaking package information, access checks for package UIDs >= {@link
     * android.os.Process#FIRST_APPLICATION_UID} must ensure the provided uid matches the uid of
     * the package being checked; to ensure this check is successful, this method accepts the
     * {@code packageUid} to be used for the package being checked.
     */
    public void setupCheckDeviceIdentifierAccessTest(int callingPid, int callingUid,
            int packageUid) {
        setupAccessTest(callingPid, callingUid, packageUid);

        when(mDevicePolicyManager.hasDeviceIdentifierAccess(anyString(), anyInt(),
                anyInt())).thenReturn(false);
        when(mInjector.getSystemService(eq(Context.DEVICE_POLICY_SERVICE))).thenReturn(
                mDevicePolicyManager);
    }

    /**
     * Configures phone number access tests to fail; tests verifying access should individually
     * set an access check to succeed to verify access when that condition is set.
     *
     */
    private void setupCheckPhoneNumberAccessTest(int callingPid, int callingUid) throws Exception {
        setupCheckPhoneNumberAccessTest(callingPid, callingUid, callingUid);
    }

    /**
     * Configures phone number access tests to fail; tests verifying access should individually set
     * an access check to succeed to verify access when that condition is met.
     *
     * <p>To prevent leaking package information, access checks for package UIDs >= {@link
     * android.os.Process#FIRST_APPLICATION_UID} must ensure the provided uid matches the uid of
     * the package being checked; to ensure this check is successful, this method accepts the
     * {@code packageUid} to be used for the package being checked.
     */
    private void setupCheckPhoneNumberAccessTest(int callingPid, int callingUid, int packageUid)
            throws Exception {
        setupAccessTest(callingPid, callingUid, packageUid);
        setPackageTargetSdk(Build.VERSION_CODES.R);
    }

    /**
     * Configures the common mocks for any access tests using the provided {@code callingPid}
     * and {@code callingUid}.
     */
    private void setupAccessTest(int callingPid, int callingUid, int packageUid) {
        when(mInjector.getCallingPid()).thenReturn(callingPid);
        when(mInjector.getCallingUid()).thenReturn(callingUid);
        when(mInjector.getPackageUidForUser(anyString(), anyInt())).thenReturn(packageUid);

        when(mInjector.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        when(mAppOpsManager.noteOpNoThrow(anyString(), anyInt(), anyString(), any(),
                any())).thenReturn(AppOpsManager.MODE_DEFAULT);
        when(mInjector.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mAppOpsManager);
    }

    /**
     * Sets the returned {@code targetSdkVersion} for the package under test.
     */
    private void setPackageTargetSdk(int targetSdkVersion) throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.targetSdkVersion = targetSdkVersion;
        when(mInjector.getApplicationInfo(eq(mPackageName), eq(APP_UID))).thenReturn(appInfo);
    }

    /**
     * Configures the mocks to return {@code PackageManager.PERMISSION_GRANTED} for the specified
     * {@code permission} and {@code AppOpsManager.MODE_ALLOWED} for the provided {@code appop}
     * when queried for the package under test.
     */
    private void grantPermissionAndAppop(String permission, String appop) {
        if (permission != null) {
            when(mInjector.checkPermission(permission, APP_PID, APP_UID)).thenReturn(
                    PackageManager.PERMISSION_GRANTED);
        }
        if (appop != null) {
            when(mAppOpsManager.noteOpNoThrow(eq(appop), eq(APP_UID), eq(mPackageName), any(),
                    any())).thenReturn(AppOpsManager.MODE_ALLOWED);
        }
    }
}
