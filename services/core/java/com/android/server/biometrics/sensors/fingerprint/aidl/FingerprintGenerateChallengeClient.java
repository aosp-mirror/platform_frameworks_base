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
import android.hardware.biometrics.fingerprint.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.GenerateChallengeClient;

/**
 * Fingerprint-specific generateChallenge client for the {@link IFingerprint} AIDL HAL interface.
 */
class FingerprintGenerateChallengeClient extends GenerateChallengeClient<ISession> {
    private static final String TAG = "FingerprintGenerateChallengeClient";

    FingerprintGenerateChallengeClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon,
            @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener,
            @NonNull String owner, int sensorId) {
        super(context, lazyDaemon, token, listener, owner, sensorId);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().generateChallenge(mSequentialId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to generateChallenge", e);
        }
    }

    void onChallengeGenerated(int sensorId, int userId, long challenge) {
        try {
            getListener().onChallengeGenerated(sensorId, challenge);
            mCallback.onClientFinished(FingerprintGenerateChallengeClient.this,
                    true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send challenge", e);
            mCallback.onClientFinished(FingerprintGenerateChallengeClient.this,
                    false /* success */);
        }
    }
}
