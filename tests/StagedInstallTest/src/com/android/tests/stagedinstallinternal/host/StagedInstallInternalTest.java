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

import static com.android.cts.shim.lib.ShimPackage.SHIM_APEX_PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.cts.install.lib.host.InstallUtilsHost;
import android.platform.test.annotations.LargeTest;

import com.android.ddmlib.Log;
import com.android.tests.rollback.host.AbandonSessionsRule;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.ProcessInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedInstallInternalTest extends BaseHostJUnit4Test {

    private static final String TAG = StagedInstallInternalTest.class.getSimpleName();
    private static final long SYSTEM_SERVER_TIMEOUT_MS = 60 * 1000;

    @Rule
    public AbandonSessionsRule mHostTestRule = new AbandonSessionsRule(this);

    private static final String SHIM_V2 = "com.android.apex.cts.shim.v2.apex";
    private static final String APK_A = "TestAppAv1.apk";

    private final InstallUtilsHost mHostUtils = new InstallUtilsHost(this);

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

    // Test waiting time for staged session to be ready using adb staged install can be altered
    @Test
    public void testAdbStagdReadyTimeoutFlagWorks() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        final File apexFile = mHostUtils.getTestFile(SHIM_V2);
        final String output = getDevice().executeAdbCommand("install", "--staged",
                "--staged-ready-timeout", "60000", apexFile.getAbsolutePath());
        assertThat(output).contains("Reboot device to apply staged session");
        final String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isNotEmpty();
    }

    // Test adb staged installation wait for session to be ready by default
    @Test
    public void testAdbStagedInstallWaitsTillReadyByDefault() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        final File apexFile = mHostUtils.getTestFile(SHIM_V2);
        final String output = getDevice().executeAdbCommand("install", "--staged",
                apexFile.getAbsolutePath());
        assertThat(output).contains("Reboot device to apply staged session");
        final String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isNotEmpty();
    }

    // Test we can skip waiting for staged session to be ready
    @Test
    public void testAdbStagedReadyWaitCanBeSkipped() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        final File apexFile = mHostUtils.getTestFile(SHIM_V2);
        final String output = getDevice().executeAdbCommand("install", "--staged",
                "--staged-ready-timeout", "0", apexFile.getAbsolutePath());
        assertThat(output).doesNotContain("Reboot device to apply staged session");
        assertThat(output).contains("Success");
        final String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isEmpty();
    }

    // Test rollback-app command waits for staged sessions to be ready
    @Test
    @LargeTest
    public void testAdbRollbackAppWaitsForStagedReady() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        final File apexFile = mHostUtils.getTestFile(SHIM_V2);
        String output = getDevice().executeAdbCommand("install", "--staged",
                "--enable-rollback", apexFile.getAbsolutePath());
        assertThat(output).contains("Reboot device to apply staged session");
        getDevice().reboot();
        output = getDevice().executeShellCommand("pm rollback-app " + SHIM_APEX_PACKAGE_NAME);
        assertThat(output).contains("Reboot device to apply staged session");
        final String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isNotEmpty();
    }

    @Test
    public void testAdbInstallMultiPackageCommandWorks() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        final File apexFile = mHostUtils.getTestFile(SHIM_V2);
        final File apkFile = mHostUtils.getTestFile(APK_A);
        final String output = getDevice().executeAdbCommand("install-multi-package",
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

    @Test
    public void testStagedInstallationShouldCleanUpOnValidationFailure() throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testStagedInstallationShouldCleanUpOnValidationFailure");
        List<String> after = getStagingDirectories();
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void testStagedInstallationShouldCleanUpOnValidationFailureMultiPackage()
            throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testStagedInstallationShouldCleanUpOnValidationFailureMultiPackage");
        List<String> after = getStagingDirectories();
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void testOrphanedStagingDirectoryGetsCleanedUpOnReboot() throws Exception {
        //create random directories in /data/app-staging folder
        getDevice().enableAdbRoot();
        getDevice().executeShellCommand("mkdir /data/app-staging/session_123");
        getDevice().executeShellCommand("mkdir /data/app-staging/random_name");
        getDevice().disableAdbRoot();

        assertThat(getStagingDirectories()).isNotEmpty();
        getDevice().reboot();
        assertThat(getStagingDirectories()).isEmpty();
    }

    private List<String> getStagingDirectories() throws DeviceNotAvailableException {
        String baseDir = "/data/app-staging";
        try {
            getDevice().enableAdbRoot();
            return getDevice().getFileEntry(baseDir).getChildren(false)
                    .stream().filter(entry -> entry.getName().matches("session_\\d+"))
                    .map(entry -> entry.getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Return an empty list if any error
            return Collections.EMPTY_LIST;
        } finally {
            getDevice().disableAdbRoot();
        }
    }

    private void restartSystemServer() throws Exception {
        // Restart the system server
        long oldStartTime = getDevice().getProcessByName("system_server").getStartTime();

        getDevice().enableAdbRoot(); // Need root to restart system server
        assertThat(getDevice().executeShellCommand("am restart")).contains("Restart the system");
        getDevice().disableAdbRoot();

        // Wait for new system server process to start
        final long start = System.currentTimeMillis();
        long newStartTime = oldStartTime;
        while (System.currentTimeMillis() < start + SYSTEM_SERVER_TIMEOUT_MS) {
            final ProcessInfo newPs = getDevice().getProcessByName("system_server");
            if (newPs != null) {
                newStartTime = newPs.getStartTime();
                if (newStartTime != oldStartTime) {
                    break;
                }
            }
            Thread.sleep(500);
        }
        assertThat(newStartTime).isNotEqualTo(oldStartTime);
        getDevice().waitForDeviceAvailable();
    }
}
