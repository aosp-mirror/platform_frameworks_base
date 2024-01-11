/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.face.ISession;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.StopUserClient;

import java.util.function.Supplier;

public class FaceStopUserClient extends StopUserClient<ISession> {
    private static final String TAG = "FaceStopUserClient";

    public FaceStopUserClient(@NonNull Context context, @NonNull Supplier<ISession> lazyDaemon,
            @Nullable IBinder token, int userId, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull UserStoppedCallback callback) {
        super(context, lazyDaemon, token, userId, sensorId, logger, biometricContext, callback);
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().close();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            getCallback().onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void unableToStart() {
    }
}
