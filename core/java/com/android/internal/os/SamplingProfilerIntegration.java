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

import android.content.pm.PackageInfo;
import dalvik.system.SamplingProfiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Log;
import android.os.*;

/**
 * Integrates the framework with Dalvik's sampling profiler.
 */
public class SamplingProfilerIntegration {

    private static final String TAG = "SamplingProfilerIntegration";

    public static final String SNAPSHOT_DIR = "/data/snapshots";

    private static final boolean enabled;
    private static final Executor snapshotWriter;
    private static final int samplingProfilerHz;
    
    /** Whether or not we've created the snapshots dir. */
    private static boolean dirMade = false;

    /** Whether or not a snapshot is being persisted. */
    private static final AtomicBoolean pending = new AtomicBoolean(false);

    static {
        samplingProfilerHz = SystemProperties.getInt("persist.sys.profiler_hz", 0);
        if (samplingProfilerHz > 0) {
            snapshotWriter = Executors.newSingleThreadExecutor();
            enabled = true;
            Log.i(TAG, "Profiler is enabled. Sampling Profiler Hz: " + samplingProfilerHz);
        } else {
            snapshotWriter = null;
            enabled = false;
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
        SamplingProfiler.getInstance().start(samplingProfilerHz);
    }

    /**
     * Writes a snapshot if profiling is enabled.
     */
    public static void writeSnapshot(final String processName, final PackageInfo packageInfo) {
        if (!enabled) return;

        /*
         * If we're already writing a snapshot, don't bother enqueueing another
         * request right now. This will reduce the number of individual
         * snapshots and in turn the total amount of memory consumed (one big
         * snapshot is smaller than N subset snapshots).
         */
        if (pending.compareAndSet(false, true)) {
            snapshotWriter.execute(new Runnable() {
                public void run() {
                    if (!dirMade) {
                        File dir = new File(SNAPSHOT_DIR);
                        dir.mkdirs();
                        // the directory needs to be writable to anybody
                        dir.setWritable(true, false);
                        // the directory needs to be executable to anybody
                        // don't know why yet, but mode 723 would work, while
                        // mode 722 throws FileNotFoundExecption at line 151
                        dir.setExecutable(true, false);
                        if (new File(SNAPSHOT_DIR).isDirectory()) {
                            dirMade = true;
                        } else {
                            Log.w(TAG, "Creation of " + SNAPSHOT_DIR + " failed.");
                            pending.set(false);
                            return;
                        }
                    }
                    try {
                        writeSnapshot(SNAPSHOT_DIR, processName, packageInfo);
                    } finally {
                        pending.set(false);
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
        writeSnapshot("zygote", null);
    }

    /**
     * pass in PackageInfo to retrieve various values for snapshot header
     */
    private static void writeSnapshot(String dir, String processName, PackageInfo packageInfo) {
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
        String name = processName.replaceAll(":", ".");
        String path = dir + "/" + name + "-" +System.currentTimeMillis() + ".snapshot";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(path);
            generateSnapshotHeader(name, packageInfo, out);
            out.write(snapshot);
        } catch (IOException e) {
            Log.e(TAG, "Error writing snapshot.", e);
        } finally {
            try {
                if(out != null) {
                    out.close();
                }
            } catch (IOException ex) {
                // let it go.
            }
        }
        // set file readable to the world so that SamplingProfilerService
        // can put it to dropbox
        new File(path).setReadable(true, false);

        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "Wrote snapshot for " + name + " in " + elapsed + "ms.");
    }

    /**
     * generate header for snapshots, with the following format (like http header):
     *
     * Version: <version number of profiler>\n
     * Process: <process name>\n
     * Package: <package name, if exists>\n
     * Package-Version: <version number of the package, if exists>\n
     * Build: <fingerprint>\n
     * \n
     * <the actual snapshot content begins here...>
     */
    private static void generateSnapshotHeader(String processName, PackageInfo packageInfo,
            FileOutputStream out) throws IOException {
        // profiler version
        out.write("Version: 1\n".getBytes());
        out.write(("Process: " + processName + "\n").getBytes());
        if(packageInfo != null) {
            out.write(("Package: " + packageInfo.packageName + "\n").getBytes());
            out.write(("Package-Version: " + packageInfo.versionCode + "\n").getBytes());
        }
        out.write(("Build: " + Build.FINGERPRINT + "\n").getBytes());
        // single blank line means the end of snapshot header.
        out.write("\n".getBytes());
    }
}
