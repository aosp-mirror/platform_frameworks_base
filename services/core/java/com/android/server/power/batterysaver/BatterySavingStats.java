/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import android.os.BatteryManagerInternal;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.power.BatterySaverPolicy;

import java.io.PrintWriter;

/**
 * This class keeps track of battery drain rate.
 *
 * TODO: The use of the terms "percent" and "level" in this class is not standard. Fix it.
 *
 * Test:
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/BatterySavingStatsTest.java
 */
public class BatterySavingStats {

    private static final String TAG = "BatterySavingStats";

    private static final boolean DEBUG = BatterySaverPolicy.DEBUG;

    private final Object mLock = new Object();

    /** Whether battery saver is on or off. */
    interface BatterySaverState {
        int OFF = 0;
        int ON = 1;

        int SHIFT = 0;
        int BITS = 1;
        int MASK = (1 << BITS) - 1;

        static int fromIndex(int index) {
            return (index >> SHIFT) & MASK;
        }
    }

    /** Whether the device is interactive (i.e. screen on) or not. */
    interface InteractiveState {
        int NON_INTERACTIVE = 0;
        int INTERACTIVE = 1;

        int SHIFT = BatterySaverState.SHIFT + BatterySaverState.BITS;
        int BITS = 1;
        int MASK = (1 << BITS) - 1;

        static int fromIndex(int index) {
            return (index >> SHIFT) & MASK;
        }
    }

    /** Doze mode. */
    interface DozeState {
        int NOT_DOZING = 0;
        int LIGHT = 1;
        int DEEP = 2;

        int SHIFT = InteractiveState.SHIFT + InteractiveState.BITS;
        int BITS = 2;
        int MASK = (1 << BITS) - 1;

        static int fromIndex(int index) {
            return (index >> SHIFT) & MASK;
        }
    }

    /**
     * Various stats in each state.
     */
    static class Stat {
        public long startTime;
        public long endTime;

        public int startBatteryLevel;
        public int endBatteryLevel;

        public int startBatteryPercent;
        public int endBatteryPercent;

        public long totalTimeMillis;
        public int totalBatteryDrain;
        public int totalBatteryDrainPercent;

        public long totalMinutes() {
            return totalTimeMillis / 60_000;
        }

        public double drainPerHour() {
            if (totalTimeMillis == 0) {
                return 0;
            }
            return (double) totalBatteryDrain / (totalTimeMillis / (60.0 * 60 * 1000));
        }

        public double drainPercentPerHour() {
            if (totalTimeMillis == 0) {
                return 0;
            }
            return (double) totalBatteryDrainPercent / (totalTimeMillis / (60.0 * 60 * 1000));
        }

        @VisibleForTesting
        String toStringForTest() {
            return "{" + totalMinutes() + "m," + totalBatteryDrain + ","
                    + String.format("%.2f", drainPerHour()) + "uA/H,"
                    + String.format("%.2f", drainPercentPerHour()) + "%"
                    + "}";
        }
    }

    @VisibleForTesting
    static final String COUNTER_POWER_PERCENT_PREFIX = "battery_saver_stats_percent_";

    @VisibleForTesting
    static final String COUNTER_POWER_MILLIAMPS_PREFIX = "battery_saver_stats_milliamps_";

    @VisibleForTesting
    static final String COUNTER_TIME_SECONDS_PREFIX = "battery_saver_stats_seconds_";

    private static BatterySavingStats sInstance;

    private BatteryManagerInternal mBatteryManagerInternal;
    private final MetricsLogger mMetricsLogger;

    private static final int STATE_NOT_INITIALIZED = -1;
    private static final int STATE_CHARGING = -2;

    /**
     * Current state, one of STATE_* or values returned by {@link #statesToIndex}.
     */
    @GuardedBy("mLock")
    private int mCurrentState = STATE_NOT_INITIALIZED;

    /**
     * Stats in each state.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    final ArrayMap<Integer, Stat> mStats = new ArrayMap<>();

    private final MetricsLoggerHelper mMetricsLoggerHelper = new MetricsLoggerHelper();

    /**
     * Don't call it directly -- use {@link #getInstance()}. Not private for testing.
     * @param metricsLogger
     */
    @VisibleForTesting
    BatterySavingStats(MetricsLogger metricsLogger) {
        mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
        mMetricsLogger = metricsLogger;
    }

    public static synchronized BatterySavingStats getInstance() {
        if (sInstance == null) {
            sInstance = new BatterySavingStats(new MetricsLogger());
        }
        return sInstance;
    }

    private BatteryManagerInternal getBatteryManagerInternal() {
        if (mBatteryManagerInternal == null) {
            mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
            if (mBatteryManagerInternal == null) {
                Slog.wtf(TAG, "BatteryManagerInternal not initialized");
            }
        }
        return mBatteryManagerInternal;
    }

