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

package com.android.internal.listeners;

import android.annotation.Nullable;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Interface (trait) for executing listener style operations on an executor.
 */
public interface ListenerExecutor {

    /**
     * An listener operation to perform.
     *
     * @param <TListener> listener type
     */
    interface ListenerOperation<TListener> {
        /**
         * Performs the operation on the given listener.
         */
        void operate(TListener listener) throws Exception;

        /**
         * Called before this operation is to be run. An operation may be canceled before it is run,
         * in which case this method may not be invoked. {@link #onPostExecute(boolean)} will only
         * be invoked if this method was previously invoked. This callback is invoked on the
         * calling thread.
         */
        default void onPreExecute() {}

        /**
         * Called if the operation fails while running. Will not be invoked in the event of a
         * unchecked exception, which will propagate normally. This callback is invoked on the
         * executor thread.
         */
        default void onFailure(Exception e) {}

        /**
         * Called after the operation may have been run. Will always be invoked for every operation
         * which has previously had {@link #onPreExecute()} invoked. Success implies that the
         * operation was run to completion with no failures. If {@code success} is true, this
         * callback will always be invoked on the executor thread. If {@code success} is false, this
         * callback may be invoked on the calling thread or executor thread.
         */
        default void onPostExecute(boolean success) {}

        /**
         * Will always be called once for every operation submitted to
         * {@link #executeSafely(Executor, Supplier, ListenerOperation)}, no matter if the operation
         * was run or not. This method is always invoked last, after every other callback. Success
         * implies that the operation was run to completion with no failures. If {@code success}
         * is true, this callback will always be invoked on the executor thread. If {@code success}
         * is false, this callback may be invoked on the calling thread or executor thread.
         */
        default void onComplete(boolean success) {}
    }

    /**
     * An callback for listener operation failure.
     *
     * @param <TListenerOperation> listener operation type
     */
    interface FailureCallback<TListenerOperation extends ListenerOperation<?>> {

        /**
         * Called if a listener operation fails while running with a checked exception. This
         * callback is invoked on the executor thread.
         */
        void onFailure(TListenerOperation operation, Exception exception);
    }

    /**
     * See {@link #executeSafely(Executor, Supplier, ListenerOperation, FailureCallback)}.
     */
    default <TListener> void executeSafely(Executor executor, Supplier<TListener> listenerSupplier,
            @Nullable ListenerOperation<TListener> operation) {
        executeSafely(executor, listenerSupplier, operation, null);
    }

    /**
     * Executes the given listener operation on the given executor, using the provided listener
     * supplier. If the supplier returns a null value, or a value during the operation that does not
     * match the value prior to the operation, then the operation is considered canceled. If a null
     * operation is supplied, nothing happens. If a failure callback is supplied, this will be
     * invoked on the executor thread in the event a checked exception is thrown from the listener
     * operation.
     */
    default <TListener, TListenerOperation extends ListenerOperation<TListener>> void executeSafely(
            Executor executor, Supplier<TListener> listenerSupplier,
            @Nullable TListenerOperation operation,
            @Nullable FailureCallback<TListenerOperation> failureCallback) {
        if (operation == null) {
            return;
        }

        TListener listener = listenerSupplier.get();
        if (listener == null) {
            return;
        }

        boolean executing = false;
        boolean preexecute = false;
        try {
            operation.onPreExecute();
            preexecute = true;
            executor.execute(() -> {
                boolean success = false;
                try {
                    if (listener == listenerSupplier.get()) {
                        operation.operate(listener);
                        success = true;
                    }
                } catch (Exception e) {
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    } else {
                        operation.onFailure(e);
                        if (failureCallback != null) {
                            failureCallback.onFailure(operation, e);
                        }
                    }
                } finally {
                    operation.onPostExecute(success);
                    operation.onComplete(success);
                }
            });
            executing = true;
        } finally {
            if (!executing) {
                if (preexecute) {
                    operation.onPostExecute(false);
                }
                operation.onComplete(false);
            }
        }
    }
}
