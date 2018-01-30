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
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.power.BatterySaverPolicy;

import java.io.PrintWriter;

/**
 * This class keeps track of battery drain rate.
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

        public long totalTimeMillis;
        public int totalBatteryDrain;

        public long totalMinutes() {
            return totalTimeMillis / 60_000;
        }

        public double drainPerHour() {
            if (totalTimeMillis == 0) {
                return 0;
            }
            return (double) totalBatteryDrain / (totalTimeMillis / (60.0 * 60 * 1000));
        }

        @VisibleForTesting
        String toStringForTest() {
            return "{" + totalMinutes() + "m," + totalBatteryDrain + ","
                    + String.format("%.2f", drainPerHour()) + "}";
        }
    }

    private static BatterySavingStats sInstance;

    private BatteryManagerInternal mBatteryManagerInternal;

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

    /**
     * Don't call it directly -- use {@link #getInstance()}. Not private for testing.
     */
    @VisibleForTesting
    BatterySavingStats() {
        mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
    }

    public static synchronized BatterySavingStats getInstance() {
        if (sInstance == null) {
            sInstance = new BatterySavingStats();
        }
        return sInstance;
    }

    private BatteryManagerInternal getBatteryManagerInternal() {
        if (mBatteryManagerInternal == null) {
            mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
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

    long injectCurrentTime() {
        return SystemClock.elapsedRealtime();
    }

    int injectBatteryLevel() {
        final BatteryManagerInternal bmi = getBatteryManagerInternal();
        if (bmi == null) {
            Slog.wtf(TAG, "BatteryManagerInternal not initialized");
            return 0;
        }
        return bmi.getBatteryChargeCounter();
    }

    /**
     * Called from the outside whenever any of the states changes, when the device is not plugged
     * in.
     */
    public void transitionState(int batterySaverState, int interactiveState, int dozeState) {
        synchronized (mLock) {

            final int newState = statesToIndex(
                    batterySaverState, interactiveState, dozeState);
            if (mCurrentState == newState) {
                return;
            }

            endLastStateLocked();
            startNewStateLocked(newState);
        }
    }

    /**
     * Called from the outside when the device is plugged in.
     */
    public void startCharging() {
        synchronized (mLock) {
            if (mCurrentState < 0) {
                return;
            }

            endLastStateLocked();
            startNewStateLocked(STATE_CHARGING);
        }
    }

    private void endLastStateLocked() {
        if (mCurrentState < 0) {
            return;
        }
        final Stat stat = getStat(mCurrentState);

        stat.endBatteryLevel = injectBatteryLevel();
        stat.endTime = injectCurrentTime();

        final long deltaTime = stat.endTime - stat.startTime;
        final int deltaDrain = stat.startBatteryLevel - stat.endBatteryLevel;

        stat.totalTimeMillis += deltaTime;
        stat.totalBatteryDrain += deltaDrain;

        if (DEBUG) {
            Slog.d(TAG, "State summary: " + stateToString(mCurrentState)
                    + ": " + (deltaTime / 1_000) + "s "
                    + "Start level: " + stat.startBatteryLevel + "uA "
                    + "End level: " + stat.endBatteryLevel + "uA "
                    + deltaDrain + "uA");
        }
        EventLogTags.writeBatterySavingStats(
                BatterySaverState.fromIndex(mCurrentState),
                InteractiveState.fromIndex(mCurrentState),
                DozeState.fromIndex(mCurrentState),
                deltaTime,
                deltaDrain,
                stat.totalTimeMillis,
                stat.totalBatteryDrain);
    }

    private void startNewStateLocked(int newState) {
        if (DEBUG) {
            Slog.d(TAG, "New state: " + stateToString(newState));
        }
        mCurrentState = newState;

        if (mCurrentState < 0) {
            return;
        }

        final Stat stat = getStat(mCurrentState);
        stat.startBatteryLevel = injectBatteryLevel();
        stat.startTime = injectCurrentTime();
        stat.endTime = 0;
    }

    public void dump(PrintWriter pw, String indent) {
        synchronized (mLock) {
            pw.print(indent);
            pw.println("Battery Saving Stats:");

            indent = indent + "  ";

            pw.print(indent);
            pw.println("Battery Saver:       Off                                 On");
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

        pw.println(String.format("%6dm %6dmA %8.1fmA/h      %6dm %6dmA %8.1fmA/h",
                offStat.totalMinutes(),
                offStat.totalBatteryDrain / 1000,
                offStat.drainPerHour() / 1000.0,
                onStat.totalMinutes(),
                onStat.totalBatteryDrain / 1000,
                onStat.drainPerHour() / 1000.0));
    }
}

