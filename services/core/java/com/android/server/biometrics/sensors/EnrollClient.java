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
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.fingerprint.FingerprintManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * A class to keep track of the enrollment state for a given client.
 */
public abstract class EnrollClient<T> extends AcquisitionClient<T> implements EnrollmentModifier {

    private static final String TAG = "Biometrics/EnrollClient";

    protected final byte[] mHardwareAuthToken;
    protected final int mTimeoutSec;
    protected final BiometricUtils mBiometricUtils;

    private long mEnrollmentStartTimeMs;
    private final boolean mHasEnrollmentsBeforeStarting;

    /**
     * @return true if the user has already enrolled the maximum number of templates.
     */
    protected abstract boolean hasReachedEnrollmentLimit();

    public EnrollClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner, @NonNull BiometricUtils utils,
            int timeoutSec, int sensorId, boolean shouldVibrate,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                shouldVibrate, logger, biometricContext);
        mBiometricUtils = utils;
        mHardwareAuthToken = Arrays.copyOf(hardwareAuthToken, hardwareAuthToken.length);
        mTimeoutSec = timeoutSec;
        mHasEnrollmentsBeforeStarting = hasEnrollments();
    }

    @Override
    public boolean hasEnrollmentStateChanged() {
        final boolean hasEnrollmentsNow = hasEnrollments();
        return hasEnrollmentsNow != mHasEnrollmentsBeforeStarting;
    }

    @Override
    public boolean hasEnrollments() {
        return !mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId()).isEmpty();
    }

    public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (mShouldVibrate) {
            vibrateSuccess();
        }

        final ClientMonitorCallbackConverter listener = getListener();
        try {
            listener.onEnrollResult(identifier, remaining);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        if (remaining == 0) {
            mBiometricUtils.addBiometricForUser(getContext(), getTargetUserId(), identifier);
            getLogger().logOnEnrolled(getTargetUserId(),
                    System.currentTimeMillis() - mEnrollmentStartTimeMs,
                    true /* enrollSuccessful */);
            mCallback.onClientFinished(this, true /* success */);
        }
        notifyUserActivity();
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        if (hasReachedEnrollmentLimit()) {
            Slog.e(TAG, "Reached enrollment limit");
            callback.onClientFinished(this, false /* success */);
            return;
        }

        mEnrollmentStartTimeMs = System.currentTimeMillis();
        startHalOperation();
    }

    /**
     * Called when we get notification from the biometric's HAL that an error has occurred with the
     * current operation.
     */
    @Override
    public void onError(int error, int vendorCode) {
        getLogger().logOnEnrolled(getTargetUserId(),
                System.currentTimeMillis() - mEnrollmentStartTimeMs,
                false /* enrollSuccessful */);
        super.onError(error, vendorCode);
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_ENROLL;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }

    protected int getRequestReasonFromEnrollReason(@FingerprintManager.EnrollReason int reason) {
        switch (reason) {
            case FingerprintManager.ENROLL_FIND_SENSOR:
                return BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR;
            case FingerprintManager.ENROLL_ENROLL:
                return BiometricRequestConstants.REASON_ENROLL_ENROLLING;
            default:
                return BiometricRequestConstants.REASON_UNKNOWN;
        }
    }
}
