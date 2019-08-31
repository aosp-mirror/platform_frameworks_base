/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.pm;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for {@link BackgroundDexOptService}.
 *
 * Tests various scenarios around BackgroundDexOptService.
 * 1. Under normal conditions, check that dexopt upgrades test app to
 * $(getprop pm.dexopt.bg-dexopt).
 * 2. Under low storage conditions and package is unused, check
 * that dexopt downgrades test app to $(getprop pm.dexopt.inactive).
 * 3. Under low storage conditions and package is recently used, check
 * that dexopt upgrades test app to $(getprop pm.dexopt.bg-dexopt).
 *
 * When downgrade feature is on (downgrade_unused_apps_enabled flag is set to true):
 * 4  On low storage, check that the inactive packages are downgraded.
 * 5. On low storage, check that used packages are upgraded.
 * 6. On storage completely full, dexopt fails.
 * 7. Not on low storage, unused packages are upgraded.
 * 8. Low storage, unused app is downgraded. When app is used again, app is upgraded.
 *
 * Each test case runs "cmd package bg-dexopt-job com.android.frameworks.bgdexopttest".
 *
 * The setup for these tests make sure this package has been configured to have been recently used
 * plus installed far enough in the past. If a test case requires that this package has not been
 * recently used, it sets the time forward more than
 * `getprop pm.dexopt.downgrade_after_inactive_days` days.
 *
 * For some of the tests, the DeviceConfig flags inactive_app_threshold_days and
 * downgrade_unused_apps_enabled are set. These turn on/off the downgrade unused apps feature for
 * all devices and set the time threshold for unused apps.
 *
 * For tests that require low storage, the phone is filled up.
 *
 * Run with "atest BackgroundDexOptServiceIntegrationTests".
 */
@RunWith(JUnit4.class)
public final class BackgroundDexOptServiceIntegrationTests {

    private static final String TAG = BackgroundDexOptServiceIntegrationTests.class.getSimpleName();

    // Name of package to test on.
    private static final String PACKAGE_NAME = "com.android.frameworks.bgdexopttest";
    // Name of file used to fill up storage.
    private static final String BIG_FILE = "bigfile";
    private static final String BG_DEXOPT_COMPILER_FILTER = SystemProperties.get(
            "pm.dexopt.bg-dexopt");
    private static final String DOWNGRADE_COMPILER_FILTER = SystemProperties.get(
            "pm.dexopt.inactive");
    private static final long DOWNGRADE_AFTER_DAYS = SystemProperties.getLong(
            "pm.dexopt.downgrade_after_inactive_days", 0);
    // Needs to be between 1.0 and 2.0.
    private static final double LOW_STORAGE_MULTIPLIER = 1.5;
    private static final int DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS = 15;

    // The file used to fill up storage.
    private File mBigFile;

    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    // Remember start time.
    @BeforeClass
    public static void setUpAll() {
        if (!SystemProperties.getBoolean("pm.dexopt.disable_bg_dexopt", false)) {
            throw new RuntimeException(
                    "bg-dexopt is not disabled (set pm.dexopt.disable_bg_dexopt to true)");
        }
        if (DOWNGRADE_AFTER_DAYS < 1) {
            throw new RuntimeException(
                    "pm.dexopt.downgrade_after_inactive_days must be at least 1");
        }
        if ("quicken".equals(BG_DEXOPT_COMPILER_FILTER)) {
            throw new RuntimeException("pm.dexopt.bg-dexopt should not be \"quicken\"");
        }
        if ("quicken".equals(DOWNGRADE_COMPILER_FILTER)) {
            throw new RuntimeException("pm.dexopt.inactive should not be \"quicken\"");
        }
    }


    private static Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Before
    public void setUp() throws IOException {
        File dataDir = getContext().getDataDir();
        mBigFile = new File(dataDir, BIG_FILE);
    }

    @After
    public void tearDown() {
        if (mBigFile.exists()) {
            boolean result = mBigFile.delete();
            if (!result) {
                throw new RuntimeException("Couldn't delete big file");
            }
        }
    }

