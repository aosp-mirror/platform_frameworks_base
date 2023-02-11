/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.remotelockscreenvalidation;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.Executor;

/**
 * Client for {@link RemoteLockscreenValidationService}
 * @hide
 */
public interface RemoteLockscreenValidationClient {

    /**
     * Create a client for the {@link RemoteLockscreenValidationService} specified by the
     * {@link ComponentName}
     * @hide
     */
    @NonNull
    static RemoteLockscreenValidationClient create(@NonNull Context context,
            @NonNull ComponentName serviceComponent) {
        return new RemoteLockscreenValidationClientImpl(
                context,
                /* bgExecutor= */ null,
                serviceComponent);
    }

    /**
     * Create a client for the {@link RemoteLockscreenValidationService} specified by the
     * {@link ComponentName}
     * @param context Context.
     * @param bgExecutor A background {@link Executor} for service registration.
     * @hide
     */
    @NonNull
    static RemoteLockscreenValidationClient create(@NonNull Context context,
            @Nullable Executor bgExecutor, @NonNull ComponentName serviceComponent) {
        return new RemoteLockscreenValidationClientImpl(context, bgExecutor, serviceComponent);
    }

    /**
     * Returns whether the {@link RemoteLockscreenValidationService} defined by the
     * {@code ComponentName} provided in the constructor is available.
     *
     * <p>Calling API methods like {@link #validateLockscreenGuess} will fail if unavailable.
     */
    boolean isServiceAvailable();

    /**
     * Unbinds from the {@link RemoteLockscreenValidationService}
     */
    void disconnect();

    /**
     * Validates the lockscreen guess.
     *
     * @param guess lockscreen guess
     * @param callback object used to relay the response of the guess validation
     */
    void validateLockscreenGuess(byte[] guess, IRemoteLockscreenValidationCallback callback);
}
