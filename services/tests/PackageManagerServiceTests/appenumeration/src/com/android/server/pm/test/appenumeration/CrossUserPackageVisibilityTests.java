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

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.app.AppGlobals;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.KeySet;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureHasSecondaryUser;
import com.android.bedstead.nene.users.UserReference;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

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
    private static final File CROSS_USER_TEST_APK_FILE =
            new File(TEST_DATA_DIR, "AppEnumerationCrossUserPackageVisibilityTestApp.apk");

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private Instrumentation mInstrumentation;
    private IPackageManager mIPackageManager;
    private Context mContext;
    private UserReference mOtherUser;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mIPackageManager = AppGlobals.getPackageManager();
        mContext = mInstrumentation.getContext();

        // Get another user
        final UserReference primaryUser = sDeviceState.primaryUser();
        if (primaryUser.id() == UserHandle.myUserId()) {
            mOtherUser = sDeviceState.secondaryUser();
        } else {
            mOtherUser = primaryUser;
        }

        uninstallPackage(CROSS_USER_TEST_PACKAGE_NAME);
    }

    @After
    public void tearDown() {
        uninstallPackage(CROSS_USER_TEST_PACKAGE_NAME);
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

    private static void installPackageForUser(File apk, UserReference user) {
        assertThat(apk.exists()).isTrue();
        final StringBuilder cmd = new StringBuilder("pm install --user ");
        cmd.append(user.id()).append(" ");
        cmd.append(apk.getPath());
        final String result = runShellCommand(cmd.toString());
        assertThat(result.trim()).contains("Success");
    }

    private static void uninstallPackage(String packageName) {
        runShellCommand("pm uninstall " + packageName);
    }
}
