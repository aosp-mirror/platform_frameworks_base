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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.fingerprint.ISession;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.sensors.HalClientMonitor;

import java.util.Map;

class FingerprintGetAuthenticatorIdClient extends HalClientMonitor<ISession> {

    private static final String TAG = "FingerprintGetAuthenticatorIdClient";

    private final Map<Integer, Long> mAuthenticatorIds;

    FingerprintGetAuthenticatorIdClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, int userId, @NonNull String owner,
            int sensorId, Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId, owner,
                0 /* cookie */, sensorId, BiometricsProtoEnums.MODALITY_FINGERPRINT,
                BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN);
        mAuthenticatorIds = authenticatorIds;
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    public void start(@NonNull Callback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().getAuthenticatorId();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    void onAuthenticatorIdRetrieved(long authenticatorId) {
        mAuthenticatorIds.put(getTargetUserId(), authenticatorId);
        mCallback.onClientFinished(this, true /* success */);
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_GET_AUTHENTICATOR_ID;
    }
}
