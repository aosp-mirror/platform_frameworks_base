/*
 * Copyright 2013 The Android Open Source Project
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

package androidx.media.filterfw.samples.simplecamera;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.TimeoutException;

/**
 * Camera test thread wrapper for handling camera callbacks
 */
public class CameraTestThread implements AutoCloseable {
    private static final String TAG = "CameraTestThread";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    // Timeout for initializing looper and opening camera in Milliseconds.
    private static final long WAIT_FOR_COMMAND_TO_COMPLETE = 5000;
    private Looper mLooper = null;
    private Handler mHandler = null;

    /**
     * Create and start a looper thread, return the Handler
     */
    public synchronized Handler start() throws Exception {
        final ConditionVariable startDone = new ConditionVariable();
        if (mLooper != null || mHandler !=null) {
            Log.w(TAG, "Looper thread already started");
            return mHandler;
        }

        new Thread() {
            @Override
            public void run() {
                if (VERBOSE) Log.v(TAG, "start loopRun");
                Looper.prepare();
                // Save the looper so that we can terminate this thread
                // after we are done with it.
                mLooper = Looper.myLooper();
                mHandler = new Handler();
                startDone.open();
                Looper.loop();
                if (VERBOSE) Log.v(TAG, "createLooperThread: finished");
            }
        }.start();

        if (VERBOSE) Log.v(TAG, "start waiting for looper");
        if (!startDone.block(WAIT_FOR_COMMAND_TO_COMPLETE)) {
            throw new TimeoutException("createLooperThread: start timeout");
        }
        return mHandler;
    }

    /**
     * Terminate the looper thread
     */
    public synchronized void close() throws Exception {
        if (mLooper == null || mHandler == null) {
            Log.w(TAG, "Looper thread doesn't start yet");
            return;
        }

        if (VERBOSE) Log.v(TAG, "Terminate looper thread");
        mLooper.quit();
        mLooper.getThread().join();
        mLooper = null;
        mHandler = null;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
