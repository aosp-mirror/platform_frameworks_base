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

package com.android.server.biometrics.log;

import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

/**
 * Wrapper for {@link FrameworkStatsLog} to isolate the testable parts.
 */
public class BiometricFrameworkStatsLogger {

    private static final String TAG = "BiometricFrameworkStatsLogger";

    private static final BiometricFrameworkStatsLogger sInstance =
            new BiometricFrameworkStatsLogger();

    private BiometricFrameworkStatsLogger() {}

    /** Shared instance. */
    public static BiometricFrameworkStatsLogger getInstance() {
        return sInstance;
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_ACQUIRED}. */
    public void acquired(OperationContext operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug,
            int acquiredInfo, int vendorCode, int targetUserId) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ACQUIRED,
                statsModality,
                targetUserId,
                operationContext.isCrypto,
                statsAction,
                statsClient,
                acquiredInfo,
                vendorCode,
                isDebug,
                -1 /* sensorId */,
                operationContext.id,
                sessionType(operationContext.reason),
                operationContext.isAod);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_AUTHENTICATED}. */
    public void authenticate(OperationContext operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug, long latency,
            int authState, boolean requireConfirmation,
            int targetUserId, float ambientLightLux) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED,
                statsModality,
                targetUserId,
                operationContext.isCrypto,
                statsClient,
                requireConfirmation,
                authState,
                sanitizeLatency(latency),
                isDebug,
                -1 /* sensorId */,
                ambientLightLux,
                operationContext.id,
                sessionType(operationContext.reason),
                operationContext.isAod);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_ENROLLED}. */
    public void enroll(int statsModality, int statsAction, int statsClient,
            int targetUserId, long latency, boolean enrollSuccessful, float ambientLightLux) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ENROLLED,
                statsModality,
                targetUserId,
                sanitizeLatency(latency),
                enrollSuccessful,
                -1, /* sensorId */
                ambientLightLux);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED}. */
    public void error(OperationContext operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug, long latency,
            int error, int vendorCode, int targetUserId) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED,
                statsModality,
                targetUserId,
                operationContext.isCrypto,
                statsAction,
                statsClient,
                error,
                vendorCode,
                isDebug,
                sanitizeLatency(latency),
                -1 /* sensorId */,
                operationContext.id,
                sessionType(operationContext.reason),
                operationContext.isAod);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED}. */
    public void reportUnknownTemplateEnrolledHal(int statsModality) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                statsModality,
                BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_HAL,
                -1 /* sensorId */);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED}. */
    public void reportUnknownTemplateEnrolledFramework(int statsModality) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                statsModality,
                BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_FRAMEWORK,
                -1 /* sensorId */);
    }

    private long sanitizeLatency(long latency) {
        if (latency < 0) {
            Slog.w(TAG, "found a negative latency : " + latency);
            return -1;
        }
        return latency;
    }

    private static int sessionType(@OperationReason byte reason) {
        if (reason == OperationReason.BIOMETRIC_PROMPT) {
            return BiometricsProtoEnums.SESSION_TYPE_BIOMETRIC_PROMPT;
        }
        if (reason == OperationReason.KEYGUARD) {
            return BiometricsProtoEnums.SESSION_TYPE_KEYGUARD_ENTRY;
        }
        return BiometricsProtoEnums.SESSION_TYPE_UNKNOWN;
    }
}
