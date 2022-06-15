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

package com.android.internal.view;

import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.InputChannel;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputMethodSubtype;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;

/**
 * Top-level interface to an input method component (implemented in a
 * Service).
 * {@hide}
 */
oneway interface IInputMethod {
    void initializeInternal(IBinder token, int displayId, IInputMethodPrivilegedOperations privOps,
             int configChanges);

    void onCreateInlineSuggestionsRequest(in InlineSuggestionsRequestInfo requestInfo,
            in IInlineSuggestionsRequestCallback cb);

    void bindInput(in InputBinding binding);

    void unbindInput();

    void startInput(in IBinder startInputToken, in IInputContext inputContext, int missingMethods,
            in EditorInfo attribute, boolean restarting);

    void createSession(in InputChannel channel, IInputSessionCallback callback);

    void setSessionEnabled(IInputMethodSession session, boolean enabled);

    void revokeSession(IInputMethodSession session);

    void showSoftInput(in IBinder showInputToken, int flags, in ResultReceiver resultReceiver);

    void hideSoftInput(in IBinder hideInputToken, int flags, in ResultReceiver resultReceiver);

    void changeInputMethodSubtype(in InputMethodSubtype subtype);
}
