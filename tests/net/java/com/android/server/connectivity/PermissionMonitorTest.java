/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;
import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_OEM;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_PRODUCT;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_VENDOR;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.os.Process.SYSTEM_UID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PermissionMonitorTest {
    private static final int MOCK_UID = 10001;
    private static final String[] MOCK_PACKAGE_NAMES = new String[] { "com.foo.bar" };
    private static final String PARTITION_SYSTEM = "system";
    private static final String PARTITION_OEM = "oem";
    private static final String PARTITION_PRODUCT = "product";
    private static final String PARTITION_VENDOR = "vendor";
    private static final int VERSION_P = Build.VERSION_CODES.P;
    private static final int VERSION_Q = Build.VERSION_CODES.Q;

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;

    private PermissionMonitor mPermissionMonitor;
    private int mMockFirstSdkInt;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(MOCK_PACKAGE_NAMES);
        // Try to use spy() here for stubbing getDeviceFirstSdkInt value but the spies are loaded
        // by a custom class loader that's different from the loader used for loading the real
        // thing. That means those two classes are not in the same package, so a package private
        // method is not accessible. Hence, using override method to control FIRST_SDK_INT value
        // instead of spy function for testing.
        mPermissionMonitor = new PermissionMonitor(mContext, null) {
            @Override
            int getDeviceFirstSdkInt() {
                return mMockFirstSdkInt;
            }
        };
    }

    private boolean hasBgPermission(String partition, int targetSdkVersion, int uid,
            String... permission) throws Exception {
        final PackageInfo packageInfo = packageInfoWithPermissions(permission, partition);
        packageInfo.applicationInfo.targetSdkVersion = targetSdkVersion;
        packageInfo.applicationInfo.uid = uid;
        when(mPackageManager.getPackageInfoAsUser(
                eq(MOCK_PACKAGE_NAMES[0]), eq(GET_PERMISSIONS), anyInt())).thenReturn(packageInfo);
        return mPermissionMonitor.hasUseBackgroundNetworksPermission(uid);
    }

    private PackageInfo packageInfoWithPermissions(String[] permissions, String partition) {
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.requestedPermissions = permissions;
        packageInfo.applicationInfo = new ApplicationInfo();
        int privateFlags = 0;
        switch (partition) {
            case PARTITION_OEM:
                privateFlags = PRIVATE_FLAG_OEM;
                break;
            case PARTITION_PRODUCT:
                privateFlags = PRIVATE_FLAG_PRODUCT;
                break;
            case PARTITION_VENDOR:
                privateFlags = PRIVATE_FLAG_VENDOR;
                break;
        }
        packageInfo.applicationInfo.privateFlags = privateFlags;
        return packageInfo;
    }

    @Test
    public void testHasPermission() {
        PackageInfo app = packageInfoWithPermissions(new String[] {}, PARTITION_SYSTEM);
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertFalse(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));

        app = packageInfoWithPermissions(new String[] {
            CHANGE_NETWORK_STATE, NETWORK_STACK
        }, PARTITION_SYSTEM);
        assertTrue(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertTrue(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));

        app = packageInfoWithPermissions(new String[] {
            CONNECTIVITY_USE_RESTRICTED_NETWORKS, CONNECTIVITY_INTERNAL
        }, PARTITION_SYSTEM);
        assertFalse(mPermissionMonitor.hasPermission(app, CHANGE_NETWORK_STATE));
        assertFalse(mPermissionMonitor.hasPermission(app, NETWORK_STACK));
        assertTrue(mPermissionMonitor.hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertTrue(mPermissionMonitor.hasPermission(app, CONNECTIVITY_INTERNAL));
    }

    @Test
    public void testIsVendorApp() {
        PackageInfo app = packageInfoWithPermissions(new String[] {}, PARTITION_SYSTEM);
        assertFalse(mPermissionMonitor.isVendorApp(app.applicationInfo));
        app = packageInfoWithPermissions(new String[] {}, PARTITION_OEM);
        assertTrue(mPermissionMonitor.isVendorApp(app.applicationInfo));
        app = packageInfoWithPermissions(new String[] {}, PARTITION_PRODUCT);
        assertTrue(mPermissionMonitor.isVendorApp(app.applicationInfo));
        app = packageInfoWithPermissions(new String[] {}, PARTITION_VENDOR);
        assertTrue(mPermissionMonitor.isVendorApp(app.applicationInfo));
    }

    @Test
    public void testHasUseBackgroundNetworksPermission() throws Exception {
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID, CHANGE_NETWORK_STATE));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID, NETWORK_STACK));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID, CONNECTIVITY_INTERNAL));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID, CHANGE_WIFI_STATE));

        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, MOCK_UID));
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, MOCK_UID, CHANGE_WIFI_STATE));
    }

    @Test
    public void testHasUseBackgroundNetworksPermissionSystemUid() throws Exception {
        mMockFirstSdkInt = VERSION_P;
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID, CHANGE_WIFI_STATE));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));

        mMockFirstSdkInt = VERSION_Q;
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID));
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID, CHANGE_WIFI_STATE));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
    }

    @Test
    public void testHasUseBackgroundNetworksPermissionVendorApp() throws Exception {
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID, CHANGE_NETWORK_STATE));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID, NETWORK_STACK));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID, CONNECTIVITY_INTERNAL));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID, CHANGE_WIFI_STATE));

        assertFalse(hasBgPermission(PARTITION_VENDOR, VERSION_Q, MOCK_UID));
        assertFalse(hasBgPermission(PARTITION_VENDOR, VERSION_Q, MOCK_UID, CHANGE_WIFI_STATE));
    }
}
