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

package com.android.server;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.cts.install.lib.host.InstallUtilsHost;

import com.android.internal.util.test.SystemPreparer;
import com.android.modules.utils.build.testing.DeviceSdkLevel;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class ApexSystemServicesTestCases extends BaseHostJUnit4Test {

    private final InstallUtilsHost mHostUtils = new InstallUtilsHost(this);
    private final TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private final SystemPreparer mPreparer = new SystemPreparer(mTemporaryFolder, this::getDevice);

    @Rule
    public final RuleChain ruleChain = RuleChain.outerRule(mTemporaryFolder).around(mPreparer);

    private DeviceSdkLevel mDeviceSdkLevel;
    private ITestDevice mDevice;

    @Before
    public void setup() throws Exception {
        mDevice = getDevice();
        mDeviceSdkLevel = new DeviceSdkLevel(getDevice());

        assumeTrue(mDeviceSdkLevel.isDeviceAtLeastT());

        assertThat(mDevice.enableAdbRoot()).isTrue();
        assertThat(mHostUtils.isApexUpdateSupported()).isTrue();
    }

    @After
    public void tearDown() throws Exception {
        mDevice.disableAdbRoot();
    }

    @Test
    public void noApexSystemServerStartsWithoutApex() throws Exception {
        mPreparer.reboot();

        assertThat(getFakeApexSystemServiceLogcat())
                .doesNotContain("FakeApexSystemService onStart");
    }

    @Test
    public void apexSystemServerStarts() throws Exception {
        // Pre-install the apex
        String apex = "test_com.android.server.apex";
        mPreparer.pushResourceFile(apex, "/system/apex/" + apex);
        // Reboot activates the apex
        mPreparer.reboot();

        assertThat(getFakeApexSystemServiceLogcat())
                .contains("FakeApexSystemService onStart");
    }

    private String getFakeApexSystemServiceLogcat() throws DeviceNotAvailableException {
        return mDevice.executeAdbCommand("logcat", "-v", "brief", "-d", "FakeApexSystemService:D",
                "*:S");
    }

}
