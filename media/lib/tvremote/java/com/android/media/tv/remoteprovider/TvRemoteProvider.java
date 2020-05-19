/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media.tv.remoteprovider;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.SuppressAutoDoc;
import android.content.Context;
import android.media.tv.ITvRemoteProvider;
import android.media.tv.ITvRemoteServiceInput;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.IntDef;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.Objects;

/**
 * Base class for emote providers implemented in unbundled service.
 * <p/>
 * This object is not thread safe.  It is only intended to be accessed on the
 * {@link Context#getMainLooper main looper thread} of an application.
 * The callback {@link #onInputBridgeConnected()} may be called from a different thread.
 * </p><p>
 * IMPORTANT: This class is effectively a system API for unbundled emote service, and
 * must remain API stable. See README.txt in the root of this package for more information.
 * </p>
 */


public abstract class TvRemoteProvider {

    /** @hide */
    @IntDef({
         KeyEvent.KEYCODE_BUTTON_A,
         KeyEvent.KEYCODE_BUTTON_B,
         KeyEvent.KEYCODE_BUTTON_X,
         KeyEvent.KEYCODE_BUTTON_Y,
         KeyEvent.KEYCODE_BUTTON_L1,
         KeyEvent.KEYCODE_BUTTON_L2,
         KeyEvent.KEYCODE_BUTTON_R1,
         KeyEvent.KEYCODE_BUTTON_R2,
         KeyEvent.KEYCODE_BUTTON_SELECT,
         KeyEvent.KEYCODE_BUTTON_START,
         KeyEvent.KEYCODE_BUTTON_MODE,
         KeyEvent.KEYCODE_BUTTON_THUMBL,
         KeyEvent.KEYCODE_BUTTON_THUMBR,
         KeyEvent.KEYCODE_DPAD_UP,
         KeyEvent.KEYCODE_DPAD_DOWN,
         KeyEvent.KEYCODE_DPAD_LEFT,
         KeyEvent.KEYCODE_DPAD_RIGHT,
         KeyEvent.KEYCODE_BUTTON_1,
         KeyEvent.KEYCODE_BUTTON_2,
         KeyEvent.KEYCODE_BUTTON_3,
         KeyEvent.KEYCODE_BUTTON_4,
         KeyEvent.KEYCODE_BUTTON_5,
         KeyEvent.KEYCODE_BUTTON_6,
         KeyEvent.KEYCODE_BUTTON_7,
         KeyEvent.KEYCODE_BUTTON_8,
         KeyEvent.KEYCODE_BUTTON_9,
         KeyEvent.KEYCODE_BUTTON_10,
         KeyEvent.KEYCODE_BUTTON_11,
         KeyEvent.KEYCODE_BUTTON_12,
         KeyEvent.KEYCODE_BUTTON_13,
         KeyEvent.KEYCODE_BUTTON_14,
         KeyEvent.KEYCODE_BUTTON_15,
         KeyEvent.KEYCODE_BUTTON_16,
         KeyEvent.KEYCODE_ASSIST,
         KeyEvent.KEYCODE_VOICE_ASSIST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GamepadKeyCode {
    }

    /** @hide */
    @IntDef({
         MotionEvent.AXIS_X,
         MotionEvent.AXIS_Y,
         MotionEvent.AXIS_Z,
         MotionEvent.AXIS_RZ,
         MotionEvent.AXIS_LTRIGGER,
         MotionEvent.AXIS_RTRIGGER,
         MotionEvent.AXIS_HAT_X,
         MotionEvent.AXIS_HAT_Y,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GamepadAxis {
    }

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * The service must also require the {@link android.Manifest.permission#BIND_TV_REMOTE_SERVICE}
     * permission so that other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "com.android.media.tv.remoteprovider.TvRemoteProvider";

    private static final String TAG = "TvRemoteProvider";
    private static final boolean DEBUG_KEYS = false;
    private final Context mContext;
    private final ProviderStub mStub;
    private final LinkedList<Runnable> mOpenBridgeRunnables;
    private ITvRemoteServiceInput mRemoteServiceInput;

    /**
     * Creates a provider for an unbundled emote controller
     * service allowing it to interface with the tv remote controller
     * system service.
     *
     * @param context The application context for the remote provider.
     */
    public TvRemoteProvider(Context context) {
        mContext = context.getApplicationContext();
        mStub = new ProviderStub();
        mOpenBridgeRunnables = new LinkedList<Runnable>();
    }

    /**
     * Gets the context of the remote service provider.
     */
    public final Context getContext() {
        return mContext;
    }

    /**
     * Gets the Binder associated with the provider.
     * <p>
     * This is intended to be used for the onBind() method of a service that implements
     * a remote provider service.
     * </p>
     *
     * @return The IBinder instance associated with the provider.
     */
    public IBinder getBinder() {
        return mStub;
    }

    /**
     * Information about the InputBridge connected status.
     *
     * @param token Identifier for the connection. Null, if failed.
     */
    public void onInputBridgeConnected(@NonNull IBinder token) {
    }

    /**
     * Set a sink for sending events to framework service.
     *
     * @param tvServiceInput sink defined in framework service
     */
    private void setRemoteServiceInputSink(ITvRemoteServiceInput tvServiceInput) {
        synchronized (mOpenBridgeRunnables) {
            mRemoteServiceInput = tvServiceInput;
        }
        mOpenBridgeRunnables.forEach(Runnable::run);
        mOpenBridgeRunnables.clear();
    }

    /**
     * openRemoteInputBridge : Open an input bridge for a particular device.
     * Clients should pass in a token that can be used to match this request with a token that
     * will be returned by {@link TvRemoteProvider#onInputBridgeConnected(IBinder token)}
     * <p>
     * The token should be used for subsequent calls.
     * </p>
     *
     * @param name        Device name
     * @param token       Identifier for this connection
     * @param width       Width of the device's virtual touchpad
     * @param height      Height of the device's virtual touchpad
     * @param maxPointers Maximum supported pointers
     * @throws RuntimeException
     */
    public void openRemoteInputBridge(
            @NonNull IBinder token, @NonNull String name, int width, int height, int maxPointers)
            throws RuntimeException {
        final IBinder finalToken = Objects.requireNonNull(token);
        final String finalName = Objects.requireNonNull(name);

        synchronized (mOpenBridgeRunnables) {
            if (mRemoteServiceInput == null) {
                Log.d(TAG, "Delaying openRemoteInputBridge() for " + finalName);

                mOpenBridgeRunnables.add(() -> {
                    try {
                        mRemoteServiceInput.openInputBridge(
                                finalToken, finalName, width, height, maxPointers);
                        Log.d(TAG, "Delayed openRemoteInputBridge() for " + finalName
                                + ": success");
                    } catch (RemoteException re) {
                        Log.e(TAG, "Delayed openRemoteInputBridge() for " + finalName
                                + ": failure", re);
                    }
                });
                return;
            }
        }
        try {
            mRemoteServiceInput.openInputBridge(finalToken, finalName, width, height, maxPointers);
            Log.d(TAG, "openRemoteInputBridge() for " + finalName + ": success");
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Opens an input bridge as a gamepad device.
     * Clients should pass in a token that can be used to match this request with a token that
     * will be returned by {@link TvRemoteProvider#onInputBridgeConnected(IBinder token)}
     * <p>
     * The token should be used for subsequent calls.
     * </p>
     *
     * @param token       Identifier for this connection
     * @param name        Device name
     * @throws RuntimeException
     */
    public void openGamepadBridge(@NonNull IBinder token, @NonNull  String name)
            throws RuntimeException {
        final IBinder finalToken = Objects.requireNonNull(token);
        final String finalName = Objects.requireNonNull(name);
        synchronized (mOpenBridgeRunnables) {
            if (mRemoteServiceInput == null) {
                Log.d(TAG, "Delaying openGamepadBridge() for " + finalName);

                mOpenBridgeRunnables.add(() -> {
                    try {
                        mRemoteServiceInput.openGamepadBridge(finalToken, finalName);
                        Log.d(TAG, "Delayed openGamepadBridge() for " + finalName + ": success");
                    } catch (RemoteException re) {
                        Log.e(TAG, "Delayed openGamepadBridge() for " + finalName + ": failure",
                                re);
                    }
                });
                return;
            }
        }
        try {
            mRemoteServiceInput.openGamepadBridge(token, finalName);
            Log.d(TAG, "openGamepadBridge() for " + finalName + ": success");
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * closeInputBridge : Close input bridge for a device
     *
     * @param token identifier for this connection
     * @throws RuntimeException
     */
    public void closeInputBridge(@NonNull IBinder token) throws RuntimeException {
        Objects.requireNonNull(token);
        try {
            mRemoteServiceInput.closeInputBridge(token);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * clearInputBridge : Clear out any existing key or pointer events in queue for this device by
     *                    dropping them on the floor and sending an UP to all keys and pointer
     *                    slots.
     *
     * @param token identifier for this connection
     * @throws RuntimeException
     */
    public void clearInputBridge(@NonNull IBinder token) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "clearInputBridge() token " + token);
        try {
            mRemoteServiceInput.clearInputBridge(token);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendTimestamp : Send a timestamp for a set of pointer events
     *
     * @param token     identifier for the device
     * @param timestamp Timestamp to be used in
     *                  {@link android.os.SystemClock#uptimeMillis} time base
     * @throws RuntimeException
     */
    public void sendTimestamp(@NonNull IBinder token, long timestamp) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "sendTimestamp() token: " + token +
                ", timestamp: " + timestamp);
        try {
            mRemoteServiceInput.sendTimestamp(token, timestamp);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendKeyUp : Send key up event for a device
     *
     * @param token   identifier for this connection
     * @param keyCode Key code to be sent
     * @throws RuntimeException
     */
    public void sendKeyUp(@NonNull IBinder token, int keyCode) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "sendKeyUp() token: " + token + ", keyCode: " + keyCode);
        try {
            mRemoteServiceInput.sendKeyUp(token, keyCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendKeyDown : Send key down event for a device
     *
     * @param token   identifier for this connection
     * @param keyCode Key code to be sent
     * @throws RuntimeException
     */
    public void sendKeyDown(@NonNull IBinder token, int keyCode) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "sendKeyDown() token: " + token +
                ", keyCode: " + keyCode);
        try {
            mRemoteServiceInput.sendKeyDown(token, keyCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendPointerUp : Send pointer up event for a device
     *
     * @param token     identifier for the device
     * @param pointerId Pointer id to be used. Value may be from 0
     *                  to {@link MotionEvent#getPointerCount()} -1
     * @throws RuntimeException
     */
    public void sendPointerUp(@NonNull IBinder token, int pointerId) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "sendPointerUp() token: " + token +
                ", pointerId: " + pointerId);
        try {
            mRemoteServiceInput.sendPointerUp(token, pointerId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendPointerDown : Send pointer down event for a device
     *
     * @param token     identifier for the device
     * @param pointerId Pointer id to be used. Value may be from 0
     *                  to {@link MotionEvent#getPointerCount()} -1
     * @param x         X co-ordinates in display pixels
     * @param y         Y co-ordinates in display pixels
     * @throws RuntimeException
     */
    public void sendPointerDown(@NonNull IBinder token, int pointerId, int x, int y)
            throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "sendPointerDown() token: " + token +
                ", pointerId: " + pointerId);
        try {
            mRemoteServiceInput.sendPointerDown(token, pointerId, x, y);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * sendPointerSync : Send pointer sync event for a device
     *
     * @param token identifier for the device
     * @throws RuntimeException
     */
    public void sendPointerSync(@NonNull IBinder token) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) Log.d(TAG, "sendPointerSync() token: " + token);
        try {
            mRemoteServiceInput.sendPointerSync(token);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Send a notification that a gamepad key was pressed.
     *
     * Supported buttons are:
     * <ul>
     *   <li> Right-side buttons: BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y
     *   <li> Digital Triggers and bumpers: BUTTON_L1, BUTTON_R1, BUTTON_L2, BUTTON_R2
     *   <li> Thumb buttons: BUTTON_THUMBL, BUTTON_THUMBR
     *   <li> DPad buttons: DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT
     *   <li> Gamepad buttons: BUTTON_SELECT, BUTTON_START, BUTTON_MODE
     *   <li> Generic buttons: BUTTON_1, BUTTON_2, ...., BUTTON16
     *   <li> Assistant: ASSIST, VOICE_ASSIST
     * </ul>
     *
     * @param token   identifier for the device. This value must never be null.
     * @param keyCode the gamepad key that was pressed (like BUTTON_A)
     *
     */
    @SuppressAutoDoc
    public void sendGamepadKeyDown(@NonNull IBinder token, @GamepadKeyCode int keyCode)
            throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) {
            Log.d(TAG, "sendGamepadKeyDown() token: " + token);
        }

        try {
            mRemoteServiceInput.sendGamepadKeyDown(token, keyCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Send a notification that a gamepad key was released.
     *
     * @see sendGamepadKeyDown for supported key codes.
     *
     * @param token identifier for the device. This value mus never be null.
     * @param keyCode the gamepad key that was pressed
     */
    @SuppressAutoDoc
    public void sendGamepadKeyUp(@NonNull IBinder token, @GamepadKeyCode int keyCode)
            throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) {
            Log.d(TAG, "sendGamepadKeyUp() token: " + token);
        }

        try {
            mRemoteServiceInput.sendGamepadKeyUp(token, keyCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Send a gamepad axis value.
     *
     * Supported axes:
     *  <li> Left Joystick: AXIS_X, AXIS_Y
     *  <li> Right Joystick: AXIS_Z, AXIS_RZ
     *  <li> Triggers: AXIS_LTRIGGER, AXIS_RTRIGGER
     *  <li> DPad: AXIS_HAT_X, AXIS_HAT_Y
     *
     * For non-trigger axes, the range of acceptable values is [-1, 1]. The trigger axes support
     * values [0, 1].
     *
     * @param token identifier for the device. This value must never be null.
     * @param axis  MotionEvent axis
     * @param value the value to send
     */
    @SuppressAutoDoc
    public void sendGamepadAxisValue(
            @NonNull IBinder token, @GamepadAxis int axis,
            @FloatRange(from = -1.0f, to = 1.0f) float value) throws RuntimeException {
        Objects.requireNonNull(token);
        if (DEBUG_KEYS) {
            Log.d(TAG, "sendGamepadAxisValue() token: " + token);
        }

        try {
            mRemoteServiceInput.sendGamepadAxisValue(token, axis, value);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private final class ProviderStub extends ITvRemoteProvider.Stub {
        @Override
        public void setRemoteServiceInputSink(ITvRemoteServiceInput tvServiceInput) {
            TvRemoteProvider.this.setRemoteServiceInputSink(tvServiceInput);
        }

        @Override
        public void onInputBridgeConnected(IBinder token) {
            TvRemoteProvider.this.onInputBridgeConnected(token);
        }
    }
}
