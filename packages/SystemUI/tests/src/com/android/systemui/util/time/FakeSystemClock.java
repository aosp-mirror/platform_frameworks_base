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

public class FakeSystemClock implements SystemClock {
    private boolean mAutoIncrement = true;

    private long mUptimeMillis;
    private long mElapsedRealtime;
    private long mElapsedRealtimeNanos;
    private long mCurrentThreadTimeMillis;
    private long mCurrentThreadTimeMicro;
    private long mCurrentTimeMicro;

    @Override
    public long uptimeMillis() {
        long value = mUptimeMillis;
        if (mAutoIncrement) {
            mUptimeMillis++;
        }
        return value;
    }

    @Override
    public long elapsedRealtime() {
        long value = mElapsedRealtime;
        if (mAutoIncrement) {
            mElapsedRealtime++;
        }
        return value;
    }

    @Override
    public long elapsedRealtimeNanos() {
        long value = mElapsedRealtimeNanos;
        if (mAutoIncrement) {
            mElapsedRealtimeNanos++;
        }
        return value;
    }

    @Override
    public long currentThreadTimeMillis() {
        long value = mCurrentThreadTimeMillis;
        if (mAutoIncrement) {
            mCurrentThreadTimeMillis++;
        }
        return value;
    }

    @Override
    public long currentThreadTimeMicro() {
        long value = mCurrentThreadTimeMicro;
        if (mAutoIncrement) {
            mCurrentThreadTimeMicro++;
        }
        return value;
    }

    @Override
    public long currentTimeMicro() {
        long value = mCurrentTimeMicro;
        if (mAutoIncrement) {
            mCurrentTimeMicro++;
        }
        return value;
    }

    public void setUptimeMillis(long uptimeMillis) {
        mUptimeMillis = uptimeMillis;
    }

    public void setElapsedRealtime(long elapsedRealtime) {
        mElapsedRealtime = elapsedRealtime;
    }

    public void setElapsedRealtimeNanos(long elapsedRealtimeNanos) {
        mElapsedRealtimeNanos = elapsedRealtimeNanos;
    }

    public void setCurrentThreadTimeMillis(long currentThreadTimeMillis) {
        mCurrentThreadTimeMillis = currentThreadTimeMillis;
    }

    public void setCurrentThreadTimeMicro(long currentThreadTimeMicro) {
        mCurrentThreadTimeMicro = currentThreadTimeMicro;
    }

    public void setCurrentTimeMicro(long currentTimeMicro) {
        mCurrentTimeMicro = currentTimeMicro;
    }

    /** If true, each call to get____ will be one higher than the previous call to that method. */
    public void setAutoIncrement(boolean autoIncrement) {
        mAutoIncrement = autoIncrement;
    }
}
