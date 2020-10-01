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

package android.inputmethodservice;

import static android.inputmethodservice.InputMethodService.DEBUG;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages the interaction with the autofill manager service for the inline suggestion sessions.
 *
 * <p>
 * The class maintains the inline suggestion session with the autofill service. There is at most one
 * active inline suggestion session at any given time.
 *
 * <p>
 * The class receives the IME status change events (input start/finish, input view start/finish, and
 * show input requested result), and send them through IPC to the {@link
 * com.android.server.inputmethod.InputMethodManagerService}, which sends them to {@link
 * com.android.server.autofill.InlineSuggestionSession} in the Autofill manager service. If there is
 * no open inline suggestion session, no event will be send to autofill manager service.
 *
 * <p>
 * All the methods are expected to be called from the main thread, to ensure thread safety.
 */
class InlineSuggestionSessionController {
    private static final String TAG = "InlineSuggestionSessionController";

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper(), null, true);

    @NonNull
    private final Function<Bundle, InlineSuggestionsRequest> mRequestSupplier;
    @NonNull
    private final Supplier<IBinder> mHostInputTokenSupplier;
    @NonNull
    private final Consumer<InlineSuggestionsResponse> mResponseConsumer;

    /* The following variables track the IME status */
    @Nullable
    private String mImeClientPackageName;
    @Nullable
    private AutofillId mImeClientFieldId;
    private boolean mImeInputStarted;
    private boolean mImeInputViewStarted;

    @Nullable
    private InlineSuggestionSession mSession;

    InlineSuggestionSessionController(
            @NonNull Function<Bundle, InlineSuggestionsRequest> requestSupplier,
            @NonNull Supplier<IBinder> hostInputTokenSupplier,
            @NonNull Consumer<InlineSuggestionsResponse> responseConsumer) {
        mRequestSupplier = requestSupplier;
        mHostInputTokenSupplier = hostInputTokenSupplier;
        mResponseConsumer = responseConsumer;
    }

    /**
     * Called upon IME receiving a create inline suggestion request. Must be called in the main
     * thread to ensure thread safety.
     */
    @MainThread
    void onMakeInlineSuggestionsRequest(@NonNull InlineSuggestionsRequestInfo requestInfo,
            @NonNull IInlineSuggestionsRequestCallback callback) {
        if (DEBUG) Log.d(TAG, "onMakeInlineSuggestionsRequest: " + requestInfo);
        // Creates a new session for the new create request from Autofill.
        if (mSession != null) {
            mSession.invalidate();
        }
        mSession = new InlineSuggestionSession(requestInfo, callback, mRequestSupplier,
                mHostInputTokenSupplier, mResponseConsumer, this, mMainThreadHandler);

        // If the input is started on the same view, then initiate the callback to the Autofill.
        // Otherwise wait for the input to start.
        if (mImeInputStarted && match(mSession.getRequestInfo())) {
            mSession.makeInlineSuggestionRequestUncheck();
            // ... then update the Autofill whether the input view is started.
            if (mImeInputViewStarted) {
                try {
                    mSession.getRequestCallback().onInputMethodStartInputView();
                } catch (RemoteException e) {
                    Log.w(TAG, "onInputMethodStartInputView() remote exception:" + e);
                }
            }
        }
    }

    /**
     * Called from IME main thread before calling {@link InputMethodService#onStartInput(EditorInfo,
     * boolean)}. This method should be quick as it makes a unblocking IPC.
     */
    @MainThread
    void notifyOnStartInput(@Nullable String imeClientPackageName,
            @Nullable AutofillId imeFieldId) {
        if (DEBUG) Log.d(TAG, "notifyOnStartInput: " + imeClientPackageName + ", " + imeFieldId);
        if (imeClientPackageName == null || imeFieldId == null) {
            return;
        }
        mImeInputStarted = true;
        mImeClientPackageName = imeClientPackageName;
        mImeClientFieldId = imeFieldId;

        if (mSession != null) {
            mSession.consumeInlineSuggestionsResponse(InlineSuggestionSession.EMPTY_RESPONSE);
            // Initiates the callback to Autofill if there is a pending matching session.
            // Otherwise updates the session with the Ime status.
            if (!mSession.isCallbackInvoked() && match(mSession.getRequestInfo())) {
                mSession.makeInlineSuggestionRequestUncheck();
            } else if (mSession.shouldSendImeStatus()) {
                try {
                    mSession.getRequestCallback().onInputMethodStartInput(mImeClientFieldId);
                } catch (RemoteException e) {
                    Log.w(TAG, "onInputMethodStartInput() remote exception:" + e);
                }
            }
        }
    }

    /**
     * Called from IME main thread after getting results from
     * {@link InputMethodService#dispatchOnShowInputRequested(int,
     * boolean)}. This method should be quick as it makes a unblocking IPC.
     */
    @MainThread
    void notifyOnShowInputRequested(boolean requestResult) {
        if (DEBUG) Log.d(TAG, "notifyShowInputRequested");
        if (mSession != null && mSession.shouldSendImeStatus()) {
            try {
                mSession.getRequestCallback().onInputMethodShowInputRequested(requestResult);
            } catch (RemoteException e) {
                Log.w(TAG, "onInputMethodShowInputRequested() remote exception:" + e);
            }
        }
    }

    /**
     * Called from IME main thread before calling
     * {@link InputMethodService#onStartInputView(EditorInfo,
     * boolean)} . This method should be quick as it makes a unblocking IPC.
     */
    @MainThread
    void notifyOnStartInputView() {
        if (DEBUG) Log.d(TAG, "notifyOnStartInputView");
        mImeInputViewStarted = true;
        if (mSession != null && mSession.shouldSendImeStatus()) {
            try {
                mSession.getRequestCallback().onInputMethodStartInputView();
            } catch (RemoteException e) {
                Log.w(TAG, "onInputMethodStartInputView() remote exception:" + e);
            }
        }
    }

    /**
     * Called from IME main thread before calling
     * {@link InputMethodService#onFinishInputView(boolean)}.
     * This method should be quick as it makes a unblocking IPC.
     */
    @MainThread
    void notifyOnFinishInputView() {
        if (DEBUG) Log.d(TAG, "notifyOnFinishInputView");
        mImeInputViewStarted = false;
        if (mSession != null && mSession.shouldSendImeStatus()) {
            try {
                mSession.getRequestCallback().onInputMethodFinishInputView();
            } catch (RemoteException e) {
                Log.w(TAG, "onInputMethodFinishInputView() remote exception:" + e);
            }
        }
    }

    /**
     * Called from IME main thread before calling {@link InputMethodService#onFinishInput()}. This
     * method should be quick as it makes a unblocking IPC.
     */
    @MainThread
    void notifyOnFinishInput() {
        if (DEBUG) Log.d(TAG, "notifyOnFinishInput");
        mImeClientPackageName = null;
        mImeClientFieldId = null;
        mImeInputViewStarted = false;
        mImeInputStarted = false;
        if (mSession != null && mSession.shouldSendImeStatus()) {
            try {
                mSession.getRequestCallback().onInputMethodFinishInput();
            } catch (RemoteException e) {
                Log.w(TAG, "onInputMethodFinishInput() remote exception:" + e);
            }
        }
    }

    /**
     * Returns true if the current Ime focused field matches the session {@code requestInfo}.
     */
    @MainThread
    boolean match(@Nullable InlineSuggestionsRequestInfo requestInfo) {
        return match(requestInfo, mImeClientPackageName, mImeClientFieldId);
    }

    /**
     * Returns true if the current Ime focused field matches the {@code autofillId}.
     */
    @MainThread
    boolean match(@Nullable AutofillId autofillId) {
        return match(autofillId, mImeClientFieldId);
    }

    private static boolean match(
            @Nullable InlineSuggestionsRequestInfo inlineSuggestionsRequestInfo,
            @Nullable String imeClientPackageName, @Nullable AutofillId imeClientFieldId) {
        if (inlineSuggestionsRequestInfo == null || imeClientPackageName == null
                || imeClientFieldId == null) {
            return false;
        }
        return inlineSuggestionsRequestInfo.getComponentName().getPackageName().equals(
                imeClientPackageName) && match(inlineSuggestionsRequestInfo.getAutofillId(),
                imeClientFieldId);
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
