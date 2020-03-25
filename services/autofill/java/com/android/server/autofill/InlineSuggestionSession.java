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

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Maintains an autofill inline suggestion session that communicates with the IME.
 *
 * <p>
 * The same session may be reused for multiple input fields involved in the same autofill
 * {@link Session}. Therefore, one {@link InlineSuggestionsRequest} and one
 * {@link IInlineSuggestionsResponseCallback} may be used to generate and callback with inline
 * suggestions for different input fields.
 *
 * <p>
 * This class is the sole place in Autofill responsible for directly communicating with the IME. It
 * receives the IME input view start/finish events, with the associated IME field Id. It uses the
 * information to decide when to send the {@link InlineSuggestionsResponse} to IME. As a result,
 * some of the response will be cached locally and only be sent when the IME is ready to show them.
 *
 * <p>
 * See {@link android.inputmethodservice.InlineSuggestionSession} comments for InputMethodService
 * side flow.
 *
 * <p>
 * This class should hold the same lock as {@link Session} as they call into each other.
 */
final class InlineSuggestionSession {

    private static final String TAG = "AfInlineSuggestionSession";
    private static final int INLINE_REQUEST_TIMEOUT_MS = 200;

    @NonNull
    private final InputMethodManagerInternal mInputMethodManagerInternal;
    private final int mUserId;
    @NonNull
    private final ComponentName mComponentName;
    @NonNull
    private final Object mLock;
    @NonNull
    private final ImeStatusListener mImeStatusListener;
    @NonNull
    private final Handler mHandler;

    /**
     * To avoid the race condition, one should not access {@code mPendingImeResponse} without
     * holding the {@code mLock}. For consuming the existing value, tt's recommended to use
     * {@link #getPendingImeResponse()} to get a copy of the reference to avoid blocking call.
     */
    @GuardedBy("mLock")
    @Nullable
    private CompletableFuture<ImeResponse> mPendingImeResponse;

    @GuardedBy("mLock")
    @Nullable
    private AutofillResponse mPendingAutofillResponse;

    @GuardedBy("mLock")
    private boolean mIsLastResponseNonEmpty = false;

    @Nullable
    @GuardedBy("mLock")
    private AutofillId mImeFieldId = null;

    @GuardedBy("mLock")
    private boolean mImeInputViewStarted = false;

    InlineSuggestionSession(InputMethodManagerInternal inputMethodManagerInternal,
            int userId, ComponentName componentName, Handler handler, Object lock) {
        mInputMethodManagerInternal = inputMethodManagerInternal;
        mUserId = userId;
        mComponentName = componentName;
        mHandler = handler;
        mLock = lock;
        mImeStatusListener = new ImeStatusListener() {
            @Override
            public void onInputMethodStartInputView(AutofillId imeFieldId) {
                synchronized (mLock) {
                    mImeFieldId = imeFieldId;
                    mImeInputViewStarted = true;
                    AutofillResponse pendingAutofillResponse = mPendingAutofillResponse;
                    if (pendingAutofillResponse != null
                            && pendingAutofillResponse.mAutofillId.equalsIgnoreSession(
                            mImeFieldId)) {
                        mPendingAutofillResponse = null;
                        onInlineSuggestionsResponseLocked(pendingAutofillResponse.mAutofillId,
                                pendingAutofillResponse.mResponse);
                    }
                }
            }

            @Override
            public void onInputMethodFinishInputView(AutofillId imeFieldId) {
                synchronized (mLock) {
                    mImeFieldId = imeFieldId;
                    mImeInputViewStarted = false;
                }
            }
        };
    }

    public void onCreateInlineSuggestionsRequest(@NonNull AutofillId autofillId,
            @NonNull Consumer<InlineSuggestionsRequest> requestConsumer) {
        if (sDebug) Log.d(TAG, "onCreateInlineSuggestionsRequest called for " + autofillId);

        synchronized (mLock) {
            // Clean up all the state about the previous request.
            hideInlineSuggestionsUi(autofillId);
            mImeFieldId = null;
            mImeInputViewStarted = false;
            if (mPendingImeResponse != null && !mPendingImeResponse.isDone()) {
                mPendingImeResponse.complete(null);
            }
            mPendingImeResponse = new CompletableFuture<>();
            // TODO(b/146454892): pipe the uiExtras from the ExtServices.
            mInputMethodManagerInternal.onCreateInlineSuggestionsRequest(
                    mUserId,
                    new InlineSuggestionsRequestInfo(mComponentName, autofillId, new Bundle()),
                    new InlineSuggestionsRequestCallbackImpl(mPendingImeResponse,
                            mImeStatusListener, requestConsumer, mHandler, mLock));
        }
    }

    public Optional<InlineSuggestionsRequest> getInlineSuggestionsRequest() {
        final CompletableFuture<ImeResponse> pendingImeResponse = getPendingImeResponse();
        if (pendingImeResponse == null || !pendingImeResponse.isDone()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pendingImeResponse.getNow(null)).map(ImeResponse::getRequest);
    }

