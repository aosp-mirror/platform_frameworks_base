/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.appfunctions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A wrapper of IExecuteAppFunctionCallback which swallows the {@link RemoteException}. This
 * callback is intended for one-time use only. Subsequent calls to onResult() or onError() will be
 * ignored.
 *
 * @hide
 */
public class SafeOneTimeExecuteAppFunctionCallback {
    private static final String TAG = "SafeOneTimeExecuteApp";

    private final AtomicBoolean mOnResultCalled = new AtomicBoolean(false);

    @NonNull private final IExecuteAppFunctionCallback mCallback;

    @Nullable CompletionCallback mCompletionCallback;

    public SafeOneTimeExecuteAppFunctionCallback(@NonNull IExecuteAppFunctionCallback callback) {
        this(callback, /* completionCallback= */ null);
    }

    public SafeOneTimeExecuteAppFunctionCallback(@NonNull IExecuteAppFunctionCallback callback,
            @Nullable CompletionCallback completionCallback) {
        mCallback = Objects.requireNonNull(callback);
        mCompletionCallback = completionCallback;
    }

    /** Invoke wrapped callback with the result. */
    public void onResult(@NonNull ExecuteAppFunctionResponse result) {
        if (!mOnResultCalled.compareAndSet(false, true)) {
            Log.w(TAG, "Ignore subsequent calls to onResult/onError()");
            return;
        }
        try {
            mCallback.onSuccess(result);
            if (mCompletionCallback != null) {
                mCompletionCallback.finalizeOnSuccess(result);
            }
        } catch (RemoteException ex) {
            // Failed to notify the other end. Ignore.
            Log.w(TAG, "Failed to invoke the callback", ex);
        }
    }

    /** Invoke wrapped callback with the error. */
    public void onError(@NonNull AppFunctionException error) {
        if (!mOnResultCalled.compareAndSet(false, true)) {
            Log.w(TAG, "Ignore subsequent calls to onResult/onError()");
            return;
        }
        try {
            mCallback.onError(error);
            if (mCompletionCallback != null) {
                mCompletionCallback.finalizeOnError(error);
            }
        } catch (RemoteException ex) {
            // Failed to notify the other end. Ignore.
            Log.w(TAG, "Failed to invoke the callback", ex);
        }
    }

    /**
     * Disables this callback. Subsequent calls to {@link #onResult(ExecuteAppFunctionResponse)} or
     * {@link #onError(AppFunctionException)} will be ignored.
     */
    public void disable() {
        mOnResultCalled.set(true);
    }

    /**
     * Provides a hook to execute additional actions after the {@link IExecuteAppFunctionCallback}
     * has been invoked.
     */
    public interface CompletionCallback {
        /** Called after {@link IExecuteAppFunctionCallback#onSuccess}. */
        void finalizeOnSuccess(@NonNull ExecuteAppFunctionResponse result);

        /** Called after {@link IExecuteAppFunctionCallback#onError}. */
        void finalizeOnError(@NonNull AppFunctionException error);
    }
}
