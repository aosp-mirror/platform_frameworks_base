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

package android.os;

import android.annotation.NonNull;

/**
 * Callback interface intended for use when an asynchronous operation may result in a failure.
 *
 * This interface may be used in cases where an asynchronous API may complete either with a value
 * or with a {@link Throwable} that indicates an error.
 * @param <R> The type of the result that's being sent.
 * @param <E> The type of the {@link Throwable} that contains more information about the error.
 */
public interface OutcomeReceiver<R, E extends Throwable> {
    /**
     * Called when the asynchronous operation succeeds and delivers a result value.
     * @param result The value delivered by the asynchronous operation.
     */
    void onResult(@NonNull R result);

    /**
     * Called when the asynchronous operation fails. The mode of failure is indicated by the
     * {@link Throwable} passed as an argument to this method.
     * @param error A subclass of {@link Throwable} with more details about the error that occurred.
     */
    default void onError(@NonNull E error) {}
}
