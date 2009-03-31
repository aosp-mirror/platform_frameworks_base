package android.os;

import java.io.PrintWriter;
import java.util.Formatter;
import java.util.Map;

import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;

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
     * 
     * {@hide}
     */
    public static final int SENSOR = 3;
    
    /**
     * A constant indicating a a wifi turn on timer
     *
     * {@hide}
     */
    public static final int WIFI_TURNED_ON = 4;
    
    /**
     * A constant indicating a full wifi lock timer
     *
     * {@hide}
     */
    public static final int FULL_WIFI_LOCK = 5;
    
    /**
     * A constant indicating a scan wifi lock timer
     *
     * {@hide}
     */
    public static final int SCAN_WIFI_LOCK = 6;

    /**
     * Include all of the data in the stats, including previously saved data.
     */
    public static final int STATS_TOTAL = 0;

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
    public static final int STATS_UNPLUGGED = 3;
    
    /**
     * Bump the version on this if the checkin format changes.
     */
    private static final int BATTERY_STATS_CHECKIN_VERSION = 3;
    
    private static final long BYTES_PER_KB = 1024;
    private static final long BYTES_PER_MB = 1048576; // 1024^2
    private static final long BYTES_PER_GB = 1073741824; //1024^3
    
    // TODO: Update this list if you add/change any stats above.
    private static final String[] STAT_NAMES = { "total", "last", "current", "unplugged" };

    private static final String APK_DATA = "apk";
    private static final String PROCESS_DATA = "process";
    private static final String SENSOR_DATA = "sensor";
    private static final String WAKELOCK_DATA = "wakelock";
    private static final String NETWORK_DATA = "network";
    private static final String USER_ACTIVITY_DATA = "useract";
    private static final String BATTERY_DATA = "battery";
    private static final String WIFI_LOCK_DATA = "wifilock";
    private static final String MISC_DATA = "misc";
    private static final String SCREEN_BRIGHTNESS_DATA = "brightness";
    private static final String SIGNAL_STRENGTH_TIME_DATA = "sigtime";
    private static final String SIGNAL_STRENGTH_COUNT_DATA = "sigcnt";
    private static final String DATA_CONNECTION_TIME_DATA = "dconntime";
    private static final String DATA_CONNECTION_COUNT_DATA = "dconncnt";

    private final StringBuilder mFormatBuilder = new StringBuilder(8);
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
        public abstract int getCount(int which);

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
        public abstract int getCount(int which);

        /**
         * Returns the total time in microseconds associated with this Timer for the
         * selected type of statistics.
         *
         * @param batteryRealtime system realtime on  battery in microseconds
         * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT
         * @return a time in microseconds
         */
        public abstract long getTotalTime(long batteryRealtime, int which);
        
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
        
        public abstract void noteWifiTurnedOnLocked();
        public abstract void noteWifiTurnedOffLocked();
        public abstract void noteFullWifiLockAcquiredLocked();
        public abstract void noteFullWifiLockReleasedLocked();
        public abstract void noteScanWifiLockAcquiredLocked();
        public abstract void noteScanWifiLockReleasedLocked();
        public abstract long getWifiTurnedOnTime(long batteryRealtime, int which);
        public abstract long getFullWifiLockTime(long batteryRealtime, int which);
        public abstract long getScanWifiLockTime(long batteryRealtime, int which);

        /**
         * Note that these must match the constants in android.os.LocalPowerManager.
         */
        static final String[] USER_ACTIVITY_TYPES = {
            "other", "cheek", "touch", "long_touch", "touch_up", "button", "unknown"
        };
        
        public static final int NUM_USER_ACTIVITY_TYPES = 7;
        
        public abstract void noteUserActivityLocked(int type);
        public abstract boolean hasUserActivity();
        public abstract int getUserActivityCount(int type, int which);
        
        public static abstract class Sensor {
            // Magic sensor number for the GPS.
            public static final int GPS = -10000;
            
            public abstract int getHandle();
            
            public abstract Timer getSensorTime();
        }

        /**
         * The statistics associated with a particular process.
         */
        public static abstract class Proc {

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

    /**
     * Returns the number of times the device has been started.
     */
    public abstract int getStartCount();
    
    /**
     * Returns the time in milliseconds that the screen has been on while the device was
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
     * Returns the time in milliseconds that the screen has been on with
     * the given brightness
     * 
     * {@hide}
     */
    public abstract long getScreenBrightnessTime(int brightnessBin,
            long batteryRealtime, int which);

    public abstract int getInputEventCount(int which);
    
    /**
     * Returns the time in milliseconds that the phone has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getPhoneOnTime(long batteryRealtime, int which);

    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN = 0;
    public static final int SIGNAL_STRENGTH_POOR = 1;
    public static final int SIGNAL_STRENGTH_MODERATE = 2;
    public static final int SIGNAL_STRENGTH_GOOD = 3;
    public static final int SIGNAL_STRENGTH_GREAT = 4;
    
    static final String[] SIGNAL_STRENGTH_NAMES = {
        "none", "poor", "moderate", "good", "great"
    };
    
    public static final int NUM_SIGNAL_STRENGTH_BINS = 5;
    
    /**
     * Returns the time in milliseconds that the phone has been running with
     * the given signal strength.
     * 
     * {@hide}
     */
    public abstract long getPhoneSignalStrengthTime(int strengthBin,
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
    public static final int DATA_CONNECTION_OTHER = 4;
    
    static final String[] DATA_CONNECTION_NAMES = {
        "none", "gprs", "edge", "umts", "other"
    };
    
    public static final int NUM_DATA_CONNECTION_TYPES = 5;
    
    /**
     * Returns the time in milliseconds that the phone has been running with
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

    /**
     * Returns the time in milliseconds that wifi has been on while the device was
     * running on battery.
     * 
     * {@hide}
     */
    public abstract long getWifiOnTime(long batteryRealtime, int which);

    /**
     * Returns the time in milliseconds that wifi has been on and the driver has
     * been in the running state while the device was running on battery.
     *
     * {@hide}
     */
    public abstract long getWifiRunningTime(long batteryRealtime, int which);

    /**
     * Returns the time in milliseconds that bluetooth has been on while the device was
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
     * Returns the current battery realtime in microseconds.
     *
     * @param curTime the amount of elapsed realtime in microseconds.
     */
    public abstract long getBatteryRealtime(long curTime);
    
    /**
     * Returns the battery percentage level at the last time the device was unplugged from power, 
     * or the last time it was booted while unplugged.
     */
    public abstract int getUnpluggedStartLevel();
    
    /**
     * Returns the battery percentage level at the last time the device was plugged into power.
     */
    public abstract int getPluggedStartLevel();

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

    private final static void formatTime(StringBuilder out, long seconds) {
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

    private final static String formatTime(long time) {
        long sec = time / 100;
        StringBuilder sb = new StringBuilder();
        formatTime(sb, sec);
        sb.append((time - (sec * 100)) * 10);
        sb.append("ms ");
        return sb.toString();
    }

    private final static String formatTimeMs(long time) {
        long sec = time / 1000;
        StringBuilder sb = new StringBuilder();
        formatTime(sb, sec);
        sb.append(time - (sec * 1000));
        sb.append("ms ");
        return sb.toString();
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
            // Convert from microseconds to milliseconds with rounding
            long totalTimeMicros = timer.getTotalTime(batteryRealtime, which);
            long totalTimeMillis = (totalTimeMicros + 500) / 1000;
            
            int count = timer.getCount(which);
            if (totalTimeMillis != 0) {
                sb.append(linePrefix);
                sb.append(formatTimeMs(totalTimeMillis));
                sb.append(name);
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
            totalTimeMicros = timer.getTotalTime(now, which);
            count = timer.getCount(which); 
        }
        sb.append(linePrefix);
        sb.append((totalTimeMicros + 500) / 1000); // microseconds to milliseconds with rounding
        sb.append(',');
        sb.append(name);
        sb.append(',');
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
     * @param fd
     * @param pw
     * @param which
     */
    private final void dumpCheckinLocked(PrintWriter pw, int which) {
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
        final long wifiRunningTime = getWifiRunningTime(batteryRealtime, which);
        final long bluetoothOnTime = getBluetoothOnTime(batteryRealtime, which);
       
        StringBuilder sb = new StringBuilder(128);
        
        SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();
        
        String category = STAT_NAMES[which];
        
        // Dump "battery" stat
        dumpLine(pw, 0 /* uid */, category, BATTERY_DATA, 
                which == STATS_TOTAL ? getStartCount() : "N/A",
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
                        fullWakeLockTimeTotal += fullWakeTimer.getTotalTime(batteryRealtime, which);
                    }

                    Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                    if (partialWakeTimer != null) {
                        partialWakeLockTimeTotal += partialWakeTimer.getTotalTime(
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
        args = new Object[NUM_SIGNAL_STRENGTH_BINS];
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            args[i] = getPhoneSignalStrengthTime(i, batteryRealtime, which) / 1000;
        }
        dumpLine(pw, 0 /* uid */, category, SIGNAL_STRENGTH_TIME_DATA, args);
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
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
        
        if (which == STATS_UNPLUGGED) {
            dumpLine(pw, 0 /* uid */, category, BATTERY_DATA, getUnpluggedStartLevel(), 
                    getPluggedStartLevel());
        }
        
        for (int iu = 0; iu < NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            Uid u = uidStats.valueAt(iu);
            // Dump Network stats per uid, if any
            long rx = u.getTcpBytesReceived(which);
            long tx = u.getTcpBytesSent(which);
            long fullWifiLockOnTime = u.getFullWifiLockTime(batteryRealtime, which);
            long scanWifiLockOnTime = u.getScanWifiLockTime(batteryRealtime, which);
            long wifiTurnedOnTime = u.getWifiTurnedOnTime(batteryRealtime, which);
            
            if (rx > 0 || tx > 0) dumpLine(pw, uid, category, NETWORK_DATA, rx, tx);
            
            if (fullWifiLockOnTime != 0 || scanWifiLockOnTime != 0
                    || wifiTurnedOnTime != 0) {
                dumpLine(pw, uid, category, WIFI_LOCK_DATA, 
                        fullWifiLockOnTime, scanWifiLockOnTime, wifiTurnedOnTime);
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
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_FULL), batteryRealtime,
                            "full", which, linePrefix);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_PARTIAL), batteryRealtime,
                            "partial", which, linePrefix);
                    linePrefix = printWakeLockCheckin(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), batteryRealtime,
                            "window", which, linePrefix);
                    
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
                        long totalTime = (timer.getTotalTime(batteryRealtime, which) + 500) / 1000;
                        int count = timer.getCount(which);
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
    private final void dumpLocked(Printer pw, String prefix, int which) {
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

        pw.println(prefix
                + "  Time on battery: "
                + formatTimeMs(whichBatteryRealtime / 1000) + "("
                + formatRatioLocked(whichBatteryRealtime, totalRealtime)
                + ") realtime, "
                + formatTimeMs(whichBatteryUptime / 1000)
                + "(" + formatRatioLocked(whichBatteryUptime, totalRealtime)
                + ") uptime");
        pw.println(prefix
                + "  Total run time: "
                + formatTimeMs(totalRealtime / 1000)
                + "realtime, "
                + formatTimeMs(totalUptime / 1000)
                + "uptime, ");
        
        final long screenOnTime = getScreenOnTime(batteryRealtime, which);
        final long phoneOnTime = getPhoneOnTime(batteryRealtime, which);
        final long wifiRunningTime = getWifiRunningTime(batteryRealtime, which);
        final long wifiOnTime = getWifiOnTime(batteryRealtime, which);
        final long bluetoothOnTime = getBluetoothOnTime(batteryRealtime, which);
        pw.println(prefix
                + "  Screen on: " + formatTimeMs(screenOnTime / 1000)
                + "(" + formatRatioLocked(screenOnTime, whichBatteryRealtime)
                + "), Input events: " + getInputEventCount(which)
                + ", Active phone call: " + formatTimeMs(phoneOnTime / 1000)
                + "(" + formatRatioLocked(phoneOnTime, whichBatteryRealtime) + ")");
        sb.setLength(0);
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
            sb.append(formatTimeMs(time/1000));
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
                        fullWakeLockTimeTotalMicros += fullWakeTimer.getTotalTime(
                                batteryRealtime, which);
                    }

                    Timer partialWakeTimer = wl.getWakeTime(WAKE_TYPE_PARTIAL);
                    if (partialWakeTimer != null) {
                        partialWakeLockTimeTotalMicros += partialWakeTimer.getTotalTime(
                                batteryRealtime, which);
                    }
                }
            }
        }
        
        pw.println(prefix
                + "  Total received: " + formatBytesLocked(rxTotal)
                + ", Total sent: " + formatBytesLocked(txTotal));
        pw.println(prefix
                + "  Total full wakelock time: " + formatTimeMs(
                        (fullWakeLockTimeTotalMicros + 500) / 1000)
                + ", Total partial waklock time: " + formatTimeMs(
                        (partialWakeLockTimeTotalMicros + 500) / 1000));
        
        sb.setLength(0);
        sb.append("  Signal levels: ");
        didOne = false;
        for (int i=0; i<NUM_SIGNAL_STRENGTH_BINS; i++) {
            final long time = getPhoneSignalStrengthTime(i, batteryRealtime, which);
            if (time == 0) {
                continue;
            }
            if (didOne) sb.append(", ");
            didOne = true;
            sb.append(SIGNAL_STRENGTH_NAMES[i]);
            sb.append(" ");
            sb.append(formatTimeMs(time/1000));
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getPhoneSignalStrengthCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append("No activity");
        pw.println(sb.toString());
        
        sb.setLength(0);
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
            sb.append(formatTimeMs(time/1000));
            sb.append("(");
            sb.append(formatRatioLocked(time, whichBatteryRealtime));
            sb.append(") ");
            sb.append(getPhoneDataConnectionCount(i, which));
            sb.append("x");
        }
        if (!didOne) sb.append("No activity");
        pw.println(sb.toString());
        
        pw.println(prefix
                + "  Wifi on: " + formatTimeMs(wifiOnTime / 1000)
                + "(" + formatRatioLocked(wifiOnTime, whichBatteryRealtime)
                + "), Wifi running: " + formatTimeMs(wifiRunningTime / 1000)
                + "(" + formatRatioLocked(wifiRunningTime, whichBatteryRealtime)
                + "), Bluetooth on: " + formatTimeMs(bluetoothOnTime / 1000)
                + "(" + formatRatioLocked(bluetoothOnTime, whichBatteryRealtime)+ ")");
        
        pw.println(" ");

        if (which == STATS_UNPLUGGED) {
            if (getIsOnBattery()) {
                pw.println(prefix + "  Device is currently unplugged");
                pw.println(prefix + "    Discharge cycle start level: " + 
                        getUnpluggedStartLevel());
            } else {
                pw.println(prefix + "  Device is currently plugged into power");
                pw.println(prefix + "    Last discharge cycle start level: " + 
                        getUnpluggedStartLevel());
                pw.println(prefix + "    Last discharge cycle end level: " + 
                        getPluggedStartLevel());
            }
            pw.println(" ");
        }
        

        for (int iu=0; iu<NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            Uid u = uidStats.valueAt(iu);
            pw.println(prefix + "  #" + uid + ":");
            boolean uidActivity = false;
            
            long tcpReceived = u.getTcpBytesReceived(which);
            long tcpSent = u.getTcpBytesSent(which);
            long fullWifiLockOnTime = u.getFullWifiLockTime(batteryRealtime, which);
            long scanWifiLockOnTime = u.getScanWifiLockTime(batteryRealtime, which);
            long wifiTurnedOnTime = u.getWifiTurnedOnTime(batteryRealtime, which);
            
            if (tcpReceived != 0 || tcpSent != 0) {
                pw.println(prefix + "    Network: " + formatBytesLocked(tcpReceived) + " received, "
                        + formatBytesLocked(tcpSent) + " sent");
            }
            
            if (u.hasUserActivity()) {
                boolean hasData = false;
                for (int i=0; i<NUM_SCREEN_BRIGHTNESS_BINS; i++) {
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
            
            if (fullWifiLockOnTime != 0 || scanWifiLockOnTime != 0
                    || wifiTurnedOnTime != 0) {
                pw.println(prefix + "    Turned Wifi On Time: " 
                        + formatTimeMs(wifiTurnedOnTime / 1000) 
                        + "(" + formatRatioLocked(wifiTurnedOnTime, 
                                whichBatteryRealtime)+ ")");
                pw.println(prefix + "    Full Wifi Lock Time: " 
                        + formatTimeMs(fullWifiLockOnTime / 1000) 
                        + "(" + formatRatioLocked(fullWifiLockOnTime, 
                                whichBatteryRealtime)+ ")");
                pw.println(prefix + "    Scan Wifi Lock Time: " 
                        + formatTimeMs(scanWifiLockOnTime / 1000)
                        + "(" + formatRatioLocked(scanWifiLockOnTime, 
                                whichBatteryRealtime)+ ")");
            }

            Map<String, ? extends BatteryStats.Uid.Wakelock> wakelocks = u.getWakelockStats();
            if (wakelocks.size() > 0) {
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
                    } else {
                        sb.append(": (nothing executed)");
                    }
                    pw.println(sb.toString());
                    uidActivity = true;
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
                        long totalTime = (timer.getTotalTime(batteryRealtime, which) + 500) / 1000;
                        int count = timer.getCount(which);
                        //timer.logState();
                        if (totalTime != 0) {
                            sb.append(formatTimeMs(totalTime));
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

                    userTime = ps.getUserTime(which);
                    systemTime = ps.getSystemTime(which);
                    starts = ps.getStarts(which);

                    if (userTime != 0 || systemTime != 0 || starts != 0) {
                        pw.println(prefix + "    Proc " + ent.getKey() + ":");
                        pw.println(prefix + "      CPU: " + formatTime(userTime) + "user + "
                                + formatTime(systemTime) + "kernel");
                        pw.println(prefix + "      " + starts + " process starts");
                        uidActivity = true;
                    }
                }
            }

            Map<String, ? extends BatteryStats.Uid.Pkg> packageStats = u.getPackageStats();
            if (packageStats.size() > 0) {
                for (Map.Entry<String, ? extends BatteryStats.Uid.Pkg> ent
                    : packageStats.entrySet()) {
                    pw.println(prefix + "    Apk " + ent.getKey() + ":");
                    boolean apkActivity = false;
                    Uid.Pkg ps = ent.getValue();
                    int wakeups = ps.getWakeups(which);
                    if (wakeups != 0) {
                        pw.println(prefix + "      " + wakeups + " wakeup alarms");
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
                                pw.println(prefix + "      Service " + sent.getKey() + ":");
                                pw.println(prefix + "        Created for: "
                                        + formatTimeMs(startTime / 1000)
                                        + " uptime");
                                pw.println(prefix + "        Starts: " + starts
                                        + ", launches: " + launches);
                                apkActivity = true;
                            }
                        }
                    }
                    if (!apkActivity) {
                        pw.println(prefix + "      (nothing executed)");
                    }
                    uidActivity = true;
                }
            }
            if (!uidActivity) {
                pw.println(prefix + "    (nothing executed)");
            }
        }
    }

    /**
     * Dumps a human-readable summary of the battery statistics to the given PrintWriter.
     *
     * @param pw a Printer to receive the dump output.
     */
    @SuppressWarnings("unused")
    public void dumpLocked(Printer pw) {
        pw.println("Total Statistics (Current and Historic):");
        pw.println("  System starts: " + getStartCount()
                + ", currently on battery: " + getIsOnBattery());
        dumpLocked(pw, "", STATS_TOTAL);
        pw.println("");
        pw.println("Last Run Statistics (Previous run of system):");
        dumpLocked(pw, "", STATS_LAST);
        pw.println("");
        pw.println("Current Battery Statistics (Currently running system):");
        dumpLocked(pw, "", STATS_CURRENT);
        pw.println("");
        pw.println("Unplugged Statistics (Since last unplugged from power):");
        dumpLocked(pw, "", STATS_UNPLUGGED);
    }
    
    @SuppressWarnings("unused")
    public void dumpCheckinLocked(PrintWriter pw, String[] args) {
        boolean isUnpluggedOnly = false;
        
        for (String arg : args) {
            if ("-u".equals(arg)) {
                if (LOCAL_LOGV) Log.v("BatteryStats", "Dumping unplugged data");
                isUnpluggedOnly = true;
            }
        }
        
        if (isUnpluggedOnly) {
            dumpCheckinLocked(pw, STATS_UNPLUGGED);
        }
        else {
            dumpCheckinLocked(pw, STATS_TOTAL);
            dumpCheckinLocked(pw, STATS_LAST);
            dumpCheckinLocked(pw, STATS_UNPLUGGED);
            dumpCheckinLocked(pw, STATS_CURRENT);
        }
    }
    
}
