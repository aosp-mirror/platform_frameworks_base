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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.LargeTest;
import android.platform.test.annotations.Presubmit;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

@Presubmit
@RunWith(DeviceJUnit4ClassRunner.class)
public final class BinaryTransparencyHostTest extends BaseHostJUnit4Test {
    private static final String PACKAGE_NAME = "android.transparency.test.app";

    private static final String JOB_ID = "1740526926";

    /** Waiting time for the job to be scheduled */
    private static final int JOB_CREATION_MAX_SECONDS = 30;

    @Before
    public void setUp() throws Exception {
        cancelPendingJob();
    }

    @Test
    public void testCollectAllApexInfo() throws Exception {
        var options = new DeviceTestRunOptions(PACKAGE_NAME);
        options.setTestClassName(PACKAGE_NAME + ".BinaryTransparencyTest");
        options.setTestMethodName("testCollectAllApexInfo");

        // Collect APEX package names from /apex, then pass them as expectation to be verified.
        // The package names are collected from the find name with deduplication (NB: we used to
        // deduplicate by dropping directory names with '@', but there's a DCLA case where it only
        // has one directory with '@'. So we have to keep it and deduplicate the current way).
        CommandResult result = getDevice().executeShellV2Command(
                "ls -d /apex/*/ |grep -v /apex/sharedlibs |cut -d/ -f3 |cut -d@ -f1 |sort |uniq");
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
        try {
            updatePreloadApp();
            runDeviceTest("testCollectAllUpdatedPreloadInfo");
        } finally {
            // No need to wait until job complete, since we can't verifying very meaningfully.
            cancelPendingJob();
            uninstallPackage("com.android.egg");
        }
    }

    @Test
    public void testCollectAllSilentInstalledMbaInfo() throws Exception {
        try {
            new InstallMultiple()
                .addFile("FeatureSplitBase.apk")
                .addFile("FeatureSplit1.apk")
                .run();
            updatePreloadApp();
            assertNotNull(getDevice().getAppPackageInfo("com.android.test.split.feature"));
            assertNotNull(getDevice().getAppPackageInfo("com.android.egg"));

            assertTrue(getDevice().setProperty("debug.transparency.bg-install-apps",
                        "com.android.test.split.feature,com.android.egg"));
            runDeviceTest("testCollectAllSilentInstalledMbaInfo");
        } finally {
            // No need to wait until job complete, since we can't verifying very meaningfully.
            cancelPendingJob();
            uninstallPackage("com.android.test.split.feature");
            uninstallPackage("com.android.egg");
        }
    }

    @LargeTest
    @Test
    public void testRebootlessApexUpdateTriggersJobScheduling() throws Exception {
        try {
            installRebootlessApex();

            // Verify
            expectJobToBeScheduled();
        } finally {
            // No need to wait until job complete, since we can't verifying very meaningfully.
            uninstallRebootlessApexThenReboot();
        }
    }

    @Test
    public void testPreloadUpdateTriggersJobScheduling() throws Exception {
        try {
            updatePreloadApp();

            // Verify
            expectJobToBeScheduled();
        } finally {
            // No need to wait until job complete, since we can't verifying very meaningfully.
            cancelPendingJob();
            uninstallPackage("com.android.egg");
        }
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
        if (result.getStatus() == CommandStatus.SUCCESS) {
            CLog.d("Canceling, output: " + result.getStdout());
        } else {
            CLog.d("Something went wrong, error: " + result.getStderr());
        }
    }

    private void expectJobToBeScheduled() throws Exception {
        for (int i = 0; i < JOB_CREATION_MAX_SECONDS; i++) {
            CommandResult result = getDevice().executeShellV2Command(
                    "cmd jobscheduler get-job-state android " + JOB_ID);
            String state = result.getStdout().toString();
            CLog.i("Job status: " + state);
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

    private void uninstallRebootlessApexThenReboot() throws DeviceNotAvailableException {
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

    private void updatePreloadApp() throws DeviceNotAvailableException {
        CommandResult result = getDevice().executeShellV2Command("pm path com.android.egg");
        assertTrue(result.getStatus() == CommandStatus.SUCCESS);
        assertThat(result.getStdout()).startsWith("package:/system/app/");
        String path = result.getStdout().replaceFirst("^package:", "");

        result = getDevice().executeShellV2Command("pm install " + path);
        assertTrue(result.getStatus() == CommandStatus.SUCCESS);
    }

    private class InstallMultiple extends BaseInstallMultiple<InstallMultiple> {
        InstallMultiple() {
            super(getDevice(), getBuild());
            // Needed since in getMockBackgroundInstalledPackages, getPackageInfo runs as the caller
            // uid. This also makes it consistent with installPackage's behavior.
            addArg("--force-queryable");
        }
    }
}
