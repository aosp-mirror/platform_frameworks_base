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

import com.android.internal.view.InputBindResult;

/**
 * Interface a client of the IInputMethodManager implements, to identify
 * itself and receive information about changes to the global manager state.
 */
oneway interface IInputMethodClient {
    void setUsingInputMethod(boolean state);
    void onBindMethod(in InputBindResult res);
    // unbindReason corresponds to InputMethodClient.UnbindReason.
    void onUnbindMethod(int sequence, int unbindReason);
    void setActive(boolean active, boolean fullscreen);
    void setUserActionNotificationSequenceNumber(int sequenceNumber);
    void reportFullscreenMode(boolean fullscreen);
}
