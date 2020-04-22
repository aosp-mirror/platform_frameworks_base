/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.tv;

import android.os.IBinder;

import dalvik.system.CloseGuard;

import java.io.IOException;

/**
 * Sends the input event to the linux driver.
 */
public final class UinputBridge {
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private long mPtr;
    private IBinder mToken;

    private static native long nativeOpen(String name, String uniqueId, int width, int height,
                                          int maxPointers);
    private static native void nativeClose(long ptr);
    private static native void nativeClear(long ptr);
    private static native void nativeSendKey(long ptr, int keyCode, boolean down);
    private static native void nativeSendPointerDown(long ptr, int pointerId, int x, int y);
    private static native void nativeSendPointerUp(long ptr, int pointerId);
    private static native void nativeSendPointerSync(long ptr);

    /** Opens a gamepad - will support gamepad key and axis sending */
    private static native long nativeGamepadOpen(String name, String uniqueId);

    /**
     * Marks the specified key up/down for a gamepad.
     *
     *  @param keyCode - a code like BUTTON_MODE, BUTTON_A, BUTTON_B, ...
     */
    private static native void nativeSendGamepadKey(long ptr, int keyCode, boolean down);

    /**
     * Send an axis value.
     *
     * Available axes are:
     *  <li> Left joystick: AXIS_X, AXIS_Y
     *  <li> Right joystick: AXIS_Z, AXIS_RZ
     *  <li> Analog triggers: AXIS_LTRIGGER, AXIS_RTRIGGER
     *  <li> DPad: AXIS_HAT_X, AXIS_HAT_Y
     *
     * @param axis is a MotionEvent.AXIS_* value.
     * @param value is a value between -1 and 1 (inclusive)
     *
     */
    private static native void nativeSendGamepadAxisValue(long ptr, int axis, float value);

    public UinputBridge(IBinder token, String name, int width, int height, int maxPointers)
                        throws IOException {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Touchpad must be at least 1x1.");
        }
        if (maxPointers < 1 || maxPointers > 32) {
            throw new IllegalArgumentException("Touchpad must support between 1 and 32 pointers.");
        }
        if (token == null) {
            throw new IllegalArgumentException("Token cannot be null");
        }
        mPtr = nativeOpen(name, token.toString(), width, height, maxPointers);
        if (mPtr == 0) {
            throw new IOException("Could not open uinput device " + name);
        }
        mToken = token;
        mCloseGuard.open("close");
    }

    /** Constructor used by static factory methods */
    private UinputBridge(IBinder token, long ptr) {
        mPtr = ptr;
        mToken = token;
        mCloseGuard.open("close");
    }

    /** Opens a UinputBridge that supports gamepad buttons and axes. */
    public static UinputBridge openGamepad(IBinder token, String name)
            throws IOException {
        if (token == null) {
            throw new IllegalArgumentException("Token cannot be null");
        }
        long ptr = nativeGamepadOpen(name, token.toString());
        if (ptr == 0) {
            throw new IOException("Could not open uinput device " + name);
        }

        return new UinputBridge(token, ptr);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            close(mToken);
        } finally {
            mToken = null;
            super.finalize();
        }
    }

    public void close(IBinder token) {
        if (isTokenValid(token)) {
            if (mPtr != 0) {
                clear(token);
                nativeClose(mPtr);

                mPtr = 0;
                mCloseGuard.close();
            }
        }
    }

    public IBinder getToken() {
        return mToken;
    }

    protected boolean isTokenValid(IBinder token) {
        return mToken.equals(token);
    }

    public void sendKeyDown(IBinder token, int keyCode) {
        if (isTokenValid(token)) {
            nativeSendKey(mPtr, keyCode, true /*down*/);
        }
    }

    public void sendKeyUp(IBinder token, int keyCode) {
        if (isTokenValid(token)) {
            nativeSendKey(mPtr, keyCode, false /*down*/);
        }
    }

    public void sendPointerDown(IBinder token, int pointerId, int x, int y) {
        if (isTokenValid(token)) {
            nativeSendPointerDown(mPtr, pointerId, x, y);
        }
    }

    public void sendPointerUp(IBinder token, int pointerId) {
        if (isTokenValid(token)) {
            nativeSendPointerUp(mPtr, pointerId);
        }
    }

    public void sendPointerSync(IBinder token) {
        if (isTokenValid(token)) {
            nativeSendPointerSync(mPtr);
        }
    }

    /** Send a gamepad key
     *  @param keyIndex - the index of the w3-spec key
     *  @param down - is the key pressed ?
     */
    public void sendGamepadKey(IBinder token, int keyCode, boolean down) {
        if (isTokenValid(token)) {
            nativeSendGamepadKey(mPtr, keyCode, down);
        }
    }

    /**
     * Send a gamepad axis value.
     *
     * @param axis is the axis code (MotionEvent.AXIS_*)
     * @param value is the value to set for that axis in [-1, 1]
     */
    public void sendGamepadAxisValue(IBinder token, int axis, float value) {
        if (isTokenValid(token)) {
            nativeSendGamepadAxisValue(mPtr, axis, value);
        }
    }

    public void clear(IBinder token) {
        if (isTokenValid(token)) {
            nativeClear(mPtr);
        }
    }

}
