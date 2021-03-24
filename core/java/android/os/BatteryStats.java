/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.os.BatteryStatsManager.NUM_WIFI_STATES;
import static android.os.BatteryStatsManager.NUM_WIFI_SUPPL_STATES;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.job.JobParameters;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.location.GnssSignalQuality;
import android.os.BatteryStatsManager.WifiState;
import android.os.BatteryStatsManager.WifiSupplState;
import android.server.ServerProtoEnums;
import android.service.batterystats.BatteryStatsServiceDumpHistoryProto;
import android.service.batterystats.BatteryStatsServiceDumpProto;
import android.telephony.CellSignalStrength;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.LongSparseArray;
import android.util.MutableBoolean;
import android.util.Pair;
import android.util.Printer;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class providing access to battery usage statistics, including information on
 * wakelocks, processes, packages, and services.  All times are represented in microseconds
 * except where indicated otherwise.
 * @hide
 */
public abstract class BatteryStats implements Parcelable {

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public BatteryStats() {}

    private static final String TAG = "BatteryStats";

    private static final boolean LOCAL_LOGV = false;
    /** Fetching RPM stats is too slow to do each time screen changes, so disable it. */
    protected static final boolean SCREEN_OFF_RPM_STATS_ENABLED = false;

    /** @hide */
    public static final String SERVICE_NAME = Context.BATTERY_STATS_SERVICE;

    /**
     * A constant indicating a partial wake lock timer.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int WAKE_TYPE_PARTIAL = 0;

    /**
     * A constant indicating a full wake lock timer.
     */
    public static final int WAKE_TYPE_FULL = 1;

    /**
     * A constant indicating a window wake lock timer.
     */
    public static final int WAKE_TYPE_WINDOW = 2;

    /**
     * A constant indicating a sensor timer.
     */
    public static final int SENSOR = 3;

    /**
     * A constant indicating a a wifi running timer
     */
    public static final int WIFI_RUNNING = 4;

    /**
     * A constant indicating a full wifi lock timer
     */
    public static final int FULL_WIFI_LOCK = 5;

    /**
     * A constant indicating a wifi scan
     */
    public static final int WIFI_SCAN = 6;

    /**
     * A constant indicating a wifi multicast timer
     */
    public static final int WIFI_MULTICAST_ENABLED = 7;

    /**
     * A constant indicating a video turn on timer
     */
    public static final int VIDEO_TURNED_ON = 8;

    /**
     * A constant indicating a vibrator on timer
     */
    public static final int VIBRATOR_ON = 9;

    /**
     * A constant indicating a foreground activity timer
     */
    public static final int FOREGROUND_ACTIVITY = 10;

    /**
     * A constant indicating a wifi batched scan is active
     */
    public static final int WIFI_BATCHED_SCAN = 11;

    /**
     * A constant indicating a process state timer
     */
    public static final int PROCESS_STATE = 12;

    /**
     * A constant indicating a sync timer
     */
    public static final int SYNC = 13;

    /**
     * A constant indicating a job timer
     */
    public static final int JOB = 14;

    /**
     * A constant indicating an audio turn on timer
     */
    public static final int AUDIO_TURNED_ON = 15;

    /**
     * A constant indicating a flashlight turn on timer
     */
    public static final int FLASHLIGHT_TURNED_ON = 16;

    /**
     * A constant indicating a camera turn on timer
     */
    public static final int CAMERA_TURNED_ON = 17;

    /**
     * A constant indicating a draw wake lock timer.
     */
    public static final int WAKE_TYPE_DRAW = 18;

    /**
     * A constant indicating a bluetooth scan timer.
     */
    public static final int BLUETOOTH_SCAN_ON = 19;

    /**
     * A constant indicating an aggregated partial wake lock timer.
     */
    public static final int AGGREGATED_WAKE_TYPE_PARTIAL = 20;

    /**
     * A constant indicating a bluetooth scan timer for unoptimized scans.
     */
    public static final int BLUETOOTH_UNOPTIMIZED_SCAN_ON = 21;

    /**
     * A constant indicating a foreground service timer
     */
    public static final int FOREGROUND_SERVICE = 22;

    /**
     * A constant indicating an aggregate wifi multicast timer
     */
     public static final int WIFI_AGGREGATE_MULTICAST_ENABLED = 23;

    /**
     * Include all of the data in the stats, including previously saved data.
     */
    public static final int STATS_SINCE_CHARGED = 0;

    /**
     * Include only the current run in the stats.
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, only {@link #STATS_SINCE_CHARGED}
     * is supported.
     */
    @UnsupportedAppUsage
    @Deprecated
    public static final int STATS_CURRENT = 1;

    /**
     * Include only the run since the last time the device was unplugged in the stats.
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, only {@link #STATS_SINCE_CHARGED}
     * is supported.
     */
    @Deprecated
    public static final int STATS_SINCE_UNPLUGGED = 2;

    /** @hide */
    @IntDef(flag = true, prefix = { "STATS_" }, value = {
            STATS_SINCE_CHARGED,
            STATS_CURRENT,
            STATS_SINCE_UNPLUGGED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatName {}

    // NOTE: Update this list if you add/change any stats above.
    // These characters are supposed to represent "total", "last", "current",
    // and "unplugged". They were shortened for efficiency sake.
    private static final String[] STAT_NAMES = { "l", "c", "u" };

    /**
     * Current version of checkin data format.
     *
     * New in version 19:
     *   - Wakelock data (wl) gets current and max times.
     * New in version 20:
     *   - Background timers and counters for: Sensor, BluetoothScan, WifiScan, Jobs, Syncs.
     * New in version 21:
     *   - Actual (not just apportioned) Wakelock time is also recorded.
     *   - Aggregated partial wakelock time (per uid, instead of per wakelock) is recorded.
     *   - BLE scan result count
     *   - CPU frequency time per uid
     * New in version 22:
     *   - BLE scan result background count, BLE unoptimized scan time
     *   - Background partial wakelock time & count
     * New in version 23:
     *   - Logging smeared power model values
     * New in version 24:
     *   - Fixed bugs in background timers and BLE scan time
     * New in version 25:
     *   - Package wakeup alarms are now on screen-off timebase
     * New in version 26:
     *   - Resource power manager (rpm) states [but screenOffRpm is disabled from working properly]
     * New in version 27:
     *   - Always On Display (screen doze mode) time and power
     * New in version 28:
     *   - Light/Deep Doze power
     *   - WiFi Multicast Wakelock statistics (count & duration)
     * New in version 29:
     *   - Process states re-ordered. TOP_SLEEPING now below BACKGROUND. HEAVY_WEIGHT introduced.
     *   - CPU times per UID process state
     * New in version 30:
     *   - Uid.PROCESS_STATE_FOREGROUND_SERVICE only tracks
     *   ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE.
     * New in version 31:
     *   - New cellular network types.
     *   - Deferred job metrics.
     * New in version 32:
     *   - Ambient display properly output in data dump.
     * New in version 33:
     *   - Fixed bug in min learned capacity updating process.
     * New in version 34:
     *   - Deprecated STATS_SINCE_UNPLUGGED and STATS_CURRENT.
     * New in version 35:
     *   - Fixed bug that was not reporting high cellular tx power correctly
     *   - Added out of service and emergency service modes to data connection types
     */
    static final int CHECKIN_VERSION = 35;

    /**
     * Old version, we hit 9 and ran out of room, need to remove.
     */
    private static final int BATTERY_STATS_CHECKIN_VERSION = 9;

    private static final long BYTES_PER_KB = 1024;
    private static final long BYTES_PER_MB = 1048576; // 1024^2
    private static final long BYTES_PER_GB = 1073741824; //1024^3
    public static final double MILLISECONDS_IN_HOUR = 3600 * 1000;

    private static final String VERSION_DATA = "vers";
    private static final String UID_DATA = "uid";
    private static final String WAKEUP_ALARM_DATA = "wua";
    private static final String APK_DATA = "apk";
    private static final String PROCESS_DATA = "pr";
    private static final String CPU_DATA = "cpu";
    private static final String GLOBAL_CPU_FREQ_DATA = "gcf";
    private static final String CPU_TIMES_AT_FREQ_DATA = "ctf";
    // rpm line is:
    // BATTERY_STATS_CHECKIN_VERSION, uid, which, "rpm", state/voter name, total time, total count,
    // screen-off time, screen-off count
    private static final String RESOURCE_POWER_MANAGER_DATA = "rpm";
    private static final String SENSOR_DATA = "sr";
    private static final String VIBRATOR_DATA = "vib";
    private static final String FOREGROUND_ACTIVITY_DATA = "fg";
    // fgs line is:
    // BATTERY_STATS_CHECKIN_VERSION, uid, category, "fgs",
    // foreground service time, count
    private static final String FOREGROUND_SERVICE_DATA = "fgs";
    private static final String STATE_TIME_DATA = "st";
    // wl line is:
    // BATTERY_STATS_CHECKIN_VERSION, uid, which, "wl", name,
    // full        totalTime, 'f',  count, current duration, max duration, total duration,
    // partial     totalTime, 'p',  count, current duration, max duration, total duration,
    // bg partial  totalTime, 'bp', count, current duration, max duration, total duration,
    // window      totalTime, 'w',  count, current duration, max duration, total duration
    // [Currently, full and window wakelocks have durations current = max = total = -1]
    private static final String WAKELOCK_DATA = "wl";
    // awl line is:
    // BATTERY_STATS_CHECKIN_VERSION, uid, which, "awl",
    // cumulative partial wakelock duration, cumulative background partial wakelock duration
    private static final String AGGREGATED_WAKELOCK_DATA = "awl";
    private static final String SYNC_DATA = "sy";
    private static final String JOB_DATA = "jb";
    private static final String JOB_COMPLETION_DATA = "jbc";

    /**
     * jbd line is:
     * BATTERY_STATS_CHECKIN_VERSION, uid, which, "jbd",
     * jobsDeferredEventCount, jobsDeferredCount, totalLatencyMillis,
     * count at latency < 1 hr, count at latency 1 to 2 hrs, 2 to 4 hrs, 4 to 8 hrs, and past 8 hrs
     * <p>
     * @see #JOB_FRESHNESS_BUCKETS
     */
    private static final String JOBS_DEFERRED_DATA = "jbd";
    private static final String KERNEL_WAKELOCK_DATA = "kwl";
    private static final String WAKEUP_REASON_DATA = "wr";
    private static final String NETWORK_DATA = "nt";
    private static final String USER_ACTIVITY_DATA = "ua";
    private static final String BATTERY_DATA = "bt";
    private static final String BATTERY_DISCHARGE_DATA = "dc";
    private static final String BATTERY_LEVEL_DATA = "lv";
    private static final String GLOBAL_WIFI_DATA = "gwfl";
    private static final String WIFI_DATA = "wfl";
    private static final String GLOBAL_WIFI_CONTROLLER_DATA = "gwfcd";
    private static final String WIFI_CONTROLLER_DATA = "wfcd";
    private static final String GLOBAL_BLUETOOTH_CONTROLLER_DATA = "gble";
    private static final String BLUETOOTH_CONTROLLER_DATA = "ble";
    private static final String BLUETOOTH_MISC_DATA = "blem";
    private static final String MISC_DATA = "m";
    private static final String GLOBAL_NETWORK_DATA = "gn";
    private static final String GLOBAL_MODEM_CONTROLLER_DATA = "gmcd";
    private static final String MODEM_CONTROLLER_DATA = "mcd";
    private static final String HISTORY_STRING_POOL = "hsp";
    private static final String HISTORY_DATA = "h";
    private static final String SCREEN_BRIGHTNESS_DATA = "br";
    private static final String SIGNAL_STRENGTH_TIME_DATA = "sgt";
    private static final String SIGNAL_SCANNING_TIME_DATA = "sst";
    private static final String SIGNAL_STRENGTH_COUNT_DATA = "sgc";
    private static final String DATA_CONNECTION_TIME_DATA = "dct";
    private static final String DATA_CONNECTION_COUNT_DATA = "dcc";
    private static final String WIFI_STATE_TIME_DATA = "wst";
    private static final String WIFI_STATE_COUNT_DATA = "wsc";
    private static final String WIFI_SUPPL_STATE_TIME_DATA = "wsst";
    private static final String WIFI_SUPPL_STATE_COUNT_DATA = "wssc";
    private static final String WIFI_SIGNAL_STRENGTH_TIME_DATA = "wsgt";
    private static final String WIFI_SIGNAL_STRENGTH_COUNT_DATA = "wsgc";
    private static final String POWER_USE_SUMMARY_DATA = "pws";
    private static final String POWER_USE_ITEM_DATA = "pwi";
    private static final String DISCHARGE_STEP_DATA = "dsd";
    private static final String CHARGE_STEP_DATA = "csd";
    private static final String DISCHARGE_TIME_REMAIN_DATA = "dtr";
    private static final String CHARGE_TIME_REMAIN_DATA = "ctr";
    private static final String FLASHLIGHT_DATA = "fla";
    private static final String CAMERA_DATA = "cam";
    private static final String VIDEO_DATA = "vid";
    private static final String AUDIO_DATA = "aud";
    private static final String WIFI_MULTICAST_TOTAL_DATA = "wmct";
    private static final String WIFI_MULTICAST_DATA = "wmc";

    public static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";

    private final StringBuilder mFormatBuilder = new StringBuilder(32);
    private final Formatter mFormatter = new Formatter(mFormatBuilder);

    private static final String CELLULAR_CONTROLLER_NAME = "Cellular";
    private static final String WIFI_CONTROLLER_NAME = "WiFi";

    /**
     * Indicates times spent by the uid at each cpu frequency in all process states.
     *
     * Other types might include times spent in foreground, background etc.
     */
    @VisibleForTesting
    public static final String UID_TIMES_TYPE_ALL = "A";

    /**
     * These are the thresholds for bucketing last time since a job was run for an app
     * that just moved to ACTIVE due to a launch. So if the last time a job ran was less
     * than 1 hour ago, then it's reasonably fresh, 2 hours ago, not so fresh and so
     * on.
     */
    public static final long[] JOB_FRESHNESS_BUCKETS = {
            1 * 60 * 60 * 1000L,
            2 * 60 * 60 * 1000L,
            4 * 60 * 60 * 1000L,
            8 * 60 * 60 * 1000L,
            Long.MAX_VALUE
    };

    /**
     * State for keeping track of counting information.
     */
    public static abstract class Counter {

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public Counter() {}

        /**
         * Returns the count associated with this Counter for the
         * selected type of statistics.
         *
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         */
        @UnsupportedAppUsage
        public abstract int getCountLocked(int which);

        /**
         * Temporary for debugging.
         */
        public abstract void logState(Printer pw, String prefix);
    }

    /**
     * State for keeping track of long counting information.
     */
    public static abstract class LongCounter {

        /**
         * Returns the count associated with this Counter for the
         * selected type of statistics.
         *
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         */
        public abstract long getCountLocked(int which);

        /**
         * Temporary for debugging.
         */
        public abstract void logState(Printer pw, String prefix);
    }

    /**
     * State for keeping track of array of long counting information.
     */
    public static abstract class LongCounterArray {
        /**
         * Returns the counts associated with this Counter for the
         * selected type of statistics.
         *
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         */
        public abstract long[] getCountsLocked(int which);

        /**
         * Temporary for debugging.
         */
        public abstract void logState(Printer pw, String prefix);
    }

    /**
     * Container class that aggregates counters for transmit, receive, and idle state of a
     * radio controller.
     */
    public static abstract class ControllerActivityCounter {
        /**
         * @return a non-null {@link LongCounter} representing time spent (milliseconds) in the
         * idle state.
         */
        public abstract LongCounter getIdleTimeCounter();

        /**
         * @return a non-null {@link LongCounter} representing time spent (milliseconds) in the
         * scan state.
         */
        public abstract LongCounter getScanTimeCounter();

        /**
         * @return a non-null {@link LongCounter} representing time spent (milliseconds) in the
         * sleep state.
         */
        public abstract LongCounter getSleepTimeCounter();

        /**
         * @return a non-null {@link LongCounter} representing time spent (milliseconds) in the
         * receive state.
         */
        public abstract LongCounter getRxTimeCounter();

        /**
         * An array of {@link LongCounter}, representing various transmit levels, where each level
         * may draw a different amount of power. The levels themselves are controller-specific.
         * @return non-null array of {@link LongCounter}s representing time spent (milliseconds) in
         * various transmit level states.
         */
        public abstract LongCounter[] getTxTimeCounters();

        /**
         * @return a non-null {@link LongCounter} representing the power consumed by the controller
         * in all states, measured in milli-ampere-milliseconds (mAms). The counter may always
         * yield a value of 0 if the device doesn't support power calculations.
         */
        public abstract LongCounter getPowerCounter();

        /**
         * @return a non-null {@link LongCounter} representing total power monitored on the rails
         * in mAms (miliamps-milliseconds). The counter may always yield a value of 0 if the device
         * doesn't support power rail monitoring.
         */
        public abstract LongCounter getMonitoredRailChargeConsumedMaMs();
    }

    /**
     * State for keeping track of timing information.
     */
    public static abstract class Timer {

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public Timer() {}

        /**
         * Returns the count associated with this Timer for the
         * selected type of statistics.
         *
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         */
        @UnsupportedAppUsage
        public abstract int getCountLocked(int which);

        /**
         * Returns the total time in microseconds associated with this Timer for the
         * selected type of statistics.
         *
         * @param elapsedRealtimeUs current elapsed realtime of system in microseconds
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         * @return a time in microseconds
         */
        @UnsupportedAppUsage
        public abstract long getTotalTimeLocked(long elapsedRealtimeUs, int which);

        /**
         * Returns the total time in microseconds associated with this Timer since the
         * 'mark' was last set.
         *
         * @param elapsedRealtimeUs current elapsed realtime of system in microseconds
         * @return a time in microseconds
         */
        public abstract long getTimeSinceMarkLocked(long elapsedRealtimeUs);

        /**
         * Returns the max duration if it is being tracked.
         * Not all Timer subclasses track the max, total, and current durations.
         */
        public long getMaxDurationMsLocked(long elapsedRealtimeMs) {
            return -1;
        }

        /**
         * Returns the current time the timer has been active, if it is being tracked.
         * Not all Timer subclasses track the max, total, and current durations.
         */
        public long getCurrentDurationMsLocked(long elapsedRealtimeMs) {
            return -1;
        }

        /**
         * Returns the total time the timer has been active, if it is being tracked.
         *
         * Returns the total cumulative duration (i.e. sum of past durations) that this timer has
         * been on since reset.
         * This may differ from getTotalTimeLocked(elapsedRealtimeUs, STATS_SINCE_CHARGED)/1000 since,
         * depending on the Timer, getTotalTimeLocked may represent the total 'blamed' or 'pooled'
         * time, rather than the actual time. By contrast, getTotalDurationMsLocked always gives
         * the actual total time.
         * Not all Timer subclasses track the max, total, and current durations.
         */
        public long getTotalDurationMsLocked(long elapsedRealtimeMs) {
            return -1;
        }

        /**
         * Returns the secondary Timer held by the Timer, if one exists. This secondary timer may be
         * used, for example, for tracking background usage. Secondary timers are never pooled.
         *
         * Not all Timer subclasses have a secondary timer; those that don't return null.
         */
        public Timer getSubTimer() {
            return null;
        }

        /**
         * Returns whether the timer is currently running.  Some types of timers
         * (e.g. BatchTimers) don't know whether the event is currently active,
         * and report false.
         */
        public boolean isRunningLocked() {
            return false;
        }

        /**
         * Temporary for debugging.
         */
        public abstract void logState(Printer pw, String prefix);
    }

    /**
     * Maps the ActivityManager procstate into corresponding BatteryStats procstate.
     */
    public static int mapToInternalProcessState(int procState) {
        if (procState == ActivityManager.PROCESS_STATE_NONEXISTENT) {
            return ActivityManager.PROCESS_STATE_NONEXISTENT;
        } else if (procState == ActivityManager.PROCESS_STATE_TOP) {
            return Uid.PROCESS_STATE_TOP;
        } else if (ActivityManager.isForegroundService(procState)) {
            // State when app has put itself in the foreground.
            return Uid.PROCESS_STATE_FOREGROUND_SERVICE;
        } else if (procState <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
            // Persistent and other foreground states go here.
            return Uid.PROCESS_STATE_FOREGROUND;
        } else if (procState <= ActivityManager.PROCESS_STATE_RECEIVER) {
            return Uid.PROCESS_STATE_BACKGROUND;
        } else if (procState <= ActivityManager.PROCESS_STATE_TOP_SLEEPING) {
            return Uid.PROCESS_STATE_TOP_SLEEPING;
        } else if (procState <= ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
            return Uid.PROCESS_STATE_HEAVY_WEIGHT;
        } else {
            return Uid.PROCESS_STATE_CACHED;
        }
    }

    /**
     * The statistics associated with a particular uid.
     */
    public static abstract class Uid {

        @UnsupportedAppUsage
        public Uid() {
        }

        /**
         * Returns a mapping containing wakelock statistics.
         *
         * @return a Map from Strings to Uid.Wakelock objects.
         */
        @UnsupportedAppUsage
        public abstract ArrayMap<String, ? extends Wakelock> getWakelockStats();

        /**
         * Returns the WiFi Multicast Wakelock statistics.
         *
         * @return a Timer Object for the per uid Multicast statistics.
         */
        public abstract Timer getMulticastWakelockStats();

        /**
         * Returns a mapping containing sync statistics.
         *
         * @return a Map from Strings to Timer objects.
         */
        public abstract ArrayMap<String, ? extends Timer> getSyncStats();

        /**
         * Returns a mapping containing scheduled job statistics.
         *
         * @return a Map from Strings to Timer objects.
         */
        public abstract ArrayMap<String, ? extends Timer> getJobStats();

        /**
         * Returns statistics about how jobs have completed.
         *
         * @return A Map of String job names to completion type -> count mapping.
         */
        public abstract ArrayMap<String, SparseIntArray> getJobCompletionStats();

        /**
         * The statistics associated with a particular wake lock.
         */
        public static abstract class Wakelock {
            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
            public Wakelock() {}

            @UnsupportedAppUsage
            public abstract Timer getWakeTime(int type);
        }

        /**
         * The cumulative time the uid spent holding any partial wakelocks. This will generally
         * differ from summing over the Wakelocks in getWakelockStats since the latter may have
         * wakelocks that overlap in time (and therefore over-counts).
         */
        public abstract Timer getAggregatedPartialWakelockTimer();

        /**
         * Returns a mapping containing sensor statistics.
         *
         * @return a Map from Integer sensor ids to Uid.Sensor objects.
         */
        @UnsupportedAppUsage
        public abstract SparseArray<? extends Sensor> getSensorStats();

        /**
         * Returns a mapping containing active process data.
         */
        public abstract SparseArray<? extends Pid> getPidStats();

        /**
         * Returns a mapping containing process statistics.
         *
         * @return a Map from Strings to Uid.Proc objects.
         */
        @UnsupportedAppUsage
        public abstract ArrayMap<String, ? extends Proc> getProcessStats();

        /**
         * Returns a mapping containing package statistics.
         *
         * @return a Map from Strings to Uid.Pkg objects.
         */
        @UnsupportedAppUsage
        public abstract ArrayMap<String, ? extends Pkg> getPackageStats();

        /**
         * Returns the proportion of power consumed by the System Service
         * calls made by this UID.
         */
        public abstract double getProportionalSystemServiceUsage();

        public abstract ControllerActivityCounter getWifiControllerActivity();
        public abstract ControllerActivityCounter getBluetoothControllerActivity();
        public abstract ControllerActivityCounter getModemControllerActivity();

        /**
         * {@hide}
         */
        @UnsupportedAppUsage
        public abstract int getUid();

        public abstract void noteWifiRunningLocked(long elapsedRealtime);
        public abstract void noteWifiStoppedLocked(long elapsedRealtime);
        public abstract void noteFullWifiLockAcquiredLocked(long elapsedRealtime);
        public abstract void noteFullWifiLockReleasedLocked(long elapsedRealtime);
        public abstract void noteWifiScanStartedLocked(long elapsedRealtime);
        public abstract void noteWifiScanStoppedLocked(long elapsedRealtime);
        public abstract void noteWifiBatchedScanStartedLocked(int csph, long elapsedRealtime);
        public abstract void noteWifiBatchedScanStoppedLocked(long elapsedRealtime);
        public abstract void noteWifiMulticastEnabledLocked(long elapsedRealtime);
        public abstract void noteWifiMulticastDisabledLocked(long elapsedRealtime);
        public abstract void noteActivityResumedLocked(long elapsedRealtime);
        public abstract void noteActivityPausedLocked(long elapsedRealtime);
        @UnsupportedAppUsage
        public abstract long getWifiRunningTime(long elapsedRealtimeUs, int which);
        @UnsupportedAppUsage
        public abstract long getFullWifiLockTime(long elapsedRealtimeUs, int which);
        @UnsupportedAppUsage
        public abstract long getWifiScanTime(long elapsedRealtimeUs, int which);
        public abstract int getWifiScanCount(int which);
        /**
         * Returns the timer keeping track of wifi scans.
         */
        public abstract Timer getWifiScanTimer();
        public abstract int getWifiScanBackgroundCount(int which);
        public abstract long getWifiScanActualTime(long elapsedRealtimeUs);
        public abstract long getWifiScanBackgroundTime(long elapsedRealtimeUs);
        /**
         * Returns the timer keeping track of background wifi scans.
         */
        public abstract Timer getWifiScanBackgroundTimer();
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public abstract long getWifiBatchedScanTime(int csphBin, long elapsedRealtimeUs, int which);
        public abstract int getWifiBatchedScanCount(int csphBin, int which);
        @UnsupportedAppUsage
        public abstract long getWifiMulticastTime(long elapsedRealtimeUs, int which);
        @UnsupportedAppUsage
        public abstract Timer getAudioTurnedOnTimer();
        @UnsupportedAppUsage
        public abstract Timer getVideoTurnedOnTimer();
        public abstract Timer getFlashlightTurnedOnTimer();
        public abstract Timer getCameraTurnedOnTimer();
        public abstract Timer getForegroundActivityTimer();

        /**
         * Returns the timer keeping track of Foreground Service time
         */
        public abstract Timer getForegroundServiceTimer();
        public abstract Timer getBluetoothScanTimer();
        public abstract Timer getBluetoothScanBackgroundTimer();
        public abstract Timer getBluetoothUnoptimizedScanTimer();
        public abstract Timer getBluetoothUnoptimizedScanBackgroundTimer();
        public abstract Counter getBluetoothScanResultCounter();
        public abstract Counter getBluetoothScanResultBgCounter();

        public abstract long[] getCpuFreqTimes(int which);
        public abstract long[] getScreenOffCpuFreqTimes(int which);
        /**
         * Returns cpu active time of an uid.
         */
        public abstract long getCpuActiveTime();
        /**
         * Returns cpu times of an uid on each cluster
         */
        public abstract long[] getCpuClusterTimes();

        /**
         * Returns cpu times of an uid at a particular process state.
         */
        public abstract long[] getCpuFreqTimes(int which, int procState);
        /**
         * Returns cpu times of an uid while the screen if off at a particular process state.
         */
        public abstract long[] getScreenOffCpuFreqTimes(int which, int procState);

        // Note: the following times are disjoint.  They can be added together to find the
        // total time a uid has had any processes running at all.

        /**
         * Time this uid has any processes in the top state.
         */
        public static final int PROCESS_STATE_TOP = 0;
        /**
         * Time this uid has any process with a started foreground service, but
         * none in the "top" state.
         */
        public static final int PROCESS_STATE_FOREGROUND_SERVICE = 1;
        /**
         * Time this uid has any process in an active foreground state, but none in the
         * "foreground service" or better state. Persistent and other foreground states go here.
         */
        public static final int PROCESS_STATE_FOREGROUND = 2;
        /**
         * Time this uid has any process in an active background state, but none in the
         * "foreground" or better state.
         */
        public static final int PROCESS_STATE_BACKGROUND = 3;
        /**
         * Time this uid has any process that is top while the device is sleeping, but not
         * active for any other reason.  We kind-of consider it a kind of cached process
         * for execution restrictions.
         */
        public static final int PROCESS_STATE_TOP_SLEEPING = 4;
        /**
         * Time this uid has any process that is in the background but it has an activity
         * marked as "can't save state".  This is essentially a cached process, though the
         * system will try much harder than normal to avoid killing it.
         */
        public static final int PROCESS_STATE_HEAVY_WEIGHT = 5;
        /**
         * Time this uid has any processes that are sitting around cached, not in one of the
         * other active states.
         */
        public static final int PROCESS_STATE_CACHED = 6;
        /**
         * Total number of process states we track.
         */
        public static final int NUM_PROCESS_STATE = 7;

        // Used in dump
        static final String[] PROCESS_STATE_NAMES = {
                "Top", "Fg Service", "Foreground", "Background", "Top Sleeping", "Heavy Weight",
                "Cached"
        };

        // Used in checkin dump
        @VisibleForTesting
        public static final String[] UID_PROCESS_TYPES = {
                "T",  // TOP
                "FS", // FOREGROUND_SERVICE
                "F",  // FOREGROUND
                "B",  // BACKGROUND
                "TS", // TOP_SLEEPING
                "HW",  // HEAVY_WEIGHT
                "C"   // CACHED
        };

        /**
         * When the process exits one of these states, we need to make sure cpu time in this state
         * is not attributed to any non-critical process states.
         */
        public static final int[] CRITICAL_PROC_STATES = {
                PROCESS_STATE_TOP,
                PROCESS_STATE_BOUND_TOP, PROCESS_STATE_FOREGROUND_SERVICE,
                PROCESS_STATE_FOREGROUND
        };

        public abstract long getProcessStateTime(int state, long elapsedRealtimeUs, int which);
        public abstract Timer getProcessStateTimer(int state);

        public abstract Timer getVibratorOnTimer();

        public static final int NUM_WIFI_BATCHED_SCAN_BINS = 5;

        /**
         * Note that these must match the constants in android.os.PowerManager.
         * Also, if the user activity types change, the BatteryStatsImpl.VERSION must
         * also be bumped.
         */
        static final String[] USER_ACTIVITY_TYPES = {
            "other", "button", "touch", "accessibility", "attention"
        };

        public static final int NUM_USER_ACTIVITY_TYPES = USER_ACTIVITY_TYPES.length;

        public abstract void noteUserActivityLocked(int type);
        public abstract boolean hasUserActivity();
        public abstract int getUserActivityCount(int type, int which);

        public abstract boolean hasNetworkActivity();
        @UnsupportedAppUsage
        public abstract long getNetworkActivityBytes(int type, int which);
        public abstract long getNetworkActivityPackets(int type, int which);
        @UnsupportedAppUsage
        public abstract long getMobileRadioActiveTime(int which);
        public abstract int getMobileRadioActiveCount(int which);

        /**
         * Get the total cpu time (in microseconds) this UID had processes executing in userspace.
         */
        public abstract long getUserCpuTimeUs(int which);

        /**
         * Get the total cpu time (in microseconds) this UID had processes executing kernel syscalls.
         */
        public abstract long getSystemCpuTimeUs(int which);

        /**
         * Returns the approximate cpu time (in microseconds) spent at a certain CPU speed for a
         * given CPU cluster.
         * @param cluster the index of the CPU cluster.
         * @param step the index of the CPU speed. This is not the actual speed of the CPU.
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
         * @see com.android.internal.os.PowerProfile#getNumCpuClusters()
         * @see com.android.internal.os.PowerProfile#getNumSpeedStepsInCpuCluster(int)
         */
        public abstract long getTimeAtCpuSpeed(int cluster, int step, int which);

        /**
         * Returns the number of times this UID woke up the Application Processor to
         * process a mobile radio packet.
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
         */
        public abstract long getMobileRadioApWakeupCount(int which);

        /**
         * Returns the number of times this UID woke up the Application Processor to
         * process a WiFi packet.
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
         */
        public abstract long getWifiRadioApWakeupCount(int which);

        /**
         * Appends the deferred jobs data to the StringBuilder passed in, in checkin format
         * @param sb StringBuilder that can be overwritten with the deferred jobs data
         * @param which one of STATS_*
         */
        public abstract void getDeferredJobsCheckinLineLocked(StringBuilder sb, int which);

        /**
         * Appends the deferred jobs data to the StringBuilder passed in
         * @param sb StringBuilder that can be overwritten with the deferred jobs data
         * @param which one of STATS_*
         */
        public abstract void getDeferredJobsLineLocked(StringBuilder sb, int which);

        /**
         * Returns the battery consumption (in microcoulombs) of bluetooth for this uid,
         * derived from on device power measurement data.
         * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
         *
         * {@hide}
         */
        public abstract long getBluetoothMeasuredBatteryConsumptionUC();

        /**
         * Returns the battery consumption (in microcoulombs) of the uid's cpu usage, derived from
         * on device power measurement data.
         * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
         *
         * {@hide}
         */
        public abstract long getCpuMeasuredBatteryConsumptionUC();

        /**
         * Returns the battery consumption (in microcoulombs) of the screen while on and uid active,
         * derived from on device power measurement data.
         * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
         *
         * {@hide}
         */
        public abstract long getScreenOnMeasuredBatteryConsumptionUC();

        /**
         * Returns the battery consumption (in microcoulombs) of wifi for this uid,
         * derived from on device power measurement data.
         * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
         *
         * {@hide}
         */
        public abstract long getWifiMeasuredBatteryConsumptionUC();

        /**
         * Returns the battery consumption (in microcoulombs) used by this uid for each
         * {@link android.hardware.power.stats.EnergyConsumer.ordinal} of (custom) energy consumer
         * type {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
         *
         * @return charge (in microcoulombs) consumed since last reset for each (custom) energy
         *         consumer of type OTHER, indexed by their ordinal. Returns null if no energy
         *         reporting is supported.
         *
         * {@hide}
         */
        public abstract @Nullable long[] getCustomConsumerMeasuredBatteryConsumptionUC();

        public static abstract class Sensor {

            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
            public Sensor() {}

            /*
             * FIXME: it's not correct to use this magic value because it
             * could clash with a sensor handle (which are defined by
             * the sensor HAL, and therefore out of our control
             */
            // Magic sensor number for the GPS.
            @UnsupportedAppUsage
            public static final int GPS = -10000;

            @UnsupportedAppUsage
            public abstract int getHandle();

            @UnsupportedAppUsage
            public abstract Timer getSensorTime();

            /** Returns a Timer for sensor usage when app is in the background. */
            public abstract Timer getSensorBackgroundTime();
        }

        public class Pid {
            public int mWakeNesting;
            public long mWakeSumMs;
            public long mWakeStartMs;
        }

        /**
         * The statistics associated with a particular process.
         */
        public static abstract class Proc {

            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
            public Proc() {}

            public static class ExcessivePower {

                @UnsupportedAppUsage
                public ExcessivePower() {
                }

                public static final int TYPE_WAKE = 1;
                public static final int TYPE_CPU = 2;

                @UnsupportedAppUsage
                public int type;
                @UnsupportedAppUsage
                public long overTime;
                @UnsupportedAppUsage
                public long usedTime;
            }

            /**
             * Returns true if this process is still active in the battery stats.
             */
            public abstract boolean isActive();

            /**
             * Returns the total time (in milliseconds) spent executing in user code.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            @UnsupportedAppUsage
            public abstract long getUserTime(int which);

            /**
             * Returns the total time (in milliseconds) spent executing in system code.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            @UnsupportedAppUsage
            public abstract long getSystemTime(int which);

            /**
             * Returns the number of times the process has been started.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            @UnsupportedAppUsage
            public abstract int getStarts(int which);

            /**
             * Returns the number of times the process has crashed.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            public abstract int getNumCrashes(int which);

            /**
             * Returns the number of times the process has ANRed.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            public abstract int getNumAnrs(int which);

            /**
             * Returns the cpu time (milliseconds) spent while the process was in the foreground.
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             * @return foreground cpu time in microseconds
             */
            @UnsupportedAppUsage
            public abstract long getForegroundTime(int which);

            @UnsupportedAppUsage
            public abstract int countExcessivePowers();

            @UnsupportedAppUsage
            public abstract ExcessivePower getExcessivePower(int i);
        }

        /**
         * The statistics associated with a particular package.
         */
        public static abstract class Pkg {

            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
            public Pkg() {}

            /**
             * Returns information about all wakeup alarms that have been triggered for this
             * package.  The mapping keys are tag names for the alarms, the counter contains
             * the number of times the alarm was triggered while on battery.
             */
            @UnsupportedAppUsage
            public abstract ArrayMap<String, ? extends Counter> getWakeupAlarmStats();

            /**
             * Returns a mapping containing service statistics.
             */
            @UnsupportedAppUsage
            public abstract ArrayMap<String, ? extends Serv> getServiceStats();

            /**
             * The statistics associated with a particular service.
             */
            public static abstract class Serv {

                /**
                 * Returns the amount of time spent started.
                 *
                 * @param batteryUptime elapsed uptime on battery in microseconds.
                 * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
                 * @return
                 */
                @UnsupportedAppUsage
                public abstract long getStartTime(long batteryUptime, int which);

                /**
                 * Returns the total number of times startService() has been called.
                 *
                 * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
                 */
                @UnsupportedAppUsage
                public abstract int getStarts(int which);

                /**
                 * Returns the total number times the service has been launched.
                 *
                 * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
                 */
                @UnsupportedAppUsage
                public abstract int getLaunches(int which);
            }
        }
    }

    public static final class LevelStepTracker {
        public long mLastStepTime = -1;
        public int mNumStepDurations;
        public final long[] mStepDurations;

        public LevelStepTracker(int maxLevelSteps) {
            mStepDurations = new long[maxLevelSteps];
        }

        public LevelStepTracker(int numSteps, long[] steps) {
            mNumStepDurations = numSteps;
            mStepDurations = new long[numSteps];
            System.arraycopy(steps, 0, mStepDurations, 0, numSteps);
        }

        public long getDurationAt(int index) {
            return mStepDurations[index] & STEP_LEVEL_TIME_MASK;
        }

        public int getLevelAt(int index) {
            return (int)((mStepDurations[index] & STEP_LEVEL_LEVEL_MASK)
                    >> STEP_LEVEL_LEVEL_SHIFT);
        }

        public int getInitModeAt(int index) {
            return (int)((mStepDurations[index] & STEP_LEVEL_INITIAL_MODE_MASK)
                    >> STEP_LEVEL_INITIAL_MODE_SHIFT);
        }

        public int getModModeAt(int index) {
            return (int)((mStepDurations[index] & STEP_LEVEL_MODIFIED_MODE_MASK)
                    >> STEP_LEVEL_MODIFIED_MODE_SHIFT);
        }

        private void appendHex(long val, int topOffset, StringBuilder out) {
            boolean hasData = false;
            while (topOffset >= 0) {
                int digit = (int)( (val>>topOffset) & 0xf );
                topOffset -= 4;
                if (!hasData && digit == 0) {
                    continue;
                }
                hasData = true;
                if (digit >= 0 && digit <= 9) {
                    out.append((char)('0' + digit));
                } else {
                    out.append((char)('a' + digit - 10));
                }
            }
        }

        public void encodeEntryAt(int index, StringBuilder out) {
            long item = mStepDurations[index];
            long duration = item & STEP_LEVEL_TIME_MASK;
            int level = (int)((item & STEP_LEVEL_LEVEL_MASK)
                    >> STEP_LEVEL_LEVEL_SHIFT);
            int initMode = (int)((item & STEP_LEVEL_INITIAL_MODE_MASK)
                    >> STEP_LEVEL_INITIAL_MODE_SHIFT);
            int modMode = (int)((item & STEP_LEVEL_MODIFIED_MODE_MASK)
                    >> STEP_LEVEL_MODIFIED_MODE_SHIFT);
            switch ((initMode&STEP_LEVEL_MODE_SCREEN_STATE) + 1) {
                case Display.STATE_OFF: out.append('f'); break;
                case Display.STATE_ON: out.append('o'); break;
                case Display.STATE_DOZE: out.append('d'); break;
                case Display.STATE_DOZE_SUSPEND: out.append('z'); break;
            }
            if ((initMode&STEP_LEVEL_MODE_POWER_SAVE) != 0) {
                out.append('p');
            }
            if ((initMode&STEP_LEVEL_MODE_DEVICE_IDLE) != 0) {
                out.append('i');
            }
            switch ((modMode&STEP_LEVEL_MODE_SCREEN_STATE) + 1) {
                case Display.STATE_OFF: out.append('F'); break;
                case Display.STATE_ON: out.append('O'); break;
                case Display.STATE_DOZE: out.append('D'); break;
                case Display.STATE_DOZE_SUSPEND: out.append('Z'); break;
            }
            if ((modMode&STEP_LEVEL_MODE_POWER_SAVE) != 0) {
                out.append('P');
            }
            if ((modMode&STEP_LEVEL_MODE_DEVICE_IDLE) != 0) {
                out.append('I');
            }
            out.append('-');
            appendHex(level, 4, out);
            out.append('-');
            appendHex(duration, STEP_LEVEL_LEVEL_SHIFT-4, out);
        }

        public void decodeEntryAt(int index, String value) {
            final int N = value.length();
            int i = 0;
            char c;
            long out = 0;
            while (i < N && (c=value.charAt(i)) != '-') {
                i++;
                switch (c) {
                    case 'f': out |= (((long)Display.STATE_OFF-1)<<STEP_LEVEL_INITIAL_MODE_SHIFT);
                        break;
                    case 'o': out |= (((long)Display.STATE_ON-1)<<STEP_LEVEL_INITIAL_MODE_SHIFT);
                        break;
                    case 'd': out |= (((long)Display.STATE_DOZE-1)<<STEP_LEVEL_INITIAL_MODE_SHIFT);
                        break;
                    case 'z': out |= (((long)Display.STATE_DOZE_SUSPEND-1)
                            << STEP_LEVEL_INITIAL_MODE_SHIFT);
                        break;
                    case 'p': out |= (((long)STEP_LEVEL_MODE_POWER_SAVE)
                            << STEP_LEVEL_INITIAL_MODE_SHIFT);
                        break;
                    case 'i': out |= (((long)STEP_LEVEL_MODE_DEVICE_IDLE)
                            << STEP_LEVEL_INITIAL_MODE_SHIFT);
                        break;
                    case 'F': out |= (((long)Display.STATE_OFF-1)<<STEP_LEVEL_MODIFIED_MODE_SHIFT);
                        break;
                    case 'O': out |= (((long)Display.STATE_ON-1)<<STEP_LEVEL_MODIFIED_MODE_SHIFT);
                        break;
                    case 'D': out |= (((long)Display.STATE_DOZE-1)<<STEP_LEVEL_MODIFIED_MODE_SHIFT);
                        break;
                    case 'Z': out |= (((long)Display.STATE_DOZE_SUSPEND-1)
                            << STEP_LEVEL_MODIFIED_MODE_SHIFT);
                        break;
                    case 'P': out |= (((long)STEP_LEVEL_MODE_POWER_SAVE)
                            << STEP_LEVEL_MODIFIED_MODE_SHIFT);
                        break;
                    case 'I': out |= (((long)STEP_LEVEL_MODE_DEVICE_IDLE)
                            << STEP_LEVEL_MODIFIED_MODE_SHIFT);
                        break;
                }
            }
            i++;
            long level = 0;
            while (i < N && (c=value.charAt(i)) != '-') {
                i++;
                level <<= 4;
                if (c >= '0' && c <= '9') {
                    level += c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    level += c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    level += c - 'A' + 10;
                }
            }
            i++;
            out |= (level << STEP_LEVEL_LEVEL_SHIFT) & STEP_LEVEL_LEVEL_MASK;
            long duration = 0;
            while (i < N && (c=value.charAt(i)) != '-') {
                i++;
                duration <<= 4;
                if (c >= '0' && c <= '9') {
                    duration += c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    duration += c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    duration += c - 'A' + 10;
                }
            }
            mStepDurations[index] = out | (duration & STEP_LEVEL_TIME_MASK);
        }

        public void init() {
            mLastStepTime = -1;
            mNumStepDurations = 0;
        }

        public void clearTime() {
            mLastStepTime = -1;
        }

        public long computeTimePerLevel() {
            final long[] steps = mStepDurations;
            final int numSteps = mNumStepDurations;

            // For now we'll do a simple average across all steps.
            if (numSteps <= 0) {
                return -1;
            }
            long total = 0;
            for (int i=0; i<numSteps; i++) {
                total += steps[i] & STEP_LEVEL_TIME_MASK;
            }
            return total / numSteps;
            /*
            long[] buckets = new long[numSteps];
            int numBuckets = 0;
            int numToAverage = 4;
            int i = 0;
            while (i < numSteps) {
                long totalTime = 0;
                int num = 0;
                for (int j=0; j<numToAverage && (i+j)<numSteps; j++) {
                    totalTime += steps[i+j] & STEP_LEVEL_TIME_MASK;
                    num++;
                }
                buckets[numBuckets] = totalTime / num;
                numBuckets++;
                numToAverage *= 2;
                i += num;
            }
            if (numBuckets < 1) {
                return -1;
            }
            long averageTime = buckets[numBuckets-1];
            for (i=numBuckets-2; i>=0; i--) {
                averageTime = (averageTime + buckets[i]) / 2;
            }
            return averageTime;
            */
        }

        public long computeTimeEstimate(long modesOfInterest, long modeValues,
                int[] outNumOfInterest) {
            final long[] steps = mStepDurations;
            final int count = mNumStepDurations;
            if (count <= 0) {
                return -1;
            }
            long total = 0;
            int numOfInterest = 0;
            for (int i=0; i<count; i++) {
                long initMode = (steps[i] & STEP_LEVEL_INITIAL_MODE_MASK)
                        >> STEP_LEVEL_INITIAL_MODE_SHIFT;
                long modMode = (steps[i] & STEP_LEVEL_MODIFIED_MODE_MASK)
                        >> STEP_LEVEL_MODIFIED_MODE_SHIFT;
                // If the modes of interest didn't change during this step period...
                if ((modMode&modesOfInterest) == 0) {
                    // And the mode values during this period match those we are measuring...
                    if ((initMode&modesOfInterest) == modeValues) {
                        // Then this can be used to estimate the total time!
                        numOfInterest++;
                        total += steps[i] & STEP_LEVEL_TIME_MASK;
                    }
                }
            }
            if (numOfInterest <= 0) {
                return -1;
            }

            if (outNumOfInterest != null) {
                outNumOfInterest[0] = numOfInterest;
            }

            // The estimated time is the average time we spend in each level, multipled
            // by 100 -- the total number of battery levels
            return (total / numOfInterest) * 100;
        }

        public void addLevelSteps(int numStepLevels, long modeBits, long elapsedRealtime) {
            int stepCount = mNumStepDurations;
            final long lastStepTime = mLastStepTime;
            if (lastStepTime >= 0 && numStepLevels > 0) {
                final long[] steps = mStepDurations;
                long duration = elapsedRealtime - lastStepTime;
                for (int i=0; i<numStepLevels; i++) {
                    System.arraycopy(steps, 0, steps, 1, steps.length-1);
                    long thisDuration = duration / (numStepLevels-i);
                    duration -= thisDuration;
                    if (thisDuration > STEP_LEVEL_TIME_MASK) {
                        thisDuration = STEP_LEVEL_TIME_MASK;
                    }
                    steps[0] = thisDuration | modeBits;
                }
                stepCount += numStepLevels;
                if (stepCount > steps.length) {
                    stepCount = steps.length;
                }
            }
            mNumStepDurations = stepCount;
            mLastStepTime = elapsedRealtime;
        }

        public void readFromParcel(Parcel in) {
            final int N = in.readInt();
            if (N > mStepDurations.length) {
                throw new ParcelFormatException("more step durations than available: " + N);
            }
            mNumStepDurations = N;
            for (int i=0; i<N; i++) {
                mStepDurations[i] = in.readLong();
            }
        }

        public void writeToParcel(Parcel out) {
            final int N = mNumStepDurations;
            out.writeInt(N);
            for (int i=0; i<N; i++) {
                out.writeLong(mStepDurations[i]);
            }
        }
    }

    public static final class PackageChange {
        public String mPackageName;
        public boolean mUpdate;
        public long mVersionCode;
    }

    public static final class DailyItem {
        public long mStartTime;
        public long mEndTime;
        public LevelStepTracker mDischargeSteps;
        public LevelStepTracker mChargeSteps;
        public ArrayList<PackageChange> mPackageChanges;
    }

    public abstract DailyItem getDailyItemLocked(int daysAgo);

    public abstract long getCurrentDailyStartTime();

    public abstract long getNextMinDailyDeadline();

    public abstract long getNextMaxDailyDeadline();

    public abstract long[] getCpuFreqs();

    public final static class HistoryTag {
        public String string;
        public int uid;

        public int poolIdx;

        public void setTo(HistoryTag o) {
            string = o.string;
            uid = o.uid;
            poolIdx = o.poolIdx;
        }

        public void setTo(String _string, int _uid) {
            string = _string;
            uid = _uid;
            poolIdx = -1;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(string);
            dest.writeInt(uid);
        }

        public void readFromParcel(Parcel src) {
            string = src.readString();
            uid = src.readInt();
            poolIdx = -1;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            HistoryTag that = (HistoryTag) o;

            if (uid != that.uid) return false;
            if (!string.equals(that.string)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = string.hashCode();
            result = 31 * result + uid;
            return result;
        }
    }

    /**
     * Optional detailed information that can go into a history step.  This is typically
     * generated each time the battery level changes.
     */
    public final static class HistoryStepDetails {
        // Time (in 1/100 second) spent in user space and the kernel since the last step.
        public int userTime;
        public int systemTime;

        // Top three apps using CPU in the last step, with times in 1/100 second.
        public int appCpuUid1;
        public int appCpuUTime1;
        public int appCpuSTime1;
        public int appCpuUid2;
        public int appCpuUTime2;
        public int appCpuSTime2;
        public int appCpuUid3;
        public int appCpuUTime3;
        public int appCpuSTime3;

        // Information from /proc/stat
        public int statUserTime;
        public int statSystemTime;
        public int statIOWaitTime;
        public int statIrqTime;
        public int statSoftIrqTime;
        public int statIdlTime;

        // Low power state stats
        public String statSubsystemPowerState;

        public HistoryStepDetails() {
            clear();
        }

        public void clear() {
            userTime = systemTime = 0;
            appCpuUid1 = appCpuUid2 = appCpuUid3 = -1;
            appCpuUTime1 = appCpuSTime1 = appCpuUTime2 = appCpuSTime2
                    = appCpuUTime3 = appCpuSTime3 = 0;
        }

        public void writeToParcel(Parcel out) {
            out.writeInt(userTime);
            out.writeInt(systemTime);
            out.writeInt(appCpuUid1);
            out.writeInt(appCpuUTime1);
            out.writeInt(appCpuSTime1);
            out.writeInt(appCpuUid2);
            out.writeInt(appCpuUTime2);
            out.writeInt(appCpuSTime2);
            out.writeInt(appCpuUid3);
            out.writeInt(appCpuUTime3);
            out.writeInt(appCpuSTime3);
            out.writeInt(statUserTime);
            out.writeInt(statSystemTime);
            out.writeInt(statIOWaitTime);
            out.writeInt(statIrqTime);
            out.writeInt(statSoftIrqTime);
            out.writeInt(statIdlTime);
            out.writeString(statSubsystemPowerState);
        }

        public void readFromParcel(Parcel in) {
            userTime = in.readInt();
            systemTime = in.readInt();
            appCpuUid1 = in.readInt();
            appCpuUTime1 = in.readInt();
            appCpuSTime1 = in.readInt();
            appCpuUid2 = in.readInt();
            appCpuUTime2 = in.readInt();
            appCpuSTime2 = in.readInt();
            appCpuUid3 = in.readInt();
            appCpuUTime3 = in.readInt();
            appCpuSTime3 = in.readInt();
            statUserTime = in.readInt();
            statSystemTime = in.readInt();
            statIOWaitTime = in.readInt();
            statIrqTime = in.readInt();
            statSoftIrqTime = in.readInt();
            statIdlTime = in.readInt();
            statSubsystemPowerState = in.readString();
        }
    }

    /**
     * Battery history record.
     */
    public static final class HistoryItem {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public HistoryItem next;

        // The time of this event in milliseconds, as per SystemClock.elapsedRealtime().
        @UnsupportedAppUsage
        public long time;

        @UnsupportedAppUsage
        public static final byte CMD_UPDATE = 0;        // These can be written as deltas
        public static final byte CMD_NULL = -1;
        public static final byte CMD_START = 4;
        public static final byte CMD_CURRENT_TIME = 5;
        public static final byte CMD_OVERFLOW = 6;
        public static final byte CMD_RESET = 7;
        public static final byte CMD_SHUTDOWN = 8;

        @UnsupportedAppUsage
        public byte cmd = CMD_NULL;

        /**
         * Return whether the command code is a delta data update.
         */
        public boolean isDeltaData() {
            return cmd == CMD_UPDATE;
        }

        @UnsupportedAppUsage
        public byte batteryLevel;
        @UnsupportedAppUsage
        public byte batteryStatus;
        @UnsupportedAppUsage
        public byte batteryHealth;
        @UnsupportedAppUsage
        public byte batteryPlugType;

        public short batteryTemperature;
        // Battery voltage in millivolts (mV).
        @UnsupportedAppUsage
        public char batteryVoltage;

        // The charge of the battery in micro-Ampere-hours.
        public int batteryChargeUah;

        public double modemRailChargeMah;
        public double wifiRailChargeMah;

        // Constants from SCREEN_BRIGHTNESS_*
        public static final int STATE_BRIGHTNESS_SHIFT = 0;
        public static final int STATE_BRIGHTNESS_MASK = 0x7;
        // Constants from SIGNAL_STRENGTH_*
        public static final int STATE_PHONE_SIGNAL_STRENGTH_SHIFT = 3;
        public static final int STATE_PHONE_SIGNAL_STRENGTH_MASK = 0x7 << STATE_PHONE_SIGNAL_STRENGTH_SHIFT;
        // Constants from ServiceState.STATE_*
        public static final int STATE_PHONE_STATE_SHIFT = 6;
        public static final int STATE_PHONE_STATE_MASK = 0x7 << STATE_PHONE_STATE_SHIFT;
        // Constants from DATA_CONNECTION_*
        public static final int STATE_DATA_CONNECTION_SHIFT = 9;
        public static final int STATE_DATA_CONNECTION_MASK = 0x1f << STATE_DATA_CONNECTION_SHIFT;

        // These states always appear directly in the first int token
        // of a delta change; they should be ones that change relatively
        // frequently.
        public static final int STATE_CPU_RUNNING_FLAG = 1<<31;
        public static final int STATE_WAKE_LOCK_FLAG = 1<<30;
        public static final int STATE_GPS_ON_FLAG = 1<<29;
        public static final int STATE_WIFI_FULL_LOCK_FLAG = 1<<28;
        public static final int STATE_WIFI_SCAN_FLAG = 1<<27;
        public static final int STATE_WIFI_RADIO_ACTIVE_FLAG = 1<<26;
        public static final int STATE_MOBILE_RADIO_ACTIVE_FLAG = 1<<25;
        // Do not use, this is used for coulomb delta count.
        private static final int STATE_RESERVED_0 = 1<<24;
        // These are on the lower bits used for the command; if they change
        // we need to write another int of data.
        public static final int STATE_SENSOR_ON_FLAG = 1<<23;
        public static final int STATE_AUDIO_ON_FLAG = 1<<22;
        public static final int STATE_PHONE_SCANNING_FLAG = 1<<21;
        public static final int STATE_SCREEN_ON_FLAG = 1<<20;       // consider moving to states2
        public static final int STATE_BATTERY_PLUGGED_FLAG = 1<<19; // consider moving to states2
        public static final int STATE_SCREEN_DOZE_FLAG = 1 << 18;
        // empty slot
        public static final int STATE_WIFI_MULTICAST_ON_FLAG = 1<<16;

        public static final int MOST_INTERESTING_STATES =
                STATE_BATTERY_PLUGGED_FLAG | STATE_SCREEN_ON_FLAG | STATE_SCREEN_DOZE_FLAG;

        public static final int SETTLE_TO_ZERO_STATES = 0xffff0000 & ~MOST_INTERESTING_STATES;

        @UnsupportedAppUsage
        public int states;

        // Constants from WIFI_SUPPL_STATE_*
        public static final int STATE2_WIFI_SUPPL_STATE_SHIFT = 0;
        public static final int STATE2_WIFI_SUPPL_STATE_MASK = 0xf;
        // Values for NUM_WIFI_SIGNAL_STRENGTH_BINS
        public static final int STATE2_WIFI_SIGNAL_STRENGTH_SHIFT = 4;
        public static final int STATE2_WIFI_SIGNAL_STRENGTH_MASK =
                0x7 << STATE2_WIFI_SIGNAL_STRENGTH_SHIFT;
        // Values for NUM_GPS_SIGNAL_QUALITY_LEVELS
        public static final int STATE2_GPS_SIGNAL_QUALITY_SHIFT = 7;
        public static final int STATE2_GPS_SIGNAL_QUALITY_MASK =
            0x1 << STATE2_GPS_SIGNAL_QUALITY_SHIFT;

        public static final int STATE2_POWER_SAVE_FLAG = 1<<31;
        public static final int STATE2_VIDEO_ON_FLAG = 1<<30;
        public static final int STATE2_WIFI_RUNNING_FLAG = 1<<29;
        public static final int STATE2_WIFI_ON_FLAG = 1<<28;
        public static final int STATE2_FLASHLIGHT_FLAG = 1<<27;
        public static final int STATE2_DEVICE_IDLE_SHIFT = 25;
        public static final int STATE2_DEVICE_IDLE_MASK = 0x3 << STATE2_DEVICE_IDLE_SHIFT;
        public static final int STATE2_CHARGING_FLAG = 1<<24;
        public static final int STATE2_PHONE_IN_CALL_FLAG = 1<<23;
        public static final int STATE2_BLUETOOTH_ON_FLAG = 1<<22;
        public static final int STATE2_CAMERA_FLAG = 1<<21;
        public static final int STATE2_BLUETOOTH_SCAN_FLAG = 1 << 20;
        public static final int STATE2_CELLULAR_HIGH_TX_POWER_FLAG = 1 << 19;
        public static final int STATE2_USB_DATA_LINK_FLAG = 1 << 18;

        public static final int MOST_INTERESTING_STATES2 =
                STATE2_POWER_SAVE_FLAG | STATE2_WIFI_ON_FLAG | STATE2_DEVICE_IDLE_MASK
                | STATE2_CHARGING_FLAG | STATE2_PHONE_IN_CALL_FLAG | STATE2_BLUETOOTH_ON_FLAG;

        public static final int SETTLE_TO_ZERO_STATES2 = 0xffff0000 & ~MOST_INTERESTING_STATES2;

        @UnsupportedAppUsage
        public int states2;

        // The wake lock that was acquired at this point.
        public HistoryTag wakelockTag;

        // Kernel wakeup reason at this point.
        public HistoryTag wakeReasonTag;

        // Non-null when there is more detailed information at this step.
        public HistoryStepDetails stepDetails;

        public static final int EVENT_FLAG_START = 0x8000;
        public static final int EVENT_FLAG_FINISH = 0x4000;

        // No event in this item.
        public static final int EVENT_NONE = 0x0000;
        // Event is about a process that is running.
        public static final int EVENT_PROC = 0x0001;
        // Event is about an application package that is in the foreground.
        public static final int EVENT_FOREGROUND = 0x0002;
        // Event is about an application package that is at the top of the screen.
        public static final int EVENT_TOP = 0x0003;
        // Event is about active sync operations.
        public static final int EVENT_SYNC = 0x0004;
        // Events for all additional wake locks aquired/release within a wake block.
        // These are not generated by default.
        public static final int EVENT_WAKE_LOCK = 0x0005;
        // Event is about an application executing a scheduled job.
        public static final int EVENT_JOB = 0x0006;
        // Events for users running.
        public static final int EVENT_USER_RUNNING = 0x0007;
        // Events for foreground user.
        public static final int EVENT_USER_FOREGROUND = 0x0008;
        // Event for connectivity changed.
        public static final int EVENT_CONNECTIVITY_CHANGED = 0x0009;
        // Event for becoming active taking us out of idle mode.
        public static final int EVENT_ACTIVE = 0x000a;
        // Event for a package being installed.
        public static final int EVENT_PACKAGE_INSTALLED = 0x000b;
        // Event for a package being uninstalled.
        public static final int EVENT_PACKAGE_UNINSTALLED = 0x000c;
        // Event for a package being uninstalled.
        public static final int EVENT_ALARM = 0x000d;
        // Record that we have decided we need to collect new stats data.
        public static final int EVENT_COLLECT_EXTERNAL_STATS = 0x000e;
        // Event for a package becoming inactive due to being unused for a period of time.
        public static final int EVENT_PACKAGE_INACTIVE = 0x000f;
        // Event for a package becoming active due to an interaction.
        public static final int EVENT_PACKAGE_ACTIVE = 0x0010;
        // Event for a package being on the temporary allowlist.
        public static final int EVENT_TEMP_WHITELIST = 0x0011;
        // Event for the screen waking up.
        public static final int EVENT_SCREEN_WAKE_UP = 0x0012;
        // Event for the UID that woke up the application processor.
        // Used for wakeups coming from WiFi, modem, etc.
        public static final int EVENT_WAKEUP_AP = 0x0013;
        // Event for reporting that a specific partial wake lock has been held for a long duration.
        public static final int EVENT_LONG_WAKE_LOCK = 0x0014;

        // Number of event types.
        public static final int EVENT_COUNT = 0x0016;
        // Mask to extract out only the type part of the event.
        public static final int EVENT_TYPE_MASK = ~(EVENT_FLAG_START|EVENT_FLAG_FINISH);

        public static final int EVENT_PROC_START = EVENT_PROC | EVENT_FLAG_START;
        public static final int EVENT_PROC_FINISH = EVENT_PROC | EVENT_FLAG_FINISH;
        public static final int EVENT_FOREGROUND_START = EVENT_FOREGROUND | EVENT_FLAG_START;
        public static final int EVENT_FOREGROUND_FINISH = EVENT_FOREGROUND | EVENT_FLAG_FINISH;
        public static final int EVENT_TOP_START = EVENT_TOP | EVENT_FLAG_START;
        public static final int EVENT_TOP_FINISH = EVENT_TOP | EVENT_FLAG_FINISH;
        public static final int EVENT_SYNC_START = EVENT_SYNC | EVENT_FLAG_START;
        public static final int EVENT_SYNC_FINISH = EVENT_SYNC | EVENT_FLAG_FINISH;
        public static final int EVENT_WAKE_LOCK_START = EVENT_WAKE_LOCK | EVENT_FLAG_START;
        public static final int EVENT_WAKE_LOCK_FINISH = EVENT_WAKE_LOCK | EVENT_FLAG_FINISH;
        public static final int EVENT_JOB_START = EVENT_JOB | EVENT_FLAG_START;
        public static final int EVENT_JOB_FINISH = EVENT_JOB | EVENT_FLAG_FINISH;
        public static final int EVENT_USER_RUNNING_START = EVENT_USER_RUNNING | EVENT_FLAG_START;
        public static final int EVENT_USER_RUNNING_FINISH = EVENT_USER_RUNNING | EVENT_FLAG_FINISH;
        public static final int EVENT_USER_FOREGROUND_START =
                EVENT_USER_FOREGROUND | EVENT_FLAG_START;
        public static final int EVENT_USER_FOREGROUND_FINISH =
                EVENT_USER_FOREGROUND | EVENT_FLAG_FINISH;
        public static final int EVENT_ALARM_START = EVENT_ALARM | EVENT_FLAG_START;
        public static final int EVENT_ALARM_FINISH = EVENT_ALARM | EVENT_FLAG_FINISH;
        public static final int EVENT_TEMP_WHITELIST_START =
                EVENT_TEMP_WHITELIST | EVENT_FLAG_START;
        public static final int EVENT_TEMP_WHITELIST_FINISH =
                EVENT_TEMP_WHITELIST | EVENT_FLAG_FINISH;
        public static final int EVENT_LONG_WAKE_LOCK_START =
                EVENT_LONG_WAKE_LOCK | EVENT_FLAG_START;
        public static final int EVENT_LONG_WAKE_LOCK_FINISH =
                EVENT_LONG_WAKE_LOCK | EVENT_FLAG_FINISH;

        // For CMD_EVENT.
        public int eventCode;
        public HistoryTag eventTag;

        // Only set for CMD_CURRENT_TIME or CMD_RESET, as per System.currentTimeMillis().
        public long currentTime;

        // Meta-data when reading.
        public int numReadInts;

        // Pre-allocated objects.
        public final HistoryTag localWakelockTag = new HistoryTag();
        public final HistoryTag localWakeReasonTag = new HistoryTag();
        public final HistoryTag localEventTag = new HistoryTag();

        @UnsupportedAppUsage
        public HistoryItem() {
        }

        public HistoryItem(Parcel src) {
            readFromParcel(src);
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(time);
            int bat = (((int)cmd)&0xff)
                    | ((((int)batteryLevel)<<8)&0xff00)
                    | ((((int)batteryStatus)<<16)&0xf0000)
                    | ((((int)batteryHealth)<<20)&0xf00000)
                    | ((((int)batteryPlugType)<<24)&0xf000000)
                    | (wakelockTag != null ? 0x10000000 : 0)
                    | (wakeReasonTag != null ? 0x20000000 : 0)
                    | (eventCode != EVENT_NONE ? 0x40000000 : 0);
            dest.writeInt(bat);
            bat = (((int)batteryTemperature)&0xffff)
                    | ((((int)batteryVoltage)<<16)&0xffff0000);
            dest.writeInt(bat);
            dest.writeInt(batteryChargeUah);
            dest.writeDouble(modemRailChargeMah);
            dest.writeDouble(wifiRailChargeMah);
            dest.writeInt(states);
            dest.writeInt(states2);
            if (wakelockTag != null) {
                wakelockTag.writeToParcel(dest, flags);
            }
            if (wakeReasonTag != null) {
                wakeReasonTag.writeToParcel(dest, flags);
            }
            if (eventCode != EVENT_NONE) {
                dest.writeInt(eventCode);
                eventTag.writeToParcel(dest, flags);
            }
            if (cmd == CMD_CURRENT_TIME || cmd == CMD_RESET) {
                dest.writeLong(currentTime);
            }
        }

        public void readFromParcel(Parcel src) {
            int start = src.dataPosition();
            time = src.readLong();
            int bat = src.readInt();
            cmd = (byte)(bat&0xff);
            batteryLevel = (byte)((bat>>8)&0xff);
            batteryStatus = (byte)((bat>>16)&0xf);
            batteryHealth = (byte)((bat>>20)&0xf);
            batteryPlugType = (byte)((bat>>24)&0xf);
            int bat2 = src.readInt();
            batteryTemperature = (short)(bat2&0xffff);
            batteryVoltage = (char)((bat2>>16)&0xffff);
            batteryChargeUah = src.readInt();
            modemRailChargeMah = src.readDouble();
            wifiRailChargeMah = src.readDouble();
            states = src.readInt();
            states2 = src.readInt();
            if ((bat&0x10000000) != 0) {
                wakelockTag = localWakelockTag;
                wakelockTag.readFromParcel(src);
            } else {
                wakelockTag = null;
            }
            if ((bat&0x20000000) != 0) {
                wakeReasonTag = localWakeReasonTag;
                wakeReasonTag.readFromParcel(src);
            } else {
                wakeReasonTag = null;
            }
            if ((bat&0x40000000) != 0) {
                eventCode = src.readInt();
                eventTag = localEventTag;
                eventTag.readFromParcel(src);
            } else {
                eventCode = EVENT_NONE;
                eventTag = null;
            }
            if (cmd == CMD_CURRENT_TIME || cmd == CMD_RESET) {
                currentTime = src.readLong();
            } else {
                currentTime = 0;
            }
            numReadInts += (src.dataPosition()-start)/4;
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public void clear() {
            time = 0;
            cmd = CMD_NULL;
            batteryLevel = 0;
            batteryStatus = 0;
            batteryHealth = 0;
            batteryPlugType = 0;
            batteryTemperature = 0;
            batteryVoltage = 0;
            batteryChargeUah = 0;
            modemRailChargeMah = 0;
            wifiRailChargeMah = 0;
            states = 0;
            states2 = 0;
            wakelockTag = null;
            wakeReasonTag = null;
            eventCode = EVENT_NONE;
            eventTag = null;
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public void setTo(HistoryItem o) {
            time = o.time;
            cmd = o.cmd;
            setToCommon(o);
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public void setTo(long time, byte cmd, HistoryItem o) {
            this.time = time;
            this.cmd = cmd;
            setToCommon(o);
        }

        private void setToCommon(HistoryItem o) {
            batteryLevel = o.batteryLevel;
            batteryStatus = o.batteryStatus;
            batteryHealth = o.batteryHealth;
            batteryPlugType = o.batteryPlugType;
            batteryTemperature = o.batteryTemperature;
            batteryVoltage = o.batteryVoltage;
            batteryChargeUah = o.batteryChargeUah;
            modemRailChargeMah = o.modemRailChargeMah;
            wifiRailChargeMah = o.wifiRailChargeMah;
            states = o.states;
            states2 = o.states2;
            if (o.wakelockTag != null) {
                wakelockTag = localWakelockTag;
                wakelockTag.setTo(o.wakelockTag);
            } else {
                wakelockTag = null;
            }
            if (o.wakeReasonTag != null) {
                wakeReasonTag = localWakeReasonTag;
                wakeReasonTag.setTo(o.wakeReasonTag);
            } else {
                wakeReasonTag = null;
            }
            eventCode = o.eventCode;
            if (o.eventTag != null) {
                eventTag = localEventTag;
                eventTag.setTo(o.eventTag);
            } else {
                eventTag = null;
            }
            currentTime = o.currentTime;
        }

        public boolean sameNonEvent(HistoryItem o) {
            return batteryLevel == o.batteryLevel
                    && batteryStatus == o.batteryStatus
                    && batteryHealth == o.batteryHealth
                    && batteryPlugType == o.batteryPlugType
                    && batteryTemperature == o.batteryTemperature
                    && batteryVoltage == o.batteryVoltage
                    && batteryChargeUah == o.batteryChargeUah
                    && modemRailChargeMah == o.modemRailChargeMah
                    && wifiRailChargeMah == o.wifiRailChargeMah
                    && states == o.states
                    && states2 == o.states2
                    && currentTime == o.currentTime;
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
        public boolean same(HistoryItem o) {
            if (!sameNonEvent(o) || eventCode != o.eventCode) {
                return false;
            }
            if (wakelockTag != o.wakelockTag) {
                if (wakelockTag == null || o.wakelockTag == null) {
                    return false;
                }
                if (!wakelockTag.equals(o.wakelockTag)) {
                    return false;
                }
            }
            if (wakeReasonTag != o.wakeReasonTag) {
                if (wakeReasonTag == null || o.wakeReasonTag == null) {
                    return false;
                }
                if (!wakeReasonTag.equals(o.wakeReasonTag)) {
                    return false;
                }
            }
            if (eventTag != o.eventTag) {
                if (eventTag == null || o.eventTag == null) {
                    return false;
                }
                if (!eventTag.equals(o.eventTag)) {
                    return false;
                }
            }
            return true;
        }
    }

    public final static class HistoryEventTracker {
        private final HashMap<String, SparseIntArray>[] mActiveEvents
                = (HashMap<String, SparseIntArray>[]) new HashMap[HistoryItem.EVENT_COUNT];

        public boolean updateState(int code, String name, int uid, int poolIdx) {
            if ((code&HistoryItem.EVENT_FLAG_START) != 0) {
                int idx = code&HistoryItem.EVENT_TYPE_MASK;
                HashMap<String, SparseIntArray> active = mActiveEvents[idx];
                if (active == null) {
                    active = new HashMap<>();
                    mActiveEvents[idx] = active;
                }
                SparseIntArray uids = active.get(name);
                if (uids == null) {
                    uids = new SparseIntArray();
                    active.put(name, uids);
                }
                if (uids.indexOfKey(uid) >= 0) {
                    // Already set, nothing to do!
                    return false;
                }
                uids.put(uid, poolIdx);
            } else if ((code&HistoryItem.EVENT_FLAG_FINISH) != 0) {
                int idx = code&HistoryItem.EVENT_TYPE_MASK;
                HashMap<String, SparseIntArray> active = mActiveEvents[idx];
                if (active == null) {
                    // not currently active, nothing to do.
                    return false;
                }
                SparseIntArray uids = active.get(name);
                if (uids == null) {
                    // not currently active, nothing to do.
                    return false;
                }
                idx = uids.indexOfKey(uid);
                if (idx < 0) {
                    // not currently active, nothing to do.
                    return false;
                }
                uids.removeAt(idx);
                if (uids.size() <= 0) {
                    active.remove(name);
                }
            }
            return true;
        }

        public void removeEvents(int code) {
            int idx = code&HistoryItem.EVENT_TYPE_MASK;
            mActiveEvents[idx] = null;
        }

        public HashMap<String, SparseIntArray> getStateForEvent(int code) {
            return mActiveEvents[code];
        }
    }

    public static final class BitDescription {
        public final int mask;
        public final int shift;
        public final String name;
        public final String shortName;
        public final String[] values;
        public final String[] shortValues;

        public BitDescription(int mask, String name, String shortName) {
            this.mask = mask;
            this.shift = -1;
            this.name = name;
            this.shortName = shortName;
            this.values = null;
            this.shortValues = null;
        }

        public BitDescription(int mask, int shift, String name, String shortName,
                String[] values, String[] shortValues) {
            this.mask = mask;
            this.shift = shift;
            this.name = name;
            this.shortName = shortName;
            this.values = values;
            this.shortValues = shortValues;
        }
    }

    /**
     * Don't allow any more batching in to the current history event.  This
     * is called when printing partial histories, so to ensure that the next
     * history event will go in to a new batch after what was printed in the
     * last partial history.
     */
    public abstract void commitCurrentHistoryBatchLocked();

    public abstract int getHistoryTotalSize();

    public abstract int getHistoryUsedSize();

    @UnsupportedAppUsage
    public abstract boolean startIteratingHistoryLocked();

    public abstract int getHistoryStringPoolSize();

    public abstract int getHistoryStringPoolBytes();

    public abstract String getHistoryTagPoolString(int index);

    public abstract int getHistoryTagPoolUid(int index);

    @UnsupportedAppUsage
    public abstract boolean getNextHistoryLocked(HistoryItem out);

    public abstract void finishIteratingHistoryLocked();

    /**
     * Return the base time offset for the battery history.
     */
    public abstract long getHistoryBaseTime();

    /**
     * Returns the number of times the device has been started.
     */
    public abstract int getStartCount();

    /**
     * Returns the time in microseconds that the screen has been on while the device was
     * running on battery.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public abstract long getScreenOnTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times the screen was turned on.
     *
     * {@hide}
     */
    public abstract int getScreenOnCount(int which);

    /**
     * Returns the time in microseconds that the screen has been dozing while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getScreenDozeTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times the screen was turned dozing.
     *
     * {@hide}
     */
    public abstract int getScreenDozeCount(int which);

    public abstract long getInteractiveTime(long elapsedRealtimeUs, int which);

    public static final int SCREEN_BRIGHTNESS_DARK = 0;
    public static final int SCREEN_BRIGHTNESS_DIM = 1;
    public static final int SCREEN_BRIGHTNESS_MEDIUM = 2;
    public static final int SCREEN_BRIGHTNESS_LIGHT = 3;
    public static final int SCREEN_BRIGHTNESS_BRIGHT = 4;

    static final String[] SCREEN_BRIGHTNESS_NAMES = {
        "dark", "dim", "medium", "light", "bright"
    };

    static final String[] SCREEN_BRIGHTNESS_SHORT_NAMES = {
        "0", "1", "2", "3", "4"
    };

    @UnsupportedAppUsage
    public static final int NUM_SCREEN_BRIGHTNESS_BINS = 5;

    /**
     * Returns the time in microseconds that the screen has been on with
     * the given brightness
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public abstract long getScreenBrightnessTime(int brightnessBin,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the {@link Timer} object that tracks the given screen brightness.
     *
     * {@hide}
     */
    public abstract Timer getScreenBrightnessTimer(int brightnessBin);

    /**
     * Returns the time in microseconds that power save mode has been enabled while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getPowerSaveModeEnabledTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that power save mode was enabled.
     *
     * {@hide}
     */
    public abstract int getPowerSaveModeEnabledCount(int which);

    /**
     * Constant for device idle mode: not active.
     */
    public static final int DEVICE_IDLE_MODE_OFF = ServerProtoEnums.DEVICE_IDLE_MODE_OFF; // 0

    /**
     * Constant for device idle mode: active in lightweight mode.
     */
    public static final int DEVICE_IDLE_MODE_LIGHT = ServerProtoEnums.DEVICE_IDLE_MODE_LIGHT; // 1

    /**
     * Constant for device idle mode: active in full mode.
     */
    public static final int DEVICE_IDLE_MODE_DEEP = ServerProtoEnums.DEVICE_IDLE_MODE_DEEP; // 2

    /**
     * Returns the time in microseconds that device has been in idle mode while
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getDeviceIdleModeTime(int mode, long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that the devie has gone in to idle mode.
     *
     * {@hide}
     */
    public abstract int getDeviceIdleModeCount(int mode, int which);

    /**
     * Return the longest duration we spent in a particular device idle mode (fully in the
     * mode, not in idle maintenance etc).
     */
    public abstract long getLongestDeviceIdleModeTime(int mode);

    /**
     * Returns the time in microseconds that device has been in idling while on
     * battery.  This is broader than {@link #getDeviceIdleModeTime} -- it
     * counts all of the time that we consider the device to be idle, whether or not
     * it is currently in the actual device idle mode.
     *
     * {@hide}
     */
    public abstract long getDeviceIdlingTime(int mode, long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that the device has started idling.
     *
     * {@hide}
     */
    public abstract int getDeviceIdlingCount(int mode, int which);

    /**
     * Returns the number of times that connectivity state changed.
     *
     * {@hide}
     */
    public abstract int getNumConnectivityChange(int which);


    /**
     * Returns the time in microseconds that the phone has been running with
     * the given GPS signal quality level
     *
     * {@hide}
     */
    public abstract long getGpsSignalQualityTime(int strengthBin,
        long elapsedRealtimeUs, int which);

    /**
     * Returns the GPS battery drain in mA-ms
     *
     * {@hide}
     */
    public abstract long getGpsBatteryDrainMaMs();

    /**
     * Returns the time in microseconds that the phone has been on while the device was
     * running on battery.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public abstract long getPhoneOnTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times a phone call was activated.
     *
     * {@hide}
     */
    public abstract int getPhoneOnCount(int which);

    /**
     * Returns the time in microseconds that the phone has been running with
     * the given signal strength.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public abstract long getPhoneSignalStrengthTime(int strengthBin,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the time in microseconds that the phone has been trying to
     * acquire a signal.
     *
     * {@hide}
     */
    public abstract long getPhoneSignalScanningTime(
            long elapsedRealtimeUs, int which);

    /**
     * Returns the {@link Timer} object that tracks how much the phone has been trying to
     * acquire a signal.
     *
     * {@hide}
     */
    public abstract Timer getPhoneSignalScanningTimer();

    /**
     * Returns the number of times the phone has entered the given signal strength.
     *
     * {@hide}
     */
    public abstract int getPhoneSignalStrengthCount(int strengthBin, int which);

    /**
     * Return the {@link Timer} object used to track the given signal strength's duration and
     * counts.
     */
    protected abstract Timer getPhoneSignalStrengthTimer(int strengthBin);

    /**
     * Returns the time in microseconds that the mobile network has been active
     * (in a high power state).
     *
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public abstract long getMobileRadioActiveTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that the mobile network has transitioned to the
     * active state.
     *
     * {@hide}
     */
    public abstract int getMobileRadioActiveCount(int which);

    /**
     * Returns the time in microseconds that is the difference between the mobile radio
     * time we saw based on the elapsed timestamp when going down vs. the given time stamp
     * from the radio.
     *
     * {@hide}
     */
    public abstract long getMobileRadioActiveAdjustedTime(int which);

    /**
     * Returns the time in microseconds that the mobile network has been active
     * (in a high power state) but not being able to blame on an app.
     *
     * {@hide}
     */
    public abstract long getMobileRadioActiveUnknownTime(int which);

    /**
     * Return count of number of times radio was up that could not be blamed on apps.
     *
     * {@hide}
     */
    public abstract int getMobileRadioActiveUnknownCount(int which);

    public static final int DATA_CONNECTION_OUT_OF_SERVICE = 0;
    public static final int DATA_CONNECTION_EMERGENCY_SERVICE =
            TelephonyManager.getAllNetworkTypes().length + 1;
    public static final int DATA_CONNECTION_OTHER = DATA_CONNECTION_EMERGENCY_SERVICE + 1;


    static final String[] DATA_CONNECTION_NAMES = {
        "oos", "gprs", "edge", "umts", "cdma", "evdo_0", "evdo_A",
        "1xrtt", "hsdpa", "hsupa", "hspa", "iden", "evdo_b", "lte",
        "ehrpd", "hspap", "gsm", "td_scdma", "iwlan", "lte_ca", "nr",
        "emngcy", "other"
    };

    @UnsupportedAppUsage
    public static final int NUM_DATA_CONNECTION_TYPES = DATA_CONNECTION_OTHER + 1;

    /**
     * Returns the time in microseconds that the phone has been running with
     * the given data connection.
     *
     * {@hide}
     */
    public abstract long getPhoneDataConnectionTime(int dataType,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times the phone has entered the given data
     * connection type.
     *
     * {@hide}
     */
    public abstract int getPhoneDataConnectionCount(int dataType, int which);

    /**
     * Returns the {@link Timer} object that tracks the phone's data connection type stats.
     */
    public abstract Timer getPhoneDataConnectionTimer(int dataType);

    static final String[] WIFI_SUPPL_STATE_NAMES = {
        "invalid", "disconn", "disabled", "inactive", "scanning",
        "authenticating", "associating", "associated", "4-way-handshake",
        "group-handshake", "completed", "dormant", "uninit"
    };

    static final String[] WIFI_SUPPL_STATE_SHORT_NAMES = {
        "inv", "dsc", "dis", "inact", "scan",
        "auth", "ascing", "asced", "4-way",
        "group", "compl", "dorm", "uninit"
    };

    /**
     * Returned value if power data is unavailable.
     *
     * {@hide}
     */
    public static final long POWER_DATA_UNAVAILABLE = -1L;

    /**
     * Returns the battery consumption (in microcoulombs) of bluetooth, derived from on
     * device power measurement data.
     * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
     *
     * {@hide}
     */
    public abstract long getBluetoothMeasuredBatteryConsumptionUC();

    /**
     * Returns the battery consumption (in microcoulombs) of the cpu, derived from on device power
     * measurement data.
     * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
     *
     * {@hide}
     */
    public abstract long getCpuMeasuredBatteryConsumptionUC();

    /**
     * Returns the battery consumption (in microcoulombs) of the screen while on, derived from on
     * device power measurement data.
     * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
     *
     * {@hide}
     */
    public abstract long getScreenOnMeasuredBatteryConsumptionUC();

    /**
     * Returns the battery consumption (in microcoulombs) of the screen in doze, derived from on
     * device power measurement data.
     * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
     *
     * {@hide}
     */
    public abstract long getScreenDozeMeasuredBatteryConsumptionUC();

    /**
     * Returns the battery consumption (in microcoulombs) of wifi, derived from on
     * device power measurement data.
     * Will return {@link #POWER_DATA_UNAVAILABLE} if data is unavailable.
     *
     * {@hide}
     */
    public abstract long getWifiMeasuredBatteryConsumptionUC();

    /**
     * Returns the battery consumption (in microcoulombs) that each
     * {@link android.hardware.power.stats.EnergyConsumer.ordinal} of (custom) energy consumer
     * type {@link android.hardware.power.stats.EnergyConsumerType#OTHER}) consumed.
     *
     * @return charge (in microcoulombs) used by each (custom) energy consumer of type OTHER,
     * indexed by their ordinal. Returns null if no energy reporting is supported.
     *
     * {@hide}
     */
    public abstract @Nullable long[] getCustomConsumerMeasuredBatteryConsumptionUC();

    public static final BitDescription[] HISTORY_STATE_DESCRIPTIONS = new BitDescription[] {
        new BitDescription(HistoryItem.STATE_CPU_RUNNING_FLAG, "running", "r"),
        new BitDescription(HistoryItem.STATE_WAKE_LOCK_FLAG, "wake_lock", "w"),
        new BitDescription(HistoryItem.STATE_SENSOR_ON_FLAG, "sensor", "s"),
        new BitDescription(HistoryItem.STATE_GPS_ON_FLAG, "gps", "g"),
        new BitDescription(HistoryItem.STATE_WIFI_FULL_LOCK_FLAG, "wifi_full_lock", "Wl"),
        new BitDescription(HistoryItem.STATE_WIFI_SCAN_FLAG, "wifi_scan", "Ws"),
        new BitDescription(HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG, "wifi_multicast", "Wm"),
        new BitDescription(HistoryItem.STATE_WIFI_RADIO_ACTIVE_FLAG, "wifi_radio", "Wr"),
        new BitDescription(HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG, "mobile_radio", "Pr"),
        new BitDescription(HistoryItem.STATE_PHONE_SCANNING_FLAG, "phone_scanning", "Psc"),
        new BitDescription(HistoryItem.STATE_AUDIO_ON_FLAG, "audio", "a"),
        new BitDescription(HistoryItem.STATE_SCREEN_ON_FLAG, "screen", "S"),
        new BitDescription(HistoryItem.STATE_BATTERY_PLUGGED_FLAG, "plugged", "BP"),
        new BitDescription(HistoryItem.STATE_SCREEN_DOZE_FLAG, "screen_doze", "Sd"),
        new BitDescription(HistoryItem.STATE_DATA_CONNECTION_MASK,
                HistoryItem.STATE_DATA_CONNECTION_SHIFT, "data_conn", "Pcn",
                DATA_CONNECTION_NAMES, DATA_CONNECTION_NAMES),
        new BitDescription(HistoryItem.STATE_PHONE_STATE_MASK,
                HistoryItem.STATE_PHONE_STATE_SHIFT, "phone_state", "Pst",
                new String[] {"in", "out", "emergency", "off"},
                new String[] {"in", "out", "em", "off"}),
        new BitDescription(HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_MASK,
                HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_SHIFT, "phone_signal_strength", "Pss",
                new String[] { "none", "poor", "moderate", "good", "great" },
                new String[] { "0", "1", "2", "3", "4" }),
        new BitDescription(HistoryItem.STATE_BRIGHTNESS_MASK,
                HistoryItem.STATE_BRIGHTNESS_SHIFT, "brightness", "Sb",
                SCREEN_BRIGHTNESS_NAMES, SCREEN_BRIGHTNESS_SHORT_NAMES),
    };

    public static final BitDescription[] HISTORY_STATE2_DESCRIPTIONS = new BitDescription[] {
        new BitDescription(HistoryItem.STATE2_POWER_SAVE_FLAG, "power_save", "ps"),
        new BitDescription(HistoryItem.STATE2_VIDEO_ON_FLAG, "video", "v"),
        new BitDescription(HistoryItem.STATE2_WIFI_RUNNING_FLAG, "wifi_running", "Ww"),
        new BitDescription(HistoryItem.STATE2_WIFI_ON_FLAG, "wifi", "W"),
        new BitDescription(HistoryItem.STATE2_FLASHLIGHT_FLAG, "flashlight", "fl"),
        new BitDescription(HistoryItem.STATE2_DEVICE_IDLE_MASK,
                HistoryItem.STATE2_DEVICE_IDLE_SHIFT, "device_idle", "di",
                new String[] { "off", "light", "full", "???" },
                new String[] { "off", "light", "full", "???" }),
        new BitDescription(HistoryItem.STATE2_CHARGING_FLAG, "charging", "ch"),
        new BitDescription(HistoryItem.STATE2_USB_DATA_LINK_FLAG, "usb_data", "Ud"),
        new BitDescription(HistoryItem.STATE2_PHONE_IN_CALL_FLAG, "phone_in_call", "Pcl"),
        new BitDescription(HistoryItem.STATE2_BLUETOOTH_ON_FLAG, "bluetooth", "b"),
        new BitDescription(HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_MASK,
                HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_SHIFT, "wifi_signal_strength", "Wss",
                new String[] { "0", "1", "2", "3", "4" },
                new String[] { "0", "1", "2", "3", "4" }),
        new BitDescription(HistoryItem.STATE2_WIFI_SUPPL_STATE_MASK,
                HistoryItem.STATE2_WIFI_SUPPL_STATE_SHIFT, "wifi_suppl", "Wsp",
                WIFI_SUPPL_STATE_NAMES, WIFI_SUPPL_STATE_SHORT_NAMES),
        new BitDescription(HistoryItem.STATE2_CAMERA_FLAG, "camera", "ca"),
        new BitDescription(HistoryItem.STATE2_BLUETOOTH_SCAN_FLAG, "ble_scan", "bles"),
        new BitDescription(HistoryItem.STATE2_CELLULAR_HIGH_TX_POWER_FLAG,
                "cellular_high_tx_power", "Chtp"),
        new BitDescription(HistoryItem.STATE2_GPS_SIGNAL_QUALITY_MASK,
            HistoryItem.STATE2_GPS_SIGNAL_QUALITY_SHIFT, "gps_signal_quality", "Gss",
            new String[] { "poor", "good"}, new String[] { "poor", "good"})
    };

    public static final String[] HISTORY_EVENT_NAMES = new String[] {
            "null", "proc", "fg", "top", "sync", "wake_lock_in", "job", "user", "userfg", "conn",
            "active", "pkginst", "pkgunin", "alarm", "stats", "pkginactive", "pkgactive",
            "tmpwhitelist", "screenwake", "wakeupap", "longwake", "est_capacity"
    };

    public static final String[] HISTORY_EVENT_CHECKIN_NAMES = new String[] {
            "Enl", "Epr", "Efg", "Etp", "Esy", "Ewl", "Ejb", "Eur", "Euf", "Ecn",
            "Eac", "Epi", "Epu", "Eal", "Est", "Eai", "Eaa", "Etw",
            "Esw", "Ewa", "Elw", "Eec"
    };

    @FunctionalInterface
    public interface IntToString {
        String applyAsString(int val);
    }

    private static final IntToString sUidToString = UserHandle::formatUid;
    private static final IntToString sIntToString = Integer::toString;

    public static final IntToString[] HISTORY_EVENT_INT_FORMATTERS = new IntToString[] {
            sUidToString, sUidToString, sUidToString, sUidToString, sUidToString, sUidToString,
            sUidToString, sUidToString, sUidToString, sUidToString, sUidToString, sIntToString,
            sUidToString, sUidToString, sUidToString, sUidToString, sUidToString, sUidToString,
            sUidToString, sUidToString, sUidToString, sIntToString
    };

    /**
     * Returns total time for WiFi Multicast Wakelock timer.
     * Note that this may be different from the sum of per uid timer values.
     *
     *  {@hide}
     */
    public abstract long getWifiMulticastWakelockTime(long elapsedRealtimeUs, int which);

    /**
     * Returns total time for WiFi Multicast Wakelock timer
     * Note that this may be different from the sum of per uid timer values.
     *
     * {@hide}
     */
    public abstract int getWifiMulticastWakelockCount(int which);

    /**
     * Returns the time in microseconds that wifi has been on while the device was
     * running on battery.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public abstract long getWifiOnTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the time in microseconds that wifi has been active while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getWifiActiveTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the time in microseconds that wifi has been on and the driver has
     * been in the running state while the device was running on battery.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public abstract long getGlobalWifiRunningTime(long elapsedRealtimeUs, int which);

    static final String[] WIFI_STATE_NAMES = {
        "off", "scanning", "no_net", "disconn",
        "sta", "p2p", "sta_p2p", "soft_ap"
    };

    /**
     * Returns the time in microseconds that WiFi has been running in the given state.
     *
     * {@hide}
     */
    public abstract long getWifiStateTime(@WifiState int wifiState,
            long elapsedRealtimeUs, @StatName int which);

    /**
     * Returns the number of times that WiFi has entered the given state.
     *
     * {@hide}
     */
    public abstract int getWifiStateCount(@WifiState int wifiState, @StatName int which);

    /**
     * Returns the {@link Timer} object that tracks the given WiFi state.
     *
     * {@hide}
     */
    public abstract Timer getWifiStateTimer(@WifiState int wifiState);

    /**
     * Returns the time in microseconds that the wifi supplicant has been
     * in a given state.
     *
     * {@hide}
     */
    public abstract long getWifiSupplStateTime(@WifiSupplState int state, long elapsedRealtimeUs,
            @StatName int which);

    /**
     * Returns the number of times that the wifi supplicant has transitioned
     * to a given state.
     *
     * {@hide}
     */
    public abstract int getWifiSupplStateCount(@WifiSupplState int state, @StatName int which);

    /**
     * Returns the {@link Timer} object that tracks the given wifi supplicant state.
     *
     * {@hide}
     */
    public abstract Timer getWifiSupplStateTimer(@WifiSupplState int state);

    public static final int NUM_WIFI_SIGNAL_STRENGTH_BINS = 5;

    /**
     * Returns the time in microseconds that WIFI has been running with
     * the given signal strength.
     *
     * {@hide}
     */
    public abstract long getWifiSignalStrengthTime(int strengthBin,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times WIFI has entered the given signal strength.
     *
     * {@hide}
     */
    public abstract int getWifiSignalStrengthCount(int strengthBin, int which);

    /**
     * Returns the {@link Timer} object that tracks the given WIFI signal strength.
     *
     * {@hide}
     */
    public abstract Timer getWifiSignalStrengthTimer(int strengthBin);

    /**
     * Returns the time in microseconds that the flashlight has been on while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getFlashlightOnTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that the flashlight has been turned on while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getFlashlightOnCount(int which);

    /**
     * Returns the time in microseconds that the camera has been on while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getCameraOnTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the time in microseconds that bluetooth scans were running while the device was
     * on battery.
     *
     * {@hide}
     */
    public abstract long getBluetoothScanTime(long elapsedRealtimeUs, int which);

    public static final int NETWORK_MOBILE_RX_DATA = 0;
    public static final int NETWORK_MOBILE_TX_DATA = 1;
    public static final int NETWORK_WIFI_RX_DATA = 2;
    public static final int NETWORK_WIFI_TX_DATA = 3;
    public static final int NETWORK_BT_RX_DATA = 4;
    public static final int NETWORK_BT_TX_DATA = 5;
    public static final int NETWORK_MOBILE_BG_RX_DATA = 6;
    public static final int NETWORK_MOBILE_BG_TX_DATA = 7;
    public static final int NETWORK_WIFI_BG_RX_DATA = 8;
    public static final int NETWORK_WIFI_BG_TX_DATA = 9;
    public static final int NUM_NETWORK_ACTIVITY_TYPES = NETWORK_WIFI_BG_TX_DATA + 1;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public abstract long getNetworkActivityBytes(int type, int which);
    public abstract long getNetworkActivityPackets(int type, int which);

    /**
     * Returns true if the BatteryStats object has detailed WiFi power reports.
     * When true, calling {@link #getWifiControllerActivity()} will yield the
     * actual power data.
     */
    public abstract boolean hasWifiActivityReporting();

    /**
     * Returns a {@link ControllerActivityCounter} which is an aggregate of the times spent
     * in various radio controller states, such as transmit, receive, and idle.
     * @return non-null {@link ControllerActivityCounter}
     */
    public abstract ControllerActivityCounter getWifiControllerActivity();

    /**
     * Returns true if the BatteryStats object has detailed bluetooth power reports.
     * When true, calling {@link #getBluetoothControllerActivity()} will yield the
     * actual power data.
     */
    public abstract boolean hasBluetoothActivityReporting();

    /**
     * Returns a {@link ControllerActivityCounter} which is an aggregate of the times spent
     * in various radio controller states, such as transmit, receive, and idle.
     * @return non-null {@link ControllerActivityCounter}
     */
    public abstract ControllerActivityCounter getBluetoothControllerActivity();

    /**
     * Returns true if the BatteryStats object has detailed modem power reports.
     * When true, calling {@link #getModemControllerActivity()} will yield the
     * actual power data.
     */
    public abstract boolean hasModemActivityReporting();

    /**
     * Returns a {@link ControllerActivityCounter} which is an aggregate of the times spent
     * in various radio controller states, such as transmit, receive, and idle.
     * @return non-null {@link ControllerActivityCounter}
     */
    public abstract ControllerActivityCounter getModemControllerActivity();

    /**
     * Return the wall clock time when battery stats data collection started.
     */
    public abstract long getStartClockTime();

    /**
     * Return platform version tag that we were running in when the battery stats started.
     */
    public abstract String getStartPlatformVersion();

    /**
     * Return platform version tag that we were running in when the battery stats ended.
     */
    public abstract String getEndPlatformVersion();

    /**
     * Return the internal version code of the parcelled format.
     */
    public abstract int getParcelVersion();

    /**
     * Return whether we are currently running on battery.
     */
    public abstract boolean getIsOnBattery();

    /**
     * Returns the timestamp of when battery stats collection started, in microseconds.
     */
    public abstract long getStatsStartRealtime();

    /**
     * Returns a SparseArray containing the statistics for each uid.
     */
    @UnsupportedAppUsage
    public abstract SparseArray<? extends Uid> getUidStats();

    /**
     * Returns the current battery uptime in microseconds.
     *
     * @param curTime the amount of elapsed realtime in microseconds.
     */
    @UnsupportedAppUsage
    public abstract long getBatteryUptime(long curTime);

    /**
     * Returns the current battery realtime in microseconds.
     *
     * @param curTime the amount of elapsed realtime in microseconds.
     */
    public abstract long getBatteryRealtime(long curTime);

    /**
     * Returns the battery percentage level at the last time the device was unplugged from power, or
     * the last time it booted on battery power.
     */
    public abstract int getDischargeStartLevel();

    /**
     * Returns the current battery percentage level if we are in a discharge cycle, otherwise
     * returns the level at the last plug event.
     */
    public abstract int getDischargeCurrentLevel();

    /**
     * Get the amount the battery has discharged since the stats were
     * last reset after charging, as a lower-end approximation.
     */
    public abstract int getLowDischargeAmountSinceCharge();

    /**
     * Get the amount the battery has discharged since the stats were
     * last reset after charging, as an upper-end approximation.
     */
    public abstract int getHighDischargeAmountSinceCharge();

    /**
     * Retrieve the discharge amount over the selected discharge period <var>which</var>.
     */
    public abstract int getDischargeAmount(int which);

    /**
     * Get the amount the battery has discharged while the screen was on,
     * since the last time power was unplugged.
     */
    public abstract int getDischargeAmountScreenOn();

    /**
     * Get the amount the battery has discharged while the screen was on,
     * since the last time the device was charged.
     */
    public abstract int getDischargeAmountScreenOnSinceCharge();

    /**
     * Get the amount the battery has discharged while the screen was off,
     * since the last time power was unplugged.
     */
    public abstract int getDischargeAmountScreenOff();

    /**
     * Get the amount the battery has discharged while the screen was off,
     * since the last time the device was charged.
     */
    public abstract int getDischargeAmountScreenOffSinceCharge();

    /**
     * Get the amount the battery has discharged while the screen was dozing,
     * since the last time power was unplugged.
     */
    public abstract int getDischargeAmountScreenDoze();

    /**
     * Get the amount the battery has discharged while the screen was dozing,
     * since the last time the device was charged.
     */
    public abstract int getDischargeAmountScreenDozeSinceCharge();

    /**
     * Returns the approximate CPU time (in microseconds) spent by the system server handling
     * incoming service calls from apps.  The result is returned as an array of longs,
     * organized as a sequence like this:
     * <pre>
     *     cluster1-speeed1, cluster1-speed2, ..., cluster2-speed1, cluster2-speed2, ...
     * </pre>
     *
     * @see com.android.internal.os.PowerProfile#getNumCpuClusters()
     * @see com.android.internal.os.PowerProfile#getNumSpeedStepsInCpuCluster(int)
     */
    @Nullable
    public abstract long[] getSystemServiceTimeAtCpuSpeeds();

    /**
     * Returns the total, last, or current battery uptime in microseconds.
     *
     * @param curTime the elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    @UnsupportedAppUsage
    public abstract long computeBatteryUptime(long curTime, int which);

    /**
     * Returns the total, last, or current battery realtime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    @UnsupportedAppUsage
    public abstract long computeBatteryRealtime(long curTime, int which);

    /**
     * Returns the total, last, or current battery screen off/doze uptime in microseconds.
     *
     * @param curTime the elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeBatteryScreenOffUptime(long curTime, int which);

    /**
     * Returns the total, last, or current battery screen off/doze realtime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeBatteryScreenOffRealtime(long curTime, int which);

    /**
     * Returns the total, last, or current uptime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeUptime(long curTime, int which);

    /**
     * Returns the total, last, or current realtime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeRealtime(long curTime, int which);

    /**
     * Compute an approximation for how much run time (in microseconds) is remaining on
     * the battery.  Returns -1 if no time can be computed: either there is not
     * enough current data to make a decision, or the battery is currently
     * charging.
     *
     * @param curTime The current elapsed realtime in microseconds.
     */
    @UnsupportedAppUsage
    public abstract long computeBatteryTimeRemaining(long curTime);

    // The part of a step duration that is the actual time.
    public static final long STEP_LEVEL_TIME_MASK = 0x000000ffffffffffL;

    // Bits in a step duration that are the new battery level we are at.
    public static final long STEP_LEVEL_LEVEL_MASK = 0x0000ff0000000000L;
    public static final int STEP_LEVEL_LEVEL_SHIFT = 40;

    // Bits in a step duration that are the initial mode we were in at that step.
    public static final long STEP_LEVEL_INITIAL_MODE_MASK = 0x00ff000000000000L;
    public static final int STEP_LEVEL_INITIAL_MODE_SHIFT = 48;

    // Bits in a step duration that indicate which modes changed during that step.
    public static final long STEP_LEVEL_MODIFIED_MODE_MASK = 0xff00000000000000L;
    public static final int STEP_LEVEL_MODIFIED_MODE_SHIFT = 56;

    // Step duration mode: the screen is on, off, dozed, etc; value is Display.STATE_* - 1.
    public static final int STEP_LEVEL_MODE_SCREEN_STATE = 0x03;

    // The largest value for screen state that is tracked in battery states. Any values above
    // this should be mapped back to one of the tracked values before being tracked here.
    public static final int MAX_TRACKED_SCREEN_STATE = Display.STATE_DOZE_SUSPEND;

    // Step duration mode: power save is on.
    public static final int STEP_LEVEL_MODE_POWER_SAVE = 0x04;

    // Step duration mode: device is currently in idle mode.
    public static final int STEP_LEVEL_MODE_DEVICE_IDLE = 0x08;

    public static final int[] STEP_LEVEL_MODES_OF_INTEREST = new int[] {
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE|STEP_LEVEL_MODE_DEVICE_IDLE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_DEVICE_IDLE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_POWER_SAVE|STEP_LEVEL_MODE_DEVICE_IDLE,
            STEP_LEVEL_MODE_SCREEN_STATE|STEP_LEVEL_MODE_DEVICE_IDLE,
    };
    public static final int[] STEP_LEVEL_MODE_VALUES = new int[] {
            (Display.STATE_OFF-1),
            (Display.STATE_OFF-1)|STEP_LEVEL_MODE_POWER_SAVE,
            (Display.STATE_OFF-1)|STEP_LEVEL_MODE_DEVICE_IDLE,
            (Display.STATE_ON-1),
            (Display.STATE_ON-1)|STEP_LEVEL_MODE_POWER_SAVE,
            (Display.STATE_DOZE-1),
            (Display.STATE_DOZE-1)|STEP_LEVEL_MODE_POWER_SAVE,
            (Display.STATE_DOZE_SUSPEND-1),
            (Display.STATE_DOZE_SUSPEND-1)|STEP_LEVEL_MODE_POWER_SAVE,
            (Display.STATE_DOZE_SUSPEND-1)|STEP_LEVEL_MODE_DEVICE_IDLE,
    };
    public static final String[] STEP_LEVEL_MODE_LABELS = new String[] {
            "screen off",
            "screen off power save",
            "screen off device idle",
            "screen on",
            "screen on power save",
            "screen doze",
            "screen doze power save",
            "screen doze-suspend",
            "screen doze-suspend power save",
            "screen doze-suspend device idle",
    };

    /**
     * Return the amount of battery discharge while the screen was off, measured in
     * micro-Ampere-hours. This will be non-zero only if the device's battery has
     * a coulomb counter.
     */
    public abstract long getUahDischargeScreenOff(int which);

    /**
     * Return the amount of battery discharge while the screen was in doze mode, measured in
     * micro-Ampere-hours. This will be non-zero only if the device's battery has
     * a coulomb counter.
     */
    public abstract long getUahDischargeScreenDoze(int which);

    /**
     * Return the amount of battery discharge  measured in micro-Ampere-hours. This will be
     * non-zero only if the device's battery has a coulomb counter.
     */
    public abstract long getUahDischarge(int which);

    /**
     * @return the amount of battery discharge while the device is in light idle mode, measured in
     * micro-Ampere-hours.
     */
    public abstract long getUahDischargeLightDoze(int which);

    /**
     * @return the amount of battery discharge while the device is in deep idle mode, measured in
     * micro-Ampere-hours.
     */
    public abstract long getUahDischargeDeepDoze(int which);

    /**
     * Returns the estimated real battery capacity, which may be less than the capacity
     * declared by the PowerProfile.
     * @return The estimated battery capacity in mAh.
     */
    public abstract int getEstimatedBatteryCapacity();

    /**
     * @return The minimum learned battery capacity in uAh.
     */
    public abstract int getMinLearnedBatteryCapacity();

    /**
     * @return The maximum learned battery capacity in uAh.
     */
    public abstract int getMaxLearnedBatteryCapacity() ;

    /**
     * Return the array of discharge step durations.
     */
    public abstract LevelStepTracker getDischargeLevelStepTracker();

    /**
     * Return the array of daily discharge step durations.
     */
    public abstract LevelStepTracker getDailyDischargeLevelStepTracker();

    /**
     * Compute an approximation for how much time (in microseconds) remains until the battery
     * is fully charged.  Returns -1 if no time can be computed: either there is not
     * enough current data to make a decision, or the battery is currently
     * discharging.
     *
     * @param curTime The current elepsed realtime in microseconds.
     */
    @UnsupportedAppUsage
    public abstract long computeChargeTimeRemaining(long curTime);

    /**
     * Return the array of charge step durations.
     */
    public abstract LevelStepTracker getChargeLevelStepTracker();

    /**
     * Return the array of daily charge step durations.
     */
    public abstract LevelStepTracker getDailyChargeLevelStepTracker();

    public abstract ArrayList<PackageChange> getDailyPackageChanges();

    public abstract Map<String, ? extends Timer> getWakeupReasonStats();

    public abstract Map<String, ? extends Timer> getKernelWakelockStats();

    /**
     * Returns Timers tracking the total time of each Resource Power Manager state and voter.
     */
    public abstract Map<String, ? extends Timer> getRpmStats();
    /**
     * Returns Timers tracking the screen-off time of each Resource Power Manager state and voter.
     */
    public abstract Map<String, ? extends Timer> getScreenOffRpmStats();


    public abstract LongSparseArray<? extends Timer> getKernelMemoryStats();

    public abstract void writeToParcelWithoutUids(Parcel out, int flags);

    private final static void formatTimeRaw(StringBuilder out, long seconds) {
        long days = seconds / (60 * 60 * 24);
        if (days != 0) {
            out.append(days);
            out.append("d ");
        }
        long used = days * 60 * 60 * 24;

        long hours = (seconds - used) / (60 * 60);
        if (hours != 0 || used != 0) {
            out.append(hours);
            out.append("h ");
        }
        used += hours * 60 * 60;

        long mins = (seconds-used) / 60;
        if (mins != 0 || used != 0) {
            out.append(mins);
            out.append("m ");
        }
        used += mins * 60;

        if (seconds != 0 || used != 0) {
            out.append(seconds-used);
            out.append("s ");
        }
    }

    public final static void formatTimeMs(StringBuilder sb, long time) {
        long sec = time / 1000;
        formatTimeRaw(sb, sec);
        sb.append(time - (sec * 1000));
        sb.append("ms ");
    }

    public final static void formatTimeMsNoSpace(StringBuilder sb, long time) {
        long sec = time / 1000;
        formatTimeRaw(sb, sec);
        sb.append(time - (sec * 1000));
        sb.append("ms");
    }

    public final String formatRatioLocked(long num, long den) {
        if (den == 0L) {
            return "--%";
        }
        float perc = ((float)num) / ((float)den) * 100;
        mFormatBuilder.setLength(0);
        mFormatter.format("%.1f%%", perc);
        return mFormatBuilder.toString();
    }

    final String formatBytesLocked(long bytes) {
        mFormatBuilder.setLength(0);

        if (bytes < BYTES_PER_KB) {
            return bytes + "B";
        } else if (bytes < BYTES_PER_MB) {
            mFormatter.format("%.2fKB", bytes / (double) BYTES_PER_KB);
            return mFormatBuilder.toString();
        } else if (bytes < BYTES_PER_GB){
            mFormatter.format("%.2fMB", bytes / (double) BYTES_PER_MB);
            return mFormatBuilder.toString();
        } else {
            mFormatter.format("%.2fGB", bytes / (double) BYTES_PER_GB);
            return mFormatBuilder.toString();
        }
    }

    private static long roundUsToMs(long timeUs) {
        return (timeUs + 500) / 1000;
    }

    private static long computeWakeLock(Timer timer, long elapsedRealtimeUs, int which) {
        if (timer != null) {
            // Convert from microseconds to milliseconds with rounding
            long totalTimeMicros = timer.getTotalTimeLocked(elapsedRealtimeUs, which);
            long totalTimeMillis = (totalTimeMicros + 500) / 1000;
            return totalTimeMillis;
        }
        return 0;
    }

    /**
     *
     * @param sb a StringBuilder object.
     * @param timer a Timer object contining the wakelock times.
     * @param elapsedRealtimeUs the current on-battery time in microseconds.
     * @param name the name of the wakelock.
     * @param which which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     * @param linePrefix a String to be prepended to each line of output.
     * @return the line prefix
     */
    private static final String printWakeLock(StringBuilder sb, Timer timer,
            long elapsedRealtimeUs, String name, int which, String linePrefix) {

        if (timer != null) {
            long totalTimeMillis = computeWakeLock(timer, elapsedRealtimeUs, which);

            int count = timer.getCountLocked(which);
            if (totalTimeMillis != 0) {
                sb.append(linePrefix);
                formatTimeMs(sb, totalTimeMillis);
                if (name != null) {
                    sb.append(name);
                    sb.append(' ');
                }
                sb.append('(');
                sb.append(count);
                sb.append(" times)");
                final long maxDurationMs = timer.getMaxDurationMsLocked(elapsedRealtimeUs/1000);
                if (maxDurationMs >= 0) {
                    sb.append(" max=");
                    sb.append(maxDurationMs);
                }
                // Put actual time if it is available and different from totalTimeMillis.
                final long totalDurMs = timer.getTotalDurationMsLocked(elapsedRealtimeUs/1000);
                if (totalDurMs > totalTimeMillis) {
                    sb.append(" actual=");
                    sb.append(totalDurMs);
                }
                if (timer.isRunningLocked()) {
                    final long currentMs = timer.getCurrentDurationMsLocked(elapsedRealtimeUs/1000);
                    if (currentMs >= 0) {
                        sb.append(" (running for ");
                        sb.append(currentMs);
                        sb.append("ms)");
                    } else {
                        sb.append(" (running)");
                    }
                }

                return ", ";
            }
        }
        return linePrefix;
    }

    /**
     * Prints details about a timer, if its total time was greater than 0.
     *
     * @param pw a PrintWriter object to print to.
     * @param sb a StringBuilder object.
     * @param timer a Timer object contining the wakelock times.
     * @param rawRealtimeUs the current on-battery time in microseconds.
     * @param which which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     * @param prefix a String to be prepended to each line of output.
     * @param type the name of the timer.
     * @return true if anything was printed.
     */
    private static final boolean printTimer(PrintWriter pw, StringBuilder sb, Timer timer,
            long rawRealtimeUs, int which, String prefix, String type) {
        if (timer != null) {
            // Convert from microseconds to milliseconds with rounding
            final long totalTimeMs = (timer.getTotalTimeLocked(
                    rawRealtimeUs, which) + 500) / 1000;
            final int count = timer.getCountLocked(which);
            if (totalTimeMs != 0) {
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    ");
                sb.append(type);
                sb.append(": ");
                formatTimeMs(sb, totalTimeMs);
                sb.append("realtime (");
                sb.append(count);
                sb.append(" times)");
                final long maxDurationMs = timer.getMaxDurationMsLocked(rawRealtimeUs/1000);
                if (maxDurationMs >= 0) {
                    sb.append(" max=");
                    sb.append(maxDurationMs);
                }
                if (timer.isRunningLocked()) {
                    final long currentMs = timer.getCurrentDurationMsLocked(rawRealtimeUs/1000);
                    if (currentMs >= 0) {
                        sb.append(" (running for ");
                        sb.append(currentMs);
                        sb.append("ms)");
                    } else {
                        sb.append(" (running)");
                    }
                }
                pw.println(sb.toString());
                return true;
            }
        }
        return false;
    }

    /**
     * Checkin version of wakelock printer. Prints simple comma-separated list.
     *
     * @param sb a StringBuilder object.
     * @param timer a Timer object contining the wakelock times.
     * @param elapsedRealtimeUs the current time in microseconds.
     * @param name the name of the wakelock.
     * @param which which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     * @param linePrefix a String to be prepended to each line of output.
     * @return the line prefix
     */
    private static final String printWakeLockCheckin(StringBuilder sb, Timer timer,
            long elapsedRealtimeUs, String name, int which, String linePrefix) {
        long totalTimeMicros = 0;
        int count = 0;
        long max = 0;
        long current = 0;
        long totalDuration = 0;
        if (timer != null) {
            totalTimeMicros = timer.getTotalTimeLocked(elapsedRealtimeUs, which);
            count = timer.getCountLocked(which);
            current = timer.getCurrentDurationMsLocked(elapsedRealtimeUs/1000);
            max = timer.getMaxDurationMsLocked(elapsedRealtimeUs/1000);
            totalDuration = timer.getTotalDurationMsLocked(elapsedRealtimeUs/1000);
        }
        sb.append(linePrefix);
        sb.append((totalTimeMicros + 500) / 1000); // microseconds to milliseconds with rounding
        sb.append(',');
        sb.append(name != null ? name + "," : "");
        sb.append(count);
        sb.append(',');
        sb.append(current);
        sb.append(',');
        sb.append(max);
        // Partial, full, and window wakelocks are pooled, so totalDuration is meaningful (albeit
        // not always tracked). Kernel wakelocks (which have name == null) have no notion of
        // totalDuration independent of totalTimeMicros (since they are not pooled).
        if (name != null) {
            sb.append(',');
            sb.append(totalDuration);
        }
        return ",";
    }

    private static final void dumpLineHeader(PrintWriter pw, int uid, String category,
                                             String type) {
        pw.print(BATTERY_STATS_CHECKIN_VERSION);
        pw.print(',');
        pw.print(uid);
        pw.print(',');
        pw.print(category);
        pw.print(',');
        pw.print(type);
    }

    /**
     * Dump a comma-separated line of values for terse checkin mode.
     *
     * @param pw the PageWriter to dump log to
     * @param category category of data (e.g. "total", "last", "unplugged", "current" )
     * @param type type of data (e.g. "wakelock", "sensor", "process", "apk" ,  "process", "network")
     * @param args type-dependent data arguments
     */
    @UnsupportedAppUsage
    private static final void dumpLine(PrintWriter pw, int uid, String category, String type,
           Object... args ) {
        dumpLineHeader(pw, uid, category, type);
        for (Object arg : args) {
            pw.print(',');
            pw.print(arg);
        }
        pw.println();
    }

    /**
     * Dump a given timer stat for terse checkin mode.
     *
     * @param pw the PageWriter to dump log to
     * @param uid the UID to log
     * @param category category of data (e.g. "total", "last", "unplugged", "current" )
     * @param type type of data (e.g. "wakelock", "sensor", "process", "apk" ,  "process", "network")
     * @param timer a {@link Timer} to dump stats for
     * @param rawRealtime the current elapsed realtime of the system in microseconds
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
     */
    private static final void dumpTimer(PrintWriter pw, int uid, String category, String type,
                                        Timer timer, long rawRealtime, int which) {
        if (timer != null) {
            // Convert from microseconds to milliseconds with rounding
            final long totalTime = roundUsToMs(timer.getTotalTimeLocked(rawRealtime, which));
            final int count = timer.getCountLocked(which);
            if (totalTime != 0 || count != 0) {
                dumpLine(pw, uid, category, type, totalTime, count);
            }
        }
    }

    /**
     * Dump a given timer stat to the proto stream.
     *
     * @param proto the ProtoOutputStream to log to
     * @param fieldId type of data, the field to save to (e.g. AggregatedBatteryStats.WAKELOCK)
     * @param timer a {@link Timer} to dump stats for
     * @param rawRealtimeUs the current elapsed realtime of the system in microseconds
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
     */
    private static void dumpTimer(ProtoOutputStream proto, long fieldId,
                                        Timer timer, long rawRealtimeUs, int which) {
        if (timer == null) {
            return;
        }
        // Convert from microseconds to milliseconds with rounding
        final long timeMs = roundUsToMs(timer.getTotalTimeLocked(rawRealtimeUs, which));
        final int count = timer.getCountLocked(which);
        final long maxDurationMs = timer.getMaxDurationMsLocked(rawRealtimeUs / 1000);
        final long curDurationMs = timer.getCurrentDurationMsLocked(rawRealtimeUs / 1000);
        final long totalDurationMs = timer.getTotalDurationMsLocked(rawRealtimeUs / 1000);
        if (timeMs != 0 || count != 0 || maxDurationMs != -1 || curDurationMs != -1
                || totalDurationMs != -1) {
            final long token = proto.start(fieldId);
            proto.write(TimerProto.DURATION_MS, timeMs);
            proto.write(TimerProto.COUNT, count);
            // These values will be -1 for timers that don't implement the functionality.
            if (maxDurationMs != -1) {
                proto.write(TimerProto.MAX_DURATION_MS, maxDurationMs);
            }
            if (curDurationMs != -1) {
                proto.write(TimerProto.CURRENT_DURATION_MS, curDurationMs);
            }
            if (totalDurationMs != -1) {
                proto.write(TimerProto.TOTAL_DURATION_MS, totalDurationMs);
            }
            proto.end(token);
        }
    }

    /**
     * Checks if the ControllerActivityCounter has any data worth dumping.
     */
    private static boolean controllerActivityHasData(ControllerActivityCounter counter, int which) {
        if (counter == null) {
            return false;
        }

        if (counter.getIdleTimeCounter().getCountLocked(which) != 0
                || counter.getRxTimeCounter().getCountLocked(which) != 0
                || counter.getPowerCounter().getCountLocked(which) != 0
                || counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which) != 0) {
            return true;
        }

        for (LongCounter c : counter.getTxTimeCounters()) {
            if (c.getCountLocked(which) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dumps the ControllerActivityCounter if it has any data worth dumping.
     * The order of the arguments in the final check in line is:
     *
     * idle, rx, power, tx...
     *
     * where tx... is one or more transmit level times.
     */
    private static final void dumpControllerActivityLine(PrintWriter pw, int uid, String category,
                                                         String type,
                                                         ControllerActivityCounter counter,
                                                         int which) {
        if (!controllerActivityHasData(counter, which)) {
            return;
        }

        dumpLineHeader(pw, uid, category, type);
        pw.print(",");
        pw.print(counter.getIdleTimeCounter().getCountLocked(which));
        pw.print(",");
        pw.print(counter.getRxTimeCounter().getCountLocked(which));
        pw.print(",");
        pw.print(counter.getPowerCounter().getCountLocked(which) / (MILLISECONDS_IN_HOUR));
        pw.print(",");
        pw.print(counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which)
                / (MILLISECONDS_IN_HOUR));
        for (LongCounter c : counter.getTxTimeCounters()) {
            pw.print(",");
            pw.print(c.getCountLocked(which));
        }
        pw.println();
    }

    /**
     * Dumps the ControllerActivityCounter if it has any data worth dumping.
     */
    private static void dumpControllerActivityProto(ProtoOutputStream proto, long fieldId,
                                                    ControllerActivityCounter counter,
                                                    int which) {
        if (!controllerActivityHasData(counter, which)) {
            return;
        }

        final long cToken = proto.start(fieldId);

        proto.write(ControllerActivityProto.IDLE_DURATION_MS,
                counter.getIdleTimeCounter().getCountLocked(which));
        proto.write(ControllerActivityProto.RX_DURATION_MS,
                counter.getRxTimeCounter().getCountLocked(which));
        proto.write(ControllerActivityProto.POWER_MAH,
                counter.getPowerCounter().getCountLocked(which) / (MILLISECONDS_IN_HOUR));
        proto.write(ControllerActivityProto.MONITORED_RAIL_CHARGE_MAH,
                counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which)
                        / (MILLISECONDS_IN_HOUR));

        long tToken;
        LongCounter[] txCounters = counter.getTxTimeCounters();
        for (int i = 0; i < txCounters.length; ++i) {
            LongCounter c = txCounters[i];
            tToken = proto.start(ControllerActivityProto.TX);
            proto.write(ControllerActivityProto.TxLevel.LEVEL, i);
            proto.write(ControllerActivityProto.TxLevel.DURATION_MS, c.getCountLocked(which));
            proto.end(tToken);
        }

        proto.end(cToken);
    }

    private final void printControllerActivityIfInteresting(PrintWriter pw, StringBuilder sb,
                                                            String prefix, String controllerName,
                                                            ControllerActivityCounter counter,
                                                            int which) {
        if (controllerActivityHasData(counter, which)) {
            printControllerActivity(pw, sb, prefix, controllerName, counter, which);
        }
    }

    private final void printControllerActivity(PrintWriter pw, StringBuilder sb, String prefix,
                                               String controllerName,
                                               ControllerActivityCounter counter, int which) {
        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(which);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(which);
        final long powerDrainMaMs = counter.getPowerCounter().getCountLocked(which);
        final long monitoredRailChargeConsumedMaMs =
                counter.getMonitoredRailChargeConsumedMaMs().getCountLocked(which);
        // Battery real time
        final long totalControllerActivityTimeMs
            = computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, which) / 1000;
        long totalTxTimeMs = 0;
        for (LongCounter txState : counter.getTxTimeCounters()) {
            totalTxTimeMs += txState.getCountLocked(which);
        }

        if (controllerName.equals(WIFI_CONTROLLER_NAME)) {
            final long scanTimeMs = counter.getScanTimeCounter().getCountLocked(which);
            sb.setLength(0);
            sb.append(prefix);
            sb.append("     ");
            sb.append(controllerName);
            sb.append(" Scan time:  ");
            formatTimeMs(sb, scanTimeMs);
            sb.append("(");
            sb.append(formatRatioLocked(scanTimeMs, totalControllerActivityTimeMs));
            sb.append(")");
            pw.println(sb.toString());

            final long sleepTimeMs
                = totalControllerActivityTimeMs - (idleTimeMs + rxTimeMs + totalTxTimeMs);
            sb.setLength(0);
            sb.append(prefix);
            sb.append("     ");
            sb.append(controllerName);
            sb.append(" Sleep time:  ");
            formatTimeMs(sb, sleepTimeMs);
            sb.append("(");
            sb.append(formatRatioLocked(sleepTimeMs, totalControllerActivityTimeMs));
            sb.append(")");
            pw.println(sb.toString());
        }

        if (controllerName.equals(CELLULAR_CONTROLLER_NAME)) {
            final long sleepTimeMs = counter.getSleepTimeCounter().getCountLocked(which);
            sb.setLength(0);
            sb.append(prefix);
            sb.append("     ");
            sb.append(controllerName);
            sb.append(" Sleep time:  ");
            formatTimeMs(sb, sleepTimeMs);
            sb.append("(");
            sb.append(formatRatioLocked(sleepTimeMs, totalControllerActivityTimeMs));
            sb.append(")");
            pw.println(sb.toString());
        }

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     ");
        sb.append(controllerName);
        sb.append(" Idle time:   ");
        formatTimeMs(sb, idleTimeMs);
        sb.append("(");
        sb.append(formatRatioLocked(idleTimeMs, totalControllerActivityTimeMs));
        sb.append(")");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     ");
        sb.append(controllerName);
        sb.append(" Rx time:     ");
        formatTimeMs(sb, rxTimeMs);
        sb.append("(");
        sb.append(formatRatioLocked(rxTimeMs, totalControllerActivityTimeMs));
        sb.append(")");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     ");
        sb.append(controllerName);
        sb.append(" Tx time:     ");

        String [] powerLevel;
        switch(controllerName) {
            case CELLULAR_CONTROLLER_NAME:
                powerLevel = new String[] {
                    "   less than 0dBm: ",
                    "   0dBm to 8dBm: ",
                    "   8dBm to 15dBm: ",
                    "   15dBm to 20dBm: ",
                    "   above 20dBm: "};
                break;
            default:
                powerLevel = new String[] {"[0]", "[1]", "[2]", "[3]", "[4]"};
                break;
        }
        final int numTxLvls = Math.min(counter.getTxTimeCounters().length, powerLevel.length);
        if (numTxLvls > 1) {
            pw.println(sb.toString());
            for (int lvl = 0; lvl < numTxLvls; lvl++) {
                final long txLvlTimeMs = counter.getTxTimeCounters()[lvl].getCountLocked(which);
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    ");
                sb.append(powerLevel[lvl]);
                sb.append(" ");
                formatTimeMs(sb, txLvlTimeMs);
                sb.append("(");
                sb.append(formatRatioLocked(txLvlTimeMs, totalControllerActivityTimeMs));
                sb.append(")");
                pw.println(sb.toString());
            }
        } else {
            final long txLvlTimeMs = counter.getTxTimeCounters()[0].getCountLocked(which);
            formatTimeMs(sb, txLvlTimeMs);
            sb.append("(");
            sb.append(formatRatioLocked(txLvlTimeMs, totalControllerActivityTimeMs));
            sb.append(")");
            pw.println(sb.toString());
        }

        if (powerDrainMaMs > 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("     ");
            sb.append(controllerName);
            sb.append(" Battery drain: ").append(
                    BatteryStatsHelper.makemAh(powerDrainMaMs / MILLISECONDS_IN_HOUR));
            sb.append("mAh");
            pw.println(sb.toString());
        }

        if (monitoredRailChargeConsumedMaMs > 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("     ");
            sb.append(controllerName);
            sb.append(" Monitored rail energy drain: ").append(
                    new DecimalFormat("#.##").format(
                            monitoredRailChargeConsumedMaMs / MILLISECONDS_IN_HOUR));
            sb.append(" mAh");
            pw.println(sb.toString());
        }
    }

    /**
     * Temporary for settings.
     */
    public final void dumpCheckinLocked(Context context, PrintWriter pw, int which, int reqUid) {
        dumpCheckinLocked(context, pw, which, reqUid, BatteryStatsHelper.checkWifiOnly(context));
    }

    /**
     * Checkin server version of dump to produce more compact, computer-readable log.
     *
     * NOTE: all times are expressed in microseconds, unless specified otherwise.
     */
    public final void dumpCheckinLocked(Context context, PrintWriter pw, int which, int reqUid,
            boolean wifiOnly) {

        if (which != BatteryStats.STATS_SINCE_CHARGED) {
            dumpLine(pw, 0, STAT_NAMES[which], "err",
                    "ERROR: BatteryStats.dumpCheckin called for which type " + which
                    + " but only STATS_SINCE_CHARGED is supported.");
            return;
        }

        final long rawUptime = SystemClock.uptimeMillis() * 1000;
        final long rawRealtimeMs = SystemClock.elapsedRealtime();
        final long rawRealtime = rawRealtimeMs * 1000;
        final long batteryUptime = getBatteryUptime(rawUptime);
        final long whichBatteryUptime = computeBatteryUptime(rawUptime, which);
        final long whichBatteryRealtime = computeBatteryRealtime(rawRealtime, which);
        final long whichBatteryScreenOffUptime = computeBatteryScreenOffUptime(rawUptime, which);
        final long whichBatteryScreenOffRealtime = computeBatteryScreenOffRealtime(rawRealtime,
                which);
        final long totalRealtime = computeRealtime(rawRealtime, which);
        final long totalUptime = computeUptime(rawUptime, which);
        final long screenOnTime = getScreenOnTime(rawRealtime, which);
        final long screenDozeTime = getScreenDozeTime(rawRealtime, which);
        final long interactiveTime = getInteractiveTime(rawRealtime, which);
        final long powerSaveModeEnabledTime = getPowerSaveModeEnabledTime(rawRealtime, which);
        final long deviceIdleModeLightTime = getDeviceIdleModeTime(DEVICE_IDLE_MODE_LIGHT,
                rawRealtime, which);
        final long deviceIdleModeFullTime = getDeviceIdleModeTime(DEVICE_IDLE_MODE_DEEP,
                rawRealtime, which);
        final long deviceLightIdlingTime = getDeviceIdlingTime(DEVICE_IDLE_MODE_LIGHT,
                rawRealtime, which);
        final long deviceIdlingTime = getDeviceIdlingTime(DEVICE_IDLE_MODE_DEEP,
                rawRealtime, which);
        final int connChanges = getNumConnectivityChange(which);
        final long phoneOnTime = getPhoneOnTime(rawRealtime, which);
        final long dischargeCount = getUahDischarge(which);
        final long dischargeScreenOffCount = getUahDischargeScreenOff(which);
        final long dischargeScreenDozeCount = getUahDischargeScreenDoze(which);
        final long dischargeLightDozeCount = getUahDischargeLightDoze(which);
        final long dischargeDeepDozeCount = getUahDischargeDeepDoze(which);

        final StringBuilder sb = new StringBuilder(128);

        final SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();

        final String category = STAT_NAMES[which];

        // Dump "battery" stat
        dumpLine(pw, 0 /* uid */, category, BATTERY_DATA,
                which == STATS_SINCE_CHARGED ? getStartCount() : "N/A",
                whichBatteryRealtime / 1000, whichBatteryUptime / 1000,
                totalRealtime / 1000, totalUptime / 1000,
                getStartClockTime(),
                whichBatteryScreenOffRealtime / 1000, whichBatteryScreenOffUptime / 1000,
                getEstimatedBatteryCapacity(),
                getMinLearnedBatteryCapacity(), getMaxLearnedBatteryCapacity(),
                screenDozeTime / 1000);


        // Calculate wakelock times across all uids.
        long fullWakeLockTimeTotal = 0;
        long partialWakeLockTimeTotal = 0;

        for (int iu = 0; iu < NU; iu++) {
            final Uid u = uidStats.valueAt(iu);

            final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelocks
                    = u.getWakelockStats();
            for (int iw=wakelocks.size()-1; iw>=0; iw--) {
                final Uid.Wakelock wl = wakelocks.valueAt(iw);

                final Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                if (fullWakeTimer != null) {
                    fullWakeLockTimeTotal += fullWakeTimer.getTotalTimeLocked(rawRealtime,
                            which);
                }

                final Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                if (partialWakeTimer != null) {
                    partialWakeLockTimeTotal += partialWakeTimer.getTotalTimeLocked(
                        rawRealtime, which);
                }
            }
        }

        // Dump network stats
        final long mobileRxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
        final long mobileTxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
        final long wifiRxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
        final long wifiTxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
        final long mobileRxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
        final long mobileTxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
        final long wifiRxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
        final long wifiTxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);
        final long btRxTotalBytes = getNetworkActivityBytes(NETWORK_BT_RX_DATA, which);
        final long btTxTotalBytes = getNetworkActivityBytes(NETWORK_BT_TX_DATA, which);
        dumpLine(pw, 0 /* uid */, category, GLOBAL_NETWORK_DATA,
                mobileRxTotalBytes, mobileTxTotalBytes, wifiRxTotalBytes, wifiTxTotalBytes,
                mobileRxTotalPackets, mobileTxTotalPackets, wifiRxTotalPackets, wifiTxTotalPackets,
                btRxTotalBytes, btTxTotalBytes);

        // Dump Modem controller stats
        dumpControllerActivityLine(pw, 0 /* uid */, category, GLOBAL_MODEM_CONTROLLER_DATA,
                getModemControllerActivity(), which);

        // Dump Wifi controller stats
        final long wifiOnTime = getWifiOnTime(rawRealtime, which);
        final long wifiRunningTime = getGlobalWifiRunningTime(rawRealtime, which);
        dumpLine(pw, 0 /* uid */, category, GLOBAL_WIFI_DATA, wifiOnTime / 1000,
                wifiRunningTime / 1000, /* legacy fields follow, keep at 0 */ 0, 0, 0);

        dumpControllerActivityLine(pw, 0 /* uid */, category, GLOBAL_WIFI_CONTROLLER_DATA,
                getWifiControllerActivity(), which);

        // Dump Bluetooth controller stats
        dumpControllerActivityLine(pw, 0 /* uid */, category, GLOBAL_BLUETOOTH_CONTROLLER_DATA,
                getBluetoothControllerActivity(), which);

        // Dump misc stats
        dumpLine(pw, 0 /* uid */, category, MISC_DATA,
                screenOnTime / 1000, phoneOnTime / 1000,
                fullWakeLockTimeTotal / 1000, partialWakeLockTimeTotal / 1000,
                getMobileRadioActiveTime(rawRealtime, which) / 1000,
                getMobileRadioActiveAdjustedTime(which) / 1000, interactiveTime / 1000,
                powerSaveModeEnabledTime / 1000, connChanges, deviceIdleModeFullTime / 1000,
                getDeviceIdleModeCount(DEVICE_IDLE_MODE_DEEP, which), deviceIdlingTime / 1000,
                getDeviceIdlingCount(DEVICE_IDLE_MODE_DEEP, which),
                getMobileRadioActiveCount(which),
                getMobileRadioActiveUnknownTime(which) / 1000, deviceIdleModeLightTime / 1000,
                getDeviceIdleModeCount(DEVICE_IDLE_MODE_LIGHT, which), deviceLightIdlingTime / 1000,
                getDeviceIdlingCount(DEVICE_IDLE_MODE_LIGHT, which),
                getLongestDeviceIdleModeTime(DEVICE_IDLE_MODE_LIGHT),
                getLongestDeviceIdleModeTime(DEVICE_IDLE_MODE_DEEP));

        // Dump screen brightness stats
        Object[] args = new Object[NUM_SCREEN_BRIGHTNESS_BINS];
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            args[i] = getScreenBrightnessTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SCREEN_BRIGHTNESS_DATA, args);

        // Dump signal strength stats
        args = new Object[CellSignalStrength.getNumSignalStrengthLevels()];
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            args[i] = getPhoneSignalStrengthTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SIGNAL_STRENGTH_TIME_DATA, args);
        dumpLine(pw, 0 /* uid */, category, SIGNAL_SCANNING_TIME_DATA,
                getPhoneSignalScanningTime(rawRealtime, which) / 1000);
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); i++) {
            args[i] = getPhoneSignalStrengthCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, SIGNAL_STRENGTH_COUNT_DATA, args);

        // Dump network type stats
        args = new Object[NUM_DATA_CONNECTION_TYPES];
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            args[i] = getPhoneDataConnectionTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, DATA_CONNECTION_TIME_DATA, args);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            args[i] = getPhoneDataConnectionCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, DATA_CONNECTION_COUNT_DATA, args);

        // Dump wifi state stats
        args = new Object[NUM_WIFI_STATES];
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            args[i] = getWifiStateTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, WIFI_STATE_TIME_DATA, args);
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            args[i] = getWifiStateCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, WIFI_STATE_COUNT_DATA, args);

        // Dump wifi suppl state stats
        args = new Object[NUM_WIFI_SUPPL_STATES];
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            args[i] = getWifiSupplStateTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, WIFI_SUPPL_STATE_TIME_DATA, args);
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            args[i] = getWifiSupplStateCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, WIFI_SUPPL_STATE_COUNT_DATA, args);

        // Dump wifi signal strength stats
        args = new Object[NUM_WIFI_SIGNAL_STRENGTH_BINS];
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            args[i] = getWifiSignalStrengthTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, WIFI_SIGNAL_STRENGTH_TIME_DATA, args);
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            args[i] = getWifiSignalStrengthCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, WIFI_SIGNAL_STRENGTH_COUNT_DATA, args);

        // Dump Multicast total stats
        final long multicastWakeLockTimeTotalMicros =
                getWifiMulticastWakelockTime(rawRealtime, which);
        final int multicastWakeLockCountTotal = getWifiMulticastWakelockCount(which);
        dumpLine(pw, 0 /* uid */, category, WIFI_MULTICAST_TOTAL_DATA,
                multicastWakeLockTimeTotalMicros / 1000,
                multicastWakeLockCountTotal);

        dumpLine(pw, 0 /* uid */, category, BATTERY_DISCHARGE_DATA,
                getLowDischargeAmountSinceCharge(), getHighDischargeAmountSinceCharge(),
                getDischargeAmountScreenOnSinceCharge(),
                getDischargeAmountScreenOffSinceCharge(),
                dischargeCount / 1000, dischargeScreenOffCount / 1000,
                getDischargeAmountScreenDozeSinceCharge(), dischargeScreenDozeCount / 1000,
                dischargeLightDozeCount / 1000, dischargeDeepDozeCount / 1000);

        if (reqUid < 0) {
            final Map<String, ? extends Timer> kernelWakelocks = getKernelWakelockStats();
            if (kernelWakelocks.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : kernelWakelocks.entrySet()) {
                    sb.setLength(0);
                    printWakeLockCheckin(sb, ent.getValue(), rawRealtime, null, which, "");
                    dumpLine(pw, 0 /* uid */, category, KERNEL_WAKELOCK_DATA,
                            "\"" + ent.getKey() + "\"", sb.toString());
                }
            }
            final Map<String, ? extends Timer> wakeupReasons = getWakeupReasonStats();
            if (wakeupReasons.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : wakeupReasons.entrySet()) {
                    // Not doing the regular wake lock formatting to remain compatible
                    // with the old checkin format.
                    long totalTimeMicros = ent.getValue().getTotalTimeLocked(rawRealtime, which);
                    int count = ent.getValue().getCountLocked(which);
                    dumpLine(pw, 0 /* uid */, category, WAKEUP_REASON_DATA,
                            "\"" + ent.getKey() + "\"", (totalTimeMicros + 500) / 1000, count);
                }
            }
        }

        final Map<String, ? extends Timer> rpmStats = getRpmStats();
        final Map<String, ? extends Timer> screenOffRpmStats = getScreenOffRpmStats();
        if (rpmStats.size() > 0) {
            for (Map.Entry<String, ? extends Timer> ent : rpmStats.entrySet()) {
                sb.setLength(0);
                Timer totalTimer = ent.getValue();
                long timeMs = (totalTimer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                int count = totalTimer.getCountLocked(which);
                Timer screenOffTimer = screenOffRpmStats.get(ent.getKey());
                long screenOffTimeMs = screenOffTimer != null
                        ? (screenOffTimer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000 : 0;
                int screenOffCount = screenOffTimer != null
                        ? screenOffTimer.getCountLocked(which) : 0;
                if (SCREEN_OFF_RPM_STATS_ENABLED) {
                    dumpLine(pw, 0 /* uid */, category, RESOURCE_POWER_MANAGER_DATA,
                            "\"" + ent.getKey() + "\"", timeMs, count, screenOffTimeMs,
                            screenOffCount);
                } else {
                    dumpLine(pw, 0 /* uid */, category, RESOURCE_POWER_MANAGER_DATA,
                            "\"" + ent.getKey() + "\"", timeMs, count);
                }
            }
        }

        final BatteryStatsHelper helper = new BatteryStatsHelper(context, false, wifiOnly);
        helper.create(this);
        helper.refreshStats(which, UserHandle.USER_ALL);
        final List<BatterySipper> sippers = helper.getUsageList();
        if (sippers != null && sippers.size() > 0) {
            dumpLine(pw, 0 /* uid */, category, POWER_USE_SUMMARY_DATA,
                    BatteryStatsHelper.makemAh(helper.getPowerProfile().getBatteryCapacity()),
                    BatteryStatsHelper.makemAh(helper.getComputedPower()),
                    BatteryStatsHelper.makemAh(helper.getMinDrainedPower()),
                    BatteryStatsHelper.makemAh(helper.getMaxDrainedPower()));
            int uid = 0;
            for (int i=0; i<sippers.size(); i++) {
                final BatterySipper bs = sippers.get(i);
                String label;
                switch (bs.drainType) {
                    case AMBIENT_DISPLAY:
                        label = "ambi";
                        break;
                    case IDLE:
                        label="idle";
                        break;
                    case CELL:
                        label="cell";
                        break;
                    case PHONE:
                        label="phone";
                        break;
                    case WIFI:
                        label="wifi";
                        break;
                    case BLUETOOTH:
                        label="blue";
                        break;
                    case SCREEN:
                        label="scrn";
                        break;
                    case FLASHLIGHT:
                        label="flashlight";
                        break;
                    case APP:
                        uid = bs.uidObj.getUid();
                        label = "uid";
                        break;
                    case USER:
                        uid = UserHandle.getUid(bs.userId, 0);
                        label = "user";
                        break;
                    case UNACCOUNTED:
                        label = "unacc";
                        break;
                    case OVERCOUNTED:
                        label = "over";
                        break;
                    case CAMERA:
                        label = "camera";
                        break;
                    case MEMORY:
                        label = "memory";
                        break;
                    default:
                        label = "???";
                }
                dumpLine(pw, uid, category, POWER_USE_ITEM_DATA, label,
                        BatteryStatsHelper.makemAh(bs.totalPowerMah),
                        bs.shouldHide ? 1 : 0,
                        BatteryStatsHelper.makemAh(bs.screenPowerMah),
                        BatteryStatsHelper.makemAh(bs.proportionalSmearMah));
            }
        }

        final long[] cpuFreqs = getCpuFreqs();
        if (cpuFreqs != null) {
            sb.setLength(0);
            for (int i = 0; i < cpuFreqs.length; ++i) {
                if (i != 0) sb.append(',');
                sb.append(cpuFreqs[i]);
            }
            dumpLine(pw, 0 /* uid */, category, GLOBAL_CPU_FREQ_DATA, sb.toString());
        }

        // Dump stats per UID.
        for (int iu = 0; iu < NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            if (reqUid >= 0 && uid != reqUid) {
                continue;
            }
            final Uid u = uidStats.valueAt(iu);

            // Dump Network stats per uid, if any
            final long mobileBytesRx = u.getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
            final long mobileBytesTx = u.getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
            final long wifiBytesRx = u.getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
            final long wifiBytesTx = u.getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
            final long mobilePacketsRx = u.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
            final long mobilePacketsTx = u.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
            final long mobileActiveTime = u.getMobileRadioActiveTime(which);
            final int mobileActiveCount = u.getMobileRadioActiveCount(which);
            final long mobileWakeup = u.getMobileRadioApWakeupCount(which);
            final long wifiPacketsRx = u.getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
            final long wifiPacketsTx = u.getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);
            final long wifiWakeup = u.getWifiRadioApWakeupCount(which);
            final long btBytesRx = u.getNetworkActivityBytes(NETWORK_BT_RX_DATA, which);
            final long btBytesTx = u.getNetworkActivityBytes(NETWORK_BT_TX_DATA, which);
            // Background data transfers
            final long mobileBytesBgRx = u.getNetworkActivityBytes(NETWORK_MOBILE_BG_RX_DATA,
                    which);
            final long mobileBytesBgTx = u.getNetworkActivityBytes(NETWORK_MOBILE_BG_TX_DATA,
                    which);
            final long wifiBytesBgRx = u.getNetworkActivityBytes(NETWORK_WIFI_BG_RX_DATA, which);
            final long wifiBytesBgTx = u.getNetworkActivityBytes(NETWORK_WIFI_BG_TX_DATA, which);
            final long mobilePacketsBgRx = u.getNetworkActivityPackets(NETWORK_MOBILE_BG_RX_DATA,
                    which);
            final long mobilePacketsBgTx = u.getNetworkActivityPackets(NETWORK_MOBILE_BG_TX_DATA,
                    which);
            final long wifiPacketsBgRx = u.getNetworkActivityPackets(NETWORK_WIFI_BG_RX_DATA,
                    which);
            final long wifiPacketsBgTx = u.getNetworkActivityPackets(NETWORK_WIFI_BG_TX_DATA,
                    which);

            if (mobileBytesRx > 0 || mobileBytesTx > 0 || wifiBytesRx > 0 || wifiBytesTx > 0
                    || mobilePacketsRx > 0 || mobilePacketsTx > 0 || wifiPacketsRx > 0
                    || wifiPacketsTx > 0 || mobileActiveTime > 0 || mobileActiveCount > 0
                    || btBytesRx > 0 || btBytesTx > 0 || mobileWakeup > 0 || wifiWakeup > 0
                    || mobileBytesBgRx > 0 || mobileBytesBgTx > 0 || wifiBytesBgRx > 0
                    || wifiBytesBgTx > 0
                    || mobilePacketsBgRx > 0 || mobilePacketsBgTx > 0 || wifiPacketsBgRx > 0
                    || wifiPacketsBgTx > 0) {
                dumpLine(pw, uid, category, NETWORK_DATA, mobileBytesRx, mobileBytesTx,
                        wifiBytesRx, wifiBytesTx,
                        mobilePacketsRx, mobilePacketsTx,
                        wifiPacketsRx, wifiPacketsTx,
                        mobileActiveTime, mobileActiveCount,
                        btBytesRx, btBytesTx, mobileWakeup, wifiWakeup,
                        mobileBytesBgRx, mobileBytesBgTx, wifiBytesBgRx, wifiBytesBgTx,
                        mobilePacketsBgRx, mobilePacketsBgTx, wifiPacketsBgRx, wifiPacketsBgTx
                        );
            }

            // Dump modem controller data, per UID.
            dumpControllerActivityLine(pw, uid, category, MODEM_CONTROLLER_DATA,
                    u.getModemControllerActivity(), which);

            // Dump Wifi controller data, per UID.
            final long fullWifiLockOnTime = u.getFullWifiLockTime(rawRealtime, which);
            final long wifiScanTime = u.getWifiScanTime(rawRealtime, which);
            final int wifiScanCount = u.getWifiScanCount(which);
            final int wifiScanCountBg = u.getWifiScanBackgroundCount(which);
            // Note that 'ActualTime' are unpooled and always since reset (regardless of 'which')
            final long wifiScanActualTimeMs = (u.getWifiScanActualTime(rawRealtime) + 500) / 1000;
            final long wifiScanActualTimeMsBg = (u.getWifiScanBackgroundTime(rawRealtime) + 500)
                    / 1000;
            final long uidWifiRunningTime = u.getWifiRunningTime(rawRealtime, which);
            if (fullWifiLockOnTime != 0 || wifiScanTime != 0 || wifiScanCount != 0
                    || wifiScanCountBg != 0 || wifiScanActualTimeMs != 0
                    || wifiScanActualTimeMsBg != 0 || uidWifiRunningTime != 0) {
                dumpLine(pw, uid, category, WIFI_DATA, fullWifiLockOnTime, wifiScanTime,
                        uidWifiRunningTime, wifiScanCount,
                        /* legacy fields follow, keep at 0 */ 0, 0, 0,
                        wifiScanCountBg, wifiScanActualTimeMs, wifiScanActualTimeMsBg);
            }

            dumpControllerActivityLine(pw, uid, category, WIFI_CONTROLLER_DATA,
                    u.getWifiControllerActivity(), which);

            final Timer bleTimer = u.getBluetoothScanTimer();
            if (bleTimer != null) {
                // Convert from microseconds to milliseconds with rounding
                final long totalTime = (bleTimer.getTotalTimeLocked(rawRealtime, which) + 500)
                        / 1000;
                if (totalTime != 0) {
                    final int count = bleTimer.getCountLocked(which);
                    final Timer bleTimerBg = u.getBluetoothScanBackgroundTimer();
                    final int countBg = bleTimerBg != null ? bleTimerBg.getCountLocked(which) : 0;
                    // 'actualTime' are unpooled and always since reset (regardless of 'which')
                    final long actualTime = bleTimer.getTotalDurationMsLocked(rawRealtimeMs);
                    final long actualTimeBg = bleTimerBg != null ?
                            bleTimerBg.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                    // Result counters
                    final int resultCount = u.getBluetoothScanResultCounter() != null ?
                            u.getBluetoothScanResultCounter().getCountLocked(which) : 0;
                    final int resultCountBg = u.getBluetoothScanResultBgCounter() != null ?
                            u.getBluetoothScanResultBgCounter().getCountLocked(which) : 0;
                    // Unoptimized scan timer. Unpooled and since reset (regardless of 'which').
                    final Timer unoptimizedScanTimer = u.getBluetoothUnoptimizedScanTimer();
                    final long unoptimizedScanTotalTime = unoptimizedScanTimer != null ?
                            unoptimizedScanTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                    final long unoptimizedScanMaxTime = unoptimizedScanTimer != null ?
                            unoptimizedScanTimer.getMaxDurationMsLocked(rawRealtimeMs) : 0;
                    // Unoptimized bg scan timer. Unpooled and since reset (regardless of 'which').
                    final Timer unoptimizedScanTimerBg =
                            u.getBluetoothUnoptimizedScanBackgroundTimer();
                    final long unoptimizedScanTotalTimeBg = unoptimizedScanTimerBg != null ?
                            unoptimizedScanTimerBg.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                    final long unoptimizedScanMaxTimeBg = unoptimizedScanTimerBg != null ?
                            unoptimizedScanTimerBg.getMaxDurationMsLocked(rawRealtimeMs) : 0;

                    dumpLine(pw, uid, category, BLUETOOTH_MISC_DATA, totalTime, count,
                            countBg, actualTime, actualTimeBg, resultCount, resultCountBg,
                            unoptimizedScanTotalTime, unoptimizedScanTotalTimeBg,
                            unoptimizedScanMaxTime, unoptimizedScanMaxTimeBg);
                }
            }

            dumpControllerActivityLine(pw, uid, category, BLUETOOTH_CONTROLLER_DATA,
                    u.getBluetoothControllerActivity(), which);

            if (u.hasUserActivity()) {
                args = new Object[Uid.NUM_USER_ACTIVITY_TYPES];
                boolean hasData = false;
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    int val = u.getUserActivityCount(i, which);
                    args[i] = val;
                    if (val != 0) hasData = true;
                }
                if (hasData) {
                    dumpLine(pw, uid /* uid */, category, USER_ACTIVITY_DATA, args);
                }
            }

            if (u.getAggregatedPartialWakelockTimer() != null) {
                final Timer timer = u.getAggregatedPartialWakelockTimer();
                // Times are since reset (regardless of 'which')
                final long totTimeMs = timer.getTotalDurationMsLocked(rawRealtimeMs);
                final Timer bgTimer = timer.getSubTimer();
                final long bgTimeMs = bgTimer != null ?
                        bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                dumpLine(pw, uid, category, AGGREGATED_WAKELOCK_DATA, totTimeMs, bgTimeMs);
            }

            final ArrayMap<String, ? extends Uid.Wakelock> wakelocks = u.getWakelockStats();
            for (int iw=wakelocks.size()-1; iw>=0; iw--) {
                final Uid.Wakelock wl = wakelocks.valueAt(iw);
                String linePrefix = "";
                sb.setLength(0);
                linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_FULL),
                        rawRealtime, "f", which, linePrefix);
                final Timer pTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                linePrefix = printWakeLockCheckin(sb, pTimer,
                        rawRealtime, "p", which, linePrefix);
                linePrefix = printWakeLockCheckin(sb, pTimer != null ? pTimer.getSubTimer() : null,
                        rawRealtime, "bp", which, linePrefix);
                linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_WINDOW),
                        rawRealtime, "w", which, linePrefix);

                // Only log if we had at least one wakelock...
                if (sb.length() > 0) {
                    String name = wakelocks.keyAt(iw);
                    if (name.indexOf(',') >= 0) {
                        name = name.replace(',', '_');
                    }
                    if (name.indexOf('\n') >= 0) {
                        name = name.replace('\n', '_');
                    }
                    if (name.indexOf('\r') >= 0) {
                        name = name.replace('\r', '_');
                    }
                    dumpLine(pw, uid, category, WAKELOCK_DATA, name, sb.toString());
                }
            }

            // WiFi Multicast Wakelock Statistics
            final Timer mcTimer = u.getMulticastWakelockStats();
            if (mcTimer != null) {
                final long totalMcWakelockTimeMs =
                        mcTimer.getTotalTimeLocked(rawRealtime, which) / 1000 ;
                final int countMcWakelock = mcTimer.getCountLocked(which);
                if(totalMcWakelockTimeMs > 0) {
                    dumpLine(pw, uid, category, WIFI_MULTICAST_DATA,
                            totalMcWakelockTimeMs, countMcWakelock);
                }
            }

            final ArrayMap<String, ? extends Timer> syncs = u.getSyncStats();
            for (int isy=syncs.size()-1; isy>=0; isy--) {
                final Timer timer = syncs.valueAt(isy);
                // Convert from microseconds to milliseconds with rounding
                final long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                final int count = timer.getCountLocked(which);
                final Timer bgTimer = timer.getSubTimer();
                final long bgTime = bgTimer != null ?
                        bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : -1;
                final int bgCount = bgTimer != null ? bgTimer.getCountLocked(which) : -1;
                if (totalTime != 0) {
                    dumpLine(pw, uid, category, SYNC_DATA, "\"" + syncs.keyAt(isy) + "\"",
                            totalTime, count, bgTime, bgCount);
                }
            }

            final ArrayMap<String, ? extends Timer> jobs = u.getJobStats();
            for (int ij=jobs.size()-1; ij>=0; ij--) {
                final Timer timer = jobs.valueAt(ij);
                // Convert from microseconds to milliseconds with rounding
                final long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                final int count = timer.getCountLocked(which);
                final Timer bgTimer = timer.getSubTimer();
                final long bgTime = bgTimer != null ?
                        bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : -1;
                final int bgCount = bgTimer != null ? bgTimer.getCountLocked(which) : -1;
                if (totalTime != 0) {
                    dumpLine(pw, uid, category, JOB_DATA, "\"" + jobs.keyAt(ij) + "\"",
                            totalTime, count, bgTime, bgCount);
                }
            }

            final int[] jobStopReasonCodes = JobParameters.getJobStopReasonCodes();
            final Object[] jobCompletionArgs = new Object[jobStopReasonCodes.length + 1];

            final ArrayMap<String, SparseIntArray> completions = u.getJobCompletionStats();
            for (int ic=completions.size()-1; ic>=0; ic--) {
                SparseIntArray types = completions.valueAt(ic);
                if (types != null) {
                    jobCompletionArgs[0] = "\"" + completions.keyAt(ic) + "\"";
                    for (int i = 0; i < jobStopReasonCodes.length; i++) {
                        jobCompletionArgs[i + 1] = types.get(jobStopReasonCodes[i], 0);
                    }

                    dumpLine(pw, uid, category, JOB_COMPLETION_DATA, jobCompletionArgs);
                }
            }

            // Dump deferred jobs stats
            u.getDeferredJobsCheckinLineLocked(sb, which);
            if (sb.length() > 0) {
                dumpLine(pw, uid, category, JOBS_DEFERRED_DATA, sb.toString());
            }

            dumpTimer(pw, uid, category, FLASHLIGHT_DATA, u.getFlashlightTurnedOnTimer(),
                    rawRealtime, which);
            dumpTimer(pw, uid, category, CAMERA_DATA, u.getCameraTurnedOnTimer(),
                    rawRealtime, which);
            dumpTimer(pw, uid, category, VIDEO_DATA, u.getVideoTurnedOnTimer(),
                    rawRealtime, which);
            dumpTimer(pw, uid, category, AUDIO_DATA, u.getAudioTurnedOnTimer(),
                    rawRealtime, which);

            final SparseArray<? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            final int NSE = sensors.size();
            for (int ise=0; ise<NSE; ise++) {
                final Uid.Sensor se = sensors.valueAt(ise);
                final int sensorNumber = sensors.keyAt(ise);
                final Timer timer = se.getSensorTime();
                if (timer != null) {
                    // Convert from microseconds to milliseconds with rounding
                    final long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500)
                            / 1000;
                    if (totalTime != 0) {
                        final int count = timer.getCountLocked(which);
                        final Timer bgTimer = se.getSensorBackgroundTime();
                        final int bgCount = bgTimer != null ? bgTimer.getCountLocked(which) : 0;
                        // 'actualTime' are unpooled and always since reset (regardless of 'which')
                        final long actualTime = timer.getTotalDurationMsLocked(rawRealtimeMs);
                        final long bgActualTime = bgTimer != null ?
                                bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                        dumpLine(pw, uid, category, SENSOR_DATA, sensorNumber, totalTime,
                                count, bgCount, actualTime, bgActualTime);
                    }
                }
            }

            dumpTimer(pw, uid, category, VIBRATOR_DATA, u.getVibratorOnTimer(),
                    rawRealtime, which);

            dumpTimer(pw, uid, category, FOREGROUND_ACTIVITY_DATA, u.getForegroundActivityTimer(),
                    rawRealtime, which);

            dumpTimer(pw, uid, category, FOREGROUND_SERVICE_DATA, u.getForegroundServiceTimer(),
                    rawRealtime, which);

            final Object[] stateTimes = new Object[Uid.NUM_PROCESS_STATE];
            long totalStateTime = 0;
            for (int ips=0; ips<Uid.NUM_PROCESS_STATE; ips++) {
                final long time = u.getProcessStateTime(ips, rawRealtime, which);
                totalStateTime += time;
                stateTimes[ips] = (time + 500) / 1000;
            }
            if (totalStateTime > 0) {
                dumpLine(pw, uid, category, STATE_TIME_DATA, stateTimes);
            }

            final long userCpuTimeUs = u.getUserCpuTimeUs(which);
            final long systemCpuTimeUs = u.getSystemCpuTimeUs(which);
            if (userCpuTimeUs > 0 || systemCpuTimeUs > 0) {
                dumpLine(pw, uid, category, CPU_DATA, userCpuTimeUs / 1000, systemCpuTimeUs / 1000,
                        0 /* old cpu power, keep for compatibility */);
            }

            // If the cpuFreqs is null, then don't bother checking for cpu freq times.
            if (cpuFreqs != null) {
                final long[] cpuFreqTimeMs = u.getCpuFreqTimes(which);
                // If total cpuFreqTimes is null, then we don't need to check for
                // screenOffCpuFreqTimes.
                if (cpuFreqTimeMs != null && cpuFreqTimeMs.length == cpuFreqs.length) {
                    sb.setLength(0);
                    for (int i = 0; i < cpuFreqTimeMs.length; ++i) {
                        if (i != 0) sb.append(',');
                        sb.append(cpuFreqTimeMs[i]);
                    }
                    final long[] screenOffCpuFreqTimeMs = u.getScreenOffCpuFreqTimes(which);
                    if (screenOffCpuFreqTimeMs != null) {
                        for (int i = 0; i < screenOffCpuFreqTimeMs.length; ++i) {
                            sb.append(',').append(screenOffCpuFreqTimeMs[i]);
                        }
                    } else {
                        for (int i = 0; i < cpuFreqTimeMs.length; ++i) {
                            sb.append(",0");
                        }
                    }
                    dumpLine(pw, uid, category, CPU_TIMES_AT_FREQ_DATA, UID_TIMES_TYPE_ALL,
                            cpuFreqTimeMs.length, sb.toString());
                }

                for (int procState = 0; procState < Uid.NUM_PROCESS_STATE; ++procState) {
                    final long[] timesMs = u.getCpuFreqTimes(which, procState);
                    if (timesMs != null && timesMs.length == cpuFreqs.length) {
                        sb.setLength(0);
                        for (int i = 0; i < timesMs.length; ++i) {
                            if (i != 0) sb.append(',');
                            sb.append(timesMs[i]);
                        }
                        final long[] screenOffTimesMs = u.getScreenOffCpuFreqTimes(
                                which, procState);
                        if (screenOffTimesMs != null) {
                            for (int i = 0; i < screenOffTimesMs.length; ++i) {
                                sb.append(',').append(screenOffTimesMs[i]);
                            }
                        } else {
                            for (int i = 0; i < timesMs.length; ++i) {
                                sb.append(",0");
                            }
                        }
                        dumpLine(pw, uid, category, CPU_TIMES_AT_FREQ_DATA,
                                Uid.UID_PROCESS_TYPES[procState], timesMs.length, sb.toString());
                    }
                }
            }

            final ArrayMap<String, ? extends BatteryStats.Uid.Proc> processStats
                    = u.getProcessStats();
            for (int ipr=processStats.size()-1; ipr>=0; ipr--) {
                final Uid.Proc ps = processStats.valueAt(ipr);

                final long userMillis = ps.getUserTime(which);
                final long systemMillis = ps.getSystemTime(which);
                final long foregroundMillis = ps.getForegroundTime(which);
                final int starts = ps.getStarts(which);
                final int numCrashes = ps.getNumCrashes(which);
                final int numAnrs = ps.getNumAnrs(which);

                if (userMillis != 0 || systemMillis != 0 || foregroundMillis != 0
                        || starts != 0 || numAnrs != 0 || numCrashes != 0) {
                    dumpLine(pw, uid, category, PROCESS_DATA, "\"" + processStats.keyAt(ipr) + "\"",
                            userMillis, systemMillis, foregroundMillis, starts, numAnrs, numCrashes);
                }
            }

            final ArrayMap<String, ? extends BatteryStats.Uid.Pkg> packageStats
                    = u.getPackageStats();
            for (int ipkg=packageStats.size()-1; ipkg>=0; ipkg--) {
                final Uid.Pkg ps = packageStats.valueAt(ipkg);
                int wakeups = 0;
                final ArrayMap<String, ? extends Counter> alarms = ps.getWakeupAlarmStats();
                for (int iwa=alarms.size()-1; iwa>=0; iwa--) {
                    int count = alarms.valueAt(iwa).getCountLocked(which);
                    wakeups += count;
                    String name = alarms.keyAt(iwa).replace(',', '_');
                    dumpLine(pw, uid, category, WAKEUP_ALARM_DATA, name, count);
                }
                final ArrayMap<String, ? extends  Uid.Pkg.Serv> serviceStats = ps.getServiceStats();
                for (int isvc=serviceStats.size()-1; isvc>=0; isvc--) {
                    final BatteryStats.Uid.Pkg.Serv ss = serviceStats.valueAt(isvc);
                    final long startTime = ss.getStartTime(batteryUptime, which);
                    final int starts = ss.getStarts(which);
                    final int launches = ss.getLaunches(which);
                    if (startTime != 0 || starts != 0 || launches != 0) {
                        dumpLine(pw, uid, category, APK_DATA,
                                wakeups, // wakeup alarms
                                packageStats.keyAt(ipkg), // Apk
                                serviceStats.keyAt(isvc), // service
                                startTime / 1000, // time spent started, in ms
                                starts,
                                launches);
                    }
                }
            }
        }
    }

    static final class TimerEntry {
        final String mName;
        final int mId;
        final BatteryStats.Timer mTimer;
        final long mTime;
        TimerEntry(String name, int id, BatteryStats.Timer timer, long time) {
            mName = name;
            mId = id;
            mTimer = timer;
            mTime = time;
        }
    }

    private void printmAh(PrintWriter printer, double power) {
        printer.print(BatteryStatsHelper.makemAh(power));
    }

    private void printmAh(StringBuilder sb, double power) {
        sb.append(BatteryStatsHelper.makemAh(power));
    }

    /**
     * Temporary for settings.
     */
    public final void dumpLocked(Context context, PrintWriter pw, String prefix, int which,
            int reqUid) {
        dumpLocked(context, pw, prefix, which, reqUid, BatteryStatsHelper.checkWifiOnly(context));
    }

    @SuppressWarnings("unused")
    public final void dumpLocked(Context context, PrintWriter pw, String prefix, final int which,
            int reqUid, boolean wifiOnly) {

        if (which != BatteryStats.STATS_SINCE_CHARGED) {
            pw.println("ERROR: BatteryStats.dump called for which type " + which
                    + " but only STATS_SINCE_CHARGED is supported");
            return;
        }

        final long rawUptime = SystemClock.uptimeMillis() * 1000;
        final long rawRealtime = SystemClock.elapsedRealtime() * 1000;
        final long rawRealtimeMs = (rawRealtime + 500) / 1000;
        final long batteryUptime = getBatteryUptime(rawUptime);

        final long whichBatteryUptime = computeBatteryUptime(rawUptime, which);
        final long whichBatteryRealtime = computeBatteryRealtime(rawRealtime, which);
        final long totalRealtime = computeRealtime(rawRealtime, which);
        final long totalUptime = computeUptime(rawUptime, which);
        final long whichBatteryScreenOffUptime = computeBatteryScreenOffUptime(rawUptime, which);
        final long whichBatteryScreenOffRealtime = computeBatteryScreenOffRealtime(rawRealtime,
                which);
        final long batteryTimeRemaining = computeBatteryTimeRemaining(rawRealtime);
        final long chargeTimeRemaining = computeChargeTimeRemaining(rawRealtime);
        final long screenDozeTime = getScreenDozeTime(rawRealtime, which);

        final StringBuilder sb = new StringBuilder(128);

        final SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();

        final int estimatedBatteryCapacity = getEstimatedBatteryCapacity();
        if (estimatedBatteryCapacity > 0) {
            sb.setLength(0);
            sb.append(prefix);
                sb.append("  Estimated battery capacity: ");
                sb.append(BatteryStatsHelper.makemAh(estimatedBatteryCapacity));
                sb.append(" mAh");
            pw.println(sb.toString());
        }

        final int minLearnedBatteryCapacity = getMinLearnedBatteryCapacity();
        if (minLearnedBatteryCapacity > 0) {
            sb.setLength(0);
            sb.append(prefix);
                sb.append("  Min learned battery capacity: ");
                sb.append(BatteryStatsHelper.makemAh(minLearnedBatteryCapacity / 1000));
                sb.append(" mAh");
            pw.println(sb.toString());
        }
        final int maxLearnedBatteryCapacity = getMaxLearnedBatteryCapacity();
        if (maxLearnedBatteryCapacity > 0) {
            sb.setLength(0);
            sb.append(prefix);
                sb.append("  Max learned battery capacity: ");
                sb.append(BatteryStatsHelper.makemAh(maxLearnedBatteryCapacity / 1000));
                sb.append(" mAh");
            pw.println(sb.toString());
        }

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Time on battery: ");
        formatTimeMs(sb, whichBatteryRealtime / 1000); sb.append("(");
        sb.append(formatRatioLocked(whichBatteryRealtime, totalRealtime));
        sb.append(") realtime, ");
        formatTimeMs(sb, whichBatteryUptime / 1000);
        sb.append("("); sb.append(formatRatioLocked(whichBatteryUptime, whichBatteryRealtime));
        sb.append(") uptime");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Time on battery screen off: ");
        formatTimeMs(sb, whichBatteryScreenOffRealtime / 1000); sb.append("(");
        sb.append(formatRatioLocked(whichBatteryScreenOffRealtime, whichBatteryRealtime));
        sb.append(") realtime, ");
        formatTimeMs(sb, whichBatteryScreenOffUptime / 1000);
        sb.append("(");
        sb.append(formatRatioLocked(whichBatteryScreenOffUptime, whichBatteryRealtime));
        sb.append(") uptime");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Time on battery screen doze: ");
        formatTimeMs(sb, screenDozeTime / 1000); sb.append("(");
        sb.append(formatRatioLocked(screenDozeTime, whichBatteryRealtime));
        sb.append(")");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Total run time: ");
                formatTimeMs(sb, totalRealtime / 1000);
                sb.append("realtime, ");
                formatTimeMs(sb, totalUptime / 1000);
                sb.append("uptime");
        pw.println(sb.toString());
        if (batteryTimeRemaining >= 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Battery time remaining: ");
                    formatTimeMs(sb, batteryTimeRemaining / 1000);
            pw.println(sb.toString());
        }
        if (chargeTimeRemaining >= 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Charge time remaining: ");
                    formatTimeMs(sb, chargeTimeRemaining / 1000);
            pw.println(sb.toString());
        }

        final long dischargeCount = getUahDischarge(which);
        if (dischargeCount >= 0) {
            sb.setLength(0);
            sb.append(prefix);
                sb.append("  Discharge: ");
                sb.append(BatteryStatsHelper.makemAh(dischargeCount / 1000.0));
                sb.append(" mAh");
            pw.println(sb.toString());
        }

        final long dischargeScreenOffCount = getUahDischargeScreenOff(which);
        if (dischargeScreenOffCount >= 0) {
            sb.setLength(0);
            sb.append(prefix);
                sb.append("  Screen off discharge: ");
                sb.append(BatteryStatsHelper.makemAh(dischargeScreenOffCount / 1000.0));
                sb.append(" mAh");
            pw.println(sb.toString());
        }

        final long dischargeScreenDozeCount = getUahDischargeScreenDoze(which);
        if (dischargeScreenDozeCount >= 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Screen doze discharge: ");
            sb.append(BatteryStatsHelper.makemAh(dischargeScreenDozeCount / 1000.0));
            sb.append(" mAh");
            pw.println(sb.toString());
        }

        final long dischargeScreenOnCount = dischargeCount - dischargeScreenOffCount;
        if (dischargeScreenOnCount >= 0) {
            sb.setLength(0);
            sb.append(prefix);
                sb.append("  Screen on discharge: ");
                sb.append(BatteryStatsHelper.makemAh(dischargeScreenOnCount / 1000.0));
                sb.append(" mAh");
            pw.println(sb.toString());
        }

        final long dischargeLightDozeCount = getUahDischargeLightDoze(which);
        if (dischargeLightDozeCount >= 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Device light doze discharge: ");
            sb.append(BatteryStatsHelper.makemAh(dischargeLightDozeCount / 1000.0));
            sb.append(" mAh");
            pw.println(sb.toString());
        }

        final long dischargeDeepDozeCount = getUahDischargeDeepDoze(which);
        if (dischargeDeepDozeCount >= 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Device deep doze discharge: ");
            sb.append(BatteryStatsHelper.makemAh(dischargeDeepDozeCount / 1000.0));
            sb.append(" mAh");
            pw.println(sb.toString());
        }

        pw.print("  Start clock time: ");
        pw.println(DateFormat.format("yyyy-MM-dd-HH-mm-ss", getStartClockTime()).toString());

        final long screenOnTime = getScreenOnTime(rawRealtime, which);
        final long interactiveTime = getInteractiveTime(rawRealtime, which);
        final long powerSaveModeEnabledTime = getPowerSaveModeEnabledTime(rawRealtime, which);
        final long deviceIdleModeLightTime = getDeviceIdleModeTime(DEVICE_IDLE_MODE_LIGHT,
                rawRealtime, which);
        final long deviceIdleModeFullTime = getDeviceIdleModeTime(DEVICE_IDLE_MODE_DEEP,
                rawRealtime, which);
        final long deviceLightIdlingTime = getDeviceIdlingTime(DEVICE_IDLE_MODE_LIGHT,
                rawRealtime, which);
        final long deviceIdlingTime = getDeviceIdlingTime(DEVICE_IDLE_MODE_DEEP,
                rawRealtime, which);
        final long phoneOnTime = getPhoneOnTime(rawRealtime, which);
        final long wifiRunningTime = getGlobalWifiRunningTime(rawRealtime, which);
        final long wifiOnTime = getWifiOnTime(rawRealtime, which);
        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Screen on: "); formatTimeMs(sb, screenOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(screenOnTime, whichBatteryRealtime));
                sb.append(") "); sb.append(getScreenOnCount(which));
                sb.append("x, Interactive: "); formatTimeMs(sb, interactiveTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(interactiveTime, whichBatteryRealtime));
                sb.append(")");
        pw.println(sb.toString());
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Screen brightnesses:");
        boolean didOne = false;
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            final long time = getScreenBrightnessTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            sb.append(prefix);
            didOne = true;
            sb.append(SCREEN_BRIGHTNESS_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, screenOnTime));
            sb.append(")");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());
        if (powerSaveModeEnabledTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Power save mode enabled: ");
                    formatTimeMs(sb, powerSaveModeEnabledTime / 1000);
                    sb.append("(");
                    sb.append(formatRatioLocked(powerSaveModeEnabledTime, whichBatteryRealtime));
                    sb.append(")");
            pw.println(sb.toString());
        }
        if (deviceLightIdlingTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Device light idling: ");
                    formatTimeMs(sb, deviceLightIdlingTime / 1000);
                    sb.append("(");
                    sb.append(formatRatioLocked(deviceLightIdlingTime, whichBatteryRealtime));
                    sb.append(") "); sb.append(getDeviceIdlingCount(DEVICE_IDLE_MODE_LIGHT, which));
                    sb.append("x");
            pw.println(sb.toString());
        }
        if (deviceIdleModeLightTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Idle mode light time: ");
                    formatTimeMs(sb, deviceIdleModeLightTime / 1000);
                    sb.append("(");
                    sb.append(formatRatioLocked(deviceIdleModeLightTime, whichBatteryRealtime));
                    sb.append(") ");
                    sb.append(getDeviceIdleModeCount(DEVICE_IDLE_MODE_LIGHT, which));
                    sb.append("x");
                    sb.append(" -- longest ");
                    formatTimeMs(sb, getLongestDeviceIdleModeTime(DEVICE_IDLE_MODE_LIGHT));
            pw.println(sb.toString());
        }
        if (deviceIdlingTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Device full idling: ");
                    formatTimeMs(sb, deviceIdlingTime / 1000);
                    sb.append("(");
                    sb.append(formatRatioLocked(deviceIdlingTime, whichBatteryRealtime));
                    sb.append(") "); sb.append(getDeviceIdlingCount(DEVICE_IDLE_MODE_DEEP, which));
                    sb.append("x");
            pw.println(sb.toString());
        }
        if (deviceIdleModeFullTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Idle mode full time: ");
                    formatTimeMs(sb, deviceIdleModeFullTime / 1000);
                    sb.append("(");
                    sb.append(formatRatioLocked(deviceIdleModeFullTime, whichBatteryRealtime));
                    sb.append(") ");
                    sb.append(getDeviceIdleModeCount(DEVICE_IDLE_MODE_DEEP, which));
                    sb.append("x");
                    sb.append(" -- longest ");
                    formatTimeMs(sb, getLongestDeviceIdleModeTime(DEVICE_IDLE_MODE_DEEP));
            pw.println(sb.toString());
        }
        if (phoneOnTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Active phone call: "); formatTimeMs(sb, phoneOnTime / 1000);
                    sb.append("("); sb.append(formatRatioLocked(phoneOnTime, whichBatteryRealtime));
                    sb.append(") "); sb.append(getPhoneOnCount(which)); sb.append("x");
        }
        final int connChanges = getNumConnectivityChange(which);
        if (connChanges != 0) {
            pw.print(prefix);
            pw.print("  Connectivity changes: "); pw.println(connChanges);
        }

        // Calculate wakelock times across all uids.
        long fullWakeLockTimeTotalMicros = 0;
        long partialWakeLockTimeTotalMicros = 0;

        final ArrayList<TimerEntry> timers = new ArrayList<>();

        for (int iu = 0; iu < NU; iu++) {
            final Uid u = uidStats.valueAt(iu);

            final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelocks
                    = u.getWakelockStats();
            for (int iw=wakelocks.size()-1; iw>=0; iw--) {
                final Uid.Wakelock wl = wakelocks.valueAt(iw);

                final Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                if (fullWakeTimer != null) {
                    fullWakeLockTimeTotalMicros += fullWakeTimer.getTotalTimeLocked(
                            rawRealtime, which);
                }

                final Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                if (partialWakeTimer != null) {
                    final long totalTimeMicros = partialWakeTimer.getTotalTimeLocked(
                            rawRealtime, which);
                    if (totalTimeMicros > 0) {
                        if (reqUid < 0) {
                            // Only show the ordered list of all wake
                            // locks if the caller is not asking for data
                            // about a specific uid.
                            timers.add(new TimerEntry(wakelocks.keyAt(iw), u.getUid(),
                                    partialWakeTimer, totalTimeMicros));
                        }
                        partialWakeLockTimeTotalMicros += totalTimeMicros;
                    }
                }
            }
        }

        final long mobileRxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
        final long mobileTxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
        final long wifiRxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
        final long wifiTxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
        final long mobileRxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
        final long mobileTxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
        final long wifiRxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
        final long wifiTxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);
        final long btRxTotalBytes = getNetworkActivityBytes(NETWORK_BT_RX_DATA, which);
        final long btTxTotalBytes = getNetworkActivityBytes(NETWORK_BT_TX_DATA, which);

        if (fullWakeLockTimeTotalMicros != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Total full wakelock time: "); formatTimeMsNoSpace(sb,
                            (fullWakeLockTimeTotalMicros + 500) / 1000);
            pw.println(sb.toString());
        }

        if (partialWakeLockTimeTotalMicros != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Total partial wakelock time: "); formatTimeMsNoSpace(sb,
                            (partialWakeLockTimeTotalMicros + 500) / 1000);
            pw.println(sb.toString());
        }

        final long multicastWakeLockTimeTotalMicros =
                getWifiMulticastWakelockTime(rawRealtime, which);
        final int multicastWakeLockCountTotal = getWifiMulticastWakelockCount(which);
        if (multicastWakeLockTimeTotalMicros != 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Total WiFi Multicast wakelock Count: ");
            sb.append(multicastWakeLockCountTotal);
            pw.println(sb.toString());

            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Total WiFi Multicast wakelock time: ");
            formatTimeMsNoSpace(sb, (multicastWakeLockTimeTotalMicros + 500) / 1000);
            pw.println(sb.toString());
        }

        pw.println("");
        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  CONNECTIVITY POWER SUMMARY START");
        pw.println(sb.toString());

        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Logging duration for connectivity statistics: ");
        formatTimeMs(sb, whichBatteryRealtime / 1000);
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Cellular Statistics:");
        pw.println(sb.toString());

        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Cellular kernel active time: ");
        final long mobileActiveTime = getMobileRadioActiveTime(rawRealtime, which);
        formatTimeMs(sb, mobileActiveTime / 1000);
        sb.append("("); sb.append(formatRatioLocked(mobileActiveTime, whichBatteryRealtime));
        sb.append(")");
        pw.println(sb.toString());

        printControllerActivity(pw, sb, prefix, CELLULAR_CONTROLLER_NAME,
                getModemControllerActivity(), which);

        pw.print("     Cellular data received: "); pw.println(formatBytesLocked(mobileRxTotalBytes));
        pw.print("     Cellular data sent: "); pw.println(formatBytesLocked(mobileTxTotalBytes));
        pw.print("     Cellular packets received: "); pw.println(mobileRxTotalPackets);
        pw.print("     Cellular packets sent: "); pw.println(mobileTxTotalPackets);

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Cellular Radio Access Technology:");
        didOne = false;
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            final long time = getPhoneDataConnectionTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n       ");
            sb.append(prefix);
            didOne = true;
            sb.append(i < DATA_CONNECTION_NAMES.length ? DATA_CONNECTION_NAMES[i] : "ERROR");
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Cellular Rx signal strength (RSRP):");
        final String[] cellularRxSignalStrengthDescription = new String[]{
            "very poor (less than -128dBm): ",
            "poor (-128dBm to -118dBm): ",
            "moderate (-118dBm to -108dBm): ",
            "good (-108dBm to -98dBm): ",
            "great (greater than -98dBm): "};
        didOne = false;
        final int numCellularRxBins = Math.min(CellSignalStrength.getNumSignalStrengthLevels(),
            cellularRxSignalStrengthDescription.length);
        for (int i=0; i<numCellularRxBins; i++) {
            final long time = getPhoneSignalStrengthTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n       ");
            sb.append(prefix);
            didOne = true;
            sb.append(cellularRxSignalStrengthDescription[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Wifi Statistics:");
        pw.println(sb.toString());

        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Wifi kernel active time: ");
        final long wifiActiveTime = getWifiActiveTime(rawRealtime, which);
        formatTimeMs(sb, wifiActiveTime / 1000);
        sb.append("("); sb.append(formatRatioLocked(wifiActiveTime, whichBatteryRealtime));
        sb.append(")");
        pw.println(sb.toString());

        printControllerActivity(pw, sb, prefix, WIFI_CONTROLLER_NAME,
                getWifiControllerActivity(), which);

        pw.print("     Wifi data received: "); pw.println(formatBytesLocked(wifiRxTotalBytes));
        pw.print("     Wifi data sent: "); pw.println(formatBytesLocked(wifiTxTotalBytes));
        pw.print("     Wifi packets received: "); pw.println(wifiRxTotalPackets);
        pw.print("     Wifi packets sent: "); pw.println(wifiTxTotalPackets);

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Wifi states:");
        didOne = false;
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            final long time = getWifiStateTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n       ");
            didOne = true;
            sb.append(WIFI_STATE_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Wifi supplicant states:");
        didOne = false;
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            final long time = getWifiSupplStateTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n       ");
            didOne = true;
            sb.append(WIFI_SUPPL_STATE_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     Wifi Rx signal strength (RSSI):");
        final String[] wifiRxSignalStrengthDescription = new String[]{
            "very poor (less than -88.75dBm): ",
            "poor (-88.75 to -77.5dBm): ",
            "moderate (-77.5dBm to -66.25dBm): ",
            "good (-66.25dBm to -55dBm): ",
            "great (greater than -55dBm): "};
        didOne = false;
        final int numWifiRxBins = Math.min(NUM_WIFI_SIGNAL_STRENGTH_BINS,
            wifiRxSignalStrengthDescription.length);
        for (int i=0; i<numWifiRxBins; i++) {
            final long time = getWifiSignalStrengthTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            sb.append(prefix);
            didOne = true;
            sb.append("     ");
            sb.append(wifiRxSignalStrengthDescription[i]);
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  GPS Statistics:");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("     GPS signal quality (Top 4 Average CN0):");
        final String[] gpsSignalQualityDescription = new String[]{
            "poor (less than 20 dBHz): ",
            "good (greater than 20 dBHz): "};
        final int numGpsSignalQualityBins = Math.min(
                GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS,
                gpsSignalQualityDescription.length);
        for (int i=0; i<numGpsSignalQualityBins; i++) {
            final long time = getGpsSignalQualityTime(i, rawRealtime, which);
            sb.append("\n    ");
            sb.append(prefix);
            sb.append("  ");
            sb.append(gpsSignalQualityDescription[i]);
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
        }
        pw.println(sb.toString());

        final long gpsBatteryDrainMaMs = getGpsBatteryDrainMaMs();
        if (gpsBatteryDrainMaMs > 0) {
            pw.print(prefix);
            sb.setLength(0);
            sb.append(prefix);
            sb.append("     GPS Battery Drain: ");
            sb.append(new DecimalFormat("#.##").format(
                    ((double) gpsBatteryDrainMaMs) / (3600 * 1000)));
            sb.append("mAh");
            pw.println(sb.toString());
        }

        pw.print(prefix);
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  CONNECTIVITY POWER SUMMARY END");
        pw.println(sb.toString());
        pw.println("");

        pw.print(prefix);
        pw.print("  Bluetooth total received: "); pw.print(formatBytesLocked(btRxTotalBytes));
        pw.print(", sent: "); pw.println(formatBytesLocked(btTxTotalBytes));

        final long bluetoothScanTimeMs = getBluetoothScanTime(rawRealtime, which) / 1000;
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Bluetooth scan time: "); formatTimeMs(sb, bluetoothScanTimeMs);
        pw.println(sb.toString());

        printControllerActivity(pw, sb, prefix, "Bluetooth", getBluetoothControllerActivity(),
                which);

        pw.println();

        pw.print(prefix); pw.println("  Device battery use since last full charge");
        pw.print(prefix); pw.print("    Amount discharged (lower bound): ");
        pw.println(getLowDischargeAmountSinceCharge());
        pw.print(prefix); pw.print("    Amount discharged (upper bound): ");
        pw.println(getHighDischargeAmountSinceCharge());
        pw.print(prefix); pw.print("    Amount discharged while screen on: ");
        pw.println(getDischargeAmountScreenOnSinceCharge());
        pw.print(prefix); pw.print("    Amount discharged while screen off: ");
        pw.println(getDischargeAmountScreenOffSinceCharge());
        pw.print(prefix); pw.print("    Amount discharged while screen doze: ");
        pw.println(getDischargeAmountScreenDozeSinceCharge());
        pw.println();

        final BatteryStatsHelper helper = new BatteryStatsHelper(context, false, wifiOnly);
        helper.create(this);
        helper.refreshStats(which, UserHandle.USER_ALL);
        List<BatterySipper> sippers = helper.getUsageList();
        if (sippers != null && sippers.size() > 0) {
            pw.print(prefix); pw.println("  Estimated power use (mAh):");
            pw.print(prefix); pw.print("    Capacity: ");
                    printmAh(pw, helper.getPowerProfile().getBatteryCapacity());
                    pw.print(", Computed drain: "); printmAh(pw, helper.getComputedPower());
                    pw.print(", actual drain: "); printmAh(pw, helper.getMinDrainedPower());
                    if (helper.getMinDrainedPower() != helper.getMaxDrainedPower()) {
                        pw.print("-"); printmAh(pw, helper.getMaxDrainedPower());
                    }
                    pw.println();
            for (int i=0; i<sippers.size(); i++) {
                final BatterySipper bs = sippers.get(i);
                pw.print(prefix);
                switch (bs.drainType) {
                    case AMBIENT_DISPLAY:
                        pw.print("    Ambient display: ");
                        break;
                    case IDLE:
                        pw.print("    Idle: ");
                        break;
                    case CELL:
                        pw.print("    Cell standby: ");
                        break;
                    case PHONE:
                        pw.print("    Phone calls: ");
                        break;
                    case WIFI:
                        pw.print("    Wifi: ");
                        break;
                    case BLUETOOTH:
                        pw.print("    Bluetooth: ");
                        break;
                    case SCREEN:
                        pw.print("    Screen: ");
                        break;
                    case FLASHLIGHT:
                        pw.print("    Flashlight: ");
                        break;
                    case APP:
                        pw.print("    Uid ");
                        UserHandle.formatUid(pw, bs.uidObj.getUid());
                        pw.print(": ");
                        break;
                    case USER:
                        pw.print("    User "); pw.print(bs.userId);
                        pw.print(": ");
                        break;
                    case UNACCOUNTED:
                        pw.print("    Unaccounted: ");
                        break;
                    case OVERCOUNTED:
                        pw.print("    Over-counted: ");
                        break;
                    case CAMERA:
                        pw.print("    Camera: ");
                        break;
                    default:
                        pw.print("    ???: ");
                        break;
                }
                printmAh(pw, bs.totalPowerMah);

                if (bs.usagePowerMah != bs.totalPowerMah) {
                    // If the usage (generic power) isn't the whole amount, we list out
                    // what components are involved in the calculation.

                    pw.print(" (");
                    if (bs.usagePowerMah != 0) {
                        pw.print(" usage=");
                        printmAh(pw, bs.usagePowerMah);
                    }
                    if (bs.cpuPowerMah != 0) {
                        pw.print(" cpu=");
                        printmAh(pw, bs.cpuPowerMah);
                    }
                    if (bs.wakeLockPowerMah != 0) {
                        pw.print(" wake=");
                        printmAh(pw, bs.wakeLockPowerMah);
                    }
                    if (bs.mobileRadioPowerMah != 0) {
                        pw.print(" radio=");
                        printmAh(pw, bs.mobileRadioPowerMah);
                    }
                    if (bs.wifiPowerMah != 0) {
                        pw.print(" wifi=");
                        printmAh(pw, bs.wifiPowerMah);
                    }
                    if (bs.bluetoothPowerMah != 0) {
                        pw.print(" bt=");
                        printmAh(pw, bs.bluetoothPowerMah);
                    }
                    if (bs.gpsPowerMah != 0) {
                        pw.print(" gps=");
                        printmAh(pw, bs.gpsPowerMah);
                    }
                    if (bs.sensorPowerMah != 0) {
                        pw.print(" sensor=");
                        printmAh(pw, bs.sensorPowerMah);
                    }
                    if (bs.cameraPowerMah != 0) {
                        pw.print(" camera=");
                        printmAh(pw, bs.cameraPowerMah);
                    }
                    if (bs.flashlightPowerMah != 0) {
                        pw.print(" flash=");
                        printmAh(pw, bs.flashlightPowerMah);
                    }
                    if (bs.customMeasuredPowerMah != null) {
                        for (int idx = 0; idx < bs.customMeasuredPowerMah.length; idx++) {
                            final double customPowerMah = bs.customMeasuredPowerMah[idx];
                            if (customPowerMah != 0) {
                                pw.print(" custom[" + idx + "]=");
                                printmAh(pw, customPowerMah);
                            }
                        }
                    }
                    pw.print(" )");
                }

                // If there is additional smearing information, include it.
                if (bs.totalSmearedPowerMah != bs.totalPowerMah) {
                    pw.print(" Including smearing: ");
                    printmAh(pw, bs.totalSmearedPowerMah);
                    pw.print(" (");
                    if (bs.screenPowerMah != 0) {
                        pw.print(" screen=");
                        printmAh(pw, bs.screenPowerMah);
                    }
                    if (bs.proportionalSmearMah != 0) {
                        pw.print(" proportional=");
                        printmAh(pw, bs.proportionalSmearMah);
                    }
                    pw.print(" )");
                }
                if (bs.shouldHide) {
                    pw.print(" Excluded from smearing");
                }

                pw.println();
            }
            pw.println();
        }

        sippers = helper.getMobilemsppList();
        if (sippers != null && sippers.size() > 0) {
            pw.print(prefix); pw.println("  Per-app mobile ms per packet:");
            long totalTime = 0;
            for (int i=0; i<sippers.size(); i++) {
                final BatterySipper bs = sippers.get(i);
                sb.setLength(0);
                sb.append(prefix); sb.append("    Uid ");
                UserHandle.formatUid(sb, bs.uidObj.getUid());
                sb.append(": "); sb.append(BatteryStatsHelper.makemAh(bs.mobilemspp));
                sb.append(" ("); sb.append(bs.mobileRxPackets+bs.mobileTxPackets);
                sb.append(" packets over "); formatTimeMsNoSpace(sb, bs.mobileActive);
                sb.append(") "); sb.append(bs.mobileActiveCount); sb.append("x");
                pw.println(sb.toString());
                totalTime += bs.mobileActive;
            }
            sb.setLength(0);
            sb.append(prefix);
            sb.append("    TOTAL TIME: ");
            formatTimeMs(sb, totalTime);
            sb.append("("); sb.append(formatRatioLocked(totalTime, whichBatteryRealtime));
            sb.append(")");
            pw.println(sb.toString());
            pw.println();
        }

        final Comparator<TimerEntry> timerComparator = new Comparator<TimerEntry>() {
            @Override
            public int compare(TimerEntry lhs, TimerEntry rhs) {
                long lhsTime = lhs.mTime;
                long rhsTime = rhs.mTime;
                if (lhsTime < rhsTime) {
                    return 1;
                }
                if (lhsTime > rhsTime) {
                    return -1;
                }
                return 0;
            }
        };

        if (reqUid < 0) {
            final Map<String, ? extends BatteryStats.Timer> kernelWakelocks
                    = getKernelWakelockStats();
            if (kernelWakelocks.size() > 0) {
                final ArrayList<TimerEntry> ktimers = new ArrayList<>();
                for (Map.Entry<String, ? extends BatteryStats.Timer> ent
                        : kernelWakelocks.entrySet()) {
                    final BatteryStats.Timer timer = ent.getValue();
                    final long totalTimeMillis = computeWakeLock(timer, rawRealtime, which);
                    if (totalTimeMillis > 0) {
                        ktimers.add(new TimerEntry(ent.getKey(), 0, timer, totalTimeMillis));
                    }
                }
                if (ktimers.size() > 0) {
                    Collections.sort(ktimers, timerComparator);
                    pw.print(prefix); pw.println("  All kernel wake locks:");
                    for (int i=0; i<ktimers.size(); i++) {
                        final TimerEntry timer = ktimers.get(i);
                        String linePrefix = ": ";
                        sb.setLength(0);
                        sb.append(prefix);
                        sb.append("  Kernel Wake lock ");
                        sb.append(timer.mName);
                        linePrefix = printWakeLock(sb, timer.mTimer, rawRealtime, null,
                                which, linePrefix);
                        if (!linePrefix.equals(": ")) {
                            sb.append(" realtime");
                            // Only print out wake locks that were held
                            pw.println(sb.toString());
                        }
                    }
                    pw.println();
                }
            }

            if (timers.size() > 0) {
                Collections.sort(timers, timerComparator);
                pw.print(prefix); pw.println("  All partial wake locks:");
                for (int i=0; i<timers.size(); i++) {
                    TimerEntry timer = timers.get(i);
                    sb.setLength(0);
                    sb.append("  Wake lock ");
                    UserHandle.formatUid(sb, timer.mId);
                    sb.append(" ");
                    sb.append(timer.mName);
                    printWakeLock(sb, timer.mTimer, rawRealtime, null, which, ": ");
                    sb.append(" realtime");
                    pw.println(sb.toString());
                }
                timers.clear();
                pw.println();
            }

            final Map<String, ? extends Timer> wakeupReasons = getWakeupReasonStats();
            if (wakeupReasons.size() > 0) {
                pw.print(prefix); pw.println("  All wakeup reasons:");
                final ArrayList<TimerEntry> reasons = new ArrayList<>();
                for (Map.Entry<String, ? extends Timer> ent : wakeupReasons.entrySet()) {
                    final Timer timer = ent.getValue();
                    reasons.add(new TimerEntry(ent.getKey(), 0, timer,
                            timer.getCountLocked(which)));
                }
                Collections.sort(reasons, timerComparator);
                for (int i=0; i<reasons.size(); i++) {
                    TimerEntry timer = reasons.get(i);
                    String linePrefix = ": ";
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("  Wakeup reason ");
                    sb.append(timer.mName);
                    printWakeLock(sb, timer.mTimer, rawRealtime, null, which, ": ");
                    sb.append(" realtime");
                    pw.println(sb.toString());
                }
                pw.println();
            }
        }

        final LongSparseArray<? extends Timer> mMemoryStats = getKernelMemoryStats();
        if (mMemoryStats.size() > 0) {
            pw.println("  Memory Stats");
            for (int i = 0; i < mMemoryStats.size(); i++) {
                sb.setLength(0);
                sb.append("  Bandwidth ");
                sb.append(mMemoryStats.keyAt(i));
                sb.append(" Time ");
                sb.append(mMemoryStats.valueAt(i).getTotalTimeLocked(rawRealtime, which));
                pw.println(sb.toString());
            }
            pw.println();
        }

        final Map<String, ? extends Timer> rpmStats = getRpmStats();
        if (rpmStats.size() > 0) {
            pw.print(prefix); pw.println("  Resource Power Manager Stats");
            if (rpmStats.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : rpmStats.entrySet()) {
                    final String timerName = ent.getKey();
                    final Timer timer = ent.getValue();
                    printTimer(pw, sb, timer, rawRealtime, which, prefix, timerName);
                }
            }
            pw.println();
        }
        if (SCREEN_OFF_RPM_STATS_ENABLED) {
            final Map<String, ? extends Timer> screenOffRpmStats = getScreenOffRpmStats();
            if (screenOffRpmStats.size() > 0) {
                pw.print(prefix);
                pw.println("  Resource Power Manager Stats for when screen was off");
                if (screenOffRpmStats.size() > 0) {
                    for (Map.Entry<String, ? extends Timer> ent : screenOffRpmStats.entrySet()) {
                        final String timerName = ent.getKey();
                        final Timer timer = ent.getValue();
                        printTimer(pw, sb, timer, rawRealtime, which, prefix, timerName);
                    }
                }
                pw.println();
            }
        }

        final long[] cpuFreqs = getCpuFreqs();
        if (cpuFreqs != null) {
            sb.setLength(0);
            sb.append("  CPU freqs:");
            for (int i = 0; i < cpuFreqs.length; ++i) {
                sb.append(' ').append(cpuFreqs[i]);
            }
            pw.println(sb.toString());
            pw.println();
        }

        for (int iu=0; iu<NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            if (reqUid >= 0 && uid != reqUid && uid != Process.SYSTEM_UID) {
                continue;
            }

            final Uid u = uidStats.valueAt(iu);

            pw.print(prefix);
            pw.print("  ");
            UserHandle.formatUid(pw, uid);
            pw.println(":");
            boolean uidActivity = false;

            final long mobileRxBytes = u.getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
            final long mobileTxBytes = u.getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
            final long wifiRxBytes = u.getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
            final long wifiTxBytes = u.getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
            final long btRxBytes = u.getNetworkActivityBytes(NETWORK_BT_RX_DATA, which);
            final long btTxBytes = u.getNetworkActivityBytes(NETWORK_BT_TX_DATA, which);

            final long mobileRxPackets = u.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
            final long mobileTxPackets = u.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
            final long wifiRxPackets = u.getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
            final long wifiTxPackets = u.getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);

            final long uidMobileActiveTime = u.getMobileRadioActiveTime(which);
            final int uidMobileActiveCount = u.getMobileRadioActiveCount(which);

            final long fullWifiLockOnTime = u.getFullWifiLockTime(rawRealtime, which);
            final long wifiScanTime = u.getWifiScanTime(rawRealtime, which);
            final int wifiScanCount = u.getWifiScanCount(which);
            final int wifiScanCountBg = u.getWifiScanBackgroundCount(which);
            // 'actualTime' are unpooled and always since reset (regardless of 'which')
            final long wifiScanActualTime = u.getWifiScanActualTime(rawRealtime);
            final long wifiScanActualTimeBg = u.getWifiScanBackgroundTime(rawRealtime);
            final long uidWifiRunningTime = u.getWifiRunningTime(rawRealtime, which);

            final long mobileWakeup = u.getMobileRadioApWakeupCount(which);
            final long wifiWakeup = u.getWifiRadioApWakeupCount(which);

            if (mobileRxBytes > 0 || mobileTxBytes > 0
                    || mobileRxPackets > 0 || mobileTxPackets > 0) {
                pw.print(prefix); pw.print("    Mobile network: ");
                        pw.print(formatBytesLocked(mobileRxBytes)); pw.print(" received, ");
                        pw.print(formatBytesLocked(mobileTxBytes));
                        pw.print(" sent (packets "); pw.print(mobileRxPackets);
                        pw.print(" received, "); pw.print(mobileTxPackets); pw.println(" sent)");
            }
            if (uidMobileActiveTime > 0 || uidMobileActiveCount > 0) {
                sb.setLength(0);
                sb.append(prefix); sb.append("    Mobile radio active: ");
                formatTimeMs(sb, uidMobileActiveTime / 1000);
                sb.append("(");
                sb.append(formatRatioLocked(uidMobileActiveTime, mobileActiveTime));
                sb.append(") "); sb.append(uidMobileActiveCount); sb.append("x");
                long packets = mobileRxPackets + mobileTxPackets;
                if (packets == 0) {
                    packets = 1;
                }
                sb.append(" @ ");
                sb.append(BatteryStatsHelper.makemAh(uidMobileActiveTime / 1000 / (double)packets));
                sb.append(" mspp");
                pw.println(sb.toString());
            }

            if (mobileWakeup > 0) {
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Mobile radio AP wakeups: ");
                sb.append(mobileWakeup);
                pw.println(sb.toString());
            }

            printControllerActivityIfInteresting(pw, sb, prefix + "  ",
                CELLULAR_CONTROLLER_NAME, u.getModemControllerActivity(), which);

            if (wifiRxBytes > 0 || wifiTxBytes > 0 || wifiRxPackets > 0 || wifiTxPackets > 0) {
                pw.print(prefix); pw.print("    Wi-Fi network: ");
                        pw.print(formatBytesLocked(wifiRxBytes)); pw.print(" received, ");
                        pw.print(formatBytesLocked(wifiTxBytes));
                        pw.print(" sent (packets "); pw.print(wifiRxPackets);
                        pw.print(" received, "); pw.print(wifiTxPackets); pw.println(" sent)");
            }

            if (fullWifiLockOnTime != 0 || wifiScanTime != 0 || wifiScanCount != 0
                    || wifiScanCountBg != 0 || wifiScanActualTime != 0 || wifiScanActualTimeBg != 0
                    || uidWifiRunningTime != 0) {
                sb.setLength(0);
                sb.append(prefix); sb.append("    Wifi Running: ");
                        formatTimeMs(sb, uidWifiRunningTime / 1000);
                        sb.append("("); sb.append(formatRatioLocked(uidWifiRunningTime,
                                whichBatteryRealtime)); sb.append(")\n");
                sb.append(prefix); sb.append("    Full Wifi Lock: ");
                        formatTimeMs(sb, fullWifiLockOnTime / 1000);
                        sb.append("("); sb.append(formatRatioLocked(fullWifiLockOnTime,
                                whichBatteryRealtime)); sb.append(")\n");
                sb.append(prefix); sb.append("    Wifi Scan (blamed): ");
                        formatTimeMs(sb, wifiScanTime / 1000);
                        sb.append("("); sb.append(formatRatioLocked(wifiScanTime,
                                whichBatteryRealtime)); sb.append(") ");
                                sb.append(wifiScanCount);
                                sb.append("x\n");
                // actual and background times are unpooled and since reset (regardless of 'which')
                sb.append(prefix); sb.append("    Wifi Scan (actual): ");
                        formatTimeMs(sb, wifiScanActualTime / 1000);
                        sb.append("("); sb.append(formatRatioLocked(wifiScanActualTime,
                                computeBatteryRealtime(rawRealtime, STATS_SINCE_CHARGED)));
                                sb.append(") ");
                                sb.append(wifiScanCount);
                                sb.append("x\n");
                sb.append(prefix); sb.append("    Background Wifi Scan: ");
                        formatTimeMs(sb, wifiScanActualTimeBg / 1000);
                        sb.append("("); sb.append(formatRatioLocked(wifiScanActualTimeBg,
                                computeBatteryRealtime(rawRealtime, STATS_SINCE_CHARGED)));
                                sb.append(") ");
                                sb.append(wifiScanCountBg);
                                sb.append("x");
                pw.println(sb.toString());
            }

            if (wifiWakeup > 0) {
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    WiFi AP wakeups: ");
                sb.append(wifiWakeup);
                pw.println(sb.toString());
            }

            printControllerActivityIfInteresting(pw, sb, prefix + "  ", WIFI_CONTROLLER_NAME,
                    u.getWifiControllerActivity(), which);

            if (btRxBytes > 0 || btTxBytes > 0) {
                pw.print(prefix); pw.print("    Bluetooth network: ");
                pw.print(formatBytesLocked(btRxBytes)); pw.print(" received, ");
                pw.print(formatBytesLocked(btTxBytes));
                pw.println(" sent");
            }

            final Timer bleTimer = u.getBluetoothScanTimer();
            if (bleTimer != null) {
                // Convert from microseconds to milliseconds with rounding
                final long totalTimeMs = (bleTimer.getTotalTimeLocked(rawRealtime, which) + 500)
                        / 1000;
                if (totalTimeMs != 0) {
                    final int count = bleTimer.getCountLocked(which);
                    final Timer bleTimerBg = u.getBluetoothScanBackgroundTimer();
                    final int countBg = bleTimerBg != null ? bleTimerBg.getCountLocked(which) : 0;
                    // 'actualTime' are unpooled and always since reset (regardless of 'which')
                    final long actualTimeMs = bleTimer.getTotalDurationMsLocked(rawRealtimeMs);
                    final long actualTimeMsBg = bleTimerBg != null ?
                            bleTimerBg.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                    // Result counters
                    final int resultCount = u.getBluetoothScanResultCounter() != null ?
                            u.getBluetoothScanResultCounter().getCountLocked(which) : 0;
                    final int resultCountBg = u.getBluetoothScanResultBgCounter() != null ?
                            u.getBluetoothScanResultBgCounter().getCountLocked(which) : 0;
                    // Unoptimized scan timer. Unpooled and since reset (regardless of 'which').
                    final Timer unoptimizedScanTimer = u.getBluetoothUnoptimizedScanTimer();
                    final long unoptimizedScanTotalTime = unoptimizedScanTimer != null ?
                            unoptimizedScanTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                    final long unoptimizedScanMaxTime = unoptimizedScanTimer != null ?
                            unoptimizedScanTimer.getMaxDurationMsLocked(rawRealtimeMs) : 0;
                    // Unoptimized bg scan timer. Unpooled and since reset (regardless of 'which').
                    final Timer unoptimizedScanTimerBg =
                            u.getBluetoothUnoptimizedScanBackgroundTimer();
                    final long unoptimizedScanTotalTimeBg = unoptimizedScanTimerBg != null ?
                            unoptimizedScanTimerBg.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                    final long unoptimizedScanMaxTimeBg = unoptimizedScanTimerBg != null ?
                            unoptimizedScanTimerBg.getMaxDurationMsLocked(rawRealtimeMs) : 0;

                    sb.setLength(0);
                    if (actualTimeMs != totalTimeMs) {
                        sb.append(prefix);
                        sb.append("    Bluetooth Scan (total blamed realtime): ");
                        formatTimeMs(sb, totalTimeMs);
                        sb.append(" (");
                        sb.append(count);
                        sb.append(" times)");
                        if (bleTimer.isRunningLocked()) {
                            sb.append(" (currently running)");
                        }
                        sb.append("\n");
                    }

                    sb.append(prefix);
                    sb.append("    Bluetooth Scan (total actual realtime): ");
                    formatTimeMs(sb, actualTimeMs); // since reset, ignores 'which'
                    sb.append(" (");
                    sb.append(count);
                    sb.append(" times)");
                    if (bleTimer.isRunningLocked()) {
                            sb.append(" (currently running)");
                    }
                    sb.append("\n");
                    if (actualTimeMsBg > 0 || countBg > 0) {
                        sb.append(prefix);
                        sb.append("    Bluetooth Scan (background realtime): ");
                        formatTimeMs(sb, actualTimeMsBg); // since reset, ignores 'which'
                        sb.append(" (");
                        sb.append(countBg);
                        sb.append(" times)");
                        if (bleTimerBg != null && bleTimerBg.isRunningLocked()) {
                            sb.append(" (currently running in background)");
                        }
                        sb.append("\n");
                    }

                    sb.append(prefix);
                    sb.append("    Bluetooth Scan Results: ");
                    sb.append(resultCount);
                    sb.append(" (");
                    sb.append(resultCountBg);
                    sb.append(" in background)");

                    if (unoptimizedScanTotalTime > 0 || unoptimizedScanTotalTimeBg > 0) {
                        sb.append("\n");
                        sb.append(prefix);
                        sb.append("    Unoptimized Bluetooth Scan (realtime): ");
                        formatTimeMs(sb, unoptimizedScanTotalTime); // since reset, ignores 'which'
                        sb.append(" (max ");
                        formatTimeMs(sb, unoptimizedScanMaxTime); // since reset, ignores 'which'
                        sb.append(")");
                        if (unoptimizedScanTimer != null
                                && unoptimizedScanTimer.isRunningLocked()) {
                            sb.append(" (currently running unoptimized)");
                        }
                        if (unoptimizedScanTimerBg != null && unoptimizedScanTotalTimeBg > 0) {
                            sb.append("\n");
                            sb.append(prefix);
                            sb.append("    Unoptimized Bluetooth Scan (background realtime): ");
                            formatTimeMs(sb, unoptimizedScanTotalTimeBg); // since reset
                            sb.append(" (max ");
                            formatTimeMs(sb, unoptimizedScanMaxTimeBg); // since reset
                            sb.append(")");
                            if (unoptimizedScanTimerBg.isRunningLocked()) {
                                sb.append(" (currently running unoptimized in background)");
                            }
                        }
                    }
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }



            if (u.hasUserActivity()) {
                boolean hasData = false;
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    final int val = u.getUserActivityCount(i, which);
                    if (val != 0) {
                        if (!hasData) {
                            sb.setLength(0);
                            sb.append("    User activity: ");
                            hasData = true;
                        } else {
                            sb.append(", ");
                        }
                        sb.append(val);
                        sb.append(" ");
                        sb.append(Uid.USER_ACTIVITY_TYPES[i]);
                    }
                }
                if (hasData) {
                    pw.println(sb.toString());
                }
            }

            final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelocks
                    = u.getWakelockStats();
            long totalFullWakelock = 0, totalPartialWakelock = 0, totalWindowWakelock = 0;
            long totalDrawWakelock = 0;
            int countWakelock = 0;
            for (int iw=wakelocks.size()-1; iw>=0; iw--) {
                final Uid.Wakelock wl = wakelocks.valueAt(iw);
                String linePrefix = ": ";
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Wake lock ");
                sb.append(wakelocks.keyAt(iw));
                linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_FULL), rawRealtime,
                        "full", which, linePrefix);
                final Timer pTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                linePrefix = printWakeLock(sb, pTimer, rawRealtime,
                        "partial", which, linePrefix);
                linePrefix = printWakeLock(sb, pTimer != null ? pTimer.getSubTimer() : null,
                        rawRealtime, "background partial", which, linePrefix);
                linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), rawRealtime,
                        "window", which, linePrefix);
                linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_DRAW), rawRealtime,
                        "draw", which, linePrefix);
                sb.append(" realtime");
                pw.println(sb.toString());
                uidActivity = true;
                countWakelock++;

                totalFullWakelock += computeWakeLock(wl.getWakeTime(WAKE_TYPE_FULL),
                        rawRealtime, which);
                totalPartialWakelock += computeWakeLock(wl.getWakeTime(WAKE_TYPE_PARTIAL),
                        rawRealtime, which);
                totalWindowWakelock += computeWakeLock(wl.getWakeTime(WAKE_TYPE_WINDOW),
                        rawRealtime, which);
                totalDrawWakelock += computeWakeLock(wl.getWakeTime(WAKE_TYPE_DRAW),
                        rawRealtime, which);
            }
            if (countWakelock > 1) {
                // get unpooled partial wakelock quantities (unlike totalPartialWakelock, which is
                // pooled and therefore just a lower bound)
                long actualTotalPartialWakelock = 0;
                long actualBgPartialWakelock = 0;
                if (u.getAggregatedPartialWakelockTimer() != null) {
                    final Timer aggTimer = u.getAggregatedPartialWakelockTimer();
                    // Convert from microseconds to milliseconds with rounding
                    actualTotalPartialWakelock =
                            aggTimer.getTotalDurationMsLocked(rawRealtimeMs);
                    final Timer bgAggTimer = aggTimer.getSubTimer();
                    actualBgPartialWakelock = bgAggTimer != null ?
                            bgAggTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                }

                if (actualTotalPartialWakelock != 0 || actualBgPartialWakelock != 0 ||
                        totalFullWakelock != 0 || totalPartialWakelock != 0 ||
                        totalWindowWakelock != 0) {
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    TOTAL wake: ");
                    boolean needComma = false;
                    if (totalFullWakelock != 0) {
                        needComma = true;
                        formatTimeMs(sb, totalFullWakelock);
                        sb.append("full");
                    }
                    if (totalPartialWakelock != 0) {
                        if (needComma) {
                            sb.append(", ");
                        }
                        needComma = true;
                        formatTimeMs(sb, totalPartialWakelock);
                        sb.append("blamed partial");
                    }
                    if (actualTotalPartialWakelock != 0) {
                        if (needComma) {
                            sb.append(", ");
                        }
                        needComma = true;
                        formatTimeMs(sb, actualTotalPartialWakelock);
                        sb.append("actual partial");
                    }
                    if (actualBgPartialWakelock != 0) {
                        if (needComma) {
                            sb.append(", ");
                        }
                        needComma = true;
                        formatTimeMs(sb, actualBgPartialWakelock);
                        sb.append("actual background partial");
                    }
                    if (totalWindowWakelock != 0) {
                        if (needComma) {
                            sb.append(", ");
                        }
                        needComma = true;
                        formatTimeMs(sb, totalWindowWakelock);
                        sb.append("window");
                    }
                    if (totalDrawWakelock != 0) {
                        if (needComma) {
                            sb.append(",");
                        }
                        needComma = true;
                        formatTimeMs(sb, totalDrawWakelock);
                        sb.append("draw");
                    }
                    sb.append(" realtime");
                    pw.println(sb.toString());
                }
            }

            // Calculate multicast wakelock stats
            final Timer mcTimer = u.getMulticastWakelockStats();
            if (mcTimer != null) {
                final long multicastWakeLockTimeMicros = mcTimer.getTotalTimeLocked(rawRealtime, which);
                final int multicastWakeLockCount = mcTimer.getCountLocked(which);

                if (multicastWakeLockTimeMicros > 0) {
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    WiFi Multicast Wakelock");
                    sb.append(" count = ");
                    sb.append(multicastWakeLockCount);
                    sb.append(" time = ");
                    formatTimeMsNoSpace(sb, (multicastWakeLockTimeMicros + 500) / 1000);
                    pw.println(sb.toString());
                }
            }

            final ArrayMap<String, ? extends Timer> syncs = u.getSyncStats();
            for (int isy=syncs.size()-1; isy>=0; isy--) {
                final Timer timer = syncs.valueAt(isy);
                // Convert from microseconds to milliseconds with rounding
                final long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                final int count = timer.getCountLocked(which);
                final Timer bgTimer = timer.getSubTimer();
                final long bgTime = bgTimer != null ?
                        bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : -1;
                final int bgCount = bgTimer != null ? bgTimer.getCountLocked(which) : -1;
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Sync ");
                sb.append(syncs.keyAt(isy));
                sb.append(": ");
                if (totalTime != 0) {
                    formatTimeMs(sb, totalTime);
                    sb.append("realtime (");
                    sb.append(count);
                    sb.append(" times)");
                    if (bgTime > 0) {
                        sb.append(", ");
                        formatTimeMs(sb, bgTime);
                        sb.append("background (");
                        sb.append(bgCount);
                        sb.append(" times)");
                    }
                } else {
                    sb.append("(not used)");
                }
                pw.println(sb.toString());
                uidActivity = true;
            }

            final ArrayMap<String, ? extends Timer> jobs = u.getJobStats();
            for (int ij=jobs.size()-1; ij>=0; ij--) {
                final Timer timer = jobs.valueAt(ij);
                // Convert from microseconds to milliseconds with rounding
                final long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                final int count = timer.getCountLocked(which);
                final Timer bgTimer = timer.getSubTimer();
                final long bgTime = bgTimer != null ?
                        bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : -1;
                final int bgCount = bgTimer != null ? bgTimer.getCountLocked(which) : -1;
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Job ");
                sb.append(jobs.keyAt(ij));
                sb.append(": ");
                if (totalTime != 0) {
                    formatTimeMs(sb, totalTime);
                    sb.append("realtime (");
                    sb.append(count);
                    sb.append(" times)");
                    if (bgTime > 0) {
                        sb.append(", ");
                        formatTimeMs(sb, bgTime);
                        sb.append("background (");
                        sb.append(bgCount);
                        sb.append(" times)");
                    }
                } else {
                    sb.append("(not used)");
                }
                pw.println(sb.toString());
                uidActivity = true;
            }

            final ArrayMap<String, SparseIntArray> completions = u.getJobCompletionStats();
            for (int ic=completions.size()-1; ic>=0; ic--) {
                SparseIntArray types = completions.valueAt(ic);
                if (types != null) {
                    pw.print(prefix);
                    pw.print("    Job Completions ");
                    pw.print(completions.keyAt(ic));
                    pw.print(":");
                    for (int it=0; it<types.size(); it++) {
                        pw.print(" ");
                        pw.print(JobParameters.getLegacyReasonCodeDescription(types.keyAt(it)));
                        pw.print("(");
                        pw.print(types.valueAt(it));
                        pw.print("x)");
                    }
                    pw.println();
                }
            }

            u.getDeferredJobsLineLocked(sb, which);
            if (sb.length() > 0) {
                pw.print("    Jobs deferred on launch "); pw.println(sb.toString());
            }

            uidActivity |= printTimer(pw, sb, u.getFlashlightTurnedOnTimer(), rawRealtime, which,
                    prefix, "Flashlight");
            uidActivity |= printTimer(pw, sb, u.getCameraTurnedOnTimer(), rawRealtime, which,
                    prefix, "Camera");
            uidActivity |= printTimer(pw, sb, u.getVideoTurnedOnTimer(), rawRealtime, which,
                    prefix, "Video");
            uidActivity |= printTimer(pw, sb, u.getAudioTurnedOnTimer(), rawRealtime, which,
                    prefix, "Audio");

            final SparseArray<? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            final int NSE = sensors.size();
            for (int ise=0; ise<NSE; ise++) {
                final Uid.Sensor se = sensors.valueAt(ise);
                final int sensorNumber = sensors.keyAt(ise);
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Sensor ");
                int handle = se.getHandle();
                if (handle == Uid.Sensor.GPS) {
                    sb.append("GPS");
                } else {
                    sb.append(handle);
                }
                sb.append(": ");

                final Timer timer = se.getSensorTime();
                if (timer != null) {
                    // Convert from microseconds to milliseconds with rounding
                    final long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500)
                            / 1000;
                    final int count = timer.getCountLocked(which);
                    final Timer bgTimer = se.getSensorBackgroundTime();
                    final int bgCount = bgTimer != null ? bgTimer.getCountLocked(which) : 0;
                    // 'actualTime' are unpooled and always since reset (regardless of 'which')
                    final long actualTime = timer.getTotalDurationMsLocked(rawRealtimeMs);
                    final long bgActualTime = bgTimer != null ?
                            bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;

                    //timer.logState();
                    if (totalTime != 0) {
                        if (actualTime != totalTime) {
                            formatTimeMs(sb, totalTime);
                            sb.append("blamed realtime, ");
                        }

                        formatTimeMs(sb, actualTime); // since reset, regardless of 'which'
                        sb.append("realtime (");
                        sb.append(count);
                        sb.append(" times)");

                        if (bgActualTime != 0 || bgCount > 0) {
                            sb.append(", ");
                            formatTimeMs(sb, bgActualTime); // since reset, regardless of 'which'
                            sb.append("background (");
                            sb.append(bgCount);
                            sb.append(" times)");
                        }
                    } else {
                        sb.append("(not used)");
                    }
                } else {
                    sb.append("(not used)");
                }

                pw.println(sb.toString());
                uidActivity = true;
            }

            uidActivity |= printTimer(pw, sb, u.getVibratorOnTimer(), rawRealtime, which, prefix,
                    "Vibrator");
            uidActivity |= printTimer(pw, sb, u.getForegroundActivityTimer(), rawRealtime, which,
                    prefix, "Foreground activities");
            uidActivity |= printTimer(pw, sb, u.getForegroundServiceTimer(), rawRealtime, which,
                    prefix, "Foreground services");

            long totalStateTime = 0;
            for (int ips=0; ips<Uid.NUM_PROCESS_STATE; ips++) {
                long time = u.getProcessStateTime(ips, rawRealtime, which);
                if (time > 0) {
                    totalStateTime += time;
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    ");
                    sb.append(Uid.PROCESS_STATE_NAMES[ips]);
                    sb.append(" for: ");
                    formatTimeMs(sb, (time + 500) / 1000);
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }
            if (totalStateTime > 0) {
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Total running: ");
                formatTimeMs(sb, (totalStateTime + 500) / 1000);
                pw.println(sb.toString());
            }

            final long userCpuTimeUs = u.getUserCpuTimeUs(which);
            final long systemCpuTimeUs = u.getSystemCpuTimeUs(which);
            if (userCpuTimeUs > 0 || systemCpuTimeUs > 0) {
                sb.setLength(0);
                sb.append(prefix);
                sb.append("    Total cpu time: u=");
                formatTimeMs(sb, userCpuTimeUs / 1000);
                sb.append("s=");
                formatTimeMs(sb, systemCpuTimeUs / 1000);
                pw.println(sb.toString());
            }

            final long[] cpuFreqTimes = u.getCpuFreqTimes(which);
            if (cpuFreqTimes != null) {
                sb.setLength(0);
                sb.append("    Total cpu time per freq:");
                for (int i = 0; i < cpuFreqTimes.length; ++i) {
                    sb.append(' ').append(cpuFreqTimes[i]);
                }
                pw.println(sb.toString());
            }
            final long[] screenOffCpuFreqTimes = u.getScreenOffCpuFreqTimes(which);
            if (screenOffCpuFreqTimes != null) {
                sb.setLength(0);
                sb.append("    Total screen-off cpu time per freq:");
                for (int i = 0; i < screenOffCpuFreqTimes.length; ++i) {
                    sb.append(' ').append(screenOffCpuFreqTimes[i]);
                }
                pw.println(sb.toString());
            }

            for (int procState = 0; procState < Uid.NUM_PROCESS_STATE; ++procState) {
                final long[] cpuTimes = u.getCpuFreqTimes(which, procState);
                if (cpuTimes != null) {
                    sb.setLength(0);
                    sb.append("    Cpu times per freq at state ")
                            .append(Uid.PROCESS_STATE_NAMES[procState]).append(':');
                    for (int i = 0; i < cpuTimes.length; ++i) {
                        sb.append(" " + cpuTimes[i]);
                    }
                    pw.println(sb.toString());
                }

                final long[] screenOffCpuTimes = u.getScreenOffCpuFreqTimes(which, procState);
                if (screenOffCpuTimes != null) {
                    sb.setLength(0);
                    sb.append("   Screen-off cpu times per freq at state ")
                            .append(Uid.PROCESS_STATE_NAMES[procState]).append(':');
                    for (int i = 0; i < screenOffCpuTimes.length; ++i) {
                        sb.append(" " + screenOffCpuTimes[i]);
                    }
                    pw.println(sb.toString());
                }
            }

            final ArrayMap<String, ? extends BatteryStats.Uid.Proc> processStats
                    = u.getProcessStats();
            for (int ipr=processStats.size()-1; ipr>=0; ipr--) {
                final Uid.Proc ps = processStats.valueAt(ipr);
                long userTime;
                long systemTime;
                long foregroundTime;
                int starts;
                int numExcessive;

                userTime = ps.getUserTime(which);
                systemTime = ps.getSystemTime(which);
                foregroundTime = ps.getForegroundTime(which);
                starts = ps.getStarts(which);
                final int numCrashes = ps.getNumCrashes(which);
                final int numAnrs = ps.getNumAnrs(which);
                numExcessive = which == STATS_SINCE_CHARGED
                        ? ps.countExcessivePowers() : 0;

                if (userTime != 0 || systemTime != 0 || foregroundTime != 0 || starts != 0
                        || numExcessive != 0 || numCrashes != 0 || numAnrs != 0) {
                    sb.setLength(0);
                    sb.append(prefix); sb.append("    Proc ");
                            sb.append(processStats.keyAt(ipr)); sb.append(":\n");
                    sb.append(prefix); sb.append("      CPU: ");
                            formatTimeMs(sb, userTime); sb.append("usr + ");
                            formatTimeMs(sb, systemTime); sb.append("krn ; ");
                            formatTimeMs(sb, foregroundTime); sb.append("fg");
                    if (starts != 0 || numCrashes != 0 || numAnrs != 0) {
                        sb.append("\n"); sb.append(prefix); sb.append("      ");
                        boolean hasOne = false;
                        if (starts != 0) {
                            hasOne = true;
                            sb.append(starts); sb.append(" starts");
                        }
                        if (numCrashes != 0) {
                            if (hasOne) {
                                sb.append(", ");
                            }
                            hasOne = true;
                            sb.append(numCrashes); sb.append(" crashes");
                        }
                        if (numAnrs != 0) {
                            if (hasOne) {
                                sb.append(", ");
                            }
                            sb.append(numAnrs); sb.append(" anrs");
                        }
                    }
                    pw.println(sb.toString());
                    for (int e=0; e<numExcessive; e++) {
                        Uid.Proc.ExcessivePower ew = ps.getExcessivePower(e);
                        if (ew != null) {
                            pw.print(prefix); pw.print("      * Killed for ");
                                    if (ew.type == Uid.Proc.ExcessivePower.TYPE_CPU) {
                                        pw.print("cpu");
                                    } else {
                                        pw.print("unknown");
                                    }
                                    pw.print(" use: ");
                                    TimeUtils.formatDuration(ew.usedTime, pw);
                                    pw.print(" over ");
                                    TimeUtils.formatDuration(ew.overTime, pw);
                                    if (ew.overTime != 0) {
                                        pw.print(" (");
                                        pw.print((ew.usedTime*100)/ew.overTime);
                                        pw.println("%)");
                                    }
                        }
                    }
                    uidActivity = true;
                }
            }

            final ArrayMap<String, ? extends BatteryStats.Uid.Pkg> packageStats
                    = u.getPackageStats();
            for (int ipkg=packageStats.size()-1; ipkg>=0; ipkg--) {
                pw.print(prefix); pw.print("    Apk "); pw.print(packageStats.keyAt(ipkg));
                pw.println(":");
                boolean apkActivity = false;
                final Uid.Pkg ps = packageStats.valueAt(ipkg);
                final ArrayMap<String, ? extends Counter> alarms = ps.getWakeupAlarmStats();
                for (int iwa=alarms.size()-1; iwa>=0; iwa--) {
                    pw.print(prefix); pw.print("      Wakeup alarm ");
                            pw.print(alarms.keyAt(iwa)); pw.print(": ");
                            pw.print(alarms.valueAt(iwa).getCountLocked(which));
                            pw.println(" times");
                    apkActivity = true;
                }
                final ArrayMap<String, ? extends  Uid.Pkg.Serv> serviceStats = ps.getServiceStats();
                for (int isvc=serviceStats.size()-1; isvc>=0; isvc--) {
                    final BatteryStats.Uid.Pkg.Serv ss = serviceStats.valueAt(isvc);
                    final long startTime = ss.getStartTime(batteryUptime, which);
                    final int starts = ss.getStarts(which);
                    final int launches = ss.getLaunches(which);
                    if (startTime != 0 || starts != 0 || launches != 0) {
                        sb.setLength(0);
                        sb.append(prefix); sb.append("      Service ");
                                sb.append(serviceStats.keyAt(isvc)); sb.append(":\n");
                        sb.append(prefix); sb.append("        Created for: ");
                                formatTimeMs(sb, startTime / 1000);
                                sb.append("uptime\n");
                        sb.append(prefix); sb.append("        Starts: ");
                                sb.append(starts);
                                sb.append(", launches: "); sb.append(launches);
                        pw.println(sb.toString());
                        apkActivity = true;
                    }
                }
                if (!apkActivity) {
                    pw.print(prefix); pw.println("      (nothing executed)");
                }
                uidActivity = true;
            }
            if (!uidActivity) {
                pw.print(prefix); pw.println("    (nothing executed)");
            }
        }
    }

    static void printBitDescriptions(StringBuilder sb, int oldval, int newval,
            HistoryTag wakelockTag, BitDescription[] descriptions, boolean longNames) {
        int diff = oldval ^ newval;
        if (diff == 0) return;
        boolean didWake = false;
        for (int i=0; i<descriptions.length; i++) {
            BitDescription bd = descriptions[i];
            if ((diff&bd.mask) != 0) {
                sb.append(longNames ? " " : ",");
                if (bd.shift < 0) {
                    sb.append((newval & bd.mask) != 0 ? "+" : "-");
                    sb.append(longNames ? bd.name : bd.shortName);
                    if (bd.mask == HistoryItem.STATE_WAKE_LOCK_FLAG && wakelockTag != null) {
                        didWake = true;
                        sb.append("=");
                        if (longNames) {
                            UserHandle.formatUid(sb, wakelockTag.uid);
                            sb.append(":\"");
                            sb.append(wakelockTag.string);
                            sb.append("\"");
                        } else {
                            sb.append(wakelockTag.poolIdx);
                        }
                    }
                } else {
                    sb.append(longNames ? bd.name : bd.shortName);
                    sb.append("=");
                    int val = (newval&bd.mask)>>bd.shift;
                    if (bd.values != null && val >= 0 && val < bd.values.length) {
                        sb.append(longNames ? bd.values[val] : bd.shortValues[val]);
                    } else {
                        sb.append(val);
                    }
                }
            }
        }
        if (!didWake && wakelockTag != null) {
            sb.append(longNames ? " wake_lock=" : ",w=");
            if (longNames) {
                UserHandle.formatUid(sb, wakelockTag.uid);
                sb.append(":\"");
                sb.append(wakelockTag.string);
                sb.append("\"");
            } else {
                sb.append(wakelockTag.poolIdx);
            }
        }
    }

    public void prepareForDumpLocked() {
        // We don't need to require subclasses implement this.
    }

    public static class HistoryPrinter {
        int oldState = 0;
        int oldState2 = 0;
        int oldLevel = -1;
        int oldStatus = -1;
        int oldHealth = -1;
        int oldPlug = -1;
        int oldTemp = -1;
        int oldVolt = -1;
        int oldChargeMAh = -1;
        double oldModemRailChargeMah = -1;
        double oldWifiRailChargeMah = -1;
        long lastTime = -1;

        void reset() {
            oldState = oldState2 = 0;
            oldLevel = -1;
            oldStatus = -1;
            oldHealth = -1;
            oldPlug = -1;
            oldTemp = -1;
            oldVolt = -1;
            oldChargeMAh = -1;
            oldModemRailChargeMah = -1;
            oldWifiRailChargeMah = -1;
        }

        public void printNextItem(PrintWriter pw, HistoryItem rec, long baseTime, boolean checkin,
                boolean verbose) {
            pw.print(printNextItem(rec, baseTime, checkin, verbose));
        }

        /** Print the next history item to proto. */
        public void printNextItem(ProtoOutputStream proto, HistoryItem rec, long baseTime,
                boolean verbose) {
            String item = printNextItem(rec, baseTime, true, verbose);
            for (String line : item.split("\n")) {
                proto.write(BatteryStatsServiceDumpHistoryProto.CSV_LINES, line);
            }
        }

        private String printNextItem(HistoryItem rec, long baseTime, boolean checkin,
                boolean verbose) {
            StringBuilder item = new StringBuilder();
            if (!checkin) {
                item.append("  ");
                TimeUtils.formatDuration(
                        rec.time - baseTime, item, TimeUtils.HUNDRED_DAY_FIELD_LEN);
                item.append(" (");
                item.append(rec.numReadInts);
                item.append(") ");
            } else {
                item.append(BATTERY_STATS_CHECKIN_VERSION); item.append(',');
                item.append(HISTORY_DATA); item.append(',');
                if (lastTime < 0) {
                    item.append(rec.time - baseTime);
                } else {
                    item.append(rec.time - lastTime);
                }
                lastTime = rec.time;
            }
            if (rec.cmd == HistoryItem.CMD_START) {
                if (checkin) {
                    item.append(":");
                }
                item.append("START\n");
                reset();
            } else if (rec.cmd == HistoryItem.CMD_CURRENT_TIME
                    || rec.cmd == HistoryItem.CMD_RESET) {
                if (checkin) {
                    item.append(":");
                }
                if (rec.cmd == HistoryItem.CMD_RESET) {
                    item.append("RESET:");
                    reset();
                }
                item.append("TIME:");
                if (checkin) {
                    item.append(rec.currentTime);
                    item.append("\n");
                } else {
                    item.append(" ");
                    item.append(DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                            rec.currentTime).toString());
                    item.append("\n");
                }
            } else if (rec.cmd == HistoryItem.CMD_SHUTDOWN) {
                if (checkin) {
                    item.append(":");
                }
                item.append("SHUTDOWN\n");
            } else if (rec.cmd == HistoryItem.CMD_OVERFLOW) {
                if (checkin) {
                    item.append(":");
                }
                item.append("*OVERFLOW*\n");
            } else {
                if (!checkin) {
                    if (rec.batteryLevel < 10) item.append("00");
                    else if (rec.batteryLevel < 100) item.append("0");
                    item.append(rec.batteryLevel);
                    if (verbose) {
                        item.append(" ");
                        if (rec.states < 0) ;
                        else if (rec.states < 0x10) item.append("0000000");
                        else if (rec.states < 0x100) item.append("000000");
                        else if (rec.states < 0x1000) item.append("00000");
                        else if (rec.states < 0x10000) item.append("0000");
                        else if (rec.states < 0x100000) item.append("000");
                        else if (rec.states < 0x1000000) item.append("00");
                        else if (rec.states < 0x10000000) item.append("0");
                        item.append(Integer.toHexString(rec.states));
                    }
                } else {
                    if (oldLevel != rec.batteryLevel) {
                        oldLevel = rec.batteryLevel;
                        item.append(",Bl="); item.append(rec.batteryLevel);
                    }
                }
                if (oldStatus != rec.batteryStatus) {
                    oldStatus = rec.batteryStatus;
                    item.append(checkin ? ",Bs=" : " status=");
                    switch (oldStatus) {
                        case BatteryManager.BATTERY_STATUS_UNKNOWN:
                            item.append(checkin ? "?" : "unknown");
                            break;
                        case BatteryManager.BATTERY_STATUS_CHARGING:
                            item.append(checkin ? "c" : "charging");
                            break;
                        case BatteryManager.BATTERY_STATUS_DISCHARGING:
                            item.append(checkin ? "d" : "discharging");
                            break;
                        case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                            item.append(checkin ? "n" : "not-charging");
                            break;
                        case BatteryManager.BATTERY_STATUS_FULL:
                            item.append(checkin ? "f" : "full");
                            break;
                        default:
                            item.append(oldStatus);
                            break;
                    }
                }
                if (oldHealth != rec.batteryHealth) {
                    oldHealth = rec.batteryHealth;
                    item.append(checkin ? ",Bh=" : " health=");
                    switch (oldHealth) {
                        case BatteryManager.BATTERY_HEALTH_UNKNOWN:
                            item.append(checkin ? "?" : "unknown");
                            break;
                        case BatteryManager.BATTERY_HEALTH_GOOD:
                            item.append(checkin ? "g" : "good");
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                            item.append(checkin ? "h" : "overheat");
                            break;
                        case BatteryManager.BATTERY_HEALTH_DEAD:
                            item.append(checkin ? "d" : "dead");
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                            item.append(checkin ? "v" : "over-voltage");
                            break;
                        case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                            item.append(checkin ? "f" : "failure");
                            break;
                        case BatteryManager.BATTERY_HEALTH_COLD:
                            item.append(checkin ? "c" : "cold");
                            break;
                        default:
                            item.append(oldHealth);
                            break;
                    }
                }
                if (oldPlug != rec.batteryPlugType) {
                    oldPlug = rec.batteryPlugType;
                    item.append(checkin ? ",Bp=" : " plug=");
                    switch (oldPlug) {
                        case 0:
                            item.append(checkin ? "n" : "none");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_AC:
                            item.append(checkin ? "a" : "ac");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_USB:
                            item.append(checkin ? "u" : "usb");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                            item.append(checkin ? "w" : "wireless");
                            break;
                        default:
                            item.append(oldPlug);
                            break;
                    }
                }
                if (oldTemp != rec.batteryTemperature) {
                    oldTemp = rec.batteryTemperature;
                    item.append(checkin ? ",Bt=" : " temp=");
                    item.append(oldTemp);
                }
                if (oldVolt != rec.batteryVoltage) {
                    oldVolt = rec.batteryVoltage;
                    item.append(checkin ? ",Bv=" : " volt=");
                    item.append(oldVolt);
                }
                final int chargeMAh = rec.batteryChargeUah / 1000;
                if (oldChargeMAh != chargeMAh) {
                    oldChargeMAh = chargeMAh;
                    item.append(checkin ? ",Bcc=" : " charge=");
                    item.append(oldChargeMAh);
                }
                if (oldModemRailChargeMah != rec.modemRailChargeMah) {
                    oldModemRailChargeMah = rec.modemRailChargeMah;
                    item.append(checkin ? ",Mrc=" : " modemRailChargemAh=");
                    item.append(new DecimalFormat("#.##").format(oldModemRailChargeMah));
                }
                if (oldWifiRailChargeMah != rec.wifiRailChargeMah) {
                    oldWifiRailChargeMah = rec.wifiRailChargeMah;
                    item.append(checkin ? ",Wrc=" : " wifiRailChargemAh=");
                    item.append(new DecimalFormat("#.##").format(oldWifiRailChargeMah));
                }
                printBitDescriptions(item, oldState, rec.states, rec.wakelockTag,
                        HISTORY_STATE_DESCRIPTIONS, !checkin);
                printBitDescriptions(item, oldState2, rec.states2, null,
                        HISTORY_STATE2_DESCRIPTIONS, !checkin);
                if (rec.wakeReasonTag != null) {
                    if (checkin) {
                        item.append(",wr=");
                        item.append(rec.wakeReasonTag.poolIdx);
                    } else {
                        item.append(" wake_reason=");
                        item.append(rec.wakeReasonTag.uid);
                        item.append(":\"");
                        item.append(rec.wakeReasonTag.string);
                        item.append("\"");
                    }
                }
                if (rec.eventCode != HistoryItem.EVENT_NONE) {
                    item.append(checkin ? "," : " ");
                    if ((rec.eventCode&HistoryItem.EVENT_FLAG_START) != 0) {
                        item.append("+");
                    } else if ((rec.eventCode&HistoryItem.EVENT_FLAG_FINISH) != 0) {
                        item.append("-");
                    }
                    String[] eventNames = checkin ? HISTORY_EVENT_CHECKIN_NAMES
                            : HISTORY_EVENT_NAMES;
                    int idx = rec.eventCode & ~(HistoryItem.EVENT_FLAG_START
                            | HistoryItem.EVENT_FLAG_FINISH);
                    if (idx >= 0 && idx < eventNames.length) {
                        item.append(eventNames[idx]);
                    } else {
                        item.append(checkin ? "Ev" : "event");
                        item.append(idx);
                    }
                    item.append("=");
                    if (checkin) {
                        item.append(rec.eventTag.poolIdx);
                    } else {
                        item.append(HISTORY_EVENT_INT_FORMATTERS[idx]
                                .applyAsString(rec.eventTag.uid));
                        item.append(":\"");
                        item.append(rec.eventTag.string);
                        item.append("\"");
                    }
                }
                item.append("\n");
                if (rec.stepDetails != null) {
                    if (!checkin) {
                        item.append("                 Details: cpu=");
                        item.append(rec.stepDetails.userTime);
                        item.append("u+");
                        item.append(rec.stepDetails.systemTime);
                        item.append("s");
                        if (rec.stepDetails.appCpuUid1 >= 0) {
                            item.append(" (");
                            printStepCpuUidDetails(item, rec.stepDetails.appCpuUid1,
                                    rec.stepDetails.appCpuUTime1, rec.stepDetails.appCpuSTime1);
                            if (rec.stepDetails.appCpuUid2 >= 0) {
                                item.append(", ");
                                printStepCpuUidDetails(item, rec.stepDetails.appCpuUid2,
                                        rec.stepDetails.appCpuUTime2, rec.stepDetails.appCpuSTime2);
                            }
                            if (rec.stepDetails.appCpuUid3 >= 0) {
                                item.append(", ");
                                printStepCpuUidDetails(item, rec.stepDetails.appCpuUid3,
                                        rec.stepDetails.appCpuUTime3, rec.stepDetails.appCpuSTime3);
                            }
                            item.append(')');
                        }
                        item.append("\n");
                        item.append("                          /proc/stat=");
                        item.append(rec.stepDetails.statUserTime);
                        item.append(" usr, ");
                        item.append(rec.stepDetails.statSystemTime);
                        item.append(" sys, ");
                        item.append(rec.stepDetails.statIOWaitTime);
                        item.append(" io, ");
                        item.append(rec.stepDetails.statIrqTime);
                        item.append(" irq, ");
                        item.append(rec.stepDetails.statSoftIrqTime);
                        item.append(" sirq, ");
                        item.append(rec.stepDetails.statIdlTime);
                        item.append(" idle");
                        int totalRun = rec.stepDetails.statUserTime + rec.stepDetails.statSystemTime
                                + rec.stepDetails.statIOWaitTime + rec.stepDetails.statIrqTime
                                + rec.stepDetails.statSoftIrqTime;
                        int total = totalRun + rec.stepDetails.statIdlTime;
                        if (total > 0) {
                            item.append(" (");
                            float perc = ((float)totalRun) / ((float)total) * 100;
                            item.append(String.format("%.1f%%", perc));
                            item.append(" of ");
                            StringBuilder sb = new StringBuilder(64);
                            formatTimeMsNoSpace(sb, total*10);
                            item.append(sb);
                            item.append(")");
                        }

                        item.append(", SubsystemPowerState ");
                        item.append(rec.stepDetails.statSubsystemPowerState);
                        item.append("\n");
                    } else {
                        item.append(BATTERY_STATS_CHECKIN_VERSION); item.append(',');
                        item.append(HISTORY_DATA); item.append(",0,Dcpu=");
                        item.append(rec.stepDetails.userTime);
                        item.append(":");
                        item.append(rec.stepDetails.systemTime);
                        if (rec.stepDetails.appCpuUid1 >= 0) {
                            printStepCpuUidCheckinDetails(item, rec.stepDetails.appCpuUid1,
                                    rec.stepDetails.appCpuUTime1, rec.stepDetails.appCpuSTime1);
                            if (rec.stepDetails.appCpuUid2 >= 0) {
                                printStepCpuUidCheckinDetails(item, rec.stepDetails.appCpuUid2,
                                        rec.stepDetails.appCpuUTime2, rec.stepDetails.appCpuSTime2);
                            }
                            if (rec.stepDetails.appCpuUid3 >= 0) {
                                printStepCpuUidCheckinDetails(item, rec.stepDetails.appCpuUid3,
                                        rec.stepDetails.appCpuUTime3, rec.stepDetails.appCpuSTime3);
                            }
                        }
                        item.append("\n");
                        item.append(BATTERY_STATS_CHECKIN_VERSION); item.append(',');
                        item.append(HISTORY_DATA); item.append(",0,Dpst=");
                        item.append(rec.stepDetails.statUserTime);
                        item.append(',');
                        item.append(rec.stepDetails.statSystemTime);
                        item.append(',');
                        item.append(rec.stepDetails.statIOWaitTime);
                        item.append(',');
                        item.append(rec.stepDetails.statIrqTime);
                        item.append(',');
                        item.append(rec.stepDetails.statSoftIrqTime);
                        item.append(',');
                        item.append(rec.stepDetails.statIdlTime);
                        item.append(',');

                        if (rec.stepDetails.statSubsystemPowerState != null) {
                            item.append(rec.stepDetails.statSubsystemPowerState);
                        }
                        item.append("\n");
                    }
                }
                oldState = rec.states;
                oldState2 = rec.states2;
                // Clear High Tx Power Flag for volta positioning
                if ((rec.states2 & HistoryItem.STATE2_CELLULAR_HIGH_TX_POWER_FLAG) != 0) {
                    rec.states2 &= ~HistoryItem.STATE2_CELLULAR_HIGH_TX_POWER_FLAG;
                }
            }

            return item.toString();
        }

        private void printStepCpuUidDetails(StringBuilder sb, int uid, int utime, int stime) {
            UserHandle.formatUid(sb, uid);
            sb.append("=");
            sb.append(utime);
            sb.append("u+");
            sb.append(stime);
            sb.append("s");
        }

        private void printStepCpuUidCheckinDetails(StringBuilder sb, int uid, int utime,
                int stime) {
            sb.append('/');
            sb.append(uid);
            sb.append(":");
            sb.append(utime);
            sb.append(":");
            sb.append(stime);
        }
    }

    private void printSizeValue(PrintWriter pw, long size) {
        float result = size;
        String suffix = "";
        if (result >= 10*1024) {
            suffix = "KB";
            result = result / 1024;
        }
        if (result >= 10*1024) {
            suffix = "MB";
            result = result / 1024;
        }
        if (result >= 10*1024) {
            suffix = "GB";
            result = result / 1024;
        }
        if (result >= 10*1024) {
            suffix = "TB";
            result = result / 1024;
        }
        if (result >= 10*1024) {
            suffix = "PB";
            result = result / 1024;
        }
        pw.print((int)result);
        pw.print(suffix);
    }

    private static boolean dumpTimeEstimate(PrintWriter pw, String label1, String label2,
            String label3, long estimatedTime) {
        if (estimatedTime < 0) {
            return false;
        }
        pw.print(label1);
        pw.print(label2);
        pw.print(label3);
        StringBuilder sb = new StringBuilder(64);
        formatTimeMs(sb, estimatedTime);
        pw.print(sb);
        pw.println();
        return true;
    }

    private static boolean dumpDurationSteps(PrintWriter pw, String prefix, String header,
            LevelStepTracker steps, boolean checkin) {
        if (steps == null) {
            return false;
        }
        int count = steps.mNumStepDurations;
        if (count <= 0) {
            return false;
        }
        if (!checkin) {
            pw.println(header);
        }
        String[] lineArgs = new String[5];
        for (int i=0; i<count; i++) {
            long duration = steps.getDurationAt(i);
            int level = steps.getLevelAt(i);
            long initMode = steps.getInitModeAt(i);
            long modMode = steps.getModModeAt(i);
            if (checkin) {
                lineArgs[0] = Long.toString(duration);
                lineArgs[1] = Integer.toString(level);
                if ((modMode&STEP_LEVEL_MODE_SCREEN_STATE) == 0) {
                    switch ((int)(initMode&STEP_LEVEL_MODE_SCREEN_STATE) + 1) {
                        case Display.STATE_OFF: lineArgs[2] = "s-"; break;
                        case Display.STATE_ON: lineArgs[2] = "s+"; break;
                        case Display.STATE_DOZE: lineArgs[2] = "sd"; break;
                        case Display.STATE_DOZE_SUSPEND: lineArgs[2] = "sds"; break;
                        default: lineArgs[2] = "?"; break;
                    }
                } else {
                    lineArgs[2] = "";
                }
                if ((modMode&STEP_LEVEL_MODE_POWER_SAVE) == 0) {
                    lineArgs[3] = (initMode&STEP_LEVEL_MODE_POWER_SAVE) != 0 ? "p+" : "p-";
                } else {
                    lineArgs[3] = "";
                }
                if ((modMode&STEP_LEVEL_MODE_DEVICE_IDLE) == 0) {
                    lineArgs[4] = (initMode&STEP_LEVEL_MODE_DEVICE_IDLE) != 0 ? "i+" : "i-";
                } else {
                    lineArgs[4] = "";
                }
                dumpLine(pw, 0 /* uid */, "i" /* category */, header, (Object[])lineArgs);
            } else {
                pw.print(prefix);
                pw.print("#"); pw.print(i); pw.print(": ");
                TimeUtils.formatDuration(duration, pw);
                pw.print(" to "); pw.print(level);
                boolean haveModes = false;
                if ((modMode&STEP_LEVEL_MODE_SCREEN_STATE) == 0) {
                    pw.print(" (");
                    switch ((int)(initMode&STEP_LEVEL_MODE_SCREEN_STATE) + 1) {
                        case Display.STATE_OFF: pw.print("screen-off"); break;
                        case Display.STATE_ON: pw.print("screen-on"); break;
                        case Display.STATE_DOZE: pw.print("screen-doze"); break;
                        case Display.STATE_DOZE_SUSPEND: pw.print("screen-doze-suspend"); break;
                        default: pw.print("screen-?"); break;
                    }
                    haveModes = true;
                }
                if ((modMode&STEP_LEVEL_MODE_POWER_SAVE) == 0) {
                    pw.print(haveModes ? ", " : " (");
                    pw.print((initMode&STEP_LEVEL_MODE_POWER_SAVE) != 0
                            ? "power-save-on" : "power-save-off");
                    haveModes = true;
                }
                if ((modMode&STEP_LEVEL_MODE_DEVICE_IDLE) == 0) {
                    pw.print(haveModes ? ", " : " (");
                    pw.print((initMode&STEP_LEVEL_MODE_DEVICE_IDLE) != 0
                            ? "device-idle-on" : "device-idle-off");
                    haveModes = true;
                }
                if (haveModes) {
                    pw.print(")");
                }
                pw.println();
            }
        }
        return true;
    }

    private static void dumpDurationSteps(ProtoOutputStream proto, long fieldId,
            LevelStepTracker steps) {
        if (steps == null) {
            return;
        }
        int count = steps.mNumStepDurations;
        for (int i = 0; i < count; ++i) {
            long token = proto.start(fieldId);
            proto.write(SystemProto.BatteryLevelStep.DURATION_MS, steps.getDurationAt(i));
            proto.write(SystemProto.BatteryLevelStep.LEVEL, steps.getLevelAt(i));

            final long initMode = steps.getInitModeAt(i);
            final long modMode = steps.getModModeAt(i);

            int ds = SystemProto.BatteryLevelStep.DS_MIXED;
            if ((modMode & STEP_LEVEL_MODE_SCREEN_STATE) == 0) {
                switch ((int) (initMode & STEP_LEVEL_MODE_SCREEN_STATE) + 1) {
                    case Display.STATE_OFF:
                        ds = SystemProto.BatteryLevelStep.DS_OFF;
                        break;
                    case Display.STATE_ON:
                        ds = SystemProto.BatteryLevelStep.DS_ON;
                        break;
                    case Display.STATE_DOZE:
                        ds = SystemProto.BatteryLevelStep.DS_DOZE;
                        break;
                    case Display.STATE_DOZE_SUSPEND:
                        ds = SystemProto.BatteryLevelStep.DS_DOZE_SUSPEND;
                        break;
                    default:
                        ds = SystemProto.BatteryLevelStep.DS_ERROR;
                        break;
                }
            }
            proto.write(SystemProto.BatteryLevelStep.DISPLAY_STATE, ds);

            int psm = SystemProto.BatteryLevelStep.PSM_MIXED;
            if ((modMode & STEP_LEVEL_MODE_POWER_SAVE) == 0) {
                psm = (initMode & STEP_LEVEL_MODE_POWER_SAVE) != 0
                    ? SystemProto.BatteryLevelStep.PSM_ON : SystemProto.BatteryLevelStep.PSM_OFF;
            }
            proto.write(SystemProto.BatteryLevelStep.POWER_SAVE_MODE, psm);

            int im = SystemProto.BatteryLevelStep.IM_MIXED;
            if ((modMode & STEP_LEVEL_MODE_DEVICE_IDLE) == 0) {
                im = (initMode & STEP_LEVEL_MODE_DEVICE_IDLE) != 0
                    ? SystemProto.BatteryLevelStep.IM_ON : SystemProto.BatteryLevelStep.IM_OFF;
            }
            proto.write(SystemProto.BatteryLevelStep.IDLE_MODE, im);

            proto.end(token);
        }
    }

    public static final int DUMP_CHARGED_ONLY = 1<<1;
    public static final int DUMP_DAILY_ONLY = 1<<2;
    public static final int DUMP_HISTORY_ONLY = 1<<3;
    public static final int DUMP_INCLUDE_HISTORY = 1<<4;
    public static final int DUMP_VERBOSE = 1<<5;
    public static final int DUMP_DEVICE_WIFI_ONLY = 1<<6;

    private void dumpHistoryLocked(PrintWriter pw, int flags, long histStart, boolean checkin) {
        final HistoryPrinter hprinter = new HistoryPrinter();
        final HistoryItem rec = new HistoryItem();
        long lastTime = -1;
        long baseTime = -1;
        boolean printed = false;
        HistoryEventTracker tracker = null;
        while (getNextHistoryLocked(rec)) {
            lastTime = rec.time;
            if (baseTime < 0) {
                baseTime = lastTime;
            }
            if (rec.time >= histStart) {
                if (histStart >= 0 && !printed) {
                    if (rec.cmd == HistoryItem.CMD_CURRENT_TIME
                            || rec.cmd == HistoryItem.CMD_RESET
                            || rec.cmd == HistoryItem.CMD_START
                            || rec.cmd == HistoryItem.CMD_SHUTDOWN) {
                        printed = true;
                        hprinter.printNextItem(pw, rec, baseTime, checkin,
                                (flags&DUMP_VERBOSE) != 0);
                        rec.cmd = HistoryItem.CMD_UPDATE;
                    } else if (rec.currentTime != 0) {
                        printed = true;
                        byte cmd = rec.cmd;
                        rec.cmd = HistoryItem.CMD_CURRENT_TIME;
                        hprinter.printNextItem(pw, rec, baseTime, checkin,
                                (flags&DUMP_VERBOSE) != 0);
                        rec.cmd = cmd;
                    }
                    if (tracker != null) {
                        if (rec.cmd != HistoryItem.CMD_UPDATE) {
                            hprinter.printNextItem(pw, rec, baseTime, checkin,
                                    (flags&DUMP_VERBOSE) != 0);
                            rec.cmd = HistoryItem.CMD_UPDATE;
                        }
                        int oldEventCode = rec.eventCode;
                        HistoryTag oldEventTag = rec.eventTag;
                        rec.eventTag = new HistoryTag();
                        for (int i=0; i<HistoryItem.EVENT_COUNT; i++) {
                            HashMap<String, SparseIntArray> active
                                    = tracker.getStateForEvent(i);
                            if (active == null) {
                                continue;
                            }
                            for (HashMap.Entry<String, SparseIntArray> ent
                                    : active.entrySet()) {
                                SparseIntArray uids = ent.getValue();
                                for (int j=0; j<uids.size(); j++) {
                                    rec.eventCode = i;
                                    rec.eventTag.string = ent.getKey();
                                    rec.eventTag.uid = uids.keyAt(j);
                                    rec.eventTag.poolIdx = uids.valueAt(j);
                                    hprinter.printNextItem(pw, rec, baseTime, checkin,
                                            (flags&DUMP_VERBOSE) != 0);
                                    rec.wakeReasonTag = null;
                                    rec.wakelockTag = null;
                                }
                            }
                        }
                        rec.eventCode = oldEventCode;
                        rec.eventTag = oldEventTag;
                        tracker = null;
                    }
                }
                hprinter.printNextItem(pw, rec, baseTime, checkin,
                        (flags&DUMP_VERBOSE) != 0);
            } else if (false && rec.eventCode != HistoryItem.EVENT_NONE) {
                // This is an attempt to aggregate the previous state and generate
                // fake events to reflect that state at the point where we start
                // printing real events.  It doesn't really work right, so is turned off.
                if (tracker == null) {
                    tracker = new HistoryEventTracker();
                }
                tracker.updateState(rec.eventCode, rec.eventTag.string,
                        rec.eventTag.uid, rec.eventTag.poolIdx);
            }
        }
        if (histStart >= 0) {
            commitCurrentHistoryBatchLocked();
            pw.print(checkin ? "NEXT: " : "  NEXT: "); pw.println(lastTime+1);
        }
    }

    private void dumpDailyLevelStepSummary(PrintWriter pw, String prefix, String label,
            LevelStepTracker steps, StringBuilder tmpSb, int[] tmpOutInt) {
        if (steps == null) {
            return;
        }
        long timeRemaining = steps.computeTimeEstimate(0, 0, tmpOutInt);
        if (timeRemaining >= 0) {
            pw.print(prefix); pw.print(label); pw.print(" total time: ");
            tmpSb.setLength(0);
            formatTimeMs(tmpSb, timeRemaining);
            pw.print(tmpSb);
            pw.print(" (from "); pw.print(tmpOutInt[0]);
            pw.println(" steps)");
        }
        for (int i=0; i< STEP_LEVEL_MODES_OF_INTEREST.length; i++) {
            long estimatedTime = steps.computeTimeEstimate(STEP_LEVEL_MODES_OF_INTEREST[i],
                    STEP_LEVEL_MODE_VALUES[i], tmpOutInt);
            if (estimatedTime > 0) {
                pw.print(prefix); pw.print(label); pw.print(" ");
                pw.print(STEP_LEVEL_MODE_LABELS[i]);
                pw.print(" time: ");
                tmpSb.setLength(0);
                formatTimeMs(tmpSb, estimatedTime);
                pw.print(tmpSb);
                pw.print(" (from "); pw.print(tmpOutInt[0]);
                pw.println(" steps)");
            }
        }
    }

    private void dumpDailyPackageChanges(PrintWriter pw, String prefix,
            ArrayList<PackageChange> changes) {
        if (changes == null) {
            return;
        }
        pw.print(prefix); pw.println("Package changes:");
        for (int i=0; i<changes.size(); i++) {
            PackageChange pc = changes.get(i);
            if (pc.mUpdate) {
                pw.print(prefix); pw.print("  Update "); pw.print(pc.mPackageName);
                pw.print(" vers="); pw.println(pc.mVersionCode);
            } else {
                pw.print(prefix); pw.print("  Uninstall "); pw.println(pc.mPackageName);
            }
        }
    }

    /**
     * Dumps a human-readable summary of the battery statistics to the given PrintWriter.
     *
     * @param pw a Printer to receive the dump output.
     */
    @SuppressWarnings("unused")
    public void dumpLocked(Context context, PrintWriter pw, int flags, int reqUid, long histStart) {
        prepareForDumpLocked();

        final boolean filtering = (flags
                & (DUMP_HISTORY_ONLY|DUMP_CHARGED_ONLY|DUMP_DAILY_ONLY)) != 0;

        if ((flags&DUMP_HISTORY_ONLY) != 0 || !filtering) {
            final long historyTotalSize = getHistoryTotalSize();
            final long historyUsedSize = getHistoryUsedSize();
            if (startIteratingHistoryLocked()) {
                try {
                    pw.print("Battery History (");
                    pw.print((100*historyUsedSize)/historyTotalSize);
                    pw.print("% used, ");
                    printSizeValue(pw, historyUsedSize);
                    pw.print(" used of ");
                    printSizeValue(pw, historyTotalSize);
                    pw.print(", ");
                    pw.print(getHistoryStringPoolSize());
                    pw.print(" strings using ");
                    printSizeValue(pw, getHistoryStringPoolBytes());
                    pw.println("):");
                    dumpHistoryLocked(pw, flags, histStart, false);
                    pw.println();
                } finally {
                    finishIteratingHistoryLocked();
                }
            }
        }

        if (filtering && (flags&(DUMP_CHARGED_ONLY|DUMP_DAILY_ONLY)) == 0) {
            return;
        }

        if (!filtering) {
            SparseArray<? extends Uid> uidStats = getUidStats();
            final int NU = uidStats.size();
            boolean didPid = false;
            long nowRealtime = SystemClock.elapsedRealtime();
            for (int i=0; i<NU; i++) {
                Uid uid = uidStats.valueAt(i);
                SparseArray<? extends Uid.Pid> pids = uid.getPidStats();
                if (pids != null) {
                    for (int j=0; j<pids.size(); j++) {
                        Uid.Pid pid = pids.valueAt(j);
                        if (!didPid) {
                            pw.println("Per-PID Stats:");
                            didPid = true;
                        }
                        long time = pid.mWakeSumMs + (pid.mWakeNesting > 0
                                ? (nowRealtime - pid.mWakeStartMs) : 0);
                        pw.print("  PID "); pw.print(pids.keyAt(j));
                                pw.print(" wake time: ");
                                TimeUtils.formatDuration(time, pw);
                                pw.println("");
                    }
                }
            }
            if (didPid) {
                pw.println();
            }
        }

        if (!filtering || (flags&DUMP_CHARGED_ONLY) != 0) {
            if (dumpDurationSteps(pw, "  ", "Discharge step durations:",
                    getDischargeLevelStepTracker(), false)) {
                long timeRemaining = computeBatteryTimeRemaining(
                    SystemClock.elapsedRealtime() * 1000);
                if (timeRemaining >= 0) {
                    pw.print("  Estimated discharge time remaining: ");
                    TimeUtils.formatDuration(timeRemaining / 1000, pw);
                    pw.println();
                }
                final LevelStepTracker steps = getDischargeLevelStepTracker();
                for (int i=0; i< STEP_LEVEL_MODES_OF_INTEREST.length; i++) {
                    dumpTimeEstimate(pw, "  Estimated ", STEP_LEVEL_MODE_LABELS[i], " time: ",
                            steps.computeTimeEstimate(STEP_LEVEL_MODES_OF_INTEREST[i],
                                    STEP_LEVEL_MODE_VALUES[i], null));
                }
                pw.println();
            }
            if (dumpDurationSteps(pw, "  ", "Charge step durations:",
                    getChargeLevelStepTracker(), false)) {
                long timeRemaining = computeChargeTimeRemaining(
                    SystemClock.elapsedRealtime() * 1000);
                if (timeRemaining >= 0) {
                    pw.print("  Estimated charge time remaining: ");
                    TimeUtils.formatDuration(timeRemaining / 1000, pw);
                    pw.println();
                }
                pw.println();
            }
        }
        if (!filtering || (flags & DUMP_DAILY_ONLY) != 0) {
            pw.println("Daily stats:");
            pw.print("  Current start time: ");
            pw.println(DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                    getCurrentDailyStartTime()).toString());
            pw.print("  Next min deadline: ");
            pw.println(DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                    getNextMinDailyDeadline()).toString());
            pw.print("  Next max deadline: ");
            pw.println(DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                    getNextMaxDailyDeadline()).toString());
            StringBuilder sb = new StringBuilder(64);
            int[] outInt = new int[1];
            LevelStepTracker dsteps = getDailyDischargeLevelStepTracker();
            LevelStepTracker csteps = getDailyChargeLevelStepTracker();
            ArrayList<PackageChange> pkgc = getDailyPackageChanges();
            if (dsteps.mNumStepDurations > 0 || csteps.mNumStepDurations > 0 || pkgc != null) {
                if ((flags&DUMP_DAILY_ONLY) != 0 || !filtering) {
                    if (dumpDurationSteps(pw, "    ", "  Current daily discharge step durations:",
                            dsteps, false)) {
                        dumpDailyLevelStepSummary(pw, "      ", "Discharge", dsteps,
                                sb, outInt);
                    }
                    if (dumpDurationSteps(pw, "    ", "  Current daily charge step durations:",
                            csteps, false)) {
                        dumpDailyLevelStepSummary(pw, "      ", "Charge", csteps,
                                sb, outInt);
                    }
                    dumpDailyPackageChanges(pw, "    ", pkgc);
                } else {
                    pw.println("  Current daily steps:");
                    dumpDailyLevelStepSummary(pw, "    ", "Discharge", dsteps,
                            sb, outInt);
                    dumpDailyLevelStepSummary(pw, "    ", "Charge", csteps,
                            sb, outInt);
                }
            }
            DailyItem dit;
            int curIndex = 0;
            while ((dit=getDailyItemLocked(curIndex)) != null) {
                curIndex++;
                if ((flags&DUMP_DAILY_ONLY) != 0) {
                    pw.println();
                }
                pw.print("  Daily from ");
                pw.print(DateFormat.format("yyyy-MM-dd-HH-mm-ss", dit.mStartTime).toString());
                pw.print(" to ");
                pw.print(DateFormat.format("yyyy-MM-dd-HH-mm-ss", dit.mEndTime).toString());
                pw.println(":");
                if ((flags&DUMP_DAILY_ONLY) != 0 || !filtering) {
                    if (dumpDurationSteps(pw, "      ",
                            "    Discharge step durations:", dit.mDischargeSteps, false)) {
                        dumpDailyLevelStepSummary(pw, "        ", "Discharge", dit.mDischargeSteps,
                                sb, outInt);
                    }
                    if (dumpDurationSteps(pw, "      ",
                            "    Charge step durations:", dit.mChargeSteps, false)) {
                        dumpDailyLevelStepSummary(pw, "        ", "Charge", dit.mChargeSteps,
                                sb, outInt);
                    }
                    dumpDailyPackageChanges(pw, "    ", dit.mPackageChanges);
                } else {
                    dumpDailyLevelStepSummary(pw, "    ", "Discharge", dit.mDischargeSteps,
                            sb, outInt);
                    dumpDailyLevelStepSummary(pw, "    ", "Charge", dit.mChargeSteps,
                            sb, outInt);
                }
            }
            pw.println();
        }
        if (!filtering || (flags&DUMP_CHARGED_ONLY) != 0) {
            pw.println("Statistics since last charge:");
            pw.println("  System starts: " + getStartCount()
                    + ", currently on battery: " + getIsOnBattery());
            dumpLocked(context, pw, "", STATS_SINCE_CHARGED, reqUid,
                    (flags&DUMP_DEVICE_WIFI_ONLY) != 0);
            pw.println();
        }
    }

    // This is called from BatteryStatsService.
    @SuppressWarnings("unused")
    public void dumpCheckinLocked(Context context, PrintWriter pw,
            List<ApplicationInfo> apps, int flags, long histStart) {
        prepareForDumpLocked();

        dumpLine(pw, 0 /* uid */, "i" /* category */, VERSION_DATA,
                CHECKIN_VERSION, getParcelVersion(), getStartPlatformVersion(),
                getEndPlatformVersion());

        long now = getHistoryBaseTime() + SystemClock.elapsedRealtime();

        if ((flags & (DUMP_INCLUDE_HISTORY | DUMP_HISTORY_ONLY)) != 0) {
            if (startIteratingHistoryLocked()) {
                try {
                    for (int i=0; i<getHistoryStringPoolSize(); i++) {
                        pw.print(BATTERY_STATS_CHECKIN_VERSION); pw.print(',');
                        pw.print(HISTORY_STRING_POOL); pw.print(',');
                        pw.print(i);
                        pw.print(",");
                        pw.print(getHistoryTagPoolUid(i));
                        pw.print(",\"");
                        String str = getHistoryTagPoolString(i);
                        str = str.replace("\\", "\\\\");
                        str = str.replace("\"", "\\\"");
                        pw.print(str);
                        pw.print("\"");
                        pw.println();
                    }
                    dumpHistoryLocked(pw, flags, histStart, true);
                } finally {
                    finishIteratingHistoryLocked();
                }
            }
        }

        if ((flags & DUMP_HISTORY_ONLY) != 0) {
            return;
        }

        if (apps != null) {
            SparseArray<Pair<ArrayList<String>, MutableBoolean>> uids = new SparseArray<>();
            for (int i=0; i<apps.size(); i++) {
                ApplicationInfo ai = apps.get(i);
                Pair<ArrayList<String>, MutableBoolean> pkgs = uids.get(
                        UserHandle.getAppId(ai.uid));
                if (pkgs == null) {
                    pkgs = new Pair<>(new ArrayList<String>(), new MutableBoolean(false));
                    uids.put(UserHandle.getAppId(ai.uid), pkgs);
                }
                pkgs.first.add(ai.packageName);
            }
            SparseArray<? extends Uid> uidStats = getUidStats();
            final int NU = uidStats.size();
            String[] lineArgs = new String[2];
            for (int i=0; i<NU; i++) {
                int uid = UserHandle.getAppId(uidStats.keyAt(i));
                Pair<ArrayList<String>, MutableBoolean> pkgs = uids.get(uid);
                if (pkgs != null && !pkgs.second.value) {
                    pkgs.second.value = true;
                    for (int j=0; j<pkgs.first.size(); j++) {
                        lineArgs[0] = Integer.toString(uid);
                        lineArgs[1] = pkgs.first.get(j);
                        dumpLine(pw, 0 /* uid */, "i" /* category */, UID_DATA,
                                (Object[])lineArgs);
                    }
                }
            }
        }
        if ((flags & DUMP_DAILY_ONLY) == 0) {
            dumpDurationSteps(pw, "", DISCHARGE_STEP_DATA, getDischargeLevelStepTracker(), true);
            String[] lineArgs = new String[1];
            long timeRemaining = computeBatteryTimeRemaining(SystemClock.elapsedRealtime() * 1000);
            if (timeRemaining >= 0) {
                lineArgs[0] = Long.toString(timeRemaining);
                dumpLine(pw, 0 /* uid */, "i" /* category */, DISCHARGE_TIME_REMAIN_DATA,
                        (Object[])lineArgs);
            }
            dumpDurationSteps(pw, "", CHARGE_STEP_DATA, getChargeLevelStepTracker(), true);
            timeRemaining = computeChargeTimeRemaining(SystemClock.elapsedRealtime() * 1000);
            if (timeRemaining >= 0) {
                lineArgs[0] = Long.toString(timeRemaining);
                dumpLine(pw, 0 /* uid */, "i" /* category */, CHARGE_TIME_REMAIN_DATA,
                        (Object[])lineArgs);
            }
            dumpCheckinLocked(context, pw, STATS_SINCE_CHARGED, -1,
                    (flags&DUMP_DEVICE_WIFI_ONLY) != 0);
        }
    }

    /**
     * Dump #STATS_SINCE_CHARGED batterystats data to a proto. If the flags include
     * DUMP_INCLUDE_HISTORY or DUMP_HISTORY_ONLY, only the history will be dumped.
     * @hide
     */
    public void dumpProtoLocked(Context context, FileDescriptor fd, List<ApplicationInfo> apps,
            int flags, long histStart) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        prepareForDumpLocked();

        if ((flags & (DUMP_INCLUDE_HISTORY | DUMP_HISTORY_ONLY)) != 0) {
            dumpProtoHistoryLocked(proto, flags, histStart);
            proto.flush();
            return;
        }

        final long bToken = proto.start(BatteryStatsServiceDumpProto.BATTERYSTATS);

        proto.write(BatteryStatsProto.REPORT_VERSION, CHECKIN_VERSION);
        proto.write(BatteryStatsProto.PARCEL_VERSION, getParcelVersion());
        proto.write(BatteryStatsProto.START_PLATFORM_VERSION, getStartPlatformVersion());
        proto.write(BatteryStatsProto.END_PLATFORM_VERSION, getEndPlatformVersion());

        if ((flags & DUMP_DAILY_ONLY) == 0) {
            final BatteryStatsHelper helper = new BatteryStatsHelper(context, false,
                    (flags & DUMP_DEVICE_WIFI_ONLY) != 0);
            helper.create(this);
            helper.refreshStats(STATS_SINCE_CHARGED, UserHandle.USER_ALL);

            dumpProtoAppsLocked(proto, helper, apps);
            dumpProtoSystemLocked(proto, helper);
        }

        proto.end(bToken);
        proto.flush();
    }

    private void dumpProtoAppsLocked(ProtoOutputStream proto, BatteryStatsHelper helper,
            List<ApplicationInfo> apps) {
        final int which = STATS_SINCE_CHARGED;
        final long rawUptimeUs = SystemClock.uptimeMillis() * 1000;
        final long rawRealtimeMs = SystemClock.elapsedRealtime();
        final long rawRealtimeUs = rawRealtimeMs * 1000;
        final long batteryUptimeUs = getBatteryUptime(rawUptimeUs);

        SparseArray<ArrayList<String>> aidToPackages = new SparseArray<>();
        if (apps != null) {
            for (int i = 0; i < apps.size(); ++i) {
                ApplicationInfo ai = apps.get(i);
                int aid = UserHandle.getAppId(ai.uid);
                ArrayList<String> pkgs = aidToPackages.get(aid);
                if (pkgs == null) {
                    pkgs = new ArrayList<String>();
                    aidToPackages.put(aid, pkgs);
                }
                pkgs.add(ai.packageName);
            }
        }

        SparseArray<BatterySipper> uidToSipper = new SparseArray<>();
        final List<BatterySipper> sippers = helper.getUsageList();
        if (sippers != null) {
            for (int i = 0; i < sippers.size(); ++i) {
                final BatterySipper bs = sippers.get(i);
                if (bs.drainType != BatterySipper.DrainType.APP) {
                    // Others are handled by dumpProtoSystemLocked()
                    continue;
                }
                uidToSipper.put(bs.uidObj.getUid(), bs);
            }
        }

        SparseArray<? extends Uid> uidStats = getUidStats();
        final int n = uidStats.size();
        for (int iu = 0; iu < n; ++iu) {
            final long uTkn = proto.start(BatteryStatsProto.UIDS);
            final Uid u = uidStats.valueAt(iu);

            final int uid = uidStats.keyAt(iu);
            proto.write(UidProto.UID, uid);

            // Print packages and apk stats (UID_DATA & APK_DATA)
            ArrayList<String> pkgs = aidToPackages.get(UserHandle.getAppId(uid));
            if (pkgs == null) {
                pkgs = new ArrayList<String>();
            }
            final ArrayMap<String, ? extends BatteryStats.Uid.Pkg> packageStats =
                    u.getPackageStats();
            for (int ipkg = packageStats.size() - 1; ipkg >= 0; --ipkg) {
                String pkg = packageStats.keyAt(ipkg);
                final ArrayMap<String, ? extends  Uid.Pkg.Serv> serviceStats =
                        packageStats.valueAt(ipkg).getServiceStats();
                if (serviceStats.size() == 0) {
                    // Due to the way ActivityManagerService logs wakeup alarms, some packages (for
                    // example, "android") may be included in the packageStats that aren't part of
                    // the UID. If they don't have any services, then they shouldn't be listed here.
                    // These packages won't be a part in the pkgs List.
                    continue;
                }

                final long pToken = proto.start(UidProto.PACKAGES);
                proto.write(UidProto.Package.NAME, pkg);
                // Remove from the packages list since we're logging it here.
                pkgs.remove(pkg);

                for (int isvc = serviceStats.size() - 1; isvc >= 0; --isvc) {
                    final BatteryStats.Uid.Pkg.Serv ss = serviceStats.valueAt(isvc);

                    final long startTimeMs = roundUsToMs(ss.getStartTime(batteryUptimeUs, which));
                    final int starts = ss.getStarts(which);
                    final int launches = ss.getLaunches(which);
                    if (startTimeMs == 0 && starts == 0 && launches == 0) {
                        continue;
                    }

                    long sToken = proto.start(UidProto.Package.SERVICES);

                    proto.write(UidProto.Package.Service.NAME, serviceStats.keyAt(isvc));
                    proto.write(UidProto.Package.Service.START_DURATION_MS, startTimeMs);
                    proto.write(UidProto.Package.Service.START_COUNT, starts);
                    proto.write(UidProto.Package.Service.LAUNCH_COUNT, launches);

                    proto.end(sToken);
                }
                proto.end(pToken);
            }
            // Print any remaining packages that weren't in the packageStats map. pkgs is pulled
            // from PackageManager data. Packages are only included in packageStats if there was
            // specific data tracked for them (services and wakeup alarms, etc.).
            for (String p : pkgs) {
                final long pToken = proto.start(UidProto.PACKAGES);
                proto.write(UidProto.Package.NAME, p);
                proto.end(pToken);
            }

            // Total wakelock data (AGGREGATED_WAKELOCK_DATA)
            if (u.getAggregatedPartialWakelockTimer() != null) {
                final Timer timer = u.getAggregatedPartialWakelockTimer();
                // Times are since reset (regardless of 'which')
                final long totTimeMs = timer.getTotalDurationMsLocked(rawRealtimeMs);
                final Timer bgTimer = timer.getSubTimer();
                final long bgTimeMs = bgTimer != null
                        ? bgTimer.getTotalDurationMsLocked(rawRealtimeMs) : 0;
                final long awToken = proto.start(UidProto.AGGREGATED_WAKELOCK);
                proto.write(UidProto.AggregatedWakelock.PARTIAL_DURATION_MS, totTimeMs);
                proto.write(UidProto.AggregatedWakelock.BACKGROUND_PARTIAL_DURATION_MS, bgTimeMs);
                proto.end(awToken);
            }

            // Audio (AUDIO_DATA)
            dumpTimer(proto, UidProto.AUDIO, u.getAudioTurnedOnTimer(), rawRealtimeUs, which);

            // Bluetooth Controller (BLUETOOTH_CONTROLLER_DATA)
            dumpControllerActivityProto(proto, UidProto.BLUETOOTH_CONTROLLER,
                    u.getBluetoothControllerActivity(), which);

            // BLE scans (BLUETOOTH_MISC_DATA) (uses totalDurationMsLocked and MaxDurationMsLocked)
            final Timer bleTimer = u.getBluetoothScanTimer();
            if (bleTimer != null) {
                final long bmToken = proto.start(UidProto.BLUETOOTH_MISC);

                dumpTimer(proto, UidProto.BluetoothMisc.APPORTIONED_BLE_SCAN, bleTimer,
                        rawRealtimeUs, which);
                dumpTimer(proto, UidProto.BluetoothMisc.BACKGROUND_BLE_SCAN,
                        u.getBluetoothScanBackgroundTimer(), rawRealtimeUs, which);
                // Unoptimized scan timer. Unpooled and since reset (regardless of 'which').
                dumpTimer(proto, UidProto.BluetoothMisc.UNOPTIMIZED_BLE_SCAN,
                        u.getBluetoothUnoptimizedScanTimer(), rawRealtimeUs, which);
                // Unoptimized bg scan timer. Unpooled and since reset (regardless of 'which').
                dumpTimer(proto, UidProto.BluetoothMisc.BACKGROUND_UNOPTIMIZED_BLE_SCAN,
                        u.getBluetoothUnoptimizedScanBackgroundTimer(), rawRealtimeUs, which);
                // Result counters
                proto.write(UidProto.BluetoothMisc.BLE_SCAN_RESULT_COUNT,
                        u.getBluetoothScanResultCounter() != null
                            ? u.getBluetoothScanResultCounter().getCountLocked(which) : 0);
                proto.write(UidProto.BluetoothMisc.BACKGROUND_BLE_SCAN_RESULT_COUNT,
                        u.getBluetoothScanResultBgCounter() != null
                            ? u.getBluetoothScanResultBgCounter().getCountLocked(which) : 0);

                proto.end(bmToken);
            }

            // Camera (CAMERA_DATA)
            dumpTimer(proto, UidProto.CAMERA, u.getCameraTurnedOnTimer(), rawRealtimeUs, which);

            // CPU stats (CPU_DATA & CPU_TIMES_AT_FREQ_DATA)
            final long cpuToken = proto.start(UidProto.CPU);
            proto.write(UidProto.Cpu.USER_DURATION_MS, roundUsToMs(u.getUserCpuTimeUs(which)));
            proto.write(UidProto.Cpu.SYSTEM_DURATION_MS, roundUsToMs(u.getSystemCpuTimeUs(which)));

            final long[] cpuFreqs = getCpuFreqs();
            if (cpuFreqs != null) {
                final long[] cpuFreqTimeMs = u.getCpuFreqTimes(which);
                // If total cpuFreqTimes is null, then we don't need to check for
                // screenOffCpuFreqTimes.
                if (cpuFreqTimeMs != null && cpuFreqTimeMs.length == cpuFreqs.length) {
                    long[] screenOffCpuFreqTimeMs = u.getScreenOffCpuFreqTimes(which);
                    if (screenOffCpuFreqTimeMs == null) {
                        screenOffCpuFreqTimeMs = new long[cpuFreqTimeMs.length];
                    }
                    for (int ic = 0; ic < cpuFreqTimeMs.length; ++ic) {
                        long cToken = proto.start(UidProto.Cpu.BY_FREQUENCY);
                        proto.write(UidProto.Cpu.ByFrequency.FREQUENCY_INDEX, ic + 1);
                        proto.write(UidProto.Cpu.ByFrequency.TOTAL_DURATION_MS,
                                cpuFreqTimeMs[ic]);
                        proto.write(UidProto.Cpu.ByFrequency.SCREEN_OFF_DURATION_MS,
                                screenOffCpuFreqTimeMs[ic]);
                        proto.end(cToken);
                    }
                }
            }

            for (int procState = 0; procState < Uid.NUM_PROCESS_STATE; ++procState) {
                final long[] timesMs = u.getCpuFreqTimes(which, procState);
                if (timesMs != null && timesMs.length == cpuFreqs.length) {
                    long[] screenOffTimesMs = u.getScreenOffCpuFreqTimes(which, procState);
                    if (screenOffTimesMs == null) {
                        screenOffTimesMs = new long[timesMs.length];
                    }
                    final long procToken = proto.start(UidProto.Cpu.BY_PROCESS_STATE);
                    proto.write(UidProto.Cpu.ByProcessState.PROCESS_STATE, procState);
                    for (int ic = 0; ic < timesMs.length; ++ic) {
                        long cToken = proto.start(UidProto.Cpu.ByProcessState.BY_FREQUENCY);
                        proto.write(UidProto.Cpu.ByFrequency.FREQUENCY_INDEX, ic + 1);
                        proto.write(UidProto.Cpu.ByFrequency.TOTAL_DURATION_MS,
                                timesMs[ic]);
                        proto.write(UidProto.Cpu.ByFrequency.SCREEN_OFF_DURATION_MS,
                                screenOffTimesMs[ic]);
                        proto.end(cToken);
                    }
                    proto.end(procToken);
                }
            }
            proto.end(cpuToken);

            // Flashlight (FLASHLIGHT_DATA)
            dumpTimer(proto, UidProto.FLASHLIGHT, u.getFlashlightTurnedOnTimer(),
                    rawRealtimeUs, which);

            // Foreground activity (FOREGROUND_ACTIVITY_DATA)
            dumpTimer(proto, UidProto.FOREGROUND_ACTIVITY, u.getForegroundActivityTimer(),
                    rawRealtimeUs, which);

            // Foreground service (FOREGROUND_SERVICE_DATA)
            dumpTimer(proto, UidProto.FOREGROUND_SERVICE, u.getForegroundServiceTimer(),
                    rawRealtimeUs, which);

            // Job completion (JOB_COMPLETION_DATA)
            final ArrayMap<String, SparseIntArray> completions = u.getJobCompletionStats();
            for (int ic = 0; ic < completions.size(); ++ic) {
                SparseIntArray types = completions.valueAt(ic);
                if (types != null) {
                    final long jcToken = proto.start(UidProto.JOB_COMPLETION);

                    proto.write(UidProto.JobCompletion.NAME, completions.keyAt(ic));

                    for (int r : JobParameters.getJobStopReasonCodes()) {
                        long rToken = proto.start(UidProto.JobCompletion.REASON_COUNT);
                        proto.write(UidProto.JobCompletion.ReasonCount.NAME, r);
                        proto.write(UidProto.JobCompletion.ReasonCount.COUNT, types.get(r, 0));
                        proto.end(rToken);
                    }

                    proto.end(jcToken);
                }
            }

            // Scheduled jobs (JOB_DATA)
            final ArrayMap<String, ? extends Timer> jobs = u.getJobStats();
            for (int ij = jobs.size() - 1; ij >= 0; --ij) {
                final Timer timer = jobs.valueAt(ij);
                final Timer bgTimer = timer.getSubTimer();
                final long jToken = proto.start(UidProto.JOBS);

                proto.write(UidProto.Job.NAME, jobs.keyAt(ij));
                // Background uses totalDurationMsLocked, while total uses totalTimeLocked
                dumpTimer(proto, UidProto.Job.TOTAL, timer, rawRealtimeUs, which);
                dumpTimer(proto, UidProto.Job.BACKGROUND, bgTimer, rawRealtimeUs, which);

                proto.end(jToken);
            }

            // Modem Controller (MODEM_CONTROLLER_DATA)
            dumpControllerActivityProto(proto, UidProto.MODEM_CONTROLLER,
                    u.getModemControllerActivity(), which);

            // Network stats (NETWORK_DATA)
            final long nToken = proto.start(UidProto.NETWORK);
            proto.write(UidProto.Network.MOBILE_BYTES_RX,
                    u.getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which));
            proto.write(UidProto.Network.MOBILE_BYTES_TX,
                    u.getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which));
            proto.write(UidProto.Network.WIFI_BYTES_RX,
                    u.getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which));
            proto.write(UidProto.Network.WIFI_BYTES_TX,
                    u.getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which));
            proto.write(UidProto.Network.BT_BYTES_RX,
                    u.getNetworkActivityBytes(NETWORK_BT_RX_DATA, which));
            proto.write(UidProto.Network.BT_BYTES_TX,
                    u.getNetworkActivityBytes(NETWORK_BT_TX_DATA, which));
            proto.write(UidProto.Network.MOBILE_PACKETS_RX,
                    u.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which));
            proto.write(UidProto.Network.MOBILE_PACKETS_TX,
                    u.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which));
            proto.write(UidProto.Network.WIFI_PACKETS_RX,
                    u.getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which));
            proto.write(UidProto.Network.WIFI_PACKETS_TX,
                    u.getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which));
            proto.write(UidProto.Network.MOBILE_ACTIVE_DURATION_MS,
                    roundUsToMs(u.getMobileRadioActiveTime(which)));
            proto.write(UidProto.Network.MOBILE_ACTIVE_COUNT,
                    u.getMobileRadioActiveCount(which));
            proto.write(UidProto.Network.MOBILE_WAKEUP_COUNT,
                    u.getMobileRadioApWakeupCount(which));
            proto.write(UidProto.Network.WIFI_WAKEUP_COUNT,
                    u.getWifiRadioApWakeupCount(which));
            proto.write(UidProto.Network.MOBILE_BYTES_BG_RX,
                    u.getNetworkActivityBytes(NETWORK_MOBILE_BG_RX_DATA, which));
            proto.write(UidProto.Network.MOBILE_BYTES_BG_TX,
                    u.getNetworkActivityBytes(NETWORK_MOBILE_BG_TX_DATA, which));
            proto.write(UidProto.Network.WIFI_BYTES_BG_RX,
                    u.getNetworkActivityBytes(NETWORK_WIFI_BG_RX_DATA, which));
            proto.write(UidProto.Network.WIFI_BYTES_BG_TX,
                    u.getNetworkActivityBytes(NETWORK_WIFI_BG_TX_DATA, which));
            proto.write(UidProto.Network.MOBILE_PACKETS_BG_RX,
                    u.getNetworkActivityPackets(NETWORK_MOBILE_BG_RX_DATA, which));
            proto.write(UidProto.Network.MOBILE_PACKETS_BG_TX,
                    u.getNetworkActivityPackets(NETWORK_MOBILE_BG_TX_DATA, which));
            proto.write(UidProto.Network.WIFI_PACKETS_BG_RX,
                    u.getNetworkActivityPackets(NETWORK_WIFI_BG_RX_DATA, which));
            proto.write(UidProto.Network.WIFI_PACKETS_BG_TX,
                    u.getNetworkActivityPackets(NETWORK_WIFI_BG_TX_DATA, which));
            proto.end(nToken);

            // Power use item (POWER_USE_ITEM_DATA)
            BatterySipper bs = uidToSipper.get(uid);
            if (bs != null) {
                final long bsToken = proto.start(UidProto.POWER_USE_ITEM);
                proto.write(UidProto.PowerUseItem.COMPUTED_POWER_MAH, bs.totalPowerMah);
                proto.write(UidProto.PowerUseItem.SHOULD_HIDE, bs.shouldHide);
                proto.write(UidProto.PowerUseItem.SCREEN_POWER_MAH, bs.screenPowerMah);
                proto.write(UidProto.PowerUseItem.PROPORTIONAL_SMEAR_MAH,
                        bs.proportionalSmearMah);
                proto.end(bsToken);
            }

            // Processes (PROCESS_DATA)
            final ArrayMap<String, ? extends BatteryStats.Uid.Proc> processStats =
                    u.getProcessStats();
            for (int ipr = processStats.size() - 1; ipr >= 0; --ipr) {
                final Uid.Proc ps = processStats.valueAt(ipr);
                final long prToken = proto.start(UidProto.PROCESS);

                proto.write(UidProto.Process.NAME, processStats.keyAt(ipr));
                proto.write(UidProto.Process.USER_DURATION_MS, ps.getUserTime(which));
                proto.write(UidProto.Process.SYSTEM_DURATION_MS, ps.getSystemTime(which));
                proto.write(UidProto.Process.FOREGROUND_DURATION_MS, ps.getForegroundTime(which));
                proto.write(UidProto.Process.START_COUNT, ps.getStarts(which));
                proto.write(UidProto.Process.ANR_COUNT, ps.getNumAnrs(which));
                proto.write(UidProto.Process.CRASH_COUNT, ps.getNumCrashes(which));

                proto.end(prToken);
            }

            // Sensors (SENSOR_DATA)
            final SparseArray<? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            for (int ise = 0; ise < sensors.size(); ++ise) {
                final Uid.Sensor se = sensors.valueAt(ise);
                final Timer timer = se.getSensorTime();
                if (timer == null) {
                    continue;
                }
                final Timer bgTimer = se.getSensorBackgroundTime();
                final int sensorNumber = sensors.keyAt(ise);
                final long seToken = proto.start(UidProto.SENSORS);

                proto.write(UidProto.Sensor.ID, sensorNumber);
                // Background uses totalDurationMsLocked, while total uses totalTimeLocked
                dumpTimer(proto, UidProto.Sensor.APPORTIONED, timer, rawRealtimeUs, which);
                dumpTimer(proto, UidProto.Sensor.BACKGROUND, bgTimer, rawRealtimeUs, which);

                proto.end(seToken);
            }

            // State times (STATE_TIME_DATA)
            for (int ips = 0; ips < Uid.NUM_PROCESS_STATE; ++ips) {
                long durMs = roundUsToMs(u.getProcessStateTime(ips, rawRealtimeUs, which));
                if (durMs == 0) {
                    continue;
                }
                final long stToken = proto.start(UidProto.STATES);
                proto.write(UidProto.StateTime.STATE, ips);
                proto.write(UidProto.StateTime.DURATION_MS, durMs);
                proto.end(stToken);
            }

            // Syncs (SYNC_DATA)
            final ArrayMap<String, ? extends Timer> syncs = u.getSyncStats();
            for (int isy = syncs.size() - 1; isy >= 0; --isy) {
                final Timer timer = syncs.valueAt(isy);
                final Timer bgTimer = timer.getSubTimer();
                final long syToken = proto.start(UidProto.SYNCS);

                proto.write(UidProto.Sync.NAME, syncs.keyAt(isy));
                // Background uses totalDurationMsLocked, while total uses totalTimeLocked
                dumpTimer(proto, UidProto.Sync.TOTAL, timer, rawRealtimeUs, which);
                dumpTimer(proto, UidProto.Sync.BACKGROUND, bgTimer, rawRealtimeUs, which);

                proto.end(syToken);
            }

            // User activity (USER_ACTIVITY_DATA)
            if (u.hasUserActivity()) {
                for (int i = 0; i < Uid.NUM_USER_ACTIVITY_TYPES; ++i) {
                    int val = u.getUserActivityCount(i, which);
                    if (val != 0) {
                        final long uaToken = proto.start(UidProto.USER_ACTIVITY);
                        proto.write(UidProto.UserActivity.NAME, i);
                        proto.write(UidProto.UserActivity.COUNT, val);
                        proto.end(uaToken);
                    }
                }
            }

            // Vibrator (VIBRATOR_DATA)
            dumpTimer(proto, UidProto.VIBRATOR, u.getVibratorOnTimer(), rawRealtimeUs, which);

            // Video (VIDEO_DATA)
            dumpTimer(proto, UidProto.VIDEO, u.getVideoTurnedOnTimer(), rawRealtimeUs, which);

            // Wakelocks (WAKELOCK_DATA)
            final ArrayMap<String, ? extends Uid.Wakelock> wakelocks = u.getWakelockStats();
            for (int iw = wakelocks.size() - 1; iw >= 0; --iw) {
                final Uid.Wakelock wl = wakelocks.valueAt(iw);
                final long wToken = proto.start(UidProto.WAKELOCKS);
                proto.write(UidProto.Wakelock.NAME, wakelocks.keyAt(iw));
                dumpTimer(proto, UidProto.Wakelock.FULL, wl.getWakeTime(WAKE_TYPE_FULL),
                        rawRealtimeUs, which);
                final Timer pTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                if (pTimer != null) {
                    dumpTimer(proto, UidProto.Wakelock.PARTIAL, pTimer, rawRealtimeUs, which);
                    dumpTimer(proto, UidProto.Wakelock.BACKGROUND_PARTIAL, pTimer.getSubTimer(),
                            rawRealtimeUs, which);
                }
                dumpTimer(proto, UidProto.Wakelock.WINDOW, wl.getWakeTime(WAKE_TYPE_WINDOW),
                        rawRealtimeUs, which);
                proto.end(wToken);
            }

            // Wifi Multicast Wakelock (WIFI_MULTICAST_WAKELOCK_DATA)
            dumpTimer(proto, UidProto.WIFI_MULTICAST_WAKELOCK, u.getMulticastWakelockStats(),
                    rawRealtimeUs, which);

            // Wakeup alarms (WAKEUP_ALARM_DATA)
            for (int ipkg = packageStats.size() - 1; ipkg >= 0; --ipkg) {
                final Uid.Pkg ps = packageStats.valueAt(ipkg);
                final ArrayMap<String, ? extends Counter> alarms = ps.getWakeupAlarmStats();
                for (int iwa = alarms.size() - 1; iwa >= 0; --iwa) {
                    final long waToken = proto.start(UidProto.WAKEUP_ALARM);
                    proto.write(UidProto.WakeupAlarm.NAME, alarms.keyAt(iwa));
                    proto.write(UidProto.WakeupAlarm.COUNT,
                            alarms.valueAt(iwa).getCountLocked(which));
                    proto.end(waToken);
                }
            }

            // Wifi Controller (WIFI_CONTROLLER_DATA)
            dumpControllerActivityProto(proto, UidProto.WIFI_CONTROLLER,
                    u.getWifiControllerActivity(), which);

            // Wifi data (WIFI_DATA)
            final long wToken = proto.start(UidProto.WIFI);
            proto.write(UidProto.Wifi.FULL_WIFI_LOCK_DURATION_MS,
                    roundUsToMs(u.getFullWifiLockTime(rawRealtimeUs, which)));
            dumpTimer(proto, UidProto.Wifi.APPORTIONED_SCAN, u.getWifiScanTimer(),
                    rawRealtimeUs, which);
            proto.write(UidProto.Wifi.RUNNING_DURATION_MS,
                    roundUsToMs(u.getWifiRunningTime(rawRealtimeUs, which)));
            dumpTimer(proto, UidProto.Wifi.BACKGROUND_SCAN, u.getWifiScanBackgroundTimer(),
                    rawRealtimeUs, which);
            proto.end(wToken);

            proto.end(uTkn);
        }
    }

    private void dumpProtoHistoryLocked(ProtoOutputStream proto, int flags, long histStart) {
        if (!startIteratingHistoryLocked()) {
            return;
        }

        proto.write(BatteryStatsServiceDumpHistoryProto.REPORT_VERSION, CHECKIN_VERSION);
        proto.write(BatteryStatsServiceDumpHistoryProto.PARCEL_VERSION, getParcelVersion());
        proto.write(BatteryStatsServiceDumpHistoryProto.START_PLATFORM_VERSION,
                getStartPlatformVersion());
        proto.write(BatteryStatsServiceDumpHistoryProto.END_PLATFORM_VERSION,
                getEndPlatformVersion());
        try {
            long token;
            // History string pool (HISTORY_STRING_POOL)
            for (int i = 0; i < getHistoryStringPoolSize(); ++i) {
                token = proto.start(BatteryStatsServiceDumpHistoryProto.KEYS);
                proto.write(BatteryStatsServiceDumpHistoryProto.Key.INDEX, i);
                proto.write(BatteryStatsServiceDumpHistoryProto.Key.UID, getHistoryTagPoolUid(i));
                proto.write(BatteryStatsServiceDumpHistoryProto.Key.TAG,
                        getHistoryTagPoolString(i));
                proto.end(token);
            }

            // History data (HISTORY_DATA)
            final HistoryPrinter hprinter = new HistoryPrinter();
            final HistoryItem rec = new HistoryItem();
            long lastTime = -1;
            long baseTime = -1;
            boolean printed = false;
            HistoryEventTracker tracker = null;
            while (getNextHistoryLocked(rec)) {
                lastTime = rec.time;
                if (baseTime < 0) {
                    baseTime = lastTime;
                }
                if (rec.time >= histStart) {
                    if (histStart >= 0 && !printed) {
                        if (rec.cmd == HistoryItem.CMD_CURRENT_TIME
                                || rec.cmd == HistoryItem.CMD_RESET
                                || rec.cmd == HistoryItem.CMD_START
                                || rec.cmd == HistoryItem.CMD_SHUTDOWN) {
                            printed = true;
                            hprinter.printNextItem(proto, rec, baseTime,
                                    (flags & DUMP_VERBOSE) != 0);
                            rec.cmd = HistoryItem.CMD_UPDATE;
                        } else if (rec.currentTime != 0) {
                            printed = true;
                            byte cmd = rec.cmd;
                            rec.cmd = HistoryItem.CMD_CURRENT_TIME;
                            hprinter.printNextItem(proto, rec, baseTime,
                                    (flags & DUMP_VERBOSE) != 0);
                            rec.cmd = cmd;
                        }
                        if (tracker != null) {
                            if (rec.cmd != HistoryItem.CMD_UPDATE) {
                                hprinter.printNextItem(proto, rec, baseTime,
                                        (flags & DUMP_VERBOSE) != 0);
                                rec.cmd = HistoryItem.CMD_UPDATE;
                            }
                            int oldEventCode = rec.eventCode;
                            HistoryTag oldEventTag = rec.eventTag;
                            rec.eventTag = new HistoryTag();
                            for (int i = 0; i < HistoryItem.EVENT_COUNT; i++) {
                                HashMap<String, SparseIntArray> active =
                                        tracker.getStateForEvent(i);
                                if (active == null) {
                                    continue;
                                }
                                for (HashMap.Entry<String, SparseIntArray> ent
                                        : active.entrySet()) {
                                    SparseIntArray uids = ent.getValue();
                                    for (int j = 0; j < uids.size(); j++) {
                                        rec.eventCode = i;
                                        rec.eventTag.string = ent.getKey();
                                        rec.eventTag.uid = uids.keyAt(j);
                                        rec.eventTag.poolIdx = uids.valueAt(j);
                                        hprinter.printNextItem(proto, rec, baseTime,
                                                (flags & DUMP_VERBOSE) != 0);
                                        rec.wakeReasonTag = null;
                                        rec.wakelockTag = null;
                                    }
                                }
                            }
                            rec.eventCode = oldEventCode;
                            rec.eventTag = oldEventTag;
                            tracker = null;
                        }
                    }
                    hprinter.printNextItem(proto, rec, baseTime,
                            (flags & DUMP_VERBOSE) != 0);
                }
            }
            if (histStart >= 0) {
                commitCurrentHistoryBatchLocked();
                proto.write(BatteryStatsServiceDumpHistoryProto.CSV_LINES,
                        "NEXT: " + (lastTime + 1));
            }
        } finally {
            finishIteratingHistoryLocked();
        }
    }

    private void dumpProtoSystemLocked(ProtoOutputStream proto, BatteryStatsHelper helper) {
        final long sToken = proto.start(BatteryStatsProto.SYSTEM);
        final long rawUptimeUs = SystemClock.uptimeMillis() * 1000;
        final long rawRealtimeMs = SystemClock.elapsedRealtime();
        final long rawRealtimeUs = rawRealtimeMs * 1000;
        final int which = STATS_SINCE_CHARGED;

        // Battery data (BATTERY_DATA)
        final long bToken = proto.start(SystemProto.BATTERY);
        proto.write(SystemProto.Battery.START_CLOCK_TIME_MS, getStartClockTime());
        proto.write(SystemProto.Battery.START_COUNT, getStartCount());
        proto.write(SystemProto.Battery.TOTAL_REALTIME_MS,
                computeRealtime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Battery.TOTAL_UPTIME_MS,
                computeUptime(rawUptimeUs, which) / 1000);
        proto.write(SystemProto.Battery.BATTERY_REALTIME_MS,
                computeBatteryRealtime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Battery.BATTERY_UPTIME_MS,
                computeBatteryUptime(rawUptimeUs, which) / 1000);
        proto.write(SystemProto.Battery.SCREEN_OFF_REALTIME_MS,
                computeBatteryScreenOffRealtime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Battery.SCREEN_OFF_UPTIME_MS,
                computeBatteryScreenOffUptime(rawUptimeUs, which) / 1000);
        proto.write(SystemProto.Battery.SCREEN_DOZE_DURATION_MS,
                getScreenDozeTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Battery.ESTIMATED_BATTERY_CAPACITY_MAH,
                getEstimatedBatteryCapacity());
        proto.write(SystemProto.Battery.MIN_LEARNED_BATTERY_CAPACITY_UAH,
                getMinLearnedBatteryCapacity());
        proto.write(SystemProto.Battery.MAX_LEARNED_BATTERY_CAPACITY_UAH,
                getMaxLearnedBatteryCapacity());
        proto.end(bToken);

        // Battery discharge (BATTERY_DISCHARGE_DATA)
        final long bdToken = proto.start(SystemProto.BATTERY_DISCHARGE);
        proto.write(SystemProto.BatteryDischarge.LOWER_BOUND_SINCE_CHARGE,
                getLowDischargeAmountSinceCharge());
        proto.write(SystemProto.BatteryDischarge.UPPER_BOUND_SINCE_CHARGE,
                getHighDischargeAmountSinceCharge());
        proto.write(SystemProto.BatteryDischarge.SCREEN_ON_SINCE_CHARGE,
                getDischargeAmountScreenOnSinceCharge());
        proto.write(SystemProto.BatteryDischarge.SCREEN_OFF_SINCE_CHARGE,
                getDischargeAmountScreenOffSinceCharge());
        proto.write(SystemProto.BatteryDischarge.SCREEN_DOZE_SINCE_CHARGE,
                getDischargeAmountScreenDozeSinceCharge());
        proto.write(SystemProto.BatteryDischarge.TOTAL_MAH,
                getUahDischarge(which) / 1000);
        proto.write(SystemProto.BatteryDischarge.TOTAL_MAH_SCREEN_OFF,
                getUahDischargeScreenOff(which) / 1000);
        proto.write(SystemProto.BatteryDischarge.TOTAL_MAH_SCREEN_DOZE,
                getUahDischargeScreenDoze(which) / 1000);
        proto.write(SystemProto.BatteryDischarge.TOTAL_MAH_LIGHT_DOZE,
                getUahDischargeLightDoze(which) / 1000);
        proto.write(SystemProto.BatteryDischarge.TOTAL_MAH_DEEP_DOZE,
                getUahDischargeDeepDoze(which) / 1000);
        proto.end(bdToken);

        // Time remaining
        long timeRemainingUs = computeChargeTimeRemaining(rawRealtimeUs);
        // These are part of a oneof, so we should only set one of them.
        if (timeRemainingUs >= 0) {
            // Charge time remaining (CHARGE_TIME_REMAIN_DATA)
            proto.write(SystemProto.CHARGE_TIME_REMAINING_MS, timeRemainingUs / 1000);
        } else {
            timeRemainingUs = computeBatteryTimeRemaining(rawRealtimeUs);
            // Discharge time remaining (DISCHARGE_TIME_REMAIN_DATA)
            if (timeRemainingUs >= 0) {
                proto.write(SystemProto.DISCHARGE_TIME_REMAINING_MS, timeRemainingUs / 1000);
            } else {
                proto.write(SystemProto.DISCHARGE_TIME_REMAINING_MS, -1);
            }
        }

        // Charge step (CHARGE_STEP_DATA)
        dumpDurationSteps(proto, SystemProto.CHARGE_STEP, getChargeLevelStepTracker());

        // Phone data connection (DATA_CONNECTION_TIME_DATA and DATA_CONNECTION_COUNT_DATA)
        for (int i = 0; i < NUM_DATA_CONNECTION_TYPES; ++i) {
            // Map OTHER to TelephonyManager.NETWORK_TYPE_UNKNOWN and mark NONE as a boolean.
            boolean isNone = (i == DATA_CONNECTION_OUT_OF_SERVICE);
            int telephonyNetworkType = i;
            if (i == DATA_CONNECTION_OTHER || i == DATA_CONNECTION_EMERGENCY_SERVICE) {
                telephonyNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            }
            final long pdcToken = proto.start(SystemProto.DATA_CONNECTION);
            if (isNone) {
                proto.write(SystemProto.DataConnection.IS_NONE, isNone);
            } else {
                proto.write(SystemProto.DataConnection.NAME, telephonyNetworkType);
            }
            dumpTimer(proto, SystemProto.DataConnection.TOTAL, getPhoneDataConnectionTimer(i),
                    rawRealtimeUs, which);
            proto.end(pdcToken);
        }

        // Discharge step (DISCHARGE_STEP_DATA)
        dumpDurationSteps(proto, SystemProto.DISCHARGE_STEP, getDischargeLevelStepTracker());

        // CPU frequencies (GLOBAL_CPU_FREQ_DATA)
        final long[] cpuFreqs = getCpuFreqs();
        if (cpuFreqs != null) {
            for (long i : cpuFreqs) {
                proto.write(SystemProto.CPU_FREQUENCY, i);
            }
        }

        // Bluetooth controller (GLOBAL_BLUETOOTH_CONTROLLER_DATA)
        dumpControllerActivityProto(proto, SystemProto.GLOBAL_BLUETOOTH_CONTROLLER,
                getBluetoothControllerActivity(), which);

        // Modem controller (GLOBAL_MODEM_CONTROLLER_DATA)
        dumpControllerActivityProto(proto, SystemProto.GLOBAL_MODEM_CONTROLLER,
                getModemControllerActivity(), which);

        // Global network data (GLOBAL_NETWORK_DATA)
        final long gnToken = proto.start(SystemProto.GLOBAL_NETWORK);
        proto.write(SystemProto.GlobalNetwork.MOBILE_BYTES_RX,
                getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.MOBILE_BYTES_TX,
                getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.MOBILE_PACKETS_RX,
                getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.MOBILE_PACKETS_TX,
                getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.WIFI_BYTES_RX,
                getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.WIFI_BYTES_TX,
                getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.WIFI_PACKETS_RX,
                getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.WIFI_PACKETS_TX,
                getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.BT_BYTES_RX,
                getNetworkActivityBytes(NETWORK_BT_RX_DATA, which));
        proto.write(SystemProto.GlobalNetwork.BT_BYTES_TX,
                getNetworkActivityBytes(NETWORK_BT_TX_DATA, which));
        proto.end(gnToken);

        // Wifi controller (GLOBAL_WIFI_CONTROLLER_DATA)
        dumpControllerActivityProto(proto, SystemProto.GLOBAL_WIFI_CONTROLLER,
                getWifiControllerActivity(), which);


        // Global wifi (GLOBAL_WIFI_DATA)
        final long gwToken = proto.start(SystemProto.GLOBAL_WIFI);
        proto.write(SystemProto.GlobalWifi.ON_DURATION_MS,
                getWifiOnTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.GlobalWifi.RUNNING_DURATION_MS,
                getGlobalWifiRunningTime(rawRealtimeUs, which) / 1000);
        proto.end(gwToken);

        // Kernel wakelock (KERNEL_WAKELOCK_DATA)
        final Map<String, ? extends Timer> kernelWakelocks = getKernelWakelockStats();
        for (Map.Entry<String, ? extends Timer> ent : kernelWakelocks.entrySet()) {
            final long kwToken = proto.start(SystemProto.KERNEL_WAKELOCK);
            proto.write(SystemProto.KernelWakelock.NAME, ent.getKey());
            dumpTimer(proto, SystemProto.KernelWakelock.TOTAL, ent.getValue(),
                    rawRealtimeUs, which);
            proto.end(kwToken);
        }

        // Misc (MISC_DATA)
        // Calculate wakelock times across all uids.
        long fullWakeLockTimeTotalUs = 0;
        long partialWakeLockTimeTotalUs = 0;

        final SparseArray<? extends Uid> uidStats = getUidStats();
        for (int iu = 0; iu < uidStats.size(); iu++) {
            final Uid u = uidStats.valueAt(iu);

            final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelocks =
                    u.getWakelockStats();
            for (int iw = wakelocks.size() - 1; iw >= 0; --iw) {
                final Uid.Wakelock wl = wakelocks.valueAt(iw);

                final Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                if (fullWakeTimer != null) {
                    fullWakeLockTimeTotalUs += fullWakeTimer.getTotalTimeLocked(rawRealtimeUs,
                            which);
                }

                final Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                if (partialWakeTimer != null) {
                    partialWakeLockTimeTotalUs += partialWakeTimer.getTotalTimeLocked(
                        rawRealtimeUs, which);
                }
            }
        }
        final long mToken = proto.start(SystemProto.MISC);
        proto.write(SystemProto.Misc.SCREEN_ON_DURATION_MS,
                getScreenOnTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.PHONE_ON_DURATION_MS,
                getPhoneOnTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.FULL_WAKELOCK_TOTAL_DURATION_MS,
                fullWakeLockTimeTotalUs / 1000);
        proto.write(SystemProto.Misc.PARTIAL_WAKELOCK_TOTAL_DURATION_MS,
                partialWakeLockTimeTotalUs / 1000);
        proto.write(SystemProto.Misc.MOBILE_RADIO_ACTIVE_DURATION_MS,
                getMobileRadioActiveTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.MOBILE_RADIO_ACTIVE_ADJUSTED_TIME_MS,
                getMobileRadioActiveAdjustedTime(which) / 1000);
        proto.write(SystemProto.Misc.MOBILE_RADIO_ACTIVE_COUNT,
                getMobileRadioActiveCount(which));
        proto.write(SystemProto.Misc.MOBILE_RADIO_ACTIVE_UNKNOWN_DURATION_MS,
                getMobileRadioActiveUnknownTime(which) / 1000);
        proto.write(SystemProto.Misc.INTERACTIVE_DURATION_MS,
                getInteractiveTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.BATTERY_SAVER_MODE_ENABLED_DURATION_MS,
                getPowerSaveModeEnabledTime(rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.NUM_CONNECTIVITY_CHANGES,
                getNumConnectivityChange(which));
        proto.write(SystemProto.Misc.DEEP_DOZE_ENABLED_DURATION_MS,
                getDeviceIdleModeTime(DEVICE_IDLE_MODE_DEEP, rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.DEEP_DOZE_COUNT,
                getDeviceIdleModeCount(DEVICE_IDLE_MODE_DEEP, which));
        proto.write(SystemProto.Misc.DEEP_DOZE_IDLING_DURATION_MS,
                getDeviceIdlingTime(DEVICE_IDLE_MODE_DEEP, rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.DEEP_DOZE_IDLING_COUNT,
                getDeviceIdlingCount(DEVICE_IDLE_MODE_DEEP, which));
        proto.write(SystemProto.Misc.LONGEST_DEEP_DOZE_DURATION_MS,
                getLongestDeviceIdleModeTime(DEVICE_IDLE_MODE_DEEP));
        proto.write(SystemProto.Misc.LIGHT_DOZE_ENABLED_DURATION_MS,
                getDeviceIdleModeTime(DEVICE_IDLE_MODE_LIGHT, rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.LIGHT_DOZE_COUNT,
                getDeviceIdleModeCount(DEVICE_IDLE_MODE_LIGHT, which));
        proto.write(SystemProto.Misc.LIGHT_DOZE_IDLING_DURATION_MS,
                getDeviceIdlingTime(DEVICE_IDLE_MODE_LIGHT, rawRealtimeUs, which) / 1000);
        proto.write(SystemProto.Misc.LIGHT_DOZE_IDLING_COUNT,
                getDeviceIdlingCount(DEVICE_IDLE_MODE_LIGHT, which));
        proto.write(SystemProto.Misc.LONGEST_LIGHT_DOZE_DURATION_MS,
                getLongestDeviceIdleModeTime(DEVICE_IDLE_MODE_LIGHT));
        proto.end(mToken);

        // Wifi multicast wakelock total stats (WIFI_MULTICAST_WAKELOCK_TOTAL_DATA)
        final long multicastWakeLockTimeTotalUs =
                getWifiMulticastWakelockTime(rawRealtimeUs, which);
        final int multicastWakeLockCountTotal = getWifiMulticastWakelockCount(which);
        final long wmctToken = proto.start(SystemProto.WIFI_MULTICAST_WAKELOCK_TOTAL);
        proto.write(SystemProto.WifiMulticastWakelockTotal.DURATION_MS,
                multicastWakeLockTimeTotalUs / 1000);
        proto.write(SystemProto.WifiMulticastWakelockTotal.COUNT,
                multicastWakeLockCountTotal);
        proto.end(wmctToken);

        // Power use item (POWER_USE_ITEM_DATA)
        final List<BatterySipper> sippers = helper.getUsageList();
        if (sippers != null) {
            for (int i = 0; i < sippers.size(); ++i) {
                final BatterySipper bs = sippers.get(i);
                int n = SystemProto.PowerUseItem.UNKNOWN_SIPPER;
                int uid = 0;
                switch (bs.drainType) {
                    case AMBIENT_DISPLAY:
                        n = SystemProto.PowerUseItem.AMBIENT_DISPLAY;
                        break;
                    case IDLE:
                        n = SystemProto.PowerUseItem.IDLE;
                        break;
                    case CELL:
                        n = SystemProto.PowerUseItem.CELL;
                        break;
                    case PHONE:
                        n = SystemProto.PowerUseItem.PHONE;
                        break;
                    case WIFI:
                        n = SystemProto.PowerUseItem.WIFI;
                        break;
                    case BLUETOOTH:
                        n = SystemProto.PowerUseItem.BLUETOOTH;
                        break;
                    case SCREEN:
                        n = SystemProto.PowerUseItem.SCREEN;
                        break;
                    case FLASHLIGHT:
                        n = SystemProto.PowerUseItem.FLASHLIGHT;
                        break;
                    case APP:
                        // dumpProtoAppsLocked will handle this.
                        continue;
                    case USER:
                        n = SystemProto.PowerUseItem.USER;
                        uid = UserHandle.getUid(bs.userId, 0);
                        break;
                    case UNACCOUNTED:
                        n = SystemProto.PowerUseItem.UNACCOUNTED;
                        break;
                    case OVERCOUNTED:
                        n = SystemProto.PowerUseItem.OVERCOUNTED;
                        break;
                    case CAMERA:
                        n = SystemProto.PowerUseItem.CAMERA;
                        break;
                    case MEMORY:
                        n = SystemProto.PowerUseItem.MEMORY;
                        break;
                }
                final long puiToken = proto.start(SystemProto.POWER_USE_ITEM);
                proto.write(SystemProto.PowerUseItem.NAME, n);
                proto.write(SystemProto.PowerUseItem.UID, uid);
                proto.write(SystemProto.PowerUseItem.COMPUTED_POWER_MAH, bs.totalPowerMah);
                proto.write(SystemProto.PowerUseItem.SHOULD_HIDE, bs.shouldHide);
                proto.write(SystemProto.PowerUseItem.SCREEN_POWER_MAH, bs.screenPowerMah);
                proto.write(SystemProto.PowerUseItem.PROPORTIONAL_SMEAR_MAH,
                        bs.proportionalSmearMah);
                proto.end(puiToken);
            }
        }

        // Power use summary (POWER_USE_SUMMARY_DATA)
        final long pusToken = proto.start(SystemProto.POWER_USE_SUMMARY);
        proto.write(SystemProto.PowerUseSummary.BATTERY_CAPACITY_MAH,
                helper.getPowerProfile().getBatteryCapacity());
        proto.write(SystemProto.PowerUseSummary.COMPUTED_POWER_MAH, helper.getComputedPower());
        proto.write(SystemProto.PowerUseSummary.MIN_DRAINED_POWER_MAH, helper.getMinDrainedPower());
        proto.write(SystemProto.PowerUseSummary.MAX_DRAINED_POWER_MAH, helper.getMaxDrainedPower());
        proto.end(pusToken);

        // RPM stats (RESOURCE_POWER_MANAGER_DATA)
        final Map<String, ? extends Timer> rpmStats = getRpmStats();
        final Map<String, ? extends Timer> screenOffRpmStats = getScreenOffRpmStats();
        for (Map.Entry<String, ? extends Timer> ent : rpmStats.entrySet()) {
            final long rpmToken = proto.start(SystemProto.RESOURCE_POWER_MANAGER);
            proto.write(SystemProto.ResourcePowerManager.NAME, ent.getKey());
            dumpTimer(proto, SystemProto.ResourcePowerManager.TOTAL,
                    ent.getValue(), rawRealtimeUs, which);
            dumpTimer(proto, SystemProto.ResourcePowerManager.SCREEN_OFF,
                    screenOffRpmStats.get(ent.getKey()), rawRealtimeUs, which);
            proto.end(rpmToken);
        }

        // Screen brightness (SCREEN_BRIGHTNESS_DATA)
        for (int i = 0; i < NUM_SCREEN_BRIGHTNESS_BINS; ++i) {
            final long sbToken = proto.start(SystemProto.SCREEN_BRIGHTNESS);
            proto.write(SystemProto.ScreenBrightness.NAME, i);
            dumpTimer(proto, SystemProto.ScreenBrightness.TOTAL, getScreenBrightnessTimer(i),
                    rawRealtimeUs, which);
            proto.end(sbToken);
        }

        // Signal scanning time (SIGNAL_SCANNING_TIME_DATA)
        dumpTimer(proto, SystemProto.SIGNAL_SCANNING, getPhoneSignalScanningTimer(), rawRealtimeUs,
                which);

        // Phone signal strength (SIGNAL_STRENGTH_TIME_DATA and SIGNAL_STRENGTH_COUNT_DATA)
        for (int i = 0; i < CellSignalStrength.getNumSignalStrengthLevels(); ++i) {
            final long pssToken = proto.start(SystemProto.PHONE_SIGNAL_STRENGTH);
            proto.write(SystemProto.PhoneSignalStrength.NAME, i);
            dumpTimer(proto, SystemProto.PhoneSignalStrength.TOTAL, getPhoneSignalStrengthTimer(i),
                    rawRealtimeUs, which);
            proto.end(pssToken);
        }

        // Wakeup reasons (WAKEUP_REASON_DATA)
        final Map<String, ? extends Timer> wakeupReasons = getWakeupReasonStats();
        for (Map.Entry<String, ? extends Timer> ent : wakeupReasons.entrySet()) {
            final long wrToken = proto.start(SystemProto.WAKEUP_REASON);
            proto.write(SystemProto.WakeupReason.NAME, ent.getKey());
            dumpTimer(proto, SystemProto.WakeupReason.TOTAL, ent.getValue(), rawRealtimeUs, which);
            proto.end(wrToken);
        }

        // Wifi signal strength (WIFI_SIGNAL_STRENGTH_TIME_DATA and WIFI_SIGNAL_STRENGTH_COUNT_DATA)
        for (int i = 0; i < NUM_WIFI_SIGNAL_STRENGTH_BINS; ++i) {
            final long wssToken = proto.start(SystemProto.WIFI_SIGNAL_STRENGTH);
            proto.write(SystemProto.WifiSignalStrength.NAME, i);
            dumpTimer(proto, SystemProto.WifiSignalStrength.TOTAL, getWifiSignalStrengthTimer(i),
                    rawRealtimeUs, which);
            proto.end(wssToken);
        }

        // Wifi state (WIFI_STATE_TIME_DATA and WIFI_STATE_COUNT_DATA)
        for (int i = 0; i < NUM_WIFI_STATES; ++i) {
            final long wsToken = proto.start(SystemProto.WIFI_STATE);
            proto.write(SystemProto.WifiState.NAME, i);
            dumpTimer(proto, SystemProto.WifiState.TOTAL, getWifiStateTimer(i),
                    rawRealtimeUs, which);
            proto.end(wsToken);
        }

        // Wifi supplicant state (WIFI_SUPPL_STATE_TIME_DATA and WIFI_SUPPL_STATE_COUNT_DATA)
        for (int i = 0; i < NUM_WIFI_SUPPL_STATES; ++i) {
            final long wssToken = proto.start(SystemProto.WIFI_SUPPLICANT_STATE);
            proto.write(SystemProto.WifiSupplicantState.NAME, i);
            dumpTimer(proto, SystemProto.WifiSupplicantState.TOTAL, getWifiSupplStateTimer(i),
                    rawRealtimeUs, which);
            proto.end(wssToken);
        }

        proto.end(sToken);
    }
}
