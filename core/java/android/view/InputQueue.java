/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.MessageQueue;
import android.util.Slog;

/**
 * An input queue provides a mechanism for an application to receive incoming
 * input events.  Currently only usable from native code.
 */
public final class InputQueue {
    private static final String TAG = "InputQueue";
    
    private static final boolean DEBUG = false;
    
    public static interface Callback {
        void onInputQueueCreated(InputQueue queue);
        void onInputQueueDestroyed(InputQueue queue);
    }

    final InputChannel mChannel;
    
    private static final Object sLock = new Object();
    
    private static native void nativeRegisterInputChannel(InputChannel inputChannel,
            InputHandler inputHandler, MessageQueue messageQueue);
    private static native void nativeUnregisterInputChannel(InputChannel inputChannel);
    private static native void nativeFinished(long finishedToken);
    
    /** @hide */
    public InputQueue(InputChannel channel) {
        mChannel = channel;
    }
    
    /** @hide */
    public InputChannel getInputChannel() {
        return mChannel;
    }
    
    /**
     * Registers an input channel and handler.
     * @param inputChannel The input channel to register.
     * @param inputHandler The input handler to input events send to the target.
     * @param messageQueue The message queue on whose thread the handler should be invoked.
     * @hide
     */
    public static void registerInputChannel(InputChannel inputChannel, InputHandler inputHandler,
            MessageQueue messageQueue) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }
        if (inputHandler == null) {
            throw new IllegalArgumentException("inputHandler must not be null");
        }
        if (messageQueue == null) {
            throw new IllegalArgumentException("messageQueue must not be null");
        }
        
        synchronized (sLock) {
            if (DEBUG) {
                Slog.d(TAG, "Registering input channel '" + inputChannel + "'");
            }
            
            nativeRegisterInputChannel(inputChannel, inputHandler, messageQueue);
        }
    }
    
    /**
     * Unregisters an input channel.
     * Does nothing if the channel is not currently registered.
     * @param inputChannel The input channel to unregister.
     * @hide
     */
    public static void unregisterInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }

        synchronized (sLock) {
            if (DEBUG) {
                Slog.d(TAG, "Unregistering input channel '" + inputChannel + "'");
            }
            
            nativeUnregisterInputChannel(inputChannel);
        }
    }
    
    @SuppressWarnings("unused")
    private static void dispatchKeyEvent(InputHandler inputHandler,
            KeyEvent event, long finishedToken) {
        Runnable finishedCallback = FinishedCallback.obtain(finishedToken);
        inputHandler.handleKey(event, finishedCallback);
    }

    @SuppressWarnings("unused")
    private static void dispatchMotionEvent(InputHandler inputHandler,
            MotionEvent event, long finishedToken) {
        Runnable finishedCallback = FinishedCallback.obtain(finishedToken);
        inputHandler.handleMotion(event, finishedCallback);
    }
    
    private static class FinishedCallback implements Runnable {
        private static final boolean DEBUG_RECYCLING = false;
        
        private static final int RECYCLE_MAX_COUNT = 4;
        
        private static FinishedCallback sRecycleHead;
        private static int sRecycleCount;
        
        private FinishedCallback mRecycleNext;
        private long mFinishedToken;
        
        private FinishedCallback() {
        }
        
        public static FinishedCallback obtain(long finishedToken) {
            synchronized (sLock) {
                FinishedCallback callback = sRecycleHead;
                if (callback != null) {
                    sRecycleHead = callback.mRecycleNext;
                    sRecycleCount -= 1;
                    callback.mRecycleNext = null;
                } else {
                    callback = new FinishedCallback();
                }
                callback.mFinishedToken = finishedToken;
                return callback;
            }
        }
        
        public void run() {
            synchronized (sLock) {
                if (mFinishedToken == -1) {
                    throw new IllegalStateException("Event finished callback already invoked.");
                }
                
                nativeFinished(mFinishedToken);
                mFinishedToken = -1;

                if (sRecycleCount < RECYCLE_MAX_COUNT) {
                    mRecycleNext = sRecycleHead;
                    sRecycleHead = this;
                    sRecycleCount += 1;
                    
                    if (DEBUG_RECYCLING) {
                        Slog.d(TAG, "Recycled finished callbacks: " + sRecycleCount);
                    }
                }
            }
        }
    }
}
