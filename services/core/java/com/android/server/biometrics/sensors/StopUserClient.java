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
import android.annotation.Nullable;
import android.content.Context;
import android.os.IBinder;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.function.Supplier;

/**
 * Abstract class for stopping a user.
 *
 * @param <T> Session for stopping the user. It should be either an instance of
 *            {@link com.android.server.biometrics.sensors.fingerprint.aidl.AidlSession} or
 *            {@link com.android.server.biometrics.sensors.face.aidl.AidlSession}.
 */
public abstract class StopUserClient<T> extends HalClientMonitor<T> {

    public interface UserStoppedCallback {
        void onUserStopped();
    }

    @NonNull @VisibleForTesting
    private final UserStoppedCallback mUserStoppedCallback;

    public void onUserStopped() {
        mUserStoppedCallback.onUserStopped();
        getCallback().onClientFinished(this, true /* success */);
    }

    public StopUserClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @Nullable IBinder token, int userId, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull UserStoppedCallback callback) {
        super(context, lazyDaemon, token, null /* listener */, userId, context.getOpPackageName(),
                0 /* cookie */, sensorId, logger, biometricContext);
        mUserStoppedCallback = callback;
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_STOP_USER;
    }
}
