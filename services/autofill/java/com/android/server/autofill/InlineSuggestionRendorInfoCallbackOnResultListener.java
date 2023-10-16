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

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

final class InlineSuggestionRendorInfoCallbackOnResultListener implements
        RemoteCallback.OnResultListener{
    private static final String TAG = "InlineSuggestionRendorInfoCallbackOnResultListener";

    private final int mRequestIdCopy;
    private final AutofillId mFocusedId;
    private final WeakReference<Session> mSessionWeakReference;
    private final Consumer<InlineSuggestionsRequest> mInlineSuggestionsRequestConsumer;

    InlineSuggestionRendorInfoCallbackOnResultListener(WeakReference<Session> sessionWeakReference,
            int requestIdCopy,
            Consumer<InlineSuggestionsRequest> inlineSuggestionsRequestConsumer,
            AutofillId focusedId) {
        this.mRequestIdCopy = requestIdCopy;
        this.mInlineSuggestionsRequestConsumer = inlineSuggestionsRequestConsumer;
        this.mSessionWeakReference = sessionWeakReference;
        this.mFocusedId = focusedId;
    }
    public void onResult(@Nullable Bundle result) {
        Session session = this.mSessionWeakReference.get();
        if (session == null) {
            Slog.wtf(TAG, "Session is null before trying to call onResult");
            return;
        }
        synchronized (session.mLock) {
            if (session.mDestroyed) {
                Slog.wtf(TAG, "Session is destroyed before trying to call onResult");
                return;
            }
            session.mInlineSessionController.onCreateInlineSuggestionsRequestLocked(
                    this.mFocusedId,
                    session.inlineSuggestionsRequestCacheDecorator(
                        this.mInlineSuggestionsRequestConsumer, this.mRequestIdCopy),
                    result);
        }
    }
}
