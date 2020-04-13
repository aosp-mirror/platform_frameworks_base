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


import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * A listener registration object which holds data associated with a listener, such the executor
 * the listener should run on.
 *
 * @param <TListener> listener type
 */
public class ListenerTransport<TListener> {

    private final Executor mExecutor;

    private volatile @Nullable TListener mListener;

    protected ListenerTransport(@NonNull Executor executor, @NonNull TListener listener) {
        Preconditions.checkArgument(executor != null, "invalid null executor");
        Preconditions.checkArgument(listener != null, "invalid null listener/callback");
        mExecutor = executor;
        mListener = listener;
    }

    final boolean isRegistered() {
        return mListener != null;
    }

    /**
     * Prevents any listener invocations that happen-after this call.
     */
    public final void unregister() {
        mListener = null;
    }

    /**
     * Executes the given operation for the listener. {@link #onOperationFinished(Consumer)} will
     * always be invoked at some time after this method, regardless of if the listener invocation
     * happens or if the operation fails in some way.
     */
    public final void execute(@NonNull Consumer<TListener> operation) {
        Objects.requireNonNull(operation);
        try {
            mExecutor.execute(() -> {
                try {
                    TListener listener = mListener;
                    if (listener == null) {
                        return;
                    }

                    operation.accept(listener);
                } finally {
                    onOperationFinished(operation);
                }
            });
        } catch (RejectedExecutionException e) {
            onOperationFinished(operation);
        }
    }

    /**
     * Invoked when an operation is finished. This method will always be called once for every call
     * to {@link #execute(Consumer)}, regardless of whether the operation encountered any
     * error or failed to execute in any way. May run on any thread.
     */
    protected void onOperationFinished(@NonNull Consumer<TListener> operation) {}

    @Override
    public final boolean equals(Object obj) {
        // intentionally bound to reference equality so removal works as expected
        return this == obj;
    }

    @Override
    public final int hashCode() {
        return super.hashCode();
    }
}
