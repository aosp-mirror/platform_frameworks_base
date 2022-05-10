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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedInstallInternalTest extends BaseHostJUnit4Test {

    private static final String TAG = StagedInstallInternalTest.class.getSimpleName();
    private static final long SYSTEM_SERVER_TIMEOUT_MS = 60 * 1000;

    @Rule
    public AbandonSessionsRule mHostTestRule = new AbandonSessionsRule(this);
    private static final String SHIM_V2 = "com.android.apex.cts.shim.v2.apex";
    private static final String APEX_WRONG_SHA = "com.android.apex.cts.shim.v2_wrong_sha.apex";
    private static final String APK_A = "TestAppAv1.apk";
    private static final String APK_IN_APEX_TESTAPEX_NAME = "com.android.apex.apkrollback.test";
    private static final String APEXD_TEST_APEX = "apex.apexd_test.apex";
    private static final String FAKE_APEX_SYSTEM_SERVER_APEX = "test_com.android.server.apex";

    private static final String TEST_VENDOR_APEX_ALLOW_LIST =
            "/vendor/etc/sysconfig/test-vendor-apex-allow-list.xml";

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
                "/data/apex/active/" + SHIM_APEX_PACKAGE_NAME + "*.apex",
                "/system/apex/test.rebootless_apex_v*.apex",
                "/data/apex/active/test.apex.rebootless*.apex",
                TEST_VENDOR_APEX_ALLOW_LIST);
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
        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }

        boolean found = false;
        for (String file : files) {
            CommandResult result = getDevice().executeShellV2Command("ls " + file);
            if (result.getStatus() == CommandStatus.SUCCESS) {
                found = true;
                break;
            }
        }

        if (found) {
            getDevice().remountSystemWritable();
            for (String file : files) {
                getDevice().executeShellCommand("rm -rf " + file);
            }
            getDevice().reboot();
        }
    }

    private void pushTestApex(String fileName) throws Exception {
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
        final File apex = buildHelper.getTestFile(fileName);
        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }
        getDevice().remountSystemWritable();
        assertTrue(getDevice().pushFile(apex, "/system/apex/" + fileName));
    }

    private void pushTestVendorApexAllowList(String installerPackageName) throws Exception {
        if (!getDevice().isAdbRoot()) {
            getDevice().enableAdbRoot();
        }
        getDevice().remountSystemWritable();
        File file = File.createTempFile("test-vendor-apex-allow-list", ".xml");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            final String fmt =
                    "<config>\n"
                            + "    <allowed-vendor-apex package=\"test.apex.rebootless\" "
                            + "       installerPackage=\"%s\" />\n"
                            + "</config>";
            writer.write(String.format(fmt, installerPackageName));
        }
        getDevice().pushFile(file, TEST_VENDOR_APEX_ALLOW_LIST);
    }

    /**
     * Tests that duplicate packages in apk-in-apex and apk should fail to install.
     */
    @Test
    @LargeTest
    public void testDuplicateApkInApexShouldFail() throws Exception {
        pushTestApex(APK_IN_APEX_TESTAPEX_NAME + "_v1.apex");
        getDevice().reboot();

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
    @LargeTest
    public void testStagedSessionShouldCleanUpOnOnSuccessMultiPackage() throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testStagedSessionShouldCleanUpOnOnSuccessMultiPackage_Commit");
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
    @LargeTest
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
    @LargeTest
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

    @Test
    public void testApexActivationFailureIsCapturedInSession() throws Exception {
        // We initiate staging a normal apex update which passes pre-reboot verification.
        // Then we replace the valid apex waiting in /data/app-staging with something
        // that cannot be activated and reboot. The apex should fail to activate, which
        // is what we want for this test.
        runPhase("testApexActivationFailureIsCapturedInSession_Commit");
        final String sessionId = getDevice().executeShellCommand(
                "pm get-stagedsessions --only-ready --only-parent --only-sessionid").trim();
        assertThat(sessionId).isNotEmpty();
        // Now replace the valid staged apex with something invalid
        getDevice().enableAdbRoot();
        getDevice().executeShellCommand("rm /data/app-staging/session_" + sessionId + "/*");
        final File invalidApexFile = mHostUtils.getTestFile(APEX_WRONG_SHA);
        getDevice().pushFile(invalidApexFile,
                "/data/app-staging/session_" + sessionId + "/base.apex");
        getDevice().reboot();

        runPhase("testApexActivationFailureIsCapturedInSession_Verify");
    }

    @Test
    public void testActiveApexIsRevertedOnCheckpointRollback() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());
        assumeTrue("Device does not support file-system checkpoint",
                mHostUtils.isCheckpointSupported());

        // Install something so that /data/apex/active is not empty
        runPhase("testActiveApexIsRevertedOnCheckpointRollback_Prepare");
        getDevice().reboot();

        // Stage another session which will be installed during fs-rollback mode
        runPhase("testActiveApexIsRevertedOnCheckpointRollback_Commit");

        // Set checkpoint to 0 so that we enter fs-rollback mode immediately on reboot
        getDevice().enableAdbRoot();
        getDevice().executeShellCommand("vdc checkpoint startCheckpoint 0");
        getDevice().disableAdbRoot();
        getDevice().reboot();

        // Verify that session was reverted and we have fallen back to
        // apex installed during preparation stage.
        runPhase("testActiveApexIsRevertedOnCheckpointRollback_VerifyPostReboot");
    }

    @Test
    public void testApexIsNotActivatedIfNotInCheckpointMode() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());
        assumeTrue("Device does not support file-system checkpoint",
                mHostUtils.isCheckpointSupported());

        runPhase("testApexIsNotActivatedIfNotInCheckpointMode_Commit");
        // Delete checkpoint file in /metadata so that device thinks
        // fs-checkpointing was never activated
        getDevice().enableAdbRoot();
        getDevice().executeShellCommand("rm /metadata/vold/checkpoint");
        getDevice().disableAdbRoot();
        getDevice().reboot();
        // Verify that session was not installed when not in fs-checkpoint mode
        runPhase("testApexIsNotActivatedIfNotInCheckpointMode_VerifyPostReboot");
    }

    @Test
    public void testApexInstallerNotInAllowListCanNotInstall() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        runPhase("testApexInstallerNotInAllowListCanNotInstall_staged");
        runPhase("testApexInstallerNotInAllowListCanNotInstall_nonStaged");
    }

    @Test
    @LargeTest
    public void testApexNotInAllowListCanNotInstall() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        pushTestApex("test.rebootless_apex_v1.apex");
        getDevice().reboot();

        runPhase("testApexNotInAllowListCanNotInstall_staged");
        runPhase("testApexNotInAllowListCanNotInstall_nonStaged");
    }

    @Test
    @LargeTest
    public void testVendorApexWrongInstaller() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        pushTestVendorApexAllowList("com.wrong.installer");
        pushTestApex("test.rebootless_apex_v1.apex");
        getDevice().reboot();

        runPhase("testVendorApexWrongInstaller_staged");
        runPhase("testVendorApexWrongInstaller_nonStaged");
    }

    @Test
    @LargeTest
    public void testVendorApexCorrectInstaller() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        pushTestVendorApexAllowList("com.android.tests.stagedinstallinternal");
        pushTestApex("test.rebootless_apex_v1.apex");
        getDevice().reboot();

        runPhase("testVendorApexCorrectInstaller_staged");
        runPhase("testVendorApexCorrectInstaller_nonStaged");
    }

    @Test
    public void testRebootlessUpdates() throws Exception {
        pushTestApex("test.rebootless_apex_v1.apex");
        getDevice().reboot();

        runPhase("testRebootlessUpdates");
    }

    @Test
    public void testRebootlessUpdate_hasStagedSessionWithSameApex_fails() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        runPhase("testRebootlessUpdate_hasStagedSessionWithSameApex_fails");
    }

    @Test
    public void testGetStagedModuleNames() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        runPhase("testGetStagedModuleNames");
    }

    @Test
    @LargeTest
    public void testGetStagedApexInfo() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        pushTestApex(APEXD_TEST_APEX);
        getDevice().reboot();

        runPhase("testGetStagedApexInfo");
    }

    @Test
    @LargeTest
    public void testGetAppInfo_flagTestOnlyIsSet() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        pushTestApex(FAKE_APEX_SYSTEM_SERVER_APEX);
        getDevice().reboot();

        runPhase("testGetAppInfo_flagTestOnlyIsSet");
    }

    @Test
    public void testStagedApexObserver() throws Exception {
        assumeTrue("Device does not support updating APEX",
                mHostUtils.isApexUpdateSupported());

        runPhase("testStagedApexObserver");
    }

    @Test
    public void testRebootlessDowngrade() throws Exception {
        pushTestApex("test.rebootless_apex_v2.apex");
        getDevice().reboot();
        runPhase("testRebootlessDowngrade");
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
