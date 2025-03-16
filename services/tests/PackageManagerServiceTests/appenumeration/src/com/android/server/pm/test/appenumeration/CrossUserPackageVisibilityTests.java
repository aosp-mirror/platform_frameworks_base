/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.test.appenumeration;

import static android.Manifest.permission.CLEAR_APP_USER_DATA;
import static android.Manifest.permission.DELETE_PACKAGES;
import static android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS;
import static android.Manifest.permission.MOVE_PACKAGE;
import static android.content.pm.PackageManager.MOVE_FAILED_DOESNT_EXIST;

import static com.android.bedstead.multiuser.MultiUserDeviceStateExtensionsKt.secondaryUser;
import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.AppGlobals;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.KeySet;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.multiuser.annotations.EnsureHasSecondaryUser;
import com.android.bedstead.nene.users.UserReference;
import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verify that app without holding the {@link android.Manifest.permission.INTERACT_ACROSS_USERS}
 * can't detect the existence of another app in the different users on the device via the
 * side channel attacks.
 */
@EnsureHasSecondaryUser
@RunWith(BedsteadJUnit4.class)
public class CrossUserPackageVisibilityTests {
    private static final String TEST_DATA_DIR = "/data/local/tmp/appenumerationtests";
    private static final String CROSS_USER_TEST_PACKAGE_NAME =
            "com.android.appenumeration.crossuserpackagevisibility";
    private static final String SHARED_USER_TEST_PACKAGE_NAME =
            "com.android.appenumeration.shareduid";
    private static final String HAS_APPOP_PERMISSION_PACKAGE_NAME =
            "com.android.appenumeration.hasappoppermission";

    private static final File CROSS_USER_TEST_APK_FILE =
            new File(TEST_DATA_DIR, "AppEnumerationCrossUserPackageVisibilityTestApp.apk");
    private static final File SHARED_USER_TEST_APK_FILE =
            new File(TEST_DATA_DIR, "AppEnumerationSharedUserTestApp.apk");
    private static final File HAS_APPOP_PERMISSION_APK_FILE =
            new File(TEST_DATA_DIR, "AppEnumerationHasAppOpPermissionTestApp.apk");

    private static final String ACTION_CROSS_USER_TEST =
            "com.android.appenumeration.action.CROSS_USER_TEST";
    private static final String PERMISSION_REQUEST_INSTALL_PACKAGES =
            "android.permission.REQUEST_INSTALL_PACKAGES";
    private static final ComponentName TEST_ACTIVITY_COMPONENT_NAME = new ComponentName(
            CROSS_USER_TEST_PACKAGE_NAME, "com.android.appenumeration.testapp.DummyActivity");

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private Instrumentation mInstrumentation;
    private IPackageManager mIPackageManager;
    private Context mContext;
    private UserReference mCurrentUser;
    private UserReference mOtherUser;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mIPackageManager = AppGlobals.getPackageManager();
        mContext = mInstrumentation.getContext();

        // Get another user
        final UserReference primaryUser = sDeviceState.primaryUser();
        if (primaryUser.id() == UserHandle.myUserId()) {
            mCurrentUser = primaryUser;
            mOtherUser = secondaryUser(sDeviceState);
        } else {
            mCurrentUser = secondaryUser(sDeviceState);
            mOtherUser = primaryUser;
        }

