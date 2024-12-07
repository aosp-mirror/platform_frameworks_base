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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInlineSuggestionsResponseCallback;
import com.android.internal.inputmethod.InlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.server.autofill.ui.InlineFillUi;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Maintains an inline suggestion session with the IME.
 *
 * <p> Each session corresponds to one request from the Autofill manager service to create an
 * {@link InlineSuggestionsRequest}. It's responsible for receiving callbacks from the IME and
 * sending {@link android.view.inputmethod.InlineSuggestionsResponse} to IME.
 */
final class AutofillInlineSuggestionsRequestSession {

    private static final String TAG = AutofillInlineSuggestionsRequestSession.class.getSimpleName();

    @NonNull
    private final InputMethodManagerInternal mInputMethodManagerInternal;
    private final int mUserId;
    @NonNull
    private final ComponentName mComponentName;
    @NonNull
    private final Object mLock;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final Bundle mUiExtras;
    @NonNull
    private final InlineFillUi.InlineUiEventCallback mUiCallback;

    @GuardedBy("mLock")
    @NonNull
    private AutofillId mAutofillId;
    @GuardedBy("mLock")
    @Nullable
    private Consumer<InlineSuggestionsRequest> mImeRequestConsumer;

    @GuardedBy("mLock")
    private boolean mImeRequestReceived;
    @GuardedBy("mLock")
    @Nullable
    private InlineSuggestionsRequest mImeRequest;
    @GuardedBy("mLock")
    @Nullable
    private IInlineSuggestionsResponseCallback mResponseCallback;

    @GuardedBy("mLock")
    @Nullable
    private AutofillId mImeCurrentFieldId;
    @GuardedBy("mLock")
    private boolean mImeInputStarted;
    @GuardedBy("mLock")
    private boolean mImeInputViewStarted;
    @GuardedBy("mLock")
    @Nullable
    private InlineFillUi mInlineFillUi;
    @GuardedBy("mLock")
    private Boolean mPreviousResponseIsNotEmpty = null;

    @GuardedBy("mLock")
    private boolean mDestroyed = false;
    @GuardedBy("mLock")
    private boolean mPreviousHasNonPinSuggestionShow;
    @GuardedBy("mLock")
    private boolean mImeSessionInvalidated = false;

    private boolean mImeShowing = false;

    AutofillInlineSuggestionsRequestSession(
            @NonNull InputMethodManagerInternal inputMethodManagerInternal, int userId,
            @NonNull ComponentName componentName, @NonNull Handler handler, @NonNull Object lock,
            @NonNull AutofillId autofillId,
            @NonNull Consumer<InlineSuggestionsRequest> requestConsumer, @NonNull Bundle uiExtras,
            @NonNull InlineFillUi.InlineUiEventCallback callback) {
        mInputMethodManagerInternal = inputMethodManagerInternal;
        mUserId = userId;
        mComponentName = componentName;
        mHandler = handler;
        mLock = lock;
        mUiExtras = uiExtras;
        mUiCallback = callback;

        mAutofillId = autofillId;
        mImeRequestConsumer = requestConsumer;
    }

    @GuardedBy("mLock")
    @NonNull
    AutofillId getAutofillIdLocked() {
        return mAutofillId;
    }

    /**
     * Returns the {@link InlineSuggestionsRequest} provided by IME.
     *
     * <p> The caller is responsible for making sure Autofill hears back from IME before calling
     * this method, using the {@link #mImeRequestConsumer}.
     */
    @GuardedBy("mLock")
    Optional<InlineSuggestionsRequest> getInlineSuggestionsRequestLocked() {
        if (mDestroyed) {
            return Optional.empty();
        }
        return Optional.ofNullable(mImeRequest);
    }

    /**
     * Requests showing the inline suggestion in the IME when the IME becomes visible and is focused
     * on the {@code autofillId}.
     *
     * @return false if the IME callback is not available.
     */
    @GuardedBy("mLock")
    boolean onInlineSuggestionsResponseLocked(@NonNull InlineFillUi inlineFillUi) {
        if (mDestroyed) {
            return false;
        }
        if (sDebug) {
            Slog.d(TAG,
                    "onInlineSuggestionsResponseLocked called for:" + inlineFillUi.getAutofillId());
        }
        if (mImeRequest == null || mResponseCallback == null || mImeSessionInvalidated) {
            return false;
        }
        // TODO(b/151123764): each session should only correspond to one field.
        mAutofillId = inlineFillUi.getAutofillId();
        mInlineFillUi = inlineFillUi;
        maybeUpdateResponseToImeLocked();
        return true;
    }

    /**
     * Prevents further interaction with the IME. Must be called before starting a new request
     * session to avoid unwanted behavior from two overlapping requests.
     */
    @GuardedBy("mLock")
    void destroySessionLocked() {
        mDestroyed = true;

        if (!mImeRequestReceived) {
            Slog.w(TAG,
                    "Never received an InlineSuggestionsRequest from the IME for " + mAutofillId);
        }
    }

