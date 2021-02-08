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

import android.annotation.NonNull;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * This is a never-give-up listener for sound trigger external capture state notifications, as
 * published by the audio policy service.
 *
 * This class will constantly try to connect to the service over a background thread and tolerate
 * its death.
 */
class ExternalCaptureStateTracker implements ICaptureStateNotifier {
    private static final String TAG = "CaptureStateTracker";

    /** Our client's listeners. Also used as lock. */
    private final List<Listener> mListeners = new LinkedList<>();

    /** Conservatively, until notified otherwise, we assume capture is active. */
    private boolean mCaptureActive = true;

    /** This semaphore will get a permit every time we need to reconnect. */
    private final Semaphore mNeedToConnect = new Semaphore(1);

    /**
     * Constructor. Will start a background thread to do the work.
     */
    ExternalCaptureStateTracker() {
        new Thread(this::run).start();
    }


    @Override
    public boolean registerListener(@NonNull Listener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
            return mCaptureActive;
        }
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
            synchronized (mListeners) {
                mCaptureActive = active;
                for (Listener listener : mListeners) {
                    listener.onCaptureStateChange(active);
                }
            }
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
