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
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.infra.AndroidFuture;

/**
 * Defines priviledged operations that only the current IME is allowed to call.
 * Actual operations are implemented and handled by InputMethodManagerService.
 */
oneway interface IInputMethodPrivilegedOperations {
    void setImeWindowStatusAsync(int vis, int backDisposition);
    void reportStartInputAsync(in IBinder startInputToken);
    void createInputContentUriToken(in Uri contentUri, in String packageName,
            in AndroidFuture future /* T=IBinder */);
    void reportFullscreenModeAsync(boolean fullscreen);
    void setInputMethod(String id, in AndroidFuture future /* T=Void */);
    void setInputMethodAndSubtype(String id, in InputMethodSubtype subtype,
            in AndroidFuture future /* T=Void */);
    void hideMySoftInput(in ImeTracker.Token statsToken, int flags, int reason,
            in AndroidFuture future /* T=Void */);
    void showMySoftInput(in ImeTracker.Token statsToken, int flags, int reason,
            in AndroidFuture future /* T=Void */);
    void updateStatusIconAsync(String packageName, int iconId);
    void switchToPreviousInputMethod(in AndroidFuture future /* T=Boolean */);
    void switchToNextInputMethod(boolean onlyCurrentIme, in AndroidFuture future /* T=Boolean */);
    void shouldOfferSwitchingToNextInputMethod(in AndroidFuture future /* T=Boolean */);
    void notifyUserActionAsync();
    void applyImeVisibilityAsync(IBinder showOrHideInputToken, boolean setVisible,
            in ImeTracker.Token statsToken);
    void onStylusHandwritingReady(int requestId, int pid);
    void resetStylusHandwriting(int requestId);
    void switchKeyboardLayoutAsync(int direction);
    void setHandwritingSurfaceNotTouchable(boolean notTouchable);
}
