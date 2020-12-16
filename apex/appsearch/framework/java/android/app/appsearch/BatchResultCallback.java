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
     * Called when a system error occurred.
     *
     * @param throwable The cause throwable.
     */
    default void onSystemError(@Nullable Throwable throwable) {
        if (throwable != null) {
            throw new RuntimeException(throwable);
        }
    }
}
