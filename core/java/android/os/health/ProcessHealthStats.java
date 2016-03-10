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

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_USER_TIME_MS = HealthKeys.BASE_PROCESS + 1;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_SYSTEM_TIME_MS = HealthKeys.BASE_PROCESS + 2;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_STARTS_COUNT = HealthKeys.BASE_PROCESS + 3;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_CRASHES_COUNT = HealthKeys.BASE_PROCESS + 4;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_ANR_COUNT = HealthKeys.BASE_PROCESS + 5;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_FOREGROUND_MS = HealthKeys.BASE_PROCESS + 6;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS = new HealthKeys.Constants(ProcessHealthStats.class);
}
