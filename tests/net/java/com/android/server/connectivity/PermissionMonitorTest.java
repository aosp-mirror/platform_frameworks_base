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
import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_OEM;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_PRODUCT;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_VENDOR;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageList;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.INetd;
import android.os.Build;
import android.os.INetworkManagementService;
import android.os.UserHandle;
import android.util.SparseIntArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PermissionMonitorTest {
    private static final int MOCK_USER1 = 0;
    private static final int MOCK_USER2 = 1;
    private static final int MOCK_UID1 = 10001;
    private static final int MOCK_UID2 = 10086;
    private static final int SYSTEM_UID1 = 1000;
    private static final int SYSTEM_UID2 = 1008;
    private static final String MOCK_PACKAGE1 = "appName1";
    private static final String MOCK_PACKAGE2 = "appName2";
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
    @Mock private INetd mNetdService;
    @Mock private PackageManagerInternal mMockPmi;

    private PackageManagerInternal.PackageListObserver mObserver;
    private PermissionMonitor mPermissionMonitor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mPermissionMonitor = spy(new PermissionMonitor(mContext, mNMS, mNetdService));

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPmi);
        when(mMockPmi.getPackageList(any())).thenReturn(new PackageList(new ArrayList<String>(),
                  /* observer */ null));
        when(mPackageManager.getInstalledPackages(anyInt())).thenReturn(/* empty app list */ null);
        mPermissionMonitor.startMonitoring();

        final ArgumentCaptor<PackageManagerInternal.PackageListObserver> observerCaptor =
                ArgumentCaptor.forClass(PackageManagerInternal.PackageListObserver.class);
        verify(mMockPmi).getPackageList(observerCaptor.capture());
        mObserver = observerCaptor.getValue();
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
        int[] requestedPermissionsFlags = new int[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            requestedPermissionsFlags[i] = REQUESTED_PERMISSION_GRANTED;
        }
        return packageInfoWithPermissions(permissions, partition,
                requestedPermissionsFlags);
    }

    private PackageInfo packageInfoWithPermissions(String[] permissions, String partition,
            int[] requestedPermissionsFlags) {
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.requestedPermissions = permissions;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.requestedPermissionsFlags = requestedPermissionsFlags;
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

    private class NetdServiceMonitor {
        private final HashMap<Integer, Integer> mPermissions = new HashMap<>();

        NetdServiceMonitor(INetd mockNetdService) throws Exception {
            // Add hook to verify and track result of setPermission.
            doAnswer((InvocationOnMock invocation) -> {
                final Object[] args = invocation.getArguments();
                final int permission = (int) args[0];
                for (final int uid : (int[]) args[1]) {
                    mPermissions.put(uid, permission);
                }
                return null;
            }).when(mockNetdService).trafficSetNetPermForUids(anyInt(), any(int[].class));
        }

        public void expectPermission(int permission, int[] apps) {
            for (final int app : apps) {
                if (!mPermissions.containsKey(app)) {
                    fail("uid " + app + " does not exist.");
                }
                if (mPermissions.get(app) != permission) {
                    fail("uid " + app + " has wrong permission: " + mPermissions.get(app));
                }
            }
        }
    }

    @Test
    public void testPackagePermissionUpdate() throws Exception {
        final NetdServiceMonitor mNetdServiceMonitor = new NetdServiceMonitor(mNetdService);
        // MOCK_UID1: MOCK_PACKAGE1 only has internet permission.
        // MOCK_UID2: MOCK_PACKAGE2 does not have any permission.
        // SYSTEM_UID1: SYSTEM_PACKAGE1 has internet permission and update device stats permission.
        // SYSTEM_UID2: SYSTEM_PACKAGE2 has only update device stats permission.

        SparseIntArray netdPermissionsAppIds = new SparseIntArray();
        netdPermissionsAppIds.put(MOCK_UID1, INetd.PERMISSION_INTERNET);
        netdPermissionsAppIds.put(MOCK_UID2, INetd.NO_PERMISSIONS);
        netdPermissionsAppIds.put(SYSTEM_UID1, INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS);
        netdPermissionsAppIds.put(SYSTEM_UID2, INetd.PERMISSION_UPDATE_DEVICE_STATS);

        // Send the permission information to netd, expect permission updated.
        mPermissionMonitor.sendPackagePermissionsToNetd(netdPermissionsAppIds);

        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET,
                new int[]{MOCK_UID1});
        mNetdServiceMonitor.expectPermission(INetd.NO_PERMISSIONS, new int[]{MOCK_UID2});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{SYSTEM_UID1});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_UPDATE_DEVICE_STATS,
                new int[]{SYSTEM_UID2});

        // Update permission of MOCK_UID1, expect new permission show up.
        mPermissionMonitor.sendPackagePermissionsForUid(MOCK_UID1,
                INetd.PERMISSION_INTERNET | INetd.PERMISSION_UPDATE_DEVICE_STATS);
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});

        // Change permissions of SYSTEM_UID2, expect new permission show up and old permission
        // revoked.
        mPermissionMonitor.sendPackagePermissionsForUid(SYSTEM_UID2,
                INetd.PERMISSION_INTERNET);
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET, new int[]{SYSTEM_UID2});

        // Revoke permission from SYSTEM_UID1, expect no permission stored.
        mPermissionMonitor.sendPackagePermissionsForUid(SYSTEM_UID1, INetd.NO_PERMISSIONS);
        mNetdServiceMonitor.expectPermission(INetd.NO_PERMISSIONS, new int[]{SYSTEM_UID1});
    }

    private PackageInfo addPackage(String packageName, int uid, String[] permissions)
            throws Exception {
        PackageInfo packageInfo = packageInfoWithPermissions(permissions, PARTITION_SYSTEM);
        when(mPackageManager.getPackageInfo(eq(packageName), anyInt())).thenReturn(packageInfo);
        when(mPackageManager.getPackagesForUid(eq(uid))).thenReturn(new String[]{packageName});
        mObserver.onPackageAdded(packageName, uid);
        return packageInfo;
    }

    @Test
    public void testPackageInstall() throws Exception {
        final NetdServiceMonitor mNetdServiceMonitor = new NetdServiceMonitor(mNetdService);

        addPackage(MOCK_PACKAGE1, MOCK_UID1, new String[] {INTERNET, UPDATE_DEVICE_STATS});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});

        addPackage(MOCK_PACKAGE2, MOCK_UID2, new String[] {INTERNET});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET, new int[]{MOCK_UID2});
    }

    @Test
    public void testPackageInstallSharedUid() throws Exception {
        final NetdServiceMonitor mNetdServiceMonitor = new NetdServiceMonitor(mNetdService);

        PackageInfo packageInfo1 = addPackage(MOCK_PACKAGE1, MOCK_UID1,
                new String[] {INTERNET, UPDATE_DEVICE_STATS});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});

        // Install another package with the same uid and no permissions should not cause the UID to
        // lose permissions.
        PackageInfo packageInfo2 = packageInfoWithPermissions(new String[]{}, PARTITION_SYSTEM);
        when(mPackageManager.getPackageInfo(eq(MOCK_PACKAGE2), anyInt())).thenReturn(packageInfo2);
        when(mPackageManager.getPackagesForUid(MOCK_UID1))
              .thenReturn(new String[]{MOCK_PACKAGE1, MOCK_PACKAGE2});
        mObserver.onPackageAdded(MOCK_PACKAGE2, MOCK_UID1);
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});
    }

    @Test
    public void testPackageUninstallBasic() throws Exception {
        final NetdServiceMonitor mNetdServiceMonitor = new NetdServiceMonitor(mNetdService);

        addPackage(MOCK_PACKAGE1, MOCK_UID1, new String[] {INTERNET, UPDATE_DEVICE_STATS});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});

        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(new String[]{});
        mObserver.onPackageRemoved(MOCK_PACKAGE1, MOCK_UID1);
        mNetdServiceMonitor.expectPermission(INetd.NO_PERMISSIONS, new int[]{MOCK_UID1});
    }

    @Test
    public void testPackageUpdate() throws Exception {
        final NetdServiceMonitor mNetdServiceMonitor = new NetdServiceMonitor(mNetdService);

        addPackage(MOCK_PACKAGE1, MOCK_UID1, new String[] {INTERNET, UPDATE_DEVICE_STATS});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});

        // Remove and install the same package to simulate the update action
        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(new String[]{});
        mObserver.onPackageRemoved(MOCK_PACKAGE1, MOCK_UID1);
        mNetdServiceMonitor.expectPermission(INetd.NO_PERMISSIONS, new int[]{MOCK_UID1});

        addPackage(MOCK_PACKAGE1, MOCK_UID1, new String[] {INTERNET});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET, new int[]{MOCK_UID1});
    }

    @Test
    public void testPackageUninstallWithMultiplePackages() throws Exception {
        final NetdServiceMonitor mNetdServiceMonitor = new NetdServiceMonitor(mNetdService);

        addPackage(MOCK_PACKAGE1, MOCK_UID1, new String[] {INTERNET, UPDATE_DEVICE_STATS});
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET
                | INetd.PERMISSION_UPDATE_DEVICE_STATS, new int[]{MOCK_UID1});

        // Mock another package with the same uid but different permissions.
        PackageInfo packageInfo2 = packageInfoWithPermissions(new String[] {INTERNET},
                PARTITION_SYSTEM);
        when(mPackageManager.getPackageInfo(eq(MOCK_PACKAGE2), anyInt())).thenReturn(packageInfo2);
        when(mPackageManager.getPackagesForUid(MOCK_UID1)).thenReturn(new String[]{
                MOCK_PACKAGE2});

        mObserver.onPackageRemoved(MOCK_PACKAGE1, MOCK_UID1);
        mNetdServiceMonitor.expectPermission(INetd.PERMISSION_INTERNET, new int[]{MOCK_UID1});
    }
}
