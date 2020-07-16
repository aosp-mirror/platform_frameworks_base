/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.view;

import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestionsRequest;

import com.android.internal.view.IInlineSuggestionsResponseCallback;

/**
 * Binder interface for the IME service to send {@link InlineSuggestionsRequest} or notify other IME
 * service events to the system.
 * {@hide}
 */
oneway interface IInlineSuggestionsRequestCallback {
    /** Indicates that the current IME does not support inline suggestion. */
    void onInlineSuggestionsUnsupported();

    /**
     * Sends the inline suggestions request from IME to Autofill. Calling this method indicates
     * that the IME input is started on the view corresponding to the request.
     */
    void onInlineSuggestionsRequest(in InlineSuggestionsRequest request,
            in IInlineSuggestionsResponseCallback callback);

    /**
     * Signals that {@link android.inputmethodservice.InputMethodService
     * #onStartInput(EditorInfo, boolean)} is called on the given focused field.
     */
    void onInputMethodStartInput(in AutofillId imeFieldId);

    /**
     * Signals that {@link android.inputmethodservice.InputMethodService
     * #dispatchOnShowInputRequested(int, boolean)} is called and shares the call result.
     * The true value of {@code requestResult} means the IME is about to be shown, while
     * false value means the IME will not be shown.
     */
    void onInputMethodShowInputRequested(boolean requestResult);

    /**
     * Signals that {@link android.inputmethodservice.InputMethodService
     * #onStartInputView(EditorInfo, boolean)} is called on the field specified by the earlier
     * {@link #onInputMethodStartInput(AutofillId)}.
     */
    void onInputMethodStartInputView();

    /**
     * Signals that {@link android.inputmethodservice.InputMethodService
     * #onFinishInputView(boolean)} is called on the field specified by the earlier
     * {@link #onInputMethodStartInput(AutofillId)}.
     */
    void onInputMethodFinishInputView();

    /**
     * Signals that {@link android.inputmethodservice.InputMethodService
     * #onFinishInput()} is called on the field specified by the earlier
     * {@link #onInputMethodStartInput(AutofillId)}.
     */
    void onInputMethodFinishInput();

    // Indicates that the current IME changes inline suggestion session.
    void onInlineSuggestionsSessionInvalidated();
}
