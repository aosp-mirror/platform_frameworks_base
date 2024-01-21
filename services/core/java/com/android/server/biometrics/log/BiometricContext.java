/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.view.Surface;

import com.android.server.biometrics.sensors.AuthSessionCoordinator;

import java.util.function.Consumer;

/**
 * Cache for system state not directly related to biometric operations that is used for
 * logging or optimizations.
 *
 * This class is also used to inject dependencies such as {@link AuthSessionCoordinator}
 */
public interface BiometricContext {
    /** Gets the context source from the system context. */
    static BiometricContext getInstance(@NonNull Context context) {
        return BiometricContextProvider.defaultProvider(context);
    }

    /** Update the given context with the most recent values and return it. */
    OperationContextExt updateContext(@NonNull OperationContextExt operationContext,
            boolean isCryptoOperation);

    /** The session id for keyguard entry, if active, or null. */
    @Nullable BiometricContextSessionInfo getKeyguardEntrySessionInfo();

    /** The session id for biometric prompt usage, if active, or null. */
    @Nullable BiometricContextSessionInfo getBiometricPromptSessionInfo();

    /** If the display is in AOD. */
    boolean isAod();

    /** If the device is awake or is becoming awake. */
    boolean isAwake();

    /** If the display is on. */
    boolean isDisplayOn();

    /** Current dock state from {@link android.content.Intent#EXTRA_DOCK_STATE}. */
    int getDockedState();

    /**
     * Current fold state from
     * {@link android.hardware.biometrics.IBiometricContextListener.FoldState}.
     */
    @IBiometricContextListener.FoldState
    int getFoldState();

    /** Current device display rotation. */
    @Surface.Rotation
    int getCurrentRotation();

    /** Current display state. */
    @AuthenticateOptions.DisplayState
    int getDisplayState();

    /** Gets whether touches on sensor are ignored by HAL */
    boolean isHardwareIgnoringTouches();

    /**
     * Subscribe to context changes.
     *
     * Note that this method only notifies for properties that are visible to the HAL.
     *
     * @param context context that will be modified when changed
     * @param consumer callback when the context is modified
     *
     * @deprecated instead use {@link BiometricContext#subscribe(OperationContextExt, Consumer,
     *                                                           Consumer, AuthenticateOptions)}
     * TODO (b/294161627): Delete this API once Flags.DE_HIDL is removed.
     */
    @Deprecated
    void subscribe(@NonNull OperationContextExt context,
            @NonNull Consumer<OperationContext> consumer);

    /**
     * Subscribe to context changes and start the HAL operation.
     *
     * Note that this method only notifies for properties that are visible to the HAL.
     *
     * @param context               context that will be modified when changed
     * @param startHalConsumer      callback to start HAL operation after subscription is done
     * @param updateContextConsumer callback when the context is modified
     * @param options               authentication options for updating the context
     */
    void subscribe(@NonNull OperationContextExt context,
            @NonNull Consumer<OperationContext> startHalConsumer,
            @NonNull Consumer<OperationContext> updateContextConsumer,
            @Nullable AuthenticateOptions options);

    /** Unsubscribe from context changes. */
    void unsubscribe(@NonNull OperationContextExt context);

    /** Obtains an AuthSessionCoordinator. */
    AuthSessionCoordinator getAuthSessionCoordinator();
}
