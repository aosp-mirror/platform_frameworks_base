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

    /**
     * Key for a TimerStat for the times a system-defined wakelock was acquired
     * to allow the application to draw when it otherwise would not be able to
     * (e.g. on the lock screen or doze screen).
     */
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

    /**
     * Key for a measurement of number of millseconds the wifi controller was
     * idle but turned on on behalf of this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_IDLE_MS = HealthKeys.BASE_UID + 16;

    /**
     * Key for a measurement of number of millseconds the wifi transmitter was
     * receiving data for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RX_MS = HealthKeys.BASE_UID + 17;

    /**
     * Key for a measurement of number of millseconds the wifi transmitter was
     * transmitting data for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_TX_MS = HealthKeys.BASE_UID + 18;

    /**
     * Key for a measurement of the estimated number of mA*ms used by this uid
     * for wifi, that is to say the number of milliseconds of wifi activity
     * times the mA current during that period.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_POWER_MAMS = HealthKeys.BASE_UID + 19;

    /**
     * Key for a measurement of number of millseconds the bluetooth controller was
     * idle but turned on on behalf of this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_IDLE_MS = HealthKeys.BASE_UID + 20;

    /**
     * Key for a measurement of number of millseconds the bluetooth transmitter was
     * receiving data for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_RX_MS = HealthKeys.BASE_UID + 21;

    /**
     * Key for a measurement of number of millseconds the bluetooth transmitter was
     * transmitting data for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_TX_MS = HealthKeys.BASE_UID + 22;

    /**
     * Key for a measurement of the estimated number of mA*ms used by this uid
     * for bluetooth, that is to say the number of milliseconds of activity
     * times the mA current during that period.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_POWER_MAMS = HealthKeys.BASE_UID + 23;

    /**
     * Key for a measurement of number of millseconds the mobile radio controller was
     * idle but turned on on behalf of this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_IDLE_MS = HealthKeys.BASE_UID + 24;

    /**
     * Key for a measurement of number of millseconds the mobile radio transmitter was
     * receiving data for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_RX_MS = HealthKeys.BASE_UID + 25;

    /**
     * Key for a measurement of number of millseconds the mobile radio transmitter was
     * transmitting data for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_TX_MS = HealthKeys.BASE_UID + 26;

    /**
     * Key for a measurement of the estimated number of mA*ms used by this uid
     * for mobile data, that is to say the number of milliseconds of activity
     * times the mA current during that period.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_POWER_MAMS = HealthKeys.BASE_UID + 27;

    /**
     * Key for a measurement of number of millseconds the wifi controller was
     * active on behalf of this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RUNNING_MS = HealthKeys.BASE_UID + 28;

    /**
     * Key for a measurement of number of millseconds that this uid held a full wifi lock.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_FULL_LOCK_MS = HealthKeys.BASE_UID + 29;

    /**
     * Key for a timer for the count and duration of wifi scans done by this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_WIFI_SCAN = HealthKeys.BASE_UID + 30;

    /**
     * Key for a measurement of number of millseconds that this uid was performing
     * multicast wifi traffic.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_MULTICAST_MS = HealthKeys.BASE_UID + 31;

    /**
     * Key for a timer for the count and duration of audio playback done by this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_AUDIO = HealthKeys.BASE_UID + 32;

    /**
     * Key for a timer for the count and duration of video playback done by this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_VIDEO = HealthKeys.BASE_UID + 33;

    /**
     * Key for a timer for the count and duration this uid had the flashlight turned on.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_FLASHLIGHT = HealthKeys.BASE_UID + 34;

    /**
     * Key for a timer for the count and duration this uid had the camera turned on.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_CAMERA = HealthKeys.BASE_UID + 35;

    /**
     * Key for a timer for the count and duration of when an activity from this uid
     * was the foreground activitiy.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_FOREGROUND_ACTIVITY = HealthKeys.BASE_UID + 36;

    /**
     * Key for a timer for the count and duration of when this uid was doing bluetooth scans.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_BLUETOOTH_SCAN = HealthKeys.BASE_UID + 37;

    /**
     * Key for a timer for the count and duration of when this uid was in the "top" process state.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_TOP_MS = HealthKeys.BASE_UID + 38;

    /**
     * Key for a timer for the count and duration of when this uid was in the "foreground service"
     * process state.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_FOREGROUND_SERVICE_MS = HealthKeys.BASE_UID + 39;

    /**
     * Key for a timer for the count and duration of when this uid was in the "top sleeping"
     * process state.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_TOP_SLEEPING_MS = HealthKeys.BASE_UID + 40;

    /**
     * Key for a timer for the count and duration of when this uid was in the "foreground"
     * process state.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_FOREGROUND_MS = HealthKeys.BASE_UID + 41;

    /**
     * Key for a timer for the count and duration of when this uid was in the "background"
     * process state.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_BACKGROUND_MS = HealthKeys.BASE_UID + 42;

    /**
     * Key for a timer for the count and duration of when this uid was in the "cached" process
     * state.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_PROCESS_STATE_CACHED_MS = HealthKeys.BASE_UID + 43;

    /**
     * Key for a timer for the count and duration this uid had the vibrator turned on.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_VIBRATOR = HealthKeys.BASE_UID + 44;

    /**
     * Key for a measurement of number of software-generated user activity events caused
     * by the UID.  Calls to userActivity() reset the user activity countdown timer and
     * keep the screen on for the user's preferred screen-on setting.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_OTHER_USER_ACTIVITY_COUNT = HealthKeys.BASE_UID + 45;

    /**
     * Key for a measurement of number of user activity events due to physical button presses caused
     * by the UID.  Calls to userActivity() reset the user activity countdown timer and
     * keep the screen on for the user's preferred screen-on setting.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BUTTON_USER_ACTIVITY_COUNT = HealthKeys.BASE_UID + 46;

    /**
     * Key for a measurement of number of user activity events due to touch events caused
     * by the UID.  Calls to userActivity() reset the user activity countdown timer and
     * keep the screen on for the user's preferred screen-on setting.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_TOUCH_USER_ACTIVITY_COUNT = HealthKeys.BASE_UID + 47;

    /**
     * Key for a measurement of number of bytes received for this uid by the mobile radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_RX_BYTES = HealthKeys.BASE_UID + 48;

    /**
     * Key for a measurement of number of bytes transmitted for this uid by the mobile radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_TX_BYTES = HealthKeys.BASE_UID + 49;

    /**
     * Key for a measurement of number of bytes received for this uid by the wifi radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RX_BYTES = HealthKeys.BASE_UID + 50;

    /**
     * Key for a measurement of number of bytes transmitted for this uid by the wifi radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_TX_BYTES = HealthKeys.BASE_UID + 51;

    /**
     * Key for a measurement of number of bytes received for this uid by the bluetooth radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_RX_BYTES = HealthKeys.BASE_UID + 52;

    /**
     * Key for a measurement of number of bytes transmitted for this uid by the bluetooth radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_TX_BYTES = HealthKeys.BASE_UID + 53;

    /**
     * Key for a measurement of number of packets received for this uid by the mobile radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_RX_PACKETS = HealthKeys.BASE_UID + 54;

    /**
     * Key for a measurement of number of packets transmitted for this uid by the mobile radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_MOBILE_TX_PACKETS = HealthKeys.BASE_UID + 55;

    /**
     * Key for a measurement of number of packets received for this uid by the wifi radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_RX_PACKETS = HealthKeys.BASE_UID + 56;

    /**
     * Key for a measurement of number of packets transmitted for this uid by the wifi radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_WIFI_TX_PACKETS = HealthKeys.BASE_UID + 57;

    /**
     * Key for a measurement of number of packets received for this uid by the bluetooth radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_RX_PACKETS = HealthKeys.BASE_UID + 58;

    /**
     * Key for a measurement of number of packets transmitted for this uid by the bluetooth radio.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_BLUETOOTH_TX_PACKETS = HealthKeys.BASE_UID + 59;

    /**
     * Key for a timer for the count and duration the mobile radio was turned on for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_TIMER)
    public static final int TIMER_MOBILE_RADIO_ACTIVE = HealthKeys.BASE_UID + 61;

    /**
     * Key for a measurement of the number of milliseconds spent by the CPU running user space
     * code for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_USER_CPU_TIME_MS = HealthKeys.BASE_UID + 62;

    /**
     * Key for a measurement of the number of milliseconds spent by the CPU running kernel
     * code for this uid.
     */
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_SYSTEM_CPU_TIME_MS = HealthKeys.BASE_UID + 63;

    /**
     * An estimate of the number of milliamp-microsends used by this uid.
     *
     * @deprecated this measurement is vendor-dependent and not reliable.
     */
    @Deprecated
    @HealthKeys.Constant(type=HealthKeys.TYPE_MEASUREMENT)
    public static final int MEASUREMENT_CPU_POWER_MAMS = HealthKeys.BASE_UID + 64;

    /**
     * @hide
     */
    public static final HealthKeys.Constants CONSTANTS = new HealthKeys.Constants(UidHealthStats.class);
}