    /**
     * Requests the IME to create an {@link InlineSuggestionsRequest}.
     *
     * <p> This method should only be called once per session.
     */
    @GuardedBy("mLock")
    void onCreateInlineSuggestionsRequestLocked() {
        if (mDestroyed) {
            return;
        }
        mImeSessionInvalidated = false;
        if (sDebug) Slog.d(TAG, "onCreateInlineSuggestionsRequestLocked called: " + mAutofillId);
        mInputMethodManagerInternal.onCreateInlineSuggestionsRequest(mUserId,
                new InlineSuggestionsRequestInfo(mComponentName, mAutofillId, mUiExtras),
                new InlineSuggestionsRequestCallbackImpl(this));
    }

    /**
     * Clear the locally cached inline fill UI, but don't clear the suggestion in IME.
     *
     * See also {@link AutofillInlineSessionController#resetInlineFillUiLocked()}
     */
    @GuardedBy("mLock")
    void resetInlineFillUiLocked() {
        mInlineFillUi = null;
    }

    /**
     * Optionally sends inline response to the IME, depending on the current state.
     */
    @GuardedBy("mLock")
    private void maybeUpdateResponseToImeLocked() {
        if (sVerbose) Slog.v(TAG, "maybeUpdateResponseToImeLocked called");
        if (mDestroyed || mResponseCallback == null) {
            return;
        }
        if (mImeInputViewStarted && mInlineFillUi != null && match(mAutofillId,
                mImeCurrentFieldId)) {
            // if IME is visible, and response is not null, send the response
            InlineSuggestionsResponse response = mInlineFillUi.getInlineSuggestionsResponse();
            boolean isEmptyResponse = response.getInlineSuggestions().isEmpty();
            if (isEmptyResponse && Boolean.FALSE.equals(mPreviousResponseIsNotEmpty)) {
                // No-op if both the previous response and current response are empty.
                return;
            }
            maybeNotifyFillUiEventLocked(response.getInlineSuggestions());
            updateResponseToImeUncheckLocked(response);
            mPreviousResponseIsNotEmpty = !isEmptyResponse;
        }
    }

    /**
     * Sends the {@code response} to the IME, assuming all the relevant checks are already done.
     */
    @GuardedBy("mLock")
    private void updateResponseToImeUncheckLocked(InlineSuggestionsResponse response) {
        if (mDestroyed) {
            return;
        }
        if (sDebug) Slog.d(TAG, "Send inline response: " + response.getInlineSuggestions().size());
        try {
            mResponseCallback.onInlineSuggestionsResponse(mAutofillId, response);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException sending InlineSuggestionsResponse to IME");
        }
    }

    @GuardedBy("mLock")
    private void maybeNotifyFillUiEventLocked(@NonNull List<InlineSuggestion> suggestions) {
        if (mDestroyed) {
            return;
        }
        boolean hasSuggestionToShow = false;
        for (int i = 0; i < suggestions.size(); i++) {
            InlineSuggestion suggestion = suggestions.get(i);
            // It is possible we don't have any match result but we still have pinned
            // suggestions. Only notify we have non-pinned suggestions to show
            if (!suggestion.getInfo().isPinned()) {
                hasSuggestionToShow = true;
                break;
            }
        }
        if (sDebug) {
            Slog.d(TAG, "maybeNotifyFillUiEventLoked(): hasSuggestionToShow=" + hasSuggestionToShow
                    + ", mPreviousHasNonPinSuggestionShow=" + mPreviousHasNonPinSuggestionShow);
        }
        // Use mPreviousHasNonPinSuggestionShow to save previous status, if the display status
        // change, we can notify the event.
        if (hasSuggestionToShow && !mPreviousHasNonPinSuggestionShow) {
            // From no suggestion to has suggestions to show
            mUiCallback.notifyInlineUiShown(mAutofillId);
        } else if (!hasSuggestionToShow && mPreviousHasNonPinSuggestionShow) {
            // From has suggestions to no suggestions to show
            mUiCallback.notifyInlineUiHidden(mAutofillId);
        }
        // Update the latest status
        mPreviousHasNonPinSuggestionShow = hasSuggestionToShow;
    }

    /**
     * Handles the {@code request} and {@code callback} received from the IME.
     *
     * <p> Should only invoked in the {@link #mHandler} thread.
     */
    private void handleOnReceiveImeRequest(@Nullable InlineSuggestionsRequest request,
            @Nullable IInlineSuggestionsResponseCallback callback) {
        synchronized (mLock) {
            if (mDestroyed || mImeRequestReceived) {
                return;
            }
            mImeRequestReceived = true;
            mImeSessionInvalidated = false;

            if (request != null && callback != null) {
                mImeRequest = request;
                mResponseCallback = callback;
                handleOnReceiveImeStatusUpdated(mAutofillId, true, false);
            }
            if (mImeRequestConsumer != null) {
                // Note that mImeRequest is only set if both request and callback are non-null.
                mImeRequestConsumer.accept(mImeRequest);
                mImeRequestConsumer = null;
            }
        }
    }

