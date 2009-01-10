package com.android.internal.view;

import com.android.internal.view.IInputContext;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

public class IInputConnectionWrapper extends IInputContext.Stub {
    static final String TAG = "IInputConnectionWrapper";
    
    private static final int DO_GET_TEXT_AFTER_CURSOR = 10;
    private static final int DO_GET_TEXT_BEFORE_CURSOR = 20;
    private static final int DO_GET_CURSOR_CAPS_MODE = 30;
    private static final int DO_GET_EXTRACTED_TEXT = 40;
    private static final int DO_COMMIT_TEXT = 50;
    private static final int DO_COMMIT_COMPLETION = 55;
    private static final int DO_SET_COMPOSING_TEXT = 60;
    private static final int DO_FINISH_COMPOSING_TEXT = 65;
    private static final int DO_SEND_KEY_EVENT = 70;
    private static final int DO_DELETE_SURROUNDING_TEXT = 80;
    private static final int DO_HIDE_STATUS_ICON = 100;
    private static final int DO_SHOW_STATUS_ICON = 110;
    private static final int DO_PERFORM_PRIVATE_COMMAND = 120;
    private static final int DO_CLEAR_META_KEY_STATES = 130;
        
    private InputConnection mInputConnection;

    private Looper mMainLooper;
    private Handler mH;

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
    
    public IInputConnectionWrapper(Looper mainLooper, InputConnection conn) {
        mInputConnection = conn;
        mMainLooper = mainLooper;
        mH = new MyHandler(mMainLooper);
    }

    public void getTextAfterCursor(int length, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageISC(DO_GET_TEXT_AFTER_CURSOR, length, seq, callback));
    }
    
    public void getTextBeforeCursor(int length, int seq, IInputContextCallback callback) {
        dispatchMessage(obtainMessageISC(DO_GET_TEXT_BEFORE_CURSOR, length, seq, callback));
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

    public void deleteSurroundingText(int leftLength, int rightLength) {
        dispatchMessage(obtainMessageII(DO_DELETE_SURROUNDING_TEXT,
            leftLength, rightLength));
    }

    public void hideStatusIcon() {
        dispatchMessage(obtainMessage(DO_HIDE_STATUS_ICON));
    }

    public void showStatusIcon(String packageName, int resId) {
        dispatchMessage(obtainMessageIO(DO_SHOW_STATUS_ICON, resId, packageName));
    }

    public void performPrivateCommand(String action, Bundle data) {
        dispatchMessage(obtainMessageOO(DO_PERFORM_PRIVATE_COMMAND, action, data));
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
                    args.callback.setTextAfterCursor(mInputConnection.getTextAfterCursor(msg.arg1),
                            args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setTextAfterCursor", e);
                }
                return;
            }
            case DO_GET_TEXT_BEFORE_CURSOR: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    args.callback.setTextBeforeCursor(mInputConnection.getTextBeforeCursor(msg.arg1),
                            args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setTextBeforeCursor", e);
                }
                return;
            }
            case DO_GET_CURSOR_CAPS_MODE: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    args.callback.setCursorCapsMode(mInputConnection.getCursorCapsMode(msg.arg1),
                            args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setCursorCapsMode", e);
                }
                return;
            }
            case DO_GET_EXTRACTED_TEXT: {
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    args.callback.setExtractedText(mInputConnection.getExtractedText(
                            (ExtractedTextRequest)args.arg1, msg.arg1), args.seq);
                } catch (RemoteException e) {
                    Log.w(TAG, "Got RemoteException calling setExtractedText", e);
                }
                return;
            }
            case DO_COMMIT_TEXT: {
                mInputConnection.commitText((CharSequence)msg.obj, msg.arg1);
                return;
            }
            case DO_COMMIT_COMPLETION: {
                mInputConnection.commitCompletion((CompletionInfo)msg.obj);
                return;
            }
            case DO_SET_COMPOSING_TEXT: {
                mInputConnection.setComposingText((CharSequence)msg.obj, msg.arg1);
                return;
            }
            case DO_FINISH_COMPOSING_TEXT: {
                mInputConnection.finishComposingText();
                return;
            }
            case DO_SEND_KEY_EVENT: {
                mInputConnection.sendKeyEvent((KeyEvent)msg.obj);
                return;
            }
            case DO_CLEAR_META_KEY_STATES: {
                mInputConnection.clearMetaKeyStates(msg.arg1);
                return;
            }
            case DO_DELETE_SURROUNDING_TEXT: {
                mInputConnection.deleteSurroundingText(msg.arg1, msg.arg2);
                return;
            }
            case DO_HIDE_STATUS_ICON: {
                mInputConnection.hideStatusIcon();
                return;
            }
            case DO_SHOW_STATUS_ICON: {
                mInputConnection.showStatusIcon((String)msg.obj, msg.arg1);
                return;
            }
            case DO_PERFORM_PRIVATE_COMMAND: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputConnection.performPrivateCommand((String)args.arg1,
                        (Bundle)args.arg2);
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
