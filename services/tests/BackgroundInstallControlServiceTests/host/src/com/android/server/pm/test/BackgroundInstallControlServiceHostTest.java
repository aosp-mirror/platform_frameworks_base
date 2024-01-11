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

package com.android.server.pm.test;

import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Test;
import org.junit.runner.RunWith;

// TODO: Add @Presubmit
@RunWith(DeviceJUnit4ClassRunner.class)
public final class BackgroundInstallControlServiceHostTest extends BaseHostJUnit4Test {
    private static final String PACKAGE_NAME = "com.android.server.pm.test.app";

    private static final String MOCK_PACKAGE_NAME_1 = "com.android.servicestests.apps.bicmockapp1";
    private static final String MOCK_PACKAGE_NAME_2 = "com.android.servicestests.apps.bicmockapp2";

    private static final String TEST_DATA_DIR = "/data/local/tmp/";

    private static final String MOCK_APK_FILE_1 = "BackgroundInstallControlMockApp1.apk";
    private static final String MOCK_APK_FILE_2 = "BackgroundInstallControlMockApp2.apk";

    @Test
    public void testGetMockBackgroundInstalledPackages() throws Exception {
        installPackage(TEST_DATA_DIR  + MOCK_APK_FILE_1);
        installPackage(TEST_DATA_DIR + MOCK_APK_FILE_2);

        assertThat(getDevice().getAppPackageInfo(MOCK_PACKAGE_NAME_1)).isNotNull();
        assertThat(getDevice().getAppPackageInfo(MOCK_PACKAGE_NAME_2)).isNotNull();

        assertThat(getDevice().setProperty("debug.transparency.bg-install-apps",
                    MOCK_PACKAGE_NAME_1 + "," + MOCK_PACKAGE_NAME_2)).isTrue();
        runDeviceTest("testGetMockBackgroundInstalledPackages");
        assertThat(getDevice().uninstallPackage(MOCK_PACKAGE_NAME_1)).isNull();
        assertThat(getDevice().uninstallPackage(MOCK_PACKAGE_NAME_2)).isNull();

        assertThat(getDevice().getAppPackageInfo(MOCK_PACKAGE_NAME_1)).isNull();
        assertThat(getDevice().getAppPackageInfo(MOCK_PACKAGE_NAME_2)).isNull();
    }

    private void installPackage(String path) throws DeviceNotAvailableException {
        String cmd = "pm install -t --force-queryable " + path;
        CommandResult result = getDevice().executeShellV2Command(cmd);
        assertThat(result.getStatus() == CommandStatus.SUCCESS).isTrue();
    }

    private void runDeviceTest(String method) throws DeviceNotAvailableException {
        var options = new DeviceTestRunOptions(PACKAGE_NAME);
        options.setTestClassName(PACKAGE_NAME + ".BackgroundInstallControlServiceTest");
        options.setTestMethodName(method);
        runDeviceTests(options);
    }
}