    /**
     * Handles the IME status updates received from the IME.
     *
     * <p> Should only be invoked in the {@link #mHandler} thread.
     */
    private void handleOnReceiveImeStatusUpdated(boolean imeInputStarted,
            boolean imeInputViewStarted) {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            mImeShowing = imeInputViewStarted;
            if (mImeCurrentFieldId != null) {
                boolean imeInputStartedChanged = (mImeInputStarted != imeInputStarted);
                boolean imeInputViewStartedChanged = (mImeInputViewStarted != imeInputViewStarted);
                mImeInputStarted = imeInputStarted;
                mImeInputViewStarted = imeInputViewStarted;
                if (imeInputStartedChanged || imeInputViewStartedChanged) {
                    maybeUpdateResponseToImeLocked();
                }
            }
        }
    }

    /**
     * Handles the IME status updates received from the IME.
     *
     * <p> Should only be invoked in the {@link #mHandler} thread.
     */
    private void handleOnReceiveImeStatusUpdated(@Nullable AutofillId imeFieldId,
            boolean imeInputStarted, boolean imeInputViewStarted) {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            if (imeFieldId != null) {
                mImeCurrentFieldId = imeFieldId;
            }
            handleOnReceiveImeStatusUpdated(imeInputStarted, imeInputViewStarted);
        }
    }

    private void handleOnInputMethodStartInputView() {
        synchronized (mLock) {
            mUiCallback.onInputMethodStartInputView();
            handleOnReceiveImeStatusUpdated(true, true);
        }
    }

    /**
     * Handles the IME session status received from the IME.
     *
     * <p> Should only be invoked in the {@link #mHandler} thread.
     */
    private void handleOnReceiveImeSessionInvalidated() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            mImeSessionInvalidated = true;
        }
    }

    boolean isImeShowing() {
        synchronized (mLock) {
            return !mDestroyed && mImeShowing;
        }
    }

    /**
     * Internal implementation of {@link IInlineSuggestionsRequestCallback}.
     */
    private static final class InlineSuggestionsRequestCallbackImpl
            implements InlineSuggestionsRequestCallback {

        private final WeakReference<AutofillInlineSuggestionsRequestSession> mSession;

        private InlineSuggestionsRequestCallbackImpl(
                AutofillInlineSuggestionsRequestSession session) {
            mSession = new WeakReference<>(session);
        }

        @BinderThread
        @Override
        public void onInlineSuggestionsUnsupported() {
            if (sDebug) Slog.d(TAG, "onInlineSuggestionsUnsupported() called.");
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession::handleOnReceiveImeRequest, session,
                        null, null));
            }
        }

        @BinderThread
        @Override
        public void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
                IInlineSuggestionsResponseCallback callback) {
            if (sDebug) Slog.d(TAG, "onInlineSuggestionsRequest() received: " + request);
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession::handleOnReceiveImeRequest, session,
                        request, callback));
            }
        }

        @Override
        public void onInputMethodStartInput(AutofillId imeFieldId) {
            if (sVerbose) Slog.v(TAG, "onInputMethodStartInput() received on " + imeFieldId);
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession::handleOnReceiveImeStatusUpdated,
                        session, imeFieldId, true, false));
            }
        }

        @Override
        public void onInputMethodShowInputRequested(boolean requestResult) {
            if (sVerbose) {
                Slog.v(TAG, "onInputMethodShowInputRequested() received: " + requestResult);
            }
        }

        @BinderThread
        @Override
        public void onInputMethodStartInputView() {
            if (sVerbose) Slog.v(TAG, "onInputMethodStartInputView() received");
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession::handleOnInputMethodStartInputView,
                        session));
            }
        }

        @BinderThread
        @Override
        public void onInputMethodFinishInputView() {
            if (sVerbose) Slog.v(TAG, "onInputMethodFinishInputView() received");
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession::handleOnReceiveImeStatusUpdated,
                        session, true, false));
            }
        }

        @Override
        public void onInputMethodFinishInput() {
            if (sVerbose) Slog.v(TAG, "onInputMethodFinishInput() received");
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession::handleOnReceiveImeStatusUpdated,
                        session, false, false));
            }
        }

        @BinderThread
        @Override
        public void onInlineSuggestionsSessionInvalidated() {
            if (sDebug) Slog.d(TAG, "onInlineSuggestionsSessionInvalidated() called.");
            final AutofillInlineSuggestionsRequestSession session = mSession.get();
            if (session != null) {
                session.mHandler.sendMessage(obtainMessage(
                        AutofillInlineSuggestionsRequestSession
                                ::handleOnReceiveImeSessionInvalidated, session));
            }
        }
    }

    private static boolean match(@Nullable AutofillId autofillId,
            @Nullable AutofillId imeClientFieldId) {
        // The IME doesn't have information about the virtual view id for the child views in the
        // web view, so we are only comparing the parent view id here. This means that for cases
        // where there are two input fields in the web view, they will have the same view id
        // (although different virtual child id), and we will not be able to distinguish them.
        return autofillId != null && imeClientFieldId != null
                && autofillId.getViewId() == imeClientFieldId.getViewId();
    }
}