    /**
     * Takes a state triplet and generates a state index.
     */
    @VisibleForTesting
    static int statesToIndex(
            int batterySaverState, int interactiveState, int dozeState) {
        int ret = batterySaverState & BatterySaverState.MASK;
        ret |= (interactiveState & InteractiveState.MASK) << InteractiveState.SHIFT;
        ret |= (dozeState & DozeState.MASK) << DozeState.SHIFT;
        return ret;
    }

    /**
     * Takes a state index and returns a string for logging.
     */
    @VisibleForTesting
    static String stateToString(int state) {
        switch (state) {
            case STATE_NOT_INITIALIZED:
                return "NotInitialized";
            case STATE_CHARGING:
                return "Charging";
        }
        return "BS=" + BatterySaverState.fromIndex(state)
                + ",I=" + InteractiveState.fromIndex(state)
                + ",D=" + DozeState.fromIndex(state);
    }

    /**
     * @return {@link Stat} fo a given state.
     */
    @VisibleForTesting
    Stat getStat(int stateIndex) {
        synchronized (mLock) {
            Stat stat = mStats.get(stateIndex);
            if (stat == null) {
                stat = new Stat();
                mStats.put(stateIndex, stat);
            }
            return stat;
        }
    }

    /**
     * @return {@link Stat} fo a given state triplet.
     */
    private Stat getStat(int batterySaverState, int interactiveState, int dozeState) {
        return getStat(statesToIndex(batterySaverState, interactiveState, dozeState));
    }

    @VisibleForTesting
    long injectCurrentTime() {
        return SystemClock.elapsedRealtime();
    }

    @VisibleForTesting
    int injectBatteryLevel() {
        final BatteryManagerInternal bmi = getBatteryManagerInternal();
        if (bmi == null) {
            return 0;
        }
        return bmi.getBatteryChargeCounter();
    }

    @VisibleForTesting
    int injectBatteryPercent() {
        final BatteryManagerInternal bmi = getBatteryManagerInternal();
        if (bmi == null) {
            return 0;
        }
        return bmi.getBatteryLevel();
    }

    /**
     * Called from the outside whenever any of the states changes, when the device is not plugged
     * in.
     */
    public void transitionState(int batterySaverState, int interactiveState, int dozeState) {
        synchronized (mLock) {
            final int newState = statesToIndex(
                    batterySaverState, interactiveState, dozeState);
            transitionStateLocked(newState);
        }
    }

    /**
     * Called from the outside when the device is plugged in.
     */
    public void startCharging() {
        synchronized (mLock) {
            transitionStateLocked(STATE_CHARGING);
        }
    }

    private void transitionStateLocked(int newState) {
        if (mCurrentState == newState) {
            return;
        }
        final long now = injectCurrentTime();
        final int batteryLevel = injectBatteryLevel();
        final int batteryPercent = injectBatteryPercent();

        endLastStateLocked(now, batteryLevel, batteryPercent);
        startNewStateLocked(newState, now, batteryLevel, batteryPercent);
        mMetricsLoggerHelper.transitionState(newState, now, batteryLevel, batteryPercent);
    }

    private void endLastStateLocked(long now, int batteryLevel, int batteryPercent) {
        if (mCurrentState < 0) {
            return;
        }
        final Stat stat = getStat(mCurrentState);

        stat.endBatteryLevel = batteryLevel;
        stat.endBatteryPercent = batteryPercent;
        stat.endTime = now;

        final long deltaTime = stat.endTime - stat.startTime;
        final int deltaDrain = stat.startBatteryLevel - stat.endBatteryLevel;
        final int deltaPercent = stat.startBatteryPercent - stat.endBatteryPercent;

        stat.totalTimeMillis += deltaTime;
        stat.totalBatteryDrain += deltaDrain;
        stat.totalBatteryDrainPercent += deltaPercent;

        if (DEBUG) {
            Slog.d(TAG, "State summary: " + stateToString(mCurrentState)
                    + ": " + (deltaTime / 1_000) + "s "
                    + "Start level: " + stat.startBatteryLevel + "uA "
                    + "End level: " + stat.endBatteryLevel + "uA "
                    + "Start percent: " + stat.startBatteryPercent + "% "
                    + "End percent: " + stat.endBatteryPercent + "% "
                    + "Drain " + deltaDrain + "uA");
        }
        EventLogTags.writeBatterySavingStats(
                BatterySaverState.fromIndex(mCurrentState),
                InteractiveState.fromIndex(mCurrentState),
                DozeState.fromIndex(mCurrentState),
                deltaTime,
                deltaDrain,
                deltaPercent,
                stat.totalTimeMillis,
                stat.totalBatteryDrain,
                stat.totalBatteryDrainPercent);

    }

