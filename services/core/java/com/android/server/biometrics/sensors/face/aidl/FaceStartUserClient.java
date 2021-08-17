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
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.ISessionCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.StartUserClient;

public class FaceStartUserClient extends StartUserClient<IFace, ISession> {
    private static final String TAG = "FaceStartUserClient";

    @NonNull private final ISessionCallback mSessionCallback;

    public FaceStartUserClient(@NonNull Context context, @NonNull LazyDaemon<IFace> lazyDaemon,
            @Nullable IBinder token, int userId, int sensorId,
            @NonNull ISessionCallback sessionCallback,
            @NonNull UserStartedCallback<ISession> callback) {
        super(context, lazyDaemon, token, userId, sensorId, callback);
        mSessionCallback = sessionCallback;
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            final ISession newSession = getFreshDaemon().createSession(getSensorId(),
                    getTargetUserId(), mSessionCallback);
            mUserStartedCallback.onUserStarted(getTargetUserId(), newSession);
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
