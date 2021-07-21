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
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.view.InputBindResult;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
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
     * @param callback {@link IBooleanResultCallback} to be called back.
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

    /**
     * A utility method using given {@link IInputMethodSubtypeResultCallback} to callback the
     * result.
     *
     * @param callback {@link IInputMethodSubtypeResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IInputMethodSubtypeResultCallback callback,
            @NonNull Supplier<InputMethodSubtype> resultSupplier) {
        InputMethodSubtype result = null;
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
     * A utility method using given {@link IInputMethodSubtypeListResultCallback} to callback the
     * result.
     *
     * @param callback {@link IInputMethodSubtypeListResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IInputMethodSubtypeListResultCallback callback,
            @NonNull Supplier<List<InputMethodSubtype>> resultSupplier) {
        List<InputMethodSubtype> result = null;
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
     * A utility method using given {@link IInputMethodInfoListResultCallback} to callback the
     * result.
     *
     * @param callback {@link IInputMethodInfoListResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IInputMethodInfoListResultCallback callback,
            @NonNull Supplier<List<InputMethodInfo>> resultSupplier) {
        List<InputMethodInfo> result = null;
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
     * A utility method using given {@link IIntResultCallback} to callback the result.
     *
     * @param callback {@link IIntResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IIntResultCallback callback,
            @NonNull IntSupplier resultSupplier) {
        int result = 0;
        Throwable exception = null;

        try {
            result = resultSupplier.getAsInt();
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
     * A utility method using given {@link IVoidResultCallback} to callback the result.
     *
     * @param callback {@link IVoidResultCallback} to be called back.
     * @param runnable to execute the given method
     */
    public static void onResult(@NonNull IVoidResultCallback callback,
            @NonNull Runnable runnable) {
        Throwable exception = null;

        try {
            runnable.run();
        } catch (Throwable throwable) {
            exception = throwable;
        }

        try {
            if (exception != null) {
                callback.onError(ThrowableHolder.of(exception));
                return;
            }
            callback.onResult();
        } catch (RemoteException ignored) { }
    }

    /**
     * A utility method using given {@link IIInputContentUriTokenResultCallback} to callback the
     * result.
     *
     * @param callback {@link IIInputContentUriTokenResultCallback} to be called back.
     * @param resultSupplier the supplier from which the result is provided.
     */
    public static void onResult(@NonNull IIInputContentUriTokenResultCallback callback,
            @NonNull Supplier<IInputContentUriToken> resultSupplier) {
        IInputContentUriToken result = null;
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
}
