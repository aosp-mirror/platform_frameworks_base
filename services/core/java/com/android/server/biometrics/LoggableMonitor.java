/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.face.FaceManager;
import android.util.Slog;
import android.util.StatsLog;

/**
 * Abstract class that adds logging functionality to the ClientMonitor classes.
 */
public abstract class LoggableMonitor {

    public static final String TAG = "BiometricStats";
    public static final boolean DEBUG = false;

    private long mFirstAcquireTimeMs;

    /**
     * Only valid for AuthenticationClient.
     * @return true if the client is authenticating for a crypto operation.
     */
    protected boolean isCryptoOperation() {
        return false;
    }

    /**
     * @return One of {@link BiometricsProtoEnums} MODALITY_* constants.
     */
    protected abstract int statsModality();

    /**
     * Action == enroll, authenticate, remove, enumerate.
     * @return One of {@link BiometricsProtoEnums} ACTION_* constants.
     */
    protected abstract int statsAction();

    /**
     * Only matters for AuthenticationClient. Should only be overridden in
     * {@link BiometricServiceBase}, which determines if a client is for BiometricPrompt, Keyguard,
     * etc.
     * @return one of {@link BiometricsProtoEnums} CLIENT_* constants.
     */
    protected int statsClient() {
        return BiometricsProtoEnums.CLIENT_UNKNOWN;
    }

    protected final void logOnAcquired(Context context, int acquiredInfo, int vendorCode,
            int targetUserId) {
        if (statsModality() == BiometricsProtoEnums.MODALITY_FACE) {
            if (acquiredInfo == FaceManager.FACE_ACQUIRED_START) {
                mFirstAcquireTimeMs = System.currentTimeMillis();
            }
        } else if (acquiredInfo == BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
            if (mFirstAcquireTimeMs == 0) {
                mFirstAcquireTimeMs = System.currentTimeMillis();
            }
        }
        if (DEBUG) {
            Slog.v(TAG, "Acquired! Modality: " + statsModality()
                    + ", User: " + targetUserId
                    + ", IsCrypto: " + isCryptoOperation()
                    + ", Action: " + statsAction()
                    + ", Client: " + statsClient()
                    + ", AcquiredInfo: " + acquiredInfo
                    + ", VendorCode: " + vendorCode);
        }
        StatsLog.write(StatsLog.BIOMETRIC_ACQUIRED,
                statsModality(),
                targetUserId,
                isCryptoOperation(),
                statsAction(),
                statsClient(),
                acquiredInfo,
                0 /* vendorCode */, // Don't log vendorCode for now
                Utils.isDebugEnabled(context, targetUserId));
    }

    protected final void logOnError(Context context, int error, int vendorCode, int targetUserId) {
        if (DEBUG) {
            Slog.v(TAG, "Error! Modality: " + statsModality()
                    + ", User: " + targetUserId
                    + ", IsCrypto: " + isCryptoOperation()
                    + ", Action: " + statsAction()
                    + ", Client: " + statsClient()
                    + ", Error: " + error
                    + ", VendorCode: " + vendorCode);
        }
        StatsLog.write(StatsLog.BIOMETRIC_ERROR_OCCURRED,
                statsModality(),
                targetUserId,
                isCryptoOperation(),
                statsAction(),
                statsClient(),
                error,
                vendorCode,
                Utils.isDebugEnabled(context, targetUserId));
    }

    protected final void logOnAuthenticated(Context context, boolean authenticated,
            boolean requireConfirmation, int targetUserId, boolean isBiometricPrompt) {
        int authState = StatsLog.BIOMETRIC_AUTHENTICATED__STATE__UNKNOWN;
        if (!authenticated) {
            authState = StatsLog.BIOMETRIC_AUTHENTICATED__STATE__REJECTED;
        } else {
            // Authenticated
            if (isBiometricPrompt && requireConfirmation) {
                authState = StatsLog.BIOMETRIC_AUTHENTICATED__STATE__PENDING_CONFIRMATION;
            } else {
                authState = StatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED;
            }
        }

        // Only valid if we have a first acquired time, otherwise set to -1
        final long latency = mFirstAcquireTimeMs != 0
                ? (System.currentTimeMillis() - mFirstAcquireTimeMs)
                : -1;

        if (DEBUG) {
            Slog.v(TAG, "Authenticated! Modality: " + statsModality()
                    + ", User: " + targetUserId
                    + ", IsCrypto: " + isCryptoOperation()
                    + ", Client: " + statsClient()
                    + ", RequireConfirmation: " + requireConfirmation
                    + ", State: " + authState
                    + ", Latency: " + latency);
        } else {
            Slog.v(TAG, "Authentication latency: " + latency);
        }

        StatsLog.write(StatsLog.BIOMETRIC_AUTHENTICATED,
                statsModality(),
                targetUserId,
                isCryptoOperation(),
                statsClient(),
                requireConfirmation,
                authState,
                latency,
                Utils.isDebugEnabled(context, targetUserId));
    }

    protected final void logOnEnrolled(int targetUserId, long latency, boolean enrollSuccessful) {
        if (DEBUG) {
            Slog.v(TAG, "Enrolled! Modality: " + statsModality()
                    + ", User: " + targetUserId
                    + ", Client: " + statsClient()
                    + ", Latency: " + latency
                    + ", Success: " + enrollSuccessful);
        } else {
            Slog.v(TAG, "Enroll latency: " + latency);
        }

        StatsLog.write(StatsLog.BIOMETRIC_ENROLLED,
                statsModality(),
                targetUserId,
                latency,
                enrollSuccessful);
    }

}
