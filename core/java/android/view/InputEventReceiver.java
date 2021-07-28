/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.Trace;
import android.util.Log;
import android.util.SparseIntArray;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

/**
 * Provides a low-level mechanism for an application to receive input events.
 * @hide
 */
public abstract class InputEventReceiver {
    private static final String TAG = "InputEventReceiver";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private long mReceiverPtr;

    // We keep references to the input channel and message queue objects here so that
    // they are not GC'd while the native peer of the receiver is using them.
    private InputChannel mInputChannel;
    private MessageQueue mMessageQueue;

    // Map from InputEvent sequence numbers to dispatcher sequence numbers.
    private final SparseIntArray mSeqMap = new SparseIntArray();

    private static native long nativeInit(WeakReference<InputEventReceiver> receiver,
            InputChannel inputChannel, MessageQueue messageQueue);
    private static native void nativeDispose(long receiverPtr);
    private static native void nativeFinishInputEvent(long receiverPtr, int seq, boolean handled);
    private static native void nativeReportTimeline(long receiverPtr, int inputEventId,
            long gpuCompletedTime, long presentTime);
    private static native boolean nativeConsumeBatchedInputEvents(long receiverPtr,
            long frameTimeNanos);
    private static native String nativeDump(long receiverPtr, String prefix);

    /**
     * Creates an input event receiver bound to the specified input channel.
     *
     * @param inputChannel The input channel.
     * @param looper The looper to use when invoking callbacks.
     */
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }

        mInputChannel = inputChannel;
        mMessageQueue = looper.getQueue();
        mReceiverPtr = nativeInit(new WeakReference<InputEventReceiver>(this),
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
     * Must be called on the same Looper thread to which the receiver is attached.
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

        if (mReceiverPtr != 0) {
            nativeDispose(mReceiverPtr);
            mReceiverPtr = 0;
        }

        if (mInputChannel != null) {
            mInputChannel.dispose();
            mInputChannel = null;
        }
        mMessageQueue = null;
        Reference.reachabilityFence(this);
    }

    /**
     * Called when an input event is received.
     * The recipient should process the input event and then call {@link #finishInputEvent}
     * to indicate whether the event was handled.  No new input events will be received
     * until {@link #finishInputEvent} is called.
     *
     * @param event The input event that was received.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void onInputEvent(InputEvent event) {
        finishInputEvent(event, false);
    }

    /**
     * Called when a focus event is received.
     *
     * @param hasFocus if true, the window associated with this input channel has just received
     *                 focus
     *                 if false, the window associated with this input channel has just lost focus
     * @param inTouchMode if true, the device is in touch mode
     *                    if false, the device is not in touch mode
     */
    // Called from native code.
    public void onFocusEvent(boolean hasFocus, boolean inTouchMode) {
    }

    /**
     * Called when a Pointer Capture event is received.
     *
     * @param pointerCaptureEnabled if true, the window associated with this input channel has just
     *                              received Pointer Capture
     *                              if false, the window associated with this input channel has just
     *                              lost Pointer Capture
     * @see View#requestPointerCapture()
     * @see View#releasePointerCapture()
     */
    // Called from native code.
    public void onPointerCaptureEvent(boolean pointerCaptureEnabled) {
    }

    /**
     * Called when a drag event is received, from native code.
     *
     * @param isExiting if false, the window associated with this input channel has just received
     *                 drag
     *                 if true, the window associated with this input channel has just lost drag
     */
    public void onDragEvent(boolean isExiting, float x, float y) {
    }

    /**
     * Called when the display for the window associated with the input channel has entered or
     * exited touch mode.
     *
     * @param isInTouchMode {@code true} if the display showing the window associated with the
     *                                  input channel entered touch mode.
     */
    public void onTouchModeChanged(boolean isInTouchMode) {
    }

    /**
     * Called when a batched input event is pending.
     *
     * The batched input event will continue to accumulate additional movement
     * samples until the recipient calls {@link #consumeBatchedInputEvents} or
     * an event is received that ends the batch and causes it to be consumed
     * immediately (such as a pointer up event).
     * @param source The source of the batched event.
     */
    public void onBatchedInputEventPending(int source) {
        consumeBatchedInputEvents(-1);
    }

    /**
     * Finishes an input event and indicates whether it was handled.
     * Must be called on the same Looper thread to which the receiver is attached.
     *
     * @param event The input event that was finished.
     * @param handled True if the event was handled.
     */
    public final void finishInputEvent(InputEvent event, boolean handled) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mReceiverPtr == 0) {
            Log.w(TAG, "Attempted to finish an input event but the input event "
                    + "receiver has already been disposed.");
        } else {
            int index = mSeqMap.indexOfKey(event.getSequenceNumber());
            if (index < 0) {
                Log.w(TAG, "Attempted to finish an input event that is not in progress.");
            } else {
                int seq = mSeqMap.valueAt(index);
                mSeqMap.removeAt(index);
                nativeFinishInputEvent(mReceiverPtr, seq, handled);
            }
        }
        event.recycleIfNeededAfterDispatch();
    }

    /**
     * Report the timing / latency information for a specific input event.
     */
    public final void reportTimeline(int inputEventId, long gpuCompletedTime, long presentTime) {
        Trace.traceBegin(Trace.TRACE_TAG_INPUT, "reportTimeline");
        nativeReportTimeline(mReceiverPtr, inputEventId, gpuCompletedTime, presentTime);
        Trace.traceEnd(Trace.TRACE_TAG_INPUT);
    }

    /**
     * Consumes all pending batched input events.
     * Must be called on the same Looper thread to which the receiver is attached.
     *
     * This method forces all batched input events to be delivered immediately.
     * Should be called just before animating or drawing a new frame in the UI.
     *
     * @param frameTimeNanos The time in the {@link System#nanoTime()} time base
     * when the current display frame started rendering, or -1 if unknown.
     *
     * @return Whether a batch was consumed
     */
    public final boolean consumeBatchedInputEvents(long frameTimeNanos) {
        if (mReceiverPtr == 0) {
            Log.w(TAG, "Attempted to consume batched input events but the input event "
                    + "receiver has already been disposed.");
        } else {
            return nativeConsumeBatchedInputEvents(mReceiverPtr, frameTimeNanos);
        }
        return false;
    }

    /**
     * @return Returns a token to identify the input channel.
     */
    public IBinder getToken() {
        if (mInputChannel == null) {
            return null;
        }
        return mInputChannel.getToken();
    }

    // Called from native code.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void dispatchInputEvent(int seq, InputEvent event) {
        mSeqMap.put(event.getSequenceNumber(), seq);
        onInputEvent(event);
    }

    /**
     * Dump the state of this InputEventReceiver to the writer.
     * @param prefix the prefix (typically whitespace padding) to append in front of each line
     * @param writer the writer where the dump should be written
     */
    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + getClass().getName());
        writer.println(prefix + " mInputChannel: " + mInputChannel);
        writer.println(prefix + " mSeqMap: " + mSeqMap);
        writer.println(prefix + " mReceiverPtr:\n" + nativeDump(mReceiverPtr, prefix + "  "));
    }

    /**
     * Factory for InputEventReceiver
     */
    public interface Factory {
        /**
         * Create a new InputReceiver for a given inputChannel
         */
        InputEventReceiver createInputEventReceiver(InputChannel inputChannel, Looper looper);
    }
}
