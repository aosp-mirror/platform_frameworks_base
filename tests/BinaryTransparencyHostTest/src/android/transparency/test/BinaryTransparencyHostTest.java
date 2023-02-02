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
import static org.junit.Assert.fail;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

// TODO: Add @Presubmit
@RunWith(DeviceJUnit4ClassRunner.class)
public final class BinaryTransparencyHostTest extends BaseHostJUnit4Test {
    private static final String PACKAGE_NAME = "android.transparency.test.app";

    private static final String JOB_ID = "1740526926";

    /** Waiting time for the job to be scheduled */
    private static final int JOB_CREATION_MAX_SECONDS = 5;

    @After
    public void tearDown() throws Exception {
        uninstallPackage("com.android.egg");
        uninstallRebootlessApex();
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
    public void testRebootlessApexUpdateTriggersJobScheduling() throws Exception {
        cancelPendingJob();
        installRebootlessApex();

        // Verify
        expectJobToBeScheduled();
        // Just cancel since we can't verifying very meaningfully.
        cancelPendingJob();
    }

    @Test
    public void testPreloadUpdateTriggersJobScheduling() throws Exception {
        cancelPendingJob();
        installPackage("EasterEgg.apk");

        // Verify
        expectJobToBeScheduled();
        // Just cancel since we can't verifying very meaningfully.
        cancelPendingJob();
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

    private void cancelPendingJob() throws DeviceNotAvailableException {
        CommandResult result = getDevice().executeShellV2Command(
                "cmd jobscheduler cancel android " + JOB_ID);
        assertTrue(result.getStatus() == CommandStatus.SUCCESS);
    }

    private void expectJobToBeScheduled() throws Exception {
        for (int i = 0; i < JOB_CREATION_MAX_SECONDS; i++) {
            CommandResult result = getDevice().executeShellV2Command(
                    "cmd jobscheduler get-job-state android " + JOB_ID);
            String state = result.getStdout().toString();
            if (state.startsWith("unknown")) {
                // The job hasn't been scheduled yet. So try again.
                TimeUnit.SECONDS.sleep(1);
            } else if (result.getExitCode() != 0) {
                fail("Failing due to unexpected job state: " + result);
            } else {
                // The job exists, which is all we care about here
                return;
            }
        }
        fail("Timed out waiting for the job to be scheduled");
    }

    private void installRebootlessApex() throws Exception {
        installPackage("com.android.apex.cts.shim.v2_rebootless.apex", "--force-non-staged");
    }

    private void uninstallRebootlessApex() throws DeviceNotAvailableException {
        // Reboot only if the APEX is not the pre-install one.
        CommandResult result = getDevice().executeShellV2Command(
                "pm list packages -f --apex-only |grep com.android.apex.cts.shim");
        assertTrue(result.getStatus() == CommandStatus.SUCCESS);
        if (result.getStdout().contains("/data/apex/active/")) {
            uninstallPackage("com.android.apex.cts.shim");
            getDevice().reboot();

            // Reboot enforces SELinux. Make it permissive again.
            CommandResult runResult = getDevice().executeShellV2Command("setenforce 0");
            assertTrue(runResult.getStatus() == CommandStatus.SUCCESS);
        }
    }
}
