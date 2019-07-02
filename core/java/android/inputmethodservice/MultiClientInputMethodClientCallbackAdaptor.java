/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IMultiClientInputMethodSession;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputConnectionWrapper;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Re-dispatches all the incoming per-client events to the specified {@link Looper} thread.
 *
 * <p>There are three types of per-client callbacks.</p>
 *
 * <ul>
 *     <li>{@link IInputMethodSession} - from the IME client</li>
 *     <li>{@link IMultiClientInputMethodSession} - from MultiClientInputMethodManagerService</li>
 *     <li>{@link InputChannel} - from the IME client</li>
 * </ul>
 *
 * <p>This class serializes all the incoming events among those channels onto
 * {@link MultiClientInputMethodServiceDelegate.ClientCallback} on the specified {@link Looper}
 * thread.</p>
 */
final class MultiClientInputMethodClientCallbackAdaptor {
    static final boolean DEBUG = false;
    static final String TAG = MultiClientInputMethodClientCallbackAdaptor.class.getSimpleName();

    private final Object mSessionLock = new Object();
    @GuardedBy("mSessionLock")
    CallbackImpl mCallbackImpl;
    @GuardedBy("mSessionLock")
    InputChannel mReadChannel;
    @GuardedBy("mSessionLock")
    KeyEvent.DispatcherState mDispatcherState;
    @GuardedBy("mSessionLock")
    Handler mHandler;
    @GuardedBy("mSessionLock")
    @Nullable
    InputEventReceiver mInputEventReceiver;

    private final AtomicBoolean mFinished = new AtomicBoolean(false);

    IInputMethodSession.Stub createIInputMethodSession() {
        synchronized (mSessionLock) {
            return new InputMethodSessionImpl(
                    mSessionLock, mCallbackImpl, mHandler, mFinished);
        }
    }

    IMultiClientInputMethodSession.Stub createIMultiClientInputMethodSession() {
        synchronized (mSessionLock) {
            return new MultiClientInputMethodSessionImpl(
                    mSessionLock, mCallbackImpl, mHandler, mFinished);
        }
    }

    MultiClientInputMethodClientCallbackAdaptor(
            MultiClientInputMethodServiceDelegate.ClientCallback clientCallback, Looper looper,
            KeyEvent.DispatcherState dispatcherState, InputChannel readChannel) {
        synchronized (mSessionLock) {
            mCallbackImpl = new CallbackImpl(this, clientCallback);
            mDispatcherState = dispatcherState;
            mHandler = new Handler(looper, null, true);
            mReadChannel = readChannel;
            mInputEventReceiver = new ImeInputEventReceiver(mReadChannel, mHandler.getLooper(),
                    mFinished, mDispatcherState, mCallbackImpl.mOriginalCallback);
        }
    }

    private static final class KeyEventCallbackAdaptor implements KeyEvent.Callback {
        private final MultiClientInputMethodServiceDelegate.ClientCallback mLocalCallback;

        KeyEventCallbackAdaptor(
                MultiClientInputMethodServiceDelegate.ClientCallback callback) {
            mLocalCallback = callback;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return mLocalCallback.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyLongPress(int keyCode, KeyEvent event) {
            return mLocalCallback.onKeyLongPress(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return mLocalCallback.onKeyUp(keyCode, event);
        }

        @Override
        public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
            return mLocalCallback.onKeyMultiple(keyCode, event);
        }
    }

    private static final class ImeInputEventReceiver extends InputEventReceiver {
        private final AtomicBoolean mFinished;
        private final KeyEvent.DispatcherState mDispatcherState;
        private final MultiClientInputMethodServiceDelegate.ClientCallback mClientCallback;
        private final KeyEventCallbackAdaptor mKeyEventCallbackAdaptor;

