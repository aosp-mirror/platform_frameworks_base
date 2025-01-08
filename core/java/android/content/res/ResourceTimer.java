/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;

import android.app.AppProtoEnums;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Provides access to the resource timers without intruding on other classes.
 * @hide
 */
public final class ResourceTimer {
    private static final String TAG = "ResourceTimer";

    // Enable metrics in this process.  The flag may be set false in some processes.  The flag is
    // never set true at runtime, so setting it false here disables the feature entirely.
    private static boolean sEnabled = true;

    // Set true for incremental metrics (the counters are reset after every fetch).  Set false for
    // cumulative metrics (the counters are never reset and accumulate values for the lifetime of
    // the process).
    private static boolean sIncrementalMetrics = true;

    // Set true to enable some debug behavior.
    private static boolean ENABLE_DEBUG = false;

    // The global lock.
    private static final Object sLock = new Object();

    // The singleton cache manager
    private static ResourceTimer sManager;

    // The handler for the timeouts.
    private static Handler mHandler;

    // The time at which the process started.
    private final static long sProcessStart = SystemClock.uptimeMillis();

    // Metrics are published at offsets from the process start.  Processes publish at five minutes
    // and one hour.  Thereafter, metrics are published every 12 hours.  The values in this array
    // are in units of minutes.
    private static final long[] sPublicationPoints = new long[]{ 5, 60, 60 * 12 };

    // The index of the latest publication point.
    private static int sCurrentPoint;

    /**
     * The runtime timer configuration.
     */
    private static class Config {
        // The number of timers in the runtime
        int maxTimer;
        // The number of histogram buckets per timer.
        int maxBuckets;
        // The number of "largest" values per timer.
        int maxLargest;
        // A string label for each timer.  This array has maxTimer elements.
        String[] timers;
    }

    /**
     * The timer information that is pulled from the native runtime.  All times have units of ns.
     */
    private static class Timer {
        int count;
        long total;
        int mintime;
        int maxtime;
        int[] largest;
        int[] percentile;
        @Override
        public String toString() {
            return TextUtils.formatSimple("%d:%d:%d:%d", count, total, mintime, maxtime);
        }
    }

    /**
     * A singleton Config.  This is initialized when the timers are started.
     */
    @GuardedBy("sLock")
    private static Config sConfig;

    /**
     * This array contains the statsd enum associated with each timer entry.  A value of NONE (0)
     * means that the entry should not be logged to statsd.  (This would be the case for timers
     * that are created for temporary debugging.)
     */
    @GuardedBy("sLock")
    private static int[] sApiMap;

    /**
     * A singleton Summary object that is refilled from the native side.  The length of the array
     * is the number of timers that can be fetched.  nativeGetTimers() will fill the array to the
     * smaller of the length of the array or the actual number of timers in the runtime.  The
     * actual number of timers in the run time is returned by the function.
     */
    @GuardedBy("sLock")
    private static Timer[] sTimers;

    /**
     * The time at which the local timer array was last updated.  This has the same units as
     * sProcessStart; the difference is the process run time.
     */
    @GuardedBy("sLock")
    private static long sLastUpdated = 0;

    // The constructor is private.  Use the factory to get a hold of the manager.
    private ResourceTimer() {
        throw new RuntimeException("ResourceTimer constructor");
    }

