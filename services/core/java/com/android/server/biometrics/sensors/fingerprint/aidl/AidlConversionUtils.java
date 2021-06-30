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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.fingerprint.AcquiredInfo;
import android.hardware.biometrics.fingerprint.Error;

/**
 * Utilities for converting from hardware to framework-defined AIDL models.
 */
final class AidlConversionUtils {
    // Prevent instantiation.
    private AidlConversionUtils() {
    }

    public static @BiometricFingerprintConstants.FingerprintError int toFrameworkError(
            byte aidlError) {
        if (aidlError == Error.UNKNOWN) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_UNKNOWN;
        } else if (aidlError == Error.HW_UNAVAILABLE) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE;
        } else if (aidlError == Error.UNABLE_TO_PROCESS) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_PROCESS;
        } else if (aidlError == Error.TIMEOUT) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_TIMEOUT;
        } else if (aidlError == Error.NO_SPACE) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_NO_SPACE;
        } else if (aidlError == Error.CANCELED) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_CANCELED;
        } else if (aidlError == Error.UNABLE_TO_REMOVE) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_UNABLE_TO_REMOVE;
        } else if (aidlError == Error.VENDOR) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_VENDOR;
        } else if (aidlError == Error.BAD_CALIBRATION) {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_BAD_CALIBRATION;
        } else {
            return BiometricFingerprintConstants.FINGERPRINT_ERROR_UNKNOWN;
        }
    }

    public static @BiometricFingerprintConstants.FingerprintAcquired int toFrameworkAcquiredInfo(
            byte aidlAcquiredInfo) {
        if (aidlAcquiredInfo == AcquiredInfo.UNKNOWN) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN;
        } else if (aidlAcquiredInfo == AcquiredInfo.GOOD) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
        } else if (aidlAcquiredInfo == AcquiredInfo.PARTIAL) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_PARTIAL;
        } else if (aidlAcquiredInfo == AcquiredInfo.INSUFFICIENT) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_INSUFFICIENT;
        } else if (aidlAcquiredInfo == AcquiredInfo.SENSOR_DIRTY) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_IMAGER_DIRTY;
        } else if (aidlAcquiredInfo == AcquiredInfo.TOO_SLOW) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_TOO_SLOW;
        } else if (aidlAcquiredInfo == AcquiredInfo.TOO_FAST) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_TOO_FAST;
        } else if (aidlAcquiredInfo == AcquiredInfo.VENDOR) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;
        } else if (aidlAcquiredInfo == AcquiredInfo.START) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
        } else if (aidlAcquiredInfo == AcquiredInfo.TOO_DARK) {
            // No framework constant available
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN;
        } else if (aidlAcquiredInfo == AcquiredInfo.TOO_BRIGHT) {
            // No framework constant available
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN;
        } else if (aidlAcquiredInfo == AcquiredInfo.IMMOBILE) {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_IMMOBILE;
        } else if (aidlAcquiredInfo == AcquiredInfo.RETRYING_CAPTURE) {
            // No framework constant available
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN;
        } else {
            return BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN;
        }
    }
}