        ImeInputEventReceiver(InputChannel readChannel, Looper looper, AtomicBoolean finished,
                KeyEvent.DispatcherState dispatcherState,
                MultiClientInputMethodServiceDelegate.ClientCallback callback) {
            super(readChannel, looper);
            mFinished = finished;
            mDispatcherState = dispatcherState;
            mClientCallback = callback;
            mKeyEventCallbackAdaptor = new KeyEventCallbackAdaptor(callback);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            if (mFinished.get()) {
                // The session has been finished.
                finishInputEvent(event, false);
                return;
            }
            boolean handled = false;
            try {
                if (event instanceof KeyEvent) {
                    final KeyEvent keyEvent = (KeyEvent) event;
                    handled = keyEvent.dispatch(mKeyEventCallbackAdaptor, mDispatcherState,
                            mKeyEventCallbackAdaptor);
                } else {
                    final MotionEvent motionEvent = (MotionEvent) event;
                    if (motionEvent.isFromSource(InputDevice.SOURCE_CLASS_TRACKBALL)) {
                        handled = mClientCallback.onTrackballEvent(motionEvent);
                    } else {
                        handled = mClientCallback.onGenericMotionEvent(motionEvent);
                    }
                }
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    private static final class InputMethodSessionImpl extends IInputMethodSession.Stub {
        private final Object mSessionLock;
        @GuardedBy("mSessionLock")
        private CallbackImpl mCallbackImpl;
        @GuardedBy("mSessionLock")
        private Handler mHandler;
        private final AtomicBoolean mSessionFinished;

        InputMethodSessionImpl(Object lock, CallbackImpl callback, Handler handler,
                AtomicBoolean sessionFinished) {
            mSessionLock = lock;
            mCallbackImpl = callback;
            mHandler = handler;
            mSessionFinished = sessionFinished;
        }

        @Override
        public void updateExtractedText(int token, ExtractedText text) {
            reportNotSupported();
        }

        @Override
        public void updateSelection(int oldSelStart, int oldSelEnd,
                int newSelStart, int newSelEnd,
                int candidatesStart, int candidatesEnd) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                final SomeArgs args = SomeArgs.obtain();
                args.argi1 = oldSelStart;
                args.argi2 = oldSelEnd;
                args.argi3 = newSelStart;
                args.argi4 = newSelEnd;
                args.argi5 = candidatesStart;
                args.argi6 = candidatesEnd;
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::updateSelection, mCallbackImpl, args));
            }
        }

        @Override
        public void viewClicked(boolean focusChanged) {
            reportNotSupported();
        }

        @Override
        public void updateCursor(Rect newCursor) {
            reportNotSupported();
        }

