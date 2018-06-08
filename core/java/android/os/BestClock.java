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

package android.os;

import android.util.Log;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * Single {@link Clock} that will return the best available time from a set of
 * prioritized {@link Clock} instances.
 * <p>
 * For example, when {@link SystemClock#currentNetworkTimeClock()} isn't able to
 * provide the time, this class could use {@link Clock#systemUTC()} instead.
 *
 * @hide
 */
public class BestClock extends SimpleClock {
    private static final String TAG = "BestClock";

    private final Clock[] clocks;

    public BestClock(ZoneId zone, Clock... clocks) {
        super(zone);
        this.clocks = clocks;
    }

    @Override
    public long millis() {
        for (Clock clock : clocks) {
            try {
                return clock.millis();
            } catch (DateTimeException e) {
                // Ignore and attempt the next clock
                Log.w(TAG, e.toString());
            }
        }
        throw new DateTimeException(
                "No clocks in " + Arrays.toString(clocks) + " were able to provide time");
    }
}
