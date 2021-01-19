/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tests.rollback.host;

import static com.android.tests.rollback.host.WatchdogEventLogger.Subject.assertThat;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.ddmlib.Log;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IFileEntry;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs the staged rollback tests.
 *
 * TODO(gavincorkery): Support the verification of logging parents in Watchdog metrics.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedRollbackTest extends BaseHostJUnit4Test {
    private static final String TAG = "StagedRollbackTest";
    private static final int NATIVE_CRASHES_THRESHOLD = 5;

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testApkOnlyEnableRollback");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertThat(runDeviceTests("com.android.tests.rollback",
                    "com.android.tests.rollback.StagedRollbackTest",
                    phase)).isTrue();
    }

    private static final String APK_IN_APEX_TESTAPEX_NAME = "com.android.apex.apkrollback.test";
    private static final String TESTAPP_A = "com.android.cts.install.lib.testapp.A";

    private static final String TEST_SUBDIR = "/subdir/";

    private static final String TEST_FILENAME_1 = "test_file.txt";
    private static final String TEST_STRING_1 = "hello this is a test";
    private static final String TEST_FILENAME_2 = "another_file.txt";
    private static final String TEST_STRING_2 = "this is a different file";
    private static final String TEST_FILENAME_3 = "also.xyz";
    private static final String TEST_STRING_3 = "also\n a\n test\n string";
    private static final String TEST_FILENAME_4 = "one_more.test";
    private static final String TEST_STRING_4 = "once more unto the test";

    private static final String REASON_APP_CRASH = "REASON_APP_CRASH";
    private static final String REASON_NATIVE_CRASH = "REASON_NATIVE_CRASH";

    private static final String ROLLBACK_INITIATE = "ROLLBACK_INITIATE";
    private static final String ROLLBACK_BOOT_TRIGGERED = "ROLLBACK_BOOT_TRIGGERED";
    private static final String ROLLBACK_SUCCESS = "ROLLBACK_SUCCESS";

    private WatchdogEventLogger mLogger = new WatchdogEventLogger();

    @Rule
    public AbandonSessionsRule mHostTestRule = new AbandonSessionsRule(this);

    @Before
    public void setUp() throws Exception {
        deleteFiles("/system/apex/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex",
                "/data/apex/active/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex");
        runPhase("expireRollbacks");
        mLogger.start(getDevice());
    }

    @After
    public void tearDown() throws Exception {
        mLogger.stop();
        runPhase("expireRollbacks");
        deleteFiles("/system/apex/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex",
                "/data/apex/active/" + APK_IN_APEX_TESTAPEX_NAME + "*.apex",
                apexDataDirDeSys(APK_IN_APEX_TESTAPEX_NAME) + "*",
                apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + "*");
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
            try {
                getDevice().enableAdbRoot();
                getDevice().remountSystemWritable();
                for (String file : files) {
                    getDevice().executeShellCommand("rm -rf " + file);
                }
            } finally {
                getDevice().disableAdbRoot();
            }
            getDevice().reboot();
        }
    }

    /**
     * Tests watchdog triggered staged rollbacks involving only apks.
     */
    @Test
    public void testBadApkOnly() throws Exception {
        runPhase("testBadApkOnly_Phase1_Install");
        getDevice().reboot();
        runPhase("testBadApkOnly_Phase2_VerifyInstall");

        // Trigger rollback and wait for reboot to happen
        runPhase("testBadApkOnly_Phase3_Crash");
        assertThat(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(2))).isTrue();

        getDevice().waitForDeviceAvailable();

        runPhase("testBadApkOnly_Phase4_VerifyRollback");

        assertThat(mLogger).eventOccurred(ROLLBACK_INITIATE, null, REASON_APP_CRASH, TESTAPP_A);
        assertThat(mLogger).eventOccurred(ROLLBACK_BOOT_TRIGGERED, null, null, null);
        assertThat(mLogger).eventOccurred(ROLLBACK_SUCCESS, null, null, null);
    }

    @Test
    public void testNativeWatchdogTriggersRollback() throws Exception {
        runPhase("testNativeWatchdogTriggersRollback_Phase1_Install");

        // Reboot device to activate staged package
        getDevice().reboot();

        runPhase("testNativeWatchdogTriggersRollback_Phase2_VerifyInstall");

        // crash system_server enough times to trigger a rollback
        crashProcess("system_server", NATIVE_CRASHES_THRESHOLD);

        // Rollback should be committed automatically now.
        // Give time for rollback to be committed. This could take a while,
        // because we need all of the following to happen:
        // 1. system_server comes back up and boot completes.
        // 2. Rollback health observer detects updatable crashing signal.
        // 3. Staged rollback session becomes ready.
        // 4. Device actually reboots.
        // So we give a generous timeout here.
        assertThat(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5))).isTrue();
        getDevice().waitForDeviceAvailable();

        // verify rollback committed
        runPhase("testNativeWatchdogTriggersRollback_Phase3_VerifyRollback");

        assertThat(mLogger).eventOccurred(ROLLBACK_INITIATE, null, REASON_NATIVE_CRASH, null);
        assertThat(mLogger).eventOccurred(ROLLBACK_BOOT_TRIGGERED, null, null, null);
        assertThat(mLogger).eventOccurred(ROLLBACK_SUCCESS, null, null, null);
    }

    @Test
    public void testNativeWatchdogTriggersRollbackForAll() throws Exception {
        // This test requires committing multiple staged rollbacks
        assumeTrue(isCheckpointSupported());

        // Install a package with rollback enabled.
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase1_InstallA");
        getDevice().reboot();

        // Once previous staged install is applied, install another package
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase2_InstallB");
        getDevice().reboot();

        // Verify the new staged install has also been applied successfully.
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase3_VerifyInstall");

        // crash system_server enough times to trigger a rollback
        crashProcess("system_server", NATIVE_CRASHES_THRESHOLD);

        // Rollback should be committed automatically now.
        // Give time for rollback to be committed. This could take a while,
        // because we need all of the following to happen:
        // 1. system_server comes back up and boot completes.
        // 2. Rollback health observer detects updatable crashing signal.
        // 3. Staged rollback session becomes ready.
        // 4. Device actually reboots.
        // So we give a generous timeout here.
        assertThat(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5))).isTrue();
        getDevice().waitForDeviceAvailable();

        // verify all available rollbacks have been committed
        runPhase("testNativeWatchdogTriggersRollbackForAll_Phase4_VerifyRollback");

        assertThat(mLogger).eventOccurred(ROLLBACK_INITIATE, null, REASON_NATIVE_CRASH, null);
        assertThat(mLogger).eventOccurred(ROLLBACK_BOOT_TRIGGERED, null, null, null);
        assertThat(mLogger).eventOccurred(ROLLBACK_SUCCESS, null, null, null);
    }

    /**
     * Tests rolling back user data where there are multiple rollbacks for that package.
     */
    @Test
    public void testPreviouslyAbandonedRollbacks() throws Exception {
        runPhase("testPreviouslyAbandonedRollbacks_Phase1_InstallAndAbandon");
        getDevice().reboot();
        runPhase("testPreviouslyAbandonedRollbacks_Phase2_Rollback");
        getDevice().reboot();
        runPhase("testPreviouslyAbandonedRollbacks_Phase3_VerifyRollback");
    }

    /**
     * Tests we can enable rollback for a allowlisted app.
     */
    @Test
    public void testRollbackAllowlistedApp() throws Exception {
        assumeTrue(hasMainlineModule());
        runPhase("testRollbackAllowlistedApp_Phase1_Install");
        getDevice().reboot();
        runPhase("testRollbackAllowlistedApp_Phase2_VerifyInstall");
    }

    @Test
    public void testRollbackDataPolicy() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_ce/0/rollback");

        runPhase("testRollbackDataPolicy_Phase1_Install");
        getDevice().reboot();
        runPhase("testRollbackDataPolicy_Phase2_Rollback");
        getDevice().reboot();
        runPhase("testRollbackDataPolicy_Phase3_VerifyRollback");

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc_ce/0/rollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_ce/0/rollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that userdata of apk-in-apex is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexWithApk() throws Exception {
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        pushTestApex();
        runPhase("testRollbackApexWithApk_Phase1_Install");
        getDevice().reboot();
        runPhase("testRollbackApexWithApk_Phase2_Rollback");
        getDevice().reboot();
        runPhase("testRollbackApexWithApk_Phase3_VerifyRollback");
    }

    /**
     * Tests that RollbackPackageHealthObserver is observing apk-in-apex.
     */
    @Test
    public void testRollbackApexWithApkCrashing() throws Exception {
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        pushTestApex();

        // Install an apex with apk that crashes
        runPhase("testRollbackApexWithApkCrashing_Phase1_Install");
        getDevice().reboot();
        // Verify apex was installed and then crash the apk
        runPhase("testRollbackApexWithApkCrashing_Phase2_Crash");
        // Wait for crash to trigger rollback
        assertThat(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5))).isTrue();
        getDevice().waitForDeviceAvailable();
        // Verify rollback occurred due to crash of apk-in-apex
        runPhase("testRollbackApexWithApkCrashing_Phase3_VerifyRollback");

        assertThat(mLogger).eventOccurred(ROLLBACK_INITIATE, null, REASON_APP_CRASH, TESTAPP_A);
        assertThat(mLogger).eventOccurred(ROLLBACK_BOOT_TRIGGERED, null, null, null);
        assertThat(mLogger).eventOccurred(ROLLBACK_SUCCESS, null, null, null);
    }

    /**
     * Tests that data in DE_sys apex data directory is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexDataDirectories_DeSys() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc/apexrollback");
        pushTestApex();

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirDeSys(APK_IN_APEX_TESTAPEX_NAME) + "/" + TEST_FILENAME_1;
        String oldFilePath2 =
                apexDataDirDeSys(APK_IN_APEX_TESTAPEX_NAME) + TEST_SUBDIR + TEST_FILENAME_2;
        runAsRoot(() -> {
            assertThat(getDevice().pushString(TEST_STRING_1, oldFilePath1)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_2, oldFilePath2)).isTrue();
        });

        // Install new version of the APEX with rollback enabled
        runPhase("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apexDataDirDeSys(APK_IN_APEX_TESTAPEX_NAME) + "/" + TEST_FILENAME_3;
        String newFilePath4 =
                apexDataDirDeSys(APK_IN_APEX_TESTAPEX_NAME) + TEST_SUBDIR + TEST_FILENAME_4;
        runAsRoot(() -> {
            getDevice().deleteFile(oldFilePath1);
            getDevice().deleteFile(oldFilePath2);
            assertThat(getDevice().pushString(TEST_STRING_3, newFilePath3)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_4, newFilePath4)).isTrue();
        });

        // Roll back the APEX
        runPhase("testRollbackApexDataDirectories_Phase2_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertThat(getDevice().pullFileContents(oldFilePath1)).isEqualTo(TEST_STRING_1);
            assertThat(getDevice().pullFileContents(oldFilePath2)).isEqualTo(TEST_STRING_2);
            assertThat(getDevice().pullFile(newFilePath3)).isNull();
            assertThat(getDevice().pullFile(newFilePath4)).isNull();
        });

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that data in DE (user) apex data directory is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexDataDirectories_DeUser() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_de/0/apexrollback");
        pushTestApex();

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirDeUser(
                APK_IN_APEX_TESTAPEX_NAME, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 =
                apexDataDirDeUser(APK_IN_APEX_TESTAPEX_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        runAsRoot(() -> {
            assertThat(getDevice().pushString(TEST_STRING_1, oldFilePath1)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_2, oldFilePath2)).isTrue();
        });

        // Install new version of the APEX with rollback enabled
        runPhase("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 =
                apexDataDirDeUser(APK_IN_APEX_TESTAPEX_NAME, 0) + "/" + TEST_FILENAME_3;
        String newFilePath4 =
                apexDataDirDeUser(APK_IN_APEX_TESTAPEX_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_4;
        runAsRoot(() -> {
            getDevice().deleteFile(oldFilePath1);
            getDevice().deleteFile(oldFilePath2);
            assertThat(getDevice().pushString(TEST_STRING_3, newFilePath3)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_4, newFilePath4)).isTrue();
        });

        // Roll back the APEX
        runPhase("testRollbackApexDataDirectories_Phase2_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertThat(getDevice().pullFileContents(oldFilePath1)).isEqualTo(TEST_STRING_1);
            assertThat(getDevice().pullFileContents(oldFilePath2)).isEqualTo(TEST_STRING_2);
            assertThat(getDevice().pullFile(newFilePath3)).isNull();
            assertThat(getDevice().pullFile(newFilePath4)).isNull();
        });

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc_de/0/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_de/0/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that data in CE apex data directory is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexDataDirectories_Ce() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_ce/0/apexrollback");
        pushTestApex();

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 =
                apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        runAsRoot(() -> {
            assertThat(getDevice().pushString(TEST_STRING_1, oldFilePath1)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_2, oldFilePath2)).isTrue();
        });

        // Install new version of the APEX with rollback enabled
        runPhase("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + "/" + TEST_FILENAME_3;
        String newFilePath4 =
                apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_4;
        runAsRoot(() -> {
            getDevice().deleteFile(oldFilePath1);
            getDevice().deleteFile(oldFilePath2);
            assertThat(getDevice().pushString(TEST_STRING_3, newFilePath3)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_4, newFilePath4)).isTrue();
        });

        // Roll back the APEX
        runPhase("testRollbackApexDataDirectories_Phase2_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertThat(getDevice().pullFileContents(oldFilePath1)).isEqualTo(TEST_STRING_1);
            assertThat(getDevice().pullFileContents(oldFilePath2)).isEqualTo(TEST_STRING_2);
            assertThat(getDevice().pullFile(newFilePath3)).isNull();
            assertThat(getDevice().pullFile(newFilePath4)).isNull();
        });

        // Verify snapshots are deleted after restoration
        List<String> after = getSnapshotDirectories("/data/misc_ce/0/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_ce/0/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        assertDirectoryIsEmpty(after.get(0));
    }

    /**
     * Tests that data in DE apk data directory is restored when apk is rolled back.
     */
    @Test
    public void testRollbackApkDataDirectories_De() throws Exception {
        // Install version 1 of TESTAPP_A
        runPhase("testRollbackApkDataDirectories_Phase1_InstallV1");

        // Push files to apk data directory
        String oldFilePath1 = apkDataDirDe(TESTAPP_A, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 = apkDataDirDe(TESTAPP_A, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        runAsRoot(() -> {
            assertThat(getDevice().pushString(TEST_STRING_1, oldFilePath1)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_2, oldFilePath2)).isTrue();
        });

        // Install version 2 of TESTAPP_A with rollback enabled
        runPhase("testRollbackApkDataDirectories_Phase2_InstallV2");
        getDevice().reboot();

        // Replace files in data directory
        String newFilePath3 = apkDataDirDe(TESTAPP_A, 0) + "/" + TEST_FILENAME_3;
        String newFilePath4 = apkDataDirDe(TESTAPP_A, 0) + TEST_SUBDIR + TEST_FILENAME_4;
        runAsRoot(() -> {
            getDevice().deleteFile(oldFilePath1);
            getDevice().deleteFile(oldFilePath2);
            assertThat(getDevice().pushString(TEST_STRING_3, newFilePath3)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_4, newFilePath4)).isTrue();
        });

        // Roll back the APK
        runPhase("testRollbackApkDataDirectories_Phase3_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertThat(getDevice().pullFileContents(oldFilePath1)).isEqualTo(TEST_STRING_1);
            assertThat(getDevice().pullFileContents(oldFilePath2)).isEqualTo(TEST_STRING_2);
            assertThat(getDevice().pullFile(newFilePath3)).isNull();
            assertThat(getDevice().pullFile(newFilePath4)).isNull();
        });
    }

    @Test
    public void testExpireApexRollback() throws Exception {
        List<String> before = getSnapshotDirectories("/data/misc_ce/0/apexrollback");
        pushTestApex();

        // Push files to apex data directory
        String oldFilePath1 = apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + "/" + TEST_FILENAME_1;
        String oldFilePath2 =
                apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + TEST_SUBDIR + TEST_FILENAME_2;
        runAsRoot(() -> {
            assertThat(getDevice().pushString(TEST_STRING_1, oldFilePath1)).isTrue();
            assertThat(getDevice().pushString(TEST_STRING_2, oldFilePath2)).isTrue();
        });

        // Install new version of the APEX with rollback enabled
        runPhase("testRollbackApexDataDirectories_Phase1_Install");
        getDevice().reboot();

        List<String> after = getSnapshotDirectories("/data/misc_ce/0/apexrollback");
        // Only check directories newly created during the test
        after.removeAll(before);
        // There should be only one /data/misc_ce/0/apexrollback/<rollbackId> created during test
        assertThat(after).hasSize(1);
        // Expire all rollbacks and check CE snapshot directories are deleted
        runPhase("expireRollbacks");
        runAsRoot(() -> {
            for (String dir : after) {
                assertThat(getDevice().getFileEntry(dir)).isNull();
            }
        });
    }

    private void pushTestApex() throws Exception {
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
        final String fileName = APK_IN_APEX_TESTAPEX_NAME + "_v1.apex";
        final File apex = buildHelper.getTestFile(fileName);
        try {
            getDevice().enableAdbRoot();
            getDevice().remountSystemWritable();
            assertThat(getDevice().pushFile(apex, "/system/apex/" + fileName)).isTrue();
        } finally {
            getDevice().disableAdbRoot();
        }
        getDevice().reboot();
    }

    private static String apexDataDirDeSys(String apexName) {
        return String.format("/data/misc/apexdata/%s", apexName);
    }

    private static String apexDataDirDeUser(String apexName, int userId) {
        return String.format("/data/misc_de/%d/apexdata/%s", userId, apexName);
    }

    private static String apexDataDirCe(String apexName, int userId) {
        return String.format("/data/misc_ce/%d/apexdata/%s", userId, apexName);
    }

    private static String apkDataDirDe(String apexName, int userId) {
        return String.format("/data/user_de/%d/%s", userId, apexName);
    }

    private List<String> getSnapshotDirectories(String baseDir) throws Exception {
        try {
            getDevice().enableAdbRoot();
            IFileEntry f = getDevice().getFileEntry(baseDir);
            if (f == null) {
                Log.d(TAG, "baseDir doesn't exist: " + baseDir);
                return Collections.EMPTY_LIST;
            }
            List<String> list = f.getChildren(false)
                    .stream().filter(entry -> entry.getName().matches("\\d+(-prerestore)?"))
                    .map(entry -> entry.getFullPath())
                    .collect(Collectors.toList());
            Log.d(TAG, "getSnapshotDirectories=" + list);
            return list;
        } finally {
            getDevice().disableAdbRoot();
        }
    }

    private void assertDirectoryIsEmpty(String path) throws Exception {
        try {
            getDevice().enableAdbRoot();
            IFileEntry file = getDevice().getFileEntry(path);
            assertWithMessage("Not a directory: " + path).that(file.isDirectory()).isTrue();
            assertWithMessage("Directory not empty: " + path)
                    .that(file.getChildren(false)).isEmpty();
        } catch (DeviceNotAvailableException e) {
            fail("Can't access directory: " + path);
        } finally {
            getDevice().disableAdbRoot();
        }
    }

    private void crashProcess(String processName, int numberOfCrashes) throws Exception {
        String pid = "";
        String lastPid = "invalid";
        for (int i = 0; i < numberOfCrashes; ++i) {
            // This condition makes sure before we kill the process, the process is running AND
            // the last crash was finished.
            while ("".equals(pid) || lastPid.equals(pid)) {
                pid = getDevice().executeShellCommand("pidof " + processName);
            }
            getDevice().executeShellCommand("kill " + pid);
            lastPid = pid;
        }
    }

    private boolean isCheckpointSupported() throws Exception {
        try {
            runPhase("isCheckpointSupported");
            return true;
        } catch (AssertionError ignore) {
            return false;
        }
    }

    /**
     * True if this build has mainline modules installed.
     */
    private boolean hasMainlineModule() throws Exception {
        try {
            runPhase("hasMainlineModule");
            return true;
        } catch (AssertionError ignore) {
            return false;
        }
    }

    @FunctionalInterface
    private interface ExceptionalRunnable {
        void run() throws Exception;
    }

    private void runAsRoot(ExceptionalRunnable runnable) throws Exception {
        try {
            getDevice().enableAdbRoot();
            runnable.run();
        } finally {
            getDevice().disableAdbRoot();
        }
    }
}
