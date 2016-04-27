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

import com.android.internal.annotations.GuardedBy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;

public abstract class IInputConnectionWrapper extends IInputContext.Stub {
    static final String TAG = "IInputConnectionWrapper";

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
    private static final int DO_REPORT_FULLSCREEN_MODE = 100;
    private static final int DO_PERFORM_PRIVATE_COMMAND = 120;
    private static final int DO_CLEAR_META_KEY_STATES = 130;
    private static final int DO_REQUEST_UPDATE_CURSOR_ANCHOR_INFO = 140;
    private static final int DO_CLOSE_CONNECTION = 150;

    @GuardedBy("mLock")
    @Nullable
    private InputConnection mInputConnection;

    private Looper mMainLooper;
    private Handler mH;
    private Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mFinished = false;
    @GuardedBy("mLock")
    private String mInputMethodId;

    static class SomeArgs {
        Object arg1;
        Object arg2;
        IInputContextCallback callback;
        int seq;
    }
    
    class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message msg) {
            executeMessage(msg);
        }
    }
    
    public IInputConnectionWrapper(Looper mainLooper, @NonNull InputConnection inputConnection) {
        mInputConnection = inputConnection;
        mMainLooper = mainLooper;
        mH = new MyHandler(mMainLooper);
    }

    @Nullable
    public InputConnection getInputConnection() {
        synchronized (mLock) {
            return mInputConnection;
        }
    }

    protected boolean isFinished() {
        synchronized (mLock) {
            return mFinished;
        }
    }

    public String getInputMethodId() {
        synchronized (mLock) {
            return mInputMethodId;
        }
    }

    public void setInputMethodId(final String inputMethodId) {
        synchronized (mLock) {
            mInputMethodId = inputMethodId;
        }
    }

    abstract protected boolean isActive();

    /**
     * Called when the user took some actions that should be taken into consideration to update the
     * LRU list for input method rotation.
     */
    abstract protected void onUserAction();

    /**
     * Called when the input method started or stopped full-screen mode.
     * @param enabled {@code true} if the input method starts full-screen mode.
     * @param calledInBackground {@code true} if this input connection is in a state when incoming
     * events are usually ignored.
     */
    abstract protected void onReportFullscreenMode(boolean enabled, boolean calledInBackground);

    public void getTextAfterCursor(int length, int flags, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageIISC(DO_GET_TEXT_AFTER_CURSOR, length, flags, seq, callback));
    }
    
    public void getTextBeforeCursor(int length, int flags, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageIISC(DO_GET_TEXT_BEFORE_CURSOR, length, flags, seq, callback));
    }

    public void getSelectedText(int flags, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageISC(DO_GET_SELECTED_TEXT, flags, seq, callback));
    }

    public void getCursorCapsMode(int reqModes, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageISC(DO_GET_CURSOR_CAPS_MODE, reqModes, seq, callback));
    }

    public void getExtractedText(ExtractedTextRequest request,
            int flags, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageIOSC(DO_GET_EXTRACTED_TEXT, flags,
                request, seq, callback));
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

    public void reportFullscreenMode(boolean enabled) {
        dispatchMessage(obtainMessageII(DO_REPORT_FULLSCREEN_MODE, enabled ? 1 : 0, 0));
    }

    public void performPrivateCommand(String action, Bundle data) {
        dispatchMessage(obtainMessageOO(DO_PERFORM_PRIVATE_COMMAND, action, data));
    }

    public void requestUpdateCursorAnchorInfo(int cursorUpdateMode, int seq,
            IInputContextCallback callback) {
        dispatchMessage(obtainMessageISC(DO_REQUEST_UPDATE_CURSOR_ANCHOR_INFO, cursorUpdateMode,
                seq, callback));
    }

    public void closeConnection() {
        dispatchMessage(obtainMessage(DO_CLOSE_CONNECTION));
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
        switch (msg.what) {
            case DO_GET_TEXT_AFTER_CURSOR: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getTextAfterCursor on inactive InputConnection");
                        args.callback.setTextAfterCursor(null, args.seq);
                        return;
                    }
                    args.callback.setTextAfterCursor(ic.getTextAfterCursor(
                            msg.arg1, msg.arg2), args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setTextAfterCursor", e);
                }
                return;
            }
            case DO_GET_TEXT_BEFORE_CURSOR: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getTextBeforeCursor on inactive InputConnection");
                        args.callback.setTextBeforeCursor(null, args.seq);
                        return;
                    }
                    args.callback.setTextBeforeCursor(ic.getTextBeforeCursor(
                            msg.arg1, msg.arg2), args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setTextBeforeCursor", e);
                }
                return;
            }
            case DO_GET_SELECTED_TEXT: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getSelectedText on inactive InputConnection");
                        args.callback.setSelectedText(null, args.seq);
                        return;
                    }
                    args.callback.setSelectedText(ic.getSelectedText(
                            msg.arg1), args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setSelectedText", e);
                }
                return;
            }
            case DO_GET_CURSOR_CAPS_MODE: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getCursorCapsMode on inactive InputConnection");
                        args.callback.setCursorCapsMode(0, args.seq);
                        return;
                    }
                    args.callback.setCursorCapsMode(ic.getCursorCapsMode(msg.arg1),
                            args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setCursorCapsMode", e);
                }
                return;
            }
            case DO_GET_EXTRACTED_TEXT: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "getExtractedText on inactive InputConnection");
                        args.callback.setExtractedText(null, args.seq);
                        return;
                    }
                    args.callback.setExtractedText(ic.getExtractedText(
                            (ExtractedTextRequest)args.arg1, msg.arg1), args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setExtractedText", e);
                }
                return;
            }
            case DO_COMMIT_TEXT: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "commitText on inactive InputConnection");
                    return;
                }
                ic.commitText((CharSequence)msg.obj, msg.arg1);
                onUserAction();
                return;
            }
            case DO_SET_SELECTION: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "setSelection on inactive InputConnection");
                    return;
                }
                ic.setSelection(msg.arg1, msg.arg2);
                return;
            }
            case DO_PERFORM_EDITOR_ACTION: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "performEditorAction on inactive InputConnection");
                    return;
                }
                ic.performEditorAction(msg.arg1);
                return;
            }
            case DO_PERFORM_CONTEXT_MENU_ACTION: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "performContextMenuAction on inactive InputConnection");
                    return;
                }
                ic.performContextMenuAction(msg.arg1);
                return;
            }
            case DO_COMMIT_COMPLETION: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "commitCompletion on inactive InputConnection");
                    return;
                }
                ic.commitCompletion((CompletionInfo)msg.obj);
                return;
            }
            case DO_COMMIT_CORRECTION: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "commitCorrection on inactive InputConnection");
                    return;
                }
                ic.commitCorrection((CorrectionInfo)msg.obj);
                return;
            }
            case DO_SET_COMPOSING_TEXT: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "setComposingText on inactive InputConnection");
                    return;
                }
                ic.setComposingText((CharSequence)msg.obj, msg.arg1);
                onUserAction();
                return;
            }
            case DO_SET_COMPOSING_REGION: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "setComposingRegion on inactive InputConnection");
                    return;
                }
                ic.setComposingRegion(msg.arg1, msg.arg2);
                return;
            }
            case DO_FINISH_COMPOSING_TEXT: {
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
                return;
            }
            case DO_SEND_KEY_EVENT: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "sendKeyEvent on inactive InputConnection");
                    return;
                }
                ic.sendKeyEvent((KeyEvent)msg.obj);
                onUserAction();
                return;
            }
            case DO_CLEAR_META_KEY_STATES: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "clearMetaKeyStates on inactive InputConnection");
                    return;
                }
                ic.clearMetaKeyStates(msg.arg1);
                return;
            }
            case DO_DELETE_SURROUNDING_TEXT: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "deleteSurroundingText on inactive InputConnection");
                    return;
                }
                ic.deleteSurroundingText(msg.arg1, msg.arg2);
                return;
            }
            case DO_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "deleteSurroundingTextInCodePoints on inactive InputConnection");
                    return;
                }
                ic.deleteSurroundingTextInCodePoints(msg.arg1, msg.arg2);
                return;
            }
            case DO_BEGIN_BATCH_EDIT: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "beginBatchEdit on inactive InputConnection");
                    return;
                }
                ic.beginBatchEdit();
                return;
            }
            case DO_END_BATCH_EDIT: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "endBatchEdit on inactive InputConnection");
                    return;
                }
                ic.endBatchEdit();
                return;
            }
            case DO_REPORT_FULLSCREEN_MODE: {
                InputConnection ic = getInputConnection();
                boolean isBackground = false;
                if (ic == null || !isActive()) {
                    Log.w(TAG, "reportFullscreenMode on inexistent InputConnection");
                    isBackground = true;
                }
                final boolean enabled = msg.arg1 == 1;
                if (!isBackground) {
                    ic.reportFullscreenMode(enabled);
                }
                // Due to the nature of asynchronous event handling, currently InputMethodService
                // has relied on the fact that #reportFullscreenMode() can be handled even when the
                // InputConnection is inactive.  We have to notify this event to InputMethodManager.
                onReportFullscreenMode(enabled, isBackground);
                return;
            }
            case DO_PERFORM_PRIVATE_COMMAND: {
                InputConnection ic = getInputConnection();
                if (ic == null || !isActive()) {
                    Log.w(TAG, "performPrivateCommand on inactive InputConnection");
                    return;
                }
                SomeArgs args = (SomeArgs)msg.obj;
                ic.performPrivateCommand((String)args.arg1,
                        (Bundle)args.arg2);
                return;
            }
            case DO_REQUEST_UPDATE_CURSOR_ANCHOR_INFO: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    InputConnection ic = getInputConnection();
                    if (ic == null || !isActive()) {
                        Log.w(TAG, "requestCursorAnchorInfo on inactive InputConnection");
                        args.callback.setRequestUpdateCursorAnchorInfoResult(false, args.seq);
                        return;
                    }
                    args.callback.setRequestUpdateCursorAnchorInfoResult(
                            ic.requestCursorUpdates(msg.arg1), args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling requestCursorAnchorInfo", e);
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
    
    Message obtainMessageISC(int what, int arg1, int seq, IInputContextCallback callback) {
        SomeArgs args = new SomeArgs();
        args.callback = callback;
        args.seq = seq;
        return mH.obtainMessage(what, arg1, 0, args);
    }
    
    Message obtainMessageIISC(int what, int arg1, int arg2, int seq, IInputContextCallback callback) {
        SomeArgs args = new SomeArgs();
        args.callback = callback;
        args.seq = seq;
        return mH.obtainMessage(what, arg1, arg2, args);
    }

    Message obtainMessageOSC(int what, Object arg1, int seq, IInputContextCallback callback) {
        SomeArgs args = new SomeArgs();
        args.arg1 = arg1;
        args.callback = callback;
        args.seq = seq;
        return mH.obtainMessage(what, 0, 0, args);
    }

    Message obtainMessageIOSC(int what, int arg1, Object arg2, int seq,
            IInputContextCallback callback) {
        SomeArgs args = new SomeArgs();
        args.arg1 = arg2;
        args.callback = callback;
        args.seq = seq;
        return mH.obtainMessage(what, arg1, 0, args);
    }
    
    Message obtainMessageIO(int what, int arg1, Object arg2) {
        return mH.obtainMessage(what, arg1, 0, arg2);
    }
    
    Message obtainMessageOO(int what, Object arg1, Object arg2) {
        SomeArgs args = new SomeArgs();
        args.arg1 = arg1;
        args.arg2 = arg2;
        return mH.obtainMessage(what, 0, 0, args);
    }
}
