/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.face.AcquiredInfo;
import android.hardware.biometrics.face.AuthenticationFrame;
import android.hardware.biometrics.face.BaseFrame;
import android.hardware.biometrics.face.Cell;
import android.hardware.biometrics.face.EnrollmentFrame;
import android.hardware.biometrics.face.EnrollmentStage;
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.face.Feature;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceDataFrame;
import android.hardware.face.FaceEnrollCell;
import android.hardware.face.FaceEnrollFrame;
import android.hardware.face.FaceEnrollStages;
import android.hardware.face.FaceEnrollStages.FaceEnrollStage;
import android.util.Slog;

/**
 * Utilities for converting from hardware to framework-defined AIDL models.
 */
public final class AidlConversionUtils {

    private static final String TAG = "AidlConversionUtils";

    // Prevent instantiation.
    private AidlConversionUtils() {
    }

    @BiometricFaceConstants.FaceError
    public static int toFrameworkError(byte aidlError) {
        switch (aidlError) {
            case Error.HW_UNAVAILABLE:
                return BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE;
            case Error.UNABLE_TO_PROCESS:
                return BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS;
            case Error.TIMEOUT:
                return BiometricFaceConstants.FACE_ERROR_TIMEOUT;
            case Error.NO_SPACE:
                return BiometricFaceConstants.FACE_ERROR_NO_SPACE;
            case Error.CANCELED:
                return BiometricFaceConstants.FACE_ERROR_CANCELED;
            case Error.UNABLE_TO_REMOVE:
                return BiometricFaceConstants.FACE_ERROR_UNABLE_TO_REMOVE;
            case Error.VENDOR:
                return BiometricFaceConstants.FACE_ERROR_VENDOR;
            case Error.REENROLL_REQUIRED:
                return BiometricFaceConstants.BIOMETRIC_ERROR_RE_ENROLL;
            case Error.UNKNOWN:
            default:
                return BiometricFaceConstants.FACE_ERROR_UNKNOWN;
        }
    }

    @BiometricFaceConstants.FaceAcquired
    public static int toFrameworkAcquiredInfo(byte aidlAcquiredInfo) {
        switch (aidlAcquiredInfo) {
            case AcquiredInfo.GOOD:
                return BiometricFaceConstants.FACE_ACQUIRED_GOOD;
            case AcquiredInfo.INSUFFICIENT:
                return BiometricFaceConstants.FACE_ACQUIRED_INSUFFICIENT;
            case AcquiredInfo.TOO_BRIGHT:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT;
            case AcquiredInfo.TOO_DARK:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK;
            case AcquiredInfo.TOO_CLOSE:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE;
            case AcquiredInfo.TOO_FAR:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_FAR;
            case AcquiredInfo.FACE_TOO_HIGH:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_HIGH;
            case AcquiredInfo.FACE_TOO_LOW:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_LOW;
            case AcquiredInfo.FACE_TOO_RIGHT:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_RIGHT;
            case AcquiredInfo.FACE_TOO_LEFT:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_LEFT;
            case AcquiredInfo.POOR_GAZE:
                return BiometricFaceConstants.FACE_ACQUIRED_POOR_GAZE;
            case AcquiredInfo.NOT_DETECTED:
                return BiometricFaceConstants.FACE_ACQUIRED_NOT_DETECTED;
            case AcquiredInfo.TOO_MUCH_MOTION:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_MUCH_MOTION;
            case AcquiredInfo.RECALIBRATE:
                return BiometricFaceConstants.FACE_ACQUIRED_RECALIBRATE;
            case AcquiredInfo.TOO_DIFFERENT:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_DIFFERENT;
            case AcquiredInfo.TOO_SIMILAR:
                return BiometricFaceConstants.FACE_ACQUIRED_TOO_SIMILAR;
            case AcquiredInfo.PAN_TOO_EXTREME:
                return BiometricFaceConstants.FACE_ACQUIRED_PAN_TOO_EXTREME;
            case AcquiredInfo.TILT_TOO_EXTREME:
                return BiometricFaceConstants.FACE_ACQUIRED_TILT_TOO_EXTREME;
            case AcquiredInfo.ROLL_TOO_EXTREME:
                return BiometricFaceConstants.FACE_ACQUIRED_ROLL_TOO_EXTREME;
            case AcquiredInfo.FACE_OBSCURED:
                return BiometricFaceConstants.FACE_ACQUIRED_FACE_OBSCURED;
            case AcquiredInfo.START:
                return BiometricFaceConstants.FACE_ACQUIRED_START;
            case AcquiredInfo.SENSOR_DIRTY:
                return BiometricFaceConstants.FACE_ACQUIRED_SENSOR_DIRTY;
            case AcquiredInfo.VENDOR:
                return BiometricFaceConstants.FACE_ACQUIRED_VENDOR;
            case AcquiredInfo.FIRST_FRAME_RECEIVED:
                return BiometricFaceConstants.FACE_ACQUIRED_FIRST_FRAME_RECEIVED;
            case AcquiredInfo.DARK_GLASSES_DETECTED:
                return BiometricFaceConstants.FACE_ACQUIRED_DARK_GLASSES_DETECTED;
            case AcquiredInfo.MOUTH_COVERING_DETECTED:
                return BiometricFaceConstants.FACE_ACQUIRED_MOUTH_COVERING_DETECTED;
            case AcquiredInfo.UNKNOWN:
            default:
                return BiometricFaceConstants.FACE_ACQUIRED_UNKNOWN;
        }
    }

