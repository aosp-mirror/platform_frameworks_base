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

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.UserManagerInternal;
import android.os.storage.StorageManagerInternal;

import com.android.internal.os.Zygote;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StorageManagerServiceTest {

    private StorageManagerService mService;

    @Mock private Context mContext;
    @Mock private PackageManager mPm;
    @Mock private PackageManagerInternal mPmi;
    @Mock private UserManagerInternal mUmi;
    @Mock private ActivityManagerInternal mAmi;

    private static final String PKG_GREY = "com.grey";
    private static final String PKG_RED = "com.red";
    private static final String PKG_BLUE = "com.blue";

    private static final int UID_GREY = 10000;
    private static final int UID_COLORS = 10001;

    private static final int PID_GREY = 1111;
    private static final int PID_RED = 2222;
    private static final int PID_BLUE = 3333;

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
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmi);

        when(mContext.getPackageManager()).thenReturn(mPm);

        when(mUmi.getUserIds()).thenReturn(new int[] { 0 });

        when(mPmi.getSharedUserIdForPackage(eq(PKG_GREY))).thenReturn(null);
        when(mPmi.getSharedUserIdForPackage(eq(PKG_RED))).thenReturn(NAME_COLORS);
        when(mPmi.getSharedUserIdForPackage(eq(PKG_BLUE))).thenReturn(NAME_COLORS);

        when(mPmi.getPackagesForSharedUserId(eq(NAME_COLORS), anyInt()))
                .thenReturn(new String[] { PKG_RED, PKG_BLUE });

        when(mPm.getPackagesForUid(eq(UID_GREY))).thenReturn(new String[] { PKG_GREY });
        when(mPm.getPackagesForUid(eq(UID_COLORS))).thenReturn(new String[] { PKG_RED, PKG_BLUE });

        setStorageMountMode(PID_BLUE, UID_COLORS, Zygote.MOUNT_EXTERNAL_WRITE);
        setStorageMountMode(PID_GREY, UID_GREY, Zygote.MOUNT_EXTERNAL_WRITE);
        setStorageMountMode(PID_RED, UID_COLORS, Zygote.MOUNT_EXTERNAL_WRITE);

        mService = new StorageManagerService(mContext);
    }

    private void setStorageMountMode(int pid, int uid, int mountMode) {
        when(mAmi.getStorageMountMode(pid, uid)).thenReturn(mountMode);
    }

    @Test
    public void testNone() throws Exception {
        assertTranslation(
                "/dev/null",
                "/dev/null", PID_GREY, UID_GREY);
        assertTranslation(
                "/dev/null",
                "/dev/null", PID_RED, UID_COLORS);
    }

    @Test
    public void testPrimary() throws Exception {
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/foo.jpg",
                "/storage/emulated/0/foo.jpg",
                PID_GREY, UID_GREY);
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/shared-colors/foo.jpg",
                "/storage/emulated/0/foo.jpg",
                PID_RED, UID_COLORS);
    }

    @Test
    public void testSecondary() throws Exception {
        assertTranslation(
                "/storage/0000-0000/Android/sandbox/com.grey/foo/bar.jpg",
                "/storage/0000-0000/foo/bar.jpg",
                PID_GREY, UID_GREY);
        assertTranslation(
                "/storage/0000-0000/Android/sandbox/shared-colors/foo/bar.jpg",
                "/storage/0000-0000/foo/bar.jpg",
                PID_RED, UID_COLORS);
    }

    @Test
    public void testLegacy() throws Exception {
        // Accessing their own paths goes straight through
        assertTranslation(
                "/storage/emulated/0/Android/data/com.grey/foo.jpg",
                "/storage/emulated/0/Android/data/com.grey/foo.jpg",
                PID_GREY, UID_GREY);

        // Accessing other package paths goes into sandbox
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/shared-colors/"
                        + "Android/data/com.grey/foo.jpg",
                "/storage/emulated/0/Android/data/com.grey/foo.jpg",
                PID_RED, UID_COLORS);
    }

    @Test
    public void testLegacyShared() throws Exception {
        // Accessing their own paths goes straight through
        assertTranslation(
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                PID_RED, UID_COLORS);
        assertTranslation(
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                PID_BLUE, UID_COLORS);

        // Accessing other package paths goes into sandbox
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/"
                        + "Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                PID_GREY, UID_GREY);
    }

    @Test
    public void testSecurity() throws Exception {
        // Shady paths should throw
        try {
            mService.translateAppToSystem(
                    "/storage/emulated/0/../foo.jpg",
                    PID_GREY, UID_GREY);
            fail();
        } catch (SecurityException expected) {
        }

        // Sandboxes can't see system paths
        try {
            mService.translateSystemToApp(
                    "/storage/emulated/0/foo.jpg",
                    PID_GREY, UID_GREY);
            fail();
        } catch (SecurityException expected) {
        }

        // Sandboxes can't see paths in other sandboxes
        try {
            mService.translateSystemToApp(
                    "/storage/emulated/0/Android/sandbox/shared-colors/foo.jpg",
                    PID_GREY, UID_GREY);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testPackageNotSandboxed() throws Exception {
        setStorageMountMode(PID_RED, UID_COLORS, Zygote.MOUNT_EXTERNAL_FULL);

        // Both app and system have the same view
        assertTranslation(
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                PID_RED, UID_COLORS);

        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/bar.jpg",
                "/storage/emulated/0/Android/sandbox/com.grey/bar.jpg",
                PID_RED, UID_COLORS);
    }

    @Test
    public void testPackageInLegacyMode() throws Exception {
        setStorageMountMode(PID_RED, UID_COLORS, Zygote.MOUNT_EXTERNAL_LEGACY);

        // Both app and system have the same view
        assertTranslation(
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                "/storage/emulated/0/Android/data/com.red/foo.jpg",
                PID_RED, UID_COLORS);

        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/bar.jpg",
                "/storage/emulated/0/Android/sandbox/com.grey/bar.jpg",
                PID_RED, UID_COLORS);
    }

    @Test
    public void testInstallerPackage() throws Exception {
        setStorageMountMode(PID_GREY, UID_GREY, Zygote.MOUNT_EXTERNAL_INSTALLER);

        assertTranslation(
                "/storage/emulated/0/Android/obb/com.grey/foo.jpg",
                "/storage/emulated/0/Android/obb/com.grey/foo.jpg",
                PID_GREY, UID_GREY);
        assertTranslation(
                "/storage/emulated/0/Android/obb/com.blue/bar.jpg",
                "/storage/emulated/0/Android/obb/com.blue/bar.jpg",
                PID_GREY, UID_GREY);

        assertTranslation(
                "/storage/emulated/0/Android/data/com.grey/foo.jpg",
                "/storage/emulated/0/Android/data/com.grey/foo.jpg",
                PID_GREY, UID_GREY);
        assertTranslation(
                "/storage/emulated/0/Android/sandbox/com.grey/Android/data/com.blue/bar.jpg",
                "/storage/emulated/0/Android/data/com.blue/bar.jpg",
                PID_GREY, UID_GREY);
    }

    private void assertTranslation(String system, String sandbox,
            int pid, int uid) throws Exception {
        assertEquals(system,
                mService.translateAppToSystem(sandbox, pid, uid));
        assertEquals(sandbox,
                mService.translateSystemToApp(system, pid, uid));
    }
}
