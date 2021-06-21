/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.util.Log;

import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * This is a never-give-up listener for sound trigger external capture state notifications, as
 * published by the audio policy service.
 *
 * This class will constantly try to connect to the service over a background thread and tolerate
 * its death. The client will be notified by a single provided function that is called in a
 * synchronized manner.
 * For simplicity, there is currently no way to stop the tracker. This is possible to add if the
 * need ever arises.
 */
class ExternalCaptureStateTracker {
    private static final String TAG = "CaptureStateTracker";
    /** Our client's listener. */
    private final Consumer<Boolean> mListener;
    /** This semaphore will get a permit every time we need to reconnect. */
    private final Semaphore mNeedToConnect = new Semaphore(1);

    /**
     * Constructor. Will start a background thread to do the work.
     *
     * @param listener A client provided listener that will be called on state
     *                 changes. May be
     *                 called multiple consecutive times with the same value. Never
     *                 called
     *                 concurrently.
     */
    ExternalCaptureStateTracker(Consumer<Boolean> listener) {
        mListener = listener;
        new Thread(this::run).start();
    }

    /**
     * Routine for the background thread. Keeps trying to reconnect.
     */
    private void run() {
        while (true) {
            mNeedToConnect.acquireUninterruptibly();
            connect();
        }
    }

    /**
     * Connect to the service, install listener and death notifier.
     */
    private native void connect();

    /**
     * Called by native code to invoke the client listener.
     *
     * @param active true when external capture is active.
     */
    private void setCaptureState(boolean active) {
        try {
            mListener.accept(active);
        } catch (Exception e) {
            Log.e(TAG, "Exception caught while setting capture state", e);
        }
    }

    /**
     * Called by native code when the remote service died.
     */
    private void binderDied() {
        Log.w(TAG, "Audio policy service died");
        mNeedToConnect.release();
    }
}
