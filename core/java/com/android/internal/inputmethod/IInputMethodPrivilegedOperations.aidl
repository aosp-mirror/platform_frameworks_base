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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.IInputContentUriToken;

/**
 * Defines priviledged operations that only the current IME is allowed to call.
 * Actual operations are implemented and handled by InputMethodManagerService.
 */
interface IInputMethodPrivilegedOperations {
    void setImeWindowStatus(int vis, int backDisposition);
    void reportStartInput(in IBinder startInputToken);
    IInputContentUriToken createInputContentUriToken(in Uri contentUri, in String packageName);
    void reportFullscreenMode(boolean fullscreen);
    void setInputMethod(String id);
    void setInputMethodAndSubtype(String id, in InputMethodSubtype subtype);
    void hideMySoftInput(int flags);
    void showMySoftInput(int flags);
    void updateStatusIcon(String packageName, int iconId);
    boolean switchToPreviousInputMethod();
    boolean switchToNextInputMethod(boolean onlyCurrentIme);
    boolean shouldOfferSwitchingToNextInputMethod();
    void notifyUserAction();
    void reportPreRendered(in EditorInfo info);
    void applyImeVisibility(boolean setVisible);
}
