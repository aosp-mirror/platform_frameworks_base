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

package android.transparency.test;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

// TODO: Add @Presubmit
@RunWith(DeviceJUnit4ClassRunner.class)
public final class BinaryTransparencyHostTest extends BaseHostJUnit4Test {
    private static final String PACKAGE_NAME = "android.transparency.test.app";

    @After
    public void tearDown() throws Exception {
        uninstallPackage("com.android.egg");
    }

    @Test
    public void testCollectAllApexInfo() throws Exception {
        var options = new DeviceTestRunOptions(PACKAGE_NAME);
        options.setTestClassName(PACKAGE_NAME + ".BinaryTransparencyTest");
        options.setTestMethodName("testCollectAllApexInfo");

        // Collect APEX package names from /apex, then pass them as expectation to be verified.
        CommandResult result = getDevice().executeShellV2Command(
                "ls -d /apex/*/ |grep -v @ |grep -v /apex/sharedlibs |cut -d/ -f3");
        assertTrue(result.getStatus() == CommandStatus.SUCCESS);
        String[] packageNames = result.getStdout().split("\n");
        for (var i = 0; i < packageNames.length; i++) {
            options.addInstrumentationArg("apex-" + String.valueOf(i), packageNames[i]);
        }
        options.addInstrumentationArg("apex-number", Integer.toString(packageNames.length));
        runDeviceTests(options);
    }

    @Test
    public void testCollectAllUpdatedPreloadInfo() throws Exception {
        installPackage("EasterEgg.apk");
        runDeviceTest("testCollectAllUpdatedPreloadInfo");
    }

    @Test
    public void testMeasureMbas() throws Exception {
        // TODO(265244016): figure out a way to install an MBA
    }

    private void runDeviceTest(String method) throws DeviceNotAvailableException {
        var options = new DeviceTestRunOptions(PACKAGE_NAME);
        options.setTestClassName(PACKAGE_NAME + ".BinaryTransparencyTest");
        options.setTestMethodName(method);
        runDeviceTests(options);
    }
}
