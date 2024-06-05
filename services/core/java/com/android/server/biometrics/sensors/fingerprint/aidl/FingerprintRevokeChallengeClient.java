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
import com.android.server.biometrics.sensors.RevokeChallengeClient;

import java.util.function.Supplier;

/**
 * Fingerprint-specific revokeChallenge client for the {@link IFingerprint} AIDL HAL interface.
 */
public class FingerprintRevokeChallengeClient extends RevokeChallengeClient<AidlSession> {

    private static final String TAG = "FingerprintRevokeChallengeClient";

    private final long mChallenge;

    public FingerprintRevokeChallengeClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon, @NonNull IBinder token,
            int userId, @NonNull String owner, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            long challenge) {
        super(context, lazyDaemon, token, userId, owner, sensorId, logger, biometricContext);
        mChallenge = challenge;
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().getSession().revokeChallenge(mChallenge);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to revokeChallenge", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    void onChallengeRevoked(long challenge) {
        final boolean success = challenge == mChallenge;
        mCallback.onClientFinished(FingerprintRevokeChallengeClient.this, success);
    }
}
