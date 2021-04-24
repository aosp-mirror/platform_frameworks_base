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
import android.hardware.biometrics.face.Error;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceDataFrame;
import android.hardware.face.FaceEnrollCell;
import android.hardware.face.FaceEnrollFrame;

/**
 * Utilities for converting from hardware to framework-defined AIDL models.
 */
final class AidlConversionUtils {
    // Prevent instantiation.
    private AidlConversionUtils() {
    }

    public static @BiometricFaceConstants.FaceError int toFrameworkError(byte aidlError) {
        if (aidlError == Error.UNKNOWN) {
            // No framework constant available
            return BiometricFaceConstants.FACE_ERROR_UNKNOWN;
        } else if (aidlError == Error.HW_UNAVAILABLE) {
            return BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE;
        } else if (aidlError == Error.UNABLE_TO_PROCESS) {
            return BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS;
        } else if (aidlError == Error.TIMEOUT) {
            return BiometricFaceConstants.FACE_ERROR_TIMEOUT;
        } else if (aidlError == Error.NO_SPACE) {
            return BiometricFaceConstants.FACE_ERROR_NO_SPACE;
        } else if (aidlError == Error.CANCELED) {
            return BiometricFaceConstants.FACE_ERROR_CANCELED;
        } else if (aidlError == Error.UNABLE_TO_REMOVE) {
            return BiometricFaceConstants.FACE_ERROR_UNABLE_TO_REMOVE;
        } else if (aidlError == Error.VENDOR) {
            return BiometricFaceConstants.FACE_ERROR_VENDOR;
        } else if (aidlError == Error.REENROLL_REQUIRED) {
            return BiometricFaceConstants.BIOMETRIC_ERROR_RE_ENROLL;
        } else {
            return BiometricFaceConstants.FACE_ERROR_UNKNOWN;
        }
    }

    public static @BiometricFaceConstants.FaceAcquired int toFrameworkAcquiredInfo(
            byte aidlAcquired) {
        if (aidlAcquired == AcquiredInfo.UNKNOWN) {
            return BiometricFaceConstants.FACE_ACQUIRED_UNKNOWN;
        } else if (aidlAcquired == AcquiredInfo.GOOD) {
            return BiometricFaceConstants.FACE_ACQUIRED_GOOD;
        } else if (aidlAcquired == AcquiredInfo.INSUFFICIENT) {
            return BiometricFaceConstants.FACE_ACQUIRED_INSUFFICIENT;
        } else if (aidlAcquired == AcquiredInfo.TOO_BRIGHT) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT;
        } else if (aidlAcquired == AcquiredInfo.TOO_DARK) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK;
        } else if (aidlAcquired == AcquiredInfo.TOO_CLOSE) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE;
        } else if (aidlAcquired == AcquiredInfo.TOO_FAR) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_FAR;
        } else if (aidlAcquired == AcquiredInfo.FACE_TOO_HIGH) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_HIGH;
        } else if (aidlAcquired == AcquiredInfo.FACE_TOO_LOW) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_LOW;
        } else if (aidlAcquired == AcquiredInfo.FACE_TOO_RIGHT) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_RIGHT;
        } else if (aidlAcquired == AcquiredInfo.FACE_TOO_LEFT) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_LEFT;
        } else if (aidlAcquired == AcquiredInfo.POOR_GAZE) {
            return BiometricFaceConstants.FACE_ACQUIRED_POOR_GAZE;
        } else if (aidlAcquired == AcquiredInfo.NOT_DETECTED) {
            return BiometricFaceConstants.FACE_ACQUIRED_NOT_DETECTED;
        } else if (aidlAcquired == AcquiredInfo.TOO_MUCH_MOTION) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_MUCH_MOTION;
        } else if (aidlAcquired == AcquiredInfo.RECALIBRATE) {
            return BiometricFaceConstants.FACE_ACQUIRED_RECALIBRATE;
        } else if (aidlAcquired == AcquiredInfo.TOO_DIFFERENT) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_DIFFERENT;
        } else if (aidlAcquired == AcquiredInfo.TOO_SIMILAR) {
            return BiometricFaceConstants.FACE_ACQUIRED_TOO_SIMILAR;
        } else if (aidlAcquired == AcquiredInfo.PAN_TOO_EXTREME) {
            return BiometricFaceConstants.FACE_ACQUIRED_PAN_TOO_EXTREME;
        } else if (aidlAcquired == AcquiredInfo.TILT_TOO_EXTREME) {
            return BiometricFaceConstants.FACE_ACQUIRED_TILT_TOO_EXTREME;
        } else if (aidlAcquired == AcquiredInfo.ROLL_TOO_EXTREME) {
            return BiometricFaceConstants.FACE_ACQUIRED_ROLL_TOO_EXTREME;
        } else if (aidlAcquired == AcquiredInfo.FACE_OBSCURED) {
            return BiometricFaceConstants.FACE_ACQUIRED_FACE_OBSCURED;
        } else if (aidlAcquired == AcquiredInfo.START) {
            return BiometricFaceConstants.FACE_ACQUIRED_START;
        } else if (aidlAcquired == AcquiredInfo.SENSOR_DIRTY) {
            return BiometricFaceConstants.FACE_ACQUIRED_SENSOR_DIRTY;
        } else if (aidlAcquired == AcquiredInfo.VENDOR) {
            return BiometricFaceConstants.FACE_ACQUIRED_VENDOR;
        } else if (aidlAcquired == AcquiredInfo.FIRST_FRAME_RECEIVED) {
            // No framework constant available
            return BiometricFaceConstants.FACE_ACQUIRED_UNKNOWN;
        } else if (aidlAcquired == AcquiredInfo.DARK_GLASSES_DETECTED) {
            // No framework constant available
            return BiometricFaceConstants.FACE_ACQUIRED_UNKNOWN;
        } else if (aidlAcquired == AcquiredInfo.MOUTH_COVERING_DETECTED) {
            // No framework constant available
            return BiometricFaceConstants.FACE_ACQUIRED_UNKNOWN;
        } else {
            return BiometricFaceConstants.FACE_ACQUIRED_UNKNOWN;
        }
    }

    @NonNull
    public static FaceAuthenticationFrame toFrameworkAuthenticationFrame(
            @NonNull AuthenticationFrame frame) {
        return new FaceAuthenticationFrame(toFrameworkBaseFrame(frame.data));
    }

    @NonNull
    public static FaceEnrollFrame toFrameworkEnrollmentFrame(@NonNull EnrollmentFrame frame) {
        return new FaceEnrollFrame(toFrameworkCell(frame.cell), frame.stage,
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
}
