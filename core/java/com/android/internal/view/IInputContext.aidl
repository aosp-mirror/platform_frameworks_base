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

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputContentInfo;

import com.android.internal.view.IInputContextCallback;

/**
 * Interface from an input method to the application, allowing it to perform
 * edits on the current input field and other interactions with the application.
 * {@hide}
 */
 oneway interface IInputContext {
    void getTextBeforeCursor(int length, int flags, int seq, IInputContextCallback callback); 

    void getTextAfterCursor(int length, int flags, int seq, IInputContextCallback callback);
    
    void getCursorCapsMode(int reqModes, int seq, IInputContextCallback callback);
    
    void getExtractedText(in ExtractedTextRequest request, int flags, int seq,
            IInputContextCallback callback);

    void deleteSurroundingText(int beforeLength, int afterLength);
    void deleteSurroundingTextInCodePoints(int beforeLength, int afterLength);

    void setComposingText(CharSequence text, int newCursorPosition);

    void finishComposingText();
    
    void commitText(CharSequence text, int newCursorPosition);

    void commitCompletion(in CompletionInfo completion);

    void commitCorrection(in CorrectionInfo correction);

    void setSelection(int start, int end);
    
    void performEditorAction(int actionCode);
    
    void performContextMenuAction(int id);
    
    void beginBatchEdit();
    
    void endBatchEdit();

    void sendKeyEvent(in KeyEvent event);
    
    void clearMetaKeyStates(int states);
    
    void performPrivateCommand(String action, in Bundle data);

    void setComposingRegion(int start, int end);

    void getSelectedText(int flags, int seq, IInputContextCallback callback);

    void requestUpdateCursorAnchorInfo(int cursorUpdateMode, int seq,
            IInputContextCallback callback);

    void commitContent(in InputContentInfo inputContentInfo, int flags, in Bundle opts, int sec,
            IInputContextCallback callback);
}