        uninstallPackage(CROSS_USER_TEST_PACKAGE_NAME);
        uninstallPackage(SHARED_USER_TEST_PACKAGE_NAME);
    }

    @After
    public void tearDown() {
        uninstallPackage(CROSS_USER_TEST_PACKAGE_NAME);
        uninstallPackage(SHARED_USER_TEST_PACKAGE_NAME);
        mInstrumentation.getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testGetSplashScreenTheme_withCrossUserId() {
        final int crossUserId = UserHandle.myUserId() + 1;
        assertThrows(SecurityException.class,
                () -> mIPackageManager.getSplashScreenTheme(
                        mInstrumentation.getContext().getPackageName(), crossUserId));
    }

    @Test
    public void testIsPackageSignedByKeySet_cannotDetectCrossUserPkg() throws Exception {
        final KeySet keySet = mIPackageManager.getSigningKeySet(mContext.getPackageName());
        assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.isPackageSignedByKeySet(
                        CROSS_USER_TEST_PACKAGE_NAME, keySet));

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.isPackageSignedByKeySet(
                        CROSS_USER_TEST_PACKAGE_NAME, keySet));
    }

    @Test
    public void testIsPackageSignedByKeySetExactly_cannotDetectCrossUserPkg() throws Exception {
        final KeySet keySet = mIPackageManager.getSigningKeySet(mContext.getPackageName());
        assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.isPackageSignedByKeySetExactly(
                        CROSS_USER_TEST_PACKAGE_NAME, keySet));

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.isPackageSignedByKeySetExactly(
                        CROSS_USER_TEST_PACKAGE_NAME, keySet));
    }

    @Test
    public void testGetSigningKeySet_cannotDetectCrossUserPkg() {
        final IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.getSigningKeySet(CROSS_USER_TEST_PACKAGE_NAME));

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        final IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.getSigningKeySet(CROSS_USER_TEST_PACKAGE_NAME));
        assertThat(e1.getMessage()).isEqualTo(e2.getMessage());
    }

    @Test
    public void testGetKeySetByAlias_cannotDetectCrossUserPkg() {
        final String alias = CROSS_USER_TEST_PACKAGE_NAME + ".alias";
        final IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.getKeySetByAlias(CROSS_USER_TEST_PACKAGE_NAME, alias));

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        final IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> mIPackageManager.getKeySetByAlias(CROSS_USER_TEST_PACKAGE_NAME, alias));
        assertThat(e1.getMessage()).isEqualTo(e2.getMessage());
    }

    @Test
    public void testGetFlagsForUid_cannotDetectCrossUserPkg() throws Exception {
        installPackage(CROSS_USER_TEST_APK_FILE);
        final int uid = mContext.getPackageManager().getPackageUid(
                CROSS_USER_TEST_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0));

        uninstallPackageForUser(CROSS_USER_TEST_PACKAGE_NAME, mCurrentUser);

        assertThat(mIPackageManager.getFlagsForUid(uid)).isEqualTo(0);
    }

    @Test
    public void testGetUidForSharedUser_cannotDetectSharedUserPkg() throws Exception {
        assertThat(mIPackageManager.getUidForSharedUser(SHARED_USER_TEST_PACKAGE_NAME))
                .isEqualTo(Process.INVALID_UID);

        installPackageForUser(SHARED_USER_TEST_APK_FILE, mOtherUser, true /* forceQueryable */);

        assertThat(mIPackageManager.getUidForSharedUser(SHARED_USER_TEST_PACKAGE_NAME))
                .isEqualTo(Process.INVALID_UID);
    }

    @Test
    public void testClearApplicationUserData_cannotDetectStubPkg() throws Exception {
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity(CLEAR_APP_USER_DATA);
        assertThat(clearApplicationUserData(CROSS_USER_TEST_PACKAGE_NAME)).isFalse();

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        assertThat(clearApplicationUserData(CROSS_USER_TEST_PACKAGE_NAME)).isFalse();
    }

    @Test
    public void testGetBlockUninstallForUser_cannotDetectStubPkg() throws Exception {
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity(DELETE_PACKAGES);
        assertThat(mIPackageManager.setBlockUninstallForUser(
                CROSS_USER_TEST_PACKAGE_NAME, true, mCurrentUser.id())).isTrue();
        try {
            assertThat(mIPackageManager.getBlockUninstallForUser(
                    CROSS_USER_TEST_PACKAGE_NAME, mCurrentUser.id())).isFalse();

            installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

            assertThat(mIPackageManager.getBlockUninstallForUser(
                    CROSS_USER_TEST_PACKAGE_NAME, mCurrentUser.id())).isFalse();
        } finally {
            assertThat(mIPackageManager.setBlockUninstallForUser(
                    CROSS_USER_TEST_PACKAGE_NAME, false, mCurrentUser.id())).isTrue();
        }
    }

    @Test
    public void testMovePackage_cannotDetectStubPkg() throws Exception {
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity(
                MOVE_PACKAGE, MOUNT_UNMOUNT_FILESYSTEMS);
        assertThat(movePackage(CROSS_USER_TEST_PACKAGE_NAME, null /* volumeUuid */))
                .isEqualTo(MOVE_FAILED_DOESNT_EXIST);

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        assertThat(movePackage(CROSS_USER_TEST_PACKAGE_NAME, null /* volumeUuid */))
                .isEqualTo(MOVE_FAILED_DOESNT_EXIST);
    }

    @Test
    public void testActivitySupportsIntentAsUser_cannotDetectStubPkg() throws Exception {
        assertThat(mIPackageManager.activitySupportsIntentAsUser(
                TEST_ACTIVITY_COMPONENT_NAME,
                new Intent(ACTION_CROSS_USER_TEST),
                null,
                mCurrentUser.id())).isFalse();

        installPackageForUser(CROSS_USER_TEST_APK_FILE, mOtherUser);

        assertThat(mIPackageManager.activitySupportsIntentAsUser(
                TEST_ACTIVITY_COMPONENT_NAME,
                new Intent(ACTION_CROSS_USER_TEST),
                null,
                mCurrentUser.id())).isFalse();
    }

    @Test
    public void testGetAppOpPermissionPackages_cannotDetectPkg() throws Exception {
        final int userId = mCurrentUser.id();
        assertThat(mIPackageManager
                .getAppOpPermissionPackages(PERMISSION_REQUEST_INSTALL_PACKAGES, userId))
                .asList().doesNotContain(HAS_APPOP_PERMISSION_PACKAGE_NAME);

        installPackageForUser(HAS_APPOP_PERMISSION_APK_FILE, mOtherUser, true /* forceQueryable */);

        assertThat(mIPackageManager
                .getAppOpPermissionPackages(PERMISSION_REQUEST_INSTALL_PACKAGES, userId))
                .asList().doesNotContain(HAS_APPOP_PERMISSION_PACKAGE_NAME);
    }

    private boolean clearApplicationUserData(String packageName) throws Exception {
        final AtomicInteger result = new AtomicInteger(-1);
        final IPackageDataObserver localObserver = new IPackageDataObserver.Stub() {
            @Override
            public void onRemoveCompleted(String removedPkgName, boolean succeeded)
                    throws RemoteException {
                if (removedPkgName.equals(packageName)) {
                    result.set(succeeded ? 1 : 0);
                    result.notifyAll();
                }
            }
        };
        mIPackageManager.clearApplicationUserData(packageName, localObserver, mCurrentUser.id());
        TestUtils.waitOn(result, () -> result.get() != -1, DEFAULT_TIMEOUT_MS,
                "clearApplicationUserData: " + packageName);
        return result.get() == 1;
    }

    private int movePackage(String packageName, String volumeUuid) throws Exception {
        final int moveId = mIPackageManager.movePackage(packageName, volumeUuid);
        PollingCheck.check(
                "Waiting for the package " + packageName + " moving timeout",
                DEFAULT_TIMEOUT_MS,
                () -> PackageManager.isMoveStatusFinished(mIPackageManager.getMoveStatus(moveId)));
        return mIPackageManager.getMoveStatus(moveId);
    }

    private static void installPackage(File apk) {
        installPackageForUser(apk, null, false /* forceQueryable */);
    }

    private static void installPackageForUser(File apk, UserReference user) {
        installPackageForUser(apk, user, false /* forceQueryable */);
    }

    private static void installPackageForUser(File apk, UserReference user,
            boolean forceQueryable) {
        assertThat(apk.exists()).isTrue();
        final StringBuilder cmd = new StringBuilder("pm install -t ");
        if (forceQueryable) {
            cmd.append("--force-queryable ");
        }
        if (user != null) {
            cmd.append("--user ").append(user.id()).append(" ");
        }
        cmd.append(apk.getPath());
        final String result = runShellCommand(cmd.toString());
        assertThat(result.trim()).contains("Success");
    }

    private static void uninstallPackage(String packageName) {
        uninstallPackageForUser(packageName, null /* user */);
    }

    private static void uninstallPackageForUser(String packageName, UserReference user) {
        final StringBuilder cmd = new StringBuilder("pm uninstall ");
        if (user != null) {
            cmd.append("--user ").append(user.id()).append(" ");
        }
        cmd.append(packageName);
        runShellCommand(cmd.toString());
    }
}