    @FaceEnrollStage
    public static int toFrameworkEnrollmentStage(int aidlEnrollmentStage) {
        switch (aidlEnrollmentStage) {
            case EnrollmentStage.FIRST_FRAME_RECEIVED:
                return FaceEnrollStages.FIRST_FRAME_RECEIVED;
            case EnrollmentStage.WAITING_FOR_CENTERING:
                return FaceEnrollStages.WAITING_FOR_CENTERING;
            case EnrollmentStage.HOLD_STILL_IN_CENTER:
                return FaceEnrollStages.HOLD_STILL_IN_CENTER;
            case EnrollmentStage.ENROLLING_MOVEMENT_1:
                return FaceEnrollStages.ENROLLING_MOVEMENT_1;
            case EnrollmentStage.ENROLLING_MOVEMENT_2:
                return FaceEnrollStages.ENROLLING_MOVEMENT_2;
            case EnrollmentStage.ENROLLMENT_FINISHED:
                return FaceEnrollStages.ENROLLMENT_FINISHED;
            case EnrollmentStage.UNKNOWN:
            default:
                return FaceEnrollStages.UNKNOWN;
        }
    }

    @NonNull
    public static FaceAuthenticationFrame toFrameworkAuthenticationFrame(
            @NonNull AuthenticationFrame frame) {
        return new FaceAuthenticationFrame(toFrameworkBaseFrame(frame.data));
    }

    @NonNull
    public static FaceEnrollFrame toFrameworkEnrollmentFrame(@NonNull EnrollmentFrame frame) {
        return new FaceEnrollFrame(
                toFrameworkCell(frame.cell),
                toFrameworkEnrollmentStage(frame.stage),
                toFrameworkBaseFrame(frame.data));
    }

    @NonNull
    public static FaceDataFrame toFrameworkBaseFrame(@NonNull BaseFrame frame) {
        return new FaceDataFrame(
                toFrameworkAcquiredInfo(frame.acquiredInfo),
                frame.vendorCode,
                frame.pan,
                frame.tilt,
                frame.distance,
                frame.isCancellable);
    }

    @Nullable
    public static FaceEnrollCell toFrameworkCell(@Nullable Cell cell) {
        return cell == null ? null : new FaceEnrollCell(cell.x, cell.y, cell.z);
    }

    public static byte convertFrameworkToAidlFeature(int feature) throws IllegalArgumentException {
        switch (feature) {
            case BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION:
                return Feature.REQUIRE_ATTENTION;
            case BiometricFaceConstants.FEATURE_REQUIRE_REQUIRE_DIVERSITY:
                return Feature.REQUIRE_DIVERSE_POSES;
            default:
                Slog.e(TAG, "Unsupported feature : " + feature);
                throw new IllegalArgumentException();
        }
    }

    public static int convertAidlToFrameworkFeature(byte feature) throws IllegalArgumentException {
        switch (feature) {
            case Feature.REQUIRE_ATTENTION:
                return BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION;
            case Feature.REQUIRE_DIVERSE_POSES:
                return BiometricFaceConstants.FEATURE_REQUIRE_REQUIRE_DIVERSITY;
            default:
                Slog.e(TAG, "Unsupported feature : " + feature);
                throw new IllegalArgumentException();
        }
    }
}
