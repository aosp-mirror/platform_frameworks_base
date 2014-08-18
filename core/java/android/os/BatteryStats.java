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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.telephony.SignalStrength;
import android.text.format.DateFormat;
import android.util.Printer;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;

/**
 * A class providing access to battery usage statistics, including information on
 * wakelocks, processes, packages, and services.  All times are represented in microseconds
 * except where indicated otherwise.
 * @hide
 */
public abstract class BatteryStats implements Parcelable {

    private static final boolean LOCAL_LOGV = false;

    /** @hide */
    public static final String SERVICE_NAME = "batterystats";

    /**
     * A constant indicating a partial wake lock timer.
     */
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
     * A constant indicating an audio turn on timer
     */
    public static final int AUDIO_TURNED_ON = 7;

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
     * Include all of the data in the stats, including previously saved data.
     */
    public static final int STATS_SINCE_CHARGED = 0;

    /**
     * Include only the current run in the stats.
     */
    public static final int STATS_CURRENT = 1;

    /**
     * Include only the run since the last time the device was unplugged in the stats.
     */
    public static final int STATS_SINCE_UNPLUGGED = 2;

    // NOTE: Update this list if you add/change any stats above.
    // These characters are supposed to represent "total", "last", "current", 
    // and "unplugged". They were shortened for efficiency sake.
    private static final String[] STAT_NAMES = { "l", "c", "u" };

    /**
     * Bump the version on this if the checkin format changes.
     */
    private static final int BATTERY_STATS_CHECKIN_VERSION = 9;
    
    private static final long BYTES_PER_KB = 1024;
    private static final long BYTES_PER_MB = 1048576; // 1024^2
    private static final long BYTES_PER_GB = 1073741824; //1024^3
    
    private static final String VERSION_DATA = "vers";
    private static final String UID_DATA = "uid";
    private static final String APK_DATA = "apk";
    private static final String PROCESS_DATA = "pr";
    private static final String SENSOR_DATA = "sr";
    private static final String VIBRATOR_DATA = "vib";
    private static final String FOREGROUND_DATA = "fg";
    private static final String STATE_TIME_DATA = "st";
    private static final String WAKELOCK_DATA = "wl";
    private static final String SYNC_DATA = "sy";
    private static final String JOB_DATA = "jb";
    private static final String KERNEL_WAKELOCK_DATA = "kwl";
    private static final String WAKEUP_REASON_DATA = "wr";
    private static final String NETWORK_DATA = "nt";
    private static final String USER_ACTIVITY_DATA = "ua";
    private static final String BATTERY_DATA = "bt";
    private static final String BATTERY_DISCHARGE_DATA = "dc";
    private static final String BATTERY_LEVEL_DATA = "lv";
    private static final String WIFI_DATA = "wfl";
    private static final String MISC_DATA = "m";
    private static final String GLOBAL_NETWORK_DATA = "gn";
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
    private static final String BLUETOOTH_STATE_TIME_DATA = "bst";
    private static final String BLUETOOTH_STATE_COUNT_DATA = "bsc";
    private static final String POWER_USE_SUMMARY_DATA = "pws";
    private static final String POWER_USE_ITEM_DATA = "pwi";
    private static final String DISCHARGE_STEP_DATA = "dsd";
    private static final String CHARGE_STEP_DATA = "csd";
    private static final String DISCHARGE_TIME_REMAIN_DATA = "dtr";
    private static final String CHARGE_TIME_REMAIN_DATA = "ctr";

    private final StringBuilder mFormatBuilder = new StringBuilder(32);
    private final Formatter mFormatter = new Formatter(mFormatBuilder);

    /**
     * State for keeping track of counting information.
     */
    public static abstract class Counter {

        /**
         * Returns the count associated with this Counter for the
         * selected type of statistics.
         *
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         */
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
     * State for keeping track of timing information.
     */
    public static abstract class Timer {

        /**
         * Returns the count associated with this Timer for the
         * selected type of statistics.
         *
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         */
        public abstract int getCountLocked(int which);

        /**
         * Returns the total time in microseconds associated with this Timer for the
         * selected type of statistics.
         *
         * @param elapsedRealtimeUs current elapsed realtime of system in microseconds
         * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT
         * @return a time in microseconds
         */
        public abstract long getTotalTimeLocked(long elapsedRealtimeUs, int which);

        /**
         * Temporary for debugging.
         */
        public abstract void logState(Printer pw, String prefix);
    }

    /**
     * The statistics associated with a particular uid.
     */
    public static abstract class Uid {

        /**
         * Returns a mapping containing wakelock statistics.
         *
         * @return a Map from Strings to Uid.Wakelock objects.
         */
        public abstract Map<String, ? extends Wakelock> getWakelockStats();

        /**
         * Returns a mapping containing sync statistics.
         *
         * @return a Map from Strings to Timer objects.
         */
        public abstract Map<String, ? extends Timer> getSyncStats();

        /**
         * Returns a mapping containing scheduled job statistics.
         *
         * @return a Map from Strings to Timer objects.
         */
        public abstract Map<String, ? extends Timer> getJobStats();

        /**
         * The statistics associated with a particular wake lock.
         */
        public static abstract class Wakelock {
            public abstract Timer getWakeTime(int type);
        }

        /**
         * Returns a mapping containing sensor statistics.
         *
         * @return a Map from Integer sensor ids to Uid.Sensor objects.
         */
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
        public abstract Map<String, ? extends Proc> getProcessStats();

        /**
         * Returns a mapping containing package statistics.
         *
         * @return a Map from Strings to Uid.Pkg objects.
         */
        public abstract Map<String, ? extends Pkg> getPackageStats();
        
        /**
         * {@hide}
         */
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
        public abstract long getWifiRunningTime(long elapsedRealtimeUs, int which);
        public abstract long getFullWifiLockTime(long elapsedRealtimeUs, int which);
        public abstract long getWifiScanTime(long elapsedRealtimeUs, int which);
        public abstract long getWifiBatchedScanTime(int csphBin, long elapsedRealtimeUs, int which);
        public abstract long getWifiMulticastTime(long elapsedRealtimeUs, int which);
        public abstract long getAudioTurnedOnTime(long elapsedRealtimeUs, int which);
        public abstract long getVideoTurnedOnTime(long elapsedRealtimeUs, int which);
        public abstract Timer getForegroundActivityTimer();

        // Time this uid has any processes in foreground state.
        public static final int PROCESS_STATE_FOREGROUND = 0;
        // Time this uid has any process in active state (not cached).
        public static final int PROCESS_STATE_ACTIVE = 1;
        // Time this uid has any processes running at all.
        public static final int PROCESS_STATE_RUNNING = 2;
        // Total number of process states we track.
        public static final int NUM_PROCESS_STATE = 3;

        static final String[] PROCESS_STATE_NAMES = {
            "Foreground", "Active", "Running"
        };

        public abstract long getProcessStateTime(int state, long elapsedRealtimeUs, int which);

        public abstract Timer getVibratorOnTimer();

        public static final int NUM_WIFI_BATCHED_SCAN_BINS = 5;

        /**
         * Note that these must match the constants in android.os.PowerManager.
         * Also, if the user activity types change, the BatteryStatsImpl.VERSION must
         * also be bumped.
         */
        static final String[] USER_ACTIVITY_TYPES = {
            "other", "button", "touch"
        };
        
        public static final int NUM_USER_ACTIVITY_TYPES = 3;

        public abstract void noteUserActivityLocked(int type);
        public abstract boolean hasUserActivity();
        public abstract int getUserActivityCount(int type, int which);

        public abstract boolean hasNetworkActivity();
        public abstract long getNetworkActivityBytes(int type, int which);
        public abstract long getNetworkActivityPackets(int type, int which);
        public abstract long getMobileRadioActiveTime(int which);
        public abstract int getMobileRadioActiveCount(int which);

        public static abstract class Sensor {
            /*
             * FIXME: it's not correct to use this magic value because it
             * could clash with a sensor handle (which are defined by
             * the sensor HAL, and therefore out of our control
             */
            // Magic sensor number for the GPS.
            public static final int GPS = -10000;
            
            public abstract int getHandle();
            
            public abstract Timer getSensorTime();
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

            public static class ExcessivePower {
                public static final int TYPE_WAKE = 1;
                public static final int TYPE_CPU = 2;

                public int type;
                public long overTime;
                public long usedTime;
            }

            /**
             * Returns true if this process is still active in the battery stats.
             */
            public abstract boolean isActive();

            /**
             * Returns the total time (in 1/100 sec) spent executing in user code.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            public abstract long getUserTime(int which);

            /**
             * Returns the total time (in 1/100 sec) spent executing in system code.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            public abstract long getSystemTime(int which);

            /**
             * Returns the number of times the process has been started.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            public abstract int getStarts(int which);

            /**
             * Returns the cpu time spent in microseconds while the process was in the foreground.
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             * @return foreground cpu time in microseconds
             */
            public abstract long getForegroundTime(int which);

            /**
             * Returns the approximate cpu time spent in microseconds, at a certain CPU speed.
             * @param speedStep the index of the CPU speed. This is not the actual speed of the
             * CPU.
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             * @see BatteryStats#getCpuSpeedSteps()
             */
            public abstract long getTimeAtCpuSpeedStep(int speedStep, int which);

            public abstract int countExcessivePowers();

            public abstract ExcessivePower getExcessivePower(int i);
        }

        /**
         * The statistics associated with a particular package.
         */
        public static abstract class Pkg {

            /**
             * Returns the number of times this package has done something that could wake up the
             * device from sleep.
             *
             * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
             */
            public abstract int getWakeups(int which);

            /**
             * Returns a mapping containing service statistics.
             */
            public abstract Map<String, ? extends Serv> getServiceStats();

            /**
             * The statistics associated with a particular service.
             */
            public abstract class Serv {

                /**
                 * Returns the amount of time spent started.
                 *
                 * @param batteryUptime elapsed uptime on battery in microseconds.
                 * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
                 * @return
                 */
                public abstract long getStartTime(long batteryUptime, int which);

                /**
                 * Returns the total number of times startService() has been called.
                 *
                 * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
                 */
                public abstract int getStarts(int which);

