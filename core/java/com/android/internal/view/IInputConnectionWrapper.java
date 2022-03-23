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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.util.imetracing.ImeTracing;
import android.util.imetracing.InputConnectionHelper;
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
import com.android.internal.inputmethod.ICharSequenceResultCallback;
import com.android.internal.inputmethod.IExtractedTextResultCallback;
import com.android.internal.inputmethod.IIntResultCallback;
import com.android.internal.inputmethod.ISurroundingTextResultCallback;
import com.android.internal.os.SomeArgs;

import java.lang.ref.WeakReference;

public final class IInputConnectionWrapper extends IInputContext.Stub {
    private static final String TAG = "IInputConnectionWrapper";
    private static final boolean DEBUG = false;

    private static final int DO_GET_TEXT_AFTER_CURSOR = 10;
    private static final int DO_GET_TEXT_BEFORE_CURSOR = 20;
    private static final int DO_GET_SELECTED_TEXT = 25;
    private static final int DO_GET_CURSOR_CAPS_MODE = 30;
    private static final int DO_GET_EXTRACTED_TEXT = 40;
    private static final int DO_COMMIT_TEXT = 50;
    private static final int DO_COMMIT_COMPLETION = 55;
    private static final int DO_COMMIT_CORRECTION = 56;
    private static final int DO_SET_SELECTION = 57;
    private static final int DO_PERFORM_EDITOR_ACTION = 58;
    private static final int DO_PERFORM_CONTEXT_MENU_ACTION = 59;
    private static final int DO_SET_COMPOSING_TEXT = 60;
    private static final int DO_SET_COMPOSING_REGION = 63;
    private static final int DO_FINISH_COMPOSING_TEXT = 65;
    private static final int DO_SEND_KEY_EVENT = 70;
    private static final int DO_DELETE_SURROUNDING_TEXT = 80;
    private static final int DO_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS = 81;
    private static final int DO_BEGIN_BATCH_EDIT = 90;
    private static final int DO_END_BATCH_EDIT = 95;
    private static final int DO_PERFORM_SPELL_CHECK = 110;
    private static final int DO_PERFORM_PRIVATE_COMMAND = 120;
    private static final int DO_CLEAR_META_KEY_STATES = 130;
    private static final int DO_REQUEST_UPDATE_CURSOR_ANCHOR_INFO = 140;
    private static final int DO_CLOSE_CONNECTION = 150;
    private static final int DO_COMMIT_CONTENT = 160;
    private static final int DO_GET_SURROUNDING_TEXT = 41;
    private static final int DO_SET_IME_CONSUMES_INPUT = 170;


    @GuardedBy("mLock")
    @Nullable
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private InputConnection mInputConnection;

    private Looper mMainLooper;
    private Handler mH;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
        closeConnection();

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

    public void getTextAfterCursor(int length, int flags, ICharSequenceResultCallback callback) {
        dispatchMessage(mH.obtainMessage(DO_GET_TEXT_AFTER_CURSOR, length, flags, callback));
    }

    public void getTextBeforeCursor(int length, int flags, ICharSequenceResultCallback callback) {
        dispatchMessage(mH.obtainMessage(DO_GET_TEXT_BEFORE_CURSOR, length, flags, callback));
    }

    public void getSelectedText(int flags, ICharSequenceResultCallback callback) {
        dispatchMessage(mH.obtainMessage(DO_GET_SELECTED_TEXT, flags, 0 /* unused */, callback));
    }

