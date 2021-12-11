/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.virtual;

import android.annotation.NonNull;
import android.graphics.Point;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualTouchEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Map;

/** Controls virtual input devices, including device lifecycle and event dispatch. */
class InputController {

    private final Object mLock;

    /* Token -> file descriptor associations. */
    @VisibleForTesting
    @GuardedBy("mLock")
    final Map<IBinder, Integer> mInputDeviceFds = new ArrayMap<>();

    private final NativeWrapper mNativeWrapper;

    InputController(@NonNull Object lock) {
        this(lock, new NativeWrapper());
    }

    @VisibleForTesting
    InputController(@NonNull Object lock, @NonNull NativeWrapper nativeWrapper) {
        mLock = lock;
        mNativeWrapper = nativeWrapper;
    }

    void close() {
        synchronized (mLock) {
            for (int fd : mInputDeviceFds.values()) {
                mNativeWrapper.closeUinput(fd);
            }
            mInputDeviceFds.clear();
        }
    }

    void createKeyboard(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken) {
        final int fd = mNativeWrapper.openUinputKeyboard(deviceName, vendorId, productId);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating keyboard: " + -fd);
        }
        synchronized (mLock) {
            mInputDeviceFds.put(deviceToken, fd);
        }
        try {
            deviceToken.linkToDeath(new BinderDeathRecipient(deviceToken), /* flags= */ 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Could not create virtual keyboard", e);
        }
    }

    void createMouse(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken) {
        final int fd = mNativeWrapper.openUinputMouse(deviceName, vendorId, productId);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating mouse: " + -fd);
        }
        synchronized (mLock) {
            mInputDeviceFds.put(deviceToken, fd);
        }
        try {
            deviceToken.linkToDeath(new BinderDeathRecipient(deviceToken), /* flags= */ 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Could not create virtual mouse", e);
        }
    }

    void createTouchscreen(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken,
            @NonNull Point screenSize) {
        final int fd = mNativeWrapper.openUinputTouchscreen(deviceName, vendorId, productId,
                screenSize.y, screenSize.x);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating touchscreen: " + -fd);
        }
        synchronized (mLock) {
            mInputDeviceFds.put(deviceToken, fd);
        }
        try {
            deviceToken.linkToDeath(new BinderDeathRecipient(deviceToken), /* flags= */ 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Could not create virtual touchscreen", e);
        }
    }