    private void startNewStateLocked(int newState, long now, int batteryLevel, int batteryPercent) {
        if (DEBUG) {
            Slog.d(TAG, "New state: " + stateToString(newState));
        }
        mCurrentState = newState;

        if (mCurrentState < 0) {
            return;
        }

        final Stat stat = getStat(mCurrentState);
        stat.startBatteryLevel = batteryLevel;
        stat.startBatteryPercent = batteryPercent;
        stat.startTime = now;
        stat.endTime = 0;
    }

    public void dump(PrintWriter pw, String indent) {
        synchronized (mLock) {
            pw.print(indent);
            pw.println("Battery Saving Stats:");

            indent = indent + "  ";

            pw.print(indent);
            pw.println("Battery Saver:       Off                                        On");
            dumpLineLocked(pw, indent, InteractiveState.NON_INTERACTIVE, "NonIntr",
                    DozeState.NOT_DOZING, "NonDoze");
            dumpLineLocked(pw, indent, InteractiveState.INTERACTIVE, "   Intr",
                    DozeState.NOT_DOZING, "       ");

            dumpLineLocked(pw, indent, InteractiveState.NON_INTERACTIVE, "NonIntr",
                    DozeState.DEEP, "Deep   ");
            dumpLineLocked(pw, indent, InteractiveState.INTERACTIVE, "   Intr",
                    DozeState.DEEP, "       ");

            dumpLineLocked(pw, indent, InteractiveState.NON_INTERACTIVE, "NonIntr",
                    DozeState.LIGHT, "Light  ");
            dumpLineLocked(pw, indent, InteractiveState.INTERACTIVE, "   Intr",
                    DozeState.LIGHT, "       ");

            pw.println();
        }
    }

    private void dumpLineLocked(PrintWriter pw, String indent,
            int interactiveState, String interactiveLabel,
            int dozeState, String dozeLabel) {
        pw.print(indent);
        pw.print(dozeLabel);
        pw.print(" ");
        pw.print(interactiveLabel);
        pw.print(": ");

        final Stat offStat = getStat(BatterySaverState.OFF, interactiveState, dozeState);
        final Stat onStat = getStat(BatterySaverState.ON, interactiveState, dozeState);

        pw.println(String.format("%6dm %6dmA (%3d%%) %8.1fmA/h      %6dm %6dmA (%3d%%) %8.1fmA/h",
                offStat.totalMinutes(),
                offStat.totalBatteryDrain / 1000,
                offStat.totalBatteryDrainPercent,
                offStat.drainPerHour() / 1000.0,
                onStat.totalMinutes(),
                onStat.totalBatteryDrain / 1000,
                onStat.totalBatteryDrainPercent,
                onStat.drainPerHour() / 1000.0));
    }

    @VisibleForTesting
    class MetricsLoggerHelper {
        private int mLastState = STATE_NOT_INITIALIZED;
        private long mStartTime;
        private int mStartBatteryLevel;
        private int mStartPercent;

        private static final int STATE_CHANGE_DETECT_MASK =
                (BatterySaverState.MASK << BatterySaverState.SHIFT) |
                (InteractiveState.MASK << InteractiveState.SHIFT);

        public void transitionState(int newState, long now, int batteryLevel, int batteryPercent) {
            final boolean stateChanging =
                    ((mLastState >= 0) ^ (newState >= 0)) ||
                    (((mLastState ^ newState) & STATE_CHANGE_DETECT_MASK) != 0);
            if (stateChanging) {
                if (mLastState >= 0) {
                    final long deltaTime = now - mStartTime;
                    final int deltaBattery = mStartBatteryLevel - batteryLevel;
                    final int deltaPercent = mStartPercent - batteryPercent;

                    report(mLastState, deltaTime, deltaBattery, deltaPercent);
                }
                mStartTime = now;
                mStartBatteryLevel = batteryLevel;
                mStartPercent = batteryPercent;
            }
            mLastState = newState;
        }

        String getCounterSuffix(int state) {
            final boolean batterySaver =
                    BatterySaverState.fromIndex(state) != BatterySaverState.OFF;
            final boolean interactive =
                    InteractiveState.fromIndex(state) != InteractiveState.NON_INTERACTIVE;
            if (batterySaver) {
                return interactive ? "11" : "10";
            } else {
                return interactive ? "01" : "00";
            }
        }

        void report(int state, long deltaTimeMs, int deltaBatteryUa, int deltaPercent) {
            final String suffix = getCounterSuffix(state);
            mMetricsLogger.count(COUNTER_POWER_MILLIAMPS_PREFIX + suffix, deltaBatteryUa / 1000);
            mMetricsLogger.count(COUNTER_POWER_PERCENT_PREFIX + suffix, deltaPercent);
            mMetricsLogger.count(COUNTER_TIME_SECONDS_PREFIX + suffix, (int) (deltaTimeMs / 1000));
        }
    }
}
