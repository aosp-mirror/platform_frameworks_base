/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.util.Slog;
import android.view.inputmethod.InlineSuggestionsRequest;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

class InlineSuggestionRequestConsumer implements Consumer<InlineSuggestionsRequest> {

    static final String TAG = "InlineSuggestionRequestConsumer";

    private final WeakReference<Session.AssistDataReceiverImpl> mAssistDataReceiverWeakReference;
    private final WeakReference<ViewState>  mViewStateWeakReference;

    InlineSuggestionRequestConsumer(WeakReference<Session.AssistDataReceiverImpl>
            assistDataReceiverWeakReference,
            WeakReference<ViewState>  viewStateWeakReference) {
        mAssistDataReceiverWeakReference = assistDataReceiverWeakReference;
        mViewStateWeakReference = viewStateWeakReference;
    }

    @Override
    public void accept(InlineSuggestionsRequest inlineSuggestionsRequest) {
        Session.AssistDataReceiverImpl assistDataReceiver = mAssistDataReceiverWeakReference.get();
        ViewState viewState = mViewStateWeakReference.get();
        if (assistDataReceiver == null) {
            Slog.wtf(TAG, "assistDataReceiver is null when accepting new inline suggestion"
                    + "requests");
            return;
        }

        if (viewState == null) {
            Slog.wtf(TAG, "view state is null when accepting new inline suggestion requests");
            return;
        }
        assistDataReceiver.handleInlineSuggestionRequest(inlineSuggestionsRequest, viewState);
    }
}
