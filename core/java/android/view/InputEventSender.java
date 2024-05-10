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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

/**
 * Provides a low-level mechanism for an application to send input events.
 * @hide
 */
public abstract class InputEventSender {
    private static final String TAG = "InputEventSender";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private long mSenderPtr;

    // We keep references to the input channel and message queue objects (indirectly through
    // Handler) here so that they are not GC'd while the native peer of the receiver is using them.
    private InputChannel mInputChannel;
    private Handler mHandler;

    private static native long nativeInit(WeakReference<InputEventSender> sender,
            InputChannel inputChannel, MessageQueue messageQueue);
    private static native void nativeDispose(long senderPtr);
    private static native boolean nativeSendKeyEvent(long senderPtr, int seq, KeyEvent event);
    private static native boolean nativeSendMotionEvent(long senderPtr, int seq, MotionEvent event);

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
        mHandler = new Handler(looper);
        mSenderPtr = nativeInit(new WeakReference<InputEventSender>(this),
                mInputChannel, looper.getQueue());

        mCloseGuard.open("InputEventSender.dispose");
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
        mHandler = null;
        mInputChannel = null;
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
     * Called when timeline is sent to the publisher.
     *
     * @param inputEventId The id of the input event that caused the frame being reported
     * @param gpuCompletedTime The time when the frame left the app process
     * @param presentTime The time when the frame was presented on screen
     */
    public void onTimelineReported(int inputEventId, long gpuCompletedTime, long presentTime) {
    }

    /**
     * Sends an input event. Can be called from any thread. Do not call this if the looper thread
     * is blocked! It would cause a deadlock.
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

        if (mHandler.getLooper().isCurrentThread()) {
            return sendInputEventInternal(seq, event);
        }
        // This is being called on another thread. Post a runnable to the looper thread
        // with the event injection, and wait until it's processed.
        final RunnableFuture<Boolean> task = new FutureTask<>(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return sendInputEventInternal(seq, event);
            }
        });
        mHandler.post(task);
        try {
            return task.get();
        } catch (InterruptedException exc) {
            throw new IllegalStateException("Interrupted while sending " + event + ": " + exc);
        } catch (ExecutionException exc) {
            throw new IllegalStateException("Couldn't send " + event + ": " + exc);
        }
    }

    private boolean sendInputEventInternal(int seq, InputEvent event) {
        if (event instanceof KeyEvent) {
            return nativeSendKeyEvent(mSenderPtr, seq, (KeyEvent)event);
        } else {
            return nativeSendMotionEvent(mSenderPtr, seq, (MotionEvent)event);
        }
    }

    // Called from native code.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void dispatchInputEventFinished(int seq, boolean handled) {
        onInputEventFinished(seq, handled);
    }

    // Called from native code.
    @SuppressWarnings("unused")
    private void dispatchTimelineReported(
            int inputEventId, long gpuCompletedTime, long presentTime) {
        onTimelineReported(inputEventId, gpuCompletedTime, presentTime);
    }
}
