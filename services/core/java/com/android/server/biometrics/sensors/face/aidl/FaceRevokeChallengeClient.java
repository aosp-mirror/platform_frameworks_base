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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.RevokeChallengeClient;

/**
 * Face-specific revokeChallenge client for the {@link IFace} AIDL HAL interface.
 */
public class FaceRevokeChallengeClient extends RevokeChallengeClient<ISession> {

    private static final String TAG = "FaceRevokeChallengeClient";

    private final long mChallenge;

    FaceRevokeChallengeClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, @NonNull IBinder token,
            int userId, @NonNull String owner, int sensorId, long challenge) {
        super(context, lazyDaemon, token, userId, owner, sensorId);
        mChallenge = challenge;
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().revokeChallenge(mChallenge);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to revokeChallenge", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    void onChallengeRevoked(int sensorId, int userId, long challenge) {
        final boolean success = challenge == mChallenge;
        mCallback.onClientFinished(this, success);
    }
}