    void unregisterInputDevice(@NonNull IBinder token) {
        synchronized (mLock) {
            final Integer fd = mInputDeviceFds.remove(token);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "Could not unregister input device for given token");
            }
            mNativeWrapper.closeUinput(fd);
        }
    }

    boolean sendKeyEvent(@NonNull IBinder token, @NonNull VirtualKeyEvent event) {
        synchronized (mLock) {
            final Integer fd = mInputDeviceFds.get(token);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "Could not send key event to input device for given token");
            }
            return mNativeWrapper.writeKeyEvent(fd, event.getKeyCode(), event.getAction());
        }
    }

    boolean sendButtonEvent(@NonNull IBinder token, @NonNull VirtualMouseButtonEvent event) {
        synchronized (mLock) {
            final Integer fd = mInputDeviceFds.get(token);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "Could not send button event to input device for given token");
            }
            return mNativeWrapper.writeButtonEvent(fd, event.getButtonCode(), event.getAction());
        }
    }

    boolean sendTouchEvent(@NonNull IBinder token, @NonNull VirtualTouchEvent event) {
        synchronized (mLock) {
            final Integer fd = mInputDeviceFds.get(token);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "Could not send touch event to input device for given token");
            }
            return mNativeWrapper.writeTouchEvent(fd, event.getPointerId(), event.getToolType(),
                    event.getAction(), event.getX(), event.getY(), event.getPressure(),
                    event.getMajorAxisSize());
        }
    }

    boolean sendRelativeEvent(@NonNull IBinder token, @NonNull VirtualMouseRelativeEvent event) {
        synchronized (mLock) {
            final Integer fd = mInputDeviceFds.get(token);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "Could not send relative event to input device for given token");
            }
            return mNativeWrapper.writeRelativeEvent(fd, event.getRelativeX(),
                    event.getRelativeY());
        }
    }

    boolean sendScrollEvent(@NonNull IBinder token, @NonNull VirtualMouseScrollEvent event) {
        synchronized (mLock) {
            final Integer fd = mInputDeviceFds.get(token);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "Could not send scroll event to input device for given token");
            }
            return mNativeWrapper.writeScrollEvent(fd, event.getXAxisMovement(),
                    event.getYAxisMovement());
        }
    }

    public void dump(@NonNull PrintWriter fout) {
        fout.println("    InputController: ");
        synchronized (mLock) {
            fout.println("      Active file descriptors: ");
            for (int inputDeviceFd : mInputDeviceFds.values()) {
                fout.println(inputDeviceFd);
            }
        }
    }

    private static native int nativeOpenUinputKeyboard(String deviceName, int vendorId,
            int productId);
    private static native int nativeOpenUinputMouse(String deviceName, int vendorId,
            int productId);
    private static native int nativeOpenUinputTouchscreen(String deviceName, int vendorId,
            int productId, int height, int width);
    private static native boolean nativeCloseUinput(int fd);
    private static native boolean nativeWriteKeyEvent(int fd, int androidKeyCode, int action);
    private static native boolean nativeWriteButtonEvent(int fd, int buttonCode, int action);
    private static native boolean nativeWriteTouchEvent(int fd, int pointerId, int toolType,
            int action, float locationX, float locationY, float pressure, float majorAxisSize);
    private static native boolean nativeWriteRelativeEvent(int fd, float relativeX,
            float relativeY);
    private static native boolean nativeWriteScrollEvent(int fd, float xAxisMovement,
            float yAxisMovement);

    /** Wrapper around the static native methods for tests. */
    @VisibleForTesting
    protected static class NativeWrapper {
        public int openUinputKeyboard(String deviceName, int vendorId, int productId) {
            return nativeOpenUinputKeyboard(deviceName, vendorId,
                    productId);
        }

        public int openUinputMouse(String deviceName, int vendorId, int productId) {
            return nativeOpenUinputMouse(deviceName, vendorId,
                    productId);
        }

        public int openUinputTouchscreen(String deviceName, int vendorId, int productId, int height,
                int width) {
            return nativeOpenUinputTouchscreen(deviceName, vendorId,
                    productId, height, width);
        }

        public boolean closeUinput(int fd) {
            return nativeCloseUinput(fd);
        }

        public boolean writeKeyEvent(int fd, int androidKeyCode, int action) {
            return nativeWriteKeyEvent(fd, androidKeyCode, action);
        }

        public boolean writeButtonEvent(int fd, int buttonCode, int action) {
            return nativeWriteButtonEvent(fd, buttonCode, action);
        }

        public boolean writeTouchEvent(int fd, int pointerId, int toolType, int action,
                float locationX, float locationY, float pressure, float majorAxisSize) {
            return nativeWriteTouchEvent(fd, pointerId, toolType,
                    action, locationX, locationY,
                    pressure, majorAxisSize);
        }

        public boolean writeRelativeEvent(int fd, float relativeX, float relativeY) {
            return nativeWriteRelativeEvent(fd, relativeX, relativeY);
        }

        public boolean writeScrollEvent(int fd, float xAxisMovement, float yAxisMovement) {
            return nativeWriteScrollEvent(fd, xAxisMovement,
                    yAxisMovement);
        }
    }

    private final class BinderDeathRecipient implements IBinder.DeathRecipient {

        private final IBinder mDeviceToken;

        BinderDeathRecipient(IBinder deviceToken) {
            mDeviceToken = deviceToken;
        }

        @Override
        public void binderDied() {
            unregisterInputDevice(mDeviceToken);
        }
    }
}
