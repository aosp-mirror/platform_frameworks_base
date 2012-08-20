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
import java.util.Formatter;
import java.util.List;
import java.util.Map;

import android.content.pm.ApplicationInfo;
import android.telephony.SignalStrength;
import android.util.Log;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

/**
 * A class providing access to battery usage statistics, including information on
 * wakelocks, processes, packages, and services.  All times are represented in microseconds
 * except where indicated otherwise.
 * @hide
 */
public abstract class BatteryStats implements Parcelable {

    private static final boolean LOCAL_LOGV = false;
    
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
     * Include all of the data in the stats, including previously saved data.
     */
    public static final int STATS_SINCE_CHARGED = 0;

    /**
     * Include only the last run in the stats.
     */
    public static final int STATS_LAST = 1;

    /**
     * Include only the current run in the stats.
     */
    public static final int STATS_CURRENT = 2;

    /**
     * Include only the run since the last time the device was unplugged in the stats.
     */
    public static final int STATS_SINCE_UNPLUGGED = 3;

    // NOTE: Update this list if you add/change any stats above.
    // These characters are supposed to represent "total", "last", "current", 
    // and "unplugged". They were shortened for efficiency sake.
    private static final String[] STAT_NAMES = { "t", "l", "c", "u" };
    
    /**
     * Bump the version on this if the checkin format changes.
     */
    private static final int BATTERY_STATS_CHECKIN_VERSION = 5;
    
    private static final long BYTES_PER_KB = 1024;
    private static final long BYTES_PER_MB = 1048576; // 1024^2
    private static final long BYTES_PER_GB = 1073741824; //1024^3
    

    private static final String UID_DATA = "uid";
    private static final String APK_DATA = "apk";
    private static final String PROCESS_DATA = "pr";
    private static final String SENSOR_DATA = "sr";
    private static final String WAKELOCK_DATA = "wl";
    private static final String KERNEL_WAKELOCK_DATA = "kwl";
    private static final String NETWORK_DATA = "nt";
    private static final String USER_ACTIVITY_DATA = "ua";
    private static final String BATTERY_DATA = "bt";
    private static final String BATTERY_DISCHARGE_DATA = "dc";
    private static final String BATTERY_LEVEL_DATA = "lv";
    private static final String WIFI_DATA = "wfl";
    private static final String MISC_DATA = "m";
    private static final String SCREEN_BRIGHTNESS_DATA = "br";
    private static final String SIGNAL_STRENGTH_TIME_DATA = "sgt";
    private static final String SIGNAL_SCANNING_TIME_DATA = "sst";
    private static final String SIGNAL_STRENGTH_COUNT_DATA = "sgc";
    private static final String DATA_CONNECTION_TIME_DATA = "dct";
    private static final String DATA_CONNECTION_COUNT_DATA = "dcc";

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
         * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT
         */
        public abstract int getCountLocked(int which);

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
         * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT
         */
        public abstract int getCountLocked(int which);

        /**
         * Returns the total time in microseconds associated with this Timer for the
         * selected type of statistics.
         *
         * @param batteryRealtime system realtime on  battery in microseconds
         * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT
         * @return a time in microseconds
         */
        public abstract long getTotalTimeLocked(long batteryRealtime, int which);

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
        public abstract Map<Integer, ? extends Sensor> getSensorStats();

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
        
        /**
         * {@hide}
         */
        public abstract long getTcpBytesReceived(int which);
        
        /**
         * {@hide}
         */
        public abstract long getTcpBytesSent(int which);
        
        public abstract void noteWifiRunningLocked();
        public abstract void noteWifiStoppedLocked();
        public abstract void noteFullWifiLockAcquiredLocked();
        public abstract void noteFullWifiLockReleasedLocked();
        public abstract void noteWifiScanStartedLocked();
        public abstract void noteWifiScanStoppedLocked();
        public abstract void noteWifiMulticastEnabledLocked();
        public abstract void noteWifiMulticastDisabledLocked();
        public abstract void noteAudioTurnedOnLocked();
        public abstract void noteAudioTurnedOffLocked();
        public abstract void noteVideoTurnedOnLocked();
        public abstract void noteVideoTurnedOffLocked();
        public abstract long getWifiRunningTime(long batteryRealtime, int which);
        public abstract long getFullWifiLockTime(long batteryRealtime, int which);
        public abstract long getWifiScanTime(long batteryRealtime, int which);
        public abstract long getWifiMulticastTime(long batteryRealtime,
                                                  int which);
        public abstract long getAudioTurnedOnTime(long batteryRealtime, int which);
        public abstract long getVideoTurnedOnTime(long batteryRealtime, int which);

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
        
        public static abstract class Sensor {
            // Magic sensor number for the GPS.
            public static final int GPS = -10000;
            
            public abstract int getHandle();
            
            public abstract Timer getSensorTime();
        }

        public class Pid {
            public long mWakeSum;
            public long mWakeStart;
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
             * Returns the total time (in 1/100 sec) spent executing in user code.
             *
             * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
             */
            public abstract long getUserTime(int which);

            /**
             * Returns the total time (in 1/100 sec) spent executing in system code.
             *
             * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
             */
            public abstract long getSystemTime(int which);

            /**
             * Returns the number of times the process has been started.
             *
             * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
             */
            public abstract int getStarts(int which);

            /**
             * Returns the cpu time spent in microseconds while the process was in the foreground.
             * @param which one of STATS_TOTAL, STATS_LAST, STATS_CURRENT or STATS_UNPLUGGED
             * @return foreground cpu time in microseconds
             */
            public abstract long getForegroundTime(int which);

            /**
             * Returns the approximate cpu time spent in microseconds, at a certain CPU speed.
             * @param speedStep the index of the CPU speed. This is not the actual speed of the
             * CPU.
             * @param which one of STATS_TOTAL, STATS_LAST, STATS_CURRENT or STATS_UNPLUGGED
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
             * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
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
                 * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
                 * @return
                 */
                public abstract long getStartTime(long batteryUptime, int which);

                /**
                 * Returns the total number of times startService() has been called.
                 *
                 * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
                 */
                public abstract int getStarts(int which);

