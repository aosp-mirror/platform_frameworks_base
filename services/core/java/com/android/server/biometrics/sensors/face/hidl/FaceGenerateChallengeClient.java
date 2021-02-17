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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.GenerateChallengeClient;

/**
 * Face-specific generateChallenge client supporting the
 * {@link android.hardware.biometrics.face.V1_0} HIDL interface.
 */
public class FaceGenerateChallengeClient extends GenerateChallengeClient<IBiometricsFace> {

    private static final String TAG = "FaceGenerateChallengeClient";
    private static final int CHALLENGE_TIMEOUT_SEC = 600; // 10 minutes

    // If `this` FaceGenerateChallengeClient was invoked while an existing in-flight challenge
    // was not revoked yet, store a reference to the interrupted client here. Notify the interrupted
    // client when `this` challenge is revoked.
    @Nullable private final FaceGenerateChallengeClient mInterruptedClient;

    FaceGenerateChallengeClient(@NonNull Context context,
            @NonNull LazyDaemon<IBiometricsFace> lazyDaemon, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, @NonNull String owner, int sensorId,
            @Nullable FaceGenerateChallengeClient interruptedClient) {
        super(context, lazyDaemon, token, listener, owner, sensorId);
        mInterruptedClient = interruptedClient;
    }

    @Nullable
    public FaceGenerateChallengeClient getInterruptedClient() {
        return mInterruptedClient;
    }

    @Override
    protected void startHalOperation() {
        try {
            final long challenge = getFreshDaemon().generateChallenge(CHALLENGE_TIMEOUT_SEC).value;
            try {
                getListener().onChallengeGenerated(getSensorId(), challenge);
                mCallback.onClientFinished(this, true /* success */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "generateChallenge failed", e);
        }
    }
}