    /**
     * Start the manager.  This runs a periodic job that collects and publishes timer values.
     * This is not part of the constructor only because the looper failicities might not be
     * available at the beginning of time.
     */
    public static void start() {
        synchronized (sLock) {
            if (!sEnabled) {
                return;
            }
            if (mHandler != null) {
                // Nothing to be done.  The job has already been started.
                return;
            }
            if (Looper.getMainLooper() == null) {
                throw new RuntimeException("ResourceTimer started too early");
            }
            mHandler = new Handler(Looper.getMainLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        ResourceTimer.handleMessage(msg);
                    }
                };

            // Initialize the container that holds information from the native runtime.  The
            // container is built according to the dimensions returned by the native layer.
            sConfig = new Config();
            nativeEnableTimers(sConfig);
            sTimers = new Timer[sConfig.maxTimer];
            for (int i = 0; i < sTimers.length; i++) {
                sTimers[i] = new Timer();
                sTimers[i].percentile = new int[sConfig.maxBuckets];
                sTimers[i].largest = new int[sConfig.maxLargest];
            }
            // Map the values returned from the runtime to statsd enumerals  The runtime may
            // return timers that are not meant to be logged via statsd.  Such timers are mapped
            // to RESOURCE_API_NONE.
            sApiMap = new int[sConfig.maxTimer];
            for (int i = 0; i < sApiMap.length; i++) {
                if (sConfig.timers[i].equals("GetResourceValue")) {
                    sApiMap[i] = AppProtoEnums.RESOURCE_API_GET_VALUE;
                } else if (sConfig.timers[i].equals("RetrieveAttributes")) {
                    sApiMap[i] = AppProtoEnums.RESOURCE_API_RETRIEVE_ATTRIBUTES;
                } else {
                    sApiMap[i] = AppProtoEnums.RESOURCE_API_NONE;
                }
            }

            sCurrentPoint = 0;
            startTimer();
        }
    }

    /**
     * Handle a refresh message.  Publish the metrics and start a timer for the next publication.
     * The message parameter is unused.
     */
    private static void handleMessage(Message msg) {
        synchronized (sLock) {
            publish();
            startTimer();
        }
    }

    /**
     * Start a timer to the next publication point.  Publication points are referenced from
     * process start.
     */
    @GuardedBy("sLock")
    private static void startTimer() {
        // The delay is in minutes.
        long delay;
        if (sCurrentPoint < sPublicationPoints.length) {
            delay = sPublicationPoints[sCurrentPoint];
        } else {
            // Repeat with the final publication point.
            final long repeated = sPublicationPoints[sPublicationPoints.length - 1];
            final int prelude = sPublicationPoints.length - 1;
            delay = (sCurrentPoint - prelude) * repeated;
        }
        // Convert minutes to milliseconds.
        delay *= 60 * 1000;
        // If debug is enabled, convert hours down to minutes.
        if (ENABLE_DEBUG) {
            delay /= 60;
        }
        mHandler.sendEmptyMessageAtTime(0, sProcessStart + delay);
    }

    /**
     * Update the local copy of the timers.  The current time is saved as well.
     */
    @GuardedBy("sLock")
    private static void update(boolean reset) {
        nativeGetTimers(sTimers, reset);
        sLastUpdated = SystemClock.uptimeMillis();
    }

    /**
     * Retrieve the accumulated timer information, reset the native counters, and advance the
     * publication point.
     */
    @GuardedBy("sLock")
    private static void publish() {
        update(true);
        // Log the number of records read.  This happens a few times a day.
        for (int i = 0; i < sTimers.length; i++) {
            var timer = sTimers[i];
            if (timer.count > 0) {
                Log.i(TAG, TextUtils.formatSimple("%s count=%d pvalues=%s",
                                sConfig.timers[i], timer.count, packedString(timer.percentile)));
                if (sApiMap[i] != AppProtoEnums.RESOURCE_API_NONE) {
                    FrameworkStatsLog.write(FrameworkStatsLog.RESOURCE_API_INFO,
                            sApiMap[i],
                            timer.count, timer.total,
                            timer.percentile[0], timer.percentile[1],
                            timer.percentile[2], timer.percentile[3],
                            timer.largest[0], timer.largest[1], timer.largest[2],
                            timer.largest[3], timer.largest[4]);
                }
            }
        }
        sCurrentPoint++;
    }

    /**
     * Given an int[], return a string that is formatted as "a,b,c" with no spaces.
     */
    private static String packedString(int[] a) {
        return Arrays.toString(a).replaceAll("[\\]\\[ ]", "");
    }

    /**
     * Update the metrics information and dump it.
     * @hide
     */
    public static void dumpTimers(@NonNull ParcelFileDescriptor pfd, @Nullable String[] args) {
        FileOutputStream fout = new FileOutputStream(pfd.getFileDescriptor());
        PrintWriter pw = new FastPrintWriter(fout);
        synchronized (sLock) {
            if (!sEnabled || (sConfig == null)) {
                pw.println("  Timers are not enabled in this process");
                pw.flush();
                return;
            }
        }

        // Look for the --refresh switch.  If the switch is present, then sTimers is updated.
        // Otherwise, the current value of sTimers is displayed.
        boolean refresh = Arrays.asList(args).contains("-refresh");

        synchronized (sLock) {
            update(refresh);
            long runtime = sLastUpdated - sProcessStart;
            pw.format("  config runtime=%d proc=%s\n", runtime, Process.myProcessName());
            for (int i = 0; i < sTimers.length; i++) {
                Timer t = sTimers[i];
                if (t.count != 0) {
                    String name = sConfig.timers[i];
                    pw.format("  stats timer=%s cnt=%d avg=%d min=%d max=%d pval=%s "
                        + "largest=%s\n",
                        name, t.count, t.total / t.count, t.mintime, t.maxtime,
                        packedString(t.percentile),
                        packedString(t.largest));
                }
            }
        }
        pw.flush();
    }

    // Enable (or disabled) the runtime timers.  Note that timers are disabled by default.  This
    // retrieves the runtime timer configuration that are taking effect
    private static native int nativeEnableTimers(@NonNull Config config);

    // Retrieve the timers from the native layer.  If reset is true, the timers are reset after
    // being fetched.  The method returns the number of timers that are defined in the runtime
    // layer.  The stats array is filled out to the smaller of its actual size and the number of
    // runtime timers; it never overflows.
    private static native int nativeGetTimers(@NonNull Timer[] stats, boolean reset);
}
