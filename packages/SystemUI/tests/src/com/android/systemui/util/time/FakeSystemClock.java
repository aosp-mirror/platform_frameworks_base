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

import java.util.ArrayList;
import java.util.List;

public class FakeSystemClock implements SystemClock {
    private long mUptimeMillis;
    private long mElapsedRealtime;
    private long mElapsedRealtimeNanos;
    private long mCurrentThreadTimeMillis;
    private long mCurrentThreadTimeMicro;
    private long mCurrentTimeMicro;

    List<ClockTickListener> mListeners = new ArrayList<>();

    @Override
    public long uptimeMillis() {
        long value = mUptimeMillis;
        return value;
    }

    @Override
    public long elapsedRealtime() {
        long value = mElapsedRealtime;
        return value;
    }

    @Override
    public long elapsedRealtimeNanos() {
        long value = mElapsedRealtimeNanos;
        return value;
    }

    @Override
    public long currentThreadTimeMillis() {
        long value = mCurrentThreadTimeMillis;
        return value;
    }

    @Override
    public long currentThreadTimeMicro() {
        long value = mCurrentThreadTimeMicro;
        return value;
    }

    @Override
    public long currentTimeMicro() {
        long value = mCurrentTimeMicro;
        return value;
    }

    public void setUptimeMillis(long uptimeMillis) {
        mUptimeMillis = uptimeMillis;
        for (ClockTickListener listener : mListeners) {
            listener.onUptimeMillis(mUptimeMillis);
        }
    }

    public void setElapsedRealtime(long elapsedRealtime) {
        mElapsedRealtime = elapsedRealtime;
        for (ClockTickListener listener : mListeners) {
            listener.onElapsedRealtime(mElapsedRealtime);
        }
    }

    public void setElapsedRealtimeNanos(long elapsedRealtimeNanos) {
        mElapsedRealtimeNanos = elapsedRealtimeNanos;
        for (ClockTickListener listener : mListeners) {
            listener.onElapsedRealtimeNanos(mElapsedRealtimeNanos);
        }
    }

    public void setCurrentThreadTimeMillis(long currentThreadTimeMillis) {
        mCurrentThreadTimeMillis = currentThreadTimeMillis;
        for (ClockTickListener listener : mListeners) {
            listener.onCurrentThreadTimeMillis(mCurrentThreadTimeMillis);
        }
    }

    public void setCurrentThreadTimeMicro(long currentThreadTimeMicro) {
        mCurrentThreadTimeMicro = currentThreadTimeMicro;
        for (ClockTickListener listener : mListeners) {
            listener.onCurrentThreadTimeMicro(mCurrentThreadTimeMicro);
        }
    }

    public void setCurrentTimeMicro(long currentTimeMicro) {
        mCurrentTimeMicro = currentTimeMicro;
        for (ClockTickListener listener : mListeners) {
            listener.onCurrentTimeMicro(mCurrentTimeMicro);
        }
    }

    public void addListener(ClockTickListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(ClockTickListener listener) {
        mListeners.remove(listener);
    }

    /** Alert all the listeners about the current time. */
    public void synchronizeListeners() {
        for (ClockTickListener listener : mListeners) {
            listener.onUptimeMillis(mUptimeMillis);
            listener.onElapsedRealtime(mElapsedRealtime);
            listener.onElapsedRealtimeNanos(mElapsedRealtimeNanos);
            listener.onCurrentThreadTimeMillis(mCurrentThreadTimeMillis);
            listener.onCurrentThreadTimeMicro(mCurrentThreadTimeMicro);
            listener.onCurrentTimeMicro(mCurrentTimeMicro);
        }
    }


    public interface ClockTickListener {
        default void onUptimeMillis(long uptimeMillis) {}

        default void onElapsedRealtime(long elapsedRealtime) {}

        default void onElapsedRealtimeNanos(long elapsedRealtimeNanos) {}

        default void onCurrentThreadTimeMillis(long currentThreadTimeMillis) {}

        default void onCurrentThreadTimeMicro(long currentThreadTimeMicro) {}

        default void onCurrentTimeMicro(long currentTimeMicro) {}
    }
}
