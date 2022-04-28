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

package com.android.internal.inputmethod;

import android.view.KeyEvent;
import android.view.inputmethod.TextAttribute;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.InputConnectionCommandHeader;

/**
 * Interface from A11y IMEs to the application, allowing it to perform edits on the current input
 * field and other interactions with the application.
 */
oneway interface IRemoteAccessibilityInputConnection {
    void commitText(in InputConnectionCommandHeader header, CharSequence text,
            int newCursorPosition, in TextAttribute textAttribute);

    void setSelection(in InputConnectionCommandHeader header, int start, int end);

    void getSurroundingText(in InputConnectionCommandHeader header, int beforeLength,
            int afterLength, int flags, in AndroidFuture future /* T=SurroundingText */);

    void deleteSurroundingText(in InputConnectionCommandHeader header, int beforeLength,
            int afterLength);

    void sendKeyEvent(in InputConnectionCommandHeader header, in KeyEvent event);

    void performEditorAction(in InputConnectionCommandHeader header, int actionCode);

    void performContextMenuAction(in InputConnectionCommandHeader header, int id);

    void getCursorCapsMode(in InputConnectionCommandHeader header, int reqModes,
            in AndroidFuture future /* T=Integer */);

    void clearMetaKeyStates(in InputConnectionCommandHeader header, int states);
}
