/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.os;

import dalvik.system.SamplingProfiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import android.util.Log;
import android.os.*;

/**
 * Integrates the framework with Dalvik's sampling profiler.
 */
public class SamplingProfilerIntegration {

    private static final String TAG = "SamplingProfilerIntegration";

    private static final boolean enabled;
    private static final Executor snapshotWriter;
    static {
        enabled = "1".equals(SystemProperties.get("persist.sampling_profiler"));
        if (enabled) {
            snapshotWriter = Executors.newSingleThreadExecutor();
            Log.i(TAG, "Profiler is enabled.");
        } else {
            snapshotWriter = null;
            Log.i(TAG, "Profiler is disabled.");
        }
    }

    /**
     * Is profiling enabled?
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Starts the profiler if profiling is enabled.
     */
    public static void start() {
        if (!enabled) return;
        SamplingProfiler.getInstance().start(10);
    }

    /** Whether or not we've created the snapshots dir. */
    static boolean dirMade = false;

    /** Whether or not a snapshot is being persisted. */
    static volatile boolean pending;

    /**
     * Writes a snapshot to the SD card if profiling is enabled.
     */
    public static void writeSnapshot(final String name) {
        if (!enabled) return;

        /*
         * If we're already writing a snapshot, don't bother enqueing another
         * request right now. This will reduce the number of individual
         * snapshots and in turn the total amount of memory consumed (one big
         * snapshot is smaller than N subset snapshots).
         */
        if (!pending) {
            pending = true;
            snapshotWriter.execute(new Runnable() {
                public void run() {
                    String dir =
                        Environment.getExternalStorageDirectory().getPath() + "/snapshots";
                    if (!dirMade) {
                        new File(dir).mkdirs();
                        if (new File(dir).isDirectory()) {
                            dirMade = true;
                        } else {
                            Log.w(TAG, "Creation of " + dir + " failed.");
                            return;
                        }
                    }
                    try {
                        writeSnapshot(dir, name);
                    } finally {
                        pending = false;
                    }
                }
            });
        }
    }

    /**
     * Writes the zygote's snapshot to internal storage if profiling is enabled.
     */
    public static void writeZygoteSnapshot() {
        if (!enabled) return;

        String dir = "/data/zygote/snapshots";
        new File(dir).mkdirs();
        writeSnapshot(dir, "zygote");
    }

    private static void writeSnapshot(String dir, String name) {
        byte[] snapshot = SamplingProfiler.getInstance().snapshot();
        if (snapshot == null) {
            return;
        }

        /*
         * We use the current time as a unique ID. We can't use a counter
         * because processes restart. This could result in some overlap if
         * we capture two snapshots in rapid succession.
         */
        long start = System.currentTimeMillis();
        String path = dir + "/" + name.replace(':', '.') + "-" +
                + System.currentTimeMillis() + ".snapshot";
        try {
            // Try to open the file a few times. The SD card may not be mounted.
            FileOutputStream out;
            int count = 0;
            while (true) {
                try {
                    out = new FileOutputStream(path);
                    break;
                } catch (FileNotFoundException e) {
                    if (++count > 3) {
                        Log.e(TAG, "Could not open " + path + ".");
                        return;
                    }

                    // Sleep for a bit and then try again.
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException e1) { /* ignore */ }
                }
            }

            try {
                out.write(snapshot);
            } finally {
                out.close();
            }
            long elapsed = System.currentTimeMillis() - start;
            Log.i(TAG, "Wrote snapshot for " + name
                    + " in " + elapsed + "ms.");
        } catch (IOException e) {
            Log.e(TAG, "Error writing snapshot.", e);
        }
    }
}
