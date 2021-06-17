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
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;

/**
 * Abstract {@link BaseClientMonitor} implementation that supports HAL operations.
 * @param <T> HAL template
 */
public abstract class HalClientMonitor<T> extends BaseClientMonitor {
    /**
     * Interface that allows ClientMonitor subclasses to retrieve a fresh instance to the HAL.
     */
    public interface LazyDaemon<T> {
        /**
         * @return A fresh instance to the biometric HAL
         */
        T getDaemon();
    }

    /**
     * Starts the HAL operation specific to the ClientMonitor subclass.
     */
    protected abstract void startHalOperation();

    /**
     * Invoked if the scheduler is unable to start the ClientMonitor (for example the HAL is null).
     * If such a problem is detected, the scheduler will not invoke
     * {@link #start(Callback)}.
     */
    public abstract void unableToStart();

    @NonNull
    protected final LazyDaemon<T> mLazyDaemon;

    /**
     * @param context    system_server context
     * @param lazyDaemon pointer for lazy retrieval of the HAL
     * @param token      a unique token for the client
     * @param listener   recipient of related events (e.g. authentication)
     * @param userId     target user id for operation
     * @param owner      name of the client that owns this
     * @param cookie     BiometricPrompt authentication cookie (to be moved into a subclass soon)
     * @param sensorId   ID of the sensor that the operation should be requested of
     * @param statsModality One of {@link BiometricsProtoEnums} MODALITY_* constants
     * @param statsAction   One of {@link BiometricsProtoEnums} ACTION_* constants
     * @param statsClient   One of {@link BiometricsProtoEnums} CLIENT_* constants
     */
    public HalClientMonitor(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @Nullable IBinder token, @Nullable ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int cookie, int sensorId, int statsModality, int statsAction,
            int statsClient) {
        super(context, token, listener, userId, owner, cookie, sensorId, statsModality,
                statsAction, statsClient);
        mLazyDaemon = lazyDaemon;
    }

    @Nullable
    public T getFreshDaemon() {
        return mLazyDaemon.getDaemon();
    }
}
