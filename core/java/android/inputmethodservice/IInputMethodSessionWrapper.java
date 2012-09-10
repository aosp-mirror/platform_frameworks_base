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

package android.inputmethodservice;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodSession;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputMethodSession;

class IInputMethodSessionWrapper extends IInputMethodSession.Stub
        implements HandlerCaller.Callback {
    private static final String TAG = "InputMethodWrapper";
    private static final boolean DEBUG = false;
    
    private static final int DO_FINISH_INPUT = 60;
    private static final int DO_DISPLAY_COMPLETIONS = 65;
    private static final int DO_UPDATE_EXTRACTED_TEXT = 67;
    private static final int DO_DISPATCH_KEY_EVENT = 70;
    private static final int DO_DISPATCH_TRACKBALL_EVENT = 80;
    private static final int DO_DISPATCH_GENERIC_MOTION_EVENT = 85;
    private static final int DO_UPDATE_SELECTION = 90;
    private static final int DO_UPDATE_CURSOR = 95;
    private static final int DO_APP_PRIVATE_COMMAND = 100;
    private static final int DO_TOGGLE_SOFT_INPUT = 105;
    private static final int DO_FINISH_SESSION = 110;
    private static final int DO_VIEW_CLICKED = 115;

    HandlerCaller mCaller;
    InputMethodSession mInputMethodSession;
    
    // NOTE: we should have a cache of these.
    static class InputMethodEventCallbackWrapper implements InputMethodSession.EventCallback {
        final IInputMethodCallback mCb;
        InputMethodEventCallbackWrapper(IInputMethodCallback cb) {
            mCb = cb;
        }
        public void finishedEvent(int seq, boolean handled) {
            try {
                mCb.finishedEvent(seq, handled);
            } catch (RemoteException e) {
            }
        }
    }
    
    public IInputMethodSessionWrapper(Context context,
            InputMethodSession inputMethodSession) {
        mCaller = new HandlerCaller(context, this);
        mInputMethodSession = inputMethodSession;
    }

    public InputMethodSession getInternalInputMethodSession() {
        return mInputMethodSession;
    }

    public void executeMessage(Message msg) {
        if (mInputMethodSession == null) return;

        switch (msg.what) {
            case DO_FINISH_INPUT:
                mInputMethodSession.finishInput();
                return;
            case DO_DISPLAY_COMPLETIONS:
                mInputMethodSession.displayCompletions((CompletionInfo[])msg.obj);
                return;
            case DO_UPDATE_EXTRACTED_TEXT:
                mInputMethodSession.updateExtractedText(msg.arg1,
                        (ExtractedText)msg.obj);
                return;
            case DO_DISPATCH_KEY_EVENT: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputMethodSession.dispatchKeyEvent(msg.arg1,
                        (KeyEvent)args.arg1,
                        new InputMethodEventCallbackWrapper(
                                (IInputMethodCallback)args.arg2));
                args.recycle();
                return;
            }
            case DO_DISPATCH_TRACKBALL_EVENT: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputMethodSession.dispatchTrackballEvent(msg.arg1,
                        (MotionEvent)args.arg1,
                        new InputMethodEventCallbackWrapper(
                                (IInputMethodCallback)args.arg2));
                args.recycle();
                return;
            }
            case DO_DISPATCH_GENERIC_MOTION_EVENT: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputMethodSession.dispatchGenericMotionEvent(msg.arg1,
                        (MotionEvent)args.arg1,
                        new InputMethodEventCallbackWrapper(
                                (IInputMethodCallback)args.arg2));
                args.recycle();
                return;
            }
            case DO_UPDATE_SELECTION: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputMethodSession.updateSelection(args.argi1, args.argi2,
                        args.argi3, args.argi4, args.argi5, args.argi6);
                args.recycle();
                return;
            }
            case DO_UPDATE_CURSOR: {
                mInputMethodSession.updateCursor((Rect)msg.obj);
                return;
            }
            case DO_APP_PRIVATE_COMMAND: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputMethodSession.appPrivateCommand((String)args.arg1,
                        (Bundle)args.arg2);
                args.recycle();
                return;
            }
            case DO_TOGGLE_SOFT_INPUT: {
                mInputMethodSession.toggleSoftInput(msg.arg1, msg.arg2);
                return;
            }
            case DO_FINISH_SESSION: {
                mInputMethodSession = null;
                return;
            }
            case DO_VIEW_CLICKED: {
                mInputMethodSession.viewClicked(msg.arg1 == 1);
                return;
            }
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
    }
    
    public void finishInput() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_FINISH_INPUT));
    }

    public void displayCompletions(CompletionInfo[] completions) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_DISPLAY_COMPLETIONS, completions));
    }
    
    public void updateExtractedText(int token, ExtractedText text) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIO(
                DO_UPDATE_EXTRACTED_TEXT, token, text));
    }
    
    public void dispatchKeyEvent(int seq, KeyEvent event, IInputMethodCallback callback) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOO(DO_DISPATCH_KEY_EVENT, seq,
                event, callback));
    }

    public void dispatchTrackballEvent(int seq, MotionEvent event, IInputMethodCallback callback) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOO(DO_DISPATCH_TRACKBALL_EVENT, seq,
                event, callback));
    }

    public void dispatchGenericMotionEvent(int seq, MotionEvent event,
            IInputMethodCallback callback) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOO(DO_DISPATCH_GENERIC_MOTION_EVENT, seq,
                event, callback));
    }

    public void updateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIIIIII(DO_UPDATE_SELECTION,
                oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd));
    }

    public void viewClicked(boolean focusChanged) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageI(DO_VIEW_CLICKED, focusChanged ? 1 : 0));
    }

    public void updateCursor(Rect newCursor) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_UPDATE_CURSOR,
                newCursor));
    }
    
    public void appPrivateCommand(String action, Bundle data) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_APP_PRIVATE_COMMAND, action, data));
    }
    
    public void toggleSoftInput(int showFlags, int hideFlags) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageII(DO_TOGGLE_SOFT_INPUT, showFlags, hideFlags));
    }

    public void finishSession() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_FINISH_SESSION));
    }
}
