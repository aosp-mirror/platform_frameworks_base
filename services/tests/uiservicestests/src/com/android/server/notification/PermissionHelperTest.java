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

import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.permission.PermissionManager.PERMISSION_GRANTED;
import static android.permission.PermissionManager.PERMISSION_SOFT_DENIED;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.permission.IPermissionManager;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        mPermissionHelper = new PermissionHelper(mPmi, mPackageManager, mPermManager, true);
    }

    // TODO (b/194833441): Remove when the migration is enabled
    @Test
    public void testMethodsThrowIfMigrationDisabled() throws IllegalAccessException,
            InvocationTargetException {
        PermissionHelper permHelper =
                new PermissionHelper(mPmi, mPackageManager, mPermManager, false);

        Method[] allMethods = PermissionHelper.class.getDeclaredMethods();
        for (Method method : allMethods) {
            if (Modifier.isPublic(method.getModifiers()) &&
                    !Objects.equals("isMigrationEnabled", method.getName())) {
                Parameter[] params = method.getParameters();
                List<Object> args = Lists.newArrayListWithCapacity(params.length);
                for (int i = 0; i < params.length; i++) {
                    Type type = params[i].getParameterizedType();
                    if (type.getTypeName().equals("java.lang.String")) {
                        args.add("");
                    } else if (type.getTypeName().equals("boolean")){
                        args.add(false);
                    } else if (type.getTypeName().equals("int")) {
                        args.add(1);
                    }
                }
                try {
                    method.invoke(permHelper, args.toArray());
                    fail("Method should have thrown because migration flag is disabled");
                } catch (InvocationTargetException e) {
                    if (!(e.getTargetException() instanceof IllegalStateException)) {
                        throw e;
                    }
                }
            }
        }
    }

    @Test
    public void testHasPermission() throws Exception {
        when(mPmi.checkUidPermission(anyInt(), eq(Manifest.permission.POST_NOTIFICATIONS)))
                .thenReturn(PERMISSION_GRANTED);

        assertThat(mPermissionHelper.hasPermission(1)).isTrue();

        when(mPmi.checkUidPermission(anyInt(), eq(Manifest.permission.POST_NOTIFICATIONS)))
                .thenReturn(PERMISSION_SOFT_DENIED);

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

        Map<Integer, String> expected = ImmutableMap.of(1, "first", 2, "second");

        ParceledListSlice<PackageInfo> infos = new ParceledListSlice<>(
                ImmutableList.of(notThis, none, first, second));
        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS), anyInt())).thenReturn(infos);

        Map<Integer, String> actual = mPermissionHelper.getAppsRequestingPermission(0);

        assertThat(actual).containsExactlyEntriesIn(expected);
    }

    @Test
    public void testGetAppsGrantedPermission_noApps() throws Exception {
        int userId = 1;
        ParceledListSlice<PackageInfo> infos = ParceledListSlice.emptyList();
        when(mPackageManager.getPackagesHoldingPermissions(
                eq(new String[] {Manifest.permission.POST_NOTIFICATIONS}), anyInt(), eq(userId)))
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
                eq(new String[] {Manifest.permission.POST_NOTIFICATIONS}), anyInt(), eq(userId)))
                .thenReturn(infos);

        Map<Integer, String> expected = ImmutableMap.of(1, "first", 2, "second");

        assertThat(mPermissionHelper.getAppsGrantedPermission(userId))
                .containsExactlyEntriesIn(expected);
    }

    @Test
    public void testSetNotificationPermission_grantUserSet() throws Exception {
        mPermissionHelper.setNotificationPermission("pkg", 10, true, true);

        verify(mPermManager).grantRuntimePermission(
                "pkg", Manifest.permission.POST_NOTIFICATIONS, 10);
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_revokeUserSet() throws Exception {
        mPermissionHelper.setNotificationPermission("pkg", 10, false, true);

        verify(mPermManager).revokeRuntimePermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10), anyString());
        verify(mPermManager).updatePermissionFlags("pkg", Manifest.permission.POST_NOTIFICATIONS,
                FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET, true, 10);
    }

    @Test
    public void testSetNotificationPermission_grantNotUserSet() throws Exception {
        mPermissionHelper.setNotificationPermission("pkg", 10, true, false);

        verify(mPermManager).grantRuntimePermission(
                "pkg", Manifest.permission.POST_NOTIFICATIONS, 10);
        verify(mPermManager, never()).updatePermissionFlags(
                anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), anyInt());
    }

    @Test
    public void testSetNotificationPermission_revokeNotUserSet() throws Exception {
        mPermissionHelper.setNotificationPermission("pkg", 10, false, false);

        verify(mPermManager).revokeRuntimePermission(
                eq("pkg"), eq(Manifest.permission.POST_NOTIFICATIONS), eq(10), anyString());
        verify(mPermManager, never()).updatePermissionFlags(
                anyString(), anyString(), anyInt(), anyInt(), anyBoolean(), anyInt());
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
}
