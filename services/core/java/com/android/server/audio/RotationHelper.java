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
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.FoldStateListener;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.util.function.Consumer;

/**
 * Class to handle device rotation events for AudioService, and forward device rotation
 * and folded state to the audio HALs through AudioSystem.
 *
 * The role of this class is to monitor device orientation changes, and upon rotation,
 * verify the UI orientation. In case of a change, send the new orientation, in increments
 * of 90deg, through AudioSystem.
 *
 * Another role of this class is to track device folded state changes. In case of a
 * change, send the new folded state through AudioSystem.
 *
 * Note that even though we're responding to device orientation events, we always
 * query the display rotation so audio stays in sync with video/dialogs. This is
 * done with .getDefaultDisplay().getRotation() from WINDOW_SERVICE.
 *
 * We also monitor current display ID and audio is able to know which display is active.
 */
class RotationHelper {

    private static final String TAG = "AudioService.RotationHelper";

    private static final boolean DEBUG_ROTATION = false;

    private static AudioDisplayListener sDisplayListener;
    private static FoldStateListener sFoldStateListener;
    /** callback to send rotation updates to AudioSystem */
    private static Consumer<Integer> sRotationCallback;
    /** callback to send folded state updates to AudioSystem */
    private static Consumer<Boolean> sFoldStateCallback;

    private static final Object sRotationLock = new Object();
    private static final Object sFoldStateLock = new Object();
    private static Integer sRotation = null; // R/W synchronized on sRotationLock
    private static Boolean sFoldState = null; // R/W synchronized on sFoldStateLock

    private static Context sContext;
    private static Handler sHandler;

    /**
     * post conditions:
     * - sDisplayListener != null
     * - sContext != null
     */
    static void init(Context context, Handler handler,
            Consumer<Integer> rotationCallback, Consumer<Boolean> foldStateCallback) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid null context");
        }
        sContext = context;
        sHandler = handler;
        sDisplayListener = new AudioDisplayListener();
        sFoldStateListener = new FoldStateListener(sContext, RotationHelper::updateFoldState);
        sRotationCallback = rotationCallback;
        sFoldStateCallback = foldStateCallback;
        enable();
    }

    static void enable() {
        ((DisplayManager) sContext.getSystemService(Context.DISPLAY_SERVICE))
                .registerDisplayListener(sDisplayListener, sHandler);
        updateOrientation();

        sContext.getSystemService(DeviceStateManager.class)
                .registerCallback(new HandlerExecutor(sHandler), sFoldStateListener);
    }

    static void disable() {
        ((DisplayManager) sContext.getSystemService(Context.DISPLAY_SERVICE))
                .unregisterDisplayListener(sDisplayListener);
        sContext.getSystemService(DeviceStateManager.class)
                .unregisterCallback(sFoldStateListener);
    }

    /**
     * Query current display rotation and publish the change if any.
     */
    static void updateOrientation() {
        // Even though we're responding to device orientation events,
        // use display rotation so audio stays in sync with video/dialogs
        // TODO(b/148458001): Support multi-display
        int newRotation = DisplayManagerGlobal.getInstance()
                .getDisplayInfo(Display.DEFAULT_DISPLAY).rotation;
        synchronized(sRotationLock) {
            if (sRotation == null || sRotation != newRotation) {
                sRotation = newRotation;
                publishRotation(sRotation);
            }
        }
    }

    private static void publishRotation(int rotation) {
        if (DEBUG_ROTATION) {
            Log.i(TAG, "publishing device rotation =" + rotation + " (x90deg)");
        }
        int rotationDegrees;
        switch (rotation) {
            case Surface.ROTATION_0:
                rotationDegrees = 0;
                break;
            case Surface.ROTATION_90:
                rotationDegrees = 90;
                break;
            case Surface.ROTATION_180:
                rotationDegrees = 180;
                break;
            case Surface.ROTATION_270:
                rotationDegrees = 270;
                break;
            default:
                Log.e(TAG, "Unknown device rotation");
                rotationDegrees = -1;
        }
        if (rotationDegrees != -1) {
            sRotationCallback.accept(rotationDegrees);
        }
    }

    /**
     * publish the change of device folded state if any.
     */
    static void updateFoldState(boolean foldState) {
        synchronized (sFoldStateLock) {
            if (sFoldState == null || sFoldState != foldState) {
                sFoldState = foldState;
                sFoldStateCallback.accept(foldState);
            }
        }
    }

    /**
     *  forceUpdate is called when audioserver restarts.
     */
    static void forceUpdate() {
        synchronized (sRotationLock) {
            sRotation = null;
        }
        updateOrientation(); // We will get at least one orientation update now.
        synchronized (sFoldStateLock) {
            if (sFoldState  != null) {
                sFoldStateCallback.accept(sFoldState);
            }
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
            if (DEBUG_ROTATION) {
                Log.i(TAG, "onDisplayChanged diplayId:" + displayId);
            }
            updateOrientation();
        }
    }
}