    // Return the content of the InputStream as a String.
    private static String inputStreamToString(InputStream is) throws IOException {
        char[] buffer = new char[1024];
        StringBuilder builder = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(is)) {
            for (; ; ) {
                int count = reader.read(buffer, 0, buffer.length);
                if (count < 0) {
                    break;
                }
                builder.append(buffer, 0, count);
            }
        }
        return builder.toString();
    }

    // Run the command and return the stdout.
    private static String runShellCommand(String cmd) throws IOException {
        Log.i(TAG, String.format("running command: '%s'", cmd));
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(cmd);
        byte[] buf = new byte[512];
        int bytesRead;
        FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        StringBuilder stdout = new StringBuilder();
        while ((bytesRead = fis.read(buf)) != -1) {
            stdout.append(new String(buf, 0, bytesRead));
        }
        fis.close();
        Log.i(TAG, "stdout");
        Log.i(TAG, stdout.toString());
        return stdout.toString();
    }

    // Run the command and return the stdout split by lines.
    private static String[] runShellCommandSplitLines(String cmd) throws IOException {
        return runShellCommand(cmd).split("\n");
    }

    // Return the compiler filter of a package.
    private static String getCompilerFilter(String pkg) throws IOException {
        String cmd = String.format("dumpsys package %s", pkg);
        String[] lines = runShellCommandSplitLines(cmd);
        final String substr = "[status=";
        for (String line : lines) {
            int startIndex = line.indexOf(substr);
            if (startIndex < 0) {
                continue;
            }
            startIndex += substr.length();
            int endIndex = line.indexOf(']', startIndex);
            return line.substring(startIndex, endIndex);
        }
        throw new RuntimeException("Couldn't find compiler filter in dumpsys package");
    }

    // Return the number of bytes available in the data partition.
    private static long getDataDirUsableSpace() {
        return Environment.getDataDirectory().getUsableSpace();
    }

    // Fill up the storage until there are bytesRemaining number of bytes available in the data
    // partition. Writes to the current package's data directory.
    private void fillUpStorage(long bytesRemaining) throws IOException {
        Log.i(TAG, String.format("Filling up storage with %d bytes remaining", bytesRemaining));
        logSpaceRemaining();
        long numBytesToAdd = getDataDirUsableSpace() - bytesRemaining;
        String cmd = String.format("fallocate -l %d %s", numBytesToAdd, mBigFile.getAbsolutePath());
        runShellCommand(cmd);
        logSpaceRemaining();
    }

    private void fillUpStorageCompletely() throws IOException {
        fillUpStorage((getStorageLowBytes()));
    }

    // Fill up storage so that device is in low storage condition.
    private void fillUpToLowStorage() throws IOException {
        fillUpStorage((long) (getStorageLowBytes() * LOW_STORAGE_MULTIPLIER));
    }

    private void setInactivePackageThreshold(int threshold) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                "inactive_app_threshold_days", Integer.toString(threshold), false);
    }

    private void enableDowngradeFeature(boolean enabled) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
                "downgrade_unused_apps_enabled", Boolean.toString(enabled), false);
    }

    // TODO(aeubanks): figure out how to get scheduled bg-dexopt to run
    private static void runBackgroundDexOpt() throws IOException {
        String result = runShellCommand("cmd package bg-dexopt-job " + PACKAGE_NAME);
        if (!result.trim().equals("Success")) {
            throw new IllegalStateException("Expected command success, received >" + result + "<");
        }
    }

    // Set the time ahead of the last use time of the test app in days.
    private static void setTimeFutureDays(long futureDays) {
        setTimeFutureMillis(TimeUnit.DAYS.toMillis(futureDays));
    }

    // Set the time ahead of the last use time of the test app in milliseconds.
    private static void setTimeFutureMillis(long futureMillis) {
        long currentTime = System.currentTimeMillis();
        setTime(currentTime + futureMillis);
    }

    private static void setTime(long time) {
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        am.setTime(time);
    }

    // Return the number of free bytes when the data partition is considered low on storage.
    private static long getStorageLowBytes() {
        StorageManager storageManager = (StorageManager) getContext().getSystemService(
                Context.STORAGE_SERVICE);
        return storageManager.getStorageLowBytes(Environment.getDataDirectory());
    }

    // Log the amount of space remaining in the data directory.
    private static void logSpaceRemaining() throws IOException {
        runShellCommand("df -h /data");
    }

    // Compile the given package with the given compiler filter.
    private static void compilePackageWithFilter(String pkg, String filter) throws IOException {
        runShellCommand(String.format("cmd package compile -f -m %s %s", filter, pkg));
    }

    // Test that background dexopt under normal conditions succeeds.
    @Test
    public void testBackgroundDexOpt_normalConditions_dexOptSucceeds() throws IOException {
        // Set filter to quicken.
        compilePackageWithFilter(PACKAGE_NAME, "verify");
        Assert.assertEquals("verify", getCompilerFilter(PACKAGE_NAME));

        runBackgroundDexOpt();

        // Verify that bg-dexopt is successful.
        Assert.assertEquals(BG_DEXOPT_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
    }

    // Test that background dexopt under low storage conditions upgrades used packages.
    @Test
    public void testBackgroundDexOpt_lowStorage_usedPkgsUpgraded() throws IOException {
        // Should be less than DOWNGRADE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_AFTER_DAYS - 1;
        try {
            enableDowngradeFeature(false);
            // Set time to future.
            setTimeFutureDays(deltaDays);
            // Set filter to quicken.
            compilePackageWithFilter(PACKAGE_NAME, "quicken");
            Assert.assertEquals("quicken", getCompilerFilter(PACKAGE_NAME));
            // Fill up storage to trigger low storage threshold.
            fillUpToLowStorage();

            runBackgroundDexOpt();

            // Verify that downgrade did not happen.
            Assert.assertEquals(BG_DEXOPT_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
        } finally {
            // Reset time.
            setTimeFutureDays(-deltaDays);
        }
    }

    // Test that background dexopt under low storage conditions downgrades unused packages.
    // This happens if the system property pm.dexopt.downgrade_after_inactive_days is set
    // (e.g. on Android Go devices).
    @Test
    public void testBackgroundDexOpt_lowStorage_unusedPkgsDowngraded()
            throws IOException {
        // Should be more than DOWNGRADE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_AFTER_DAYS + 1;
        try {
            enableDowngradeFeature(false);
            // Set time to future.
            setTimeFutureDays(deltaDays);
            // Set filter to quicken.
            compilePackageWithFilter(PACKAGE_NAME, "quicken");
            Assert.assertEquals("quicken", getCompilerFilter(PACKAGE_NAME));
            // Fill up storage to trigger low storage threshold.
            fillUpToLowStorage();

            runBackgroundDexOpt();

            // Verify that downgrade is successful.
            Assert.assertEquals(DOWNGRADE_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
        } finally {
            // Reset time.
            setTimeFutureDays(-deltaDays);
        }
    }

    // Test that the background dexopt downgrades inactive packages when the downgrade feature is
    // enabled.
    @Test
    public void testBackgroundDexOpt_downgradeFeatureEnabled_lowStorage_inactivePkgsDowngraded()
            throws IOException {
        // Should be more than DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS + 1;
        try {
            enableDowngradeFeature(true);
            setInactivePackageThreshold(DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS);
            // Set time to future.
            setTimeFutureDays(deltaDays);
            // Set filter to quicken.
            compilePackageWithFilter(PACKAGE_NAME, "quicken");
            Assert.assertEquals("quicken", getCompilerFilter(PACKAGE_NAME));
            // Fill up storage to trigger low storage threshold.
            fillUpToLowStorage();

            runBackgroundDexOpt();

            // Verify that downgrade is successful.
            Assert.assertEquals(DOWNGRADE_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
        } finally {
            // Reset time.
            setTimeFutureDays(-deltaDays);
        }
    }

    // Test that the background dexopt upgrades used packages when the downgrade feature is enabled.
    // This test doesn't fill the device storage completely, but to a multiplier of the low storage
    // threshold and this is why apps can still be optimized.
    @Test
    public void testBackgroundDexOpt_downgradeFeatureEnabled_lowStorage_usedPkgsUpgraded()
            throws IOException {
        enableDowngradeFeature(true);
        // Set filter to quicken.
        compilePackageWithFilter(PACKAGE_NAME, "quicken");
        Assert.assertEquals("quicken", getCompilerFilter(PACKAGE_NAME));
        // Fill up storage to trigger low storage threshold.
        fillUpToLowStorage();

        runBackgroundDexOpt();

        /// Verify that bg-dexopt is successful in upgrading the used packages.
        Assert.assertEquals(BG_DEXOPT_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
    }

    // Test that the background dexopt fails and doesn't change the compilation filter of used
    // packages when the downgrade feature is enabled and the storage is filled up completely.
    // The bg-dexopt shouldn't optimise nor downgrade these packages.
    @Test
    public void testBackgroundDexOpt_downgradeFeatureEnabled_fillUpStorageCompletely_dexOptFails()
            throws IOException {
        enableDowngradeFeature(true);
        String previousCompilerFilter = getCompilerFilter(PACKAGE_NAME);

        // Fill up storage completely, without using a multiplier for the low storage threshold.
        fillUpStorageCompletely();

        // When the bg dexopt runs with the storage filled up completely, it will fail.
        mExpectedException.expect(IllegalStateException.class);
        runBackgroundDexOpt();

        /// Verify that bg-dexopt doesn't change the compilation filter of used apps.
        Assert.assertEquals(previousCompilerFilter, getCompilerFilter(PACKAGE_NAME));
    }

    // Test that the background dexopt upgrades the unused packages when the downgrade feature is
    // on if the device is not low on storage.
    @Test
    public void testBackgroundDexOpt_downgradeFeatureEnabled_notLowStorage_unusedPkgsUpgraded()
            throws IOException {
        // Should be more than DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS + 1;
        try {
            enableDowngradeFeature(true);
            setInactivePackageThreshold(DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS);
            // Set time to future.
            setTimeFutureDays(deltaDays);
            // Set filter to quicken.
            compilePackageWithFilter(PACKAGE_NAME, "quicken");
            Assert.assertEquals("quicken", getCompilerFilter(PACKAGE_NAME));

            runBackgroundDexOpt();

            // Verify that bg-dexopt is successful in upgrading the unused packages when the device
            // is not low on storage.
            Assert.assertEquals(BG_DEXOPT_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
        } finally {
            // Reset time.
            setTimeFutureDays(-deltaDays);
        }
    }

    // Test that when an unused package (which was downgraded) is used again, it's re-optimized when
    // bg-dexopt runs again.
    @Test
    public void testBackgroundDexOpt_downgradeFeatureEnabled_downgradedPkgsUpgradedAfterUse()
            throws IOException {
        // Should be more than DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS + 1;
        try {
            enableDowngradeFeature(true);
            setInactivePackageThreshold(DOWNGRADE_FEATURE_PKG_INACTIVE_AFTER_DAYS);
            // Set time to future.
            setTimeFutureDays(deltaDays);
            // Fill up storage to trigger low storage threshold.
            fillUpToLowStorage();
            // Set filter to quicken.
            compilePackageWithFilter(PACKAGE_NAME, "quicken");
            Assert.assertEquals("quicken", getCompilerFilter(PACKAGE_NAME));

            runBackgroundDexOpt();

            // Verify that downgrade is successful.
            Assert.assertEquals(DOWNGRADE_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));

            // Reset time.
            setTimeFutureDays(-deltaDays);
            deltaDays = 0;
            runBackgroundDexOpt();

            // Verify that bg-dexopt is successful in upgrading the unused packages that were used
            // again.
            Assert.assertEquals(BG_DEXOPT_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
        } finally {
            // Reset time.
            setTimeFutureDays(-deltaDays);
        }
    }
}
