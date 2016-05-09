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
 * {@link UidHealthStats#STATS_PROCESSES UidHealthStats.STATS_PROCESSES} key.
 */
public final class ProcessHealthStats {

    private ProcessHealthStats() {
    }

    /**
     * Key for a measurement of number of millseconds the CPU spent running in user space
     * for this process.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_USER_TIME_MS = HealthKeys.BASE_PROCESS + 1;

    /**
     * Key for a measurement of number of millseconds the CPU spent running in kernel space
     * for this process.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_SYSTEM_TIME_MS = HealthKeys.BASE_PROCESS + 2;

    /**
     * Key for a measurement of the number of times this process was started for any reason.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_STARTS_COUNT = HealthKeys.BASE_PROCESS + 3;

    /**
     * Key for a measurement of the number of crashes that happened in this process.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_CRASHES_COUNT = HealthKeys.BASE_PROCESS + 4;

    /**
     * Key for a measurement of the number of ANRs that happened in this process.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_ANR_COUNT = HealthKeys.BASE_PROCESS + 5;

    /**
     * Key for a measurement of the number of milliseconds this process spent with
     * an activity in the foreground.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_FOREGROUND_MS = HealthKeys.BASE_PROCESS + 6;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS = new HealthKeys.Constants(ProcessHealthStats.class);
}
