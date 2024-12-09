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

import static android.view.inputmethod.Flags.verifyKeyEvent;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputMethodSession;

import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.util.Objects;

class IInputMethodSessionWrapper extends IInputMethodSession.Stub
        implements HandlerCaller.Callback {
    private static final String TAG = "InputMethodWrapper";

    private static final int DO_DISPLAY_COMPLETIONS = 65;
    private static final int DO_UPDATE_EXTRACTED_TEXT = 67;
    private static final int DO_UPDATE_SELECTION = 90;
    private static final int DO_UPDATE_CURSOR = 95;
    private static final int DO_UPDATE_CURSOR_ANCHOR_INFO = 99;
    private static final int DO_APP_PRIVATE_COMMAND = 100;
    private static final int DO_FINISH_SESSION = 110;
    private static final int DO_VIEW_CLICKED = 115;
    private static final int DO_REMOVE_IME_SURFACE = 130;
    private static final int DO_FINISH_INPUT = 140;
    private static final int DO_INVALIDATE_INPUT = 150;
    private final Context mContext;


    @UnsupportedAppUsage
    HandlerCaller mCaller;
    InputMethodSession mInputMethodSession;
    InputChannel mChannel;
    ImeInputEventReceiver mReceiver;

    public IInputMethodSessionWrapper(Context context,
            InputMethodSession inputMethodSession, InputChannel channel) {
        mContext = context;
        mCaller = new HandlerCaller(context, null,
                this, true /*asyncHandler*/);
        mInputMethodSession = inputMethodSession;
        mChannel = channel;
        if (channel != null) {
            mReceiver = new ImeInputEventReceiver(channel, context.getMainLooper());
        }
    }

    public InputMethodSession getInternalInputMethodSession() {
        return mInputMethodSession;
    }

    @Override
    public void executeMessage(Message msg) {
        if (mInputMethodSession == null) {
            // The session has been finished. Args needs to be recycled
            // for cases below.
            switch (msg.what) {
                case DO_UPDATE_SELECTION:
                case DO_APP_PRIVATE_COMMAND: {
                    SomeArgs args = (SomeArgs)msg.obj;
                    args.recycle();
                }
            }
            return;
        }

        switch (msg.what) {
            case DO_DISPLAY_COMPLETIONS:
                mInputMethodSession.displayCompletions((CompletionInfo[])msg.obj);
                return;
            case DO_UPDATE_EXTRACTED_TEXT:
                mInputMethodSession.updateExtractedText(msg.arg1,
                        (ExtractedText)msg.obj);
                return;
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
            case DO_UPDATE_CURSOR_ANCHOR_INFO: {
                mInputMethodSession.updateCursorAnchorInfo((CursorAnchorInfo)msg.obj);
                return;
            }
            case DO_APP_PRIVATE_COMMAND: {
                SomeArgs args = (SomeArgs)msg.obj;
                mInputMethodSession.appPrivateCommand((String)args.arg1,
                        (Bundle)args.arg2);
                args.recycle();
                return;
            }
            case DO_FINISH_SESSION: {
                doFinishSession();
                return;
            }
            case DO_VIEW_CLICKED: {
                mInputMethodSession.viewClicked(msg.arg1 == 1);
                return;
            }
            case DO_REMOVE_IME_SURFACE: {
                mInputMethodSession.removeImeSurface();
                return;
            }
            case DO_FINISH_INPUT: {
                mInputMethodSession.finishInput();
                return;
            }
            case DO_INVALIDATE_INPUT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                try {
                    mInputMethodSession.invalidateInputInternal((EditorInfo) args.arg1,
                            (IRemoteInputConnection) args.arg2, msg.arg1);
                } finally {
                    args.recycle();
                }
                return;
            }
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
    }

    private void doFinishSession() {
        mInputMethodSession = null;
        if (mReceiver != null) {
            mReceiver.dispose();
            mReceiver = null;
        }
        if (mChannel != null) {
            mChannel.dispose();
            mChannel = null;
        }
    }

    @Override
    public void displayCompletions(CompletionInfo[] completions) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_DISPLAY_COMPLETIONS, completions));
    }

    @Override
    public void updateExtractedText(int token, ExtractedText text) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIO(
                DO_UPDATE_EXTRACTED_TEXT, token, text));
    }

    @Override
    public void updateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIIIIII(DO_UPDATE_SELECTION,
                oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd));
    }

    @Override
    public void viewClicked(boolean focusChanged) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageI(DO_VIEW_CLICKED, focusChanged ? 1 : 0));
    }

    @Override
    public void removeImeSurface() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_REMOVE_IME_SURFACE));
    }

    @Override
    public void updateCursor(Rect newCursor) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_UPDATE_CURSOR, newCursor));
    }

    @Override
    public void updateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_UPDATE_CURSOR_ANCHOR_INFO, cursorAnchorInfo));
    }

    @Override
    public void appPrivateCommand(String action, Bundle data) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_APP_PRIVATE_COMMAND, action, data));
    }

    @Override
    public void finishSession() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_FINISH_SESSION));
    }

    @Override
    public void invalidateInput(EditorInfo editorInfo, IRemoteInputConnection inputConnection,
            int sessionId) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOO(
                DO_INVALIDATE_INPUT, sessionId, editorInfo, inputConnection));
    }

    @Override
    public void finishInput() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_FINISH_INPUT));
    }
    private final class ImeInputEventReceiver extends InputEventReceiver
            implements InputMethodSession.EventCallback {
        // Time after which a KeyEvent is invalid
        private static final long KEY_EVENT_ALLOW_PERIOD_MS = 100L;
        private final SparseArray<InputEvent> mPendingEvents = new SparseArray<InputEvent>();

        public ImeInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            if (mInputMethodSession == null) {
                // The session has been finished.
                finishInputEvent(event, false);
                return;
            }

            if (event instanceof KeyEvent keyEvent && needsVerification(keyEvent)) {
                // any KeyEvent with modifiers (e.g. Ctrl/Alt/Fn) must be verified that
                // they originated from system.
                InputManager im = mContext.getSystemService(InputManager.class);
                Objects.requireNonNull(im);
                final long age = SystemClock.uptimeMillis() - keyEvent.getEventTime();
                if (age >= KEY_EVENT_ALLOW_PERIOD_MS && im.verifyInputEvent(keyEvent) == null) {
                    Log.w(TAG, "Unverified or Invalid KeyEvent injected into IME. Dropping "
                            + keyEvent);
                    finishInputEvent(event, false /* handled */);
                    return;
                }
            }

            final int seq = event.getSequenceNumber();
            mPendingEvents.put(seq, event);
            if (event instanceof KeyEvent keyEvent) {
                mInputMethodSession.dispatchKeyEvent(seq, keyEvent, this);
            } else {
                MotionEvent motionEvent = (MotionEvent)event;
                if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_TRACKBALL)) {
                    mInputMethodSession.dispatchTrackballEvent(seq, motionEvent, this);
                } else {
                    mInputMethodSession.dispatchGenericMotionEvent(seq, motionEvent, this);
                }
            }
        }

        @Override
        public void finishedEvent(int seq, boolean handled) {
            int index = mPendingEvents.indexOfKey(seq);
            if (index >= 0) {
                InputEvent event = mPendingEvents.valueAt(index);
                mPendingEvents.removeAt(index);
                finishInputEvent(event, handled);
            }
        }

        private boolean hasKeyModifiers(KeyEvent event) {
            if (event.hasNoModifiers()) {
                return false;
            }
            return event.isCtrlPressed()
                    || event.isAltPressed()
                    || event.isFunctionPressed()
                    || event.isMetaPressed();
        }

        private boolean needsVerification(KeyEvent event) {
            //TODO(b/331730488): Handle a11y events as well.
            return verifyKeyEvent()
                    && (hasKeyModifiers(event)
                            || mInputMethodSession.onShouldVerifyKeyEvent(event));
        }
    }
}
