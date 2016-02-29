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

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;

import static com.android.internal.util.Preconditions.checkNotNull;

/**
 * <p>Wrapper class for proxying calls to another InputConnection.  Subclass
 * and have fun!
 */
public class InputConnectionWrapper implements InputConnection {
    @NonNull
    private InputConnection mTarget;
    final boolean mMutable;

    /**
     * Initializes the wrapper for the given {@link InputConnection}.
     * @param target the {@link InputConnection} to be wrapped.
     * @param mutable {@code true} if the wrapper is to be mutable.
     * @throws NullPointerException if {@code target} is {@code null}.
     */
    public InputConnectionWrapper(@NonNull InputConnection target, boolean mutable) {
        checkNotNull(target);
        mMutable = mutable;
        mTarget = target;
    }

    /**
     * Change the target of the input connection.
     * @param target the {@link InputConnection} to be wrapped.
     * @throws NullPointerException if {@code target} is {@code null}.
     */
    public void setTarget(@NonNull InputConnection target) {
        checkNotNull(target);
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

    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return mTarget.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
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

    public Handler getHandler() {
        return mTarget.getHandler();
    }
}
