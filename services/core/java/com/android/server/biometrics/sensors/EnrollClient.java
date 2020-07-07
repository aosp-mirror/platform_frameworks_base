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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Arrays;

/**
 * A class to keep track of the enrollment state for a given client.
 */
public abstract class EnrollClient<T> extends AcquisitionClient<T> {

    private static final String TAG = "Biometrics/EnrollClient";

    protected final byte[] mHardwareAuthToken;
    protected final int mTimeoutSec;
    private final BiometricUtils mBiometricUtils;
    private final boolean mShouldVibrate;

    private long mEnrollmentStartTimeMs;
    private boolean mAlreadyCancelled;

    public EnrollClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner, @NonNull BiometricUtils utils,
            int timeoutSec, int statsModality, int sensorId,
            boolean shouldVibrate) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_ENROLL,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricUtils = utils;
        mHardwareAuthToken = Arrays.copyOf(hardwareAuthToken, hardwareAuthToken.length);
        mTimeoutSec = timeoutSec;
        mShouldVibrate = shouldVibrate;
    }

    public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (mShouldVibrate) {
            vibrateSuccess();
        }

        final ClientMonitorCallbackConverter listener = getListener();
        try {
            if (listener != null) {
                listener.onEnrollResult(identifier, remaining);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }

        if (remaining == 0) {
            mBiometricUtils.addBiometricForUser(getContext(), getTargetUserId(), identifier);
            logOnEnrolled(getTargetUserId(), System.currentTimeMillis() - mEnrollmentStartTimeMs,
                    true /* enrollSuccessful */);
            mFinishCallback.onClientFinished(this, true /* success */);
        }
        notifyUserActivity();
    }

    @Override
    public void start(@NonNull FinishCallback finishCallback) {
        super.start(finishCallback);

        mEnrollmentStartTimeMs = System.currentTimeMillis();
        startHalOperation();
    }

    @Override
    public void cancel() {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopEnroll: already cancelled!");
            return;
        }

        stopHalOperation();
        mAlreadyCancelled = true;
    }

    /**
     * Called when we get notification from the biometric's HAL that an error has occurred with the
     * current operation.
     */
    @Override
    public void onError(int error, int vendorCode) {
        logOnEnrolled(getTargetUserId(), System.currentTimeMillis() - mEnrollmentStartTimeMs,
                false /* enrollSuccessful */);
        super.onError(error, vendorCode);
    }
}
