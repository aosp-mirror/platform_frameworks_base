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

package com.android.internal.inputmethod;

import android.annotation.BinderThread;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;

/**
 * An internal interface that mirrors {@link IInlineSuggestionsRequestCallback}.
 *
 * <p>This interface is used to forward incoming IPCs from
 * {@link com.android.server.inputmethod.AutofillSuggestionsController} to
 * {@link com.android.server.autofill.AutofillInlineSuggestionsRequestSession}.</p>
 */
public interface InlineSuggestionsRequestCallback {

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInlineSuggestionsUnsupported()}.
     */
    @BinderThread
    void onInlineSuggestionsUnsupported();

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInlineSuggestionsRequest(
     * InlineSuggestionsRequest, IInlineSuggestionsResponseCallback)}.
     */
    @BinderThread
    void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
            IInlineSuggestionsResponseCallback callback);

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInputMethodStartInput(AutofillId)}.
     */
    @BinderThread
    void onInputMethodStartInput(AutofillId imeFieldId);

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInputMethodShowInputRequested(boolean)}.
     */
    @BinderThread
    void onInputMethodShowInputRequested(boolean requestResult);

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInputMethodStartInputView()}.
     */
    @BinderThread
    void onInputMethodStartInputView();

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInputMethodFinishInputView()}.
     */
    @BinderThread
    void onInputMethodFinishInputView();

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInputMethodFinishInput()}.
     */
    @BinderThread
    void onInputMethodFinishInput();

    /**
     * Forwards {@link IInlineSuggestionsRequestCallback#onInlineSuggestionsSessionInvalidated()}.
     */
    @BinderThread
    void onInlineSuggestionsSessionInvalidated();
}
