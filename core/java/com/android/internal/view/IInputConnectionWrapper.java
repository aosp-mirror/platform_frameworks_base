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

import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.DumpableInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.CallbackUtils;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InputConnectionCommand;
import com.android.internal.inputmethod.InputConnectionCommandType;
import com.android.internal.inputmethod.InputConnectionProtoDumper;
import com.android.internal.inputmethod.InputMethodDebug;

import java.lang.ref.WeakReference;

public final class IInputConnectionWrapper extends IInputContext.Stub {
    private static final String TAG = "IInputConnectionWrapper";
    private static final boolean DEBUG = false;

    private static final int DO_EDIT = 10;
    private static final int DO_CLOSE_CONNECTION = 20;

    @GuardedBy("mLock")
    @Nullable
    private InputConnection mInputConnection;

    private Looper mMainLooper;
    private Handler mH;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mFinished = false;

    private final InputMethodManager mParentInputMethodManager;
    private final WeakReference<View> mServedView;

    class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message msg) {
            executeMessage(msg);
        }
    }

    public IInputConnectionWrapper(@NonNull Looper mainLooper,
            @NonNull InputConnection inputConnection,
            @NonNull InputMethodManager inputMethodManager, @Nullable View servedView) {
        mInputConnection = inputConnection;
        mMainLooper = mainLooper;
        mH = new MyHandler(mMainLooper);
        mParentInputMethodManager = inputMethodManager;
        mServedView = new WeakReference<>(servedView);
    }

    @Nullable
    public InputConnection getInputConnection() {
        synchronized (mLock) {
            return mInputConnection;
        }
    }

    private boolean isFinished() {
        synchronized (mLock) {
            return mFinished;
        }
    }

    public boolean isActive() {
        return mParentInputMethodManager.isActive() && !isFinished();
    }

    public View getServedView() {
        return mServedView.get();
    }

    public void deactivate() {
        if (isFinished()) {
            // This is a small performance optimization.  Still only the 1st call of
            // reportFinish() will take effect.
            return;
        }
        dispatchMessage(mH.obtainMessage(DO_CLOSE_CONNECTION));

        // Notify the app that the InputConnection was closed.
        final View servedView = mServedView.get();
        if (servedView != null) {
            final Handler handler = servedView.getHandler();
            // The handler is null if the view is already detached. When that's the case, for
            // now, we simply don't dispatch this callback.
            if (handler != null) {
                if (DEBUG) {
                    Log.v(TAG, "Calling View.onInputConnectionClosed: view=" + servedView);
                }
                if (handler.getLooper().isCurrentThread()) {
                    servedView.onInputConnectionClosedInternal();
                } else {
                    handler.post(servedView::onInputConnectionClosedInternal);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "IInputConnectionWrapper{"
                + "connection=" + getInputConnection()
                + " finished=" + isFinished()
                + " mParentInputMethodManager.isActive()=" + mParentInputMethodManager.isActive()
                + " mServedView=" + mServedView.get()
                + "}";
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            // Check that the call is initiated in the main thread of the current InputConnection
            // {@link InputConnection#getHandler} since the messages to IInputConnectionWrapper are
            // executed on this thread. Otherwise the messages are dispatched to the correct thread
            // in IInputConnectionWrapper, but this is not wanted while dumpng, for performance
            // reasons.
            if ((mInputConnection instanceof DumpableInputConnection)
                    && Looper.myLooper() == mMainLooper) {
                ((DumpableInputConnection) mInputConnection).dumpDebug(proto, fieldId);
            }
        }
    }

    @BinderThread
    @Override
    public void doEdit(@Nullable InputConnectionCommand command) {
        if (command == null) {
            // As long as everything is working as expected, we should never see any null object
            // here.  If we are seeing null object, it means that either the sender or
            // InputConnectionCommand.CREATOR#createFromParcel() returned null for whatever
            // unexpected reasons.  Note that InputConnectionCommand.CREATOR#createFromParcel() does
            // some data verifications.  Hence failing to pass the verification is one of the
            // reasons to see null here.
            Log.w(TAG, "Ignoring invalid InputConnectionCommand.");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "incoming: " + InputMethodDebug.dumpInputConnectionCommand(command));
        }
        dispatchMessage(mH.obtainMessage(DO_EDIT, command));
    }

    /**
     * Exposed for {@link InputMethodManager} to trigger
     * {@link InputConnection#finishComposingText()}.
     */
    @AnyThread
    public void finishComposingText() {
        dispatchMessage(mH.obtainMessage(DO_EDIT, InputConnectionCommand.create(
                InputConnectionCommandType.FINISH_COMPOSING_TEXT)));
    }

    void dispatchMessage(Message msg) {
        // If we are calling this from the main thread, then we can call
        // right through.  Otherwise, we need to send the message to the
        // main thread.
        if (Looper.myLooper() == mMainLooper) {
            executeMessage(msg);
            msg.recycle();
            return;
        }
        
        mH.sendMessage(msg);
    }

    private void executeMessage(Message msg) {
        switch (msg.what) {
            case DO_EDIT:
                doEditMain((InputConnectionCommand) msg.obj);
                break;
            case DO_CLOSE_CONNECTION:
                // Note that we do not need to worry about race condition here, because 1) mFinished
                // is updated only inside this block, and 2) the code here is running on a Handler
                // hence we assume multiple DO_CLOSE_CONNECTION messages will not be handled at the
                // same time.
                if (isFinished()) {
                    return;
                }
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#closeConnection");
                try {
                    InputConnection ic = getInputConnection();
                    // Note we do NOT check isActive() here, because this is safe
                    // for an IME to call at any time, and we need to allow it
                    // through to clean up our state after the IME has switched to
                    // another client.
                    if (ic == null) {
                        return;
                    }
                    @MissingMethodFlags final int missingMethods =
                            InputConnectionInspector.getMissingMethodFlags(ic);
                    if ((missingMethods & MissingMethodFlags.CLOSE_CONNECTION) == 0) {
                        ic.closeConnection();
                    }
                } finally {
                    synchronized (mLock) {
                        mInputConnection = null;
                        mFinished = true;
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                break;
        }
    }

    private void doEditMain(@NonNull InputConnectionCommand command) {
        if (DEBUG) {
            Log.d(TAG, "handling: " + InputMethodDebug.dumpInputConnectionCommand(command));
        }
        byte[] icProto;
        switch (command.mCommandType) {
            case InputConnectionCommandType.GET_TEXT_AFTER_CURSOR: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getTextAfterCursor");
                try {
                    final int n = command.mIntArg0;
                    final int flags = command.mFlags;
                    final InputConnection ic = getInputConnection();
                    final CharSequence result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getTextAfterCursor on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getTextAfterCursor(n, flags);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionProtoDumper.buildGetTextAfterCursorProto(n, flags,
                                result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getTextAfterCursor", mParentInputMethodManager, icProto);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.GET_TEXT_BEFORE_CURSOR: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getTextBeforeCursor");
                try {
                    final int n = command.mIntArg0;
                    final int flags = command.mFlags;
                    final InputConnection ic = getInputConnection();
                    final CharSequence result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getTextBeforeCursor on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getTextBeforeCursor(n, flags);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionProtoDumper.buildGetTextBeforeCursorProto(n, flags,
                                result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getTextBeforeCursor", mParentInputMethodManager, icProto);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.GET_SELECTED_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getSelectedText");
                try {
                    final int flags = command.mFlags;
                    final InputConnection ic = getInputConnection();
                    final CharSequence result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getSelectedText on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getSelectedText(flags);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionProtoDumper.buildGetSelectedTextProto(flags,
                                result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getSelectedText", mParentInputMethodManager, icProto);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.GET_SURROUNDING_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getSurroundingText");
                try {
                    final int beforeLength = command.mIntArg0;
                    final int afterLength  = command.mIntArg1;
                    final int flags = command.mFlags;
                    final InputConnection ic = getInputConnection();
                    final SurroundingText result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getSurroundingText on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getSurroundingText(beforeLength, afterLength, flags);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionProtoDumper.buildGetSurroundingTextProto(
                                beforeLength, afterLength, flags, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getSurroundingText", mParentInputMethodManager, icProto);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.GET_CURSOR_CAPS_MODE: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getCursorCapsMode");
                try {
                    final int reqModes = command.mIntArg0;
                    final InputConnection ic = getInputConnection();
                    final int result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getCursorCapsMode on inactive InputConnection");
                        result = 0;
                    } else {
                        result = ic.getCursorCapsMode(reqModes);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionProtoDumper.buildGetCursorCapsModeProto(reqModes,
                                result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getCursorCapsMode", mParentInputMethodManager, icProto);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.GET_EXTRACTED_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getExtractedText");
                try {
                    final ExtractedTextRequest request = (ExtractedTextRequest) command.mParcelable;
                    final int flags = command.mFlags;
                    final InputConnection ic = getInputConnection();
                    final ExtractedText result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getExtractedText on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getExtractedText(request, flags);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionProtoDumper.buildGetExtractedTextProto(request,
                                flags, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getExtractedText", mParentInputMethodManager, icProto);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.COMMIT_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitText");
                try {
                    final CharSequence text = command.mCharSequence;
                    final int newCursorPosition = command.mIntArg0;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitText on inactive InputConnection");
                        return;
                    }
                    ic.commitText(text, newCursorPosition);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.SET_SELECTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#setSelection");
                try {
                    final int start = command.mIntArg0;
                    final int end = command.mIntArg1;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "setSelection on inactive InputConnection");
                        return;
                    }
                    ic.setSelection(start, end);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.PERFORM_EDITOR_ACTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performEditorAction");
                try {
                    final int editorAction = command.mIntArg0;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performEditorAction on inactive InputConnection");
                        return;
                    }
                    ic.performEditorAction(editorAction);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.PERFORM_CONTEXT_MENU_ACTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performContextMenuAction");
                try {
                    final int id = command.mIntArg0;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performContextMenuAction on inactive InputConnection");
                        return;
                    }
                    ic.performContextMenuAction(id);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.COMMIT_COMPLETION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitCompletion");
                try {
                    final CompletionInfo text = (CompletionInfo) command.mParcelable;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitCompletion on inactive InputConnection");
                        return;
                    }
                    ic.commitCompletion(text);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.COMMIT_CORRECTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitCorrection");
                try {
                    final CorrectionInfo correctionInfo = (CorrectionInfo) command.mParcelable;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitCorrection on inactive InputConnection");
                        return;
                    }
                    ic.commitCorrection(correctionInfo);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.SET_COMPOSING_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#setComposingText");
                try {
                    final CharSequence text = command.mCharSequence;
                    final int newCursorPosition = command.mIntArg0;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "setComposingText on inactive InputConnection");
                        return;
                    }
                    ic.setComposingText(text, newCursorPosition);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.SET_COMPOSING_REGION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#setComposingRegion");
                try {
                    final int start = command.mIntArg0;
                    final int end = command.mIntArg1;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "setComposingRegion on inactive InputConnection");
                        return;
                    }
                    ic.setComposingRegion(start, end);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.FINISH_COMPOSING_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#finishComposingText");
                try {
                    if (isFinished()) {
                        // In this case, #finishComposingText() is guaranteed to be called already.
                        // There should be no negative impact if we ignore this call silently.
                        if (DEBUG) {
                            Log.w(TAG, "Bug 35301295: Redundant finishComposingText.");
                        }
                        return;
                    }
                    InputConnection ic = getInputConnection();
                    // Note we do NOT check isActive() here, because this is safe
                    // for an IME to call at any time, and we need to allow it
                    // through to clean up our state after the IME has switched to
                    // another client.
                    if (ic == null) {
                        Log.w(TAG, "finishComposingText on inactive InputConnection");
                        return;
                    }
                    ic.finishComposingText();
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.SEND_KEY_EVENT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#sendKeyEvent");
                try {
                    final KeyEvent event = (KeyEvent) command.mParcelable;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "sendKeyEvent on inactive InputConnection");
                        return;
                    }
                    ic.sendKeyEvent(event);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.CLEAR_META_KEY_STATES: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#clearMetaKeyStates");
                try {
                    final int states = command.mIntArg0;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "clearMetaKeyStates on inactive InputConnection");
                        return;
                    }
                    ic.clearMetaKeyStates(states);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.DELETE_SURROUNDING_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#deleteSurroundingText");
                try {
                    final int beforeLength = command.mIntArg0;
                    final int afterLength = command.mIntArg1;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "deleteSurroundingText on inactive InputConnection");
                        return;
                    }
                    ic.deleteSurroundingText(beforeLength, afterLength);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT,
                        "InputConnection#deleteSurroundingTextInCodePoints");
                try {
                    final int beforeLength = command.mIntArg0;
                    final int afterLength = command.mIntArg1;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "deleteSurroundingTextInCodePoints on inactive InputConnection");
                        return;
                    }
                    ic.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.BEGIN_BATCH_EDIT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#beginBatchEdit");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "beginBatchEdit on inactive InputConnection");
                        return;
                    }
                    ic.beginBatchEdit();
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.END_BATCH_EDIT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#endBatchEdit");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "endBatchEdit on inactive InputConnection");
                        return;
                    }
                    ic.endBatchEdit();
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.PERFORM_SPELL_CHECK: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performSpellCheck");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performSpellCheck on inactive InputConnection");
                        return;
                    }
                    ic.performSpellCheck();
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.PERFORM_PRIVATE_COMMAND: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performPrivateCommand");
                try {
                    final String action = command.mString;
                    final Bundle data = command.mBundle;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performPrivateCommand on inactive InputConnection");
                        return;
                    }
                    ic.performPrivateCommand(action, data);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.REQUEST_CURSOR_UPDATES: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#requestCursorUpdates");
                try {
                    final int cursorUpdateMode = command.mIntArg0;
                    final InputConnection ic = getInputConnection();
                    final boolean result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "requestCursorAnchorInfo on inactive InputConnection");
                        result = false;
                    } else {
                        result = ic.requestCursorUpdates(cursorUpdateMode);
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.COMMIT_CONTENT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitContent");
                try {
                    final InputContentInfo inputContentInfo =
                            (InputContentInfo) command.mParcelable;
                    final int flags = command.mFlags;
                    final Bundle opts = command.mBundle;
                    final InputConnection ic = getInputConnection();
                    final boolean result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitContent on inactive InputConnection");
                        result = false;
                    } else {
                        if (inputContentInfo == null || !inputContentInfo.validate()) {
                            Log.w(TAG, "commitContent with invalid inputContentInfo="
                                    + inputContentInfo);
                            result = false;
                        } else {
                            result = ic.commitContent(inputContentInfo, flags, opts);
                        }
                    }
                    CallbackUtils.onResult(command, result, TAG);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case InputConnectionCommandType.SET_IME_CONSUMES_INPUT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT,
                        "InputConnection#setImeConsumesInput");
                try {
                    final boolean imeConsumesInput = (command.mIntArg0 != 0);
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG,
                                "setImeConsumesInput on inactive InputConnection");
                        return;
                    }
                    ic.setImeConsumesInput(imeConsumesInput);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
        }
    }
}
