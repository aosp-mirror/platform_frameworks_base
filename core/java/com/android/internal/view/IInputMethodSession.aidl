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

import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;

import com.android.internal.view.IInputContext;

/**
 * Sub-interface of IInputMethod which is safe to give to client applications.
 * {@hide}
 */
oneway interface IInputMethodSession {
    void updateExtractedText(int token, in ExtractedText text);

    void updateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd);

    void viewClicked(boolean focusChanged);

    void updateCursor(in Rect newCursor);

    void displayCompletions(in CompletionInfo[] completions);

    void appPrivateCommand(String action, in Bundle data);

    void finishSession();

    void updateCursorAnchorInfo(in CursorAnchorInfo cursorAnchorInfo);

    void removeImeSurface();

    void finishInput();

    void invalidateInput(in EditorInfo editorInfo, in IInputContext inputContext, int sessionId);
}