                /**
                 * Returns the total number times the service has been launched.
                 *
                 * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
                 */
                public abstract int getLaunches(int which);
            }
        }
    }

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
        public boolean equals(Object o) {
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

    public final static class HistoryItem implements Parcelable {
        public HistoryItem next;

        // The time of this event in milliseconds, as per SystemClock.elapsedRealtime().
        public long time;

        public static final byte CMD_UPDATE = 0;        // These can be written as deltas
        public static final byte CMD_NULL = -1;
        public static final byte CMD_START = 4;
        public static final byte CMD_CURRENT_TIME = 5;
        public static final byte CMD_OVERFLOW = 6;
        public static final byte CMD_RESET = 7;

        public byte cmd = CMD_NULL;
        
        /**
         * Return whether the command code is a delta data update.
         */
        public boolean isDeltaData() {
            return cmd == CMD_UPDATE;
        }

        public byte batteryLevel;
        public byte batteryStatus;
        public byte batteryHealth;
        public byte batteryPlugType;
        
        public short batteryTemperature;
        public char batteryVoltage;
        
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
        public static final int STATE_WIFI_MULTICAST_ON_FLAG = 1<<26;
        public static final int STATE_MOBILE_RADIO_ACTIVE_FLAG = 1<<25;
        // These are on the lower bits used for the command; if they change
        // we need to write another int of data.
        public static final int STATE_SENSOR_ON_FLAG = 1<<23;
        public static final int STATE_AUDIO_ON_FLAG = 1<<22;
        public static final int STATE_PHONE_SCANNING_FLAG = 1<<21;
        public static final int STATE_SCREEN_ON_FLAG = 1<<20;
        public static final int STATE_BATTERY_PLUGGED_FLAG = 1<<19;
        public static final int STATE_PHONE_IN_CALL_FLAG = 1<<18;
        public static final int STATE_BLUETOOTH_ON_FLAG = 1<<16;

        public static final int MOST_INTERESTING_STATES =
            STATE_BATTERY_PLUGGED_FLAG | STATE_SCREEN_ON_FLAG
            | STATE_PHONE_IN_CALL_FLAG | STATE_BLUETOOTH_ON_FLAG;

        public int states;

        // Constants from WIFI_SUPPL_STATE_*
        public static final int STATE2_WIFI_SUPPL_STATE_SHIFT = 0;
        public static final int STATE2_WIFI_SUPPL_STATE_MASK = 0xf;
        // Values for NUM_WIFI_SIGNAL_STRENGTH_BINS
        public static final int STATE2_WIFI_SIGNAL_STRENGTH_SHIFT = 4;
        public static final int STATE2_WIFI_SIGNAL_STRENGTH_MASK =
                0x7 << STATE2_WIFI_SIGNAL_STRENGTH_SHIFT;

        public static final int STATE2_LOW_POWER_FLAG = 1<<31;
        public static final int STATE2_VIDEO_ON_FLAG = 1<<30;
        public static final int STATE2_WIFI_RUNNING_FLAG = 1<<29;
        public static final int STATE2_WIFI_ON_FLAG = 1<<28;
        public static final int STATE2_FLASHLIGHT_FLAG = 1<<27;

        public static final int MOST_INTERESTING_STATES2 =
            STATE2_LOW_POWER_FLAG | STATE2_WIFI_ON_FLAG;

        public int states2;

        // The wake lock that was acquired at this point.
        public HistoryTag wakelockTag;

        // Kernel wakeup reason at this point.
        public HistoryTag wakeReasonTag;

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
        // Number of event types.
        public static final int EVENT_COUNT = 0x0009;
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

        public HistoryItem() {
        }
        
        public HistoryItem(long time, Parcel src) {
            this.time = time;
            numReadInts = 2;
            readFromParcel(src);
        }
        
        public int describeContents() {
            return 0;
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
            int bat = src.readInt();
            cmd = (byte)(bat&0xff);
            batteryLevel = (byte)((bat>>8)&0xff);
            batteryStatus = (byte)((bat>>16)&0xf);
            batteryHealth = (byte)((bat>>20)&0xf);
            batteryPlugType = (byte)((bat>>24)&0xf);
            int bat2 = src.readInt();
            batteryTemperature = (short)(bat2&0xffff);
            batteryVoltage = (char)((bat2>>16)&0xffff);
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

        public void clear() {
            time = 0;
            cmd = CMD_NULL;
            batteryLevel = 0;
            batteryStatus = 0;
            batteryHealth = 0;
            batteryPlugType = 0;
            batteryTemperature = 0;
            batteryVoltage = 0;
            states = 0;
            states2 = 0;
            wakelockTag = null;
            wakeReasonTag = null;
            eventCode = EVENT_NONE;
            eventTag = null;
        }
        
        public void setTo(HistoryItem o) {
            time = o.time;
            cmd = o.cmd;
            setToCommon(o);
        }

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
                    && states == o.states
                    && states2 == o.states2
                    && currentTime == o.currentTime;
        }

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
                    active = new HashMap<String, SparseIntArray>();
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

    public abstract boolean startIteratingHistoryLocked();

    public abstract int getHistoryStringPoolSize();

    public abstract int getHistoryStringPoolBytes();

    public abstract String getHistoryTagPoolString(int index);

    public abstract int getHistoryTagPoolUid(int index);

    public abstract boolean getNextHistoryLocked(HistoryItem out);

    public abstract void finishIteratingHistoryLocked();

    public abstract boolean startIteratingOldHistoryLocked();

    public abstract boolean getNextOldHistoryLocked(HistoryItem out);

    public abstract void finishIteratingOldHistoryLocked();

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
    public abstract long getScreenOnTime(long elapsedRealtimeUs, int which);
    
    /**
     * Returns the number of times the screen was turned on.
     *
     * {@hide}
     */
    public abstract int getScreenOnCount(int which);

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

    public static final int NUM_SCREEN_BRIGHTNESS_BINS = 5;

    /**
     * Returns the time in microseconds that the screen has been on with
     * the given brightness
     * 
     * {@hide}
     */
    public abstract long getScreenBrightnessTime(int brightnessBin,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the time in microseconds that low power mode has been enabled while the device was
     * running on battery.
     *
     * {@hide}
     */
    public abstract long getLowPowerModeEnabledTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that low power mode was enabled.
     *
     * {@hide}
     */
    public abstract int getLowPowerModeEnabledCount(int which);

    /**
     * Returns the time in microseconds that the phone has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
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
     * Returns the number of times the phone has entered the given signal strength.
     * 
     * {@hide}
     */
    public abstract int getPhoneSignalStrengthCount(int strengthBin, int which);

    /**
     * Returns the time in microseconds that the mobile network has been active
     * (in a high power state).
     *
     * {@hide}
     */
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

    public static final int DATA_CONNECTION_NONE = 0;
    public static final int DATA_CONNECTION_GPRS = 1;
    public static final int DATA_CONNECTION_EDGE = 2;
    public static final int DATA_CONNECTION_UMTS = 3;
    public static final int DATA_CONNECTION_CDMA = 4;
    public static final int DATA_CONNECTION_EVDO_0 = 5;
    public static final int DATA_CONNECTION_EVDO_A = 6;
    public static final int DATA_CONNECTION_1xRTT = 7;
    public static final int DATA_CONNECTION_HSDPA = 8;
    public static final int DATA_CONNECTION_HSUPA = 9;
    public static final int DATA_CONNECTION_HSPA = 10;
    public static final int DATA_CONNECTION_IDEN = 11;
    public static final int DATA_CONNECTION_EVDO_B = 12;
    public static final int DATA_CONNECTION_LTE = 13;
    public static final int DATA_CONNECTION_EHRPD = 14;
    public static final int DATA_CONNECTION_HSPAP = 15;
    public static final int DATA_CONNECTION_OTHER = 16;

    static final String[] DATA_CONNECTION_NAMES = {
        "none", "gprs", "edge", "umts", "cdma", "evdo_0", "evdo_A",
        "1xrtt", "hsdpa", "hsupa", "hspa", "iden", "evdo_b", "lte",
        "ehrpd", "hspap", "other"
    };
    
    public static final int NUM_DATA_CONNECTION_TYPES = DATA_CONNECTION_OTHER+1;
    
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

    public static final int WIFI_SUPPL_STATE_INVALID = 0;
    public static final int WIFI_SUPPL_STATE_DISCONNECTED = 1;
    public static final int WIFI_SUPPL_STATE_INTERFACE_DISABLED = 2;
    public static final int WIFI_SUPPL_STATE_INACTIVE = 3;
    public static final int WIFI_SUPPL_STATE_SCANNING = 4;
    public static final int WIFI_SUPPL_STATE_AUTHENTICATING = 5;
    public static final int WIFI_SUPPL_STATE_ASSOCIATING = 6;
    public static final int WIFI_SUPPL_STATE_ASSOCIATED = 7;
    public static final int WIFI_SUPPL_STATE_FOUR_WAY_HANDSHAKE = 8;
    public static final int WIFI_SUPPL_STATE_GROUP_HANDSHAKE = 9;
    public static final int WIFI_SUPPL_STATE_COMPLETED = 10;
    public static final int WIFI_SUPPL_STATE_DORMANT = 11;
    public static final int WIFI_SUPPL_STATE_UNINITIALIZED = 12;

    public static final int NUM_WIFI_SUPPL_STATES = WIFI_SUPPL_STATE_UNINITIALIZED+1;

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

    public static final BitDescription[] HISTORY_STATE_DESCRIPTIONS
            = new BitDescription[] {
        new BitDescription(HistoryItem.STATE_CPU_RUNNING_FLAG, "running", "r"),
        new BitDescription(HistoryItem.STATE_WAKE_LOCK_FLAG, "wake_lock", "w"),
        new BitDescription(HistoryItem.STATE_SENSOR_ON_FLAG, "sensor", "s"),
        new BitDescription(HistoryItem.STATE_GPS_ON_FLAG, "gps", "g"),
        new BitDescription(HistoryItem.STATE_WIFI_FULL_LOCK_FLAG, "wifi_full_lock", "Wl"),
        new BitDescription(HistoryItem.STATE_WIFI_SCAN_FLAG, "wifi_scan", "Ws"),
        new BitDescription(HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG, "wifi_multicast", "Wm"),
        new BitDescription(HistoryItem.STATE_MOBILE_RADIO_ACTIVE_FLAG, "mobile_radio", "Pr"),
        new BitDescription(HistoryItem.STATE_PHONE_SCANNING_FLAG, "phone_scanning", "Psc"),
        new BitDescription(HistoryItem.STATE_AUDIO_ON_FLAG, "audio", "a"),
        new BitDescription(HistoryItem.STATE_SCREEN_ON_FLAG, "screen", "S"),
        new BitDescription(HistoryItem.STATE_BATTERY_PLUGGED_FLAG, "plugged", "BP"),
        new BitDescription(HistoryItem.STATE_PHONE_IN_CALL_FLAG, "phone_in_call", "Pcl"),
        new BitDescription(HistoryItem.STATE_BLUETOOTH_ON_FLAG, "bluetooth", "b"),
        new BitDescription(HistoryItem.STATE_DATA_CONNECTION_MASK,
                HistoryItem.STATE_DATA_CONNECTION_SHIFT, "data_conn", "Pcn",
                DATA_CONNECTION_NAMES, DATA_CONNECTION_NAMES),
        new BitDescription(HistoryItem.STATE_PHONE_STATE_MASK,
                HistoryItem.STATE_PHONE_STATE_SHIFT, "phone_state", "Pst",
                new String[] {"in", "out", "emergency", "off"},
                new String[] {"in", "out", "em", "off"}),
        new BitDescription(HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_MASK,
                HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_SHIFT, "phone_signal_strength", "Pss",
                SignalStrength.SIGNAL_STRENGTH_NAMES,
                new String[] { "0", "1", "2", "3", "4" }),
        new BitDescription(HistoryItem.STATE_BRIGHTNESS_MASK,
                HistoryItem.STATE_BRIGHTNESS_SHIFT, "brightness", "Sb",
                SCREEN_BRIGHTNESS_NAMES, SCREEN_BRIGHTNESS_SHORT_NAMES),
    };

    public static final BitDescription[] HISTORY_STATE2_DESCRIPTIONS
            = new BitDescription[] {
        new BitDescription(HistoryItem.STATE2_LOW_POWER_FLAG, "low_power", "lp"),
        new BitDescription(HistoryItem.STATE2_VIDEO_ON_FLAG, "video", "v"),
        new BitDescription(HistoryItem.STATE2_WIFI_RUNNING_FLAG, "wifi_running", "Wr"),
        new BitDescription(HistoryItem.STATE2_WIFI_ON_FLAG, "wifi", "W"),
        new BitDescription(HistoryItem.STATE2_FLASHLIGHT_FLAG, "flashlight", "fl"),
        new BitDescription(HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_MASK,
                HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_SHIFT, "wifi_signal_strength", "Wss",
                new String[] { "0", "1", "2", "3", "4" },
                new String[] { "0", "1", "2", "3", "4" }),
        new BitDescription(HistoryItem.STATE2_WIFI_SUPPL_STATE_MASK,
                HistoryItem.STATE2_WIFI_SUPPL_STATE_SHIFT, "wifi_suppl", "Wsp",
                WIFI_SUPPL_STATE_NAMES, WIFI_SUPPL_STATE_SHORT_NAMES),
    };

    public static final String[] HISTORY_EVENT_NAMES = new String[] {
            "null", "proc", "fg", "top", "sync", "wake_lock_in", "job", "user", "userfg"
    };

    public static final String[] HISTORY_EVENT_CHECKIN_NAMES = new String[] {
            "Enl", "Epr", "Efg", "Etp", "Esy", "Ewl", "Ejb", "Eur", "Euf"
    };

    /**
     * Returns the time in microseconds that wifi has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getWifiOnTime(long elapsedRealtimeUs, int which);

    /**
     * Returns the time in microseconds that wifi has been on and the driver has
     * been in the running state while the device was running on battery.
     *
     * {@hide}
     */
    public abstract long getGlobalWifiRunningTime(long elapsedRealtimeUs, int which);

    public static final int WIFI_STATE_OFF = 0;
    public static final int WIFI_STATE_OFF_SCANNING = 1;
    public static final int WIFI_STATE_ON_NO_NETWORKS = 2;
    public static final int WIFI_STATE_ON_DISCONNECTED = 3;
    public static final int WIFI_STATE_ON_CONNECTED_STA = 4;
    public static final int WIFI_STATE_ON_CONNECTED_P2P = 5;
    public static final int WIFI_STATE_ON_CONNECTED_STA_P2P = 6;
    public static final int WIFI_STATE_SOFT_AP = 7;

    static final String[] WIFI_STATE_NAMES = {
        "off", "scanning", "no_net", "disconn",
        "sta", "p2p", "sta_p2p", "soft_ap"
    };

    public static final int NUM_WIFI_STATES = WIFI_STATE_SOFT_AP+1;

    /**
     * Returns the time in microseconds that WiFi has been running in the given state.
     *
     * {@hide}
     */
    public abstract long getWifiStateTime(int wifiState,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that WiFi has entered the given state.
     *
     * {@hide}
     */
    public abstract int getWifiStateCount(int wifiState, int which);

    /**
     * Returns the time in microseconds that the wifi supplicant has been
     * in a given state.
     *
     * {@hide}
     */
    public abstract long getWifiSupplStateTime(int state, long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that the wifi supplicant has transitioned
     * to a given state.
     *
     * {@hide}
     */
    public abstract int getWifiSupplStateCount(int state, int which);

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
     * Returns the time in microseconds that bluetooth has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getBluetoothOnTime(long elapsedRealtimeUs, int which);
    
    public abstract int getBluetoothPingCount();

    public static final int BLUETOOTH_STATE_INACTIVE = 0;
    public static final int BLUETOOTH_STATE_LOW = 1;
    public static final int BLUETOOTH_STATE_MEDIUM = 2;
    public static final int BLUETOOTH_STATE_HIGH = 3;

    static final String[] BLUETOOTH_STATE_NAMES = {
        "inactive", "low", "med", "high"
    };

    public static final int NUM_BLUETOOTH_STATES = BLUETOOTH_STATE_HIGH +1;

    /**
     * Returns the time in microseconds that Bluetooth has been running in the
     * given active state.
     *
     * {@hide}
     */
    public abstract long getBluetoothStateTime(int bluetoothState,
            long elapsedRealtimeUs, int which);

    /**
     * Returns the number of times that Bluetooth has entered the given active state.
     *
     * {@hide}
     */
    public abstract int getBluetoothStateCount(int bluetoothState, int which);

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

    public static final int NETWORK_MOBILE_RX_DATA = 0;
    public static final int NETWORK_MOBILE_TX_DATA = 1;
    public static final int NETWORK_WIFI_RX_DATA = 2;
    public static final int NETWORK_WIFI_TX_DATA = 3;

    public static final int NUM_NETWORK_ACTIVITY_TYPES = NETWORK_WIFI_TX_DATA + 1;

    public abstract long getNetworkActivityBytes(int type, int which);
    public abstract long getNetworkActivityPackets(int type, int which);

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
     * Returns a SparseArray containing the statistics for each uid.
     */
    public abstract SparseArray<? extends Uid> getUidStats();

    /**
     * Returns the current battery uptime in microseconds.
     *
     * @param curTime the amount of elapsed realtime in microseconds.
     */
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
     * Returns the total, last, or current battery uptime in microseconds.
     *
     * @param curTime the elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeBatteryUptime(long curTime, int which);

    /**
     * Returns the total, last, or current battery realtime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeBatteryRealtime(long curTime, int which);

    /**
     * Returns the total, last, or current battery screen off uptime in microseconds.
     *
     * @param curTime the elapsed realtime in microseconds.
     * @param which one of STATS_SINCE_CHARGED, STATS_SINCE_UNPLUGGED, or STATS_CURRENT.
     */
    public abstract long computeBatteryScreenOffUptime(long curTime, int which);

    /**
     * Returns the total, last, or current battery screen off realtime in microseconds.
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
     * @param curTime The current elepsed realtime in microseconds.
     */
    public abstract long computeBatteryTimeRemaining(long curTime);

    // The part of a step duration that is the actual time.
    public static final long STEP_LEVEL_TIME_MASK = 0x000000ffffffffffL;

    // Bits in a step duration that are the new battery level we are at.
    public static final long STEP_LEVEL_LEVEL_MASK = 0x0000ff0000000000L;
    public static final long STEP_LEVEL_LEVEL_SHIFT = 40;

    // Bits in a step duration that are the initial mode we were in at that step.
    public static final long STEP_LEVEL_INITIAL_MODE_MASK = 0x00ff000000000000L;
    public static final long STEP_LEVEL_INITIAL_MODE_SHIFT = 48;

    // Bits in a step duration that indicate which modes changed during that step.
    public static final long STEP_LEVEL_MODIFIED_MODE_MASK = 0xff00000000000000L;
    public static final long STEP_LEVEL_MODIFIED_MODE_SHIFT = 56;

    // Step duration mode: the screen is on, off, dozed, etc; value is Display.STATE_* - 1.
    public static final int STEP_LEVEL_MODE_SCREEN_STATE = 0x03;

    // Step duration mode: power save is on.
    public static final int STEP_LEVEL_MODE_POWER_SAVE = 0x04;

    /**
     * Return the historical number of discharge steps we currently have.
     */
    public abstract int getNumDischargeStepDurations();

    /**
     * Return the array of discharge step durations; the number of valid
     * items in it is returned by {@link #getNumDischargeStepDurations()}.
     * These values are in milliseconds.
     */
    public abstract long[] getDischargeStepDurationsArray();

    /**
     * Compute an approximation for how much time (in microseconds) remains until the battery
     * is fully charged.  Returns -1 if no time can be computed: either there is not
     * enough current data to make a decision, or the battery is currently
     * discharging.
     *
     * @param curTime The current elepsed realtime in microseconds.
     */
    public abstract long computeChargeTimeRemaining(long curTime);

    /**
     * Return the historical number of charge steps we currently have.
     */
    public abstract int getNumChargeStepDurations();

    /**
     * Return the array of charge step durations; the number of valid
     * items in it is returned by {@link #getNumChargeStepDurations()}.
     * These values are in milliseconds.
     */
    public abstract long[] getChargeStepDurationsArray();

    public abstract Map<String, ? extends LongCounter> getWakeupReasonStats();

    public abstract Map<String, ? extends Timer> getKernelWakelockStats();

    /** Returns the number of different speeds that the CPU can run at */
    public abstract int getCpuSpeedSteps();

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

    public final static void formatTime(StringBuilder sb, long time) {
        long sec = time / 100;
        formatTimeRaw(sb, sec);
        sb.append((time - (sec * 100)) * 10);
        sb.append("ms ");
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
                return ", ";
            }
        }
        return linePrefix;
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
        if (timer != null) {
            totalTimeMicros = timer.getTotalTimeLocked(elapsedRealtimeUs, which);
            count = timer.getCountLocked(which); 
        }
        sb.append(linePrefix);
        sb.append((totalTimeMicros + 500) / 1000); // microseconds to milliseconds with rounding
        sb.append(',');
        sb.append(name != null ? name + "," : "");
        sb.append(count);
        return ",";
    }
    
    /**
     * Dump a comma-separated line of values for terse checkin mode.
     * 
     * @param pw the PageWriter to dump log to
     * @param category category of data (e.g. "total", "last", "unplugged", "current" )
     * @param type type of data (e.g. "wakelock", "sensor", "process", "apk" ,  "process", "network")
     * @param args type-dependent data arguments
     */
    private static final void dumpLine(PrintWriter pw, int uid, String category, String type, 
           Object... args ) {
        pw.print(BATTERY_STATS_CHECKIN_VERSION); pw.print(',');
        pw.print(uid); pw.print(',');
        pw.print(category); pw.print(',');
        pw.print(type);
        
        for (Object arg : args) {  
            pw.print(',');
            pw.print(arg);
        }
        pw.println();
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
     * NOTE: all times are expressed in 'ms'.
     */
    public final void dumpCheckinLocked(Context context, PrintWriter pw, int which, int reqUid,
            boolean wifiOnly) {
        final long rawUptime = SystemClock.uptimeMillis() * 1000;
        final long rawRealtime = SystemClock.elapsedRealtime() * 1000;
        final long batteryUptime = getBatteryUptime(rawUptime);
        final long whichBatteryUptime = computeBatteryUptime(rawUptime, which);
        final long whichBatteryRealtime = computeBatteryRealtime(rawRealtime, which);
        final long whichBatteryScreenOffUptime = computeBatteryScreenOffUptime(rawUptime, which);
        final long whichBatteryScreenOffRealtime = computeBatteryScreenOffRealtime(rawRealtime,
                which);
        final long totalRealtime = computeRealtime(rawRealtime, which);
        final long totalUptime = computeUptime(rawUptime, which);
        final long screenOnTime = getScreenOnTime(rawRealtime, which);
        final long interactiveTime = getInteractiveTime(rawRealtime, which);
        final long lowPowerModeEnabledTime = getLowPowerModeEnabledTime(rawRealtime, which);
        final long phoneOnTime = getPhoneOnTime(rawRealtime, which);
        final long wifiOnTime = getWifiOnTime(rawRealtime, which);
        final long wifiRunningTime = getGlobalWifiRunningTime(rawRealtime, which);
        final long bluetoothOnTime = getBluetoothOnTime(rawRealtime, which);

        StringBuilder sb = new StringBuilder(128);
        
        SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();
        
        String category = STAT_NAMES[which];

        // Dump "battery" stat
        dumpLine(pw, 0 /* uid */, category, BATTERY_DATA, 
                which == STATS_SINCE_CHARGED ? getStartCount() : "N/A",
                whichBatteryRealtime / 1000, whichBatteryUptime / 1000,
                totalRealtime / 1000, totalUptime / 1000,
                getStartClockTime(),
                whichBatteryScreenOffRealtime / 1000, whichBatteryScreenOffUptime / 1000);
        
        // Calculate wakelock times across all uids.
        long fullWakeLockTimeTotal = 0;
        long partialWakeLockTimeTotal = 0;
        
        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);

            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> ent 
                        : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    
                    Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                    if (fullWakeTimer != null) {
                        fullWakeLockTimeTotal += fullWakeTimer.getTotalTimeLocked(rawRealtime,
                                which);
                    }

                    Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                    if (partialWakeTimer != null) {
                        partialWakeLockTimeTotal += partialWakeTimer.getTotalTimeLocked(
                            rawRealtime, which);
                    }
                }
            }
        }
        
        long mobileRxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
        long mobileTxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
        long wifiRxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
        long wifiTxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
        long mobileRxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
        long mobileTxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
        long wifiRxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
        long wifiTxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);

