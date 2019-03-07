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

import static com.android.server.connectivity.PermissionMonitor.NETWORK;
import static com.android.server.connectivity.PermissionMonitor.SYSTEM;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.INetworkManagementService;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PermissionMonitorTest {
    private static final int MOCK_USER1 = 0;
    private static final int MOCK_USER2 = 1;
    private static final int MOCK_UID1 = 10001;
    private static final String MOCK_PACKAGE1 = "appName1";
    private static final String SYSTEM_PACKAGE1 = "sysName1";
    private static final String SYSTEM_PACKAGE2 = "sysName2";
    private static final String PARTITION_SYSTEM = "system";
    private static final String PARTITION_OEM = "oem";
    private static final String PARTITION_PRODUCT = "product";
    private static final String PARTITION_VENDOR = "vendor";
    private static final int VERSION_P = Build.VERSION_CODES.P;
    private static final int VERSION_Q = Build.VERSION_CODES.Q;

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNMS;

    private PermissionMonitor mPermissionMonitor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mPermissionMonitor = spy(new PermissionMonitor(mContext, mNMS));
    }

    private boolean hasBgPermission(String partition, int targetSdkVersion, int uid,
            String... permission) throws Exception {
        final PackageInfo packageInfo = packageInfoWithPermissions(permission, partition);
        packageInfo.applicationInfo.targetSdkVersion = targetSdkVersion;
        packageInfo.applicationInfo.uid = uid;
        when(mPackageManager.getPackageInfoAsUser(
                eq(MOCK_PACKAGE1), eq(GET_PERMISSIONS), anyInt())).thenReturn(packageInfo);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[] {MOCK_PACKAGE1});
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
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID1));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID1, CHANGE_NETWORK_STATE));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID1, NETWORK_STACK));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID1, CONNECTIVITY_INTERNAL));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_P, MOCK_UID1, CHANGE_WIFI_STATE));

        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, MOCK_UID1));
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, MOCK_UID1, CHANGE_WIFI_STATE));
    }

    @Test
    public void testHasUseBackgroundNetworksPermissionSystemUid() throws Exception {
        doReturn(VERSION_P).when(mPermissionMonitor).getDeviceFirstSdkInt();
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID, CHANGE_WIFI_STATE));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_P, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));

        doReturn(VERSION_Q).when(mPermissionMonitor).getDeviceFirstSdkInt();
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID));
        assertFalse(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID, CHANGE_WIFI_STATE));
        assertTrue(hasBgPermission(PARTITION_SYSTEM, VERSION_Q, SYSTEM_UID,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
    }

    @Test
    public void testHasUseBackgroundNetworksPermissionVendorApp() throws Exception {
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID1));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID1, CHANGE_NETWORK_STATE));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID1, NETWORK_STACK));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID1, CONNECTIVITY_INTERNAL));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID1,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS));
        assertTrue(hasBgPermission(PARTITION_VENDOR, VERSION_P, MOCK_UID1, CHANGE_WIFI_STATE));

        assertFalse(hasBgPermission(PARTITION_VENDOR, VERSION_Q, MOCK_UID1));
        assertFalse(hasBgPermission(PARTITION_VENDOR, VERSION_Q, MOCK_UID1, CHANGE_WIFI_STATE));
    }

    private class NMSMonitor {
        private final HashMap<Integer, Boolean> mApps = new HashMap<>();

        NMSMonitor(INetworkManagementService mockNMS) throws Exception {
            // Add hook to verify and track result of setPermission.
            doAnswer((InvocationOnMock invocation) -> {
                final Object[] args = invocation.getArguments();
                final Boolean isSystem = args[0].equals("SYSTEM");
                for (final int uid : (int[]) args[1]) {
                    // TODO: Currently, permission monitor will send duplicate commands for each uid
                    // corresponding to each user. Need to fix that and uncomment below test.
                    // if (mApps.containsKey(uid) && mApps.get(uid) == isSystem) {
                    //     fail("uid " + uid + " is already set to " + isSystem);
                    // }
                    mApps.put(uid, isSystem);
                }
                return null;
            }).when(mockNMS).setPermission(anyString(), any(int[].class));

            // Add hook to verify and track result of clearPermission.
            doAnswer((InvocationOnMock invocation) -> {
                final Object[] args = invocation.getArguments();
                for (final int uid : (int[]) args[0]) {
                    // TODO: Currently, permission monitor will send duplicate commands for each uid
                    // corresponding to each user. Need to fix that and uncomment below test.
                    // if (!mApps.containsKey(uid)) {
                    //     fail("uid " + uid + " does not exist.");
                    // }
                    mApps.remove(uid);
                }
                return null;
            }).when(mockNMS).clearPermission(any(int[].class));
        }

        public void expectPermission(Boolean permission, int[] users, int[] apps) {
            for (final int user : users) {
                for (final int app : apps) {
                    final int uid = UserHandle.getUid(user, app);
                    if (!mApps.containsKey(uid)) {
                        fail("uid " + uid + " does not exist.");
                    }
                    if (mApps.get(uid) != permission) {
                        fail("uid " + uid + " has wrong permission: " +  permission);
                    }
                }
            }
        }

        public void expectNoPermission(int[] users, int[] apps) {
            for (final int user : users) {
                for (final int app : apps) {
                    final int uid = UserHandle.getUid(user, app);
                    if (mApps.containsKey(uid)) {
                        fail("uid " + uid + " has listed permissions, expected none.");
                    }
                }
            }
        }
    }

    @Test
    public void testUserAndPackageAddRemove() throws Exception {
        final NMSMonitor mNMSMonitor = new NMSMonitor(mNMS);

        // MOCK_UID1: MOCK_PACKAGE1 only has network permission.
        // SYSTEM_UID: SYSTEM_PACKAGE1 has system permission.
        // SYSTEM_UID: SYSTEM_PACKAGE2 only has network permission.
        doReturn(SYSTEM).when(mPermissionMonitor).highestPermissionForUid(eq(SYSTEM), anyString());
        doReturn(SYSTEM).when(mPermissionMonitor).highestPermissionForUid(any(),
                eq(SYSTEM_PACKAGE1));
        doReturn(NETWORK).when(mPermissionMonitor).highestPermissionForUid(any(),
                eq(SYSTEM_PACKAGE2));
        doReturn(NETWORK).when(mPermissionMonitor).highestPermissionForUid(any(),
                eq(MOCK_PACKAGE1));

        // Add SYSTEM_PACKAGE2, expect only have network permission.
        mPermissionMonitor.onUserAdded(MOCK_USER1);
        addPackageForUsers(new int[]{MOCK_USER1}, SYSTEM_PACKAGE2, SYSTEM_UID);
        mNMSMonitor.expectPermission(NETWORK, new int[]{MOCK_USER1}, new int[]{SYSTEM_UID});

        // Add SYSTEM_PACKAGE1, expect permission escalate.
        addPackageForUsers(new int[]{MOCK_USER1}, SYSTEM_PACKAGE1, SYSTEM_UID);
        mNMSMonitor.expectPermission(SYSTEM, new int[]{MOCK_USER1}, new int[]{SYSTEM_UID});

        mPermissionMonitor.onUserAdded(MOCK_USER2);
        mNMSMonitor.expectPermission(SYSTEM, new int[]{MOCK_USER1, MOCK_USER2},
                new int[]{SYSTEM_UID});

        addPackageForUsers(new int[]{MOCK_USER1, MOCK_USER2}, MOCK_PACKAGE1, MOCK_UID1);
        mNMSMonitor.expectPermission(SYSTEM, new int[]{MOCK_USER1, MOCK_USER2},
                new int[]{SYSTEM_UID});
        mNMSMonitor.expectPermission(NETWORK, new int[]{MOCK_USER1, MOCK_USER2},
                new int[]{MOCK_UID1});

        // Remove MOCK_UID1, expect no permission left for all user.
        mPermissionMonitor.onPackageRemoved(MOCK_UID1);
        removePackageForUsers(new int[]{MOCK_USER1, MOCK_USER2}, MOCK_UID1);
        mNMSMonitor.expectNoPermission(new int[]{MOCK_USER1, MOCK_USER2}, new int[]{MOCK_UID1});

        // Remove SYSTEM_PACKAGE1, expect permission downgrade.
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{SYSTEM_PACKAGE2});
        removePackageForUsers(new int[]{MOCK_USER1, MOCK_USER2}, SYSTEM_UID);
        mNMSMonitor.expectPermission(NETWORK, new int[]{MOCK_USER1, MOCK_USER2},
                new int[]{SYSTEM_UID});

        mPermissionMonitor.onUserRemoved(MOCK_USER1);
        mNMSMonitor.expectPermission(NETWORK, new int[]{MOCK_USER2}, new int[]{SYSTEM_UID});

        // Remove all packages, expect no permission left.
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{});
        removePackageForUsers(new int[]{MOCK_USER2}, SYSTEM_UID);
        mNMSMonitor.expectNoPermission(new int[]{MOCK_USER1, MOCK_USER2},
                new int[]{SYSTEM_UID, MOCK_UID1});

        // Remove last user, expect no redundant clearPermission is invoked.
        mPermissionMonitor.onUserRemoved(MOCK_USER2);
        mNMSMonitor.expectNoPermission(new int[]{MOCK_USER1, MOCK_USER2},
                new int[]{SYSTEM_UID, MOCK_UID1});
    }

    // Normal package add/remove operations will trigger multiple intent for uids corresponding to
    // each user. To simulate generic package operations, the onPackageAdded/Removed will need to be
    // called multiple times with the uid corresponding to each user.
    private void addPackageForUsers(int[] users, String packageName, int uid) {
        for (final int user : users) {
            mPermissionMonitor.onPackageAdded(packageName, UserHandle.getUid(user, uid));
        }
    }

    private void removePackageForUsers(int[] users, int uid) {
        for (final int user : users) {
            mPermissionMonitor.onPackageRemoved(UserHandle.getUid(user, uid));
        }
    }
}
