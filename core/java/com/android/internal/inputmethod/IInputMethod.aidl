/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.InputChannel;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputMethodSubtype;
import android.window.ImeOnBackInvokedDispatcher;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;

/**
 * Top-level interface to an input method component (implemented in a Service).
 */
oneway interface IInputMethod {
    void initializeInternal(IBinder token, IInputMethodPrivilegedOperations privOps,
             int configChanges, boolean stylusHwSupported, int navigationBarFlags);

    void onCreateInlineSuggestionsRequest(in InlineSuggestionsRequestInfo requestInfo,
            in IInlineSuggestionsRequestCallback cb);

    void bindInput(in InputBinding binding);

    void unbindInput();

    void startInput(in IBinder startInputToken, in IRemoteInputConnection inputConnection,
            in EditorInfo editorInfo, boolean restarting, int navigationBarFlags,
            in ImeOnBackInvokedDispatcher imeDispatcher);

    void onNavButtonFlagsChanged(int navButtonFlags);

    void createSession(in InputChannel channel, IInputMethodSessionCallback callback);

    void setSessionEnabled(IInputMethodSession session, boolean enabled);

    void showSoftInput(in IBinder showInputToken, int flags, in ResultReceiver resultReceiver);

    void hideSoftInput(in IBinder hideInputToken, int flags, in ResultReceiver resultReceiver);

    void changeInputMethodSubtype(in InputMethodSubtype subtype);

    void canStartStylusHandwriting(int requestId);

    void startStylusHandwriting(int requestId, in InputChannel channel,
            in List<MotionEvent> events);

    void initInkWindow();

    void finishStylusHandwriting();
}
