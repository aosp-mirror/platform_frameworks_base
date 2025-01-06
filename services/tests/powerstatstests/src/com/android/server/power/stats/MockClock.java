/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power.stats;

import com.android.internal.os.Clock;

public class MockClock extends Clock {
    /** ElapsedRealtime in ms */
    public long realtime;
    /** Uptime in ms */
    public long uptime;
    /** Current time in ms */
    public long currentTime;

    @Override
    public long elapsedRealtime() {
        return realtime;
    }

    @Override
    public long uptimeMillis() {
        return uptime;
    }

    @Override
    public long currentTimeMillis() {
        return currentTime;
    }

    /**
     * Advances the clock by the given number of milliseconds.
     */
    public void advance(long milliseconds) {
        realtime += milliseconds;
        uptime += milliseconds;
        currentTime += milliseconds;
    }
}
