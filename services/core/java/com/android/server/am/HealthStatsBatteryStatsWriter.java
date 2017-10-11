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

package com.android.server.am;

import android.os.BatteryStats;
import static android.os.BatteryStats.STATS_SINCE_UNPLUGGED;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.health.HealthKeys;
import android.os.health.HealthStatsParceler;
import android.os.health.HealthStatsWriter;
import android.os.health.PackageHealthStats;
import android.os.health.ProcessHealthStats;
import android.os.health.PidHealthStats;
import android.os.health.ServiceHealthStats;
import android.os.health.TimerStat;
import android.os.health.UidHealthStats;
import android.util.SparseArray;

import java.util.Map;

public class HealthStatsBatteryStatsWriter {

    private final long mNowRealtimeMs;
    private final long mNowUptimeMs;

    public HealthStatsBatteryStatsWriter() {
        mNowRealtimeMs = SystemClock.elapsedRealtime();
        mNowUptimeMs = SystemClock.uptimeMillis();
    }

    /**
     * Writes the contents of a BatteryStats.Uid into a HealthStatsWriter.
     */
    @SuppressWarnings("deprecation")
    public void writeUid(HealthStatsWriter uidWriter, BatteryStats bs, BatteryStats.Uid uid) {
        int N;
        BatteryStats.Timer timer;
        SparseArray<? extends BatteryStats.Uid.Sensor> sensors;
        SparseArray<? extends BatteryStats.Uid.Pid> pids;
        BatteryStats.ControllerActivityCounter controller;
        long sum;

        //
        // It's a little odd for these first four to be here but it's not the end of the
        // world. It would be easy enough to duplicate them somewhere else if this API
        // grows.
        //

        // MEASUREMENT_REALTIME_BATTERY_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_REALTIME_BATTERY_MS,
                bs.computeBatteryRealtime(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_UPTIME_BATTERY_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_UPTIME_BATTERY_MS,
                bs.computeBatteryUptime(mNowUptimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_REALTIME_SCREEN_OFF_BATTERY_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_REALTIME_SCREEN_OFF_BATTERY_MS,
                bs.computeBatteryScreenOffRealtime(
                    mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_UPTIME_SCREEN_OFF_BATTERY_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_UPTIME_SCREEN_OFF_BATTERY_MS,
                bs.computeBatteryScreenOffUptime(mNowUptimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        //
        // Now on to the real per-uid stats...
        //

        for (final Map.Entry<String,? extends BatteryStats.Uid.Wakelock> entry:
                uid.getWakelockStats().entrySet()) {
            final String key = entry.getKey();
            final BatteryStats.Uid.Wakelock wakelock = entry.getValue();

            // TIMERS_WAKELOCKS_FULL
            timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_FULL);
            addTimers(uidWriter, UidHealthStats.TIMERS_WAKELOCKS_FULL, key, timer);

            // TIMERS_WAKELOCKS_PARTIAL
            timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
            addTimers(uidWriter, UidHealthStats.TIMERS_WAKELOCKS_PARTIAL, key, timer);
            
            // TIMERS_WAKELOCKS_WINDOW
            timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_WINDOW);
            addTimers(uidWriter, UidHealthStats.TIMERS_WAKELOCKS_WINDOW, key, timer);
            
            // TIMERS_WAKELOCKS_DRAW
            timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_DRAW);
            addTimers(uidWriter, UidHealthStats.TIMERS_WAKELOCKS_DRAW, key, timer);
        }
        
        // TIMERS_SYNCS
        for (final Map.Entry<String,? extends BatteryStats.Timer> entry:
                uid.getSyncStats().entrySet()) {
            addTimers(uidWriter, UidHealthStats.TIMERS_SYNCS, entry.getKey(), entry.getValue());
        }

        // TIMERS_JOBS
        for (final Map.Entry<String,? extends BatteryStats.Timer> entry:
                uid.getJobStats().entrySet()) {
            addTimers(uidWriter, UidHealthStats.TIMERS_JOBS, entry.getKey(), entry.getValue());
        }

        // TIMERS_SENSORS
        sensors = uid.getSensorStats();
        N = sensors.size();
        for (int i=0; i<N; i++) {
            int sensorId = sensors.keyAt(i);
            // Battery Stats stores the GPS sensors with a bogus key in this API. Pull it out
            // as a separate metric here so as to not expose that in the API.
            if (sensorId == BatteryStats.Uid.Sensor.GPS) {
                addTimer(uidWriter, UidHealthStats.TIMER_GPS_SENSOR,
                        sensors.valueAt(i).getSensorTime());
            } else {
                addTimers(uidWriter, UidHealthStats.TIMERS_SENSORS, Integer.toString(sensorId),
                        sensors.valueAt(i).getSensorTime());
            }
        }

        // STATS_PIDS
        pids = uid.getPidStats();
        N = pids.size();
        for (int i=0; i<N; i++) {
            final HealthStatsWriter writer = new HealthStatsWriter(PidHealthStats.CONSTANTS);
            writePid(writer, pids.valueAt(i));
            uidWriter.addStats(UidHealthStats.STATS_PIDS, Integer.toString(pids.keyAt(i)), writer);
        }

        // STATS_PROCESSES
        for (final Map.Entry<String,? extends BatteryStats.Uid.Proc> entry:
                uid.getProcessStats().entrySet()) {
            final HealthStatsWriter writer = new HealthStatsWriter(ProcessHealthStats.CONSTANTS);
            writeProc(writer, entry.getValue());
            uidWriter.addStats(UidHealthStats.STATS_PROCESSES, entry.getKey(), writer);
        }

        // STATS_PACKAGES
        for (final Map.Entry<String,? extends BatteryStats.Uid.Pkg> entry:
                uid.getPackageStats().entrySet()) {
            final HealthStatsWriter writer = new HealthStatsWriter(PackageHealthStats.CONSTANTS);
            writePkg(writer, entry.getValue());
            uidWriter.addStats(UidHealthStats.STATS_PACKAGES, entry.getKey(), writer);
        }

        controller = uid.getWifiControllerActivity();
        if (controller != null) {
            // MEASUREMENT_WIFI_IDLE_MS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_IDLE_MS,
                    controller.getIdleTimeCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
            // MEASUREMENT_WIFI_RX_MS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_RX_MS,
                    controller.getRxTimeCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
            // MEASUREMENT_WIFI_TX_MS
            sum = 0;
            for (final BatteryStats.LongCounter counter: controller.getTxTimeCounters()) {
                sum += counter.getCountLocked(STATS_SINCE_UNPLUGGED);
            }
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_TX_MS, sum);
            // MEASUREMENT_WIFI_POWER_MAMS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS,
                    controller.getPowerCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
        }

        controller = uid.getBluetoothControllerActivity();
        if (controller != null) {
            // MEASUREMENT_BLUETOOTH_IDLE_MS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_IDLE_MS,
                    controller.getIdleTimeCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
            // MEASUREMENT_BLUETOOTH_RX_MS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_RX_MS,
                    controller.getRxTimeCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
            // MEASUREMENT_BLUETOOTH_TX_MS
            sum = 0;
            for (final BatteryStats.LongCounter counter: controller.getTxTimeCounters()) {
                sum += counter.getCountLocked(STATS_SINCE_UNPLUGGED);
            }
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_TX_MS, sum);
            // MEASUREMENT_BLUETOOTH_POWER_MAMS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_POWER_MAMS,
                    controller.getPowerCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
        }

