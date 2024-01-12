/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm.test.app;

import static android.Manifest.permission.GET_BACKGROUND_INSTALLED_PACKAGES;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ShellIdentityUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class BackgroundInstallControlServiceTest {
    private static final String TAG = "BackgroundInstallControlServiceTest";
    private static final String MOCK_PACKAGE_NAME = "com.android.servicestests.apps.bicmockapp3";

    private IBackgroundInstallControlService mIBics;

    @Before
    public void setUp() {
        mIBics =
                IBackgroundInstallControlService.Stub.asInterface(
                        ServiceManager.getService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE));
        assertThat(mIBics).isNotNull();
    }

    @After
    public void tearDown() {
        runShellCommand("pm uninstall " + MOCK_PACKAGE_NAME);
    }

    @Test
    public void testGetMockBackgroundInstalledPackages() throws RemoteException {
        ParceledListSlice<PackageInfo> slice =
                ShellIdentityUtils.invokeMethodWithShellPermissions(
                        mIBics,
                        (bics) -> {
                            try {
                                return bics.getBackgroundInstalledPackages(
                                        PackageManager.MATCH_ALL, Process.myUserHandle()
                                                .getIdentifier());
                            } catch (RemoteException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        GET_BACKGROUND_INSTALLED_PACKAGES);
        assertThat(slice).isNotNull();

        var packageList = slice.getList();
        assertThat(packageList).isNotNull();
        assertThat(packageList).hasSize(2);

        var expectedPackageNames =
                Set.of(
                        "com.android.servicestests.apps.bicmockapp1",
                        "com.android.servicestests.apps.bicmockapp2");
        var actualPackageNames =
                packageList.stream()
                        .map((packageInfo) -> packageInfo.packageName)
                        .collect(Collectors.toSet());
        assertThat(actualPackageNames).containsExactlyElementsIn(expectedPackageNames);
    }
}