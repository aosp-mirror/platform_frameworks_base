package android.os;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Formatter;
import java.util.Map;

import android.util.SparseArray;

/**
 * A class providing access to battery usage statistics, including information on
 * wakelocks, processes, packages, and services.  All times are represented in microseconds
 * except where indicated otherwise.
 */
public abstract class BatteryStats {

    /**
     * A constant indicating a partial wake lock.
     */
    public static final int WAKE_TYPE_PARTIAL = 0;

    /**
     * A constant indicating a full wake lock.
     */
    public static final int WAKE_TYPE_FULL = 1;

    /**
     * A constant indicating a window wake lock.
     */
    public static final int WAKE_TYPE_WINDOW = 2;

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

    private final StringBuilder mFormatBuilder = new StringBuilder(8);
    private final Formatter mFormatter = new Formatter(mFormatBuilder);

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
         * @param now system uptime time in microseconds
         * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT
         * @return a time in microseconds
         */
        public abstract long getTotalTime(long now, int which);
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

        public static abstract class Sensor {
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
                 * @param now elapsed realtime in microseconds.
                 * @param which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
                 * @return
                 */
                public abstract long getStartTime(long now, int which);

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
     * Returns the total, last, or current uptime in micropeconds.
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
        float perc = ((float)num) / ((float)den) * 100;
        mFormatBuilder.setLength(0);
        mFormatter.format("%.1f%%", perc);
        return mFormatBuilder.toString();
    }

    /**
     *
     * @param sb a StringBuilder object.
     * @param timer a Timer object contining the wakelock times.
     * @param now the current time in microseconds.
     * @param name the name of the wakelock.
     * @param which which one of STATS_TOTAL, STATS_LAST, or STATS_CURRENT.
     * @param linePrefix a String to be prepended to each line of output.
     * @return the line prefix
     */
    private final String printWakeLock(StringBuilder sb, Timer timer, long now,
        String name, int which, String linePrefix) {
        if (timer != null) {
            // Convert from microseconds to milliseconds with rounding
            long totalTimeMillis = (timer.getTotalTime(now, which) + 500) / 1000;
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

    @SuppressWarnings("unused")
    private final void dumpLocked(FileDescriptor fd, PrintWriter pw, String prefix, int which) {
        long uSecTime = SystemClock.elapsedRealtime() * 1000;
        final long uSecNow = getBatteryUptime(uSecTime);

        StringBuilder sb = new StringBuilder(128);
        if (which == STATS_TOTAL) {
            pw.println(prefix + "Current and Historic Battery Usage Statistics:");
            pw.println(prefix + "  System starts: " + getStartCount());
        } else if (which == STATS_LAST) {
            pw.println(prefix + "Last Battery Usage Statistics:");
        } else {
            pw.println(prefix + "Current Battery Usage Statistics:");
        }
        long batteryUptime = computeBatteryUptime(uSecNow, which);
        long batteryRealtime = computeBatteryRealtime(getBatteryRealtime(uSecTime), which);
        long elapsedRealtime = computeRealtime(uSecTime, which);
        pw.println(prefix
                + "  On battery: " + formatTimeMs(batteryUptime) + "("
                + formatRatioLocked(batteryUptime, batteryRealtime)
                + ") uptime, "
                + formatTimeMs(batteryRealtime) + "("
                + formatRatioLocked(batteryRealtime, elapsedRealtime)
                + ") realtime");
        pw.println(prefix
                + "  Total: "
                + formatTimeMs(computeUptime(SystemClock.uptimeMillis() * 1000, which))
                + "uptime, "
                + formatTimeMs(elapsedRealtime)
                + "realtime");

        pw.println(" ");

        SparseArray<? extends Uid> uidStats = getUidStats();
        final int NU = uidStats.size();
        for (int iu=0; iu<NU; iu++) {
            final int uid = uidStats.keyAt(iu);
            Uid u = uidStats.valueAt(iu);
            pw.println(prefix + "  #" + uid + ":");
            boolean uidActivity = false;

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
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_FULL), uSecNow,
                            "full", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_PARTIAL), uSecNow,
                            "partial", which, linePrefix);
                    linePrefix = printWakeLock(sb, wl.getWakeTime(WAKE_TYPE_WINDOW), uSecNow,
                            "window", which, linePrefix);
                    if (linePrefix.equals(": ")) {
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
                    sb.append(sensorNumber);

                    Timer timer = se.getSensorTime();
                    if (timer != null) {
                        // Convert from microseconds to milliseconds with rounding
                        long totalTime = (timer.getTotalTime(uSecNow, which) + 500) / 1000;
                        int count = timer.getCount(which);
                        if (totalTime != 0) {
                            sb.append(": ");
                            sb.append(formatTimeMs(totalTime));
                            sb.append(' ');
                            sb.append('(');
                            sb.append(count);
                            sb.append(" times)");
                        }
                    } else {
                        sb.append(": (none used)");
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
                            long startTime = ss.getStartTime(uSecNow, which);
                            int starts = ss.getStarts(which);
                            int launches = ss.getLaunches(which);
                            if (startTime != 0 || starts != 0 || launches != 0) {
                                pw.println(prefix + "      Service " + sent.getKey() + ":");
                                pw.println(prefix + "        Time spent started: "
                                        + formatTimeMs(startTime));
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
     * @param fd a FileDescriptor, currently unused.
     * @param pw a PrintWriter to receive the dump output.
     * @param args an array of Strings, currently unused.
     */
    @SuppressWarnings("unused")
    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            dumpLocked(fd, pw, "", STATS_TOTAL);
            pw.println("");
            dumpLocked(fd, pw, "", STATS_LAST);
            pw.println("");
            dumpLocked(fd, pw, "", STATS_CURRENT);
        }
    }
}
