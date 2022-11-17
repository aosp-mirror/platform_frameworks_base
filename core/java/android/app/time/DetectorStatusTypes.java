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
package android.app.time;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A set of constants that can relate to time or time zone detector status.
 *
 * <ul>
 *     <li>Detector status - the status of the overall detector.</li>
 *     <li>Detection algorithm status - the status of an algorithm that a detector can use.
 *     Each detector is expected to have one or more known algorithms to detect its chosen property,
 *     e.g. for time zone devices can have a "location" detection algorithm, where the device's
 *     location is used to detect the time zone.</li>
 * </ul>
 *
 * @hide
 */
public final class DetectorStatusTypes {

    /** A status code for a detector. */
    @IntDef(prefix = "DETECTOR_STATUS_", value = {
            DETECTOR_STATUS_UNKNOWN,
            DETECTOR_STATUS_NOT_SUPPORTED,
            DETECTOR_STATUS_NOT_RUNNING,
            DETECTOR_STATUS_RUNNING,
    })
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface DetectorStatus {}

    /**
     * The detector status is unknown. Expected only for use as a placeholder before the actual
     * status is known.
     */
    public static final @DetectorStatus int DETECTOR_STATUS_UNKNOWN = 0;

    /** The detector is not supported on this device. */
    public static final @DetectorStatus int DETECTOR_STATUS_NOT_SUPPORTED = 1;

    /** The detector is supported but is not running. */
    public static final @DetectorStatus int DETECTOR_STATUS_NOT_RUNNING = 2;

    /** The detector is supported and is running. */
    public static final @DetectorStatus int DETECTOR_STATUS_RUNNING = 3;

    private DetectorStatusTypes() {}

    /**
     * A status code for a detection algorithm.
     */
    @IntDef(prefix = "DETECTION_ALGORITHM_STATUS_", value = {
            DETECTION_ALGORITHM_STATUS_UNKNOWN,
            DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED,
            DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
            DETECTION_ALGORITHM_STATUS_RUNNING,
    })
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface DetectionAlgorithmStatus {}

    /**
     * The detection algorithm status is unknown. Expected only for use as a placeholder before the
     * actual status is known.
     */
    public static final @DetectionAlgorithmStatus int DETECTION_ALGORITHM_STATUS_UNKNOWN = 0;

    /** The detection algorithm is not supported on this device. */
    public static final @DetectionAlgorithmStatus int DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED = 1;

    /** The detection algorithm supported but is not running. */
    public static final @DetectionAlgorithmStatus int DETECTION_ALGORITHM_STATUS_NOT_RUNNING = 2;

    /** The detection algorithm supported and is running. */
    public static final @DetectionAlgorithmStatus int DETECTION_ALGORITHM_STATUS_RUNNING = 3;

    /**
     * Validates the supplied value is one of the known {@code DETECTOR_STATUS_} constants and
     * returns it if it is valid. {@link #DETECTOR_STATUS_UNKNOWN} is considered valid.
     *
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static @DetectorStatus int requireValidDetectorStatus(
            @DetectorStatus int detectorStatus) {
        if (detectorStatus < DETECTOR_STATUS_UNKNOWN || detectorStatus > DETECTOR_STATUS_RUNNING) {
            throw new IllegalArgumentException("Invalid detector status: " + detectorStatus);
        }
        return detectorStatus;
    }

    /**
     * Returns a string for each {@code DETECTOR_STATUS_} constant. See also
     * {@link #detectorStatusFromString(String)}.
     *
     * @throws IllegalArgumentException if the value is not recognized
     */
    @NonNull
    public static String detectorStatusToString(@DetectorStatus int detectorStatus) {
        switch (detectorStatus) {
            case DETECTOR_STATUS_UNKNOWN:
                return "UNKNOWN";
            case DETECTOR_STATUS_NOT_SUPPORTED:
                return "NOT_SUPPORTED";
            case DETECTOR_STATUS_NOT_RUNNING:
                return "NOT_RUNNING";
            case DETECTOR_STATUS_RUNNING:
                return "RUNNING";
            default:
                throw new IllegalArgumentException("Unknown status: " + detectorStatus);
        }
    }

