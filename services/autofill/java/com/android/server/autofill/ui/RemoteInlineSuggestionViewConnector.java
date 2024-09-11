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

package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.IBinder;
import android.service.autofill.IInlineSuggestionUiCallback;
import android.service.autofill.InlinePresentation;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.autofill.RemoteInlineSuggestionRenderService;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.util.function.Consumer;

/**
 * Wraps the parameters needed to create a new inline suggestion view in the remote renderer
 * service, and handles the callback from the events on the created remote view.
 */
final class RemoteInlineSuggestionViewConnector {
    private static final String TAG = RemoteInlineSuggestionViewConnector.class.getSimpleName();

    @Nullable
    private final RemoteInlineSuggestionRenderService mRemoteRenderService;
    @NonNull
    private final InlinePresentation mInlinePresentation;
    @Nullable
    private final IBinder mHostInputToken;
    private final int mDisplayId;
    private final int mUserId;
    private final int mSessionId;

    @NonNull
    private final Runnable mOnAutofillCallback;
    @NonNull
    private final Runnable mOnErrorCallback;
    @NonNull
    private final Runnable mOnInflateCallback;
    @NonNull
    private final Consumer<IntentSender> mStartIntentSenderFromClientApp;

    RemoteInlineSuggestionViewConnector(
            @NonNull InlineFillUi.InlineFillUiInfo inlineFillUiInfo,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull Runnable onAutofillCallback,
            @NonNull InlineFillUi.InlineSuggestionUiCallback uiCallback) {
        mRemoteRenderService = inlineFillUiInfo.mRemoteRenderService;
        mInlinePresentation = inlinePresentation;
        mHostInputToken = inlineFillUiInfo.mInlineRequest.getHostInputToken();
        mDisplayId = inlineFillUiInfo.mInlineRequest.getHostDisplayId();
        mUserId = inlineFillUiInfo.mUserId;
        mSessionId = inlineFillUiInfo.mSessionId;

        mOnAutofillCallback = onAutofillCallback;
        mOnErrorCallback = uiCallback::onError;
        mOnInflateCallback = uiCallback::onInflate;
        mStartIntentSenderFromClientApp = uiCallback::startIntentSender;
    }

    /**
     * Calls the remote renderer service to create a new inline suggestion view.
     *
     * @return true if the call is made to the remote renderer service, false otherwise.
     */
    public boolean renderSuggestion(int width, int height,
            @NonNull IInlineSuggestionUiCallback callback) {
        if (mRemoteRenderService != null) {
            if (sDebug) Slog.d(TAG, "Request to recreate the UI");
            mRemoteRenderService.renderSuggestion(callback, mInlinePresentation, width, height,
                    mHostInputToken, mDisplayId, mUserId, mSessionId);
            return true;
        }
        return false;
    }

    /**
     * Handles the callback for the event of remote view being clicked.
     */
    public void onClick() {
        mOnAutofillCallback.run();
    }

    /**
     * Handles the callback for the remote error when creating or interacting with the view.
     */
    public void onError() {
        mOnErrorCallback.run();
    }

    public void onRender() {
        mOnInflateCallback.run();
    }

    /**
     * Handles the callback for transferring the touch event on the remote view to the IME
     * process.
     */
    public void onTransferTouchFocusToImeWindow(IBinder sourceInputToken, int displayId) {
        final InputMethodManagerInternal inputMethodManagerInternal =
                LocalServices.getService(InputMethodManagerInternal.class);
        if (!inputMethodManagerInternal.transferTouchFocusToImeWindow(sourceInputToken,
                displayId, mUserId)) {
            Slog.e(TAG, "Cannot transfer touch focus from suggestion to IME");
            mOnErrorCallback.run();
        }
    }

    /**
     * Handles starting an intent sender from the client app's process.
     */
    public void onStartIntentSender(IntentSender intentSender) {
        mStartIntentSenderFromClientApp.accept(intentSender);
    }
}
