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

package com.android.server.autofill;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.RemoteException;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInlineSuggestionsResponseCallback;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Maintains an inline suggestion autofill session.
 *
 * <p> This class is thread safe.
 */
final class InlineSuggestionSession {

    private static final String TAG = "InlineSuggestionSession";
    private static final int INLINE_REQUEST_TIMEOUT_MS = 1000;

    @NonNull
    private final InputMethodManagerInternal mInputMethodManagerInternal;
    private final int mUserId;
    @NonNull
    private final ComponentName mComponentName;
    @NonNull
    private final Object mLock;

    @GuardedBy("mLock")
    @Nullable
    private CompletableFuture<ImeResponse> mPendingImeResponse;

    InlineSuggestionSession(InputMethodManagerInternal inputMethodManagerInternal,
            int userId, ComponentName componentName) {
        mInputMethodManagerInternal = inputMethodManagerInternal;
        mUserId = userId;
        mComponentName = componentName;
        mLock = new Object();
    }

    public void createRequest(@NonNull AutofillId currentViewId) {
        synchronized (mLock) {
            cancelCurrentRequest();
            mPendingImeResponse = new CompletableFuture<>();
            mInputMethodManagerInternal.onCreateInlineSuggestionsRequest(
                    mUserId, mComponentName, currentViewId,
                    new InlineSuggestionsRequestCallbackImpl(mPendingImeResponse));
        }
    }

    @Nullable
    public ImeResponse waitAndGetImeResponse() {
        CompletableFuture<ImeResponse> pendingImeResponse = getPendingImeResponse();
        if (pendingImeResponse == null || pendingImeResponse.isCancelled()) {
            return null;
        }
        try {
            return pendingImeResponse.get(INLINE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "Exception getting inline suggestions request in time: " + e);
        } catch (CancellationException e) {
            Log.w(TAG, "Inline suggestions request cancelled");
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private void cancelCurrentRequest() {
        CompletableFuture<ImeResponse> pendingImeResponse = getPendingImeResponse();
        if (pendingImeResponse != null) {
            pendingImeResponse.cancel(true);
        }
    }

    @Nullable
    @GuardedBy("mLock")
    private CompletableFuture<ImeResponse> getPendingImeResponse() {
        synchronized (mLock) {
            return mPendingImeResponse;
        }
    }

    private static final class InlineSuggestionsRequestCallbackImpl
            extends IInlineSuggestionsRequestCallback.Stub {

        private final CompletableFuture<ImeResponse> mResponse;

        private InlineSuggestionsRequestCallbackImpl(CompletableFuture<ImeResponse> response) {
            mResponse = response;
        }

        @Override
        public void onInlineSuggestionsUnsupported() throws RemoteException {
            if (sDebug) {
                Log.d(TAG, "onInlineSuggestionsUnsupported() called.");
            }
            mResponse.cancel(true);
        }

        @Override
        public void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
                IInlineSuggestionsResponseCallback callback) throws RemoteException {
            if (sDebug) {
                Log.d(TAG, "onInlineSuggestionsRequest() received: " + request);
            }
            if (request != null && callback != null) {
                mResponse.complete(new ImeResponse(request, callback));
            } else {
                mResponse.cancel(true);
            }
        }
    }

    /**
     * A data class wrapping IME responses for the inline suggestion request.
     */
    public static class ImeResponse {
        @NonNull
        private final InlineSuggestionsRequest mRequest;

        @NonNull
        private final IInlineSuggestionsResponseCallback mCallback;

        ImeResponse(@NonNull InlineSuggestionsRequest request,
                @NonNull IInlineSuggestionsResponseCallback callback) {
            mRequest = request;
            mCallback = callback;
        }

        public InlineSuggestionsRequest getRequest() {
            return mRequest;
        }

        public IInlineSuggestionsResponseCallback getCallback() {
            return mCallback;
        }
    }
}