    /**
     * Returns {@code DETECTOR_STATUS_} constant value from a string. See also
     * {@link #detectorStatusToString(int)}.
     *
     * @throws IllegalArgumentException if the value is not recognized or is invalid
     */
    public static @DetectorStatus int detectorStatusFromString(
            @Nullable String detectorStatusString) {
        if (TextUtils.isEmpty(detectorStatusString)) {
            throw new IllegalArgumentException("Empty status: " + detectorStatusString);
        }

        switch (detectorStatusString) {
            case "UNKNOWN":
                return DETECTOR_STATUS_UNKNOWN;
            case "NOT_SUPPORTED":
                return DETECTOR_STATUS_NOT_SUPPORTED;
            case "NOT_RUNNING":
                return DETECTOR_STATUS_NOT_RUNNING;
            case "RUNNING":
                return DETECTOR_STATUS_RUNNING;
            default:
                throw new IllegalArgumentException("Unknown status: " + detectorStatusString);
        }
    }

    /**
     * Validates the supplied value is one of the known {@code DETECTION_ALGORITHM_} constants and
     * returns it if it is valid. {@link #DETECTION_ALGORITHM_STATUS_UNKNOWN} is considered valid.
     *
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static @DetectionAlgorithmStatus int requireValidDetectionAlgorithmStatus(
            @DetectionAlgorithmStatus int detectionAlgorithmStatus) {
        if (detectionAlgorithmStatus < DETECTION_ALGORITHM_STATUS_UNKNOWN
                || detectionAlgorithmStatus > DETECTION_ALGORITHM_STATUS_RUNNING) {
            throw new IllegalArgumentException(
                    "Invalid detection algorithm: " + detectionAlgorithmStatus);
        }
        return detectionAlgorithmStatus;
    }

    /**
     * Returns a string for each {@code DETECTION_ALGORITHM_} constant. See also
     * {@link #detectionAlgorithmStatusFromString(String)}
     *
     * @throws IllegalArgumentException if the value is not recognized
     */
    @NonNull
    public static String detectionAlgorithmStatusToString(
            @DetectionAlgorithmStatus int detectorAlgorithmStatus) {
        switch (detectorAlgorithmStatus) {
            case DETECTION_ALGORITHM_STATUS_UNKNOWN:
                return "UNKNOWN";
            case DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED:
                return "NOT_SUPPORTED";
            case DETECTION_ALGORITHM_STATUS_NOT_RUNNING:
                return "NOT_RUNNING";
            case DETECTION_ALGORITHM_STATUS_RUNNING:
                return "RUNNING";
            default:
                throw new IllegalArgumentException("Unknown status: " + detectorAlgorithmStatus);
        }
    }

    /**
     * Returns {@code DETECTION_ALGORITHM_} constant value from a string. See also
     * {@link #detectionAlgorithmStatusToString(int)} (String)}
     *
     * @throws IllegalArgumentException if the value is not recognized or is invalid
     */
    public static @DetectionAlgorithmStatus int detectionAlgorithmStatusFromString(
            @Nullable String detectorAlgorithmStatusString) {

        if (TextUtils.isEmpty(detectorAlgorithmStatusString)) {
            throw new IllegalArgumentException("Empty status: " + detectorAlgorithmStatusString);
        }

        switch (detectorAlgorithmStatusString) {
            case "UNKNOWN":
                return DETECTION_ALGORITHM_STATUS_UNKNOWN;
            case "NOT_SUPPORTED":
                return DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED;
            case "NOT_RUNNING":
                return DETECTION_ALGORITHM_STATUS_NOT_RUNNING;
            case "RUNNING":
                return DETECTION_ALGORITHM_STATUS_RUNNING;
            default:
                throw new IllegalArgumentException(
                        "Unknown status: " + detectorAlgorithmStatusString);
        }
    }
}
