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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.StartUserClient;

import java.util.function.Supplier;

public class FingerprintStartUserClient extends StartUserClient<IFingerprint, ISession> {
    private static final String TAG = "FingerprintStartUserClient";

    @NonNull private final ISessionCallback mSessionCallback;

    public FingerprintStartUserClient(@NonNull Context context,
            @NonNull Supplier<IFingerprint> lazyDaemon,
            @Nullable IBinder token, int userId, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull ISessionCallback sessionCallback,
            @NonNull UserStartedCallback<ISession> callback) {
        super(context, lazyDaemon, token, userId, sensorId, logger, biometricContext, callback);
        mSessionCallback = sessionCallback;
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            final IFingerprint hal = getFreshDaemon();
            final int version = hal.getInterfaceVersion();
            final ISession newSession = hal.createSession(getSensorId(),
                    getTargetUserId(), mSessionCallback);
            Binder.allowBlocking(newSession.asBinder());
            mUserStartedCallback.onUserStarted(getTargetUserId(), newSession, version);
            getCallback().onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            getCallback().onClientFinished(this, false /* success */);
        }
    }

    @Override
    public void unableToStart() {
    }
}