        // Dump network stats
        dumpLine(pw, 0 /* uid */, category, GLOBAL_NETWORK_DATA,
                mobileRxTotalBytes, mobileTxTotalBytes, wifiRxTotalBytes, wifiTxTotalBytes,
                mobileRxTotalPackets, mobileTxTotalPackets, wifiRxTotalPackets, wifiTxTotalPackets);

        // Dump misc stats
        dumpLine(pw, 0 /* uid */, category, MISC_DATA,
                screenOnTime / 1000, phoneOnTime / 1000, wifiOnTime / 1000,
                wifiRunningTime / 1000, bluetoothOnTime / 1000,
                mobileRxTotalBytes, mobileTxTotalBytes, wifiRxTotalBytes, wifiTxTotalBytes,
                fullWakeLockTimeTotal / 1000, partialWakeLockTimeTotal / 1000,
                0 /*legacy input event count*/, getMobileRadioActiveTime(rawRealtime, which) / 1000,
                getMobileRadioActiveAdjustedTime(which) / 1000, interactiveTime / 1000,
                lowPowerModeEnabledTime / 1000);
        
        // Dump screen brightness stats
        Object[] args = new Object[NUM_SCREEN_BRIGHTNESS_BINS];
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            args[i] = getScreenBrightnessTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SCREEN_BRIGHTNESS_DATA, args);
        
