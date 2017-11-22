/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.statsd.dogfood;

import android.text.format.DateFormat;

import com.android.os.StatsLog;

import java.util.List;

public class DisplayProtoUtils {
    public static void displayLogReport(StringBuilder sb, StatsLog.ConfigMetricsReport report) {
        sb.append("ConfigKey: ");
        if (report.hasConfigKey()) {
            com.android.os.StatsLog.ConfigMetricsReport.ConfigKey key = report.getConfigKey();
            sb.append("\tuid: ").append(key.getUid()).append(" name: ").append(key.getName())
                    .append("\n");
        }

        sb.append("StatsLogReport size: ").append(report.getMetricsCount()).append("\n");
        for (StatsLog.StatsLogReport log : report.getMetricsList()) {
            sb.append("\n\n");
            sb.append("metric id: ").append(log.getMetricName()).append("\n");
            sb.append("start time:").append(getDateStr(log.getStartReportNanos())).append("\n");
            sb.append("end time:").append(getDateStr(log.getEndReportNanos())).append("\n");

            switch (log.getDataCase()) {
                case DURATION_METRICS:
                    sb.append("Duration metric data\n");
                    displayDurationMetricData(sb, log);
                    break;
                case EVENT_METRICS:
                    sb.append("Event metric data\n");
                    displayEventMetricData(sb, log);
                    break;
                case COUNT_METRICS:
                    sb.append("Count metric data\n");
                    displayCountMetricData(sb, log);
                    break;
                case GAUGE_METRICS:
                    sb.append("Gauge metric data\n");
                    displayGaugeMetricData(sb, log);
                    break;
                case VALUE_METRICS:
                    sb.append("Value metric data\n");
                    displayValueMetricData(sb, log);
                    break;
                case DATA_NOT_SET:
                    sb.append("No metric data\n");
                    break;
            }
        }
    }

    public static String getDateStr(long nanoSec) {
        return DateFormat.format("dd/MM hh:mm:ss", nanoSec/1000000).toString();
    }

    private static void displayDimension(StringBuilder sb, List<StatsLog.KeyValuePair> pairs) {
        for (com.android.os.StatsLog.KeyValuePair kv : pairs) {
            sb.append(kv.getKey()).append(":");
            if (kv.hasValueBool()) {
                sb.append(kv.getValueBool());
            } else if (kv.hasValueFloat()) {
                sb.append(kv.getValueFloat());
            } else if (kv.hasValueInt()) {
                sb.append(kv.getValueInt());
            } else if (kv.hasValueStr()) {
                sb.append(kv.getValueStr());
            }
            sb.append(" ");
        }
    }

    public static void displayDurationMetricData(StringBuilder sb, StatsLog.StatsLogReport log) {
        StatsLog.StatsLogReport.DurationMetricDataWrapper durationMetricDataWrapper
                = log.getDurationMetrics();
        sb.append("Dimension size: ").append(durationMetricDataWrapper.getDataCount()).append("\n");
        for (StatsLog.DurationMetricData duration : durationMetricDataWrapper.getDataList()) {
            sb.append("dimension: ");
            displayDimension(sb, duration.getDimensionList());
            sb.append("\n");

            for (StatsLog.DurationBucketInfo info : duration.getBucketInfoList())  {
                sb.append("\t[").append(getDateStr(info.getStartBucketNanos())).append("-")
                        .append(getDateStr(info.getEndBucketNanos())).append("] -> ")
                        .append(info.getDurationNanos()).append(" ns\n");
            }
        }
    }

