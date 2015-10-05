/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.audio;

import android.content.Context;
import android.media.AudioSystem;
import android.os.Handler;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import com.android.server.policy.WindowOrientationListener;

/**
 * Class to handle device rotation events for AudioService, and forward device rotation
 * to the audio HALs through AudioSystem.
 *
 * The role of this class is to monitor device orientation changes, and upon rotation,
 * verify the UI orientation. In case of a change, send the new orientation, in increments
 * of 90deg, through AudioSystem.
 *
 * Note that even though we're responding to device orientation events, we always
 * query the display rotation so audio stays in sync with video/dialogs. This is
 * done with .getDefaultDisplay().getRotation() from WINDOW_SERVICE.
 */
class RotationHelper {

    private static final String TAG = "AudioService.RotationHelper";

    private static AudioOrientationListener sOrientationListener;
    private static AudioWindowOrientationListener sWindowOrientationListener;

    private static final Object sRotationLock = new Object();
    private static int sDeviceRotation = Surface.ROTATION_0; // R/W synchronized on sRotationLock

    private static Context sContext;

    /**
     * post conditions:
     * - (sWindowOrientationListener != null) xor (sOrientationListener != null)
     * - sWindowOrientationListener xor sOrientationListener is enabled
     * - sContext != null
     */
    static void init(Context context, Handler handler) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid null context");
        }
        sContext = context;
        sWindowOrientationListener = new AudioWindowOrientationListener(context, handler);
        sWindowOrientationListener.enable();
        if (!sWindowOrientationListener.canDetectOrientation()) {
            // cannot use com.android.server.policy.WindowOrientationListener, revert to public
            // orientation API
            Log.i(TAG, "Not using WindowOrientationListener, reverting to OrientationListener");
            sWindowOrientationListener.disable();
            sWindowOrientationListener = null;
            sOrientationListener = new AudioOrientationListener(context);
            sOrientationListener.enable();
        }
    }

    static void enable() {
        if (sWindowOrientationListener != null) {
            sWindowOrientationListener.enable();
        } else {
            sOrientationListener.enable();
        }
        updateOrientation();
    }

    static void disable() {
        if (sWindowOrientationListener != null) {
            sWindowOrientationListener.disable();
        } else {
            sOrientationListener.disable();
        }
    }

    /**
     * Query current display rotation and publish the change if any.
     */
    static void updateOrientation() {
        // Even though we're responding to device orientation events,
        // use display rotation so audio stays in sync with video/dialogs
        int newRotation = ((WindowManager) sContext.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        synchronized(sRotationLock) {
            if (newRotation != sDeviceRotation) {
                sDeviceRotation = newRotation;
                publishRotation(sDeviceRotation);
            }
        }
    }

    private static void publishRotation(int rotation) {
        Log.v(TAG, "publishing device rotation =" + rotation + " (x90deg)");
        switch (rotation) {
            case Surface.ROTATION_0:
                AudioSystem.setParameters("rotation=0");
                break;
            case Surface.ROTATION_90:
                AudioSystem.setParameters("rotation=90");
                break;
            case Surface.ROTATION_180:
                AudioSystem.setParameters("rotation=180");
                break;
            case Surface.ROTATION_270:
                AudioSystem.setParameters("rotation=270");
                break;
            default:
                Log.e(TAG, "Unknown device rotation");
        }
    }

    /**
     * Uses android.view.OrientationEventListener
     */
    final static class AudioOrientationListener extends OrientationEventListener {
        AudioOrientationListener(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            updateOrientation();
        }
    }

    /**
     * Uses com.android.server.policy.WindowOrientationListener
     */
    final static class AudioWindowOrientationListener extends WindowOrientationListener {
        private static RotationCheckThread sRotationCheckThread;

        AudioWindowOrientationListener(Context context, Handler handler) {
            super(context, handler);
        }

        public void onProposedRotationChanged(int rotation) {
            updateOrientation();
            if (sRotationCheckThread != null) {
                sRotationCheckThread.endCheck();
            }
            sRotationCheckThread = new RotationCheckThread();
            sRotationCheckThread.beginCheck();
        }
    }

    /**
     * When com.android.server.policy.WindowOrientationListener report an orientation change,
     * the UI may not have rotated yet. This thread polls with gradually increasing delays
     * the new orientation.
     */
    final static class RotationCheckThread extends Thread {
        // how long to wait between each rotation check
        private final int[] WAIT_TIMES_MS = { 10, 20, 50, 100, 100, 200, 200, 500 };
        private int mWaitCounter;
        private final Object mCounterLock = new Object();

        RotationCheckThread() {
            super("RotationCheck");
        }

        void beginCheck() {
            synchronized(mCounterLock) {
                mWaitCounter = 0;
            }
            try {
                start();
            } catch (IllegalStateException e) { }
        }

        void endCheck() {
            synchronized(mCounterLock) {
                mWaitCounter = WAIT_TIMES_MS.length;
            }
        }

        public void run() {
            while (mWaitCounter < WAIT_TIMES_MS.length) {
                int waitTimeMs;
                synchronized(mCounterLock) {
                    waitTimeMs = mWaitCounter < WAIT_TIMES_MS.length ?
                            WAIT_TIMES_MS[mWaitCounter] : 0;
                    mWaitCounter++;
                }
                try {
                    if (waitTimeMs > 0) {
                        sleep(waitTimeMs);
                        updateOrientation();
                    }
                } catch (InterruptedException e) { }
            }
        }
    }
}