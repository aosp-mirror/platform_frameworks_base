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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

public abstract class GenerateChallengeClient<T> extends ClientMonitor<T> {

    private static final String TAG = "GenerateChallengeClient";

    protected long mChallenge;

    public GenerateChallengeClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            @NonNull String owner, int sensorId) {
        super(context, lazyDaemon, token, listener, 0 /* userId */, owner, 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
    }

    @Override
    public void unableToStart() {
        try {
            getListener().onChallengeGenerated(0L);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send error", e);
        }
    }

    @Override
    public void start(@NonNull FinishCallback finishCallback) {
        super.start(finishCallback);

        startHalOperation();
        try {
            getListener().onChallengeGenerated(mChallenge);
            mFinishCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            mFinishCallback.onClientFinished(this, false /* success */);
        }
    }
}
