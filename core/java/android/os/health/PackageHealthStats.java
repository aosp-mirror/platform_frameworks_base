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
 * {@link UidHealthStats#STATS_PACKAGES UidHealthStats.STATS_PACKAGES} key.
 */
public final class PackageHealthStats {

    private PackageHealthStats() {
    }

    /**
     * Key for a HealthStats with {@link ServiceHealthStats} keys for each of the
     * services defined in this apk.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_STATS)
    public static final int STATS_SERVICES = HealthKeys.BASE_PACKAGE + 1;

    /**
     * Key for a map of the number of times that a package's wakeup alarms have fired
     * while the device was on battery.
     *
     * @see android.app.AlarmManager
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENTS)
    public static final int MEASUREMENTS_WAKEUP_ALARMS_COUNT = HealthKeys.BASE_PACKAGE + 2;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS
            = new HealthKeys.Constants(PackageHealthStats.class);
}
