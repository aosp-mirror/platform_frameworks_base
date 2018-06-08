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
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
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
 * Each test case runs "cmd package bg-dexopt-job com.android.frameworks.bgdexopttest".
 *
 * The setup for these tests make sure this package has been configured to have been recently used
 * plus installed far enough in the past. If a test case requires that this package has not been
 * recently used, it sets the time forward more than
 * `getprop pm.dexopt.downgrade_after_inactive_days` days.
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

    // The file used to fill up storage.
    private File mBigFile;

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
        long startTime = System.nanoTime();
        Process p = Runtime.getRuntime().exec(cmd);
        int res;
        try {
            res = p.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        String stdout = inputStreamToString(p.getInputStream());
        String stderr = inputStreamToString(p.getErrorStream());
        long elapsedTime = System.nanoTime() - startTime;
        Log.i(TAG, String.format("ran command: '%s' in %d ms with return code %d", cmd,
                TimeUnit.NANOSECONDS.toMillis(elapsedTime), res));
        Log.i(TAG, "stdout");
        Log.i(TAG, stdout);
        Log.i(TAG, "stderr");
        Log.i(TAG, stderr);
        if (res != 0) {
            throw new RuntimeException(String.format("failed command: '%s'", cmd));
        }
        return stdout;
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

    // Fill up storage so that device is in low storage condition.
    private void fillUpToLowStorage() throws IOException {
        fillUpStorage((long) (getStorageLowBytes() * LOW_STORAGE_MULTIPLIER));
    }

    // TODO(aeubanks): figure out how to get scheduled bg-dexopt to run
    private static void runBackgroundDexOpt() throws IOException {
        runShellCommand("cmd package bg-dexopt-job " + PACKAGE_NAME);
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
    public void testBackgroundDexOpt() throws IOException {
        // Set filter to quicken.
        compilePackageWithFilter(PACKAGE_NAME, "verify");
        Assert.assertEquals("verify", getCompilerFilter(PACKAGE_NAME));

        runBackgroundDexOpt();

        // Verify that bg-dexopt is successful.
        Assert.assertEquals(BG_DEXOPT_COMPILER_FILTER, getCompilerFilter(PACKAGE_NAME));
    }

    // Test that background dexopt under low storage conditions upgrades used packages.
    @Test
    public void testBackgroundDexOptDowngradeSkipRecentlyUsedPackage() throws IOException {
        // Should be less than DOWNGRADE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_AFTER_DAYS - 1;
        try {
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
    @Test
    public void testBackgroundDexOptDowngradeSuccessful() throws IOException {
        // Should be more than DOWNGRADE_AFTER_DAYS.
        long deltaDays = DOWNGRADE_AFTER_DAYS + 1;
        try {
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

}
