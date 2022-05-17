/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.notification;

import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ParceledListSlice;
import android.permission.IPermissionManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PermissionHelperTest extends UiServiceTestCase {

    @Mock
    private PermissionManagerServiceInternal mPmi;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private IPermissionManager mPermManager;

    private PermissionHelper mPermissionHelper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPermissionHelper = new PermissionHelper(mPmi, mPackageManager, mPermManager);
        PackageInfo testPkgInfo = new PackageInfo();
        testPkgInfo.requestedPermissions = new String[]{ Manifest.permission.POST_NOTIFICATIONS };
        when(mPackageManager.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenReturn(testPkgInfo);
    }

    @Test
    public void testHasPermission() throws Exception {
        when(mPmi.checkUidPermission(anyInt(), anyString()))
                .thenReturn(PERMISSION_GRANTED);

        assertThat(mPermissionHelper.hasPermission(1)).isTrue();

        when(mPmi.checkUidPermission(anyInt(), anyString()))
                .thenReturn(PERMISSION_DENIED);

        assertThat(mPermissionHelper.hasPermission(1)).isFalse();
    }

    @Test
    public void testGetAppsRequestingPermission() throws Exception {
        // App that does not request permission
        PackageInfo notThis = new PackageInfo();
        notThis.packageName = "wrong.permission";
        notThis.requestedPermissions = new String[] {"something else"};
        // App that does not request any permissions (null check
        PackageInfo none = new PackageInfo();
        none.packageName = "no.permissions";
        // 2 apps that request the permission
        PackageInfo first = new PackageInfo();
        first.packageName = "first";
        first.requestedPermissions =
                new String[] {"something else", Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiFirst = new ApplicationInfo();
        aiFirst.uid = 1;
        first.applicationInfo = aiFirst;
        PackageInfo second = new PackageInfo();
        second.packageName = "second";
        second.requestedPermissions = new String[] {Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiSecond = new ApplicationInfo();
        aiSecond.uid = 2;
        second.applicationInfo = aiSecond;

        Set<Pair<Integer, String>> expected =
                ImmutableSet.of(new Pair(1, "first"), new Pair(2, "second"));

        ParceledListSlice<PackageInfo> infos = new ParceledListSlice<>(
                ImmutableList.of(notThis, none, first, second));
        when(mPackageManager.getInstalledPackages(eq((long) GET_PERMISSIONS), anyInt()))
                .thenReturn(infos);

        Set<Pair<Integer, String>> actual = mPermissionHelper.getAppsRequestingPermission(0);

        assertThat(actual).containsExactlyElementsIn(expected);
    }

    @Test
    public void testGetAppsGrantedPermission_noApps() throws Exception {
        int userId = 1;
        ParceledListSlice<PackageInfo> infos = ParceledListSlice.emptyList();
        when(mPackageManager.getPackagesHoldingPermissions(
                eq(new String[] {Manifest.permission.POST_NOTIFICATIONS}), anyLong(), eq(userId)))
                .thenReturn(infos);
        assertThat(mPermissionHelper.getAppsGrantedPermission(userId)).isNotNull();
    }

    @Test
    public void testGetAppsGrantedPermission() throws Exception {
        int userId = 1;
        PackageInfo first = new PackageInfo();
        first.packageName = "first";
        first.requestedPermissions =
                new String[] {"something else", Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiFirst = new ApplicationInfo();
        aiFirst.uid = 1;
        first.applicationInfo = aiFirst;
        PackageInfo second = new PackageInfo();
        second.packageName = "second";
        second.requestedPermissions = new String[] {Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiSecond = new ApplicationInfo();
        aiSecond.uid = 2;
        second.applicationInfo = aiSecond;

        ParceledListSlice<PackageInfo> infos = new ParceledListSlice<>(
                ImmutableList.of(first, second));
        when(mPackageManager.getPackagesHoldingPermissions(
                eq(new String[] {Manifest.permission.POST_NOTIFICATIONS}), anyLong(), eq(userId)))
                .thenReturn(infos);

        Set<Pair<Integer, String>> expected =
                ImmutableSet.of(new Pair(1, "first"), new Pair(2, "second"));

        assertThat(mPermissionHelper.getAppsGrantedPermission(userId))
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testSetNotificationPermission_grantUserSet() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_DENIED);
        mPermissionHelper.setNotificationPermission("pkg", 10, true, true);

        verify(mPermManager).grantRuntimePermission(
                "pkg", Manifest.permission.POST_NOTIFICATIONS, 10);
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_pkgPerm_grantedByDefaultPermSet_allUserSet()
            throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_DENIED);
        when(mPermManager.getPermissionFlags(anyString(),
                eq(Manifest.permission.POST_NOTIFICATIONS),
                anyInt())).thenReturn(FLAG_PERMISSION_GRANTED_BY_DEFAULT);
        PermissionHelper.PackagePermission pkgPerm = new PermissionHelper.PackagePermission(
                "pkg", 10, true, false);

        mPermissionHelper.setNotificationPermission(pkgPerm);
        verify(mPermManager).grantRuntimePermission(
                "pkg", Manifest.permission.POST_NOTIFICATIONS, 10);
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_revokeUserSet() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_GRANTED);

        mPermissionHelper.setNotificationPermission("pkg", 10, false, true);

        verify(mPermManager).revokeRuntimePermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10), anyString());
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_grantNotUserSet() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_DENIED);

        mPermissionHelper.setNotificationPermission("pkg", 10, true, false);

        verify(mPermManager).grantRuntimePermission(
                "pkg", Manifest.permission.POST_NOTIFICATIONS, 10);
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                0, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_revokeNotUserSet() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_GRANTED);

        mPermissionHelper.setNotificationPermission("pkg", 10, false, false);

        verify(mPermManager).revokeRuntimePermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10), anyString());
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                0, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_SystemFixedPermNotSet() throws Exception {
        when(mPermManager.getPermissionFlags(anyString(),
                eq(Manifest.permission.POST_NOTIFICATIONS),
                anyInt())).thenReturn(FLAG_PERMISSION_SYSTEM_FIXED);

        mPermissionHelper.setNotificationPermission("pkg", 10, false, true);
        verify(mPermManager, never()).revokeRuntimePermission(
                anyString(), anyString(), anyInt(), anyString());
        verify(mPermManager, never()).updatePermissionFlags(
                anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), anyInt());
    }

    @Test
    public void testSetNotificationPermission_PolicyFixedPermNotSet() throws Exception {
        when(mPermManager.getPermissionFlags(anyString(),
                eq(Manifest.permission.POST_NOTIFICATIONS),
                anyInt())).thenReturn(FLAG_PERMISSION_POLICY_FIXED);

        mPermissionHelper.setNotificationPermission("pkg", 10, false, true);
        verify(mPermManager, never()).revokeRuntimePermission(
                anyString(), anyString(), anyInt(), anyString());
        verify(mPermManager, never()).updatePermissionFlags(
                anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), anyInt());
    }

    @Test
    public void testSetNotificationPermission_alreadyGrantedNotRegranted() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_GRANTED);
        mPermissionHelper.setNotificationPermission("pkg", 10, true, false);

        verify(mPermManager, never()).grantRuntimePermission(
                "pkg", Manifest.permission.POST_NOTIFICATIONS, 10);
    }

    @Test
    public void testSetNotificationPermission_alreadyRevokedNotRerevoked() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_DENIED);
        mPermissionHelper.setNotificationPermission("pkg", 10, false, false);

        verify(mPermManager, never()).revokeRuntimePermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10), anyString());
    }

    @Test
    public void testSetNotificationPermission_doesntRequestNotChanged() throws Exception {
        when(mPmi.checkPermission(anyString(), anyString(), anyInt()))
                .thenReturn(PERMISSION_GRANTED);
        PackageInfo testPkgInfo = new PackageInfo();
        testPkgInfo.requestedPermissions = new String[]{ Manifest.permission.RECORD_AUDIO };
        when(mPackageManager.getPackageInfo(anyString(), anyLong(), anyInt()))
                .thenReturn(testPkgInfo);
        mPermissionHelper.setNotificationPermission("pkg", 10, false, false);

        verify(mPmi, never()).checkPermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10));
        verify(mPermManager, never()).revokeRuntimePermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10), anyString());
    }

    @Test
    public void testIsPermissionFixed() throws Exception {
        when(mPermManager.getPermissionFlags(anyString(),
                eq(Manifest.permission.POST_NOTIFICATIONS),
                anyInt())).thenReturn(FLAG_PERMISSION_USER_SET);

        assertThat(mPermissionHelper.isPermissionFixed("pkg", 0)).isFalse();

        when(mPermManager.getPermissionFlags(anyString(),
                eq(Manifest.permission.POST_NOTIFICATIONS),
                anyInt())).thenReturn(FLAG_PERMISSION_USER_SET|FLAG_PERMISSION_POLICY_FIXED);

        assertThat(mPermissionHelper.isPermissionFixed("pkg", 0)).isTrue();

        when(mPermManager.getPermissionFlags(anyString(),
                eq(Manifest.permission.POST_NOTIFICATIONS),
                anyInt())).thenReturn(FLAG_PERMISSION_SYSTEM_FIXED);

        assertThat(mPermissionHelper.isPermissionFixed("pkg", 0)).isTrue();
    }

    @Test
    public void testGetNotificationPermissionValues() throws Exception {
        int userId = 1;
        PackageInfo first = new PackageInfo();
        first.packageName = "first";
        first.requestedPermissions =
                new String[] {"something else", Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiFirst = new ApplicationInfo();
        aiFirst.uid = 1;
        first.applicationInfo = aiFirst;

        PackageInfo second = new PackageInfo();
        second.packageName = "second";
        second.requestedPermissions = new String[] {Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiSecond = new ApplicationInfo();
        aiSecond.uid = 2;
        second.applicationInfo = aiSecond;

        PackageInfo third = new PackageInfo();
        third.packageName = "third";
        third.requestedPermissions = new String[] {Manifest.permission.POST_NOTIFICATIONS};
        ApplicationInfo aiThird = new ApplicationInfo();
        aiThird.uid = 3;
        third.applicationInfo = aiThird;

        ParceledListSlice<PackageInfo> infos = new ParceledListSlice<>(
                ImmutableList.of(first, second));
        when(mPackageManager.getPackagesHoldingPermissions(
                eq(new String[] {Manifest.permission.POST_NOTIFICATIONS}), anyLong(), eq(userId)))
                .thenReturn(infos);
        ParceledListSlice<PackageInfo> requesting = new ParceledListSlice<>(
                ImmutableList.of(first, second, third));
        when(mPackageManager.getInstalledPackages(eq((long) GET_PERMISSIONS), anyInt()))
                .thenReturn(requesting);

        // 2 and 3 are user-set permissions
        when(mPermManager.getPermissionFlags(
                "first", Manifest.permission.POST_NOTIFICATIONS, userId)).thenReturn(0);
        when(mPermManager.getPermissionFlags(
                "second", Manifest.permission.POST_NOTIFICATIONS, userId))
                .thenReturn(FLAG_PERMISSION_USER_SET);
        when(mPermManager.getPermissionFlags(
                "third", Manifest.permission.POST_NOTIFICATIONS, userId))
                .thenReturn(FLAG_PERMISSION_USER_SET);

        Map<Pair<Integer, String>, Pair<Boolean, Boolean>> expected =
                ImmutableMap.of(new Pair(1, "first"), new Pair(true, false),
                    new Pair(2, "second"), new Pair(true, true),
                    new Pair(3, "third"), new Pair(false, true));

        Map<Pair<Integer, String>, Pair<Boolean, Boolean>> actual =
                mPermissionHelper.getNotificationPermissionValues(userId);

        assertThat(actual).containsExactlyEntriesIn(expected);
    }
}