    /**
     * Dispatches the request for retrieving surrounding text.
     *
     * <p>See {@link InputConnection#getSurroundingText(int, int, int)}.
     */
    public void getSurroundingText(int beforeLength, int afterLength, int flags,
            ISurroundingTextResultCallback callback) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = beforeLength;
        args.arg2 = afterLength;
        args.arg3 = flags;
        args.arg4 = callback;
        dispatchMessage(mH.obtainMessage(DO_GET_SURROUNDING_TEXT, flags, 0 /* unused */, args));
    }

    public void getCursorCapsMode(int reqModes, IIntResultCallback callback) {
        dispatchMessage(
                mH.obtainMessage(DO_GET_CURSOR_CAPS_MODE, reqModes, 0 /* unused */, callback));
    }

    public void getExtractedText(ExtractedTextRequest request, int flags,
            IExtractedTextResultCallback callback) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = request;
        args.arg2 = callback;
        dispatchMessage(mH.obtainMessage(DO_GET_EXTRACTED_TEXT, flags, 0 /* unused */, args));
    }

    public void commitText(CharSequence text, int newCursorPosition) {
        dispatchMessage(obtainMessageIO(DO_COMMIT_TEXT, newCursorPosition, text));
    }

    public void commitCompletion(CompletionInfo text) {
        dispatchMessage(obtainMessageO(DO_COMMIT_COMPLETION, text));
    }

    public void commitCorrection(CorrectionInfo info) {
        dispatchMessage(obtainMessageO(DO_COMMIT_CORRECTION, info));
    }

    public void setSelection(int start, int end) {
        dispatchMessage(obtainMessageII(DO_SET_SELECTION, start, end));
    }

    public void performEditorAction(int id) {
        dispatchMessage(obtainMessageII(DO_PERFORM_EDITOR_ACTION, id, 0));
    }
    
    public void performContextMenuAction(int id) {
        dispatchMessage(obtainMessageII(DO_PERFORM_CONTEXT_MENU_ACTION, id, 0));
    }
    
    public void setComposingRegion(int start, int end) {
        dispatchMessage(obtainMessageII(DO_SET_COMPOSING_REGION, start, end));
    }

    public void setComposingText(CharSequence text, int newCursorPosition) {
        dispatchMessage(obtainMessageIO(DO_SET_COMPOSING_TEXT, newCursorPosition, text));
    }

    public void finishComposingText() {
        dispatchMessage(obtainMessage(DO_FINISH_COMPOSING_TEXT));
    }

    public void sendKeyEvent(KeyEvent event) {
        dispatchMessage(obtainMessageO(DO_SEND_KEY_EVENT, event));
    }

    public void clearMetaKeyStates(int states) {
        dispatchMessage(obtainMessageII(DO_CLEAR_META_KEY_STATES, states, 0));
    }

    public void deleteSurroundingText(int beforeLength, int afterLength) {
        dispatchMessage(obtainMessageII(DO_DELETE_SURROUNDING_TEXT,
                beforeLength, afterLength));
    }

    public void deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        dispatchMessage(obtainMessageII(DO_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS,
                beforeLength, afterLength));
    }

    public void beginBatchEdit() {
        dispatchMessage(obtainMessage(DO_BEGIN_BATCH_EDIT));
    }

    public void endBatchEdit() {
        dispatchMessage(obtainMessage(DO_END_BATCH_EDIT));
    }

    /**
     * Dispatches the request for performing spell check.
     *
     * @see InputConnection#performSpellCheck()
     */
    public void performSpellCheck() {
        dispatchMessage(obtainMessage(DO_PERFORM_SPELL_CHECK));
    }

    public void performPrivateCommand(String action, Bundle data) {
        dispatchMessage(obtainMessageOO(DO_PERFORM_PRIVATE_COMMAND, action, data));
    }

    public void requestUpdateCursorAnchorInfo(int cursorUpdateMode, IIntResultCallback callback) {
        dispatchMessage(mH.obtainMessage(DO_REQUEST_UPDATE_CURSOR_ANCHOR_INFO, cursorUpdateMode,
                0 /* unused */, callback));
    }

    public void closeConnection() {
        dispatchMessage(obtainMessage(DO_CLOSE_CONNECTION));
    }

    public void commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts,
            IIntResultCallback callback) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = inputContentInfo;
        args.arg2 = opts;
        args.arg3 = callback;
        dispatchMessage(mH.obtainMessage(DO_COMMIT_CONTENT, flags, 0 /* unused */, args));
    }

    /**
     * Dispatches the request for setting ime consumes input.
     *
     * <p>See {@link InputConnection#setImeConsumesInput(boolean)}.
     */
    public void setImeConsumesInput(boolean imeConsumesInput) {
        dispatchMessage(obtainMessageB(DO_SET_IME_CONSUMES_INPUT, imeConsumesInput));
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

    void executeMessage(Message msg) {
        ProtoOutputStream icProto;
        switch (msg.what) {
            case DO_GET_TEXT_AFTER_CURSOR: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getTextAfterCursor");
                try {
                    final ICharSequenceResultCallback callback =
                            (ICharSequenceResultCallback) msg.obj;
                    final InputConnection ic = getInputConnection();
                    final CharSequence result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getTextAfterCursor on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getTextAfterCursor(msg.arg1, msg.arg2);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionHelper.buildGetTextAfterCursorProto(msg.arg1,
                                msg.arg2, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getTextAfterCursor", mParentInputMethodManager, icProto);
                    }
                    try {
                        callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to getTextAfterCursor()."
                            + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_GET_TEXT_BEFORE_CURSOR: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getTextBeforeCursor");
                try {
                    final ICharSequenceResultCallback callback =
                            (ICharSequenceResultCallback) msg.obj;
                    final InputConnection ic = getInputConnection();
                    final CharSequence result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getTextBeforeCursor on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getTextBeforeCursor(msg.arg1, msg.arg2);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionHelper.buildGetTextBeforeCursorProto(msg.arg1,
                                msg.arg2, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getTextBeforeCursor", mParentInputMethodManager, icProto);
                    }
                    try {
                        callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to getTextBeforeCursor()."
                            + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_GET_SELECTED_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getSelectedText");
                try {
                    final ICharSequenceResultCallback callback =
                            (ICharSequenceResultCallback) msg.obj;
                    final InputConnection ic = getInputConnection();
                    final CharSequence result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getSelectedText on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getSelectedText(msg.arg1);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionHelper.buildGetSelectedTextProto(msg.arg1, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getSelectedText", mParentInputMethodManager, icProto);
                    }
                    try {
                        callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to getSelectedText()."
                                + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_GET_SURROUNDING_TEXT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getSurroundingText");
                try {
                    int beforeLength = (int) args.arg1;
                    int afterLength  = (int) args.arg2;
                    int flags = (int) args.arg3;
                    final ISurroundingTextResultCallback callback =
                            (ISurroundingTextResultCallback) args.arg4;
                    final InputConnection ic = getInputConnection();
                    final SurroundingText result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getSurroundingText on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getSurroundingText(beforeLength, afterLength, flags);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionHelper.buildGetSurroundingTextProto(beforeLength,
                                afterLength, flags, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getSurroundingText", mParentInputMethodManager, icProto);
                    }
                    try {
                        callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to getSurroundingText()."
                                + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                    args.recycle();
                }
                return;
            }
            case DO_GET_CURSOR_CAPS_MODE: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getCursorCapsMode");
                try {
                    final IIntResultCallback callback = (IIntResultCallback) msg.obj;
                    final InputConnection ic = getInputConnection();
                    final int result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getCursorCapsMode on inactive InputConnection");
                        result = 0;
                    } else {
                        result = ic.getCursorCapsMode(msg.arg1);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionHelper.buildGetCursorCapsModeProto(msg.arg1,
                                result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getCursorCapsMode", mParentInputMethodManager, icProto);
                    }
                    try {
                        callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to getCursorCapsMode()."
                            + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_GET_EXTRACTED_TEXT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#getExtractedText");
                try {
                    final ExtractedTextRequest request = (ExtractedTextRequest) args.arg1;
                    final IExtractedTextResultCallback callback =
                            (IExtractedTextResultCallback) args.arg2;
                    final InputConnection ic = getInputConnection();
                    final ExtractedText result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getExtractedText on inactive InputConnection");
                        result = null;
                    } else {
                        result = ic.getExtractedText(request, msg.arg1);
                    }
                    if (ImeTracing.getInstance().isEnabled()) {
                        icProto = InputConnectionHelper.buildGetExtractedTextProto(request,
                                msg.arg1, result);
                        ImeTracing.getInstance().triggerClientDump(
                                TAG + "#getExtractedText", mParentInputMethodManager, icProto);
                    }
                    try {
                        callback.onResult(result);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to getExtractedText()."
                                + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                    args.recycle();
                }
                return;
            }
            case DO_COMMIT_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitText");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitText on inactive InputConnection");
                        return;
                    }
                    ic.commitText((CharSequence) msg.obj, msg.arg1);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_SET_SELECTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#setSelection");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "setSelection on inactive InputConnection");
                        return;
                    }
                    ic.setSelection(msg.arg1, msg.arg2);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_PERFORM_EDITOR_ACTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performEditorAction");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performEditorAction on inactive InputConnection");
                        return;
                    }
                    ic.performEditorAction(msg.arg1);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_PERFORM_CONTEXT_MENU_ACTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performContextMenuAction");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performContextMenuAction on inactive InputConnection");
                        return;
                    }
                    ic.performContextMenuAction(msg.arg1);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_COMMIT_COMPLETION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitCompletion");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitCompletion on inactive InputConnection");
                        return;
                    }
                    ic.commitCompletion((CompletionInfo) msg.obj);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_COMMIT_CORRECTION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitCorrection");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitCorrection on inactive InputConnection");
                        return;
                    }
                    ic.commitCorrection((CorrectionInfo) msg.obj);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_SET_COMPOSING_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#setComposingText");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "setComposingText on inactive InputConnection");
                        return;
                    }
                    ic.setComposingText((CharSequence) msg.obj, msg.arg1);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_SET_COMPOSING_REGION: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#setComposingRegion");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "setComposingRegion on inactive InputConnection");
                        return;
                    }
                    ic.setComposingRegion(msg.arg1, msg.arg2);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_FINISH_COMPOSING_TEXT: {
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
            case DO_SEND_KEY_EVENT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#sendKeyEvent");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "sendKeyEvent on inactive InputConnection");
                        return;
                    }
                    ic.sendKeyEvent((KeyEvent) msg.obj);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_CLEAR_META_KEY_STATES: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#clearMetaKeyStates");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "clearMetaKeyStates on inactive InputConnection");
                        return;
                    }
                    ic.clearMetaKeyStates(msg.arg1);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_DELETE_SURROUNDING_TEXT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#deleteSurroundingText");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "deleteSurroundingText on inactive InputConnection");
                        return;
                    }
                    ic.deleteSurroundingText(msg.arg1, msg.arg2);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT,
                        "InputConnection#deleteSurroundingTextInCodePoints");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "deleteSurroundingTextInCodePoints on inactive InputConnection");
                        return;
                    }
                    ic.deleteSurroundingTextInCodePoints(msg.arg1, msg.arg2);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_BEGIN_BATCH_EDIT: {
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
            case DO_END_BATCH_EDIT: {
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
            case DO_PERFORM_SPELL_CHECK: {
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
            case DO_PERFORM_PRIVATE_COMMAND: {
                final SomeArgs args = (SomeArgs) msg.obj;
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#performPrivateCommand");
                try {
                    final String action = (String) args.arg1;
                    final Bundle data = (Bundle) args.arg2;
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "performPrivateCommand on inactive InputConnection");
                        return;
                    }
                    ic.performPrivateCommand(action, data);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                    args.recycle();
                }
                return;
            }
            case DO_REQUEST_UPDATE_CURSOR_ANCHOR_INFO: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#requestCursorUpdates");
                try {
                    final IIntResultCallback callback = (IIntResultCallback) msg.obj;
                    final InputConnection ic = getInputConnection();
                    final boolean result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "requestCursorAnchorInfo on inactive InputConnection");
                        result = false;
                    } else {
                        result = ic.requestCursorUpdates(msg.arg1);
                    }
                    try {
                        callback.onResult(result ? 1 : 0);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to requestCursorUpdates()."
                                + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
            case DO_CLOSE_CONNECTION: {
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
                    @MissingMethodFlags
                    final int missingMethods = InputConnectionInspector.getMissingMethodFlags(ic);
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
                return;
            }
            case DO_COMMIT_CONTENT: {
                final int flags = msg.arg1;
                SomeArgs args = (SomeArgs) msg.obj;
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#commitContent");
                try {
                    final IIntResultCallback callback = (IIntResultCallback) args.arg3;
                    final InputConnection ic = getInputConnection();
                    final boolean result;
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "commitContent on inactive InputConnection");
                        result = false;
                    } else {
                        final InputContentInfo inputContentInfo = (InputContentInfo) args.arg1;
                        if (inputContentInfo == null || !inputContentInfo.validate()) {
                            Log.w(TAG, "commitContent with invalid inputContentInfo="
                                    + inputContentInfo);
                            result = false;
                        } else {
                            result = ic.commitContent(inputContentInfo, flags, (Bundle) args.arg2);
                        }
                    }
                    try {
                        callback.onResult(result ? 1 : 0);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to return the result to commitContent()."
                                + " result=" + result, e);
                    }
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                    args.recycle();
                }
                return;
            }
            case DO_SET_IME_CONSUMES_INPUT: {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT,
                        "InputConnection#setImeConsumesInput");
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG,
                                "setImeConsumesInput on inactive InputConnection");
                        return;
                    }
                    ic.setImeConsumesInput(msg.arg1 == 1);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
                return;
            }
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
    }

    Message obtainMessage(int what) {
        return mH.obtainMessage(what);
    }

    Message obtainMessageII(int what, int arg1, int arg2) {
        return mH.obtainMessage(what, arg1, arg2);
    }

    Message obtainMessageO(int what, Object arg1) {
        return mH.obtainMessage(what, 0, 0, arg1);
    }

    Message obtainMessageIO(int what, int arg1, Object arg2) {
        return mH.obtainMessage(what, arg1, 0, arg2);
    }

    Message obtainMessageOO(int what, Object arg1, Object arg2) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.arg2 = arg2;
        return mH.obtainMessage(what, 0, 0, args);
    }

    Message obtainMessageB(int what, boolean arg1) {
        return mH.obtainMessage(what, arg1 ? 1 : 0, 0);
    }
}