    public boolean hideInlineSuggestionsUi(@NonNull AutofillId autofillId) {
        synchronized (mLock) {
            if (mIsLastResponseNonEmpty) {
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
            if (sDebug) Log.d(TAG, "onInlineSuggestionsResponseLocked without IMS request");
            return false;
        }
        // There is no need to wait on the CompletableFuture since it should have been completed.
        ImeResponse imeResponse = completedImsResponse.getNow(null);
        if (imeResponse == null) {
            if (sDebug) Log.d(TAG, "onInlineSuggestionsResponseLocked with pending IMS response");
            return false;
        }

        // TODO(b/151846600): IME doesn't have access to the virtual id of the webview, so we
        //  only compare the view id for now.
        if (!mImeInputViewStarted || mImeFieldId == null
                || autofillId.getViewId() != mImeFieldId.getViewId()) {
            if (sDebug) {
                Log.d(TAG,
                        "onInlineSuggestionsResponseLocked not sent because input view is not "
                                + "started for " + autofillId);
            }
            mPendingAutofillResponse = new AutofillResponse(autofillId, inlineSuggestionsResponse);
            // TODO(b/149442582): Although we are not sending the response to IME right away, we
            //  still return true to indicate that the response may be sent eventually, such that
            //  the dropdown UI will not be shown. This may not be the desired behavior in the
            //  auto-focus case where IME isn't shown after switching back to an activity. We may
            //  revisit this.
            return true;
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

    @Nullable
    @GuardedBy("mLock")
    private CompletableFuture<ImeResponse> getPendingImeResponse() {
        synchronized (mLock) {
            return mPendingImeResponse;
        }
    }

    private static final class InlineSuggestionsRequestCallbackImpl
            extends IInlineSuggestionsRequestCallback.Stub {

        private final Object mLock;
        @GuardedBy("mLock")
        private final CompletableFuture<ImeResponse> mResponse;
        @GuardedBy("mLock")
        private final Consumer<InlineSuggestionsRequest> mRequestConsumer;
        private final ImeStatusListener mImeStatusListener;
        private final Handler mHandler;
        private final Runnable mTimeoutCallback;

        private InlineSuggestionsRequestCallbackImpl(CompletableFuture<ImeResponse> response,
                ImeStatusListener imeStatusListener,
                Consumer<InlineSuggestionsRequest> requestConsumer,
                Handler handler, Object lock) {
            mResponse = response;
            mImeStatusListener = imeStatusListener;
            mRequestConsumer = requestConsumer;
            mLock = lock;

            mHandler = handler;
            mTimeoutCallback = () -> {
                Log.w(TAG, "Timed out waiting for IME callback InlineSuggestionsRequest.");
                completeIfNot(null);
            };
            mHandler.postDelayed(mTimeoutCallback, INLINE_REQUEST_TIMEOUT_MS);
        }

        private void completeIfNot(@Nullable ImeResponse response) {
            synchronized (mLock) {
                if (mResponse.isDone()) {
                    return;
                }
                mResponse.complete(response);
                mRequestConsumer.accept(response == null ? null : response.mRequest);
                mHandler.removeCallbacks(mTimeoutCallback);
            }
        }

        @BinderThread
        @Override
        public void onInlineSuggestionsUnsupported() throws RemoteException {
            if (sDebug) Log.d(TAG, "onInlineSuggestionsUnsupported() called.");
            completeIfNot(null);
        }

        @BinderThread
        @Override
        public void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
                IInlineSuggestionsResponseCallback callback, AutofillId imeFieldId,
                boolean inputViewStarted) {
            if (sDebug) {
                Log.d(TAG,
                        "onInlineSuggestionsRequest() received: " + request + ", inputViewStarted="
                                + inputViewStarted + ", imeFieldId=" + imeFieldId);
            }
            if (inputViewStarted) {
                mImeStatusListener.onInputMethodStartInputView(imeFieldId);
            } else {
                mImeStatusListener.onInputMethodFinishInputView(imeFieldId);
            }
            if (request != null && callback != null) {
                completeIfNot(new ImeResponse(request, callback));
            } else {
                completeIfNot(null);
            }
        }

        @BinderThread
        @Override
        public void onInputMethodStartInputView(AutofillId imeFieldId) {
            if (sDebug) Log.d(TAG, "onInputMethodStartInputView() received on " + imeFieldId);
            mImeStatusListener.onInputMethodStartInputView(imeFieldId);
        }

        @BinderThread
        @Override
        public void onInputMethodFinishInputView(AutofillId imeFieldId) {
            if (sDebug) Log.d(TAG, "onInputMethodFinishInputView() received on " + imeFieldId);
            mImeStatusListener.onInputMethodFinishInputView(imeFieldId);
        }
    }

    private interface ImeStatusListener {
        void onInputMethodStartInputView(AutofillId imeFieldId);

        void onInputMethodFinishInputView(AutofillId imeFieldId);
    }

    /**
     * A data class wrapping Autofill responses for the inline suggestion request.
     */
    private static class AutofillResponse {
        @NonNull
        final AutofillId mAutofillId;

        @NonNull
        final InlineSuggestionsResponse mResponse;

        AutofillResponse(@NonNull AutofillId autofillId,
                @NonNull InlineSuggestionsResponse response) {
            mAutofillId = autofillId;
            mResponse = response;
        }

    }

    /**
     * A data class wrapping IME responses for the create inline suggestions request.
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
