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
import android.content.Context;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.InvalidationClient;

import java.util.Map;

public class FaceInvalidationClient extends InvalidationClient<Face, ISession> {
    private static final String TAG = "FaceInvalidationClient";

    public FaceInvalidationClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, int userId, int sensorId,
            @NonNull Map<Integer, Long> authenticatorIds, @NonNull IInvalidationCallback callback) {
        super(context, lazyDaemon, userId, sensorId, authenticatorIds, callback);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().invalidateAuthenticatorId();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }
}
