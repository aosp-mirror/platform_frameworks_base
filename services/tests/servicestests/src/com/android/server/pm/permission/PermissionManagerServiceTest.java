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
import android.content.pm.PackageManager;
import android.os.Process;
import android.permission.PermissionManagerInternal;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class PermissionManagerServiceTest {
    private static final String TAG = "PermissionManagerServiceTag";

    private static final int SYSTEM_UID = 1000;
    private static final int SYSTEM_PID = 1234;
    private static final int APP_UID = Process.FIRST_APPLICATION_UID;
    private static final int APP_PID = 5678;

    private PermissionManagerService mPermissionManagerService;
    private Context mContext;

    @Mock
    private PermissionManagerService.Injector mInjector;

    @Mock
    private AppOpsManager mAppOpsManager;

    @Mock
    private DevicePolicyManager mDevicePolicyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getContext();
        Object lock = new Object();
        mPermissionManagerService = new PermissionManagerService(mContext, lock, mInjector);
    }

    @After
    public void tearDown() {
        // The LocalServices added by the constructor of the PermissionManagerService can either be
        // removed here after each test when tests are run serially, or to run them in parallel
        // the Injector can provide methods to add these that can be ignored by the mock.
        LocalServices.removeServiceForTest(PermissionManagerServiceInternal.class);
        LocalServices.removeServiceForTest(PermissionManagerInternal.class);
    }

    @Test
    public void checkDeviceIdentifierAccess_callingAppUidMismatch_throwsException() {
        // An application should only be able to query its own device identifier access, querying
        // of any other UIDs should result in a SecurityException.
        setupCheckDeviceIdentifierAccessTest(APP_PID, APP_UID);

        assertThrows(SecurityException.class,
                () -> mPermissionManagerService.checkDeviceIdentifierAccess(
                        mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null,
                        APP_PID, SYSTEM_UID));
    }

    @Test
    public void checkDeviceIdentifierAccess_callingAppPidMismatch_throwsException() {
        // Similar to above an app can only specify its own pid, a mismatch should result in a
        // SecurityException.
        setupCheckDeviceIdentifierAccessTest(APP_PID, APP_UID);

        assertThrows(SecurityException.class,
                () -> mPermissionManagerService.checkDeviceIdentifierAccess(
                        mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null,
                        SYSTEM_PID, APP_UID));
    }

    @Test
    public void checkDeviceIdentifierAccess_callingAppIdWithoutAccess_returnsDenied() {
        // An application can query its own device identifier access; this test verifies that all
        // checks can run through completion and return denied.
        setupCheckDeviceIdentifierAccessTest(APP_PID, APP_UID);

        int result = mPermissionManagerService.checkDeviceIdentifierAccess(
                mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_DENIED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_systemUid_returnsGranted() {
        // The system UID should always have access to device identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID);
        int result = mPermissionManagerService.checkDeviceIdentifierAccess(
                mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null, SYSTEM_PID,
                SYSTEM_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_hasPrivilegedPermission_returnsGranted() {
        // Apps with the READ_PRIVILEGED_PHONE_STATE permission should have access to device
        // identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID);
        when(mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                APP_PID, APP_UID)).thenReturn(PackageManager.PERMISSION_GRANTED);

        int result = mPermissionManagerService.checkDeviceIdentifierAccess(
                mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_hasAppOp_returnsGranted() {
        // Apps that have been granted the READ_DEVICE_IDENTIFIERS appop should have access to
        // device identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID);
        when(mAppOpsManager.noteOpNoThrow(eq(AppOpsManager.OPSTR_READ_DEVICE_IDENTIFIERS),
                eq(APP_UID), eq(mContext.getPackageName()), any(), any())).thenReturn(
                AppOpsManager.MODE_ALLOWED);

        int result = mPermissionManagerService.checkDeviceIdentifierAccess(
                mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    @Test
    public void checkDeviceIdentifierAccess_hasDpmAccess_returnsGranted() {
        // Apps that pass a DevicePolicyManager device / profile owner check should have access to
        // device identifiers.
        setupCheckDeviceIdentifierAccessTest(SYSTEM_PID, SYSTEM_UID);
        when(mDevicePolicyManager.hasDeviceIdentifierAccess(mContext.getPackageName(), APP_PID,
                APP_UID)).thenReturn(true);

        int result = mPermissionManagerService.checkDeviceIdentifierAccess(
                mContext.getPackageName(), "testCheckDeviceIdentifierAccess", null, APP_PID,
                APP_UID);

        assertEquals(PackageManager.PERMISSION_GRANTED, result);
    }

    private void setupCheckDeviceIdentifierAccessTest(int callingPid, int callingUid) {
        when(mInjector.getCallingPid()).thenReturn(callingPid);
        when(mInjector.getCallingUid()).thenReturn(callingUid);

        // Configure the checkDeviceIdentifierAccess tests to fail all access checks, then each test
        // can individually set the access check to pass for verification.
        when(mInjector.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        when(mAppOpsManager.noteOpNoThrow(anyString(), anyInt(), anyString(), any(),
                any())).thenReturn(AppOpsManager.MODE_DEFAULT);
        when(mInjector.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mAppOpsManager);

        when(mDevicePolicyManager.hasDeviceIdentifierAccess(anyString(), anyInt(),
                anyInt())).thenReturn(false);
        when(mInjector.getSystemService(eq(Context.DEVICE_POLICY_SERVICE))).thenReturn(
                mDevicePolicyManager);
    }
}
