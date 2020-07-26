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
         * Called before this operation is to be run. Some operations may be canceled before they
         * are run, in which case this method may not be called. {@link #onPostExecute(boolean)}
         * will only be run if this method was run. This callback is invoked on the calling thread.
         */
        default void onPreExecute() {}

        /**
         * Called if the operation fails while running. Will not be invoked in the event of a
         * RuntimeException, which will propagate normally. Implementations of
         * {@link ListenerExecutor} have the option to override
         * {@link ListenerExecutor#onOperationFailure(ListenerOperation, Exception)} instead to
         * intercept failures at the class level. This callback is invoked on the executor thread.
         */
        default void onFailure(Exception e) {
            // implementations should handle any exceptions that may be thrown
            throw new AssertionError(e);
        }

        /**
         * Called after the operation is run. This method will always be called if
         * {@link #onPreExecute()} is called. Success implies that the operation was run to
         * completion with no failures. This callback may be invoked on the calling thread or
         * executor thread.
         */
        default void onPostExecute(boolean success) {}

        /**
         * Called after this operation is complete (which does not imply that it was necessarily
         * run). Will always be called once per operation, no matter if the operation was run or
         * not. Success implies that the operation was run to completion with no failures. This
         * callback may be invoked on the calling thread or executor thread.
         */
        default void onComplete(boolean success) {}
    }

    /**
     * May be override to handle operation failures at a class level. Will not be invoked in the
     * event of a RuntimeException, which will propagate normally. This callback is invoked on the
     * executor thread.
     */
    default <TListener> void onOperationFailure(ListenerOperation<TListener> operation,
            Exception exception) {
        operation.onFailure(exception);
    }

    /**
     * Executes the given listener operation on the given executor, using the provided listener
     * supplier. If the supplier returns a null value, or a value during the operation that does not
     * match the value prior to the operation, then the operation is considered canceled. If a null
     * operation is supplied, nothing happens.
     */
    default <TListener> void executeSafely(Executor executor, Supplier<TListener> listenerSupplier,
            @Nullable ListenerOperation<TListener> operation) {
        if (operation == null) {
            return;
        }

        boolean executing = false;
        boolean preexecute = false;
        try {
            TListener listener = listenerSupplier.get();
            if (listener == null) {
                return;
            }

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
                        onOperationFailure(operation, e);
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
