/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util.time;

import com.android.systemui.util.concurrency.FakeExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake {@link SystemClock} for use with {@link FakeExecutor}.
 *
 * Attempts to simulate the behavior of a real system clock. Time can be moved forward but not
 * backwards. uptimeMillis, elapsedRealtime, and currentThreadTimeMillis are all kept in sync.
 *
 * Unless otherwise specified, uptimeMillis and elapsedRealtime will advance the same amount with
 * every call to {@link #advanceTime}. Thread time always lags by 50% of the uptime
 * advancement to simulate time loss due to scheduling.
 */
public class FakeSystemClock implements SystemClock {
    private long mUptimeMillis = 10000;
    private long mElapsedRealtime = 10000;
    private long mCurrentThreadTimeMillis = 10000;
    private long mCurrentTimeMillis = 1555555500000L;
    private final List<ClockTickListener> mListeners = new ArrayList<>();

    @Override
    public long uptimeMillis() {
        return mUptimeMillis;
    }

    @Override
    public long elapsedRealtime() {
        return mElapsedRealtime;
    }

    @Override
    public long elapsedRealtimeNanos() {
        return mElapsedRealtime * 1000000 + 447;
    }

    @Override
    public long currentThreadTimeMillis() {
        return mCurrentThreadTimeMillis;
    }

    @Override
    public long currentTimeMillis() {
        return mCurrentTimeMillis;
    }

    public void setUptimeMillis(long uptime) {
        advanceTime(uptime - mUptimeMillis);
    }

    public void setCurrentTimeMillis(long millis) {
        mCurrentTimeMillis = millis;
    }

    public void setElapsedRealtime(long millis) {
        mElapsedRealtime = millis;
    }

    /**
     * Advances the time tracked by the fake clock and notifies any listeners that the time has
     * changed (for example, an attached {@link FakeExecutor} may fire its pending runnables).
     *
     * All tracked times increment by [millis], with the exception of currentThreadTimeMillis,
     * which advances by [millis] * 0.5
     */
    public void advanceTime(long millis) {
        advanceTime(millis, 0);
    }

    /**
     * Advances the time tracked by the fake clock and notifies any listeners that the time has
     * changed (for example, an attached {@link FakeExecutor} may fire its pending runnables).
     *
     * The tracked times change as follows:
     * - uptimeMillis increments by [awakeMillis]
     * - currentThreadTimeMillis increments by [awakeMillis] * 0.5
     * - elapsedRealtime increments by [awakeMillis] + [sleepMillis]
     * - currentTimeMillis increments by [awakeMillis] + [sleepMillis]
     */
    public void advanceTime(long awakeMillis, long sleepMillis) {
        if (awakeMillis < 0 || sleepMillis < 0) {
            throw new IllegalArgumentException("Time cannot go backwards");
        }

        if (awakeMillis > 0 || sleepMillis > 0) {
            mUptimeMillis += awakeMillis;
            mElapsedRealtime += awakeMillis + sleepMillis;
            mCurrentTimeMillis += awakeMillis + sleepMillis;

            mCurrentThreadTimeMillis += Math.ceil(awakeMillis * 0.5);

            for (ClockTickListener listener : mListeners) {
                listener.onClockTick();
            }
        }
    }

    public void addListener(ClockTickListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(ClockTickListener listener) {
        mListeners.remove(listener);
    }

    public interface ClockTickListener {
        void onClockTick();
    }
}
