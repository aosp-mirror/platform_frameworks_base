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

package com.google.android.startop.iorapd;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.text.SimpleDateFormat;

/**
 * Test for the work flow of iorap.
 *
 * <p> This test tests the function of iorap from:
 * perfetto collection -> compilation ->  prefetching -> version update -> perfetto collection.
 */
@RunWith(AndroidJUnit4.class)
public class IorapWorkFlowTest {
  private static final String TAG = "IorapWorkFlowTest";

  private static final String TEST_APP_VERSION_ONE_PATH = "/data/misc/iorapd/iorap_test_app_v1.apk";
  private static final String TEST_APP_VERSION_TWO_PATH = "/data/misc/iorapd/iorap_test_app_v2.apk";
  private static final String TEST_APP_VERSION_THREE_PATH = "/data/misc/iorapd/iorap_test_app_v3.apk";

  private static final String DB_PATH = "/data/misc/iorapd/sqlite.db";
  private static final Duration TIMEOUT = Duration.ofSeconds(300L);

  private UiDevice mDevice;

  @Before
  public void setUp() throws Exception {
    // Initialize UiDevice instance
    mDevice = UiDevice.getInstance(getInstrumentation());

    // Start from the home screen
    mDevice.pressHome();

    // Wait for launcher
    final String launcherPackage = mDevice.getLauncherPackageName();
    assertThat(launcherPackage, notNullValue());
    mDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), TIMEOUT.getSeconds());
  }

  @After
  public void tearDown() throws Exception {
    String packageName = "com.example.ioraptestapp";
    uninstallApk(packageName);
  }

  @Test (timeout = 300000)
  public void testNormalWorkFlow() throws Exception {
    assertThat(mDevice, notNullValue());

    // Install test app version one
    installApk(TEST_APP_VERSION_ONE_PATH);
    String packageName = "com.example.ioraptestapp";
    String activityName = "com.example.ioraptestapp.MainActivity";

    // Perfetto trace collection phase.
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/1L));
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/1L));
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/1L));

    // Trigger maintenance service for compilation.
    TimeUnit.SECONDS.sleep(5L);
    assertTrue(compile(packageName, activityName, /*version=*/1L));

    // Run app with prefetching
    assertTrue(startAppWithCompiledTrace(
        packageName, activityName, /*version=*/1L));
  }

  @Test (timeout = 300000)
  public void testUpdateApp() throws Exception {
    assertThat(mDevice, notNullValue());

    // Install test app version two,
    String packageName = "com.example.ioraptestapp";
    String activityName = "com.example.ioraptestapp.MainActivity";
    installApk(TEST_APP_VERSION_TWO_PATH);

    // Perfetto trace collection phase.
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/2L));
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/2L));
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/2L));

    // Trigger maintenance service for compilation.
    TimeUnit.SECONDS.sleep(5L);
    assertTrue(compile(packageName, activityName, /*version=*/2L));

    // Run app with prefetching
    assertTrue(startAppWithCompiledTrace(
        packageName, activityName, /*version=*/2L));

    // Update test app to version 3
    installApk(TEST_APP_VERSION_THREE_PATH);

    // Rerun app, should do pefetto tracing.
    assertTrue(startAppForPerfettoTrace(
        packageName, activityName, /*version=*/3L));
  }

  private static void installApk(String apkPath) throws Exception {
    // Disable the selinux to allow pm install apk in the dir.
    executeShellCommand("setenforce 0");
    executeShellCommand("pm install -r -d " + apkPath);
    executeShellCommand("setenforce 1");

  }

  private static void uninstallApk(String apkPath) throws Exception {
    executeShellCommand("pm uninstall " + apkPath);
  }

  /**
   * Starts the testing app to collect the perfetto trace.
   *
   * @param expectPerfettoTraceCount is the expected count of perfetto traces.
   */
  private boolean startAppForPerfettoTrace(
      String packageName, String activityName, long version)
      throws Exception {
    LogcatTimestamp timestamp = runAppOnce(packageName, activityName);
    return waitForPerfettoTraceSavedFromLogcat(
        packageName, activityName, version, timestamp);
  }

  private boolean startAppWithCompiledTrace(
      String packageName, String activityName, long version)
      throws Exception {
    LogcatTimestamp timestamp = runAppOnce(packageName, activityName);
    return waitForPrefetchingFromLogcat(
        packageName, activityName, version, timestamp);
  }

  private LogcatTimestamp runAppOnce(String packageName, String activityName) throws Exception {
    // Close the specified app if it's open
    closeApp(packageName);
    LogcatTimestamp timestamp = new LogcatTimestamp();
    // Launch the specified app
    startApp(packageName, activityName);
    // Wait for the app to appear
    mDevice.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT.getSeconds());
    return timestamp;
  }

  // Invokes the maintenance to compile the perfetto traces to compiled trace.
  private boolean compile(
      String packageName, String activityName, long version) throws Exception {
    // The job id (283673059) is defined in class IorapForwardingService.
    executeShellCommandViaTmpFile("cmd jobscheduler run -f android 283673059");
    return waitForFileExistence(getCompiledTracePath(packageName, activityName, version));
  }

  private String getCompiledTracePath(
      String packageName, String activityName, long version) {
    return String.format(
        "/data/misc/iorapd/%s/%d/%s/compiled_traces/compiled_trace.pb",
        packageName, version, activityName);
  }

  /**
   * Starts the testing app.
   */
  private void startApp(String packageName, String activityName) throws Exception {
    executeShellCommandViaTmpFile(
        String.format("am start %s/%s", packageName, activityName));
  }

  /**
   * Closes the testing app.
   * <p> Keep trying to kill the process of the app until no process of the app package
   * appears.</p>
   */
  private void closeApp(String packageName) throws Exception {
    while (true) {
      String pid = executeShellCommand("pidof " + packageName);
      if (pid.isEmpty()) {
        Log.i(TAG, "Closed app " + packageName);
        return;
      }
      executeShellCommand("kill -9 " + pid);
      TimeUnit.SECONDS.sleep(1L);
    }
  }

  /** Waits for a file to appear. */
  private boolean waitForFileExistence(String fileName) throws Exception {
    return retryWithTimeout(TIMEOUT, () -> {
      try {
        String fileExists = executeShellCommandViaTmpFile(
            String.format("test -f %s; echo $?", fileName));
        Log.i(TAG, fileName + " existence is " +  fileExists);
        return fileExists.trim().equals("0");
      } catch (Exception e) {
        Log.i(TAG, e.getMessage());
        return false;
      }
    });
  }

  /** Waits for the perfetto trace saved message from logcat. */
  private boolean waitForPerfettoTraceSavedFromLogcat(
      String packageName, String activityName, long version, LogcatTimestamp timestamp)
      throws Exception {
    Pattern p = Pattern.compile(".*"
        + getPerfettoTraceSavedIndicator(packageName, activityName, version)
        + "(.*[.]perfetto_trace[.]pb)\n.*", Pattern.DOTALL);

    return retryWithTimeout(TIMEOUT, () -> {
      try {
        String log = timestamp.getLogcatAfter();
        Matcher m = p.matcher(log);
        Log.d(TAG, "Tries to find perfetto trace...");
        if (!m.matches()) {
          Log.i(TAG, "Cannot find perfetto trace saved in log.");
          return false;
        }
        String filePath = m.group(1);
        Log.i(TAG, "Perfetto trace is saved to " + filePath);
        return true;
      } catch(Exception e) {
        Log.e(TAG, e.getMessage());
        return false;
      }
   });
  }

  private String getPerfettoTraceSavedIndicator(
      String packageName, String activityName, long version) {
    return String.format(
        "Perfetto TraceBuffer saved to file: /data/misc/iorapd/%s/%d/%s/raw_traces/",
        packageName, version, activityName);
  }

  /**
   * Waits for the prefetching log in the logcat.
   *
   * <p> When prefetching works, the perfetto traces should not be collected. </p>
   */
  private boolean waitForPrefetchingFromLogcat(
      String packageName, String activityName, long version, LogcatTimestamp timestamp)
      throws Exception {
    Pattern p = Pattern.compile(
    ".*" + getReadaheadIndicator(packageName, activityName, version) +
    ".*Total File Paths=(\\d+) \\(good: (\\d+[.]?\\d*)%\\)\n"
        + ".*Total Entries=(\\d+) \\(good: (\\d+[.]?\\d*)%\\)\n"
        + ".*Total Bytes=(\\d+) \\(good: (\\d+[.]?\\d*)%\\).*",
    Pattern.DOTALL);

    return retryWithTimeout(TIMEOUT, () -> {
      try {
        String log = timestamp.getLogcatAfter();
        Matcher m = p.matcher(log);
        if (!m.matches()) {
          Log.i(TAG, "Cannot find readahead log.");
          return false;
        }

        int totalFilePath = Integer.parseInt(m.group(1));
        float totalFilePathGoodRate = Float.parseFloat(m.group(2)) / 100;
        int totalEntries = Integer.parseInt(m.group(3));
        float totalEntriesGoodRate = Float.parseFloat(m.group(4)) / 100;
        int totalBytes = Integer.parseInt(m.group(5));
        float totalBytesGoodRate = Float.parseFloat(m.group(6)) / 100;

        Log.i(TAG, String.format(
            "totalFilePath: %d (good %.2f) totalEntries: %d (good %.2f) totalBytes: %d (good %.2f)",
            totalFilePath, totalFilePathGoodRate, totalEntries, totalEntriesGoodRate, totalBytes,
            totalBytesGoodRate));

        return totalFilePath > 0 &&
          totalEntries > 0 &&
          totalBytes > 0 &&
          totalFilePathGoodRate > 0.5 &&
          totalEntriesGoodRate > 0.5 &&
          totalBytesGoodRate > 0.5;
      } catch(Exception e) {
        return false;
      }
   });
  }

  private static String getReadaheadIndicator(
      String packageName, String activityName, long version) {
    return String.format(
        "Description = /data/misc/iorapd/%s/%d/%s/compiled_traces/compiled_trace.pb",
        packageName, version, activityName);
  }

  /** Retry until timeout. */
  private boolean retryWithTimeout(Duration timeout, BooleanSupplier supplier) throws Exception {
    long totalSleepTimeSeconds = 0L;
    long sleepIntervalSeconds = 2L;
    while (true) {
      if (supplier.getAsBoolean()) {
        return true;
      }
      TimeUnit.SECONDS.sleep(sleepIntervalSeconds);
      totalSleepTimeSeconds += sleepIntervalSeconds;
      if (totalSleepTimeSeconds > timeout.getSeconds()) {
        return false;
      }
    }
  }

  /**
   * Executes command in adb shell via a tmp file.
   *
   * <p> This should be run as root.</p>
   */
  private static String executeShellCommandViaTmpFile(String cmd) throws Exception {
    Log.i(TAG, "Execute via tmp file: " + cmd);
    Path tmp = null;
    try {
      tmp = Files.createTempFile(/*prefix=*/null, /*suffix=*/".sh");
      Files.write(tmp, cmd.getBytes(StandardCharsets.UTF_8));
      tmp.toFile().setExecutable(true);
      return UiDevice.getInstance(
          InstrumentationRegistry.getInstrumentation()).
          executeShellCommand(tmp.toString());
    } finally {
      if (tmp != null) {
        Files.delete(tmp);
      }
    }
  }

  /**
   * Executes command in adb shell.
   *
   * <p> This should be run as root.</p>
   */
  private static String executeShellCommand(String cmd) throws Exception {
    Log.i(TAG, "Execute: " + cmd);
    return UiDevice.getInstance(
        InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
  }

  static class LogcatTimestamp {
    private String epochTime;

    public LogcatTimestamp() throws Exception{
      long currentTimeMillis = System.currentTimeMillis();
      epochTime = String.format(
          "%d.%d", currentTimeMillis/1000, currentTimeMillis%1000);
      Log.i(TAG, "Current logcat timestamp is " + epochTime);
    }

    // For example, 1585264100.000
    public String getEpochTime() {
      return epochTime;
    }

    // Gets the logcat after this epoch time.
    public String getLogcatAfter() throws Exception {
      return executeShellCommandViaTmpFile(
          "logcat -v epoch -t '" + epochTime + "'");
    }
  }
}