        // Dump signal strength stats
        args = new Object[SignalStrength.NUM_SIGNAL_STRENGTH_BINS];
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            args[i] = getPhoneSignalStrengthTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SIGNAL_STRENGTH_TIME_DATA, args);
        dumpLine(pw, 0 /* uid */, category, SIGNAL_SCANNING_TIME_DATA,
                getPhoneSignalScanningTime(rawRealtime, which) / 1000);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
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

        // Dump bluetooth state stats
        args = new Object[NUM_BLUETOOTH_STATES];
        for (int i=0; i<NUM_BLUETOOTH_STATES; i++) {
            args[i] = getBluetoothStateTime(i, rawRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, BLUETOOTH_STATE_TIME_DATA, args);
        for (int i=0; i<NUM_BLUETOOTH_STATES; i++) {
            args[i] = getBluetoothStateCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, BLUETOOTH_STATE_COUNT_DATA, args);

        if (which == STATS_SINCE_UNPLUGGED) {
            dumpLine(pw, 0 /* uid */, category, BATTERY_LEVEL_DATA, getDischargeStartLevel(),
                    getDischargeCurrentLevel());
        }
        
        if (which == STATS_SINCE_UNPLUGGED) {
            dumpLine(pw, 0 /* uid */, category, BATTERY_DISCHARGE_DATA,
                    getDischargeStartLevel()-getDischargeCurrentLevel(),
                    getDischargeStartLevel()-getDischargeCurrentLevel(),
                    getDischargeAmountScreenOn(), getDischargeAmountScreenOff());
        } else {
            dumpLine(pw, 0 /* uid */, category, BATTERY_DISCHARGE_DATA,
                    getLowDischargeAmountSinceCharge(), getHighDischargeAmountSinceCharge(),
                    getDischargeAmountScreenOnSinceCharge(),
                    getDischargeAmountScreenOffSinceCharge());
        }
        
        if (reqUid < 0) {
            Map<String, ? extends Timer> kernelWakelocks = getKernelWakelockStats();
            if (kernelWakelocks.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : kernelWakelocks.entrySet()) {
                    sb.setLength(0);
                    printWakeLockCheckin(sb, ent.getValue(), rawRealtime, null, which, "");
                    dumpLine(pw, 0 /* uid */, category, KERNEL_WAKELOCK_DATA, ent.getKey(),
                            sb.toString());
                }
            }
            Map<String, ? extends LongCounter> wakeupReasons = getWakeupReasonStats();
            if (wakeupReasons.size() > 0) {
                for (Map.Entry<String, ? extends LongCounter> ent : wakeupReasons.entrySet()) {
                    dumpLine(pw, 0 /* uid */, category, WAKEUP_REASON_DATA,
                            "\"" + ent.getKey() + "\"", ent.getValue().getCountLocked(which));
                }
            }
        }
        
        BatteryStatsHelper helper = new BatteryStatsHelper(context, false, wifiOnly);
        helper.create(this);
        helper.refreshStats(which, UserHandle.USER_ALL);
        List<BatterySipper> sippers = helper.getUsageList();
        if (sippers != null && sippers.size() > 0) {
            dumpLine(pw, 0 /* uid */, category, POWER_USE_SUMMARY_DATA,
                    BatteryStatsHelper.makemAh(helper.getPowerProfile().getBatteryCapacity()),
                    BatteryStatsHelper.makemAh(helper.getComputedPower()),
                    BatteryStatsHelper.makemAh(helper.getMinDrainedPower()),
                    BatteryStatsHelper.makemAh(helper.getMaxDrainedPower()));
            for (int i=0; i<sippers.size(); i++) {
                BatterySipper bs = sippers.get(i);
                int uid = 0;
                String label;
                switch (bs.drainType) {
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
                    default:
                        label = "???";
                }
                dumpLine(pw, uid, category, POWER_USE_ITEM_DATA, label,
                        BatteryStatsHelper.makemAh(bs.value));
            }
        }

        for (int iu = 0; iu < NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            if (reqUid >= 0 && uid != reqUid) {
                continue;
            }
            Uid u = uidStats.valueAt(iu);
            // Dump Network stats per uid, if any
            long mobileBytesRx = u.getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
            long mobileBytesTx = u.getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
            long wifiBytesRx = u.getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
            long wifiBytesTx = u.getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
            long mobilePacketsRx = u.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
            long mobilePacketsTx = u.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
            long mobileActiveTime = u.getMobileRadioActiveTime(which);
            int mobileActiveCount = u.getMobileRadioActiveCount(which);
            long wifiPacketsRx = u.getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
            long wifiPacketsTx = u.getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);
            long fullWifiLockOnTime = u.getFullWifiLockTime(rawRealtime, which);
            long wifiScanTime = u.getWifiScanTime(rawRealtime, which);
            long uidWifiRunningTime = u.getWifiRunningTime(rawRealtime, which);

            if (mobileBytesRx > 0 || mobileBytesTx > 0 || wifiBytesRx > 0 || wifiBytesTx > 0
                    || mobilePacketsRx > 0 || mobilePacketsTx > 0 || wifiPacketsRx > 0
                    || wifiPacketsTx > 0 || mobileActiveTime > 0 || mobileActiveCount > 0) {
                dumpLine(pw, uid, category, NETWORK_DATA, mobileBytesRx, mobileBytesTx,
                        wifiBytesRx, wifiBytesTx,
                        mobilePacketsRx, mobilePacketsTx,
                        wifiPacketsRx, wifiPacketsTx,
                        mobileActiveTime, mobileActiveCount);
            }

            if (fullWifiLockOnTime != 0 || wifiScanTime != 0
                    || uidWifiRunningTime != 0) {
                dumpLine(pw, uid, category, WIFI_DATA,
                        fullWifiLockOnTime, wifiScanTime, uidWifiRunningTime);
            }

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
            
            Map<String, ? extends Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                for (Map.Entry<String, ? extends Uid.Wakelock> ent : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    String linePrefix = "";
                    sb.setLength(0);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_FULL), 
                            rawRealtime, "f", which, linePrefix);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_PARTIAL), 
                            rawRealtime, "p", which, linePrefix);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), 
                            rawRealtime, "w", which, linePrefix);
                    
                    // Only log if we had at lease one wakelock...
                    if (sb.length() > 0) {
                        String name = ent.getKey();
                        if (name.indexOf(',') >= 0) {
                            name = name.replace(',', '_');
                        }
                        dumpLine(pw, uid, category, WAKELOCK_DATA, name, sb.toString());
                    }
                }
            }

            Map<String, ? extends Timer> syncs = u.getSyncStats();
            if (syncs.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : syncs.entrySet()) {
                    Timer timer = ent.getValue();
                    // Convert from microseconds to milliseconds with rounding
                    long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                    int count = timer.getCountLocked(which);
                    if (totalTime != 0) {
                        dumpLine(pw, uid, category, SYNC_DATA, ent.getKey(), totalTime, count);
                    }
                }
            }

            Map<String, ? extends Timer> jobs = u.getJobStats();
            if (jobs.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : jobs.entrySet()) {
                    Timer timer = ent.getValue();
                    // Convert from microseconds to milliseconds with rounding
                    long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                    int count = timer.getCountLocked(which);
                    if (totalTime != 0) {
                        dumpLine(pw, uid, category, JOB_DATA, ent.getKey(), totalTime, count);
                    }
                }
            }

            SparseArray<? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            int NSE = sensors.size();
            for (int ise=0; ise<NSE; ise++) {
                Uid.Sensor se = sensors.valueAt(ise);
                int sensorNumber = sensors.keyAt(ise);
                Timer timer = se.getSensorTime();
                if (timer != null) {
                    // Convert from microseconds to milliseconds with rounding
                    long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                    int count = timer.getCountLocked(which);
                    if (totalTime != 0) {
                        dumpLine(pw, uid, category, SENSOR_DATA, sensorNumber, totalTime, count);
                    }
                }
            }

            Timer vibTimer = u.getVibratorOnTimer();
            if (vibTimer != null) {
                // Convert from microseconds to milliseconds with rounding
                long totalTime = (vibTimer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                int count = vibTimer.getCountLocked(which);
                if (totalTime != 0) {
                    dumpLine(pw, uid, category, VIBRATOR_DATA, totalTime, count);
                }
            }

            Timer fgTimer = u.getForegroundActivityTimer();
            if (fgTimer != null) {
                // Convert from microseconds to milliseconds with rounding
                long totalTime = (fgTimer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                int count = fgTimer.getCountLocked(which);
                if (totalTime != 0) {
                    dumpLine(pw, uid, category, FOREGROUND_DATA, totalTime, count);
                }
            }

            Object[] stateTimes = new Object[Uid.NUM_PROCESS_STATE];
            long totalStateTime = 0;
            for (int ips=0; ips<Uid.NUM_PROCESS_STATE; ips++) {
                totalStateTime += u.getProcessStateTime(ips, rawRealtime, which);
                stateTimes[ips] = (totalStateTime + 500) / 1000;
            }
            if (totalStateTime > 0) {
                dumpLine(pw, uid, category, STATE_TIME_DATA, stateTimes);
            }

            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            if (processStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                        : processStats.entrySet()) {
                    Uid.Proc ps = ent.getValue();

                    final long userMillis = ps.getUserTime(which) * 10;
                    final long systemMillis = ps.getSystemTime(which) * 10;
                    final long foregroundMillis = ps.getForegroundTime(which) * 10;
                    final long starts = ps.getStarts(which);

                    if (userMillis != 0 || systemMillis != 0 || foregroundMillis != 0
                            || starts != 0) {
                        dumpLine(pw, uid, category, PROCESS_DATA, ent.getKey(), userMillis,
                                systemMillis, foregroundMillis, starts);
                    }
                }
            }

            Map<String, ? extends BatteryStats.Uid.Pkg> packageStats = u.getPackageStats();
            if (packageStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Pkg> ent
                        : packageStats.entrySet()) {
              
                    Uid.Pkg ps = ent.getValue();
                    int wakeups = ps.getWakeups(which);
                    Map<String, ? extends  Uid.Pkg.Serv> serviceStats = ps.getServiceStats();
                    for (Map.Entry<String, ? extends BatteryStats.Uid.Pkg.Serv> sent
                            : serviceStats.entrySet()) {
                        BatteryStats.Uid.Pkg.Serv ss = sent.getValue();
                        long startTime = ss.getStartTime(batteryUptime, which);
                        int starts = ss.getStarts(which);
                        int launches = ss.getLaunches(which);
                        if (startTime != 0 || starts != 0 || launches != 0) {
                            dumpLine(pw, uid, category, APK_DATA, 
                                    wakeups, // wakeup alarms
                                    ent.getKey(), // Apk
                                    sent.getKey(), // service
                                    startTime / 1000, // time spent started, in ms
                                    starts,
                                    launches);
                        }
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
        final long rawUptime = SystemClock.uptimeMillis() * 1000;
        final long rawRealtime = SystemClock.elapsedRealtime() * 1000;
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

        StringBuilder sb = new StringBuilder(128);
        
        SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();

        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Time on battery: ");
                formatTimeMs(sb, whichBatteryRealtime / 1000); sb.append("(");
                sb.append(formatRatioLocked(whichBatteryRealtime, totalRealtime));
                sb.append(") realtime, ");
                formatTimeMs(sb, whichBatteryUptime / 1000);
                sb.append("("); sb.append(formatRatioLocked(whichBatteryUptime, totalRealtime));
                sb.append(") uptime");
        pw.println(sb.toString());
        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Time on battery screen off: ");
                formatTimeMs(sb, whichBatteryScreenOffRealtime / 1000); sb.append("(");
                sb.append(formatRatioLocked(whichBatteryScreenOffRealtime, totalRealtime));
                sb.append(") realtime, ");
                formatTimeMs(sb, whichBatteryScreenOffUptime / 1000);
                sb.append("(");
                sb.append(formatRatioLocked(whichBatteryScreenOffUptime, totalRealtime));
                sb.append(") uptime");
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
        pw.print("  Start clock time: ");
        pw.println(DateFormat.format("yyyy-MM-dd-HH-mm-ss", getStartClockTime()).toString());

        final long screenOnTime = getScreenOnTime(rawRealtime, which);
        final long interactiveTime = getInteractiveTime(rawRealtime, which);
        final long lowPowerModeEnabledTime = getLowPowerModeEnabledTime(rawRealtime, which);
        final long phoneOnTime = getPhoneOnTime(rawRealtime, which);
        final long wifiRunningTime = getGlobalWifiRunningTime(rawRealtime, which);
        final long wifiOnTime = getWifiOnTime(rawRealtime, which);
        final long bluetoothOnTime = getBluetoothOnTime(rawRealtime, which);
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
        if (lowPowerModeEnabledTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Low power mode enabled: ");
                    formatTimeMs(sb, lowPowerModeEnabledTime / 1000);
                    sb.append("(");
                    sb.append(formatRatioLocked(lowPowerModeEnabledTime, whichBatteryRealtime));
                    sb.append(")");
            pw.println(sb.toString());
        }
        if (phoneOnTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
                    sb.append("  Active phone call: "); formatTimeMs(sb, phoneOnTime / 1000);
                    sb.append("("); sb.append(formatRatioLocked(phoneOnTime, whichBatteryRealtime));
                    sb.append(") "); sb.append(getPhoneOnCount(which));
        }

        // Calculate wakelock times across all uids.
        long fullWakeLockTimeTotalMicros = 0;
        long partialWakeLockTimeTotalMicros = 0;

        final ArrayList<TimerEntry> timers = new ArrayList<TimerEntry>();

        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);

            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> ent 
                        : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    
                    Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                    if (fullWakeTimer != null) {
                        fullWakeLockTimeTotalMicros += fullWakeTimer.getTotalTimeLocked(
                                rawRealtime, which);
                    }

                    Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                    if (partialWakeTimer != null) {
                        long totalTimeMicros = partialWakeTimer.getTotalTimeLocked(
                                rawRealtime, which);
                        if (totalTimeMicros > 0) {
                            if (reqUid < 0) {
                                // Only show the ordered list of all wake
                                // locks if the caller is not asking for data
                                // about a specific uid.
                                timers.add(new TimerEntry(ent.getKey(), u.getUid(),
                                        partialWakeTimer, totalTimeMicros));
                            }
                            partialWakeLockTimeTotalMicros += totalTimeMicros;
                        }
                    }
                }
            }
        }
        
        long mobileRxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
        long mobileTxTotalBytes = getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
        long wifiRxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
        long wifiTxTotalBytes = getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
        long mobileRxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
        long mobileTxTotalPackets = getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
        long wifiRxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
        long wifiTxTotalPackets = getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);

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

        pw.print(prefix);
                pw.print("  Mobile total received: "); pw.print(formatBytesLocked(mobileRxTotalBytes));
                pw.print(", sent: "); pw.print(formatBytesLocked(mobileTxTotalBytes));
                pw.print(" (packets received "); pw.print(mobileRxTotalPackets);
                pw.print(", sent "); pw.print(mobileTxTotalPackets); pw.println(")");
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Phone signal levels:");
        didOne = false;
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            final long time = getPhoneSignalStrengthTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            sb.append(prefix);
            didOne = true;
            sb.append(SignalStrength.SIGNAL_STRENGTH_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getPhoneSignalStrengthCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Signal scanning time: ");
        formatTimeMsNoSpace(sb, getPhoneSignalScanningTime(rawRealtime, which) / 1000);
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Radio types:");
        didOne = false;
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            final long time = getPhoneDataConnectionTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            sb.append(prefix);
            didOne = true;
            sb.append(DATA_CONNECTION_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getPhoneDataConnectionCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Mobile radio active time: ");
        final long mobileActiveTime = getMobileRadioActiveTime(rawRealtime, which);
        formatTimeMs(sb, mobileActiveTime / 1000);
        sb.append("("); sb.append(formatRatioLocked(mobileActiveTime, whichBatteryRealtime));
        sb.append(") "); sb.append(getMobileRadioActiveCount(which));
        sb.append("x");
        pw.println(sb.toString());

        final long mobileActiveUnknownTime = getMobileRadioActiveUnknownTime(which);
        if (mobileActiveUnknownTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Mobile radio active unknown time: ");
            formatTimeMs(sb, mobileActiveUnknownTime / 1000);
            sb.append("(");
            sb.append(formatRatioLocked(mobileActiveUnknownTime, whichBatteryRealtime));
            sb.append(") "); sb.append(getMobileRadioActiveUnknownCount(which));
            sb.append("x");
            pw.println(sb.toString());
        }

        final long mobileActiveAdjustedTime = getMobileRadioActiveAdjustedTime(which);
        if (mobileActiveAdjustedTime != 0) {
            sb.setLength(0);
            sb.append(prefix);
            sb.append("  Mobile radio active adjusted time: ");
            formatTimeMs(sb, mobileActiveAdjustedTime / 1000);
            sb.append("(");
            sb.append(formatRatioLocked(mobileActiveAdjustedTime, whichBatteryRealtime));
            sb.append(")");
            pw.println(sb.toString());
        }

        pw.print(prefix);
                pw.print("  Wi-Fi total received: "); pw.print(formatBytesLocked(wifiRxTotalBytes));
                pw.print(", sent: "); pw.print(formatBytesLocked(wifiTxTotalBytes));
                pw.print(" (packets received "); pw.print(wifiRxTotalPackets);
                pw.print(", sent "); pw.print(wifiTxTotalPackets); pw.println(")");
        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Wifi on: "); formatTimeMs(sb, wifiOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(wifiOnTime, whichBatteryRealtime));
                sb.append("), Wifi running: "); formatTimeMs(sb, wifiRunningTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(wifiRunningTime, whichBatteryRealtime));
                sb.append(")");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Wifi states:");
        didOne = false;
        for (int i=0; i<NUM_WIFI_STATES; i++) {
            final long time = getWifiStateTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            didOne = true;
            sb.append(WIFI_STATE_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getWifiStateCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Wifi supplicant states:");
        didOne = false;
        for (int i=0; i<NUM_WIFI_SUPPL_STATES; i++) {
            final long time = getWifiSupplStateTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            didOne = true;
            sb.append(WIFI_SUPPL_STATE_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getWifiSupplStateCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Wifi signal levels:");
        didOne = false;
        for (int i=0; i<NUM_WIFI_SIGNAL_STRENGTH_BINS; i++) {
            final long time = getWifiSignalStrengthTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            sb.append(prefix);
            didOne = true;
            sb.append("level(");
            sb.append(i);
            sb.append(") ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getWifiSignalStrengthCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Bluetooth on: "); formatTimeMs(sb, bluetoothOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(bluetoothOnTime, whichBatteryRealtime));
                sb.append(")");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Bluetooth states:");
        didOne = false;
        for (int i=0; i<NUM_BLUETOOTH_STATES; i++) {
            final long time = getBluetoothStateTime(i, rawRealtime, which);
            if (time == 0) {
                continue;
            }
            sb.append("\n    ");
            didOne = true;
            sb.append(BLUETOOTH_STATE_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getPhoneDataConnectionCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append(" (no activity)");
        pw.println(sb.toString());

        pw.println();

        if (which == STATS_SINCE_UNPLUGGED) {
            if (getIsOnBattery()) {
                pw.print(prefix); pw.println("  Device is currently unplugged");
                pw.print(prefix); pw.print("    Discharge cycle start level: "); 
                        pw.println(getDischargeStartLevel());
                pw.print(prefix); pw.print("    Discharge cycle current level: ");
                        pw.println(getDischargeCurrentLevel());
            } else {
                pw.print(prefix); pw.println("  Device is currently plugged into power");
                pw.print(prefix); pw.print("    Last discharge cycle start level: "); 
                        pw.println(getDischargeStartLevel());
                pw.print(prefix); pw.print("    Last discharge cycle end level: "); 
                        pw.println(getDischargeCurrentLevel());
            }
            pw.print(prefix); pw.print("    Amount discharged while screen on: ");
                    pw.println(getDischargeAmountScreenOn());
            pw.print(prefix); pw.print("    Amount discharged while screen off: ");
                    pw.println(getDischargeAmountScreenOff());
            pw.println(" ");
        } else {
            pw.print(prefix); pw.println("  Device battery use since last full charge");
            pw.print(prefix); pw.print("    Amount discharged (lower bound): ");
                    pw.println(getLowDischargeAmountSinceCharge());
            pw.print(prefix); pw.print("    Amount discharged (upper bound): ");
                    pw.println(getHighDischargeAmountSinceCharge());
            pw.print(prefix); pw.print("    Amount discharged while screen on: ");
                    pw.println(getDischargeAmountScreenOnSinceCharge());
            pw.print(prefix); pw.print("    Amount discharged while screen off: ");
                    pw.println(getDischargeAmountScreenOffSinceCharge());
            pw.println();
        }

        BatteryStatsHelper helper = new BatteryStatsHelper(context, false, wifiOnly);
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
                BatterySipper bs = sippers.get(i);
                switch (bs.drainType) {
                    case IDLE:
                        pw.print(prefix); pw.print("    Idle: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case CELL:
                        pw.print(prefix); pw.print("    Cell standby: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case PHONE:
                        pw.print(prefix); pw.print("    Phone calls: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case WIFI:
                        pw.print(prefix); pw.print("    Wifi: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case BLUETOOTH:
                        pw.print(prefix); pw.print("    Bluetooth: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case SCREEN:
                        pw.print(prefix); pw.print("    Screen: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case FLASHLIGHT:
                        pw.print(prefix); pw.print("    Flashlight: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case APP:
                        pw.print(prefix); pw.print("    Uid ");
                        UserHandle.formatUid(pw, bs.uidObj.getUid());
                        pw.print(": "); printmAh(pw, bs.value); pw.println();
                        break;
                    case USER:
                        pw.print(prefix); pw.print("    User "); pw.print(bs.userId);
                        pw.print(": "); printmAh(pw, bs.value); pw.println();
                        break;
                    case UNACCOUNTED:
                        pw.print(prefix); pw.print("    Unaccounted: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                    case OVERCOUNTED:
                        pw.print(prefix); pw.print("    Over-counted: "); printmAh(pw, bs.value);
                        pw.println();
                        break;
                }
            }
            pw.println();
        }

        sippers = helper.getMobilemsppList();
        if (sippers != null && sippers.size() > 0) {
            pw.print(prefix); pw.println("  Per-app mobile ms per packet:");
            long totalTime = 0;
            for (int i=0; i<sippers.size(); i++) {
                BatterySipper bs = sippers.get(i);
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
            Map<String, ? extends BatteryStats.Timer> kernelWakelocks = getKernelWakelockStats();
            if (kernelWakelocks.size() > 0) {
                final ArrayList<TimerEntry> ktimers = new ArrayList<TimerEntry>();
                for (Map.Entry<String, ? extends BatteryStats.Timer> ent : kernelWakelocks.entrySet()) {
                    BatteryStats.Timer timer = ent.getValue();
                    long totalTimeMillis = computeWakeLock(timer, rawRealtime, which);
                    if (totalTimeMillis > 0) {
                        ktimers.add(new TimerEntry(ent.getKey(), 0, timer, totalTimeMillis));
                    }
                }
                if (ktimers.size() > 0) {
                    Collections.sort(ktimers, timerComparator);
                    pw.print(prefix); pw.println("  All kernel wake locks:");
                    for (int i=0; i<ktimers.size(); i++) {
                        TimerEntry timer = ktimers.get(i);
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

            Map<String, ? extends LongCounter> wakeupReasons = getWakeupReasonStats();
            if (wakeupReasons.size() > 0) {
                pw.print(prefix); pw.println("  All wakeup reasons:");
                final ArrayList<TimerEntry> reasons = new ArrayList<TimerEntry>();
                for (Map.Entry<String, ? extends LongCounter> ent : wakeupReasons.entrySet()) {
                    BatteryStats.LongCounter counter = ent.getValue();
                    reasons.add(new TimerEntry(ent.getKey(), 0, null,
                            ent.getValue().getCountLocked(which)));
                }
                Collections.sort(reasons, timerComparator);
                for (int i=0; i<reasons.size(); i++) {
                    TimerEntry timer = reasons.get(i);
                    String linePrefix = ": ";
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("  Wakeup reason ");
                    sb.append(timer.mName);
                    sb.append(": ");
                    formatTimeMs(sb, timer.mTime);
                    sb.append("realtime");
                    pw.println(sb.toString());
                }
                pw.println();
            }
        }

        for (int iu=0; iu<NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            if (reqUid >= 0 && uid != reqUid && uid != Process.SYSTEM_UID) {
                continue;
            }
            
            Uid u = uidStats.valueAt(iu);

            pw.print(prefix);
            pw.print("  ");
            UserHandle.formatUid(pw, uid);
            pw.println(":");
            boolean uidActivity = false;

            long mobileRxBytes = u.getNetworkActivityBytes(NETWORK_MOBILE_RX_DATA, which);
            long mobileTxBytes = u.getNetworkActivityBytes(NETWORK_MOBILE_TX_DATA, which);
            long wifiRxBytes = u.getNetworkActivityBytes(NETWORK_WIFI_RX_DATA, which);
            long wifiTxBytes = u.getNetworkActivityBytes(NETWORK_WIFI_TX_DATA, which);
            long mobileRxPackets = u.getNetworkActivityPackets(NETWORK_MOBILE_RX_DATA, which);
            long mobileTxPackets = u.getNetworkActivityPackets(NETWORK_MOBILE_TX_DATA, which);
            long uidMobileActiveTime = u.getMobileRadioActiveTime(which);
            int uidMobileActiveCount = u.getMobileRadioActiveCount(which);
            long wifiRxPackets = u.getNetworkActivityPackets(NETWORK_WIFI_RX_DATA, which);
            long wifiTxPackets = u.getNetworkActivityPackets(NETWORK_WIFI_TX_DATA, which);
            long fullWifiLockOnTime = u.getFullWifiLockTime(rawRealtime, which);
            long wifiScanTime = u.getWifiScanTime(rawRealtime, which);
            long uidWifiRunningTime = u.getWifiRunningTime(rawRealtime, which);

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

            if (wifiRxBytes > 0 || wifiTxBytes > 0 || wifiRxPackets > 0 || wifiTxPackets > 0) {
                pw.print(prefix); pw.print("    Wi-Fi network: ");
                        pw.print(formatBytesLocked(wifiRxBytes)); pw.print(" received, ");
                        pw.print(formatBytesLocked(wifiTxBytes));
                        pw.print(" sent (packets "); pw.print(wifiRxPackets);
                        pw.print(" received, "); pw.print(wifiTxPackets); pw.println(" sent)");
            }

            if (fullWifiLockOnTime != 0 || wifiScanTime != 0
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
                sb.append(prefix); sb.append("    Wifi Scan: ");
                        formatTimeMs(sb, wifiScanTime / 1000);
                        sb.append("("); sb.append(formatRatioLocked(wifiScanTime,
                                whichBatteryRealtime)); sb.append(")");
                pw.println(sb.toString());
            }

            if (u.hasUserActivity()) {
                boolean hasData = false;
                for (int i=0; i<Uid.NUM_USER_ACTIVITY_TYPES; i++) {
                    int val = u.getUserActivityCount(i, which);
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

            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                long totalFull = 0, totalPartial = 0, totalWindow = 0;
                int count = 0;
                for (Map.Entry<String, ? extends Uid.Wakelock> ent : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    String linePrefix = ": ";
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Wake lock ");
                    sb.append(ent.getKey());
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_FULL), rawRealtime,
                            "full", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_PARTIAL), rawRealtime,
                            "partial", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), rawRealtime,
                            "window", which, linePrefix);
                    if (!linePrefix.equals(": ")) {
                        sb.append(" realtime");
                        // Only print out wake locks that were held
                        pw.println(sb.toString());
                        uidActivity = true;
                        count++;
                    }
                    totalFull += computeWakeLock(wl.getWakeTime(WAKE_TYPE_FULL),
                            rawRealtime, which);
                    totalPartial += computeWakeLock(wl.getWakeTime(WAKE_TYPE_PARTIAL),
                            rawRealtime, which);
                    totalWindow += computeWakeLock(wl.getWakeTime(WAKE_TYPE_WINDOW),
                            rawRealtime, which);
                }
                if (count > 1) {
                    if (totalFull != 0 || totalPartial != 0 || totalWindow != 0) {
                        sb.setLength(0);
                        sb.append(prefix);
                        sb.append("    TOTAL wake: ");
                        boolean needComma = false;
                        if (totalFull != 0) {
                            needComma = true;
                            formatTimeMs(sb, totalFull);
                            sb.append("full");
                        }
                        if (totalPartial != 0) {
                            if (needComma) {
                                sb.append(", ");
                            }
                            needComma = true;
                            formatTimeMs(sb, totalPartial);
                            sb.append("partial");
                        }
                        if (totalWindow != 0) {
                            if (needComma) {
                                sb.append(", ");
                            }
                            needComma = true;
                            formatTimeMs(sb, totalWindow);
                            sb.append("window");
                        }
                        sb.append(" realtime");
                        pw.println(sb.toString());
                    }
                }
            }

            Map<String, ? extends Timer> syncs = u.getSyncStats();
            if (syncs.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : syncs.entrySet()) {
                    Timer timer = ent.getValue();
                    // Convert from microseconds to milliseconds with rounding
                    long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                    int count = timer.getCountLocked(which);
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Sync ");
                    sb.append(ent.getKey());
                    sb.append(": ");
                    if (totalTime != 0) {
                        formatTimeMs(sb, totalTime);
                        sb.append("realtime (");
                        sb.append(count);
                        sb.append(" times)");
                    } else {
                        sb.append("(not used)");
                    }
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }

            Map<String, ? extends Timer> jobs = u.getJobStats();
            if (syncs.size() > 0) {
                for (Map.Entry<String, ? extends Timer> ent : jobs.entrySet()) {
                    Timer timer = ent.getValue();
                    // Convert from microseconds to milliseconds with rounding
                    long totalTime = (timer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                    int count = timer.getCountLocked(which);
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Job ");
                    sb.append(ent.getKey());
                    sb.append(": ");
                    if (totalTime != 0) {
                        formatTimeMs(sb, totalTime);
                        sb.append("realtime (");
                        sb.append(count);
                        sb.append(" times)");
                    } else {
                        sb.append("(not used)");
                    }
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }

            SparseArray<? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            int NSE = sensors.size();
            for (int ise=0; ise<NSE; ise++) {
                Uid.Sensor se = sensors.valueAt(ise);
                int sensorNumber = sensors.keyAt(ise);
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

                Timer timer = se.getSensorTime();
                if (timer != null) {
                    // Convert from microseconds to milliseconds with rounding
                    long totalTime = (timer.getTotalTimeLocked(
                            rawRealtime, which) + 500) / 1000;
                    int count = timer.getCountLocked(which);
                    //timer.logState();
                    if (totalTime != 0) {
                        formatTimeMs(sb, totalTime);
                        sb.append("realtime (");
                        sb.append(count);
                        sb.append(" times)");
                    } else {
                        sb.append("(not used)");
                    }
                } else {
                    sb.append("(not used)");
                }

                pw.println(sb.toString());
                uidActivity = true;
            }

            Timer vibTimer = u.getVibratorOnTimer();
            if (vibTimer != null) {
                // Convert from microseconds to milliseconds with rounding
                long totalTime = (vibTimer.getTotalTimeLocked(
                        rawRealtime, which) + 500) / 1000;
                int count = vibTimer.getCountLocked(which);
                //timer.logState();
                if (totalTime != 0) {
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Vibrator: ");
                    formatTimeMs(sb, totalTime);
                    sb.append("realtime (");
                    sb.append(count);
                    sb.append(" times)");
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }

            Timer fgTimer = u.getForegroundActivityTimer();
            if (fgTimer != null) {
                // Convert from microseconds to milliseconds with rounding
                long totalTime = (fgTimer.getTotalTimeLocked(rawRealtime, which) + 500) / 1000;
                int count = fgTimer.getCountLocked(which);
                if (totalTime != 0) {
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Foreground activities: ");
                    formatTimeMs(sb, totalTime);
                    sb.append("realtime (");
                    sb.append(count);
                    sb.append(" times)");
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }

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
                    formatTimeMs(sb, (totalStateTime + 500) / 1000);
                    pw.println(sb.toString());
                    uidActivity = true;
                }
            }

            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            if (processStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                    : processStats.entrySet()) {
                    Uid.Proc ps = ent.getValue();
                    long userTime;
                    long systemTime;
                    long foregroundTime;
                    int starts;
                    int numExcessive;

                    userTime = ps.getUserTime(which);
                    systemTime = ps.getSystemTime(which);
                    foregroundTime = ps.getForegroundTime(which);
                    starts = ps.getStarts(which);
                    numExcessive = which == STATS_SINCE_CHARGED
                            ? ps.countExcessivePowers() : 0;

                    if (userTime != 0 || systemTime != 0 || foregroundTime != 0 || starts != 0
                            || numExcessive != 0) {
                        sb.setLength(0);
                        sb.append(prefix); sb.append("    Proc ");
                                sb.append(ent.getKey()); sb.append(":\n");
                        sb.append(prefix); sb.append("      CPU: ");
                                formatTime(sb, userTime); sb.append("usr + ");
                                formatTime(sb, systemTime); sb.append("krn ; ");
                                formatTime(sb, foregroundTime); sb.append("fg");
                        if (starts != 0) {
                            sb.append("\n"); sb.append(prefix); sb.append("      ");
                                    sb.append(starts); sb.append(" proc starts");
                        }
                        pw.println(sb.toString());
                        for (int e=0; e<numExcessive; e++) {
                            Uid.Proc.ExcessivePower ew = ps.getExcessivePower(e);
                            if (ew != null) {
                                pw.print(prefix); pw.print("      * Killed for ");
                                        if (ew.type == Uid.Proc.ExcessivePower.TYPE_WAKE) {
                                            pw.print("wake lock");
                                        } else if (ew.type == Uid.Proc.ExcessivePower.TYPE_CPU) {
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
            }

            Map<String, ? extends BatteryStats.Uid.Pkg> packageStats = u.getPackageStats();
            if (packageStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Pkg> ent
                    : packageStats.entrySet()) {
                    pw.print(prefix); pw.print("    Apk "); pw.print(ent.getKey()); pw.println(":");
                    boolean apkActivity = false;
                    Uid.Pkg ps = ent.getValue();
                    int wakeups = ps.getWakeups(which);
                    if (wakeups != 0) {
                        pw.print(prefix); pw.print("      ");
                                pw.print(wakeups); pw.println(" wakeup alarms");
                        apkActivity = true;
                    }
                    Map<String, ? extends  Uid.Pkg.Serv> serviceStats = ps.getServiceStats();
                    if (serviceStats.size() > 0) {
                        for (Map.Entry<String, ? extends BatteryStats.Uid.Pkg.Serv> sent
                                : serviceStats.entrySet()) {
                            BatteryStats.Uid.Pkg.Serv ss = sent.getValue();
                            long startTime = ss.getStartTime(batteryUptime, which);
                            int starts = ss.getStarts(which);
                            int launches = ss.getLaunches(which);
                            if (startTime != 0 || starts != 0 || launches != 0) {
                                sb.setLength(0);
                                sb.append(prefix); sb.append("      Service ");
                                        sb.append(sent.getKey()); sb.append(":\n");
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
                    }
                    if (!apkActivity) {
                        pw.print(prefix); pw.println("      (nothing executed)");
                    }
                    uidActivity = true;
                }
            }
            if (!uidActivity) {
                pw.print(prefix); pw.println("    (nothing executed)");
            }
        }
    }

    static void printBitDescriptions(PrintWriter pw, int oldval, int newval, HistoryTag wakelockTag,
            BitDescription[] descriptions, boolean longNames) {
        int diff = oldval ^ newval;
        if (diff == 0) return;
        boolean didWake = false;
        for (int i=0; i<descriptions.length; i++) {
            BitDescription bd = descriptions[i];
            if ((diff&bd.mask) != 0) {
                pw.print(longNames ? " " : ",");
                if (bd.shift < 0) {
                    pw.print((newval&bd.mask) != 0 ? "+" : "-");
                    pw.print(longNames ? bd.name : bd.shortName);
                    if (bd.mask == HistoryItem.STATE_WAKE_LOCK_FLAG && wakelockTag != null) {
                        didWake = true;
                        pw.print("=");
                        if (longNames) {
                            UserHandle.formatUid(pw, wakelockTag.uid);
                            pw.print(":\"");
                            pw.print(wakelockTag.string);
                            pw.print("\"");
                        } else {
                            pw.print(wakelockTag.poolIdx);
                        }
                    }
                } else {
                    pw.print(longNames ? bd.name : bd.shortName);
                    pw.print("=");
                    int val = (newval&bd.mask)>>bd.shift;
                    if (bd.values != null && val >= 0 && val < bd.values.length) {
                        pw.print(longNames? bd.values[val] : bd.shortValues[val]);
                    } else {
                        pw.print(val);
                    }
                }
            }
        }
        if (!didWake && wakelockTag != null) {
            pw.print(longNames ? " wake_lock=" : ",w=");
            if (longNames) {
                UserHandle.formatUid(pw, wakelockTag.uid);
                pw.print(":\"");
                pw.print(wakelockTag.string);
                pw.print("\"");
            } else {
                pw.print(wakelockTag.poolIdx);
            }
        }
    }
    
    public void prepareForDumpLocked() {
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
        long lastTime = -1;

        void reset() {
            oldState = oldState2 = 0;
            oldLevel = -1;
            oldStatus = -1;
            oldHealth = -1;
            oldPlug = -1;
            oldTemp = -1;
            oldVolt = -1;
        }

        public void printNextItem(PrintWriter pw, HistoryItem rec, long baseTime, boolean checkin,
                boolean verbose) {
            if (!checkin) {
                pw.print("  ");
                TimeUtils.formatDuration(rec.time - baseTime, pw, TimeUtils.HUNDRED_DAY_FIELD_LEN);
                pw.print(" (");
                pw.print(rec.numReadInts);
                pw.print(") ");
            } else {
                pw.print(BATTERY_STATS_CHECKIN_VERSION); pw.print(',');
                pw.print(HISTORY_DATA); pw.print(',');
                if (lastTime < 0) {
                    pw.print(rec.time - baseTime);
                } else {
                    pw.print(rec.time - lastTime);
                }
                lastTime = rec.time;
            }
            if (rec.cmd == HistoryItem.CMD_START) {
                if (checkin) {
                    pw.print(":");
                }
                pw.println("START");
                reset();
            } else if (rec.cmd == HistoryItem.CMD_CURRENT_TIME
                    || rec.cmd == HistoryItem.CMD_RESET) {
                if (checkin) {
                    pw.print(":");
                }
                if (rec.cmd == HistoryItem.CMD_RESET) {
                    pw.print("RESET:");
                    reset();
                }
                pw.print("TIME:");
                if (checkin) {
                    pw.println(rec.currentTime);
                } else {
                    pw.print(" ");
                    pw.println(DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                            rec.currentTime).toString());
                }
            } else if (rec.cmd == HistoryItem.CMD_OVERFLOW) {
                if (checkin) {
                    pw.print(":");
                }
                pw.println("*OVERFLOW*");
            } else {
                if (!checkin) {
                    if (rec.batteryLevel < 10) pw.print("00");
                    else if (rec.batteryLevel < 100) pw.print("0");
                    pw.print(rec.batteryLevel);
                    if (verbose) {
                        pw.print(" ");
                        if (rec.states < 0) ;
                        else if (rec.states < 0x10) pw.print("0000000");
                        else if (rec.states < 0x100) pw.print("000000");
                        else if (rec.states < 0x1000) pw.print("00000");
                        else if (rec.states < 0x10000) pw.print("0000");
                        else if (rec.states < 0x100000) pw.print("000");
                        else if (rec.states < 0x1000000) pw.print("00");
                        else if (rec.states < 0x10000000) pw.print("0");
                        pw.print(Integer.toHexString(rec.states));
                    }
                } else {
                    if (oldLevel != rec.batteryLevel) {
                        oldLevel = rec.batteryLevel;
                        pw.print(",Bl="); pw.print(rec.batteryLevel);
                    }
                }
                if (oldStatus != rec.batteryStatus) {
                    oldStatus = rec.batteryStatus;
                    pw.print(checkin ? ",Bs=" : " status=");
                    switch (oldStatus) {
                        case BatteryManager.BATTERY_STATUS_UNKNOWN:
                            pw.print(checkin ? "?" : "unknown");
                            break;
                        case BatteryManager.BATTERY_STATUS_CHARGING:
                            pw.print(checkin ? "c" : "charging");
                            break;
                        case BatteryManager.BATTERY_STATUS_DISCHARGING:
                            pw.print(checkin ? "d" : "discharging");
                            break;
                        case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                            pw.print(checkin ? "n" : "not-charging");
                            break;
                        case BatteryManager.BATTERY_STATUS_FULL:
                            pw.print(checkin ? "f" : "full");
                            break;
                        default:
                            pw.print(oldStatus);
                            break;
                    }
                }
                if (oldHealth != rec.batteryHealth) {
                    oldHealth = rec.batteryHealth;
                    pw.print(checkin ? ",Bh=" : " health=");
                    switch (oldHealth) {
                        case BatteryManager.BATTERY_HEALTH_UNKNOWN:
                            pw.print(checkin ? "?" : "unknown");
                            break;
                        case BatteryManager.BATTERY_HEALTH_GOOD:
                            pw.print(checkin ? "g" : "good");
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                            pw.print(checkin ? "h" : "overheat");
                            break;
                        case BatteryManager.BATTERY_HEALTH_DEAD:
                            pw.print(checkin ? "d" : "dead");
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                            pw.print(checkin ? "v" : "over-voltage");
                            break;
                        case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                            pw.print(checkin ? "f" : "failure");
                            break;
                        case BatteryManager.BATTERY_HEALTH_COLD:
                            pw.print(checkin ? "c" : "cold");
                            break;
                        default:
                            pw.print(oldHealth);
                            break;
                    }
                }
                if (oldPlug != rec.batteryPlugType) {
                    oldPlug = rec.batteryPlugType;
                    pw.print(checkin ? ",Bp=" : " plug=");
                    switch (oldPlug) {
                        case 0:
                            pw.print(checkin ? "n" : "none");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_AC:
                            pw.print(checkin ? "a" : "ac");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_USB:
                            pw.print(checkin ? "u" : "usb");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                            pw.print(checkin ? "w" : "wireless");
                            break;
                        default:
                            pw.print(oldPlug);
                            break;
                    }
                }
                if (oldTemp != rec.batteryTemperature) {
                    oldTemp = rec.batteryTemperature;
                    pw.print(checkin ? ",Bt=" : " temp=");
                    pw.print(oldTemp);
                }
                if (oldVolt != rec.batteryVoltage) {
                    oldVolt = rec.batteryVoltage;
                    pw.print(checkin ? ",Bv=" : " volt=");
                    pw.print(oldVolt);
                }
                printBitDescriptions(pw, oldState, rec.states, rec.wakelockTag,
                        HISTORY_STATE_DESCRIPTIONS, !checkin);
                printBitDescriptions(pw, oldState2, rec.states2, null,
                        HISTORY_STATE2_DESCRIPTIONS, !checkin);
                if (rec.wakeReasonTag != null) {
                    if (checkin) {
                        pw.print(",wr=");
                        pw.print(rec.wakeReasonTag.poolIdx);
                    } else {
                        pw.print(" wake_reason=");
                        pw.print(rec.wakeReasonTag.uid);
                        pw.print(":\"");
                        pw.print(rec.wakeReasonTag.string);
                        pw.print("\"");
                    }
                }
                if (rec.eventCode != HistoryItem.EVENT_NONE) {
                    pw.print(checkin ? "," : " ");
                    if ((rec.eventCode&HistoryItem.EVENT_FLAG_START) != 0) {
                        pw.print("+");
                    } else if ((rec.eventCode&HistoryItem.EVENT_FLAG_FINISH) != 0) {
                        pw.print("-");
                    }
                    String[] eventNames = checkin ? HISTORY_EVENT_CHECKIN_NAMES
                            : HISTORY_EVENT_NAMES;
                    int idx = rec.eventCode & ~(HistoryItem.EVENT_FLAG_START
                            | HistoryItem.EVENT_FLAG_FINISH);
                    if (idx >= 0 && idx < eventNames.length) {
                        pw.print(eventNames[idx]);
                    } else {
                        pw.print(checkin ? "Ev" : "event");
                        pw.print(idx);
                    }
                    pw.print("=");
                    if (checkin) {
                        pw.print(rec.eventTag.poolIdx);
                    } else {
                        UserHandle.formatUid(pw, rec.eventTag.uid);
                        pw.print(":\"");
                        pw.print(rec.eventTag.string);
                        pw.print("\"");
                    }
                }
                pw.println();
                oldState = rec.states;
                oldState2 = rec.states2;
            }
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

    private static boolean dumpDurationSteps(PrintWriter pw, String header, long[] steps,
            int count, boolean checkin) {
        if (count <= 0) {
            return false;
        }
        if (!checkin) {
            pw.println(header);
        }
        String[] lineArgs = new String[4];
        for (int i=0; i<count; i++) {
            long duration = steps[i] & STEP_LEVEL_TIME_MASK;
            int level = (int)((steps[i] & STEP_LEVEL_LEVEL_MASK)
                    >> STEP_LEVEL_LEVEL_SHIFT);
            long initMode = (steps[i] & STEP_LEVEL_INITIAL_MODE_MASK)
                    >> STEP_LEVEL_INITIAL_MODE_SHIFT;
            long modMode = (steps[i] & STEP_LEVEL_MODIFIED_MODE_MASK)
                    >> STEP_LEVEL_MODIFIED_MODE_SHIFT;
            if (checkin) {
                lineArgs[0] = Long.toString(duration);
                lineArgs[1] = Integer.toString(level);
                if ((modMode&STEP_LEVEL_MODE_SCREEN_STATE) == 0) {
                    switch ((int)(initMode&STEP_LEVEL_MODE_SCREEN_STATE) + 1) {
                        case Display.STATE_OFF: lineArgs[2] = "s-"; break;
                        case Display.STATE_ON: lineArgs[2] = "s+"; break;
                        case Display.STATE_DOZE: lineArgs[2] = "sd"; break;
                        case Display.STATE_DOZE_SUSPEND: lineArgs[2] = "sds"; break;
                        default: lineArgs[1] = "?"; break;
                    }
                } else {
                    lineArgs[2] = "";
                }
                if ((modMode&STEP_LEVEL_MODE_POWER_SAVE) == 0) {
                    lineArgs[3] = (initMode&STEP_LEVEL_MODE_POWER_SAVE) != 0 ? "p+" : "p-";
                } else {
                    lineArgs[3] = "";
                }
                dumpLine(pw, 0 /* uid */, "i" /* category */, header, (Object[])lineArgs);
            } else {
                pw.print("  #"); pw.print(i); pw.print(": ");
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
                        default: lineArgs[1] = "screen-?"; break;
                    }
                    haveModes = true;
                }
                if ((modMode&STEP_LEVEL_MODE_POWER_SAVE) == 0) {
                    pw.print(haveModes ? ", " : " (");
                    pw.print((initMode&STEP_LEVEL_MODE_POWER_SAVE) != 0
                            ? "power-save-on" : "power-save-off");
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

    public static final int DUMP_UNPLUGGED_ONLY = 1<<0;
    public static final int DUMP_CHARGED_ONLY = 1<<1;
    public static final int DUMP_HISTORY_ONLY = 1<<2;
    public static final int DUMP_INCLUDE_HISTORY = 1<<3;
    public static final int DUMP_VERBOSE = 1<<4;
    public static final int DUMP_DEVICE_WIFI_ONLY = 1<<5;

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
                            || rec.cmd == HistoryItem.CMD_START) {
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

    /**
     * Dumps a human-readable summary of the battery statistics to the given PrintWriter.
     *
     * @param pw a Printer to receive the dump output.
     */
    @SuppressWarnings("unused")
    public void dumpLocked(Context context, PrintWriter pw, int flags, int reqUid, long histStart) {
        prepareForDumpLocked();

        final boolean filtering =
                (flags&(DUMP_HISTORY_ONLY|DUMP_UNPLUGGED_ONLY|DUMP_CHARGED_ONLY)) != 0;

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

            if (startIteratingOldHistoryLocked()) {
                try {
                    final HistoryItem rec = new HistoryItem();
                    pw.println("Old battery History:");
                    HistoryPrinter hprinter = new HistoryPrinter();
                    long baseTime = -1;
                    while (getNextOldHistoryLocked(rec)) {
                        if (baseTime < 0) {
                            baseTime = rec.time;
                        }
                        hprinter.printNextItem(pw, rec, baseTime, false, (flags&DUMP_VERBOSE) != 0);
                    }
                    pw.println();
                } finally {
                    finishIteratingOldHistoryLocked();
                }
            }
        }

        if (filtering && (flags&(DUMP_UNPLUGGED_ONLY|DUMP_CHARGED_ONLY)) == 0) {
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
            if (dumpDurationSteps(pw, "Discharge step durations:", getDischargeStepDurationsArray(),
                    getNumDischargeStepDurations(), false)) {
                long timeRemaining = computeBatteryTimeRemaining(SystemClock.elapsedRealtime());
                if (timeRemaining >= 0) {
                    pw.print("  Estimated discharge time remaining: ");
                    TimeUtils.formatDuration(timeRemaining / 1000, pw);
                    pw.println();
                }
                pw.println();
            }
            if (dumpDurationSteps(pw, "Charge step durations:", getChargeStepDurationsArray(),
                    getNumChargeStepDurations(), false)) {
                long timeRemaining = computeChargeTimeRemaining(SystemClock.elapsedRealtime());
                if (timeRemaining >= 0) {
                    pw.print("  Estimated charge time remaining: ");
                    TimeUtils.formatDuration(timeRemaining / 1000, pw);
                    pw.println();
                }
                pw.println();
            }
            pw.println("Statistics since last charge:");
            pw.println("  System starts: " + getStartCount()
                    + ", currently on battery: " + getIsOnBattery());
            dumpLocked(context, pw, "", STATS_SINCE_CHARGED, reqUid,
                    (flags&DUMP_DEVICE_WIFI_ONLY) != 0);
            pw.println();
        }
        if (!filtering || (flags&DUMP_UNPLUGGED_ONLY) != 0) {
            pw.println("Statistics since last unplugged:");
            dumpLocked(context, pw, "", STATS_SINCE_UNPLUGGED, reqUid,
                    (flags&DUMP_DEVICE_WIFI_ONLY) != 0);
        }
    }
    
    @SuppressWarnings("unused")
    public void dumpCheckinLocked(Context context, PrintWriter pw,
            List<ApplicationInfo> apps, int flags, long histStart) {
        prepareForDumpLocked();

        dumpLine(pw, 0 /* uid */, "i" /* category */, VERSION_DATA,
                "10", getParcelVersion(), getStartPlatformVersion(), getEndPlatformVersion());

        long now = getHistoryBaseTime() + SystemClock.elapsedRealtime();

        final boolean filtering =
                (flags&(DUMP_HISTORY_ONLY|DUMP_UNPLUGGED_ONLY|DUMP_CHARGED_ONLY)) != 0;

        if ((flags&DUMP_INCLUDE_HISTORY) != 0 || (flags&DUMP_HISTORY_ONLY) != 0) {
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

        if (filtering && (flags&(DUMP_UNPLUGGED_ONLY|DUMP_CHARGED_ONLY)) == 0) {
            return;
        }

        if (apps != null) {
            SparseArray<ArrayList<String>> uids = new SparseArray<ArrayList<String>>();
            for (int i=0; i<apps.size(); i++) {
                ApplicationInfo ai = apps.get(i);
                ArrayList<String> pkgs = uids.get(ai.uid);
                if (pkgs == null) {
                    pkgs = new ArrayList<String>();
                    uids.put(ai.uid, pkgs);
                }
                pkgs.add(ai.packageName);
            }
            SparseArray<? extends Uid> uidStats = getUidStats();
            final int NU = uidStats.size();
            String[] lineArgs = new String[2];
            for (int i=0; i<NU; i++) {
                int uid = uidStats.keyAt(i);
                ArrayList<String> pkgs = uids.get(uid);
                if (pkgs != null) {
                    for (int j=0; j<pkgs.size(); j++) {
                        lineArgs[0] = Integer.toString(uid);
                        lineArgs[1] = pkgs.get(j);
                        dumpLine(pw, 0 /* uid */, "i" /* category */, UID_DATA,
                                (Object[])lineArgs);
                    }
                }
            }
        }
        if (!filtering || (flags&DUMP_CHARGED_ONLY) != 0) {
            dumpDurationSteps(pw, DISCHARGE_STEP_DATA, getDischargeStepDurationsArray(),
                    getNumDischargeStepDurations(), true);
            String[] lineArgs = new String[1];
            long timeRemaining = computeBatteryTimeRemaining(SystemClock.elapsedRealtime());
            if (timeRemaining >= 0) {
                lineArgs[0] = Long.toString(timeRemaining);
                dumpLine(pw, 0 /* uid */, "i" /* category */, DISCHARGE_TIME_REMAIN_DATA,
                        (Object[])lineArgs);
            }
            dumpDurationSteps(pw, CHARGE_STEP_DATA, getChargeStepDurationsArray(),
                    getNumChargeStepDurations(), true);
            timeRemaining = computeChargeTimeRemaining(SystemClock.elapsedRealtime());
            if (timeRemaining >= 0) {
                lineArgs[0] = Long.toString(timeRemaining);
                dumpLine(pw, 0 /* uid */, "i" /* category */, CHARGE_TIME_REMAIN_DATA,
                        (Object[])lineArgs);
            }
            dumpCheckinLocked(context, pw, STATS_SINCE_CHARGED, -1,
                    (flags&DUMP_DEVICE_WIFI_ONLY) != 0);
        }
        if (!filtering || (flags&DUMP_UNPLUGGED_ONLY) != 0) {
            dumpCheckinLocked(context, pw, STATS_SINCE_UNPLUGGED, -1,
                    (flags&DUMP_DEVICE_WIFI_ONLY) != 0);
        }
    }
}
