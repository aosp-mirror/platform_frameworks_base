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
package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;

/** Unit tests for {@link LocationPermissionChecker}. */
public class LocationPermissionCheckerTest {

    public static final String TAG = "ConnectivityUtilTest";

    // Mock objects for testing
    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPkgMgr;
    @Mock private ApplicationInfo mMockApplInfo;
    @Mock private AppOpsManager mMockAppOps;
    @Mock private UserManager mMockUserManager;
    @Mock private LocationManager mLocationManager;

    private static final String TEST_PKG_NAME = "com.google.somePackage";
    private static final String TEST_FEATURE_ID = "com.google.someFeature";
    private static final int MANAGED_PROFILE_UID = 1100000;
    private static final int OTHER_USER_UID = 1200000;

    private final String mInteractAcrossUsersFullPermission =
            "android.permission.INTERACT_ACROSS_USERS_FULL";
    private final String mManifestStringCoarse =
            Manifest.permission.ACCESS_COARSE_LOCATION;
    private final String mManifestStringFine =
            Manifest.permission.ACCESS_FINE_LOCATION;

    // Test variables
    private int mWifiScanAllowApps;
    private int mUid;
    private int mCoarseLocationPermission;
    private int mAllowCoarseLocationApps;
    private int mFineLocationPermission;
    private int mAllowFineLocationApps;
    private int mCurrentUser;
    private boolean mIsLocationEnabled;
    private boolean mThrowSecurityException;
    private Answer<Integer> mReturnPermission;
    private HashMap<String, Integer> mPermissionsList = new HashMap<String, Integer>();
    private LocationPermissionChecker mChecker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        initTestVars();
    }

    private void setupMocks() throws Exception {
        when(mMockPkgMgr.getApplicationInfoAsUser(eq(TEST_PKG_NAME), eq(0), any()))
                .thenReturn(mMockApplInfo);
        when(mMockContext.getPackageManager()).thenReturn(mMockPkgMgr);
        when(mMockAppOps.noteOp(AppOpsManager.OPSTR_WIFI_SCAN, mUid, TEST_PKG_NAME,
                TEST_FEATURE_ID, null)).thenReturn(mWifiScanAllowApps);
        when(mMockAppOps.noteOp(eq(AppOpsManager.OPSTR_COARSE_LOCATION), eq(mUid),
                eq(TEST_PKG_NAME), eq(TEST_FEATURE_ID), nullable(String.class)))
                .thenReturn(mAllowCoarseLocationApps);
        when(mMockAppOps.noteOp(eq(AppOpsManager.OPSTR_FINE_LOCATION), eq(mUid),
                eq(TEST_PKG_NAME), eq(TEST_FEATURE_ID), nullable(String.class)))
                .thenReturn(mAllowFineLocationApps);
        if (mThrowSecurityException) {
            doThrow(new SecurityException("Package " + TEST_PKG_NAME + " doesn't belong"
                    + " to application bound to user " + mUid))
                    .when(mMockAppOps).checkPackage(mUid, TEST_PKG_NAME);
        }
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE))
                .thenReturn(mMockAppOps);
        when(mMockContext.getSystemService(Context.USER_SERVICE))
                .thenReturn(mMockUserManager);
        when(mMockContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mLocationManager);
    }

    private void setupTestCase() throws Exception {
        setupMocks();
        setupMockInterface();
        mChecker = new LocationPermissionChecker(mMockContext);
    }

    private void initTestVars() {
        mPermissionsList.clear();
        mReturnPermission = createPermissionAnswer();
        mWifiScanAllowApps = AppOpsManager.MODE_ERRORED;
        mUid = OTHER_USER_UID;
        mThrowSecurityException = true;
        mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.M;
        mIsLocationEnabled = false;
        mCurrentUser = ActivityManager.getCurrentUser();
        mCoarseLocationPermission = PackageManager.PERMISSION_DENIED;
        mFineLocationPermission = PackageManager.PERMISSION_DENIED;
        mAllowCoarseLocationApps = AppOpsManager.MODE_ERRORED;
        mAllowFineLocationApps = AppOpsManager.MODE_ERRORED;
    }

    private void setupMockInterface() {
        Binder.restoreCallingIdentity((((long) mUid) << 32) | Binder.getCallingPid());
        doAnswer(mReturnPermission).when(mMockContext).checkPermission(
                anyString(), anyInt(), anyInt());
        when(mMockUserManager.isSameProfileGroup(UserHandle.SYSTEM,
                UserHandle.getUserHandleForUid(MANAGED_PROFILE_UID)))
                .thenReturn(true);
        when(mMockContext.checkPermission(mManifestStringCoarse, -1, mUid))
                .thenReturn(mCoarseLocationPermission);
        when(mMockContext.checkPermission(mManifestStringFine, -1, mUid))
                .thenReturn(mFineLocationPermission);
        when(mLocationManager.isLocationEnabledForUser(any())).thenReturn(mIsLocationEnabled);
    }

    private Answer<Integer> createPermissionAnswer() {
        return new Answer<Integer>() {
            @Override
            public Integer answer(InvocationOnMock invocation) {
                int myUid = (int) invocation.getArguments()[1];
                String myPermission = (String) invocation.getArguments()[0];
                mPermissionsList.get(myPermission);
                if (mPermissionsList.containsKey(myPermission)) {
                    int uid = mPermissionsList.get(myPermission);
                    if (myUid == uid) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                }
                return PackageManager.PERMISSION_DENIED;
            }
        };
    }

    @Test
    public void testEnforceLocationPermission_HasAllPermissions_BeforeQ() throws Exception {
        mIsLocationEnabled = true;
        mThrowSecurityException = false;
        mCoarseLocationPermission = PackageManager.PERMISSION_GRANTED;
        mAllowCoarseLocationApps = AppOpsManager.MODE_ALLOWED;
        mWifiScanAllowApps = AppOpsManager.MODE_ALLOWED;
        mUid = mCurrentUser;
        setupTestCase();

        final int result =
                mChecker.checkLocationPermissionWithDetailInfo(
                        TEST_PKG_NAME, TEST_FEATURE_ID, mUid, null);
        assertEquals(LocationPermissionChecker.SUCCEEDED, result);
    }

    @Test
    public void testEnforceLocationPermission_HasAllPermissions_AfterQ() throws Exception {
        mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        mIsLocationEnabled = true;
        mThrowSecurityException = false;
        mUid = mCurrentUser;
        mFineLocationPermission = PackageManager.PERMISSION_GRANTED;
        mAllowFineLocationApps = AppOpsManager.MODE_ALLOWED;
        mWifiScanAllowApps = AppOpsManager.MODE_ALLOWED;
        setupTestCase();

        final int result =
                mChecker.checkLocationPermissionWithDetailInfo(
                        TEST_PKG_NAME, TEST_FEATURE_ID, mUid, null);
        assertEquals(LocationPermissionChecker.SUCCEEDED, result);
    }

    @Test
    public void testEnforceLocationPermission_PkgNameAndUidMismatch() throws Exception {
        mThrowSecurityException = true;
        mIsLocationEnabled = true;
        mFineLocationPermission = PackageManager.PERMISSION_GRANTED;
        mAllowFineLocationApps = AppOpsManager.MODE_ALLOWED;
        mWifiScanAllowApps = AppOpsManager.MODE_ALLOWED;
        setupTestCase();

        assertThrows(SecurityException.class,
                () -> mChecker.checkLocationPermissionWithDetailInfo(
                        TEST_PKG_NAME, TEST_FEATURE_ID, mUid, null));
    }

    @Test
    public void testenforceCanAccessScanResults_NoCoarseLocationPermission() throws Exception {
        mThrowSecurityException = false;
        mIsLocationEnabled = true;
        setupTestCase();

        final int result =
                mChecker.checkLocationPermissionWithDetailInfo(
                        TEST_PKG_NAME, TEST_FEATURE_ID, mUid, null);
        assertEquals(LocationPermissionChecker.ERROR_LOCATION_PERMISSION_MISSING, result);
    }

    @Test
    public void testenforceCanAccessScanResults_NoFineLocationPermission() throws Exception {
        mThrowSecurityException = false;
        mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        mIsLocationEnabled = true;
        mCoarseLocationPermission = PackageManager.PERMISSION_GRANTED;
        mAllowFineLocationApps = AppOpsManager.MODE_ERRORED;
        mUid = MANAGED_PROFILE_UID;
        setupTestCase();

        final int result =
                mChecker.checkLocationPermissionWithDetailInfo(
                        TEST_PKG_NAME, TEST_FEATURE_ID, mUid, null);
        assertEquals(LocationPermissionChecker.ERROR_LOCATION_PERMISSION_MISSING, result);
        verify(mMockAppOps, never()).noteOp(anyInt(), anyInt(), anyString());
    }

    @Test
    public void testenforceCanAccessScanResults_LocationModeDisabled() throws Exception {
        mThrowSecurityException = false;
        mUid = MANAGED_PROFILE_UID;
        mWifiScanAllowApps = AppOpsManager.MODE_ALLOWED;
        mPermissionsList.put(mInteractAcrossUsersFullPermission, mUid);
        mIsLocationEnabled = false;

        setupTestCase();

        final int result =
                mChecker.checkLocationPermissionWithDetailInfo(
                        TEST_PKG_NAME, TEST_FEATURE_ID, mUid, null);
        assertEquals(LocationPermissionChecker.ERROR_LOCATION_MODE_OFF, result);
    }

    private static void assertThrows(Class<? extends Exception> exceptionClass, Runnable r) {
        try {
            r.run();
            Assert.fail("Expected " + exceptionClass + " to be thrown.");
        } catch (Exception exception) {
            assertTrue(exceptionClass.isInstance(exception));
        }
    }
}
