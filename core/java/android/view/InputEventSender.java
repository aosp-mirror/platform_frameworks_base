/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view;

import dalvik.system.CloseGuard;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Provides a low-level mechanism for an application to send input events.
 * @hide
 */
public abstract class InputEventSender {
    private static final String TAG = "InputEventSender";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private int mSenderPtr;

    // We keep references to the input channel and message queue objects here so that
    // they are not GC'd while the native peer of the receiver is using them.
    private InputChannel mInputChannel;
    private MessageQueue mMessageQueue;

    private static native int nativeInit(WeakReference<InputEventSender> sender,
            InputChannel inputChannel, MessageQueue messageQueue);
    private static native void nativeDispose(int senderPtr);
    private static native boolean nativeSendKeyEvent(int senderPtr, int seq, KeyEvent event);
    private static native boolean nativeSendMotionEvent(int senderPtr, int seq, MotionEvent event);

    /**
     * Creates an input event sender bound to the specified input channel.
     *
     * @param inputChannel The input channel.
     * @param looper The looper to use when invoking callbacks.
     */
    public InputEventSender(InputChannel inputChannel, Looper looper) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }

        mInputChannel = inputChannel;
        mMessageQueue = looper.getQueue();
        mSenderPtr = nativeInit(new WeakReference<InputEventSender>(this),
                inputChannel, mMessageQueue);

        mCloseGuard.open("dispose");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose(true);
        } finally {
            super.finalize();
        }
    }

    /**
     * Disposes the receiver.
     */
    public void dispose() {
        dispose(false);
    }

    private void dispose(boolean finalized) {
        if (mCloseGuard != null) {
            if (finalized) {
                mCloseGuard.warnIfOpen();
            }
            mCloseGuard.close();
        }

        if (mSenderPtr != 0) {
            nativeDispose(mSenderPtr);
            mSenderPtr = 0;
        }
        mInputChannel = null;
        mMessageQueue = null;
    }

    /**
     * Called when an input event is finished.
     *
     * @param seq The input event sequence number.
     * @param handled True if the input event was handled.
     */
    public void onInputEventFinished(int seq, boolean handled) {
    }

    /**
     * Sends an input event.
     * Must be called on the same Looper thread to which the sender is attached.
     *
     * @param seq The input event sequence number.
     * @param event The input event to send.
     * @return True if the entire event was sent successfully.  May return false
     * if the input channel buffer filled before all samples were dispatched.
     */
    public final boolean sendInputEvent(int seq, InputEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mSenderPtr == 0) {
            Log.w(TAG, "Attempted to send an input event but the input event "
                    + "sender has already been disposed.");
            return false;
        }

        if (event instanceof KeyEvent) {
            return nativeSendKeyEvent(mSenderPtr, seq, (KeyEvent)event);
        } else {
            return nativeSendMotionEvent(mSenderPtr, seq, (MotionEvent)event);
        }
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchInputEventFinished(int seq, boolean handled) {
        onInputEventFinished(seq, handled);
    }
}
