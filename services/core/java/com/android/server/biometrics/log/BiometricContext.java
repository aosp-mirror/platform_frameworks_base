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
import android.hardware.biometrics.common.OperationContext;

import java.util.function.Consumer;

/**
 * Cache for system state not directly related to biometric operations that is used for
 * logging or optimizations.
 */
public interface BiometricContext {
    /** Gets the context source from the system context. */
    static BiometricContext getInstance(@NonNull Context context) {
        return BiometricContextProvider.defaultProvider(context);
    }

    /** Update the given context with the most recent values and return it. */
    OperationContext updateContext(@NonNull OperationContext operationContext,
            boolean isCryptoOperation);

    /** The session id for keyguard entry, if active, or null. */
    @Nullable Integer getKeyguardEntrySessionId();

    /** The session id for biometric prompt usage, if active, or null. */
    @Nullable Integer getBiometricPromptSessionId();

    /** If the display is in AOD. */
    boolean isAod();

    /**
     * Subscribe to context changes.
     *
     * @param context context that will be modified when changed
     * @param consumer callback when the context is modified
     */
    void subscribe(@NonNull OperationContext context, @NonNull Consumer<OperationContext> consumer);

    /** Unsubscribe from context changes. */
    void unsubscribe(@NonNull OperationContext context);
}
