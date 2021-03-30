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
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.cts.install.lib.host.InstallUtilsHost;
import android.platform.test.annotations.LargeTest;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.ddmlib.Log;
import com.android.tests.rollback.host.AbandonSessionsRule;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.ProcessInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
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
    private static final String APK_IN_APEX_TESTAPEX_NAME = "com.android.apex.apkrollback.test";

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
        deleteFiles("/system/apex/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex",
                "/data/apex/active/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex",
                "/data/apex/active/" + SHIM_APEX_PACKAGE_NAME + "*.apex");
    }

    @Before
    public void setUp() throws Exception {
        cleanUp();
    }

    @After
    public void tearDown() throws Exception {
        cleanUp();
    }

    /**
     * Deletes files and reboots the device if necessary.
     * @param files the paths of files which might contain wildcards
     */
    private void deleteFiles(String... files) throws Exception {
        boolean found = false;
        for (String file : files) {
            CommandResult result = getDevice().executeShellV2Command("ls " + file);
            if (result.getStatus() == CommandStatus.SUCCESS) {
                found = true;
                break;
            }
        }

        if (found) {
            if (!getDevice().isAdbRoot()) {
                getDevice().enableAdbRoot();
            }
            getDevice().remountSystemWritable();
            for (String file : files) {
                getDevice().executeShellCommand("rm -rf " + file);
            }
            getDevice().reboot();
        }
    }

    private void pushTestApex() throws Exception {
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
        final String fileName = APK_IN_APEX_TESTAPEX_NAME + "_v1.apex";
        final File apex = buildHelper.getTestFile(fileName);
        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }
        getDevice().remountSystemWritable();
        assertTrue(getDevice().pushFile(apex, "/system/apex/" + fileName));
        getDevice().reboot();
    }

    /**
     * Tests that duplicate packages in apk-in-apex and apk should fail to install.
     */
    @Test
    public void testDuplicateApkInApexShouldFail() throws Exception {
        pushTestApex();
        runPhase("testDuplicateApkInApexShouldFail_Commit");
        getDevice().reboot();
        runPhase("testDuplicateApkInApexShouldFail_Verify");
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
    public void testAbandonStagedSessionShouldCleanUp() throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testAbandonStagedSessionShouldCleanUp");
        List<String> after = getStagingDirectories();
        // The staging directories generated during the test should be deleted
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void testStagedSessionShouldCleanUpOnVerificationFailure() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());
        List<String> before = getStagingDirectories();
        runPhase("testStagedSessionShouldCleanUpOnVerificationFailure");
        List<String> after = getStagingDirectories();
        assertThat(after).isEqualTo(before);
    }

    @Test
    @LargeTest
    public void testStagedSessionShouldCleanUpOnOnSuccess() throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testStagedSessionShouldCleanUpOnOnSuccess_Commit");
        assertThat(getStagingDirectories()).isNotEqualTo(before);
        getDevice().reboot();
        runPhase("testStagedSessionShouldCleanUpOnOnSuccess_Verify");
        List<String> after = getStagingDirectories();
        assertThat(after).isEqualTo(before);
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
        getDevice().executeShellCommand("mkdir /data/app-staging/session_456");
        getDevice().disableAdbRoot();

        assertThat(getStagingDirectories()).contains("session_123");
        assertThat(getStagingDirectories()).contains("session_456");
        getDevice().reboot();
        assertThat(getStagingDirectories()).doesNotContain("session_123");
        assertThat(getStagingDirectories()).doesNotContain("session_456");
    }

    @Test
    public void testFailStagedSessionIfStagingDirectoryDeleted() throws Exception {
        // Create a staged session
        runPhase("testFailStagedSessionIfStagingDirectoryDeleted_Commit");

        // Delete the staging directory
        getDevice().enableAdbRoot();
        getDevice().executeShellCommand("rm -r /data/app-staging");
        getDevice().disableAdbRoot();

        getDevice().reboot();

        runPhase("testFailStagedSessionIfStagingDirectoryDeleted_Verify");
    }

    private List<String> getStagingDirectories() throws DeviceNotAvailableException {
        String baseDir = "/data/app-staging";
        try {
            getDevice().enableAdbRoot();
            return getDevice().getFileEntry(baseDir).getChildren(false)
                    .stream().filter(entry -> entry.getName().matches("session_\\d+"))
                    .map(entry -> entry.getName())
                    .collect(Collectors.toList());
        } finally {
            getDevice().disableAdbRoot();
        }
    }

    private void restartSystemServer() throws Exception {
        // Restart the system server
        final ProcessInfo oldPs = getDevice().getProcessByName("system_server");

        getDevice().enableAdbRoot(); // Need root to restart system server
        assertThat(getDevice().executeShellCommand("am restart")).contains("Restart the system");
        getDevice().disableAdbRoot();

        // Wait for new system server process to start
        final long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < start + SYSTEM_SERVER_TIMEOUT_MS) {
            final ProcessInfo newPs = getDevice().getProcessByName("system_server");
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
