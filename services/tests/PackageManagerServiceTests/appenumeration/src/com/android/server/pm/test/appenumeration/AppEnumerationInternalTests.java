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

package com.android.server.pm.test.appenumeration;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.IPackageManager;
import android.content.pm.ProviderInfo;
import android.os.Process;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Application enumeration tests for the internal apis of package manager service.
 */
@RunWith(AndroidJUnit4.class)
public class AppEnumerationInternalTests {
    private static final String TEST_DATA_PATH = "/data/local/tmp/appenumerationtests/";
    private static final String SYNC_PROVIDER_APK_PATH =
            TEST_DATA_PATH + "AppEnumerationSyncProviderTestApp.apk";
    private static final String HAS_APPOP_PERMISSION_APK_PATH =
            TEST_DATA_PATH + "AppEnumerationHasAppOpPermissionTestApp.apk";
    private static final String SHARED_USER_APK_PATH =
            TEST_DATA_PATH + "AppEnumerationSharedUserTestApp.apk";

    private static final String TARGET_SYNC_PROVIDER = "com.android.appenumeration.syncprovider";
    private static final String TARGET_HAS_APPOP_PERMISSION =
            "com.android.appenumeration.hasappoppermission";
    private static final String TARGET_SHARED_USER = "com.android.appenumeration.shareduid";
    private static final String TARGET_NON_EXISTENT = "com.android.appenumeration.nonexistent.pkg";

    private static final String SYNC_PROVIDER_AUTHORITY = TARGET_SYNC_PROVIDER;
    private static final String PERMISSION_REQUEST_INSTALL_PACKAGES =
            "android.permission.REQUEST_INSTALL_PACKAGES";
    private static final String SHARED_USER_NAME = "com.android.appenumeration.shareduid";

    private IPackageManager mIPackageManager;

    @Before
    public void setup() {
        mIPackageManager = AppGlobals.getPackageManager();
    }

    @After
    public void tearDown() throws Exception {
        uninstallPackage(TARGET_SYNC_PROVIDER);
        uninstallPackage(TARGET_HAS_APPOP_PERMISSION);
        uninstallPackage(TARGET_SHARED_USER);
    }

    @Test
    public void querySyncProviders_canSeeForceQueryable() throws Exception {
        final List<String> names = new ArrayList<>();
        final List<ProviderInfo> infos = new ArrayList<>();
        installPackage(SYNC_PROVIDER_APK_PATH, true /* forceQueryable */);
        mIPackageManager.querySyncProviders(names, infos);

        assertThat(names).contains(SYNC_PROVIDER_AUTHORITY);
        assertThat(infos.stream().map(info -> info.packageName).collect(Collectors.toList()))
                .contains(TARGET_SYNC_PROVIDER);
    }

    @Test
    public void querySyncProviders_cannotSeeSyncProvider() throws Exception {
        final List<String> names = new ArrayList<>();
        final List<ProviderInfo> infos = new ArrayList<>();
        installPackage(SYNC_PROVIDER_APK_PATH, false /* forceQueryable */);
        mIPackageManager.querySyncProviders(names, infos);

        assertThat(names).doesNotContain(SYNC_PROVIDER_AUTHORITY);
        assertThat(infos.stream().map(info -> info.packageName).collect(Collectors.toList()))
                .doesNotContain(TARGET_SYNC_PROVIDER);
    }

    @Test
    public void getAppOpPermissionPackages_canSeeForceQueryable() throws Exception {
        installPackage(HAS_APPOP_PERMISSION_APK_PATH, true /* forceQueryable */);

        final String[] packageNames = mIPackageManager.getAppOpPermissionPackages(
                PERMISSION_REQUEST_INSTALL_PACKAGES, UserHandle.myUserId());

        assertThat(packageNames).asList().contains(TARGET_HAS_APPOP_PERMISSION);
    }

    @Test
    public void getAppOpPermissionPackages_cannotSeeHasAppOpPermission() throws Exception {
        installPackage(HAS_APPOP_PERMISSION_APK_PATH, false /* forceQueryable */);

        final String[] packageNames = mIPackageManager.getAppOpPermissionPackages(
                PERMISSION_REQUEST_INSTALL_PACKAGES, UserHandle.myUserId());

        assertThat(packageNames).asList().doesNotContain(TARGET_HAS_APPOP_PERMISSION);
    }

    @Test
    public void getUidForSharedUser_canSeeForceQueryable() throws Exception {
        installPackage(SHARED_USER_APK_PATH, true /* forceQueryable */);

        final int uid = mIPackageManager.getUidForSharedUser(SHARED_USER_NAME);
        assertThat(uid).isGreaterThan(Process.FIRST_APPLICATION_UID);
    }

    @Test
    public void getUidForSharedUser_cannotSeeSharedUser() throws Exception {
        installPackage(SHARED_USER_APK_PATH, false /* forceQueryable */);

        final int uid = mIPackageManager.getUidForSharedUser(SHARED_USER_NAME);
        assertThat(uid).isEqualTo(Process.INVALID_UID);
    }

    @Test
    public void getLaunchIntentSenderForPackage_intentSender_cannotDetectPackage()
            throws Exception {
        installPackage(SHARED_USER_APK_PATH, false /* forceQueryable */);

        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final IntentSender sender = context.getPackageManager()
                .getLaunchIntentSenderForPackage(TARGET_SHARED_USER);
        assertThat(new PendingIntent(sender.getTarget()).isTargetedToPackage()).isTrue();
        sender.sendIntent(context, 0 /* code */, null /* intent */,
                null /* onFinished */, null /* handler */);

        final IntentSender failedSender = InstrumentationRegistry.getInstrumentation().getContext()
                .getPackageManager().getLaunchIntentSenderForPackage(TARGET_NON_EXISTENT);
        assertThat(new PendingIntent(failedSender.getTarget()).isTargetedToPackage()).isTrue();
        Assert.assertThrows(IntentSender.SendIntentException.class,
                () -> failedSender.sendIntent(context, 0 /* code */, null /* intent */,
                        null /* onFinished */, null /* handler */));
    }

    private static void installPackage(String apkPath, boolean forceQueryable) {
        final StringBuilder cmd = new StringBuilder("pm install ");
        if (forceQueryable) {
            cmd.append("--force-queryable ");
        }
        cmd.append(apkPath);
        final String result = runShellCommand(cmd.toString());
        assertThat(result.trim()).contains("Success");
    }

    private static void uninstallPackage(String packageName) {
        runShellCommand("pm uninstall " + packageName);
    }
}
