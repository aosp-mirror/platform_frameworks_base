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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.AuthenticateReason;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.hardware.biometrics.common.WakeReason;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.util.stream.Stream;

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
    public void acquired(OperationContextExt operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug,
            int acquiredInfo, int vendorCode, int targetUserId) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ACQUIRED,
                statsModality,
                targetUserId,
                operationContext.isCrypto(),
                statsAction,
                statsClient,
                acquiredInfo,
                vendorCode,
                isDebug,
                -1 /* sensorId */,
                operationContext.getId(),
                sessionType(operationContext.getReason()),
                operationContext.isAod(),
                operationContext.isDisplayOn(),
                operationContext.getDockState(),
                orientationType(operationContext.getOrientation()),
                foldType(operationContext.getFoldState()),
                operationContext.getOrderAndIncrement(),
                toProtoWakeReason(operationContext));
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_AUTHENTICATED}. */
    public void authenticate(OperationContextExt operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug, long latency,
            int authState, boolean requireConfirmation, int targetUserId, float ambientLightLux) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED,
                statsModality,
                targetUserId,
                operationContext.isCrypto(),
                statsClient,
                requireConfirmation,
                authState,
                sanitizeLatency(latency),
                isDebug,
                -1 /* sensorId */,
                ambientLightLux,
                operationContext.getId(),
                sessionType(operationContext.getReason()),
                operationContext.isAod(),
                operationContext.isDisplayOn(),
                operationContext.getDockState(),
                orientationType(operationContext.getOrientation()),
                foldType(operationContext.getFoldState()),
                operationContext.getOrderAndIncrement(),
                toProtoWakeReason(operationContext),
                toProtoWakeReasonDetails(operationContext),
                operationContext.getIsMandatoryBiometrics());
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_AUTHENTICATED}. */
    public void authenticate(OperationContextExt operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug, long latency,
            int authState, boolean requireConfirmation, int targetUserId, ALSProbe alsProbe) {
        alsProbe.awaitNextLux((ambientLightLux) -> {
            authenticate(operationContext, statsModality, statsAction, statsClient, isDebug,
                    latency, authState, requireConfirmation, targetUserId, ambientLightLux);
        }, null /* handler */);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_ENROLLED}. */
    public void enroll(int statsModality, int statsAction, int statsClient,
            int targetUserId, long latency, boolean enrollSuccessful, float ambientLightLux,
            int source) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ENROLLED,
                statsModality,
                targetUserId,
                sanitizeLatency(latency),
                enrollSuccessful,
                -1, /* sensorId */
                ambientLightLux,
                source);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED}. */
    public void error(OperationContextExt operationContext,
            int statsModality, int statsAction, int statsClient, boolean isDebug, long latency,
            int error, int vendorCode, int targetUserId) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED,
                statsModality,
                targetUserId,
                operationContext.isCrypto(),
                statsAction,
                statsClient,
                error,
                vendorCode,
                isDebug,
                sanitizeLatency(latency),
                -1 /* sensorId */,
                operationContext.getId(),
                sessionType(operationContext.getReason()),
                operationContext.isAod(),
                operationContext.isDisplayOn(),
                operationContext.getDockState(),
                orientationType(operationContext.getOrientation()),
                foldType(operationContext.getFoldState()),
                operationContext.getOrderAndIncrement(),
                toProtoWakeReason(operationContext),
                toProtoWakeReasonDetails(operationContext),
                operationContext.getIsMandatoryBiometrics());
    }

    @VisibleForTesting
    static int[] toProtoWakeReasonDetails(@NonNull OperationContextExt operationContext) {
        final OperationContext ctx = operationContext.toAidlContext();
        return Stream.of(toProtoWakeReasonDetails(ctx.authenticateReason))
                .mapToInt(i -> i)
                .filter(i -> i != BiometricsProtoEnums.DETAILS_UNKNOWN)
                .toArray();
    }

    @VisibleForTesting
    static int toProtoWakeReason(@NonNull OperationContextExt operationContext) {
        @WakeReason final int reason = operationContext.getWakeReason();
        switch (reason) {
            case WakeReason.POWER_BUTTON:
                return BiometricsProtoEnums.WAKE_REASON_POWER_BUTTON;
            case WakeReason.GESTURE:
                return BiometricsProtoEnums.WAKE_REASON_GESTURE;
            case WakeReason.WAKE_KEY:
                return BiometricsProtoEnums.WAKE_REASON_WAKE_KEY;
            case WakeReason.WAKE_MOTION:
                return BiometricsProtoEnums.WAKE_REASON_WAKE_MOTION;
            case WakeReason.LID:
                return BiometricsProtoEnums.WAKE_REASON_LID;
            case WakeReason.DISPLAY_GROUP_ADDED:
                return BiometricsProtoEnums.WAKE_REASON_DISPLAY_GROUP_ADDED;
            case WakeReason.TAP:
                return BiometricsProtoEnums.WAKE_REASON_TAP;
            case WakeReason.LIFT:
                return BiometricsProtoEnums.WAKE_REASON_LIFT;
            case WakeReason.BIOMETRIC:
                return BiometricsProtoEnums.WAKE_REASON_BIOMETRIC;
            default:
                return BiometricsProtoEnums.WAKE_REASON_UNKNOWN;
        }
    }

    private static int toProtoWakeReasonDetails(@Nullable AuthenticateReason reason) {
        if (reason != null) {
            switch (reason.getTag()) {
                case AuthenticateReason.faceAuthenticateReason:
                    return toProtoWakeReasonDetailsFromFace(reason.getFaceAuthenticateReason());
            }
        }
        return BiometricsProtoEnums.DETAILS_UNKNOWN;
    }

    private static int toProtoWakeReasonDetailsFromFace(@AuthenticateReason.Face int reason) {
        switch (reason) {
            case AuthenticateReason.Face.STARTED_WAKING_UP:
                return BiometricsProtoEnums.DETAILS_FACE_STARTED_WAKING_UP;
            case AuthenticateReason.Face.PRIMARY_BOUNCER_SHOWN:
                return BiometricsProtoEnums.DETAILS_FACE_PRIMARY_BOUNCER_SHOWN;
            case AuthenticateReason.Face.ASSISTANT_VISIBLE:
                return BiometricsProtoEnums.DETAILS_FACE_ASSISTANT_VISIBLE;
            case AuthenticateReason.Face.ALTERNATE_BIOMETRIC_BOUNCER_SHOWN:
                return BiometricsProtoEnums.DETAILS_FACE_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN;
            case AuthenticateReason.Face.NOTIFICATION_PANEL_CLICKED:
                return BiometricsProtoEnums.DETAILS_FACE_NOTIFICATION_PANEL_CLICKED;
            case AuthenticateReason.Face.OCCLUDING_APP_REQUESTED:
                return BiometricsProtoEnums.DETAILS_FACE_OCCLUDING_APP_REQUESTED;
            case AuthenticateReason.Face.PICK_UP_GESTURE_TRIGGERED:
                return BiometricsProtoEnums.DETAILS_FACE_PICK_UP_GESTURE_TRIGGERED;
            case AuthenticateReason.Face.QS_EXPANDED:
                return BiometricsProtoEnums.DETAILS_FACE_QS_EXPANDED;
            case AuthenticateReason.Face.SWIPE_UP_ON_BOUNCER:
                return BiometricsProtoEnums.DETAILS_FACE_SWIPE_UP_ON_BOUNCER;
            case AuthenticateReason.Face.UDFPS_POINTER_DOWN:
                return BiometricsProtoEnums.DETAILS_FACE_UDFPS_POINTER_DOWN;
            default:
                return BiometricsProtoEnums.DETAILS_UNKNOWN;
        }
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

    /** {@see FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED}. */
    public void reportFingerprintsLoe(int statsModality) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                statsModality,
                BiometricsProtoEnums.ISSUE_FINGERPRINTS_LOE,
                -1 /* sensorId */);
    }

    /** {@see FrameworkStatsLog.BIOMETRIC_FRR_NOTIFICATION}. */
    public void logFrameworkNotification(int action, int modality) {
        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_FRR_NOTIFICATION,
                action, modality);
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

    private static int orientationType(@Surface.Rotation int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return BiometricsProtoEnums.ORIENTATION_0;
            case Surface.ROTATION_90:
                return BiometricsProtoEnums.ORIENTATION_90;
            case Surface.ROTATION_180:
                return BiometricsProtoEnums.ORIENTATION_180;
            case Surface.ROTATION_270:
                return BiometricsProtoEnums.ORIENTATION_270;
        }
        return BiometricsProtoEnums.ORIENTATION_UNKNOWN;
    }

    private static int foldType(int foldType) {
        switch (foldType) {
            case IBiometricContextListener.FoldState.FULLY_CLOSED:
                return BiometricsProtoEnums.FOLD_CLOSED;
            case IBiometricContextListener.FoldState.FULLY_OPENED:
                return BiometricsProtoEnums.FOLD_OPEN;
            case IBiometricContextListener.FoldState.HALF_OPENED:
                return BiometricsProtoEnums.FOLD_HALF_OPEN;
        }
        return BiometricsProtoEnums.FOLD_UNKNOWN;
    }
}
