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
import android.hardware.display.DisplayManager;
import android.media.AudioSystem;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

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

    private static AudioDisplayListener sDisplayListener;

    private static final Object sRotationLock = new Object();
    private static int sDeviceRotation = Surface.ROTATION_0; // R/W synchronized on sRotationLock

    private static Context sContext;
    private static Handler sHandler;

    /**
     * post conditions:
     * - sDisplayListener != null
     * - sContext != null
     */
    static void init(Context context, Handler handler) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid null context");
        }
        sContext = context;
        sHandler = handler;
        sDisplayListener = new AudioDisplayListener();
        enable();
    }

    static void enable() {
        ((DisplayManager) sContext.getSystemService(Context.DISPLAY_SERVICE))
                .registerDisplayListener(sDisplayListener, sHandler);
        updateOrientation();
    }

    static void disable() {
        ((DisplayManager) sContext.getSystemService(Context.DISPLAY_SERVICE))
                .unregisterDisplayListener(sDisplayListener);
    }

    /**
     * Query current display rotation and publish the change if any.
     */
    static void updateOrientation() {
        // Even though we're responding to device orientation events,
        // use display rotation so audio stays in sync with video/dialogs
        // TODO(b/148458001): Support multi-display
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
     * Uses android.hardware.display.DisplayManager.DisplayListener
     */
    final static class AudioDisplayListener implements DisplayManager.DisplayListener {

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateOrientation();
        }
    }
}