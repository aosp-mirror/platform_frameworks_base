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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.GenerateChallengeClient;

import java.util.function.Supplier;

/**
 * Fingerprint-specific generateChallenge client for the {@link IFingerprint} AIDL HAL interface.
 */
public class FingerprintGenerateChallengeClient extends GenerateChallengeClient<AidlSession> {
    private static final String TAG = "FingerprintGenerateChallengeClient";

    public FingerprintGenerateChallengeClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener,
            int userId, @NonNull String owner, int sensorId,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext) {
        super(context, lazyDaemon, token, listener, userId, owner, sensorId,
                biometricLogger, biometricContext);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().getSession().generateChallenge();
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to generateChallenge", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    void onChallengeGenerated(int sensorId, int userId, long challenge) {
        try {
            final ClientMonitorCallbackConverter listener = getListener();
            listener.onChallengeGenerated(sensorId, userId, challenge);
            mCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send challenge", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }
}
