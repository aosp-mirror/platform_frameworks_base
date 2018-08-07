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
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.os.Debug;

import com.android.internal.os.StatsdConfigProto.TimeUnit;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public abstract class PerfDataRecorder {
    private static final String TAG = "loadtest.PerfDataRecorder";

    protected final String mTimeAsString;
    protected final String mColumnSuffix;

    protected PerfDataRecorder(boolean placebo, int replication, TimeUnit bucket, long periodSecs,
        int burst, boolean includeCountMetric, boolean includeDurationMetric,
        boolean includeEventMetric,  boolean includeValueMetric, boolean includeGaugeMetric) {
        mTimeAsString = new SimpleDateFormat("YYYY_MM_dd_HH_mm_ss").format(new Date());
        mColumnSuffix = getColumnSuffix(placebo, replication, bucket, periodSecs, burst,
            includeCountMetric, includeDurationMetric, includeEventMetric, includeValueMetric,
            includeGaugeMetric);
    }

    /** Starts recording performance data. */
    public abstract void startRecording(Context context);

    /** Called periodically. For the recorder to sample data, if needed. */
    public abstract void onAlarm(Context context);

    /** Stops recording performance data, and writes it to disk. */
    public abstract void stopRecording(Context context);

    /** Runs the dumpsys command. */
    protected void runDumpsysStats(Context context, String dumpFilename, String cmd,
        String... args) {
        boolean success = false;
        // Call dumpsys Dump statistics to a file.
        FileOutputStream fo = null;
        try {
            fo = context.openFileOutput(dumpFilename, Context.MODE_PRIVATE);
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

    /**
     * Reads a text file and parses each line, one by one. The result of the parsing is stored
     * in the passed {@link StringBuffer}.
     */
    protected void readDumpData(Context context, String dumpFilename, PerfParser parser,
        StringBuilder sb) {
        FileInputStream fi = null;
        BufferedReader br = null;
        try {
            fi = context.openFileInput(dumpFilename);
            br = new BufferedReader(new InputStreamReader(fi));
            String line = br.readLine();
            while (line != null) {
                String recordLine = parser.parseLine(line);
                if (recordLine != null) {
                  sb.append(recordLine).append('\n');
                }
                line = br.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeQuietly(br);
        }
    }

    /** Writes CSV data to a file. */
    protected void writeData(Context context, String filePrefix, String columnPrefix,
        StringBuilder sb) {
        File dataFile = new File(getStorageDir(), filePrefix + mTimeAsString + ".csv");

        FileWriter writer = null;
        try {
            writer = new FileWriter(dataFile);
            writer.append(columnPrefix + mColumnSuffix + "\n");
            writer.append(sb.toString());
            writer.flush();
            Log.d(TAG, "Finished writing data at " + dataFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeQuietly(writer);
        }
    }

    /** Gets the suffix to use in the column name for perf data. */
    private String getColumnSuffix(boolean placebo, int replication, TimeUnit bucket,
        long periodSecs, int burst, boolean includeCountMetric, boolean includeDurationMetric,
        boolean includeEventMetric,  boolean includeValueMetric, boolean includeGaugeMetric) {
        if (placebo) {
            return "_placebo_p=" + periodSecs;
        }
        StringBuilder sb = new StringBuilder()
            .append("_r=" + replication)
            .append("_bkt=" + bucket)
            .append("_p=" + periodSecs)
            .append("_bst=" + burst)
            .append("_m=");
        if (includeCountMetric) {
            sb.append("c");
        }
        if (includeEventMetric) {
            sb.append("e");
        }
        if (includeDurationMetric) {
            sb.append("d");
        }
        if (includeGaugeMetric) {
            sb.append("g");
        }
        if (includeValueMetric) {
            sb.append("v");
        }
        return sb.toString();
    }

    private File getStorageDir() {
        File file = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS), "loadtest/" + mTimeAsString);
        if (!file.mkdirs()) {
            Log.e(TAG, "Directory not created");
        }
        return file;
    }

    private void closeQuietly(@Nullable Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }
}
