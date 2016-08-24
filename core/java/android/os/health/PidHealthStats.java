/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os.health;

/**
 * Keys for {@link HealthStats} returned from
 * {@link HealthStats#getStats(int) HealthStats.getStats(int)} with the
 * {@link UidHealthStats#STATS_PIDS UidHealthStats.STATS_PIDS} key.
 * <p>
 * The values coming from PidHealthStats are a little bit different from
 * the other HealthStats values.  These values are not aggregate or historical
 * values, but instead live values from when the snapshot is taken.  These
 * tend to be more useful in debugging rogue processes than in gathering
 * aggregate metrics across the fleet of devices.
 */
public final class PidHealthStats {

    private PidHealthStats() {
    }

    /**
     * Key for a measurement of the current nesting depth of wakelocks for this process.
     * That is to say, the number of times a nested wakelock has been started but not
     * stopped.  A high number here indicates an improperly paired wakelock acquire/release
     * combination.
     * <p>
     * More details on the individual wake locks is available
     * by getting the {@link UidHealthStats#TIMERS_WAKELOCKS_FULL},
     * {@link UidHealthStats#TIMERS_WAKELOCKS_PARTIAL},
     * {@link UidHealthStats#TIMERS_WAKELOCKS_WINDOW}
     * and {@link UidHealthStats#TIMERS_WAKELOCKS_DRAW} keys.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WAKE_NESTING_COUNT = HealthKeys.BASE_PID + 1;

    /**
     * Key for a measurement of the total number of milleseconds that this process
     * has held a wake lock.
     * <p>
     * More details on the individual wake locks is available
     * by getting the {@link UidHealthStats#TIMERS_WAKELOCKS_FULL},
     * {@link UidHealthStats#TIMERS_WAKELOCKS_PARTIAL},
     * {@link UidHealthStats#TIMERS_WAKELOCKS_WINDOW}
     * and {@link UidHealthStats#TIMERS_WAKELOCKS_DRAW} keys.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WAKE_SUM_MS = HealthKeys.BASE_PID + 2;

    /**
     * Key for a measurement of the time in the {@link android.os.SystemClock#elapsedRealtime}
     * timebase that a wakelock was first acquired in this process.
     * <p>
     * More details on the individual wake locks is available
     * by getting the {@link UidHealthStats#TIMERS_WAKELOCKS_FULL},
     * {@link UidHealthStats#TIMERS_WAKELOCKS_PARTIAL},
     * {@link UidHealthStats#TIMERS_WAKELOCKS_WINDOW}
     * and {@link UidHealthStats#TIMERS_WAKELOCKS_DRAW} keys.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WAKE_START_MS = HealthKeys.BASE_PID + 3;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS = new HealthKeys.Constants(PidHealthStats.class);
}
