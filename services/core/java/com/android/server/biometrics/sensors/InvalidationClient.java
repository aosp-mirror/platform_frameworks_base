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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.IInvalidationCallback;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;

import java.util.Map;

/**
 * ClientMonitor subclass for requesting authenticatorId invalidation. See
 * {@link InvalidationRequesterClient} for more info.
 */
public abstract class InvalidationClient<S extends BiometricAuthenticator.Identifier, T>
        extends HalClientMonitor<T> {

    private static final String TAG = "InvalidationClient";

    @NonNull private final Map<Integer, Long> mAuthenticatorIds;
    @NonNull private final IInvalidationCallback mInvalidationCallback;

    public InvalidationClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            int userId, int sensorId, @NonNull Map<Integer, Long> authenticatorIds,
            @NonNull IInvalidationCallback callback) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId,
                context.getOpPackageName(), 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mAuthenticatorIds = authenticatorIds;
        mInvalidationCallback = callback;
    }

    public void onAuthenticatorIdInvalidated(long newAuthenticatorId) {
        mAuthenticatorIds.put(getTargetUserId(), newAuthenticatorId);
        try {
            mInvalidationCallback.onCompleted();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
        mCallback.onClientFinished(this, true /* success */);
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);

        startHalOperation();
    }

    @Override
    public void unableToStart() {

    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_INVALIDATE;
    }
}
