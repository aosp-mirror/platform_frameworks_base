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
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * This class keeps track of battery drain rate.
 *
 * IMPORTANT: This class shares the power manager lock, which is very low in the lock hierarchy.
 * Do not call out with the lock held. (Settings provider is okay.)
 *
 * TODO: The use of the terms "percent" and "level" in this class is not standard. Fix it.
 *
 * Test:
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/BatterySavingStatsTest.java
 */
public class BatterySavingStats {

    private static final String TAG = "BatterySavingStats";

    private static final boolean DEBUG = BatterySaverPolicy.DEBUG;

    private final Object mLock;

    /** Whether battery saver is on or off. */
    interface BatterySaverState {
        int OFF = 0;
        int ON = 1;
        int ADAPTIVE = 2;

        int SHIFT = 0;
        int BITS = 2;
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

    /** Whether the device is plugged in or not. */
    interface PlugState {
        int UNPLUGGED = 0;
        int PLUGGED = 1;

        int SHIFT = DozeState.SHIFT + DozeState.BITS;
        int BITS = 1;
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

    private BatteryManagerInternal mBatteryManagerInternal;

    private static final int STATE_NOT_INITIALIZED = -1;

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
    final SparseArray<Stat> mStats = new SparseArray<>();

    @GuardedBy("mLock")
    private int mBatterySaverEnabledCount = 0;

    @GuardedBy("mLock")
    private long mLastBatterySaverEnabledTime = 0;

    @GuardedBy("mLock")
    private long mLastBatterySaverDisabledTime = 0;

    @GuardedBy("mLock")
    private int mAdaptiveBatterySaverEnabledCount = 0;

    @GuardedBy("mLock")
    private long mLastAdaptiveBatterySaverEnabledTime = 0;

    @GuardedBy("mLock")
    private long mLastAdaptiveBatterySaverDisabledTime = 0;

