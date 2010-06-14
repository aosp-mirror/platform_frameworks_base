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
 * input events sent over an input channel.  Signalling is implemented by MessageQueue.
 * @hide
 */
public final class InputQueue {
    private static final String TAG = "InputQueue";
    
    // Describes the interpretation of an event.
    // XXX This concept is tentative.  See comments in android/input.h.
    public static final int INPUT_EVENT_NATURE_KEY = 1;
    public static final int INPUT_EVENT_NATURE_TOUCH = 2;
    public static final int INPUT_EVENT_NATURE_TRACKBALL = 3;
    
    private static Object sLock = new Object();
    
    private static native void nativeRegisterInputChannel(InputChannel inputChannel,
            InputHandler inputHandler, MessageQueue messageQueue);
    private static native void nativeUnregisterInputChannel(InputChannel inputChannel);
    private static native void nativeFinished(long finishedToken);
    
    private InputQueue() {
    }
    
    /**
     * Registers an input channel and handler.
     * @param inputChannel The input channel to register.
     * @param inputHandler The input handler to input events send to the target.
     * @param messageQueue The message queue on whose thread the handler should be invoked.
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
            Slog.d(TAG, "Registering input channel '" + inputChannel + "'");
            nativeRegisterInputChannel(inputChannel, inputHandler, messageQueue);
        }
    }
    
    /**
     * Unregisters an input channel.
     * Does nothing if the channel is not currently registered.
     * @param inputChannel The input channel to unregister.
     */
    public static void unregisterInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null");
        }

        synchronized (sLock) {
            Slog.d(TAG, "Unregistering input channel '" + inputChannel + "'");
            nativeUnregisterInputChannel(inputChannel);
        }
    }
    
    @SuppressWarnings("unused")
    private static void dispatchKeyEvent(InputHandler inputHandler,
            KeyEvent event, int nature, long finishedToken) {
        Runnable finishedCallback = new FinishedCallback(finishedToken);
        
        if (nature == INPUT_EVENT_NATURE_KEY) {
            inputHandler.handleKey(event, finishedCallback);
        } else {
            Slog.d(TAG, "Unsupported nature for key event: " + nature);
        }
    }

    @SuppressWarnings("unused")
    private static void dispatchMotionEvent(InputHandler inputHandler,
            MotionEvent event, int nature, long finishedToken) {
        Runnable finishedCallback = new FinishedCallback(finishedToken);
        
        if (nature == INPUT_EVENT_NATURE_TOUCH) {
            inputHandler.handleTouch(event, finishedCallback);
        } else if (nature == INPUT_EVENT_NATURE_TRACKBALL) {
            inputHandler.handleTrackball(event, finishedCallback);
        } else {
            Slog.d(TAG, "Unsupported nature for motion event: " + nature);
        }
    }
    
    // TODO consider recycling finished callbacks when done
    private static class FinishedCallback implements Runnable {
        private long mFinishedToken;
        
        public FinishedCallback(long finishedToken) {
            mFinishedToken = finishedToken;
        }
        
        public void run() {
            synchronized (sLock) {
                nativeFinished(mFinishedToken);
            }
        }
    }
}
