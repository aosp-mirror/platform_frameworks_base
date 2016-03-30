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

package com.android.internal.widget;

import android.os.Bundle;
import android.text.Editable;
import android.text.Spanned;
import android.text.method.KeyListener;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

public class EditableInputConnection extends BaseInputConnection {
    private static final boolean DEBUG = false;
    private static final String TAG = "EditableInputConnection";

    private final TextView mTextView;

    // Keeps track of nested begin/end batch edit to ensure this connection always has a
    // balanced impact on its associated TextView.
    // A negative value means that this connection has been finished by the InputMethodManager.
    private int mBatchEditNesting;

    public EditableInputConnection(TextView textview) {
        super(textview, true);
        mTextView = textview;
    }

    @Override
    public Editable getEditable() {
        TextView tv = mTextView;
        if (tv != null) {
            return tv.getEditableText();
        }
        return null;
    }

    @Override
    public boolean beginBatchEdit() {
        synchronized(this) {
            if (mBatchEditNesting >= 0) {
                mTextView.beginBatchEdit();
                mBatchEditNesting++;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean endBatchEdit() {
        synchronized(this) {
            if (mBatchEditNesting > 0) {
                // When the connection is reset by the InputMethodManager and reportFinish
                // is called, some endBatchEdit calls may still be asynchronously received from the
                // IME. Do not take these into account, thus ensuring that this IC's final
                // contribution to mTextView's nested batch edit count is zero.
                mTextView.endBatchEdit();
                mBatchEditNesting--;
                return true;
            }
        }
        return false;
    }

    @Override
    public void closeConnection() {
        super.closeConnection();
        synchronized(this) {
            while (mBatchEditNesting > 0) {
                endBatchEdit();
            }
            // Will prevent any further calls to begin or endBatchEdit
            mBatchEditNesting = -1;
        }
    }

    @Override
    public boolean clearMetaKeyStates(int states) {
        final Editable content = getEditable();
        if (content == null) return false;
        KeyListener kl = mTextView.getKeyListener();
        if (kl != null) {
            try {
                kl.clearMetaKeyState(mTextView, content, states);
            } catch (AbstractMethodError e) {
                // This is an old listener that doesn't implement the
                // new method.
            }
        }
        return true;
    }

    @Override
    public boolean commitCompletion(CompletionInfo text) {
        if (DEBUG) Log.v(TAG, "commitCompletion " + text);
        mTextView.beginBatchEdit();
        mTextView.onCommitCompletion(text);
        mTextView.endBatchEdit();
        return true;
    }

    /**
     * Calls the {@link TextView#onCommitCorrection} method of the associated TextView.
     */
    @Override
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        if (DEBUG) Log.v(TAG, "commitCorrection" + correctionInfo);
        mTextView.beginBatchEdit();
        mTextView.onCommitCorrection(correctionInfo);
        mTextView.endBatchEdit();
        return true;
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        if (DEBUG) Log.v(TAG, "performEditorAction " + actionCode);
        mTextView.onEditorAction(actionCode);
        return true;
    }
    
    @Override
    public boolean performContextMenuAction(int id) {
        if (DEBUG) Log.v(TAG, "performContextMenuAction " + id);
        mTextView.beginBatchEdit();
        mTextView.onTextContextMenuItem(id);
        mTextView.endBatchEdit();
        return true;
    }
    
    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (mTextView != null) {
            ExtractedText et = new ExtractedText();
            if (mTextView.extractText(request, et)) {
                if ((flags&GET_EXTRACTED_TEXT_MONITOR) != 0) {
                    mTextView.setExtracting(request);
                }
                return et;
            }
        }
        return null;
    }

    @Override
    public boolean performPrivateCommand(String action, Bundle data) {
        mTextView.onPrivateIMECommand(action, data);
        return true;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (mTextView == null) {
            return super.commitText(text, newCursorPosition);
        }
        if (text instanceof Spanned) {
            Spanned spanned = ((Spanned) text);
            SuggestionSpan[] spans = spanned.getSpans(0, text.length(), SuggestionSpan.class);
            mIMM.registerSuggestionSpansForNotification(spans);
        }

        mTextView.resetErrorChangedFlag();
        boolean success = super.commitText(text, newCursorPosition);
        mTextView.hideErrorIfUnchanged();

        return success;
    }

    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        if (DEBUG) Log.v(TAG, "requestUpdateCursorAnchorInfo " + cursorUpdateMode);

        // It is possible that any other bit is used as a valid flag in a future release.
        // We should reject the entire request in such a case.
        final int KNOWN_FLAGS_MASK = InputConnection.CURSOR_UPDATE_IMMEDIATE |
                InputConnection.CURSOR_UPDATE_MONITOR;
        final int unknownFlags = cursorUpdateMode & ~KNOWN_FLAGS_MASK;
        if (unknownFlags != 0) {
            if (DEBUG) {
                Log.d(TAG, "Rejecting requestUpdateCursorAnchorInfo due to unknown flags." +
                        " cursorUpdateMode=" + cursorUpdateMode +
                        " unknownFlags=" + unknownFlags);
            }
            return false;
        }

        if (mIMM == null) {
            // In this case, TYPE_CURSOR_ANCHOR_INFO is not handled.
            // TODO: Return some notification code rather than false to indicate method that
            // CursorAnchorInfo is temporarily unavailable.
            return false;
        }
        mIMM.setUpdateCursorAnchorInfoMode(cursorUpdateMode);
        if ((cursorUpdateMode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0) {
            if (mTextView == null) {
                // In this case, FLAG_CURSOR_ANCHOR_INFO_IMMEDIATE is silently ignored.
                // TODO: Return some notification code for the input method that indicates
                // FLAG_CURSOR_ANCHOR_INFO_IMMEDIATE is ignored.
            } else if (mTextView.isInLayout()) {
                // In this case, the view hierarchy is currently undergoing a layout pass.
                // IMM#updateCursorAnchorInfo is supposed to be called soon after the layout
                // pass is finished.
            } else {
                // This will schedule a layout pass of the view tree, and the layout event
                // eventually triggers IMM#updateCursorAnchorInfo.
                mTextView.requestLayout();
            }
        }
        return true;
    }
}
