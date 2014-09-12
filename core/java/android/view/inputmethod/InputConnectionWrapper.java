/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.os.Bundle;
import android.view.KeyEvent;

/**
 * <p>Wrapper class for proxying calls to another InputConnection.  Subclass
 * and have fun!
 */
public class InputConnectionWrapper implements InputConnection {
    private InputConnection mTarget;
    final boolean mMutable;
    
    public InputConnectionWrapper(InputConnection target, boolean mutable) {
        mMutable = mutable;
        mTarget = target;
    }

    /**
     * Change the target of the input connection.
     */
    public void setTarget(InputConnection target) {
        if (mTarget != null && !mMutable) {
            throw new SecurityException("not mutable");
        }
        mTarget = target;
    }
    
    public CharSequence getTextBeforeCursor(int n, int flags) {
        return mTarget.getTextBeforeCursor(n, flags);
    }
    
    public CharSequence getTextAfterCursor(int n, int flags) {
        return mTarget.getTextAfterCursor(n, flags);
    }

    public CharSequence getSelectedText(int flags) {
        return mTarget.getSelectedText(flags);
    }

    public int getCursorCapsMode(int reqModes) {
        return mTarget.getCursorCapsMode(reqModes);
    }
    
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        return mTarget.getExtractedText(request, flags);
    }

    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return mTarget.deleteSurroundingText(beforeLength, afterLength);
    }

    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        return mTarget.setComposingText(text, newCursorPosition);
    }

    public boolean setComposingRegion(int start, int end) {
        return mTarget.setComposingRegion(start, end);
    }

    public boolean finishComposingText() {
        return mTarget.finishComposingText();
    }
    
    public boolean commitText(CharSequence text, int newCursorPosition) {
        return mTarget.commitText(text, newCursorPosition);
    }

    public boolean commitCompletion(CompletionInfo text) {
        return mTarget.commitCompletion(text);
    }

    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        return mTarget.commitCorrection(correctionInfo);
    }

    public boolean setSelection(int start, int end) {
        return mTarget.setSelection(start, end);
    }
    
    public boolean performEditorAction(int editorAction) {
        return mTarget.performEditorAction(editorAction);
    }
    
    public boolean performContextMenuAction(int id) {
        return mTarget.performContextMenuAction(id);
    }
    
    public boolean beginBatchEdit() {
        return mTarget.beginBatchEdit();
    }
    
    public boolean endBatchEdit() {
        return mTarget.endBatchEdit();
    }
    
    public boolean sendKeyEvent(KeyEvent event) {
        return mTarget.sendKeyEvent(event);
    }

    public boolean clearMetaKeyStates(int states) {
        return mTarget.clearMetaKeyStates(states);
    }
    
    public boolean reportFullscreenMode(boolean enabled) {
        return mTarget.reportFullscreenMode(enabled);
    }
    
    public boolean performPrivateCommand(String action, Bundle data) {
        return mTarget.performPrivateCommand(action, data);
    }

    public boolean requestCursorUpdates(int cursorUpdateMode) {
        return mTarget.requestCursorUpdates(cursorUpdateMode);
    }
}
