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

package com.android.internal.inputmethod;

import android.net.Uri;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.IBooleanResultCallback;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IIInputContentUriTokenResultCallback;
import com.android.internal.inputmethod.IVoidResultCallback;

/**
 * Defines priviledged operations that only the current IME is allowed to call.
 * Actual operations are implemented and handled by InputMethodManagerService.
 */
oneway interface IInputMethodPrivilegedOperations {
    void setImeWindowStatusAsync(int vis, int backDisposition);
    void reportStartInputAsync(in IBinder startInputToken);
    void createInputContentUriToken(in Uri contentUri, in String packageName,
            in IIInputContentUriTokenResultCallback resultCallback);
    void reportFullscreenModeAsync(boolean fullscreen);
    void setInputMethod(String id, in IVoidResultCallback resultCallback);
    void setInputMethodAndSubtype(String id, in InputMethodSubtype subtype,
            in IVoidResultCallback resultCallback);
    void hideMySoftInput(int flags, in IVoidResultCallback resultCallback);
    void showMySoftInput(int flags, in IVoidResultCallback resultCallback);
    void updateStatusIconAsync(String packageName, int iconId);
    void switchToPreviousInputMethod(in IBooleanResultCallback resultCallback);
    void switchToNextInputMethod(boolean onlyCurrentIme, in IBooleanResultCallback resultCallback);
    void shouldOfferSwitchingToNextInputMethod(in IBooleanResultCallback resultCallback);
    void notifyUserActionAsync();
    void applyImeVisibilityAsync(IBinder showOrHideInputToken, boolean setVisible);
}