    /** Visible for unit tests */
    @VisibleForTesting
    public BatterySavingStats(Object lock) {
        mLock = lock;
        mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
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
            int batterySaverState, int interactiveState, int dozeState, int plugState) {
        int ret = batterySaverState & BatterySaverState.MASK;
        ret |= (interactiveState & InteractiveState.MASK) << InteractiveState.SHIFT;
        ret |= (dozeState & DozeState.MASK) << DozeState.SHIFT;
        ret |= (plugState & PlugState.MASK) << PlugState.SHIFT;
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
        }
        return "BS=" + BatterySaverState.fromIndex(state)
                + ",I=" + InteractiveState.fromIndex(state)
                + ",D=" + DozeState.fromIndex(state)
                + ",P=" + PlugState.fromIndex(state);
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
    private Stat getStat(int batterySaverState, int interactiveState, int dozeState,
            int plugState) {
        return getStat(statesToIndex(batterySaverState, interactiveState, dozeState, plugState));
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
     * Called from the outside whenever any of the states change.
     */
    void transitionState(int batterySaverState, int interactiveState, int dozeState,
            int plugState) {
        synchronized (mLock) {
            final int newState = statesToIndex(
                    batterySaverState, interactiveState, dozeState, plugState);
            transitionStateLocked(newState);
        }
    }

    @GuardedBy("mLock")
    private void transitionStateLocked(int newState) {
        if (mCurrentState == newState) {
            return;
        }
        final long now = injectCurrentTime();
        final int batteryLevel = injectBatteryLevel();
        final int batteryPercent = injectBatteryPercent();

        final int oldBatterySaverState = mCurrentState < 0
                ? BatterySaverState.OFF : BatterySaverState.fromIndex(mCurrentState);
        final int newBatterySaverState = newState < 0
                ? BatterySaverState.OFF : BatterySaverState.fromIndex(newState);
        if (oldBatterySaverState != newBatterySaverState) {
            switch (newBatterySaverState) {
                case BatterySaverState.ON:
                    mBatterySaverEnabledCount++;
                    mLastBatterySaverEnabledTime = now;
                    if (oldBatterySaverState == BatterySaverState.ADAPTIVE) {
                        mLastAdaptiveBatterySaverDisabledTime = now;
                    }
                    break;
                case BatterySaverState.OFF:
                    if (oldBatterySaverState == BatterySaverState.ON) {
                        mLastBatterySaverDisabledTime = now;
                    } else {
                        mLastAdaptiveBatterySaverDisabledTime = now;
                    }
                    break;
                case BatterySaverState.ADAPTIVE:
                    mAdaptiveBatterySaverEnabledCount++;
                    mLastAdaptiveBatterySaverEnabledTime = now;
                    if (oldBatterySaverState == BatterySaverState.ON) {
                        mLastBatterySaverDisabledTime = now;
                    }
                    break;
            }
        }

        endLastStateLocked(now, batteryLevel, batteryPercent);
        startNewStateLocked(newState, now, batteryLevel, batteryPercent);
    }

    @GuardedBy("mLock")
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

    @GuardedBy("mLock")
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

    public void dump(IndentingPrintWriter pw) {
        pw.println("Battery saving stats:");
        pw.increaseIndent();

        synchronized (mLock) {
            final long now = System.currentTimeMillis();
            final long nowElapsed = injectCurrentTime();
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

            pw.print("Battery Saver is currently: ");
            switch (BatterySaverState.fromIndex(mCurrentState)) {
                case BatterySaverState.OFF:
                    pw.println("OFF");
                    break;
                case BatterySaverState.ON:
                    pw.println("ON");
                    break;
                case BatterySaverState.ADAPTIVE:
                    pw.println("ADAPTIVE");
                    break;
            }

            pw.increaseIndent();
            if (mLastBatterySaverEnabledTime > 0) {
                pw.print("Last ON time: ");
                pw.print(sdf.format(new Date(now - nowElapsed + mLastBatterySaverEnabledTime)));
                pw.print(" ");
                TimeUtils.formatDuration(mLastBatterySaverEnabledTime, nowElapsed, pw);
                pw.println();
            }

            if (mLastBatterySaverDisabledTime > 0) {
                pw.print("Last OFF time: ");
                pw.print(sdf.format(new Date(now - nowElapsed + mLastBatterySaverDisabledTime)));
                pw.print(" ");
                TimeUtils.formatDuration(mLastBatterySaverDisabledTime, nowElapsed, pw);
                pw.println();
            }

            pw.print("Times full enabled: ");
            pw.println(mBatterySaverEnabledCount);

            if (mLastAdaptiveBatterySaverEnabledTime > 0) {
                pw.print("Last ADAPTIVE ON time: ");
                pw.print(sdf.format(
                        new Date(now - nowElapsed + mLastAdaptiveBatterySaverEnabledTime)));
                pw.print(" ");
                TimeUtils.formatDuration(mLastAdaptiveBatterySaverEnabledTime, nowElapsed, pw);
                pw.println();
            }
            if (mLastAdaptiveBatterySaverDisabledTime > 0) {
                pw.print("Last ADAPTIVE OFF time: ");
                pw.print(sdf.format(
                        new Date(now - nowElapsed + mLastAdaptiveBatterySaverDisabledTime)));
                pw.print(" ");
                TimeUtils.formatDuration(mLastAdaptiveBatterySaverDisabledTime, nowElapsed, pw);
                pw.println();
            }
            pw.print("Times adaptive enabled: ");
            pw.println(mAdaptiveBatterySaverEnabledCount);

            pw.decreaseIndent();
            pw.println();

            pw.println("Drain stats:");

            pw.println("                   Battery saver OFF                          ON");
            dumpLineLocked(pw, InteractiveState.NON_INTERACTIVE, "NonIntr",
                    DozeState.NOT_DOZING, "NonDoze");
            dumpLineLocked(pw, InteractiveState.INTERACTIVE, "   Intr",
                    DozeState.NOT_DOZING, "       ");

            dumpLineLocked(pw, InteractiveState.NON_INTERACTIVE, "NonIntr",
                    DozeState.DEEP, "Deep   ");
            dumpLineLocked(pw, InteractiveState.INTERACTIVE, "   Intr",
                    DozeState.DEEP, "       ");

            dumpLineLocked(pw, InteractiveState.NON_INTERACTIVE, "NonIntr",
                    DozeState.LIGHT, "Light  ");
            dumpLineLocked(pw, InteractiveState.INTERACTIVE, "   Intr",
                    DozeState.LIGHT, "       ");
        }
        pw.decreaseIndent();
    }

    private void dumpLineLocked(IndentingPrintWriter pw,
            int interactiveState, String interactiveLabel,
            int dozeState, String dozeLabel) {
        pw.print(dozeLabel);
        pw.print(" ");
        pw.print(interactiveLabel);
        pw.print(": ");

        final Stat offStat = getStat(BatterySaverState.OFF, interactiveState, dozeState,
                PlugState.UNPLUGGED);
        final Stat onStat = getStat(BatterySaverState.ON, interactiveState, dozeState,
                PlugState.UNPLUGGED);

        pw.println(String.format("%6dm %6dmAh(%3d%%) %8.1fmAh/h     %6dm %6dmAh(%3d%%) %8.1fmAh/h",
                offStat.totalMinutes(),
                offStat.totalBatteryDrain / 1000,
                offStat.totalBatteryDrainPercent,
                offStat.drainPerHour() / 1000.0,
                onStat.totalMinutes(),
                onStat.totalBatteryDrain / 1000,
                onStat.totalBatteryDrainPercent,
                onStat.drainPerHour() / 1000.0));
    }
}
