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
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.IBinder;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.OperationContextExt;

import java.util.function.Supplier;

/**
 * Abstract {@link BaseClientMonitor} implementation that supports HAL operations.
 * @param <T> HAL template
 */
public abstract class HalClientMonitor<T> extends BaseClientMonitor {
    
    @NonNull
    protected final Supplier<T> mLazyDaemon;

    @NonNull
    private final OperationContextExt mOperationContext;

    /**
     * @param context    system_server context
     * @param lazyDaemon pointer for lazy retrieval of the HAL
     * @param token      a unique token for the client
     * @param listener   recipient of related events (e.g. authentication)
     * @param userId     target user id for operation
     * @param owner      name of the client that owns this
     * @param cookie     BiometricPrompt authentication cookie (to be moved into a subclass soon)
     * @param sensorId   ID of the sensor that the operation should be requested of
     * @param biometricLogger framework stats logger
     * @param biometricContext system context metadata
     */
    public HalClientMonitor(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @Nullable IBinder token, @Nullable ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int cookie, int sensorId,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext) {
        super(context, token, listener, userId, owner, cookie, sensorId,
                biometricLogger, biometricContext);
        mLazyDaemon = lazyDaemon;
        int modality = listener != null ? listener.getModality() : BiometricAuthenticator.TYPE_NONE;
        mOperationContext = new OperationContextExt(isBiometricPrompt(), modality);
    }

    @Nullable
    public T getFreshDaemon() {
        return mLazyDaemon.get();
    }

    /**
     * Starts the HAL operation specific to the ClientMonitor subclass.
     */
    protected abstract void startHalOperation();

    /**
     * Invoked if the scheduler is unable to start the ClientMonitor (for example the HAL is null).
     * If such a problem is detected, the scheduler will not invoke
     * {@link #start(ClientMonitorCallback)}.
     */
    public abstract void unableToStart();

    @Override
    public void destroy() {
        super.destroy();

        // subclasses should do this earlier in most cases, but ensure it happens now
        unsubscribeBiometricContext();
    }

    public boolean isBiometricPrompt() {
        return getCookie() != 0;
    }

    protected OperationContextExt getOperationContext() {
        return getBiometricContext().updateContext(mOperationContext, isCryptoOperation());
    }

    protected ClientMonitorCallback getBiometricContextUnsubscriber() {
        return new ClientMonitorCallback() {
            @Override
            public void onClientFinished(@NonNull BaseClientMonitor monitor, boolean success) {
                unsubscribeBiometricContext();
            }
        };
    }

    protected void unsubscribeBiometricContext() {
        getBiometricContext().unsubscribe(mOperationContext);
    }
}
