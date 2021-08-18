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
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
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
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.B");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.C");
    }

    @After
    public void tearDown() throws Exception {
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.A");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.B");
        getDevice().uninstallPackage("com.android.cts.install.lib.testapp.C");
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

    private void waitForDeviceNotAvailable(long timeout, TimeUnit unit) {
        assertWithMessage("waitForDeviceNotAvailable() timed out in %s %s", timeout, unit)
                .that(getDevice().waitForDeviceNotAvailable(unit.toMillis(timeout))).isTrue();
    }

    /**
     * Tests watchdog triggered staged rollbacks involving only apks.
     */
    @Test
    public void testBadApkOnly() throws Exception {
        runPhase("testBadApkOnly_Phase1_Install");
        getDevice().reboot();
        runPhase("testBadApkOnly_Phase2_VerifyInstall");

        // Launch the app to crash to trigger rollback
        startActivity(TESTAPP_A);
        // Wait for reboot to happen
        waitForDeviceNotAvailable(2, TimeUnit.MINUTES);

        getDevice().waitForDeviceAvailable();

        runPhase("testBadApkOnly_Phase3_VerifyRollback");

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
        waitForDeviceNotAvailable(5, TimeUnit.MINUTES);
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
        waitForDeviceNotAvailable(5, TimeUnit.MINUTES);
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

    /**
     * Tests that userdata of apk-in-apex is restored when apex is rolled back.
     */
    @Test
    public void testRollbackApexWithApk() throws Exception {
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
        pushTestApex();

        // Install an apex with apk that crashes
        runPhase("testRollbackApexWithApkCrashing_Phase1_Install");
        getDevice().reboot();
        // Verify apex was installed and then crash the apk
        runPhase("testRollbackApexWithApkCrashing_Phase2_Crash");
        // Launch the app to crash to trigger rollback
        startActivity(TESTAPP_A);
        // Wait for reboot to happen
        waitForDeviceNotAvailable(2, TimeUnit.MINUTES);
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
            pushString(TEST_STRING_1, oldFilePath1);
            pushString(TEST_STRING_2, oldFilePath2);
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
            pushString(TEST_STRING_3, newFilePath3);
            pushString(TEST_STRING_4, newFilePath4);
        });

        // Roll back the APEX
        runPhase("testRollbackApexDataDirectories_Phase2_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertFileContents(TEST_STRING_1, oldFilePath1);
            assertFileContents(TEST_STRING_2, oldFilePath2);
            assertFileNotExists(newFilePath3);
            assertFileNotExists(newFilePath4);
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
            pushString(TEST_STRING_1, oldFilePath1);
            pushString(TEST_STRING_2, oldFilePath2);
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
            pushString(TEST_STRING_3, newFilePath3);
            pushString(TEST_STRING_4, newFilePath4);
        });

        // Roll back the APEX
        runPhase("testRollbackApexDataDirectories_Phase2_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertFileContents(TEST_STRING_1, oldFilePath1);
            assertFileContents(TEST_STRING_2, oldFilePath2);
            assertFileNotExists(newFilePath3);
            assertFileNotExists(newFilePath4);
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
            pushString(TEST_STRING_1, oldFilePath1);
            pushString(TEST_STRING_2, oldFilePath2);
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
            pushString(TEST_STRING_3, newFilePath3);
            pushString(TEST_STRING_4, newFilePath4);
        });

        // Roll back the APEX
        runPhase("testRollbackApexDataDirectories_Phase2_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertFileContents(TEST_STRING_1, oldFilePath1);
            assertFileContents(TEST_STRING_2, oldFilePath2);
            assertFileNotExists(newFilePath3);
            assertFileNotExists(newFilePath4);
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
            pushString(TEST_STRING_1, oldFilePath1);
            pushString(TEST_STRING_2, oldFilePath2);
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
            pushString(TEST_STRING_3, newFilePath3);
            pushString(TEST_STRING_4, newFilePath4);
        });

        // Roll back the APK
        runPhase("testRollbackApkDataDirectories_Phase3_Rollback");
        getDevice().reboot();

        // Verify that old files have been restored and new files are gone
        runAsRoot(() -> {
            assertFileContents(TEST_STRING_1, oldFilePath1);
            assertFileContents(TEST_STRING_2, oldFilePath2);
            assertFileNotExists(newFilePath3);
            assertFileNotExists(newFilePath4);
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
            pushString(TEST_STRING_1, oldFilePath1);
            pushString(TEST_STRING_2, oldFilePath2);
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
                assertFileNotExists(dir);
            }
        });
    }

    /**
     * Tests that packages are monitored across multiple reboots.
     */
    @Test
    public void testWatchdogMonitorsAcrossReboots() throws Exception {
        runPhase("testWatchdogMonitorsAcrossReboots_Phase1_Install");

        // The first reboot will make the rollback available.
        // Information about which packages are monitored will be persisted to a file before the
        // second reboot, and read from disk after the second reboot.
        getDevice().reboot();
        getDevice().reboot();

        runPhase("testWatchdogMonitorsAcrossReboots_Phase2_VerifyInstall");

        // Launch the app to crash to trigger rollback
        startActivity(TESTAPP_A);
        // Wait for reboot to happen
        waitForDeviceNotAvailable(2, TimeUnit.MINUTES);
        getDevice().waitForDeviceAvailable();

        runPhase("testWatchdogMonitorsAcrossReboots_Phase3_VerifyRollback");
    }

    /**
     * Tests an available rollback shouldn't be deleted when its session expires.
     */
    @Test
    public void testExpireSession() throws Exception {
        runPhase("testExpireSession_Phase1_Install");
        getDevice().reboot();
        runPhase("testExpireSession_Phase2_VerifyInstall");

        // Advance system clock by 7 days to expire the staged session
        Instant t1 = Instant.ofEpochMilli(getDevice().getDeviceDate());
        Instant t2 = t1.plusMillis(TimeUnit.DAYS.toMillis(7));
        runAsRoot(() -> getDevice().setDate(Date.from(t2)));

        // Somehow we need to wait for a while before reboot. Otherwise the change to the
        // system clock will be reset after reboot.
        Thread.sleep(3000);
        getDevice().reboot();
        runPhase("testExpireSession_Phase3_VerifyRollback");
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

    private void pushString(String contents, String path) throws Exception {
        assertWithMessage("Failed to push file to device, content=%s path=%s", contents, path)
                .that(getDevice().pushString(contents, path)).isTrue();
    }

    private void assertFileContents(String expectedContents, String path) throws Exception {
        String actualContents = getDevice().pullFileContents(path);
        assertWithMessage("Failed to retrieve file=%s", path).that(actualContents).isNotNull();
        assertWithMessage("Mismatched file contents, path=%s", path)
                .that(actualContents).isEqualTo(expectedContents);
    }

    private void assertFileNotExists(String path) throws Exception {
        assertWithMessage("File shouldn't exist, path=%s", path)
                .that(getDevice().getFileEntry(path)).isNull();
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

    private void startActivity(String packageName) throws Exception {
        String cmd = "am start -S -a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER " + packageName;
        getDevice().executeShellCommand(cmd);
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
