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

import android.view.inputmethod.ImeTracker;
import com.android.internal.inputmethod.InputBindResult;

/**
 * Interface a client of the IInputMethodManager implements, to identify
 * itself and receive information about changes to the global manager state.
 */
oneway interface IInputMethodClient {
    void onBindMethod(in InputBindResult res);
    void onStartInputResult(in InputBindResult res, int startInputSeq);
    void onBindAccessibilityService(in InputBindResult res, int id);
    void onUnbindMethod(int sequence, int unbindReason);
    void onUnbindAccessibilityService(int sequence, int id);
    void setActive(boolean active, boolean fullscreen);
    void setInteractive(boolean active, boolean fullscreen);
    void setImeVisibility(boolean visible, in @nullable ImeTracker.Token statsToken);
    void scheduleStartInputIfNecessary(boolean fullscreen);
    void reportFullscreenMode(boolean fullscreen);
    void setImeTraceEnabled(boolean enabled);
    void throwExceptionFromSystem(String message);
}
