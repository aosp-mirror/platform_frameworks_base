/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.view.inputmethod.InputConnectionProto.CURSOR_CAPS_MODE;
import static android.view.inputmethod.InputConnectionProto.SELECTED_TEXT_END;
import static android.view.inputmethod.InputConnectionProto.SELECTED_TEXT_START;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.Editable;
import android.text.Selection;
import android.text.method.KeyListener;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.DeleteGesture;
import android.view.inputmethod.DeleteRangeGesture;
import android.view.inputmethod.DumpableInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.HandwritingGesture;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InsertGesture;
import android.view.inputmethod.InsertModeGesture;
import android.view.inputmethod.JoinOrSplitGesture;
import android.view.inputmethod.PreviewableHandwritingGesture;
import android.view.inputmethod.RemoveSpaceGesture;
import android.view.inputmethod.SelectGesture;
import android.view.inputmethod.SelectRangeGesture;
import android.view.inputmethod.TextBoundsInfo;
import android.view.inputmethod.TextBoundsInfoResult;
import android.widget.TextView;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Base class for an editable InputConnection instance. This is created by {@link TextView} or
 * {@link android.widget.EditText}.
 */
public final class EditableInputConnection extends BaseInputConnection
        implements DumpableInputConnection {
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
        synchronized (this) {
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
        synchronized (this) {
            if (mBatchEditNesting > 0) {
                // When the connection is reset by the InputMethodManager and reportFinish
                // is called, some endBatchEdit calls may still be asynchronously received from the
                // IME. Do not take these into account, thus ensuring that this IC's final
                // contribution to mTextView's nested batch edit count is zero.
                mTextView.endBatchEdit();
                mBatchEditNesting--;
                return mBatchEditNesting > 0;
            }
        }
        return false;
    }

    @Override
    public void endComposingRegionEditInternal() {
        // The ContentCapture service is interested in Composing-state changes.
        mTextView.notifyContentCaptureTextChanged();
    }

    @Override
    public void closeConnection() {
        super.closeConnection();
        synchronized (this) {
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
                if ((flags & GET_EXTRACTED_TEXT_MONITOR) != 0) {
                    mTextView.setExtracting(request);
                }
                return et;
            }
        }
        return null;
    }

    @Override
    public boolean performSpellCheck() {
        mTextView.onPerformSpellCheck();
        return true;
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
        mTextView.resetErrorChangedFlag();
        boolean success = super.commitText(text, newCursorPosition);
        mTextView.hideErrorIfUnchanged();

        return success;
    }

    @Override
    public boolean requestCursorUpdates(
            @CursorUpdateMode int cursorUpdateMode, @CursorUpdateFilter int cursorUpdateFilter) {
        // TODO(b/210039666): use separate attrs for updateMode and updateFilter.
        return requestCursorUpdates(cursorUpdateMode | cursorUpdateFilter);
    }

    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        if (DEBUG) Log.v(TAG, "requestUpdateCursorAnchorInfo " + cursorUpdateMode);

        final int knownModeFlags = InputConnection.CURSOR_UPDATE_IMMEDIATE
                | InputConnection.CURSOR_UPDATE_MONITOR;
        final int knownFilterFlags = InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS
                | InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
                | InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS
                | InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS
                | InputConnection.CURSOR_UPDATE_FILTER_TEXT_APPEARANCE;

        // It is possible that any other bit is used as a valid flag in a future release.
        // We should reject the entire request in such a case.
        final int knownFlagMask = knownModeFlags | knownFilterFlags;
        final int unknownFlags = cursorUpdateMode & ~knownFlagMask;
        if (unknownFlags != 0) {
            if (DEBUG) {
                Log.d(TAG, "Rejecting requestUpdateCursorAnchorInfo due to unknown flags. "
                        + "cursorUpdateMode=" + cursorUpdateMode + " unknownFlags=" + unknownFlags);
            }
            return false;
        }

        if (mIMM == null) {
            // In this case, TYPE_CURSOR_ANCHOR_INFO is not handled.
            // TODO: Return some notification code rather than false to indicate method that
            // CursorAnchorInfo is temporarily unavailable.
            return false;
        }
        mIMM.setUpdateCursorAnchorInfoMode(cursorUpdateMode);  // for UnsupportedAppUsage
        if (mTextView != null) {
            mTextView.onRequestCursorUpdatesInternal(cursorUpdateMode & knownModeFlags,
                    cursorUpdateMode & knownFilterFlags);
        }
        return true;
    }

    @Override
    public void requestTextBoundsInfo(
            @NonNull RectF bounds, @Nullable @CallbackExecutor Executor executor,
            @NonNull Consumer<TextBoundsInfoResult> consumer) {
        final TextBoundsInfo textBoundsInfo = mTextView.getTextBoundsInfo(bounds);
        final int resultCode;
        if (textBoundsInfo != null) {
            resultCode = TextBoundsInfoResult.CODE_SUCCESS;
        } else {
            resultCode = TextBoundsInfoResult.CODE_FAILED;
        }
        final TextBoundsInfoResult textBoundsInfoResult =
                new TextBoundsInfoResult(resultCode, textBoundsInfo);

        executor.execute(() -> consumer.accept(textBoundsInfoResult));
    }

    @Override
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        if (mTextView == null) {
            return super.setImeConsumesInput(imeConsumesInput);
        }
        mTextView.setImeConsumesInput(imeConsumesInput);
        return true;
    }

    @Override
    public void performHandwritingGesture(
            @NonNull HandwritingGesture gesture, @Nullable @CallbackExecutor Executor executor,
            @Nullable IntConsumer consumer) {
        int result;
        if (gesture instanceof SelectGesture) {
            result = mTextView.performHandwritingSelectGesture((SelectGesture) gesture);
        } else if (gesture instanceof SelectRangeGesture) {
            result = mTextView.performHandwritingSelectRangeGesture((SelectRangeGesture) gesture);
        } else if (gesture instanceof DeleteGesture) {
            result = mTextView.performHandwritingDeleteGesture((DeleteGesture) gesture);
        } else if (gesture instanceof DeleteRangeGesture) {
            result = mTextView.performHandwritingDeleteRangeGesture((DeleteRangeGesture) gesture);
        } else if (gesture instanceof InsertGesture) {
            result = mTextView.performHandwritingInsertGesture((InsertGesture) gesture);
        } else if (gesture instanceof RemoveSpaceGesture) {
            result = mTextView.performHandwritingRemoveSpaceGesture((RemoveSpaceGesture) gesture);
        } else if (gesture instanceof JoinOrSplitGesture) {
            result = mTextView.performHandwritingJoinOrSplitGesture((JoinOrSplitGesture) gesture);
        } else if (gesture instanceof InsertModeGesture) {
            result = mTextView.performHandwritingInsertModeGesture((InsertModeGesture) gesture);
        } else {
            result = HANDWRITING_GESTURE_RESULT_UNSUPPORTED;
        }
        if (executor != null && consumer != null) {
            executor.execute(() -> consumer.accept(result));
        }
    }

    @Override
    public boolean previewHandwritingGesture(
            @NonNull PreviewableHandwritingGesture gesture,
            @Nullable CancellationSignal cancellationSignal) {
        return mTextView.previewHandwritingGesture(gesture, cancellationSignal);
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        final Editable content = getEditable();
        if (content != null) {
            int start = Selection.getSelectionStart(content);
            int end = Selection.getSelectionEnd(content);
            proto.write(SELECTED_TEXT_START, start);
            proto.write(SELECTED_TEXT_END, end);
        }
        proto.write(CURSOR_CAPS_MODE, getCursorCapsMode(0));
        proto.end(token);
    }
}
