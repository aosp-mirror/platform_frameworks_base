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

package android.service.translation;

import android.annotation.NonNull;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.util.Log;
import android.view.translation.TranslationResponse;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Callback to receive the {@link TranslationResponse} on successful translation.
 *
 * @hide
 */
final class OnTranslationResultCallbackWrapper implements
        Consumer<TranslationResponse> {

    private static final String TAG = "OnTranslationResultCallback";

    private final @NonNull ITranslationCallback mCallback;

    private final AtomicBoolean mCalled;

    /**
     * @hide
     */
    public OnTranslationResultCallbackWrapper(@NonNull ITranslationCallback callback) {
        mCallback = Objects.requireNonNull(callback);
        mCalled = new AtomicBoolean();
    }

    @Override
    public void accept(TranslationResponse response) {
        assertNotCalled();
        if (mCalled.getAndSet(response.isFinalResponse())) {
            throw new IllegalStateException("Already called with complete response");
        }

        try {
            mCallback.onTranslationResponse(response);
        } catch (RemoteException e) {
            if (e instanceof DeadObjectException) {
                Log.w(TAG, "Process is dead, ignore.");
                return;
            }
            throw e.rethrowAsRuntimeException();
        }
    }

    private void assertNotCalled() {
        if (mCalled.get()) {
            throw new IllegalStateException("Already called");
        }
    }
}
