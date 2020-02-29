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
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInlineSuggestionsResponseCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Maintains an autofill inline suggestion session that communicates with the IME.
 *
 * <p>
 * The same session may be reused for multiple input fields involved in the same autofill
 * {@link Session}. Therefore, one {@link InlineSuggestionsRequest} and one
 * {@link IInlineSuggestionsResponseCallback} may be used to generate and callback with inline
 * suggestions for  different input fields.
 *
 * <p>
 * This class is thread safe.
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

    @GuardedBy("mLock")
    private boolean mIsLastResponseNonEmpty = false;

    InlineSuggestionSession(InputMethodManagerInternal inputMethodManagerInternal,
            int userId, ComponentName componentName) {
        mInputMethodManagerInternal = inputMethodManagerInternal;
        mUserId = userId;
        mComponentName = componentName;
        mLock = new Object();
    }

    public void onCreateInlineSuggestionsRequest(@NonNull AutofillId autofillId) {
        if (sDebug) Log.d(TAG, "onCreateInlineSuggestionsRequest called for " + autofillId);

        synchronized (mLock) {
            cancelCurrentRequest();
            mPendingImeResponse = new CompletableFuture<>();
            // TODO(b/146454892): pipe the uiExtras from the ExtServices.
            mInputMethodManagerInternal.onCreateInlineSuggestionsRequest(
                    mUserId,
                    new InlineSuggestionsRequestInfo(mComponentName, autofillId, new Bundle()),
                    new InlineSuggestionsRequestCallbackImpl(mPendingImeResponse));
        }
    }

    public Optional<InlineSuggestionsRequest> waitAndGetInlineSuggestionsRequest() {
        final CompletableFuture<ImeResponse> pendingImeResponse = getPendingImeResponse();
        if (pendingImeResponse == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(pendingImeResponse.get(INLINE_REQUEST_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)).map(ImeResponse::getRequest);
        } catch (TimeoutException e) {
            Log.w(TAG, "Exception getting inline suggestions request in time: " + e);
        } catch (CancellationException e) {
            Log.w(TAG, "Inline suggestions request cancelled");
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public boolean hideInlineSuggestionsUi(@NonNull AutofillId autofillId) {
        if (sDebug) Log.d(TAG, "Called hideInlineSuggestionsUi for " + autofillId);
        synchronized (mLock) {
            if (mIsLastResponseNonEmpty) {
                if (sDebug) Log.d(TAG, "Send empty suggestion to IME");
                return onInlineSuggestionsResponseLocked(autofillId,
                        new InlineSuggestionsResponse(Collections.EMPTY_LIST));
            }
            return false;
        }
    }

    public boolean onInlineSuggestionsResponse(@NonNull AutofillId autofillId,
            @NonNull InlineSuggestionsResponse inlineSuggestionsResponse) {
        synchronized (mLock) {
            return onInlineSuggestionsResponseLocked(autofillId, inlineSuggestionsResponse);
        }
    }

    private boolean onInlineSuggestionsResponseLocked(@NonNull AutofillId autofillId,
            @NonNull InlineSuggestionsResponse inlineSuggestionsResponse) {
        final CompletableFuture<ImeResponse> completedImsResponse = getPendingImeResponse();
        if (completedImsResponse == null || !completedImsResponse.isDone()) {
            return false;
        }
        // There is no need to wait on the CompletableFuture since it should have been completed
        // when {@link #waitAndGetInlineSuggestionsRequest()} was called.
        ImeResponse imeResponse = completedImsResponse.getNow(null);
        if (imeResponse == null) {
            return false;
        }
        try {
            imeResponse.mCallback.onInlineSuggestionsResponse(autofillId,
                    inlineSuggestionsResponse);
            mIsLastResponseNonEmpty = !inlineSuggestionsResponse.getInlineSuggestions().isEmpty();
            if (sDebug) {
                Log.d(TAG, "Autofill sends inline response to IME: "
                        + inlineSuggestionsResponse.getInlineSuggestions().size());
            }
            return true;
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException sending InlineSuggestionsResponse to IME");
            return false;
        }
    }

    private void cancelCurrentRequest() {
        CompletableFuture<ImeResponse> pendingImeResponse = getPendingImeResponse();
        if (pendingImeResponse != null && !pendingImeResponse.isDone()) {
            pendingImeResponse.complete(null);
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
            if (sDebug) Log.d(TAG, "onInlineSuggestionsUnsupported() called.");
            mResponse.complete(null);
        }

        @Override
        public void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
                IInlineSuggestionsResponseCallback callback) {
            if (sDebug) Log.d(TAG, "onInlineSuggestionsRequest() received: " + request);
            if (request != null && callback != null) {
                mResponse.complete(new ImeResponse(request, callback));
            } else {
                mResponse.complete(null);
            }
        }
    }

    /**
     * A data class wrapping IME responses for the inline suggestion request.
     */
    private static class ImeResponse {
        @NonNull
        final InlineSuggestionsRequest mRequest;

        @NonNull
        final IInlineSuggestionsResponseCallback mCallback;

        ImeResponse(@NonNull InlineSuggestionsRequest request,
                @NonNull IInlineSuggestionsResponseCallback callback) {
            mRequest = request;
            mCallback = callback;
        }

        InlineSuggestionsRequest getRequest() {
            return mRequest;
        }
    }
}
