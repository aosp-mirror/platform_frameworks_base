/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tests.stagedinstallinternal.host;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.android.ddmlib.Log;
import com.android.tests.rollback.host.AbandonSessionsRule;
import com.android.tests.util.ModuleTestUtils;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.ProcessInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedInstallInternalTest extends BaseHostJUnit4Test {

    private static final String TAG = StagedInstallInternalTest.class.getSimpleName();
    private static final long SYSTEM_SERVER_TIMEOUT_MS = 60 * 1000;

    @Rule
    public AbandonSessionsRule mHostTestRule = new AbandonSessionsRule(this);
    private static final String SHIM_V2 = "com.android.apex.cts.shim.v2.apex";
    private static final String APK_A = "TestAppAv1.apk";

    private final ModuleTestUtils mTestUtils = new ModuleTestUtils(this);

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testApkOnlyEnableRollback");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.stagedinstallinternal",
                "com.android.tests.stagedinstallinternal.StagedInstallInternalTest",
                phase));
    }

    // We do not assert the success of cleanup phase since it might fail due to flaky reasons.
    private void cleanUp() throws Exception {
        try {
            runDeviceTests("com.android.tests.stagedinstallinternal",
                    "com.android.tests.stagedinstallinternal.StagedInstallInternalTest",
                    "cleanUp");
        } catch (AssertionError e) {
            Log.e(TAG, e);
        }
    }

    @Before
    public void setUp() throws Exception {
        cleanUp();
    }

    @After
    public void tearDown() throws Exception {
        cleanUp();
    }

    @Test
    public void testSystemServerRestartDoesNotAffectStagedSessions() throws Exception {
        runPhase("testSystemServerRestartDoesNotAffectStagedSessions_Commit");
        restartSystemServer();
        runPhase("testSystemServerRestartDoesNotAffectStagedSessions_Verify");
    }

    @Test
    public void testAdbStagedInstallWaitForReadyFlagWorks() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mTestUtils.isApexUpdateSupported());

        File apexFile = mTestUtils.getTestFile(SHIM_V2);
        String output = getDevice().executeAdbCommand("install", "--staged",
                "--wait-for-staged-ready", "60000", apexFile.getAbsolutePath());
        assertThat(output).contains("Reboot device to apply staged session");
        String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isNotEmpty();
    }

    @Test
    public void testAdbStagedInstallNoWaitFlagWorks() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mTestUtils.isApexUpdateSupported());

        File apexFile = mTestUtils.getTestFile(SHIM_V2);
        String output = getDevice().executeAdbCommand("install", "--staged",
                "--no-wait", apexFile.getAbsolutePath());
        assertThat(output).doesNotContain("Reboot device to apply staged session");
        assertThat(output).contains("Success");
        String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isEmpty();
    }

    @Test
    public void testAdbInstallMultiPackageCommandWorks() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mTestUtils.isApexUpdateSupported());

        File apexFile = mTestUtils.getTestFile(SHIM_V2);
        File apkFile = mTestUtils.getTestFile(APK_A);
        String output = getDevice().executeAdbCommand("install-multi-package",
                apexFile.getAbsolutePath(), apkFile.getAbsolutePath());
        assertThat(output).contains("Created parent session");
        assertThat(output).contains("Created child session");
        assertThat(output).contains("Success. Reboot device to apply staged session");

        // Ensure there is only one parent session
        String[] sessionIds = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").split("\n");
        assertThat(sessionIds.length).isEqualTo(1);
        // Ensure there are two children session
        sessionIds = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-sessionid").split("\n");
        assertThat(sessionIds.length).isEqualTo(3);
    }

    private void restartSystemServer() throws Exception {
        // Restart the system server
        ProcessInfo oldPs = getDevice().getProcessByName("system_server");

        getDevice().enableAdbRoot(); // Need root to restart system server
        assertThat(getDevice().executeShellCommand("am restart")).contains("Restart the system");
        getDevice().disableAdbRoot();

        // Wait for new system server process to start
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < start + SYSTEM_SERVER_TIMEOUT_MS) {
            ProcessInfo newPs = getDevice().getProcessByName("system_server");
            if (newPs != null) {
                if (newPs.getPid() != oldPs.getPid()) {
                    getDevice().waitForDeviceAvailable();
                    return;
                }
            }
            Thread.sleep(500);
        }
        fail("Timed out in restarting system server");
    }
}
