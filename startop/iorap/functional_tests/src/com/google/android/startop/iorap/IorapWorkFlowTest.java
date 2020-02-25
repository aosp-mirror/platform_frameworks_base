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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;


/**
 * Test for the work flow of iorap.
 *
 * <p> This test tests the function of iorap from perfetto collection -> compilation ->
 * prefetching.
 * </p>
 */
@RunWith(AndroidJUnit4.class)
public class IorapWorkFlowTest {

  private static final String TAG = "IorapWorkFlowTest";

  private static final String TEST_PACKAGE_NAME = "com.android.settings";
  private static final String TEST_ACTIVITY_NAME = "com.android.settings.Settings";

  private static final String DB_PATH = "/data/misc/iorapd/sqlite.db";
  private static final Duration TIMEOUT = Duration.ofSeconds(300L);

  private static final String READAHEAD_INDICATOR =
      "Description = /data/misc/iorapd/com.android.settings/-?\\d+/com.android.settings.Settings/compiled_traces/compiled_trace.pb";

  private UiDevice mDevice;

  @Before
  public void startMainActivityFromHomeScreen() throws Exception {
    // Initialize UiDevice instance
    mDevice = UiDevice.getInstance(getInstrumentation());

    // Start from the home screen
    mDevice.pressHome();

    // Wait for launcher
    final String launcherPackage = mDevice.getLauncherPackageName();
    assertThat(launcherPackage, notNullValue());
    mDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), TIMEOUT.getSeconds());
  }

  @Test (timeout = 300000)
  public void testApp() throws Exception {
    assertThat(mDevice, notNullValue());

    // Perfetto trace collection phase.
    assertTrue(startAppForPerfettoTrace(/*expectPerfettoTraceCount=*/1));
    assertTrue(startAppForPerfettoTrace(/*expectPerfettoTraceCount=*/2));
    assertTrue(startAppForPerfettoTrace(/*expectPerfettoTraceCount=*/3));
    assertTrue(checkPerfettoTracesExistence(TIMEOUT, 3));

    // Trigger maintenance service for compilation.
    assertTrue(compile(TIMEOUT));

    // Check if prefetching works.
    assertTrue(waitForPrefetchingFromLogcat(/*expectPerfettoTraceCount=*/3));
  }

  /**
   * Starts the testing app to collect the perfetto trace.
   *
   * @param expectPerfettoTraceCount is the expected count of perfetto traces.
   */
  private boolean startAppForPerfettoTrace(long expectPerfettoTraceCount)
      throws Exception {
    // Close the specified app if it's open
    closeApp();
    // Launch the specified app
    startApp();
    // Wait for the app to appear
    mDevice.wait(Until.hasObject(By.pkg(TEST_PACKAGE_NAME).depth(0)), TIMEOUT.getSeconds());

    String sql = "SELECT COUNT(*) FROM activities "
        + "JOIN app_launch_histories ON activities.id = app_launch_histories.activity_id "
        + "JOIN raw_traces ON raw_traces.history_id = app_launch_histories.id "
        + "WHERE activities.name = ?";
    return checkAndWaitEntriesNum(sql, new String[]{TEST_ACTIVITY_NAME}, expectPerfettoTraceCount,
        TIMEOUT);
  }

  // Invokes the maintenance to compile the perfetto traces to compiled trace.
  private boolean compile(Duration timeout) throws Exception {
    // The job id (283673059) is defined in class IorapForwardingService.
    executeShellCommand("cmd jobscheduler run -f android 283673059");

    // Wait for the compilation.
    String sql = "SELECT COUNT(*) FROM activities JOIN prefetch_files ON "
        + "activities.id = prefetch_files.activity_id "
        + "WHERE activities.name = ?";
    boolean result = checkAndWaitEntriesNum(sql, new String[]{TEST_ACTIVITY_NAME}, /*count=*/1,
        timeout);
    if (!result) {
      return false;
    }

    return retryWithTimeout(timeout, () -> {
      try {
        String compiledTrace = getCompiledTraceFilePath();
        File compiledTraceLocal = copyFileToLocal(compiledTrace, "compiled_trace.tmp");
        return compiledTraceLocal.exists();
      } catch (Exception e) {
        Log.i(TAG, e.getMessage());
        return false;
      }
    });
  }

  /**
   * Check if all the perfetto traces in the db exist.
   */
  private boolean checkPerfettoTracesExistence(Duration timeout, int expectPerfettoTraceCount)
      throws Exception {
    return retryWithTimeout(timeout, () -> {
      try {
        File dbFile = getIorapDb();
        List<String> traces = getPerfettoTracePaths(dbFile);
        assertEquals(traces.size(), expectPerfettoTraceCount);

        int count = 0;
        for (String trace : traces) {
          File tmp = copyFileToLocal(trace, "perfetto_trace.tmp" + count);
          ++count;
          Log.i(TAG, "Check perfetto trace: " + trace);
          if (!tmp.exists()) {
            Log.i(TAG, "Perfetto trace does not exist: " + trace);
            return false;
          }
        }
        return true;
      } catch (Exception e) {
        Log.i(TAG, e.getMessage());
        return false;
      }
    });
  }

  /**
   * Gets the perfetto traces file path from the db.
   */
  private List<String> getPerfettoTracePaths(File dbFile) throws Exception {
    String sql = "SELECT raw_traces.file_path FROM activities "
        + "JOIN app_launch_histories ON activities.id = app_launch_histories.activity_id "
        + "JOIN raw_traces ON raw_traces.history_id = app_launch_histories.id "
        + "WHERE activities.name = ?";

    List<String> perfettoTraces = new ArrayList<>();
    try (SQLiteDatabase db = SQLiteDatabase
        .openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
      Cursor cursor = db.rawQuery(sql, new String[]{TEST_ACTIVITY_NAME});
      while (cursor.moveToNext()) {
        perfettoTraces.add(cursor.getString(0));
      }
    }
    return perfettoTraces;
  }

  private String getCompiledTraceFilePath() throws Exception {
    File dbFile = getIorapDb();
    try (SQLiteDatabase db = SQLiteDatabase
        .openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
      String sql = "SELECT prefetch_files.file_path FROM activities JOIN prefetch_files ON "
              + "activities.id = prefetch_files.activity_id "
              + "WHERE activities.name = ?";
      return DatabaseUtils.stringForQuery(db, sql, new String[]{TEST_ACTIVITY_NAME});
    }
  }

  /**
   * Checks the number of entries in the database table.
   *
   * <p> Keep checking until the timeout.
   */
  private boolean checkAndWaitEntriesNum(String sql, String[] selectionArgs, long count,
      Duration timeout)
      throws Exception {
    return retryWithTimeout(timeout, () -> {
      try {
        File db = getIorapDb();
        long curCount = getEntriesNum(db, selectionArgs, sql);
        Log.i(TAG, String
            .format("For %s, current count is %d, expected count is :%d.", sql, curCount,
                count));
        return curCount == count;
      } catch (Exception e) {
        Log.i(TAG, e.getMessage());
        return false;
      }
    });
  }

  /**
   * Retry until timeout.
   */
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
   * Gets the number of entries in the query of sql.
   */
  private long getEntriesNum(File dbFile, String[] selectionArgs, String sql) throws Exception {
    try (SQLiteDatabase db = SQLiteDatabase
        .openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
      return DatabaseUtils.longForQuery(db, sql, selectionArgs);
    }
  }

  /**
   * Gets the iorapd sqlite db file.
   *
   * <p> The test cannot access the db file directly under "/data/misc/iorapd".
   * Copy it to the local directory and change the mode.
   */
  private File getIorapDb() throws Exception {
    File tmpDb = copyFileToLocal("/data/misc/iorapd/sqlite.db", "tmp.db");
    // Change the mode of the file to allow the access from test.
    executeShellCommand("chmod 777 " + tmpDb.getPath());
    return tmpDb;
  }

  /**
   * Copys a file to local directory.
   */
  private File copyFileToLocal(String src, String tgtFileName) throws Exception {
    File localDir = getApplicationContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
    File localFile = new File(localDir, tgtFileName);
    executeShellCommand(String.format("cp %s %s", src, localFile.getPath()));
    return localFile;
  }

  /**
   * Starts the testing app.
   */
  private void startApp() throws Exception {
    Context context = getApplicationContext();
    final Intent intent = context.getPackageManager()
        .getLaunchIntentForPackage(TEST_PACKAGE_NAME);
    context.startActivity(intent);
    Log.i(TAG, "Started app " + TEST_PACKAGE_NAME);
  }

  /**
   * Closes the testing app.
   * <p> Keep trying to kill the process of the app until no process of the app package
   * appears.</p>
   */
  private void closeApp() throws Exception {
    while (true) {
      String pid = executeShellCommand("pidof " + TEST_PACKAGE_NAME);
      if (pid.isEmpty()) {
        Log.i(TAG, "Closed app " + TEST_PACKAGE_NAME);
        return;
      }
      executeShellCommand("kill -9 " + pid);
      TimeUnit.SECONDS.sleep(1L);
    }
  }

  /**
   * Waits for the prefetching log in the logcat.
   *
   * <p> When prefetching works, the perfetto traces should not be collected. </p>
   */
  private boolean waitForPrefetchingFromLogcat(long expectPerfettoTraceCount) throws Exception {
    if (!startAppForPerfettoTrace(expectPerfettoTraceCount)) {
      return false;
    }

    String log = executeShellCommand("logcat -d");

    Pattern p = Pattern.compile(
    ".*" + READAHEAD_INDICATOR
        + ".*Total File Paths=(\\d+) \\(good: (\\d+[.]?\\d*)%\\)\n"
        + ".*Total Entries=(\\d+) \\(good: (\\d+[.]?\\d*)%\\)\n"
        + ".*Total Bytes=(\\d+) \\(good: (\\d+[.]?\\d*)%\\).*",
    Pattern.DOTALL);
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
        totalBytes > 100000 &&
        totalFilePathGoodRate > 0.5 &&
        totalEntriesGoodRate > 0.5 &&
        totalBytesGoodRate > 0.5;
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
}


