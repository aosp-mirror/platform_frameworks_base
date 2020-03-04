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
 * Binder interface for the IME service to send an inline suggestion request to the system.
 * {@hide}
 */
oneway interface IInlineSuggestionsRequestCallback {
    void onInlineSuggestionsUnsupported();
    void onInlineSuggestionsRequest(in InlineSuggestionsRequest request,
            in IInlineSuggestionsResponseCallback callback, in AutofillId imeFieldId,
            boolean inputViewStarted);
    void onInputMethodStartInputView(in AutofillId imeFieldId);
    void onInputMethodFinishInputView(in AutofillId imeFieldId);
}
