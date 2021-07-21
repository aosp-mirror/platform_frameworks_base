/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.biometrics.Utils;

/**
 * Abstract class that adds logging functionality to the ClientMonitor classes.
 */
public abstract class LoggableMonitor {

    public static final String TAG = "Biometrics/LoggableMonitor";
    public static final boolean DEBUG = false;

    final int mStatsModality;
    private final int mStatsAction;
    private final int mStatsClient;
    @NonNull private final SensorManager mSensorManager;
    private long mFirstAcquireTimeMs;
    private boolean mLightSensorEnabled = false;
    private boolean mShouldLogMetrics = true;

    // report only the most recent value
    // consider com.android.server.display.utils.AmbientFilter or similar if need arises
    private volatile float mLastAmbientLux = 0;

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastAmbientLux = event.values[0];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    /**
     * @param context system_server context
     * @param statsModality One of {@link BiometricsProtoEnums} MODALITY_* constants.
     * @param statsAction One of {@link BiometricsProtoEnums} ACTION_* constants.
     * @param statsClient One of {@link BiometricsProtoEnums} CLIENT_* constants.
     */
    public LoggableMonitor(@NonNull Context context, int statsModality, int statsAction,
            int statsClient) {
        mStatsModality = statsModality;
        mStatsAction = statsAction;
        mStatsClient = statsClient;
        mSensorManager = context.getSystemService(SensorManager.class);
    }

    /**
     * Only valid for AuthenticationClient.
     * @return true if the client is authenticating for a crypto operation.
     */
    protected boolean isCryptoOperation() {
        return false;
    }

    protected void setShouldLog(boolean shouldLog) {
        mShouldLogMetrics = shouldLog;
    }

    public int getStatsClient() {
        return mStatsClient;
    }

    private boolean shouldSkipLogging() {
        boolean shouldSkipLogging = (mStatsModality == BiometricsProtoEnums.MODALITY_UNKNOWN
                || mStatsAction == BiometricsProtoEnums.ACTION_UNKNOWN);

        if (mStatsModality == BiometricsProtoEnums.MODALITY_UNKNOWN) {
            Slog.w(TAG, "Unknown field detected: MODALITY_UNKNOWN, will not report metric");
        }

        if (mStatsAction == BiometricsProtoEnums.ACTION_UNKNOWN) {
            Slog.w(TAG, "Unknown field detected: ACTION_UNKNOWN, will not report metric");
        }

        if (mStatsClient == BiometricsProtoEnums.CLIENT_UNKNOWN) {
            Slog.w(TAG, "Unknown field detected: CLIENT_UNKNOWN");
        }

        return shouldSkipLogging;
    }

