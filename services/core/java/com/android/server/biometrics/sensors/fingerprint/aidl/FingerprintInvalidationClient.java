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
import android.content.Context;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.fingerprint.Fingerprint;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.InvalidationClient;

import java.util.Map;
import java.util.function.Supplier;

public class FingerprintInvalidationClient extends InvalidationClient<Fingerprint, AidlSession> {
    private static final String TAG = "FingerprintInvalidationClient";

    public FingerprintInvalidationClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon, int userId, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull Map<Integer, Long> authenticatorIds, @NonNull IInvalidationCallback callback) {
        super(context, lazyDaemon, userId, sensorId, logger, biometricContext,
                authenticatorIds, callback);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().getSession().invalidateAuthenticatorId();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }
}
