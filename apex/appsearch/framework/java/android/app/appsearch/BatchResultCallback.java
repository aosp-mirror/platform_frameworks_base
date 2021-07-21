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

package android.app.appsearch;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * The callback interface to return {@link AppSearchBatchResult}.
 *
 * @param <KeyType> The type of the keys for {@link AppSearchBatchResult#getSuccesses} and
 * {@link AppSearchBatchResult#getFailures}.
 * @param <ValueType> The type of result objects associated with the keys.
 */
public interface BatchResultCallback<KeyType, ValueType> {

    /**
     * Called when {@link AppSearchBatchResult} results are ready.
     *
     * @param result The result of the executed request.
     */
    void onResult(@NonNull AppSearchBatchResult<KeyType, ValueType> result);

    /**
     * Called when a system error occurs.
     *
     * <p>This method is only called the infrastructure is fundamentally broken or unavailable, such
     * that none of the requests could be started. For example, it will be called if the AppSearch
     * service unexpectedly fails to initialize and can't be recovered by any means, or if
     * communicating to the server over Binder fails (e.g. system service crashed or device is
     * rebooting).
     *
     * <p>The error is not expected to be recoverable and there is no specific recommended action
     * other than displaying a permanent message to the user.
     *
     * <p>Normal errors that are caused by invalid inputs or recoverable/retriable situations
     * are reported associated with the input that caused them via the {@link #onResult} method.
     *
     * @param throwable an exception describing the system error
     */
    default void onSystemError(@Nullable Throwable throwable) {
        throw new RuntimeException("Unrecoverable system error", throwable);
    }
}
