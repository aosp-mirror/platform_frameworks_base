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
 * {@link PackageHealthStats#STATS_SERVICES PackageHealthStats.STATS_SERVICES} key.
 */
public final class ServiceHealthStats {

    private ServiceHealthStats() {
    }

    /**
     * Key for a measurement of the number of times this service was started due to calls to
     * {@link android.content.Context#startService startService()}, including re-launches
     * after crashes.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_START_SERVICE_COUNT = HealthKeys.BASE_SERVICE + 1;

    /**
     * Key for a measurement of the total number of times this service was started
     * due to calls to {@link android.content.Context#startService startService()}
     * or {@link android.content.Context#bindService bindService()} including re-launches
     * after crashes.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_LAUNCH_COUNT = HealthKeys.BASE_SERVICE + 2;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS
            = new HealthKeys.Constants(ServiceHealthStats.class);
}
