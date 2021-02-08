/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.notification;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake {@link InjectableSystemClock}
 *
 * Attempts to simulate the behavior of a real system clock. Time can be moved forward but not
 * backwards. uptimeMillis, elapsedRealtime, and currentThreadTimeMillis are all kept in sync.
 *
 * Unless otherwise specified, uptimeMillis and elapsedRealtime will advance the same amount with
 * every call to {@link #advanceTime(long)}. Thread time always lags by 50% of the uptime
 * advancement to simulate time loss due to scheduling.
 *
 * @hide
 */
public class FakeSystemClock implements InjectableSystemClock {
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

    public void advanceTime(long uptime) {
        advanceTime(uptime, 0);
    }

    public void advanceTime(long uptime, long sleepTime) {
        if (uptime < 0 || sleepTime < 0) {
            throw new IllegalArgumentException("Time cannot go backwards.");
        }

        if (uptime > 0 || sleepTime > 0) {
            mUptimeMillis += uptime;
            mElapsedRealtime += uptime + sleepTime;
            mCurrentTimeMillis += uptime + sleepTime;

            mCurrentThreadTimeMillis += Math.ceil(uptime * 0.5);

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

    private static final long START_TIME = 10000;
}