        controller = uid.getModemControllerActivity();
        if (controller != null) {
            // MEASUREMENT_MOBILE_IDLE_MS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_IDLE_MS,
                    controller.getIdleTimeCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
            // MEASUREMENT_MOBILE_RX_MS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_RX_MS,
                    controller.getRxTimeCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
            // MEASUREMENT_MOBILE_TX_MS
            sum = 0;
            for (final BatteryStats.LongCounter counter: controller.getTxTimeCounters()) {
                sum += counter.getCountLocked(STATS_SINCE_UNPLUGGED);
            }
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_TX_MS, sum);
            // MEASUREMENT_MOBILE_POWER_MAMS
            uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS,
                    controller.getPowerCounter().getCountLocked(STATS_SINCE_UNPLUGGED));
        }

        // MEASUREMENT_WIFI_RUNNING_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_RUNNING_MS,
                uid.getWifiRunningTime(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_WIFI_FULL_LOCK_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_FULL_LOCK_MS,
                uid.getFullWifiLockTime(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // TIMER_WIFI_SCAN
        uidWriter.addTimer(UidHealthStats.TIMER_WIFI_SCAN,
                uid.getWifiScanCount(STATS_SINCE_UNPLUGGED),
                uid.getWifiScanTime(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_WIFI_MULTICAST_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_MULTICAST_MS,
                uid.getWifiMulticastTime(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED)/1000);

        // TIMER_AUDIO
        addTimer(uidWriter, UidHealthStats.TIMER_AUDIO, uid.getAudioTurnedOnTimer());

        // TIMER_VIDEO
        addTimer(uidWriter, UidHealthStats.TIMER_VIDEO, uid.getVideoTurnedOnTimer());

        // TIMER_FLASHLIGHT
        addTimer(uidWriter, UidHealthStats.TIMER_FLASHLIGHT, uid.getFlashlightTurnedOnTimer());

        // TIMER_CAMERA
        addTimer(uidWriter, UidHealthStats.TIMER_CAMERA, uid.getCameraTurnedOnTimer());

        // TIMER_FOREGROUND_ACTIVITY
        addTimer(uidWriter, UidHealthStats.TIMER_FOREGROUND_ACTIVITY,
                uid.getForegroundActivityTimer());

        // TIMER_BLUETOOTH_SCAN
        addTimer(uidWriter, UidHealthStats.TIMER_BLUETOOTH_SCAN, uid.getBluetoothScanTimer());

        // TIMER_PROCESS_STATE_TOP_MS
        addTimer(uidWriter, UidHealthStats.TIMER_PROCESS_STATE_TOP_MS,
                uid.getProcessStateTimer(BatteryStats.Uid.PROCESS_STATE_TOP));

        // TIMER_PROCESS_STATE_FOREGROUND_SERVICE_MS
        addTimer(uidWriter, UidHealthStats.TIMER_PROCESS_STATE_FOREGROUND_SERVICE_MS,
                uid.getProcessStateTimer(BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE));

        // TIMER_PROCESS_STATE_TOP_SLEEPING_MS
        addTimer(uidWriter, UidHealthStats.TIMER_PROCESS_STATE_TOP_SLEEPING_MS,
                uid.getProcessStateTimer(BatteryStats.Uid.PROCESS_STATE_TOP_SLEEPING));

        // TIMER_PROCESS_STATE_FOREGROUND_MS
        addTimer(uidWriter, UidHealthStats.TIMER_PROCESS_STATE_FOREGROUND_MS,
                uid.getProcessStateTimer(BatteryStats.Uid.PROCESS_STATE_FOREGROUND));

        // TIMER_PROCESS_STATE_BACKGROUND_MS
        addTimer(uidWriter, UidHealthStats.TIMER_PROCESS_STATE_BACKGROUND_MS,
                uid.getProcessStateTimer(BatteryStats.Uid.PROCESS_STATE_BACKGROUND));

        // TIMER_PROCESS_STATE_CACHED_MS
        addTimer(uidWriter, UidHealthStats.TIMER_PROCESS_STATE_CACHED_MS,
                uid.getProcessStateTimer(BatteryStats.Uid.PROCESS_STATE_CACHED));

        // TIMER_VIBRATOR
        addTimer(uidWriter, UidHealthStats.TIMER_VIBRATOR, uid.getVibratorOnTimer());

        // MEASUREMENT_OTHER_USER_ACTIVITY_COUNT
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_OTHER_USER_ACTIVITY_COUNT,
                uid.getUserActivityCount(PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_BUTTON_USER_ACTIVITY_COUNT
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BUTTON_USER_ACTIVITY_COUNT,
                uid.getUserActivityCount(PowerManager.USER_ACTIVITY_EVENT_BUTTON,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_TOUCH_USER_ACTIVITY_COUNT
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_TOUCH_USER_ACTIVITY_COUNT,
                uid.getUserActivityCount(PowerManager.USER_ACTIVITY_EVENT_TOUCH,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_MOBILE_RX_BYTES
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_RX_BYTES,
                uid.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_RX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_MOBILE_TX_BYTES
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_TX_BYTES,
                uid.getNetworkActivityBytes(BatteryStats.NETWORK_MOBILE_TX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_WIFI_RX_BYTES
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_RX_BYTES,
                uid.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_RX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_WIFI_TX_BYTES
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_TX_BYTES,
                uid.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_TX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_BLUETOOTH_RX_BYTES
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_RX_BYTES,
                uid.getNetworkActivityBytes(BatteryStats.NETWORK_BT_RX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_BLUETOOTH_TX_BYTES
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_TX_BYTES,
                uid.getNetworkActivityBytes(BatteryStats.NETWORK_BT_TX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_MOBILE_RX_PACKETS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_RX_PACKETS,
                uid.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_RX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_MOBILE_TX_PACKETS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_MOBILE_TX_PACKETS,
                uid.getNetworkActivityPackets(BatteryStats.NETWORK_MOBILE_TX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_WIFI_RX_PACKETS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_RX_PACKETS,
                uid.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_RX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_WIFI_TX_PACKETS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_WIFI_TX_PACKETS,
                uid.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_TX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_BLUETOOTH_RX_PACKETS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_RX_PACKETS,
                uid.getNetworkActivityPackets(BatteryStats.NETWORK_BT_RX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_BLUETOOTH_TX_PACKETS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_BLUETOOTH_TX_PACKETS,
                uid.getNetworkActivityPackets(BatteryStats.NETWORK_BT_TX_DATA,
                    STATS_SINCE_UNPLUGGED));

        // TIMER_MOBILE_RADIO_ACTIVE
        uidWriter.addTimer(UidHealthStats.TIMER_MOBILE_RADIO_ACTIVE,
                uid.getMobileRadioActiveCount(STATS_SINCE_UNPLUGGED),
                uid.getMobileRadioActiveTime(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_USER_CPU_TIME_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS,
                uid.getUserCpuTimeUs(STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_SYSTEM_CPU_TIME_MS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS,
                uid.getSystemCpuTimeUs(STATS_SINCE_UNPLUGGED)/1000);

        // MEASUREMENT_CPU_POWER_MAMS
        uidWriter.addMeasurement(UidHealthStats.MEASUREMENT_CPU_POWER_MAMS, 0);
    }

    /**
     * Writes the contents of a BatteryStats.Uid.Pid into a HealthStatsWriter.
     */
    public void writePid(HealthStatsWriter pidWriter, BatteryStats.Uid.Pid pid) {
        if (pid == null) {
            return;
        }

        // MEASUREMENT_WAKE_NESTING_COUNT
        pidWriter.addMeasurement(PidHealthStats.MEASUREMENT_WAKE_NESTING_COUNT, pid.mWakeNesting);

        // MEASUREMENT_WAKE_SUM_MS
        pidWriter.addMeasurement(PidHealthStats.MEASUREMENT_WAKE_SUM_MS, pid.mWakeSumMs);

        // MEASUREMENT_WAKE_START_MS
        pidWriter.addMeasurement(PidHealthStats.MEASUREMENT_WAKE_SUM_MS, pid.mWakeStartMs);
    }

    /**
     * Writes the contents of a BatteryStats.Uid.Proc into a HealthStatsWriter.
     */
    public void writeProc(HealthStatsWriter procWriter, BatteryStats.Uid.Proc proc) {
        // MEASUREMENT_USER_TIME_MS
        procWriter.addMeasurement(ProcessHealthStats.MEASUREMENT_USER_TIME_MS,
                proc.getUserTime(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_SYSTEM_TIME_MS
        procWriter.addMeasurement(ProcessHealthStats.MEASUREMENT_SYSTEM_TIME_MS,
                proc.getSystemTime(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_STARTS_COUNT
        procWriter.addMeasurement(ProcessHealthStats.MEASUREMENT_STARTS_COUNT,
                proc.getStarts(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_CRASHES_COUNT
        procWriter.addMeasurement(ProcessHealthStats.MEASUREMENT_CRASHES_COUNT,
                proc.getNumCrashes(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_ANR_COUNT
        procWriter.addMeasurement(ProcessHealthStats.MEASUREMENT_ANR_COUNT,
                proc.getNumAnrs(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_FOREGROUND_MS
        procWriter.addMeasurement(ProcessHealthStats.MEASUREMENT_FOREGROUND_MS,
                proc.getForegroundTime(STATS_SINCE_UNPLUGGED));
    }

    /**
     * Writes the contents of a BatteryStats.Uid.Pkg into a HealthStatsWriter.
     */
    public void writePkg(HealthStatsWriter pkgWriter, BatteryStats.Uid.Pkg pkg) {
        // STATS_SERVICES
        for (final Map.Entry<String,? extends BatteryStats.Uid.Pkg.Serv> entry:
                pkg.getServiceStats().entrySet()) {
            final HealthStatsWriter writer = new HealthStatsWriter(ServiceHealthStats.CONSTANTS);
            writeServ(writer, entry.getValue());
            pkgWriter.addStats(PackageHealthStats.STATS_SERVICES, entry.getKey(), writer);
        }

        // MEASUREMENTS_WAKEUP_ALARMS_COUNT
        for (final Map.Entry<String,? extends BatteryStats.Counter> entry:
                pkg.getWakeupAlarmStats().entrySet()) {
            final BatteryStats.Counter counter = entry.getValue();
            if (counter != null) {
                pkgWriter.addMeasurements(PackageHealthStats.MEASUREMENTS_WAKEUP_ALARMS_COUNT,
                        entry.getKey(), counter.getCountLocked(STATS_SINCE_UNPLUGGED));
            }
        }
    }

    /**
     * Writes the contents of a BatteryStats.Uid.Pkg.Serv into a HealthStatsWriter.
     */
    public void writeServ(HealthStatsWriter servWriter, BatteryStats.Uid.Pkg.Serv serv) {
        // MEASUREMENT_START_SERVICE_COUNT
        servWriter.addMeasurement(ServiceHealthStats.MEASUREMENT_START_SERVICE_COUNT,
                serv.getStarts(STATS_SINCE_UNPLUGGED));

        // MEASUREMENT_LAUNCH_COUNT
        servWriter.addMeasurement(ServiceHealthStats.MEASUREMENT_LAUNCH_COUNT,
                serv.getLaunches(STATS_SINCE_UNPLUGGED));
    }

    /**
     * Adds a BatteryStats.Timer into a HealthStatsWriter. Safe to pass a null timer.
     */
    private void addTimer(HealthStatsWriter writer, int key, BatteryStats.Timer timer) {
        if (timer != null) {
            writer.addTimer(key, timer.getCountLocked(STATS_SINCE_UNPLUGGED),
                    timer.getTotalTimeLocked(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED) / 1000);
        }
    }

    /**
     * Adds a named BatteryStats.Timer into a HealthStatsWriter. Safe to pass a null timer.
     */
    private void addTimers(HealthStatsWriter writer, int key, String name,
            BatteryStats.Timer timer) {
        if (timer != null) {
            writer.addTimers(key, name, new TimerStat(timer.getCountLocked(STATS_SINCE_UNPLUGGED),
                    timer.getTotalTimeLocked(mNowRealtimeMs*1000, STATS_SINCE_UNPLUGGED) / 1000));
        }
    }
}

