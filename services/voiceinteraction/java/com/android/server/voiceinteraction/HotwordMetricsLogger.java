/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_CREATE_REQUESTED__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_CREATE_REQUESTED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_CREATE_REQUESTED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__NORMAL_DETECTOR;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
import static com.android.internal.util.FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;

import android.service.voice.HotwordDetector;

import com.android.internal.util.FrameworkStatsLog;

/**
 * A utility class for logging hotword statistics event.
 */
public final class HotwordMetricsLogger {

    private static final int METRICS_INIT_DETECTOR_SOFTWARE =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
    private static final int METRICS_INIT_DETECTOR_DSP =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
    private static final int METRICS_INIT_NORMAL_DETECTOR =
            HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED__DETECTOR_TYPE__NORMAL_DETECTOR;

    private HotwordMetricsLogger() {
        // Class only contains static utility functions, and should not be instantiated
    }

    /**
     * Logs information related to create hotword detector.
     */
    public static void writeDetectorCreateEvent(int detectorType, boolean isCreated, int uid) {
        int metricsDetectorType = getCreateMetricsDetectorType(detectorType);
        FrameworkStatsLog.write(FrameworkStatsLog.HOTWORD_DETECTOR_CREATE_REQUESTED,
                metricsDetectorType, isCreated, uid);
    }

    /**
     * Logs information related to hotword detection service init result.
     */
    public static void writeServiceInitResultEvent(int detectorType, int result) {
        int metricsDetectorType = getInitMetricsDetectorType(detectorType);
        FrameworkStatsLog.write(FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_INIT_RESULT_REPORTED,
                metricsDetectorType, result);
    }

    /**
     * Logs information related to hotword detection service restarting.
     */
    public static void writeServiceRestartEvent(int detectorType, int reason) {
        int metricsDetectorType = getRestartMetricsDetectorType(detectorType);
        FrameworkStatsLog.write(FrameworkStatsLog.HOTWORD_DETECTION_SERVICE_RESTARTED,
                metricsDetectorType, reason);
    }

    /**
     * Logs information related to keyphrase trigger.
     */
    public static void writeKeyphraseTriggerEvent(int detectorType, int result) {
        int metricsDetectorType = getKeyphraseMetricsDetectorType(detectorType);
        FrameworkStatsLog.write(FrameworkStatsLog.HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED,
                metricsDetectorType, result);
    }

    /**
     * Logs information related to hotword detector events.
     */
    public static void writeDetectorEvent(int detectorType, int event, int uid) {
        int metricsDetectorType = getDetectorMetricsDetectorType(detectorType);
        FrameworkStatsLog.write(FrameworkStatsLog.HOTWORD_DETECTOR_EVENTS,
                metricsDetectorType, event, uid);
    }

    private static int getCreateMetricsDetectorType(int detectorType) {
        switch (detectorType) {
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE:
                return HOTWORD_DETECTOR_CREATE_REQUESTED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP:
                return HOTWORD_DETECTOR_CREATE_REQUESTED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
            default:
                return HOTWORD_DETECTOR_CREATE_REQUESTED__DETECTOR_TYPE__NORMAL_DETECTOR;
        }
    }

    private static int getRestartMetricsDetectorType(int detectorType) {
        switch (detectorType) {
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE:
                return HOTWORD_DETECTION_SERVICE_RESTARTED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP:
                return HOTWORD_DETECTION_SERVICE_RESTARTED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
            default:
                return HOTWORD_DETECTION_SERVICE_RESTARTED__DETECTOR_TYPE__NORMAL_DETECTOR;
        }
    }

    private static int getInitMetricsDetectorType(int detectorType) {
        switch (detectorType) {
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE:
                return METRICS_INIT_DETECTOR_SOFTWARE;
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP:
                return METRICS_INIT_DETECTOR_DSP;
            default:
                return METRICS_INIT_NORMAL_DETECTOR;
        }
    }

    private static int getKeyphraseMetricsDetectorType(int detectorType) {
        switch (detectorType) {
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE:
                return HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP:
                return HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
            default:
                return HOTWORD_DETECTOR_KEYPHRASE_TRIGGERED__DETECTOR_TYPE__NORMAL_DETECTOR;
        }
    }

    private static int getDetectorMetricsDetectorType(int detectorType) {
        switch (detectorType) {
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE:
                return HOTWORD_DETECTOR_EVENTS__DETECTOR_TYPE__TRUSTED_DETECTOR_SOFTWARE;
            case HotwordDetector.DETECTOR_TYPE_TRUSTED_HOTWORD_DSP:
                return HOTWORD_DETECTOR_EVENTS__DETECTOR_TYPE__TRUSTED_DETECTOR_DSP;
            default:
                return HOTWORD_DETECTOR_EVENTS__DETECTOR_TYPE__NORMAL_DETECTOR;
        }
    }
}
