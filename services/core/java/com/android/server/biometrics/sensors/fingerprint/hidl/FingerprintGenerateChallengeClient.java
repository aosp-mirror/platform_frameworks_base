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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.GenerateChallengeClient;

/**
 * Fingerprint-specific generateChallenge/preEnroll client supporting the
 * {@link android.hardware.biometrics.fingerprint.V2_1} and
 * {@link android.hardware.biometrics.fingerprint.V2_2} HIDL interfaces.
 */
public class FingerprintGenerateChallengeClient
        extends GenerateChallengeClient<IBiometricsFingerprint> {

    private static final String TAG = "FingerprintGenerateChallengeClient";

    FingerprintGenerateChallengeClient(@NonNull Context context,
            @NonNull LazyDaemon<IBiometricsFingerprint> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int userId, @NonNull String owner,
            int sensorId) {
        super(context, lazyDaemon, token, listener, userId, owner, sensorId);
    }

    @Override
    protected void startHalOperation() {
        try {
            final long challenge = getFreshDaemon().preEnroll();
            try {
                getListener().onChallengeGenerated(getSensorId(), getTargetUserId(), challenge);
                mCallback.onClientFinished(this, true /* success */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "preEnroll failed", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }
}
