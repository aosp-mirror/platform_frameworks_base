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
import java.util.function.Consumer;

/**
 * A wrapper of IExecuteAppFunctionCallback which swallows the {@link RemoteException}. This
 * callback is intended for one-time use only. Subsequent calls to onResult() will be ignored.
 *
 * @hide
 */
public class SafeOneTimeExecuteAppFunctionCallback {
    private static final String TAG = "SafeOneTimeExecuteApp";

    private final AtomicBoolean mOnResultCalled = new AtomicBoolean(false);

    @NonNull private final IExecuteAppFunctionCallback mCallback;

    @Nullable private final Consumer<ExecuteAppFunctionResponse> mOnDispatchCallback;

    public SafeOneTimeExecuteAppFunctionCallback(@NonNull IExecuteAppFunctionCallback callback) {
        this(callback, /* onDispatchCallback= */ null);
    }

    /**
     * @param callback The callback to wrap.
     * @param onDispatchCallback An optional callback invoked after the wrapped callback has been
     *     dispatched with a result. This callback receives the result that has been dispatched.
     */
    public SafeOneTimeExecuteAppFunctionCallback(
            @NonNull IExecuteAppFunctionCallback callback,
            @Nullable Consumer<ExecuteAppFunctionResponse> onDispatchCallback) {
        mCallback = Objects.requireNonNull(callback);
        mOnDispatchCallback = onDispatchCallback;
    }

    /** Invoke wrapped callback with the result. */
    public void onResult(@NonNull ExecuteAppFunctionResponse result) {
        if (!mOnResultCalled.compareAndSet(false, true)) {
            Log.w(TAG, "Ignore subsequent calls to onResult()");
            return;
        }
        try {
            mCallback.onResult(result);
        } catch (RemoteException ex) {
            // Failed to notify the other end. Ignore.
            Log.w(TAG, "Failed to invoke the callback", ex);
        }
        if (mOnDispatchCallback != null) {
            mOnDispatchCallback.accept(result);
        }
    }
}
