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
 * {@link SystemHealthManager#takeUidSnapshot(int) SystemHealthManager.takeUidSnapshot(int)},
 * {@link SystemHealthManager#takeMyUidSnapshot() SystemHealthManager.takeMyUidSnapshot()}, and
 * {@link SystemHealthManager#takeUidSnapshots(int[]) SystemHealthManager.takeUidSnapshots(int[])}.
 */
public final class UidHealthStats {

    private UidHealthStats() {
    }

    /**
     * How many milliseconds this statistics report covers in wall-clock time while the
     * device was on battery including both screen-on and screen-off time.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_REALTIME_BATTERY_MS = HealthKeys.BASE_UID + 1;

    /**
     * How many milliseconds this statistics report covers that the CPU was running while the
     * device was on battery including both screen-on and screen-off time.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_UPTIME_BATTERY_MS = HealthKeys.BASE_UID + 2;

    /**
     * How many milliseconds this statistics report covers in wall-clock time while the
     * device was on battery including both screen-on and screen-off time.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_REALTIME_SCREEN_OFF_BATTERY_MS = HealthKeys.BASE_UID + 3;

    /**
     * How many milliseconds this statistics report covers that the CPU was running while the
     * device was on battery including both screen-on and screen-off time.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_UPTIME_SCREEN_OFF_BATTERY_MS = HealthKeys.BASE_UID + 4;

    /**
     * Key for a TimerStat for the times a
     * {@link android.os.PowerManager#FULL_WAKE_LOCK full wake lock}
     * was acquired for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_WAKELOCKS_FULL = HealthKeys.BASE_UID + 5;

    /**
     * Key for a TimerStat for the times a
     * {@link android.os.PowerManager#PARTIAL_WAKE_LOCK full wake lock}
     * was acquired for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_WAKELOCKS_PARTIAL = HealthKeys.BASE_UID + 6;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_WAKELOCKS_WINDOW = HealthKeys.BASE_UID + 7;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_WAKELOCKS_DRAW = HealthKeys.BASE_UID + 8;

    /**
     * Key for a map of Timers for the sync adapter syncs that were done for
     * this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_SYNCS = HealthKeys.BASE_UID + 9;

    /**
     * Key for a map of Timers for the {@link android.app.job.JobScheduler} jobs for
     * this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_JOBS = HealthKeys.BASE_UID + 10;

    /**
     * Key for a timer for the applications use of the GPS sensor.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_GPS_SENSOR = HealthKeys.BASE_UID + 11;

    /**
     * Key for a map of the sensor usage for this uid. The keys are a
     * string representation of the handle for the sensor.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMERS)
    public static final int TIMERS_SENSORS = HealthKeys.BASE_UID + 12;

    /**
     * Key for a HealthStats with {@link PidHealthStats} keys for each of the
     * currently running processes for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_STATS)
    public static final int STATS_PIDS = HealthKeys.BASE_UID + 13;

    /**
     * Key for a HealthStats with {@link ProcessHealthStats} keys for each of the
     * named processes for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_STATS)
    public static final int STATS_PROCESSES = HealthKeys.BASE_UID + 14;

    /**
     * Key for a HealthStats with {@link PackageHealthStats} keys for each of the
     * APKs that share this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_STATS)
    public static final int STATS_PACKAGES = HealthKeys.BASE_UID + 15;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_IDLE_MS = HealthKeys.BASE_UID + 16;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RX_MS = HealthKeys.BASE_UID + 17;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_TX_MS = HealthKeys.BASE_UID + 18;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_POWER_MAMS = HealthKeys.BASE_UID + 19;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_IDLE_MS = HealthKeys.BASE_UID + 20;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_RX_MS = HealthKeys.BASE_UID + 21;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_TX_MS = HealthKeys.BASE_UID + 22;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_POWER_MAMS = HealthKeys.BASE_UID + 23;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_IDLE_MS = HealthKeys.BASE_UID + 24;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_RX_MS = HealthKeys.BASE_UID + 25;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_TX_MS = HealthKeys.BASE_UID + 26;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_POWER_MAMS = HealthKeys.BASE_UID + 27;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RUNNING_MS = HealthKeys.BASE_UID + 28;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_FULL_LOCK_MS = HealthKeys.BASE_UID + 29;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_WIFI_SCAN = HealthKeys.BASE_UID + 30;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_MULTICAST_MS = HealthKeys.BASE_UID + 31;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_AUDIO = HealthKeys.BASE_UID + 32;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_VIDEO = HealthKeys.BASE_UID + 33;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_FLASHLIGHT = HealthKeys.BASE_UID + 34;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_CAMERA = HealthKeys.BASE_UID + 35;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_FOREGROUND_ACTIVITY = HealthKeys.BASE_UID + 36;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_BLUETOOTH_SCAN = HealthKeys.BASE_UID + 37;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_TOP_MS = HealthKeys.BASE_UID + 38;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_FOREGROUND_SERVICE_MS = HealthKeys.BASE_UID + 39;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_TOP_SLEEPING_MS = HealthKeys.BASE_UID + 40;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_FOREGROUND_MS = HealthKeys.BASE_UID + 41;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_BACKGROUND_MS = HealthKeys.BASE_UID + 42;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_CACHED_MS = HealthKeys.BASE_UID + 43;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_VIBRATOR = HealthKeys.BASE_UID + 44;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_OTHER_USER_ACTIVITY_COUNT = HealthKeys.BASE_UID + 45;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BUTTON_USER_ACTIVITY_COUNT = HealthKeys.BASE_UID + 46;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_TOUCH_USER_ACTIVITY_COUNT = HealthKeys.BASE_UID + 47;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_RX_BYTES = HealthKeys.BASE_UID + 48;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_TX_BYTES = HealthKeys.BASE_UID + 49;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RX_BYTES = HealthKeys.BASE_UID + 50;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_TX_BYTES = HealthKeys.BASE_UID + 51;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_RX_BYTES = HealthKeys.BASE_UID + 52;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_TX_BYTES = HealthKeys.BASE_UID + 53;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_RX_PACKETS = HealthKeys.BASE_UID + 54;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_TX_PACKETS = HealthKeys.BASE_UID + 55;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RX_PACKETS = HealthKeys.BASE_UID + 56;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_TX_PACKETS = HealthKeys.BASE_UID + 57;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_RX_PACKETS = HealthKeys.BASE_UID + 58;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_TX_PACKETS = HealthKeys.BASE_UID + 59;

    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_MOBILE_RADIO_ACTIVE = HealthKeys.BASE_UID + 61;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_USER_CPU_TIME_MS = HealthKeys.BASE_UID + 62;

    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_SYSTEM_CPU_TIME_MS = HealthKeys.BASE_UID + 63;

    /**
     * An estimate of the number of milliamp-microsends used by this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_CPU_POWER_MAMS = HealthKeys.BASE_UID + 64;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS = new HealthKeys.Constants(UidHealthStats.class);
}

