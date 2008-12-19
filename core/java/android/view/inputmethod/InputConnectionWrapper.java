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
 * Wrapper around InputConnection interface, calling through to another
 * implementation of it.
 */
public class InputConnectionWrapper implements InputConnection {
    InputConnection mBase;
    
    /**
     * Create a new wrapper around an existing InputConnection implementation.
     */
    public InputConnectionWrapper(InputConnection base) {
        mBase = base;
    }
    
    /**
     * Return the base InputConnection that this class is wrapping.
     */
    InputConnection getBase() {
        return mBase;
    }
    
    public CharSequence getTextBeforeCursor(int n) {
        return mBase.getTextBeforeCursor(n);
    }

    public CharSequence getTextAfterCursor(int n) {
        return mBase.getTextAfterCursor(n);
    }

    public int getCursorCapsMode(int reqModes) {
        return mBase.getCursorCapsMode(reqModes);
    }
    
    public ExtractedText getExtractedText(ExtractedTextRequest request,
            int flags) {
        return mBase.getExtractedText(request, flags);
    }

    public boolean deleteSurroundingText(int leftLength, int rightLength) {
        return mBase.deleteSurroundingText(leftLength, rightLength);
    }

    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        return mBase.setComposingText(text, newCursorPosition);
    }

    public boolean commitText(CharSequence text, int newCursorPosition) {
        return mBase.commitText(text, newCursorPosition);
    }

    public boolean commitCompletion(CompletionInfo text) {
        return mBase.commitCompletion(text);
    }
    
    public boolean sendKeyEvent(KeyEvent event) {
        return mBase.sendKeyEvent(event);
    }

    public boolean clearMetaKeyStates(int states) {
        return mBase.clearMetaKeyStates(states);
    }
    
    public boolean performPrivateCommand(String action, Bundle data) {
        return mBase.performPrivateCommand(action, data);
    }
    
    public boolean showStatusIcon(String packageName, int resId) {
        return mBase.showStatusIcon(packageName, resId);
    }
    
    public boolean hideStatusIcon() {
        return mBase.hideStatusIcon();
    }
}
