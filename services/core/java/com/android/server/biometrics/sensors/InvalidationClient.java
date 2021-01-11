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

/**
 * ClientMonitor subclass for requesting authenticatorId invalidation. See
 * {@link InvalidationRequesterClient} for more info.
 */
public abstract class InvalidationClient<S extends BiometricAuthenticator.Identifier, T>
        extends ClientMonitor<T> {

    private final BiometricUtils<S> mUtils;

    public InvalidationClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            int userId, int sensorId, @NonNull BiometricUtils<S> utils) {
        super(context, lazyDaemon, null /* token */, null /* listener */, userId,
                context.getOpPackageName(), 0 /* cookie */, sensorId,
                BiometricsProtoEnums.MODALITY_UNKNOWN, BiometricsProtoEnums.ACTION_UNKNOWN,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mUtils = utils;
    }

    public void onAuthenticatorIdInvalidated(long newAuthenticatorId) {
        // TODO: Update framework w/ newAuthenticatorId
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
}
