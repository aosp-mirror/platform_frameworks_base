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
package com.android.statsd.loadtest;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Prints some information about the device via Dumpsys in order to evaluate health metrics. */
public class PerfData {

    private static final String TAG = "PerfData";
    private static final String DUMP_FILENAME = TAG + "_dump.tmp";

    public void resetData(Context context) {
        runDumpsysStats(context, "batterystats", "--reset");
    }

    public void publishData(Context context, boolean placebo, int replication, long bucketMins,
        long periodSecs, int burst) {
        publishBatteryData(context, placebo, replication, bucketMins, periodSecs, burst);
    }

    private void publishBatteryData(Context context, boolean placebo, int replication,
        long bucketMins, long periodSecs, int burst) {
        // Don't use --checkin.
        runDumpsysStats(context, "batterystats");
        writeBatteryData(context, placebo, replication, bucketMins, periodSecs, burst);
    }

    private void runDumpsysStats(Context context, String cmd, String... args) {
        boolean success = false;
        // Call dumpsys Dump statistics to a file.
        FileOutputStream fo = null;
        try {
            fo = context.openFileOutput(DUMP_FILENAME, Context.MODE_PRIVATE);
            if (!Debug.dumpService(cmd, fo.getFD(), args)) {
                Log.w(TAG, "Dumpsys failed.");
            }
            success = true;
        } catch (IOException | SecurityException | NullPointerException e) {
            // SecurityException may occur when trying to dump multi-user info.
            // NPE can occur during dumpService  (root cause unknown).
            throw new RuntimeException(e);
        } finally {
            closeQuietly(fo);
        }
    }

    private String readDumpFile(Context context) {
        StringBuilder sb = new StringBuilder();
        FileInputStream fi = null;
        BufferedReader br = null;
        try {
            fi = context.openFileInput(DUMP_FILENAME);
            br = new BufferedReader(new InputStreamReader(fi));
            String line = br.readLine();
            while (line != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
                line = br.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeQuietly(br);
        }
        return sb.toString();
    }

    private static void closeQuietly(@Nullable Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }

    public void writeBatteryData(Context context, boolean placebo, int replication, long bucketMins,
        long periodSecs, int burst) {
        BatteryStatsParser parser = new BatteryStatsParser();
        FileInputStream fi = null;
        BufferedReader br = null;
        String suffix = new SimpleDateFormat("YYYY_MM_dd_HH_mm_ss").format(new Date());
        File batteryDataFile = new File(getStorageDir(), "battery_" + suffix + ".csv");
        Log.d(TAG, "Writing battery data to " + batteryDataFile.getAbsolutePath());

        FileWriter writer = null;
        try {
            fi = context.openFileInput(DUMP_FILENAME);
            writer = new FileWriter(batteryDataFile);
            writer.append("time,battery_level"
                + getColumnName(placebo, replication, bucketMins, periodSecs, burst) + "\n");
            br = new BufferedReader(new InputStreamReader(fi));
            String line = br.readLine();
            while (line != null) {
                String recordLine = parser.parseLine(line);
                if (recordLine != null) {
                    writer.append(recordLine);
                }
                line = br.readLine();
            }
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeQuietly(writer);
            closeQuietly(br);
        }
    }

    private File getStorageDir() {
        File file = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS), "loadtest");
        if (!file.mkdirs()) {
            Log.e(TAG, "Directory not created");
        }
        return file;
    }

    private String getColumnName(boolean placebo, int replication, long bucketMins, long periodSecs,
        int burst) {
        if (placebo) {
            return "_placebo_p=" + periodSecs;
        }
        return "_r=" + replication + "_bkt=" + bucketMins + "_p=" + periodSecs + "_bst=" + burst;
    }
}
