/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.accessibilityservice;

import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;

interface AccessibilityInputMethodSession {
    void finishInput();

    void updateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd);

    void invalidateInput(EditorInfo editorInfo, IRemoteAccessibilityInputConnection connection,
            int sessionId);

    void setEnabled(boolean enabled);
}