    public static void displayEventMetricData(StringBuilder sb, StatsLog.StatsLogReport log) {
        sb.append("Contains ").append(log.getEventMetrics().getDataCount()).append(" events\n");
        StatsLog.StatsLogReport.EventMetricDataWrapper eventMetricDataWrapper =
                log.getEventMetrics();
        for (StatsLog.EventMetricData event : eventMetricDataWrapper.getDataList()) {
            sb.append(getDateStr(event.getTimestampNanos())).append(": ");
            switch (event.getAtom().getPushedCase()) {
                case SETTING_CHANGED:
                    sb.append("SETTING_CHANGED\n");
                    break;
                case SYNC_STATE_CHANGED:
                    sb.append("SYNC_STATE_CHANGED\n");
                    break;
                case AUDIO_STATE_CHANGED:
                    sb.append("AUDIO_STATE_CHANGED\n");
                    break;
                case CAMERA_STATE_CHANGED:
                    sb.append("CAMERA_STATE_CHANGED\n");
                    break;
                case ISOLATED_UID_CHANGED:
                    sb.append("ISOLATED_UID_CHANGED\n");
                    break;
                case SCREEN_STATE_CHANGED:
                    sb.append("SCREEN_STATE_CHANGED\n");
                    break;
                case SENSOR_STATE_CHANGED:
                    sb.append("SENSOR_STATE_CHANGED\n");
                    break;
                case BATTERY_LEVEL_CHANGED:
                    sb.append("BATTERY_LEVEL_CHANGED\n");
                    break;
                case PLUGGED_STATE_CHANGED:
                    sb.append("PLUGGED_STATE_CHANGED\n");
                    break;
                case WAKEUP_ALARM_OCCURRED:
                    sb.append("WAKEUP_ALARM_OCCURRED\n");
                    break;
                case BLE_SCAN_STATE_CHANGED:
                    sb.append("BLE_SCAN_STATE_CHANGED\n");
                    break;
                case CHARGING_STATE_CHANGED:
                    sb.append("CHARGING_STATE_CHANGED\n");
                    break;
                case GPS_SCAN_STATE_CHANGED:
                    sb.append("GPS_SCAN_STATE_CHANGED\n");
                    break;
                case KERNEL_WAKEUP_REPORTED:
                    sb.append("KERNEL_WAKEUP_REPORTED\n");
                    break;
                case WAKELOCK_STATE_CHANGED:
                    sb.append("WAKELOCK_STATE_CHANGED\n");
                    break;
                case WIFI_LOCK_STATE_CHANGED:
                    sb.append("WIFI_LOCK_STATE_CHANGED\n");
                    break;
                case WIFI_SCAN_STATE_CHANGED:
                    sb.append("WIFI_SCAN_STATE_CHANGED\n");
                    break;
                case BLE_SCAN_RESULT_RECEIVED:
                    sb.append("BLE_SCAN_RESULT_RECEIVED\n");
                    break;
                case DEVICE_ON_STATUS_CHANGED:
                    sb.append("DEVICE_ON_STATUS_CHANGED\n");
                    break;
                case FLASHLIGHT_STATE_CHANGED:
                    sb.append("FLASHLIGHT_STATE_CHANGED\n");
                    break;
                case SCREEN_BRIGHTNESS_CHANGED:
                    sb.append("SCREEN_BRIGHTNESS_CHANGED\n");
                    break;
                case UID_PROCESS_STATE_CHANGED:
                    sb.append("UID_PROCESS_STATE_CHANGED\n");
                    break;
                case UID_WAKELOCK_STATE_CHANGED:
                    sb.append("UID_WAKELOCK_STATE_CHANGED\n");
                    break;
                case DEVICE_TEMPERATURE_REPORTED:
                    sb.append("DEVICE_TEMPERATURE_REPORTED\n");
                    break;
                case SCHEDULED_JOB_STATE_CHANGED:
                    sb.append("SCHEDULED_JOB_STATE_CHANGED\n");
                    break;
                case MEDIA_CODEC_ACTIVITY_CHANGED:
                    sb.append("MEDIA_CODEC_ACTIVITY_CHANGED\n");
                    break;
                case WIFI_SIGNAL_STRENGTH_CHANGED:
                    sb.append("WIFI_SIGNAL_STRENGTH_CHANGED\n");
                    break;
                case PHONE_SIGNAL_STRENGTH_CHANGED:
                    sb.append("PHONE_SIGNAL_STRENGTH_CHANGED\n");
                    break;
                case DEVICE_IDLE_MODE_STATE_CHANGED:
                    sb.append("DEVICE_IDLE_MODE_STATE_CHANGED\n");
                    break;
                case BATTERY_SAVER_MODE_STATE_CHANGED:
                    sb.append("BATTERY_SAVER_MODE_STATE_CHANGED\n");
                    break;
                case PROCESS_LIFE_CYCLE_STATE_CHANGED:
                    sb.append("PROCESS_LIFE_CYCLE_STATE_CHANGED\n");
                    break;
                case ACTIVITY_FOREGROUND_STATE_CHANGED:
                    sb.append("ACTIVITY_FOREGROUND_STATE_CHANGED\n");
                    break;
                case BLE_UNOPTIMIZED_SCAN_STATE_CHANGED:
                    sb.append("BLE_UNOPTIMIZED_SCAN_STATE_CHANGED\n");
                    break;
                case LONG_PARTIAL_WAKELOCK_STATE_CHANGED:
                    sb.append("LONG_PARTIAL_WAKELOCK_STATE_CHANGED\n");
                    break;
                case PUSHED_NOT_SET:
                    sb.append("PUSHED_NOT_SET\n");
                    break;
            }
        }
    }

    public static void displayCountMetricData(StringBuilder sb, StatsLog.StatsLogReport log) {
        StatsLog.StatsLogReport.CountMetricDataWrapper countMetricDataWrapper
                = log.getCountMetrics();
        sb.append("Dimension size: ").append(countMetricDataWrapper.getDataCount()).append("\n");
        for (StatsLog.CountMetricData count : countMetricDataWrapper.getDataList()) {
            sb.append("dimension: ");
            displayDimension(sb, count.getDimensionList());
            sb.append("\n");

            for (StatsLog.CountBucketInfo info : count.getBucketInfoList())  {
                sb.append("\t[").append(getDateStr(info.getStartBucketNanos())).append("-")
                        .append(getDateStr(info.getEndBucketNanos())).append("] -> ")
                        .append(info.getCount()).append("\n");
            }
        }
    }

    public static void displayGaugeMetricData(StringBuilder sb, StatsLog.StatsLogReport log) {
        sb.append("Display me!");
    }

    public static void displayValueMetricData(StringBuilder sb, StatsLog.StatsLogReport log) {
        sb.append("Display me!");
    }
}
