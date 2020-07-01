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

import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

public abstract class GenerateChallengeClient extends ClientMonitor {

    private static final String TAG = "GenerateChallengeClient";

    protected long mChallenge;

    public GenerateChallengeClient(FinishCallback finishCallback, Context context, IBinder token,
            ClientMonitorCallbackConverter listener, String owner, int sensorId) {
        super(finishCallback, context, token, listener, 0 /* userId */, false /* restricted */,
                owner, 0 /* cookie */, sensorId, BiometricsProtoEnums.MODALITY_UNKNOWN,
                BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN);
    }

    @Override
    public void start() {
        startHalOperation();
        try {
            getListener().onChallengeGenerated(mChallenge);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
        mFinishCallback.onClientFinished(this);
    }

    @Override
    protected void stopHalOperation() {
        // Not supported for GenerateChallenge
    }
}