                /**
                 * Returns the total number times the service has been launched.
                 *
                 * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
                 */
                public abstract int getLaunches(int which);
            }
        }
    }

    public final static class HistoryItem implements Parcelable {
        static final String TAG = "HistoryItem";
        static final boolean DEBUG = false;
        
        public HistoryItem next;
        
        public long time;
        
        public static final byte CMD_NULL = 0;
        public static final byte CMD_UPDATE = 1;
        public static final byte CMD_START = 2;
        public static final byte CMD_OVERFLOW = 3;
        
        public byte cmd = CMD_NULL;
        
        public byte batteryLevel;
        public byte batteryStatus;
        public byte batteryHealth;
        public byte batteryPlugType;
        
        public char batteryTemperature;
        public char batteryVoltage;
        
        // Constants from SCREEN_BRIGHTNESS_*
        public static final int STATE_BRIGHTNESS_MASK = 0x0000000f;
        public static final int STATE_BRIGHTNESS_SHIFT = 0;
        // Constants from SIGNAL_STRENGTH_*
        public static final int STATE_SIGNAL_STRENGTH_MASK = 0x000000f0;
        public static final int STATE_SIGNAL_STRENGTH_SHIFT = 4;
        // Constants from ServiceState.STATE_*
        public static final int STATE_PHONE_STATE_MASK = 0x00000f00;
        public static final int STATE_PHONE_STATE_SHIFT = 8;
        // Constants from DATA_CONNECTION_*
        public static final int STATE_DATA_CONNECTION_MASK = 0x0000f000;
        public static final int STATE_DATA_CONNECTION_SHIFT = 12;
        
        // These states always appear directly in the first int token
        // of a delta change; they should be ones that change relatively
        // frequently.
        public static final int STATE_WAKE_LOCK_FLAG = 1<<30;
        public static final int STATE_SENSOR_ON_FLAG = 1<<29;
        public static final int STATE_GPS_ON_FLAG = 1<<28;
        public static final int STATE_PHONE_SCANNING_FLAG = 1<<27;
        public static final int STATE_WIFI_RUNNING_FLAG = 1<<26;
        public static final int STATE_WIFI_FULL_LOCK_FLAG = 1<<25;
        public static final int STATE_WIFI_SCAN_FLAG = 1<<24;
        public static final int STATE_WIFI_MULTICAST_ON_FLAG = 1<<23;
        // These are on the lower bits used for the command; if they change
        // we need to write another int of data.
        public static final int STATE_AUDIO_ON_FLAG = 1<<22;
        public static final int STATE_VIDEO_ON_FLAG = 1<<21;
        public static final int STATE_SCREEN_ON_FLAG = 1<<20;
        public static final int STATE_BATTERY_PLUGGED_FLAG = 1<<19;
        public static final int STATE_PHONE_IN_CALL_FLAG = 1<<18;
        public static final int STATE_WIFI_ON_FLAG = 1<<17;
        public static final int STATE_BLUETOOTH_ON_FLAG = 1<<16;
        
        public static final int MOST_INTERESTING_STATES =
            STATE_BATTERY_PLUGGED_FLAG | STATE_SCREEN_ON_FLAG
            | STATE_GPS_ON_FLAG | STATE_PHONE_IN_CALL_FLAG;

        public int states;

        public HistoryItem() {
        }
        
        public HistoryItem(long time, Parcel src) {
            this.time = time;
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
                    | ((((int)batteryPlugType)<<24)&0xf000000);
            dest.writeInt(bat);
            bat = (((int)batteryTemperature)&0xffff)
                    | ((((int)batteryVoltage)<<16)&0xffff0000);
            dest.writeInt(bat);
            dest.writeInt(states);
        }

        private void readFromParcel(Parcel src) {
            int bat = src.readInt();
            cmd = (byte)(bat&0xff);
            batteryLevel = (byte)((bat>>8)&0xff);
            batteryStatus = (byte)((bat>>16)&0xf);
            batteryHealth = (byte)((bat>>20)&0xf);
            batteryPlugType = (byte)((bat>>24)&0xf);
            bat = src.readInt();
            batteryTemperature = (char)(bat&0xffff);
            batteryVoltage = (char)((bat>>16)&0xffff);
            states = src.readInt();
        }

        // Part of initial delta int that specifies the time delta.
        static final int DELTA_TIME_MASK = 0x3ffff;
        static final int DELTA_TIME_ABS = 0x3fffd;    // Following is an entire abs update.
        static final int DELTA_TIME_INT = 0x3fffe;    // The delta is a following int
        static final int DELTA_TIME_LONG = 0x3ffff;   // The delta is a following long
        // Part of initial delta int holding the command code.
        static final int DELTA_CMD_MASK = 0x3;
        static final int DELTA_CMD_SHIFT = 18;
        // Flag in delta int: a new battery level int follows.
        static final int DELTA_BATTERY_LEVEL_FLAG = 1<<20;
        // Flag in delta int: a new full state and battery status int follows.
        static final int DELTA_STATE_FLAG = 1<<21;
        static final int DELTA_STATE_MASK = 0xffc00000;
        
        public void writeDelta(Parcel dest, HistoryItem last) {
            if (last == null || last.cmd != CMD_UPDATE) {
                dest.writeInt(DELTA_TIME_ABS);
                writeToParcel(dest, 0);
                return;
            }
            
            final long deltaTime = time - last.time;
            final int lastBatteryLevelInt = last.buildBatteryLevelInt();
            final int lastStateInt = last.buildStateInt();
            
            int deltaTimeToken;
            if (deltaTime < 0 || deltaTime > Integer.MAX_VALUE) {
                deltaTimeToken = DELTA_TIME_LONG;
            } else if (deltaTime >= DELTA_TIME_ABS) {
                deltaTimeToken = DELTA_TIME_INT;
            } else {
                deltaTimeToken = (int)deltaTime;
            }
            int firstToken = deltaTimeToken
                    | (cmd<<DELTA_CMD_SHIFT)
                    | (states&DELTA_STATE_MASK);
            final int batteryLevelInt = buildBatteryLevelInt();
            final boolean batteryLevelIntChanged = batteryLevelInt != lastBatteryLevelInt;
            if (batteryLevelIntChanged) {
                firstToken |= DELTA_BATTERY_LEVEL_FLAG;
            }
            final int stateInt = buildStateInt();
            final boolean stateIntChanged = stateInt != lastStateInt;
            if (stateIntChanged) {
                firstToken |= DELTA_STATE_FLAG;
            }
            dest.writeInt(firstToken);
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                    + " deltaTime=" + deltaTime);
            
            if (deltaTimeToken >= DELTA_TIME_INT) {
                if (deltaTimeToken == DELTA_TIME_INT) {
                    if (DEBUG) Slog.i(TAG, "WRITE DELTA: int deltaTime=" + (int)deltaTime);
                    dest.writeInt((int)deltaTime);
                } else {
                    if (DEBUG) Slog.i(TAG, "WRITE DELTA: long deltaTime=" + deltaTime);
                    dest.writeLong(deltaTime);
                }
            }
            if (batteryLevelIntChanged) {
                dest.writeInt(batteryLevelInt);
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: batteryToken=0x"
                        + Integer.toHexString(batteryLevelInt)
                        + " batteryLevel=" + batteryLevel
                        + " batteryTemp=" + (int)batteryTemperature
                        + " batteryVolt=" + (int)batteryVoltage);
            }
            if (stateIntChanged) {
                dest.writeInt(stateInt);
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: stateToken=0x"
                        + Integer.toHexString(stateInt)
                        + " batteryStatus=" + batteryStatus
                        + " batteryHealth=" + batteryHealth
                        + " batteryPlugType=" + batteryPlugType
                        + " states=0x" + Integer.toHexString(states));
            }
        }
        
        private int buildBatteryLevelInt() {
            return ((((int)batteryLevel)<<24)&0xff000000)
                    | ((((int)batteryTemperature)<<14)&0x00ffc000)
                    | (((int)batteryVoltage)&0x00003fff);
        }
        
        private int buildStateInt() {
            return ((((int)batteryStatus)<<28)&0xf0000000)
                    | ((((int)batteryHealth)<<24)&0x0f000000)
                    | ((((int)batteryPlugType)<<22)&0x00c00000)
                    | (states&(~DELTA_STATE_MASK));
        }
        
        public void readDelta(Parcel src) {
            int firstToken = src.readInt();
            int deltaTimeToken = firstToken&DELTA_TIME_MASK;
            cmd = (byte)((firstToken>>DELTA_CMD_SHIFT)&DELTA_CMD_MASK);
            if (DEBUG) Slog.i(TAG, "READ DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                    + " deltaTimeToken=" + deltaTimeToken);
            
            if (deltaTimeToken < DELTA_TIME_ABS) {
                time += deltaTimeToken;
            } else if (deltaTimeToken == DELTA_TIME_ABS) {
                time = src.readLong();
                readFromParcel(src);
                return;
            } else if (deltaTimeToken == DELTA_TIME_INT) {
                int delta = src.readInt();
                time += delta;
                if (DEBUG) Slog.i(TAG, "READ DELTA: time delta=" + delta + " new time=" + time);
            } else {
                long delta = src.readLong();
                if (DEBUG) Slog.i(TAG, "READ DELTA: time delta=" + delta + " new time=" + time);
                time += delta;
            }
            
            if ((firstToken&DELTA_BATTERY_LEVEL_FLAG) != 0) {
                int batteryLevelInt = src.readInt();
                batteryLevel = (byte)((batteryLevelInt>>24)&0xff);
                batteryTemperature = (char)((batteryLevelInt>>14)&0x3ff);
                batteryVoltage = (char)(batteryLevelInt&0x3fff);
                if (DEBUG) Slog.i(TAG, "READ DELTA: batteryToken=0x"
                        + Integer.toHexString(batteryLevelInt)
                        + " batteryLevel=" + batteryLevel
                        + " batteryTemp=" + (int)batteryTemperature
                        + " batteryVolt=" + (int)batteryVoltage);
            }
            
            if ((firstToken&DELTA_STATE_FLAG) != 0) {
                int stateInt = src.readInt();
                states = (firstToken&DELTA_STATE_MASK) | (stateInt&(~DELTA_STATE_MASK));
                batteryStatus = (byte)((stateInt>>28)&0xf);
                batteryHealth = (byte)((stateInt>>24)&0xf);
                batteryPlugType = (byte)((stateInt>>22)&0x3);
                if (DEBUG) Slog.i(TAG, "READ DELTA: stateToken=0x"
                        + Integer.toHexString(stateInt)
                        + " batteryStatus=" + batteryStatus
                        + " batteryHealth=" + batteryHealth
                        + " batteryPlugType=" + batteryPlugType
                        + " states=0x" + Integer.toHexString(states));
            } else {
                states = (firstToken&DELTA_STATE_MASK) | (states&(~DELTA_STATE_MASK));
            }
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
        }
        
        public void setTo(HistoryItem o) {
            time = o.time;
            cmd = o.cmd;
            batteryLevel = o.batteryLevel;
            batteryStatus = o.batteryStatus;
            batteryHealth = o.batteryHealth;
            batteryPlugType = o.batteryPlugType;
            batteryTemperature = o.batteryTemperature;
            batteryVoltage = o.batteryVoltage;
            states = o.states;
        }

        public void setTo(long time, byte cmd, HistoryItem o) {
            this.time = time;
            this.cmd = cmd;
            batteryLevel = o.batteryLevel;
            batteryStatus = o.batteryStatus;
            batteryHealth = o.batteryHealth;
            batteryPlugType = o.batteryPlugType;
            batteryTemperature = o.batteryTemperature;
            batteryVoltage = o.batteryVoltage;
            states = o.states;
        }

        public boolean same(HistoryItem o) {
            return batteryLevel == o.batteryLevel
                    && batteryStatus == o.batteryStatus
                    && batteryHealth == o.batteryHealth
                    && batteryPlugType == o.batteryPlugType
                    && batteryTemperature == o.batteryTemperature
                    && batteryVoltage == o.batteryVoltage
                    && states == o.states;
        }
    }
    
    public static final class BitDescription {
        public final int mask;
        public final int shift;
        public final String name;
        public final String[] values;
        
        public BitDescription(int mask, String name) {
            this.mask = mask;
            this.shift = -1;
            this.name = name;
            this.values = null;
        }
        
        public BitDescription(int mask, int shift, String name, String[] values) {
            this.mask = mask;
            this.shift = shift;
            this.name = name;
            this.values = values;
        }
    }
    
    public abstract boolean startIteratingHistoryLocked();

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
    public abstract long getScreenOnTime(long batteryRealtime, int which);
    
    public static final int SCREEN_BRIGHTNESS_DARK = 0;
    public static final int SCREEN_BRIGHTNESS_DIM = 1;
    public static final int SCREEN_BRIGHTNESS_MEDIUM = 2;
    public static final int SCREEN_BRIGHTNESS_LIGHT = 3;
    public static final int SCREEN_BRIGHTNESS_BRIGHT = 4;
    
    static final String[] SCREEN_BRIGHTNESS_NAMES = {
        "dark", "dim", "medium", "light", "bright"
    };
    
    public static final int NUM_SCREEN_BRIGHTNESS_BINS = 5;
    
    /**
     * Returns the time in microseconds that the screen has been on with
     * the given brightness
     * 
     * {@hide}
     */
    public abstract long getScreenBrightnessTime(int brightnessBin,
            long batteryRealtime, int which);

    public abstract int getInputEventCount(int which);
    
    /**
     * Returns the time in microseconds that the phone has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getPhoneOnTime(long batteryRealtime, int which);
    
    /**
     * Returns the time in microseconds that the phone has been running with
     * the given signal strength.
     * 
     * {@hide}
     */
    public abstract long getPhoneSignalStrengthTime(int strengthBin,
            long batteryRealtime, int which);

    /**
     * Returns the time in microseconds that the phone has been trying to
     * acquire a signal.
     *
     * {@hide}
     */
    public abstract long getPhoneSignalScanningTime(
            long batteryRealtime, int which);

    /**
     * Returns the number of times the phone has entered the given signal strength.
     * 
     * {@hide}
     */
    public abstract int getPhoneSignalStrengthCount(int strengthBin, int which);

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
    public static final int DATA_CONNECTION_OTHER = 15;

    static final String[] DATA_CONNECTION_NAMES = {
        "none", "gprs", "edge", "umts", "cdma", "evdo_0", "evdo_A",
        "1xrtt", "hsdpa", "hsupa", "hspa", "iden", "evdo_b", "lte",
        "ehrpd", "other"
    };
    
    public static final int NUM_DATA_CONNECTION_TYPES = DATA_CONNECTION_OTHER+1;
    
    /**
     * Returns the time in microseconds that the phone has been running with
     * the given data connection.
     * 
     * {@hide}
     */
    public abstract long getPhoneDataConnectionTime(int dataType,
            long batteryRealtime, int which);

    /**
     * Returns the number of times the phone has entered the given data
     * connection type.
     * 
     * {@hide}
     */
    public abstract int getPhoneDataConnectionCount(int dataType, int which);
    
    public static final BitDescription[] HISTORY_STATE_DESCRIPTIONS
            = new BitDescription[] {
        new BitDescription(HistoryItem.STATE_BATTERY_PLUGGED_FLAG, "plugged"),
        new BitDescription(HistoryItem.STATE_SCREEN_ON_FLAG, "screen"),
        new BitDescription(HistoryItem.STATE_GPS_ON_FLAG, "gps"),
        new BitDescription(HistoryItem.STATE_PHONE_IN_CALL_FLAG, "phone_in_call"),
        new BitDescription(HistoryItem.STATE_PHONE_SCANNING_FLAG, "phone_scanning"),
        new BitDescription(HistoryItem.STATE_WIFI_ON_FLAG, "wifi"),
        new BitDescription(HistoryItem.STATE_WIFI_RUNNING_FLAG, "wifi_running"),
        new BitDescription(HistoryItem.STATE_WIFI_FULL_LOCK_FLAG, "wifi_full_lock"),
        new BitDescription(HistoryItem.STATE_WIFI_SCAN_FLAG, "wifi_scan"),
        new BitDescription(HistoryItem.STATE_WIFI_MULTICAST_ON_FLAG, "wifi_multicast"),
        new BitDescription(HistoryItem.STATE_BLUETOOTH_ON_FLAG, "bluetooth"),
        new BitDescription(HistoryItem.STATE_AUDIO_ON_FLAG, "audio"),
        new BitDescription(HistoryItem.STATE_VIDEO_ON_FLAG, "video"),
        new BitDescription(HistoryItem.STATE_WAKE_LOCK_FLAG, "wake_lock"),
        new BitDescription(HistoryItem.STATE_SENSOR_ON_FLAG, "sensor"),
        new BitDescription(HistoryItem.STATE_BRIGHTNESS_MASK,
                HistoryItem.STATE_BRIGHTNESS_SHIFT, "brightness",
                SCREEN_BRIGHTNESS_NAMES),
        new BitDescription(HistoryItem.STATE_SIGNAL_STRENGTH_MASK,
                HistoryItem.STATE_SIGNAL_STRENGTH_SHIFT, "signal_strength",
                SignalStrength.SIGNAL_STRENGTH_NAMES),
        new BitDescription(HistoryItem.STATE_PHONE_STATE_MASK,
                HistoryItem.STATE_PHONE_STATE_SHIFT, "phone_state",
                new String[] {"in", "out", "emergency", "off"}),
        new BitDescription(HistoryItem.STATE_DATA_CONNECTION_MASK,
                HistoryItem.STATE_DATA_CONNECTION_SHIFT, "data_conn",
                DATA_CONNECTION_NAMES),
    };

    /**
     * Returns the time in microseconds that wifi has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getWifiOnTime(long batteryRealtime, int which);

    /**
     * Returns the time in microseconds that wifi has been on and the driver has
     * been in the running state while the device was running on battery.
     *
     * {@hide}
     */
    public abstract long getGlobalWifiRunningTime(long batteryRealtime, int which);

    /**
     * Returns the time in microseconds that bluetooth has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getBluetoothOnTime(long batteryRealtime, int which);
    
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
     * @deprecated use getRadioDataUptime
     */
    public long getRadioDataUptimeMs() {
        return getRadioDataUptime() / 1000;
    }

    /**
     * Returns the time that the radio was on for data transfers.
     * @return the uptime in microseconds while unplugged
     */
    public abstract long getRadioDataUptime();

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
     * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     */
    public abstract long computeBatteryUptime(long curTime, int which);

    /**
     * Returns the total, last, or current battery realtime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     */
    public abstract long computeBatteryRealtime(long curTime, int which);

    /**
     * Returns the total, last, or current uptime in microseconds.
     *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     */
    public abstract long computeUptime(long curTime, int which);

    /**
     * Returns the total, last, or current realtime in microseconds.
     * *
     * @param curTime the current elapsed realtime in microseconds.
     * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     */
    public abstract long computeRealtime(long curTime, int which);
    
    public abstract Map<String, ? extends Timer> getKernelWakelockStats();

    /** Returns the number of different speeds that the CPU can run at */
    public abstract int getCpuSpeedSteps();

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

    private final static void formatTime(StringBuilder sb, long time) {
        long sec = time / 100;
        formatTimeRaw(sb, sec);
        sb.append((time - (sec * 100)) * 10);
        sb.append("ms ");
    }

    private final static void formatTimeMs(StringBuilder sb, long time) {
        long sec = time / 1000;
        formatTimeRaw(sb, sec);
        sb.append(time - (sec * 1000));
        sb.append("ms ");
    }

    private final String formatRatioLocked(long num, long den) {
        if (den == 0L) {
            return "---%";
        }
        float perc = ((float)num) / ((float)den) * 100;
        mFormatBuilder.setLength(0);
        mFormatter.format("%.1f%%", perc);
        return mFormatBuilder.toString();
    }

    private final String formatBytesLocked(long bytes) {
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

    private static long computeWakeLock(Timer timer, long batteryRealtime, int which) {
        if (timer != null) {
            // Convert from microseconds to milliseconds with rounding
            long totalTimeMicros = timer.getTotalTimeLocked(batteryRealtime, which);
            long totalTimeMillis = (totalTimeMicros + 500) / 1000;
            return totalTimeMillis;
        }
        return 0;
    }

    /**
     *
     * @param sb a StringBuilder object.
     * @param timer a Timer object contining the wakelock times.
     * @param batteryRealtime the current on-battery time in microseconds.
     * @param name the name of the wakelock.
     * @param which which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     * @param linePrefix a String to be prepended to each line of output.
     * @return the line prefix
     */
    private static final String printWakeLock(StringBuilder sb, Timer timer,
            long batteryRealtime, String name, int which, String linePrefix) {
        
        if (timer != null) {
            long totalTimeMillis = computeWakeLock(timer, batteryRealtime, which);
            
            int count = timer.getCountLocked(which);
            if (totalTimeMillis != 0) {
                sb.append(linePrefix);
                formatTimeMs(sb, totalTimeMillis);
                if (name != null) sb.append(name);
                sb.append(' ');
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
     * @param now the current time in microseconds.
     * @param name the name of the wakelock.
     * @param which which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     * @param linePrefix a String to be prepended to each line of output.
     * @return the line prefix
     */
    private static final String printWakeLockCheckin(StringBuilder sb, Timer timer, long now,
            String name, int which, String linePrefix) {
        long totalTimeMicros = 0;
        int count = 0;
        if (timer != null) {
            totalTimeMicros = timer.getTotalTimeLocked(now, which);
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
        pw.print('\n');
    }
    
    /**
     * Checkin server version of dump to produce more compact, computer-readable log.
     * 
     * NOTE: all times are expressed in 'ms'.
     */
    public final void dumpCheckinLocked(PrintWriter pw, int which, int reqUid) {
        final long rawUptime = SystemClock.uptimeMillis() * 1000;
        final long rawRealtime = SystemClock.elapsedRealtime() * 1000;
        final long batteryUptime = getBatteryUptime(rawUptime);
        final long batteryRealtime = getBatteryRealtime(rawRealtime);
        final long whichBatteryUptime = computeBatteryUptime(rawUptime, which);
        final long whichBatteryRealtime = computeBatteryRealtime(rawRealtime, which);
        final long totalRealtime = computeRealtime(rawRealtime, which);
        final long totalUptime = computeUptime(rawUptime, which);
        final long screenOnTime = getScreenOnTime(batteryRealtime, which);
        final long phoneOnTime = getPhoneOnTime(batteryRealtime, which);
        final long wifiOnTime = getWifiOnTime(batteryRealtime, which);
        final long wifiRunningTime = getGlobalWifiRunningTime(batteryRealtime, which);
        final long bluetoothOnTime = getBluetoothOnTime(batteryRealtime, which);
       
        StringBuilder sb = new StringBuilder(128);
        
        SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();
        
        String category = STAT_NAMES[which];
        
        // Dump "battery" stat
        dumpLine(pw, 0 /* uid */, category, BATTERY_DATA, 
                which == STATS_SINCE_CHARGED ? getStartCount() : "N/A",
                whichBatteryRealtime / 1000, whichBatteryUptime / 1000,
                totalRealtime / 1000, totalUptime / 1000); 
        
        // Calculate total network and wakelock times across all uids.
        long rxTotal = 0;
        long txTotal = 0;
        long fullWakeLockTimeTotal = 0;
        long partialWakeLockTimeTotal = 0;
        
        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);
            rxTotal += u.getTcpBytesReceived(which);
            txTotal += u.getTcpBytesSent(which);
            
            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> ent 
                        : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    
                    Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                    if (fullWakeTimer != null) {
                        fullWakeLockTimeTotal += fullWakeTimer.getTotalTimeLocked(batteryRealtime, which);
                    }

                    Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                    if (partialWakeTimer != null) {
                        partialWakeLockTimeTotal += partialWakeTimer.getTotalTimeLocked(
                            batteryRealtime, which);
                    }
                }
            }
        }
        
        // Dump misc stats
        dumpLine(pw, 0 /* uid */, category, MISC_DATA,
                screenOnTime / 1000, phoneOnTime / 1000, wifiOnTime / 1000,
                wifiRunningTime / 1000, bluetoothOnTime / 1000, rxTotal, txTotal, 
                fullWakeLockTimeTotal, partialWakeLockTimeTotal,
                getInputEventCount(which));
        
        // Dump screen brightness stats
        Object[] args = new Object[NUM_SCREEN_BRIGHTNESS_BINS];
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            args[i] = getScreenBrightnessTime(i, batteryRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SCREEN_BRIGHTNESS_DATA, args);
        
        // Dump signal strength stats
        args = new Object[SignalStrength.NUM_SIGNAL_STRENGTH_BINS];
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            args[i] = getPhoneSignalStrengthTime(i, batteryRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SIGNAL_STRENGTH_TIME_DATA, args);
        dumpLine(pw, 0 /* uid */, category, SIGNAL_SCANNING_TIME_DATA,
                getPhoneSignalScanningTime(batteryRealtime, which) / 1000);
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            args[i] = getPhoneSignalStrengthCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, SIGNAL_STRENGTH_COUNT_DATA, args);
        
        // Dump network type stats
        args = new Object[NUM_DATA_CONNECTION_TYPES];
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            args[i] = getPhoneDataConnectionTime(i, batteryRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, DATA_CONNECTION_TIME_DATA, args);
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            args[i] = getPhoneDataConnectionCount(i, which);
        }
        dumpLine(pw, 0 /* uid */, category, DATA_CONNECTION_COUNT_DATA, args);
        
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
                    getDischargeAmountScreenOn(), getDischargeAmountScreenOff());
        }
        
        if (reqUid < 0) {
            Map<String, ? extends BatteryStats.Timer> kernelWakelocks = getKernelWakelockStats();
            if (kernelWakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Timer> ent : kernelWakelocks.entrySet()) {
                    sb.setLength(0);
                    printWakeLockCheckin(sb, ent.getValue(), batteryRealtime, null, which, "");
    
                    dumpLine(pw, 0 /* uid */, category, KERNEL_WAKELOCK_DATA, ent.getKey(), 
                            sb.toString());
                }
            }
        }
        
        for (int iu = 0; iu < NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            if (reqUid >= 0 && uid != reqUid) {
                continue;
            }
            Uid u = uidStats.valueAt(iu);
            // Dump Network stats per uid, if any
            long rx = u.getTcpBytesReceived(which);
            long tx = u.getTcpBytesSent(which);
            long fullWifiLockOnTime = u.getFullWifiLockTime(batteryRealtime, which);
            long wifiScanTime = u.getWifiScanTime(batteryRealtime, which);
            long uidWifiRunningTime = u.getWifiRunningTime(batteryRealtime, which);
            
            if (rx > 0 || tx > 0) dumpLine(pw, uid, category, NETWORK_DATA, rx, tx);
            
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
                    dumpLine(pw, 0 /* uid */, category, USER_ACTIVITY_DATA, args);
                }
            }
            
            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> ent
                        : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    String linePrefix = "";
                    sb.setLength(0);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_FULL), 
                            batteryRealtime, "f", which, linePrefix);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_PARTIAL), 
                            batteryRealtime, "p", which, linePrefix);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), 
                            batteryRealtime, "w", which, linePrefix);
                    
                    // Only log if we had at lease one wakelock...
                    if (sb.length() > 0) {
                       dumpLine(pw, uid, category, WAKELOCK_DATA, ent.getKey(), sb.toString());
                    }
                }
            }
                
            Map<Integer, ? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            if (sensors.size() > 0)  {
                for (Map.Entry<Integer, ? extends BatteryStats.Uid.Sensor> ent
                        : sensors.entrySet()) {
                    Uid.Sensor se = ent.getValue();
                    int sensorNumber = ent.getKey();
                    Timer timer = se.getSensorTime();
                    if (timer != null) {
                        // Convert from microseconds to milliseconds with rounding
                        long totalTime = (timer.getTotalTimeLocked(batteryRealtime, which) + 500) / 1000;
                        int count = timer.getCountLocked(which);
                        if (totalTime != 0) {
                            dumpLine(pw, uid, category, SENSOR_DATA, sensorNumber, totalTime, count);
                        }
                    } 
                }
            }

            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            if (processStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                        : processStats.entrySet()) {
                    Uid.Proc ps = ent.getValue();
    
                    long userTime = ps.getUserTime(which);
                    long systemTime = ps.getSystemTime(which);
                    int starts = ps.getStarts(which);
    
                    if (userTime != 0 || systemTime != 0 || starts != 0) {
                        dumpLine(pw, uid, category, PROCESS_DATA, 
                                ent.getKey(), // proc
                                userTime * 10, // cpu time in ms
                                systemTime * 10, // user time in ms
                                starts); // process starts
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

    @SuppressWarnings("unused")
    public final void dumpLocked(PrintWriter pw, String prefix, int which, int reqUid) {
        final long rawUptime = SystemClock.uptimeMillis() * 1000;
        final long rawRealtime = SystemClock.elapsedRealtime() * 1000;
        final long batteryUptime = getBatteryUptime(rawUptime);
        final long batteryRealtime = getBatteryRealtime(rawRealtime);

        final long whichBatteryUptime = computeBatteryUptime(rawUptime, which);
        final long whichBatteryRealtime = computeBatteryRealtime(rawRealtime, which);
        final long totalRealtime = computeRealtime(rawRealtime, which);
        final long totalUptime = computeUptime(rawUptime, which);
        
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
                sb.append("  Total run time: ");
                formatTimeMs(sb, totalRealtime / 1000);
                sb.append("realtime, ");
                formatTimeMs(sb, totalUptime / 1000);
                sb.append("uptime, ");
        pw.println(sb.toString());
        
        final long screenOnTime = getScreenOnTime(batteryRealtime, which);
        final long phoneOnTime = getPhoneOnTime(batteryRealtime, which);
        final long wifiRunningTime = getGlobalWifiRunningTime(batteryRealtime, which);
        final long wifiOnTime = getWifiOnTime(batteryRealtime, which);
        final long bluetoothOnTime = getBluetoothOnTime(batteryRealtime, which);
        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Screen on: "); formatTimeMs(sb, screenOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(screenOnTime, whichBatteryRealtime));
                sb.append("), Input events: "); sb.append(getInputEventCount(which));
                sb.append(", Active phone call: "); formatTimeMs(sb, phoneOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(phoneOnTime, whichBatteryRealtime));
                sb.append(")");
        pw.println(sb.toString());
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Screen brightnesses: ");
        boolean didOne = false;
        for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            final long time = getScreenBrightnessTime(i, batteryRealtime, which);
            if (time == 0) {
                continue;
            }
            if (didOne) sb.append(", ");
            didOne = true;
            sb.append(SCREEN_BRIGHTNESS_NAMES[i]);
            sb.append(" ");
            formatTimeMs(sb, time/1000);
            sb.append("(");
            sb.append(formatRatioLocked(time, screenOnTime));
            sb.append(")");
        }
        if (!didOne) sb.append("No activity");
        pw.println(sb.toString());
        
        // Calculate total network and wakelock times across all uids.
        long rxTotal = 0;
        long txTotal = 0;
        long fullWakeLockTimeTotalMicros = 0;
        long partialWakeLockTimeTotalMicros = 0;
        
        if (reqUid < 0) {
            Map<String, ? extends BatteryStats.Timer> kernelWakelocks = getKernelWakelockStats();
            if (kernelWakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Timer> ent : kernelWakelocks.entrySet()) {
                    
                    String linePrefix = ": ";
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("  Kernel Wake lock ");
                    sb.append(ent.getKey());
                    linePrefix = printWakeLock(sb, ent.getValue(), batteryRealtime, null, which, 
                            linePrefix);
                    if (!linePrefix.equals(": ")) {
                        sb.append(" realtime");
                        // Only print out wake locks that were held
                        pw.println(sb.toString());
                    }
                }
            }
        }
    
        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);
            rxTotal += u.getTcpBytesReceived(which);
            txTotal += u.getTcpBytesSent(which);
            
            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> ent 
                        : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    
                    Timer fullWakeTimer = wl.getWakeTime(WAKE_TYPE_FULL);
                    if (fullWakeTimer != null) {
                        fullWakeLockTimeTotalMicros += fullWakeTimer.getTotalTimeLocked(
                                batteryRealtime, which);
                    }

                    Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                    if (partialWakeTimer != null) {
                        partialWakeLockTimeTotalMicros += partialWakeTimer.getTotalTimeLocked(
                                batteryRealtime, which);
                    }
                }
            }
        }
        
        pw.print(prefix);
                pw.print("  Total received: "); pw.print(formatBytesLocked(rxTotal));
                pw.print(", Total sent: "); pw.println(formatBytesLocked(txTotal));
        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Total full wakelock time: "); formatTimeMs(sb,
                        (fullWakeLockTimeTotalMicros + 500) / 1000);
                sb.append(", Total partial waklock time: "); formatTimeMs(sb,
                        (partialWakeLockTimeTotalMicros + 500) / 1000);
        pw.println(sb.toString());
        
        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Signal levels: ");
        didOne = false;
        for (int i=0; i<SignalStrength.NUM_SIGNAL_STRENGTH_BINS; i++) {
            final long time = getPhoneSignalStrengthTime(i, batteryRealtime, which);
            if (time == 0) {
                continue;
            }
            if (didOne) sb.append(", ");
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
        if (!didOne) sb.append("No activity");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Signal scanning time: ");
        formatTimeMs(sb, getPhoneSignalScanningTime(batteryRealtime, which) / 1000);
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Radio types: ");
        didOne = false;
        for (int i=0; i<NUM_DATA_CONNECTION_TYPES; i++) {
            final long time = getPhoneDataConnectionTime(i, batteryRealtime, which);
            if (time == 0) {
                continue;
            }
            if (didOne) sb.append(", ");
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
        if (!didOne) sb.append("No activity");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
        sb.append("  Radio data uptime when unplugged: ");
        sb.append(getRadioDataUptime() / 1000);
        sb.append(" ms");
        pw.println(sb.toString());

        sb.setLength(0);
        sb.append(prefix);
                sb.append("  Wifi on: "); formatTimeMs(sb, wifiOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(wifiOnTime, whichBatteryRealtime));
                sb.append("), Wifi running: "); formatTimeMs(sb, wifiRunningTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(wifiRunningTime, whichBatteryRealtime));
                sb.append("), Bluetooth on: "); formatTimeMs(sb, bluetoothOnTime / 1000);
                sb.append("("); sb.append(formatRatioLocked(bluetoothOnTime, whichBatteryRealtime));
                sb.append(")");
        pw.println(sb.toString());
        
        pw.println(" ");

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
            pw.println(" ");
        }
        

        for (int iu=0; iu<NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            if (reqUid >= 0 && uid != reqUid && uid != Process.SYSTEM_UID) {
                continue;
            }
            
            Uid u = uidStats.valueAt(iu);
            
            pw.println(prefix + "  #" + uid + ":");
            boolean uidActivity = false;
            
            long tcpReceived = u.getTcpBytesReceived(which);
            long tcpSent = u.getTcpBytesSent(which);
            long fullWifiLockOnTime = u.getFullWifiLockTime(batteryRealtime, which);
            long wifiScanTime = u.getWifiScanTime(batteryRealtime, which);
            long uidWifiRunningTime = u.getWifiRunningTime(batteryRealtime, which);
            
            if (tcpReceived != 0 || tcpSent != 0) {
                pw.print(prefix); pw.print("    Network: ");
                        pw.print(formatBytesLocked(tcpReceived)); pw.print(" received, ");
                        pw.print(formatBytesLocked(tcpSent)); pw.println(" sent");
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

            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
                long totalFull = 0, totalPartial = 0, totalWindow = 0;
                int count = 0;
                for (Map.Entry<String, ? extends BatteryStats.Uid.Wakelock> ent
                    : wakelocks.entrySet()) {
                    Uid.Wakelock wl = ent.getValue();
                    String linePrefix = ": ";
                    sb.setLength(0);
                    sb.append(prefix);
                    sb.append("    Wake lock ");
                    sb.append(ent.getKey());
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_FULL), batteryRealtime,
                            "full", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_PARTIAL), batteryRealtime,
                            "partial", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), batteryRealtime,
                            "window", which, linePrefix);
                    if (!linePrefix.equals(": ")) {
                        sb.append(" realtime");
                        // Only print out wake locks that were held
                        pw.println(sb.toString());
                        uidActivity = true;
                        count++;
                    }
                    totalFull += computeWakeLock(wl.getWakeTime(WAKE_TYPE_FULL),
                            batteryRealtime, which);
                    totalPartial += computeWakeLock(wl.getWakeTime(WAKE_TYPE_PARTIAL),
                            batteryRealtime, which);
                    totalWindow += computeWakeLock(wl.getWakeTime(WAKE_TYPE_WINDOW),
                            batteryRealtime, which);
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

            Map<Integer, ? extends BatteryStats.Uid.Sensor> sensors = u.getSensorStats();
            if (sensors.size() > 0) {
                for (Map.Entry<Integer, ? extends BatteryStats.Uid.Sensor> ent
                    : sensors.entrySet()) {
                    Uid.Sensor se = ent.getValue();
                    int sensorNumber = ent.getKey();
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
                                batteryRealtime, which) + 500) / 1000;
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
            }

            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            if (processStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                    : processStats.entrySet()) {
                    Uid.Proc ps = ent.getValue();
                    long userTime;
                    long systemTime;
                    int starts;
                    int numExcessive;

                    userTime = ps.getUserTime(which);
                    systemTime = ps.getSystemTime(which);
                    starts = ps.getStarts(which);
                    numExcessive = which == STATS_SINCE_CHARGED
                            ? ps.countExcessivePowers() : 0;

                    if (userTime != 0 || systemTime != 0 || starts != 0
                            || numExcessive != 0) {
                        sb.setLength(0);
                        sb.append(prefix); sb.append("    Proc ");
                                sb.append(ent.getKey()); sb.append(":\n");
                        sb.append(prefix); sb.append("      CPU: ");
                                formatTime(sb, userTime); sb.append("usr + ");
                                formatTime(sb, systemTime); sb.append("krn");
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
                                        pw.print(" (");
                                        pw.print((ew.usedTime*100)/ew.overTime);
                                        pw.println("%)");
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
                                        sb.append(" uptime\n");
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

    static void printBitDescriptions(PrintWriter pw, int oldval, int newval, BitDescription[] descriptions) {
        int diff = oldval ^ newval;
        if (diff == 0) return;
        for (int i=0; i<descriptions.length; i++) {
            BitDescription bd = descriptions[i];
            if ((diff&bd.mask) != 0) {
                if (bd.shift < 0) {
                    pw.print((newval&bd.mask) != 0 ? " +" : " -");
                    pw.print(bd.name);
                } else {
                    pw.print(" ");
                    pw.print(bd.name);
                    pw.print("=");
                    int val = (newval&bd.mask)>>bd.shift;
                    if (bd.values != null && val >= 0 && val < bd.values.length) {
                        pw.print(bd.values[val]);
                    } else {
                        pw.print(val);
                    }
                }
            }
        }
    }
    
    public void prepareForDumpLocked() {
    }

    public static class HistoryPrinter {
        int oldState = 0;
        int oldStatus = -1;
        int oldHealth = -1;
        int oldPlug = -1;
        int oldTemp = -1;
        int oldVolt = -1;

        public void printNextItem(PrintWriter pw, HistoryItem rec, long now) {
            pw.print("  ");
            TimeUtils.formatDuration(rec.time-now, pw, TimeUtils.HUNDRED_DAY_FIELD_LEN);
            pw.print(" ");
            if (rec.cmd == HistoryItem.CMD_START) {
                pw.println(" START");
            } else if (rec.cmd == HistoryItem.CMD_OVERFLOW) {
                pw.println(" *OVERFLOW*");
            } else {
                if (rec.batteryLevel < 10) pw.print("00");
                else if (rec.batteryLevel < 100) pw.print("0");
                pw.print(rec.batteryLevel);
                pw.print(" ");
                if (rec.states < 0x10) pw.print("0000000");
                else if (rec.states < 0x100) pw.print("000000");
                else if (rec.states < 0x1000) pw.print("00000");
                else if (rec.states < 0x10000) pw.print("0000");
                else if (rec.states < 0x100000) pw.print("000");
                else if (rec.states < 0x1000000) pw.print("00");
                else if (rec.states < 0x10000000) pw.print("0");
                pw.print(Integer.toHexString(rec.states));
                if (oldStatus != rec.batteryStatus) {
                    oldStatus = rec.batteryStatus;
                    pw.print(" status=");
                    switch (oldStatus) {
                        case BatteryManager.BATTERY_STATUS_UNKNOWN:
                            pw.print("unknown");
                            break;
                        case BatteryManager.BATTERY_STATUS_CHARGING:
                            pw.print("charging");
                            break;
                        case BatteryManager.BATTERY_STATUS_DISCHARGING:
                            pw.print("discharging");
                            break;
                        case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                            pw.print("not-charging");
                            break;
                        case BatteryManager.BATTERY_STATUS_FULL:
                            pw.print("full");
                            break;
                        default:
                            pw.print(oldStatus);
                            break;
                    }
                }
                if (oldHealth != rec.batteryHealth) {
                    oldHealth = rec.batteryHealth;
                    pw.print(" health=");
                    switch (oldHealth) {
                        case BatteryManager.BATTERY_HEALTH_UNKNOWN:
                            pw.print("unknown");
                            break;
                        case BatteryManager.BATTERY_HEALTH_GOOD:
                            pw.print("good");
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                            pw.print("overheat");
                            break;
                        case BatteryManager.BATTERY_HEALTH_DEAD:
                            pw.print("dead");
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                            pw.print("over-voltage");
                            break;
                        case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                            pw.print("failure");
                            break;
                        default:
                            pw.print(oldHealth);
                            break;
                    }
                }
                if (oldPlug != rec.batteryPlugType) {
                    oldPlug = rec.batteryPlugType;
                    pw.print(" plug=");
                    switch (oldPlug) {
                        case 0:
                            pw.print("none");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_AC:
                            pw.print("ac");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_USB:
                            pw.print("usb");
                            break;
                        case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                            pw.print("wireless");
                            break;
                        default:
                            pw.print(oldPlug);
                            break;
                    }
                }
                if (oldTemp != rec.batteryTemperature) {
                    oldTemp = rec.batteryTemperature;
                    pw.print(" temp=");
                    pw.print(oldTemp);
                }
                if (oldVolt != rec.batteryVoltage) {
                    oldVolt = rec.batteryVoltage;
                    pw.print(" volt=");
                    pw.print(oldVolt);
                }
                printBitDescriptions(pw, oldState, rec.states,
                        HISTORY_STATE_DESCRIPTIONS);
                pw.println();
            }
            oldState = rec.states;
        }
    }

    /**
     * Dumps a human-readable summary of the battery statistics to the given PrintWriter.
     *
     * @param pw a Printer to receive the dump output.
     */
    @SuppressWarnings("unused")
    public void dumpLocked(PrintWriter pw) {
        prepareForDumpLocked();

        long now = getHistoryBaseTime() + SystemClock.elapsedRealtime();

        final HistoryItem rec = new HistoryItem();
        if (startIteratingHistoryLocked()) {
            pw.println("Battery History:");
            HistoryPrinter hprinter = new HistoryPrinter();
            while (getNextHistoryLocked(rec)) {
                hprinter.printNextItem(pw, rec, now);
            }
            finishIteratingHistoryLocked();
            pw.println("");
        }

        if (startIteratingOldHistoryLocked()) {
            pw.println("Old battery History:");
            HistoryPrinter hprinter = new HistoryPrinter();
            while (getNextOldHistoryLocked(rec)) {
                hprinter.printNextItem(pw, rec, now);
            }
            finishIteratingOldHistoryLocked();
            pw.println("");
        }
        
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
                    long time = pid.mWakeSum + (pid.mWakeStart != 0
                            ? (nowRealtime - pid.mWakeStart) : 0);
                    pw.print("  PID "); pw.print(pids.keyAt(j));
                            pw.print(" wake time: ");
                            TimeUtils.formatDuration(time, pw);
                            pw.println("");
                }
            }
        }
        if (didPid) {
            pw.println("");
        }
        
        pw.println("Statistics since last charge:");
        pw.println("  System starts: " + getStartCount()
                + ", currently on battery: " + getIsOnBattery());
        dumpLocked(pw, "", STATS_SINCE_CHARGED, -1);
        pw.println("");
        pw.println("Statistics since last unplugged:");
        dumpLocked(pw, "", STATS_SINCE_UNPLUGGED, -1);
    }
    
    @SuppressWarnings("unused")
    public void dumpCheckinLocked(PrintWriter pw, String[] args, List<ApplicationInfo> apps) {
        prepareForDumpLocked();

        boolean isUnpluggedOnly = false;
        
        for (String arg : args) {
            if ("-u".equals(arg)) {
                if (LOCAL_LOGV) Log.v("BatteryStats", "Dumping unplugged data");
                isUnpluggedOnly = true;
            }
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
        if (isUnpluggedOnly) {
            dumpCheckinLocked(pw, STATS_SINCE_UNPLUGGED, -1);
        }
        else {
            dumpCheckinLocked(pw, STATS_SINCE_CHARGED, -1);
            dumpCheckinLocked(pw, STATS_SINCE_UNPLUGGED, -1);
        }
    }
}
