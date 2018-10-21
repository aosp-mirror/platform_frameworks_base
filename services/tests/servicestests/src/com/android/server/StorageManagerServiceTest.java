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
 * limitations under the License.
 */
package com.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.storage.StorageManagerInternal;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StorageManagerServiceTest {

    private StorageManagerService mService;

    @Mock private Context mContext;
    @Mock private PackageManager mPm;
    @Mock private PackageManagerInternal mPmi;
    @Mock private UserManagerInternal mUmi;

    private static final String PKG_GREY = "com.grey";
    private static final String PKG_RED = "com.red";
    private static final String PKG_BLUE = "com.blue";

    private static final int UID_GREY = 10000;
    private static final int UID_COLORS = 10001;

    private static final String NAME_COLORS = "colors";

    private static ApplicationInfo buildApplicationInfo(String packageName, int uid) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.uid = uid;
        return ai;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(StorageManagerInternal.class);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPmi);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mUmi);

        when(mContext.getPackageManager()).thenReturn(mPm);

        when(mUmi.getUserIds()).thenReturn(new int[] { 0 });

        {
            final SparseArray<String> res = new SparseArray<>();
            res.put(UID_COLORS, NAME_COLORS);
            when(mPmi.getAppsWithSharedUserIds()).thenReturn(res);
        }

        {
            final List<ApplicationInfo> res = new ArrayList<>();
            res.add(buildApplicationInfo(PKG_GREY, UID_GREY));
            res.add(buildApplicationInfo(PKG_RED, UID_COLORS));
            res.add(buildApplicationInfo(PKG_BLUE, UID_COLORS));
            when(mPm.getInstalledApplicationsAsUser(anyInt(), anyInt())).thenReturn(res);
        }

        when(mPmi.getPackageUid(eq(PKG_GREY), anyInt(), anyInt())).thenReturn(UID_GREY);
        when(mPmi.getPackageUid(eq(PKG_RED), anyInt(), anyInt())).thenReturn(UID_COLORS);
        when(mPmi.getPackageUid(eq(PKG_BLUE), anyInt(), anyInt())).thenReturn(UID_COLORS);

        when(mPm.getPackagesForUid(eq(UID_GREY))).thenReturn(new String[] { PKG_GREY });
        when(mPm.getPackagesForUid(eq(UID_COLORS))).thenReturn(new String[] { PKG_RED, PKG_BLUE });

        mService = new StorageManagerService(mContext);
        mService.collectPackagesInfo();
    }

    @Test
    public void testNone() throws Exception {
        assertTranslation(
                "/dev/null",
                "/dev/null", PKG_GREY);
        assertTranslation(
                "/dev/null",
                "/dev/null", PKG_RED);
    }

    @Test
    public void testPrimary() throws Exception {
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/foo.jpg",
                "/storage/emulated/0/foo.jpg", PKG_GREY);
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/shared:colors/foo.jpg",
                "/storage/emulated/0/foo.jpg", PKG_RED);
    }

    @Test
    public void testSecondary() throws Exception {
        assertTranslation(
                "/storage/0000-0000/Android/sandbox/com.grey/foo/bar.jpg",
                "/storage/0000-0000/foo/bar.jpg", PKG_GREY);
        assertTranslation(
                "/storage/0000-0000/Android/sandbox/shared:colors/foo/bar.jpg",
                "/storage/0000-0000/foo/bar.jpg", PKG_RED);
    }

    @Test
    public void testLegacy() throws Exception {
        // Accessing their own paths goes straight through
        assertTranslation(
                "/storage/emulated/0/Android/data/com.grey/foo.jpg",
                "/storage/emulated/0/Android/data/com.grey/foo.jpg", PKG_GREY);

        // Accessing other package paths goes into sandbox
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/shared:colors/"
                        + "Android/data/com.grey/foo.jpg",
                "/storage/emulated/0/Android/data/com.grey/foo.jpg", PKG_RED);
    }

    @Test
    public void testLegacyShared() throws Exception {
        // Accessing their own paths goes straight through
        assertTranslation(
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg", PKG_RED);
        assertTranslation(
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg", PKG_BLUE);

        // Accessing other package paths goes into sandbox
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/"
                        + "Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg", PKG_GREY);
    }

    @Test
    public void testSecurity() throws Exception {
        // Shady paths should throw
        try {
            mService.translateAppToSystem(
                    "/storage/emulated/0/../foo.jpg",
                    PKG_GREY, UserHandle.USER_SYSTEM);
            fail();
        } catch (SecurityException expected) {
        }

        // Sandboxes can't see system paths
        try {
            mService.translateSystemToApp(
                    "/storage/emulated/0/foo.jpg",
                    PKG_GREY, UserHandle.USER_SYSTEM);
            fail();
        } catch (SecurityException expected) {
        }

        // Sandboxes can't see paths in other sandboxes
        try {
            mService.translateSystemToApp(
                    "/storage/emulated/0/Android/sandbox/shared:colors/foo.jpg",
                    PKG_GREY, UserHandle.USER_SYSTEM);
            fail();
        } catch (SecurityException expected) {
        }
    }

    private void assertTranslation(String system, String sandbox, String packageName)
            throws Exception {
        assertEquals(system,
                mService.translateAppToSystem(sandbox, packageName, UserHandle.USER_SYSTEM));
        assertEquals(sandbox,
                mService.translateSystemToApp(system, packageName, UserHandle.USER_SYSTEM));
    }
}
