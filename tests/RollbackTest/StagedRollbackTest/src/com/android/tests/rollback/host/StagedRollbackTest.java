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

import static org.junit.Assume.assumeTrue;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
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
import java.util.concurrent.TimeUnit;

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
                apexDataDirCe(APK_IN_APEX_TESTAPEX_NAME, 0) + "*",
                "/system/apex/test.rebootless_apex_v*.apex",
                "/data/apex/active/test.apex.rebootless*.apex");
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
     * Tests that RollbackPackageHealthObserver is observing apk-in-apex.
     */
    @Test
    public void testRollbackApexWithApkCrashing() throws Exception {
        pushTestApex(APK_IN_APEX_TESTAPEX_NAME + "_v1.apex");

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
     * Tests rollback is supported correctly for rebootless apex
     */
    @Test
    public void testRollbackRebootlessApex() throws Exception {
        pushTestApex("test.rebootless_apex_v1.apex");
        runPhase("testRollbackRebootlessApex");
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

    private void pushTestApex(String fileName) throws Exception {
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
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

    private static String apexDataDirCe(String apexName, int userId) {
        return String.format("/data/misc_ce/%d/apexdata/%s", userId, apexName);
    }

    private void startActivity(String packageName) throws Exception {
        String cmd = "am start -S -a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER " + packageName;
        getDevice().executeShellCommand(cmd);
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
}
