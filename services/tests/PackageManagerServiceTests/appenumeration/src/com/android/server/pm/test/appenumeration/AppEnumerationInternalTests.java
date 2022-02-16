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
import android.content.pm.IPackageManager;
import android.content.pm.ProviderInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
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
    private static final String SYNC_PROVIDER_PKG_NAME = "com.android.appenumeration.syncprovider";
    private static final String SYNC_PROVIDER_AUTHORITY = SYNC_PROVIDER_PKG_NAME;

    private IPackageManager mIPackageManager;

    @Before
    public void setup() {
        mIPackageManager = AppGlobals.getPackageManager();
    }

    @After
    public void tearDown() throws Exception {
        uninstallPackage(SYNC_PROVIDER_PKG_NAME);
    }

    @Test
    public void querySyncProviders_canSeeForceQueryable() throws Exception {
        final List<String> names = new ArrayList<>();
        final List<ProviderInfo> infos = new ArrayList<>();
        installPackage(SYNC_PROVIDER_APK_PATH, true /* forceQueryable */);
        mIPackageManager.querySyncProviders(names, infos);

        assertThat(names).contains(SYNC_PROVIDER_AUTHORITY);
        assertThat(infos.stream().map(info -> info.packageName).collect(Collectors.toList()))
                .contains(SYNC_PROVIDER_PKG_NAME);
    }

    @Test
    public void querySyncProviders_cannotSeeSyncProvider() throws Exception {
        final List<String> names = new ArrayList<>();
        final List<ProviderInfo> infos = new ArrayList<>();
        installPackage(SYNC_PROVIDER_APK_PATH, false /* forceQueryable */);
        mIPackageManager.querySyncProviders(names, infos);

        assertThat(names).doesNotContain(SYNC_PROVIDER_AUTHORITY);
        assertThat(infos.stream().map(info -> info.packageName).collect(Collectors.toList()))
                .doesNotContain(SYNC_PROVIDER_PKG_NAME);
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