        @Override
        public void displayCompletions(CompletionInfo[] completions) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::displayCompletions, mCallbackImpl, completions));
            }
        }

        @Override
        public void appPrivateCommand(String action, Bundle data) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::appPrivateCommand, mCallbackImpl, action, data));
            }
        }

        @Override
        public void toggleSoftInput(int showFlags, int hideFlags) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::toggleSoftInput, mCallbackImpl, showFlags,
                        hideFlags));
            }
        }

        @Override
        public void finishSession() {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mSessionFinished.set(true);
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::finishSession, mCallbackImpl));
                mCallbackImpl = null;
                mHandler = null;
            }
        }

        @Override
        public void updateCursorAnchorInfo(CursorAnchorInfo info) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::updateCursorAnchorInfo, mCallbackImpl, info));
            }
        }

        @Override
        public final void notifyImeHidden() {
            // no-op for multi-session since IME is responsible controlling navigation bar buttons.
            reportNotSupported();
        }
    }

    private static final class MultiClientInputMethodSessionImpl
            extends IMultiClientInputMethodSession.Stub {
        private final Object mSessionLock;
        @GuardedBy("mSessionLock")
        private CallbackImpl mCallbackImpl;
        @GuardedBy("mSessionLock")
        private Handler mHandler;
        private final AtomicBoolean mSessionFinished;

        MultiClientInputMethodSessionImpl(Object lock, CallbackImpl callback,
                Handler handler, AtomicBoolean sessionFinished) {
            mSessionLock = lock;
            mCallbackImpl = callback;
            mHandler = handler;
            mSessionFinished = sessionFinished;
        }

        @Override
        public void startInputOrWindowGainedFocus(@Nullable IInputContext inputContext,
                int missingMethods, @Nullable EditorInfo editorInfo, int controlFlags,
                @SoftInputModeFlags int softInputMode, int windowHandle) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                final SomeArgs args = SomeArgs.obtain();
                // TODO(Bug 119211536): Remove dependency on AbstractInputMethodService from ICW
                final WeakReference<AbstractInputMethodService> fakeIMS =
                        new WeakReference<>(null);
                args.arg1 = (inputContext == null) ? null
                        : new InputConnectionWrapper(fakeIMS, inputContext, missingMethods,
                                mSessionFinished);
                args.arg2 = editorInfo;
                args.argi1 = controlFlags;
                args.argi2 = softInputMode;
                args.argi3 = windowHandle;
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::startInputOrWindowGainedFocus, mCallbackImpl, args));
            }
        }

        @Override
        public void showSoftInput(int flags, ResultReceiver resultReceiver) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::showSoftInput, mCallbackImpl, flags,
                        resultReceiver));
            }
        }

        @Override
        public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
            synchronized (mSessionLock) {
                if (mCallbackImpl == null || mHandler == null) {
                    return;
                }
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        CallbackImpl::hideSoftInput, mCallbackImpl, flags,
                        resultReceiver));
            }
        }
    }

    /**
     * The maim part of adaptor to {@link MultiClientInputMethodServiceDelegate.ClientCallback}.
     */
    @WorkerThread
    private static final class CallbackImpl {
        private final MultiClientInputMethodClientCallbackAdaptor mCallbackAdaptor;
        private final MultiClientInputMethodServiceDelegate.ClientCallback mOriginalCallback;
        private boolean mFinished = false;

        CallbackImpl(MultiClientInputMethodClientCallbackAdaptor callbackAdaptor,
                MultiClientInputMethodServiceDelegate.ClientCallback callback) {
            mCallbackAdaptor = callbackAdaptor;
            mOriginalCallback = callback;
        }

        void updateSelection(SomeArgs args) {
            try {
                if (mFinished) {
                    return;
                }
                mOriginalCallback.onUpdateSelection(args.argi1, args.argi2, args.argi3,
                        args.argi4, args.argi5, args.argi6);
            } finally {
                args.recycle();
            }
        }

        void displayCompletions(CompletionInfo[] completions) {
            if (mFinished) {
                return;
            }
            mOriginalCallback.onDisplayCompletions(completions);
        }

        void appPrivateCommand(String action, Bundle data) {
            if (mFinished) {
                return;
            }
            mOriginalCallback.onAppPrivateCommand(action, data);
        }

        void toggleSoftInput(int showFlags, int hideFlags) {
            if (mFinished) {
                return;
            }
            mOriginalCallback.onToggleSoftInput(showFlags, hideFlags);
        }

        void finishSession() {
            if (mFinished) {
                return;
            }
            mFinished = true;
            mOriginalCallback.onFinishSession();
            synchronized (mCallbackAdaptor.mSessionLock) {
                mCallbackAdaptor.mDispatcherState = null;
                if (mCallbackAdaptor.mReadChannel != null) {
                    mCallbackAdaptor.mReadChannel.dispose();
                    mCallbackAdaptor.mReadChannel = null;
                }
                mCallbackAdaptor.mInputEventReceiver = null;
            }
        }

        void updateCursorAnchorInfo(CursorAnchorInfo info) {
            if (mFinished) {
                return;
            }
            mOriginalCallback.onUpdateCursorAnchorInfo(info);
        }

        void startInputOrWindowGainedFocus(SomeArgs args) {
            try {
                if (mFinished) {
                    return;
                }
                final InputConnectionWrapper inputConnection = (InputConnectionWrapper) args.arg1;
                final EditorInfo editorInfo = (EditorInfo) args.arg2;
                final int startInputFlags = args.argi1;
                final int softInputMode = args.argi2;
                final int windowHandle = args.argi3;
                mOriginalCallback.onStartInputOrWindowGainedFocus(inputConnection, editorInfo,
                        startInputFlags, softInputMode, windowHandle);
            } finally {
                args.recycle();
            }
        }

        void showSoftInput(int flags, ResultReceiver resultReceiver) {
            if (mFinished) {
                return;
            }
            mOriginalCallback.onShowSoftInput(flags, resultReceiver);
        }

        void hideSoftInput(int flags, ResultReceiver resultReceiver) {
            if (mFinished) {
                return;
            }
            mOriginalCallback.onHideSoftInput(flags, resultReceiver);
        }
    }

    private static void reportNotSupported() {
        if (DEBUG) {
            Log.d(TAG, Debug.getCaller() + " is not supported");
        }
    }
}
