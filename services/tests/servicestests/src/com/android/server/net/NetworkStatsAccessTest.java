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
 * limitations under the License
 */

package com.android.server.net;

import static org.mockito.Mockito.when;

import android.Manifest;
import android.Manifest.permission;
import android.app.AppOpsManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;

import com.android.server.LocalServices;

import junit.framework.TestCase;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class NetworkStatsAccessTest extends TestCase {
    private static final String TEST_PKG = "com.example.test";
    private static final int TEST_UID = 12345;

    @Mock private Context mContext;
    @Mock private DevicePolicyManagerInternal mDpmi;
    @Mock private TelephonyManager mTm;
    @Mock private AppOpsManager mAppOps;

    // Hold the real service so we can restore it when tearing down the test.
    private DevicePolicyManagerInternal mSystemDpmi;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);

        mSystemDpmi = LocalServices.getService(DevicePolicyManagerInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class, mDpmi);

        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTm);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOps);
    }

    @Override
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class, mSystemDpmi);
        super.tearDown();
    }

    public void testCheckAccessLevel_hasCarrierPrivileges() throws Exception {
        setHasCarrierPrivileges(true);
        setIsDeviceOwner(false);
        setIsProfileOwner(false);
        setHasAppOpsPermission(AppOpsManager.MODE_DEFAULT, false);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.DEVICE,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_isDeviceOwner() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(true);
        setIsProfileOwner(false);
        setHasAppOpsPermission(AppOpsManager.MODE_DEFAULT, false);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.DEVICE,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_isProfileOwner() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(false);
        setIsProfileOwner(true);
        setHasAppOpsPermission(AppOpsManager.MODE_DEFAULT, false);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.USER,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_hasAppOpsBitAllowed() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(false);
        setIsProfileOwner(true);
        setHasAppOpsPermission(AppOpsManager.MODE_ALLOWED, false);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.USER,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_hasAppOpsBitDefault_grantedPermission() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(false);
        setIsProfileOwner(true);
        setHasAppOpsPermission(AppOpsManager.MODE_DEFAULT, true);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.USER,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_hasReadHistoryPermission() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(false);
        setIsProfileOwner(true);
        setHasAppOpsPermission(AppOpsManager.MODE_DEFAULT, false);
        setHasReadHistoryPermission(true);
        assertEquals(NetworkStatsAccess.Level.USER,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_deniedAppOpsBit() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(false);
        setIsProfileOwner(false);
        setHasAppOpsPermission(AppOpsManager.MODE_ERRORED, true);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.DEFAULT,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    public void testCheckAccessLevel_deniedAppOpsBit_deniedPermission() throws Exception {
        setHasCarrierPrivileges(false);
        setIsDeviceOwner(false);
        setIsProfileOwner(false);
        setHasAppOpsPermission(AppOpsManager.MODE_DEFAULT, false);
        setHasReadHistoryPermission(false);
        assertEquals(NetworkStatsAccess.Level.DEFAULT,
                NetworkStatsAccess.checkAccessLevel(mContext, TEST_UID, TEST_PKG));
    }

    private void setHasCarrierPrivileges(boolean hasPrivileges) {
        when(mTm.checkCarrierPrivilegesForPackage(TEST_PKG)).thenReturn(
                hasPrivileges ? TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS
                        : TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
    }

    private void setIsDeviceOwner(boolean isOwner) {
        when(mDpmi.isActiveAdminWithPolicy(TEST_UID, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER))
                .thenReturn(isOwner);
    }

    private void setIsProfileOwner(boolean isOwner) {
        when(mDpmi.isActiveAdminWithPolicy(TEST_UID, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER))
                .thenReturn(isOwner);
    }

    private void setHasAppOpsPermission(int appOpsMode, boolean hasPermission) {
        when(mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS, TEST_UID, TEST_PKG))
                .thenReturn(appOpsMode);
        when(mContext.checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)).thenReturn(
                hasPermission ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED);
    }

    private void setHasReadHistoryPermission(boolean hasPermission) {
        when(mContext.checkCallingOrSelfPermission(permission.READ_NETWORK_USAGE_HISTORY))
                .thenReturn(hasPermission ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED);
    }
}