    protected final void logOnAcquired(Context context, int acquiredInfo, int vendorCode,
            int targetUserId) {
        if (!mShouldLogMetrics) {
            return;
        }

        final boolean isFace = mStatsModality == BiometricsProtoEnums.MODALITY_FACE;
        final boolean isFingerprint = mStatsModality == BiometricsProtoEnums.MODALITY_FINGERPRINT;
        if (isFace || isFingerprint) {
            if ((isFingerprint && acquiredInfo == FingerprintManager.FINGERPRINT_ACQUIRED_START)
                    || (isFace && acquiredInfo == FaceManager.FACE_ACQUIRED_START)) {
                mFirstAcquireTimeMs = System.currentTimeMillis();
            }
        } else if (acquiredInfo == BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
            if (mFirstAcquireTimeMs == 0) {
                mFirstAcquireTimeMs = System.currentTimeMillis();
            }
        }
        if (DEBUG) {
            Slog.v(TAG, "Acquired! Modality: " + mStatsModality
                    + ", User: " + targetUserId
                    + ", IsCrypto: " + isCryptoOperation()
                    + ", Action: " + mStatsAction
                    + ", Client: " + mStatsClient
                    + ", AcquiredInfo: " + acquiredInfo
                    + ", VendorCode: " + vendorCode);
        }

        if (shouldSkipLogging()) {
            return;
        }

        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ACQUIRED,
                mStatsModality,
                targetUserId,
                isCryptoOperation(),
                mStatsAction,
                mStatsClient,
                acquiredInfo,
                vendorCode,
                Utils.isDebugEnabled(context, targetUserId),
                -1 /* sensorId */);
    }

    protected final void logOnError(Context context, int error, int vendorCode, int targetUserId) {
        if (!mShouldLogMetrics) {
            return;
        }

        final long latency = mFirstAcquireTimeMs != 0
                ? (System.currentTimeMillis() - mFirstAcquireTimeMs) : -1;

        if (DEBUG) {
            Slog.v(TAG, "Error! Modality: " + mStatsModality
                    + ", User: " + targetUserId
                    + ", IsCrypto: " + isCryptoOperation()
                    + ", Action: " + mStatsAction
                    + ", Client: " + mStatsClient
                    + ", Error: " + error
                    + ", VendorCode: " + vendorCode
                    + ", Latency: " + latency);
        } else {
            Slog.v(TAG, "Error latency: " + latency);
        }

        if (shouldSkipLogging()) {
            return;
        }

        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ERROR_OCCURRED,
                mStatsModality,
                targetUserId,
                isCryptoOperation(),
                mStatsAction,
                mStatsClient,
                error,
                vendorCode,
                Utils.isDebugEnabled(context, targetUserId),
                sanitizeLatency(latency),
                -1 /* sensorId */);
    }

    protected final void logOnAuthenticated(Context context, boolean authenticated,
            boolean requireConfirmation, int targetUserId, boolean isBiometricPrompt) {
        if (!mShouldLogMetrics) {
            return;
        }

        int authState = FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__UNKNOWN;
        if (!authenticated) {
            authState = FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__REJECTED;
        } else {
            // Authenticated
            if (isBiometricPrompt && requireConfirmation) {
                authState = FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__PENDING_CONFIRMATION;
            } else {
                authState = FrameworkStatsLog.BIOMETRIC_AUTHENTICATED__STATE__CONFIRMED;
            }
        }

        // Only valid if we have a first acquired time, otherwise set to -1
        final long latency = mFirstAcquireTimeMs != 0
                ? (System.currentTimeMillis() - mFirstAcquireTimeMs)
                : -1;

        if (DEBUG) {
            Slog.v(TAG, "Authenticated! Modality: " + mStatsModality
                    + ", User: " + targetUserId
                    + ", IsCrypto: " + isCryptoOperation()
                    + ", Client: " + mStatsClient
                    + ", RequireConfirmation: " + requireConfirmation
                    + ", State: " + authState
                    + ", Latency: " + latency
                    + ", Lux: " + mLastAmbientLux);
        } else {
            Slog.v(TAG, "Authentication latency: " + latency);
        }

        if (shouldSkipLogging()) {
            return;
        }

        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_AUTHENTICATED,
                mStatsModality,
                targetUserId,
                isCryptoOperation(),
                mStatsClient,
                requireConfirmation,
                authState,
                sanitizeLatency(latency),
                Utils.isDebugEnabled(context, targetUserId),
                -1 /* sensorId */,
                mLastAmbientLux /* ambientLightLux */);
    }

    protected final void logOnEnrolled(int targetUserId, long latency, boolean enrollSuccessful) {
        if (!mShouldLogMetrics) {
            return;
        }

        if (DEBUG) {
            Slog.v(TAG, "Enrolled! Modality: " + mStatsModality
                    + ", User: " + targetUserId
                    + ", Client: " + mStatsClient
                    + ", Latency: " + latency
                    + ", Lux: " + mLastAmbientLux
                    + ", Success: " + enrollSuccessful);
        } else {
            Slog.v(TAG, "Enroll latency: " + latency);
        }

        if (shouldSkipLogging()) {
            return;
        }

        FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_ENROLLED,
                mStatsModality,
                targetUserId,
                sanitizeLatency(latency),
                enrollSuccessful,
                -1, /* sensorId */
                mLastAmbientLux /* ambientLightLux */);
    }

    private long sanitizeLatency(long latency) {
        if (latency < 0) {
            Slog.w(TAG, "found a negative latency : " + latency);
            return -1;
        }
        return latency;
    }

    /** Get a callback to start/stop ALS capture when client runs. */
    @NonNull
    protected BaseClientMonitor.Callback createALSCallback() {
        return new BaseClientMonitor.Callback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                setLightSensorLoggingEnabled(getAmbientLightSensor(mSensorManager));
            }

            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                setLightSensorLoggingEnabled(null);
            }
        };
    }

    /** The sensor to use for ALS logging. */
    @Nullable
    protected Sensor getAmbientLightSensor(@NonNull SensorManager sensorManager) {
        return mShouldLogMetrics ? sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
    }

    private void setLightSensorLoggingEnabled(@Nullable Sensor lightSensor) {
        if (DEBUG) {
            Slog.v(TAG, "capturing ambient light using: "
                    + (lightSensor != null ? lightSensor : "[disabled]"));
        }

        if (lightSensor != null) {
            if (!mLightSensorEnabled) {
                mLightSensorEnabled = true;
                mLastAmbientLux = 0;
                mSensorManager.registerListener(mLightSensorListener, lightSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
            }
        } else {
            mLightSensorEnabled = false;
            mLastAmbientLux = 0;
            mSensorManager.unregisterListener(mLightSensorListener);
        }
    }
}
