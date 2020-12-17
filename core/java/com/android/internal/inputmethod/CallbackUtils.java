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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.os.RemoteException;

import com.android.internal.view.InputBindResult;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Defines a set of helper methods to callback corresponding results in {@link ResultCallbacks}.
 */
public final class CallbackUtils {

    /**
     * Not intended to be instantiated.
     */
    private CallbackUtils() {
    }

    /**
     * A utility method using given {@link IInputBindResultResultCallback} to callback the
     * {@link InputBindResult}.
     *
     * @param callback {@link IInputBindResultResultCallback} to be called back.
     * @param resultSupplier the supplier from which {@link InputBindResult} is provided.
     */
    @AnyThread
    public static void onResult(@NonNull IInputBindResultResultCallback callback,
            @NonNull Supplier<InputBindResult> resultSupplier) {
        InputBindResult result = null;
        Throwable exception = null;

        try {
            result = resultSupplier.get();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult(result);
        } catch (RemoteException ignored) { }
    }

    /**
     * A utility method using given {@link IBooleanResultCallback} to callback the result.
     *
     * @param callback {@link IInputBindResultResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IBooleanResultCallback callback,
            @NonNull BooleanSupplier resultSupplier) {
        boolean result = false;
        Throwable exception = null;

        try {
            result = resultSupplier.getAsBoolean();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult(result);
        } catch (RemoteException ignored) { }
    }
}
