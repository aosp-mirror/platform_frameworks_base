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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.input.InputManagerInternal;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualTouchEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Controls virtual input devices, including device lifecycle and event dispatch. */
class InputController {

    private static final String TAG = "VirtualInputController";

    private final Object mLock;

    /* Token -> file descriptor associations. */
    @VisibleForTesting
    @GuardedBy("mLock")
    final Map<IBinder, InputDeviceDescriptor> mInputDeviceDescriptors = new ArrayMap<>();

    private final NativeWrapper mNativeWrapper;

    /**
     * Because the pointer is a singleton, it can only be targeted at one display at a time. Because
     * multiple mice could be concurrently registered, mice that are associated with a different
     * display than the current target display should not be allowed to affect the current target.
     */
    @VisibleForTesting int mActivePointerDisplayId;

    InputController(@NonNull Object lock) {
        this(lock, new NativeWrapper());
    }

    @VisibleForTesting
    InputController(@NonNull Object lock, @NonNull NativeWrapper nativeWrapper) {
        mLock = lock;
        mNativeWrapper = nativeWrapper;
        mActivePointerDisplayId = Display.INVALID_DISPLAY;
    }

    void close() {
        synchronized (mLock) {
            for (InputDeviceDescriptor inputDeviceDescriptor : mInputDeviceDescriptors.values()) {
                mNativeWrapper.closeUinput(inputDeviceDescriptor.getFileDescriptor());
            }
            mInputDeviceDescriptors.clear();
            resetMouseValuesLocked();
        }
    }

    void createKeyboard(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken,
            int displayId) {
        final int fd = mNativeWrapper.openUinputKeyboard(deviceName, vendorId, productId);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating keyboard: " + -fd);
        }
        final BinderDeathRecipient binderDeathRecipient = new BinderDeathRecipient(deviceToken);
        synchronized (mLock) {
            mInputDeviceDescriptors.put(deviceToken,
                    new InputDeviceDescriptor(fd, binderDeathRecipient,
                            InputDeviceDescriptor.TYPE_KEYBOARD, displayId));
        }
        try {
            deviceToken.linkToDeath(binderDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            // TODO(b/215608394): remove and close InputDeviceDescriptor
            throw new RuntimeException("Could not create virtual keyboard", e);
        }
    }

    void createMouse(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken,
            int displayId) {
        final int fd = mNativeWrapper.openUinputMouse(deviceName, vendorId, productId);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating mouse: " + -fd);
        }
        final BinderDeathRecipient binderDeathRecipient = new BinderDeathRecipient(deviceToken);
        synchronized (mLock) {
            mInputDeviceDescriptors.put(deviceToken,
                    new InputDeviceDescriptor(fd, binderDeathRecipient,
                            InputDeviceDescriptor.TYPE_MOUSE, displayId));
            final InputManagerInternal inputManagerInternal =
                    LocalServices.getService(InputManagerInternal.class);
            inputManagerInternal.setVirtualMousePointerDisplayId(displayId);
            mActivePointerDisplayId = displayId;
        }
        try {
            deviceToken.linkToDeath(binderDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            // TODO(b/215608394): remove and close InputDeviceDescriptor
            throw new RuntimeException("Could not create virtual mouse", e);
        }
    }

    void createTouchscreen(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken,
            int displayId,
            @NonNull Point screenSize) {
        final int fd = mNativeWrapper.openUinputTouchscreen(deviceName, vendorId, productId,
                screenSize.y, screenSize.x);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating touchscreen: " + -fd);
        }
        final BinderDeathRecipient binderDeathRecipient = new BinderDeathRecipient(deviceToken);
        synchronized (mLock) {
            mInputDeviceDescriptors.put(deviceToken,
                    new InputDeviceDescriptor(fd, binderDeathRecipient,
                            InputDeviceDescriptor.TYPE_TOUCHSCREEN, displayId));
        }
        try {
            deviceToken.linkToDeath(binderDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            // TODO(b/215608394): remove and close InputDeviceDescriptor
            throw new RuntimeException("Could not create virtual touchscreen", e);
        }
    }

    void unregisterInputDevice(@NonNull IBinder token) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.remove(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not unregister input device for given token");
            }
            token.unlinkToDeath(inputDeviceDescriptor.getDeathRecipient(), /* flags= */ 0);
            mNativeWrapper.closeUinput(inputDeviceDescriptor.getFileDescriptor());

            // Reset values to the default if all virtual mice are unregistered, or set display
            // id if there's another mouse (choose the most recent).
            if (inputDeviceDescriptor.isMouse()) {
                updateMouseValuesLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private void updateMouseValuesLocked() {
        InputDeviceDescriptor mostRecentlyCreatedMouse = null;
        for (InputDeviceDescriptor otherInputDeviceDescriptor :
                mInputDeviceDescriptors.values()) {
            if (otherInputDeviceDescriptor.isMouse()) {
                if (mostRecentlyCreatedMouse == null
                        || (otherInputDeviceDescriptor.getCreationOrderNumber()
                        > mostRecentlyCreatedMouse.getCreationOrderNumber())) {
                    mostRecentlyCreatedMouse = otherInputDeviceDescriptor;
                }
            }
        }
        if (mostRecentlyCreatedMouse != null) {
            final InputManagerInternal inputManagerInternal =
                    LocalServices.getService(InputManagerInternal.class);
            inputManagerInternal.setVirtualMousePointerDisplayId(
                    mostRecentlyCreatedMouse.getDisplayId());
            mActivePointerDisplayId = mostRecentlyCreatedMouse.getDisplayId();
        } else {
            // All mice have been unregistered; reset all values.
            resetMouseValuesLocked();
        }
    }

    private void resetMouseValuesLocked() {
        final InputManagerInternal inputManagerInternal =
                LocalServices.getService(InputManagerInternal.class);
        inputManagerInternal.setVirtualMousePointerDisplayId(Display.INVALID_DISPLAY);
        mActivePointerDisplayId = Display.INVALID_DISPLAY;
    }

    boolean sendKeyEvent(@NonNull IBinder token, @NonNull VirtualKeyEvent event) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.get(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not send key event to input device for given token");
            }
            return mNativeWrapper.writeKeyEvent(inputDeviceDescriptor.getFileDescriptor(),
                    event.getKeyCode(), event.getAction());
        }
    }

    boolean sendButtonEvent(@NonNull IBinder token, @NonNull VirtualMouseButtonEvent event) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.get(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not send button event to input device for given token");
            }
            if (inputDeviceDescriptor.getDisplayId() != mActivePointerDisplayId) {
                throw new IllegalStateException(
                        "Display id associated with this mouse is not currently targetable");
            }
            return mNativeWrapper.writeButtonEvent(inputDeviceDescriptor.getFileDescriptor(),
                    event.getButtonCode(), event.getAction());
        }
    }

    boolean sendTouchEvent(@NonNull IBinder token, @NonNull VirtualTouchEvent event) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.get(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not send touch event to input device for given token");
            }
            return mNativeWrapper.writeTouchEvent(inputDeviceDescriptor.getFileDescriptor(),
                    event.getPointerId(), event.getToolType(), event.getAction(), event.getX(),
                    event.getY(), event.getPressure(), event.getMajorAxisSize());
        }
    }

    boolean sendRelativeEvent(@NonNull IBinder token, @NonNull VirtualMouseRelativeEvent event) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.get(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not send relative event to input device for given token");
            }
            if (inputDeviceDescriptor.getDisplayId() != mActivePointerDisplayId) {
                throw new IllegalStateException(
                        "Display id associated with this mouse is not currently targetable");
            }
            return mNativeWrapper.writeRelativeEvent(inputDeviceDescriptor.getFileDescriptor(),
                    event.getRelativeX(), event.getRelativeY());
        }
    }

    boolean sendScrollEvent(@NonNull IBinder token, @NonNull VirtualMouseScrollEvent event) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.get(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not send scroll event to input device for given token");
            }
            if (inputDeviceDescriptor.getDisplayId() != mActivePointerDisplayId) {
                throw new IllegalStateException(
                        "Display id associated with this mouse is not currently targetable");
            }
            return mNativeWrapper.writeScrollEvent(inputDeviceDescriptor.getFileDescriptor(),
                    event.getXAxisMovement(), event.getYAxisMovement());
        }
    }

    public PointF getCursorPosition(@NonNull IBinder token) {
        synchronized (mLock) {
            final InputDeviceDescriptor inputDeviceDescriptor = mInputDeviceDescriptors.get(
                    token);
            if (inputDeviceDescriptor == null) {
                throw new IllegalArgumentException(
                        "Could not get cursor position for input device for given token");
            }
            if (inputDeviceDescriptor.getDisplayId() != mActivePointerDisplayId) {
                throw new IllegalStateException(
                        "Display id associated with this mouse is not currently targetable");
            }
            return LocalServices.getService(InputManagerInternal.class).getCursorPosition();
        }
    }

    public void dump(@NonNull PrintWriter fout) {
        fout.println("    InputController: ");
        synchronized (mLock) {
            fout.println("      Active descriptors: ");
            for (InputDeviceDescriptor inputDeviceDescriptor : mInputDeviceDescriptors.values()) {
                fout.println("        fd: " + inputDeviceDescriptor.getFileDescriptor());
                fout.println("          displayId: " + inputDeviceDescriptor.getDisplayId());
                fout.println("          creationOrder: "
                        + inputDeviceDescriptor.getCreationOrderNumber());
                fout.println("          type: " + inputDeviceDescriptor.getType());
            }
            fout.println("      Active mouse display id: " + mActivePointerDisplayId);
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

    @VisibleForTesting static final class InputDeviceDescriptor {

        static final int TYPE_KEYBOARD = 1;
        static final int TYPE_MOUSE = 2;
        static final int TYPE_TOUCHSCREEN = 3;
        @IntDef(prefix = { "TYPE_" }, value = {
                TYPE_KEYBOARD,
                TYPE_MOUSE,
                TYPE_TOUCHSCREEN,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface Type {
        }

        private static final AtomicLong sNextCreationOrderNumber = new AtomicLong(1);

        private final int mFd;
        private final IBinder.DeathRecipient mDeathRecipient;
        private final @Type int mType;
        private final int mDisplayId;
        // Monotonically increasing number; devices with lower numbers were created earlier.
        private final long mCreationOrderNumber;

        InputDeviceDescriptor(int fd, IBinder.DeathRecipient deathRecipient,
                @Type int type, int displayId) {
            mFd = fd;
            mDeathRecipient = deathRecipient;
            mType = type;
            mDisplayId = displayId;
            mCreationOrderNumber = sNextCreationOrderNumber.getAndIncrement();
        }

        public int getFileDescriptor() {
            return mFd;
        }

        public int getType() {
            return mType;
        }

        public boolean isMouse() {
            return mType == TYPE_MOUSE;
        }

        public IBinder.DeathRecipient getDeathRecipient() {
            return mDeathRecipient;
        }

        public int getDisplayId() {
            return mDisplayId;
        }

        public long getCreationOrderNumber() {
            return mCreationOrderNumber;
        }
    }

    private final class BinderDeathRecipient implements IBinder.DeathRecipient {

        private final IBinder mDeviceToken;

        BinderDeathRecipient(IBinder deviceToken) {
            mDeviceToken = deviceToken;
        }

        @Override
        public void binderDied() {
            // All callers are expected to call {@link VirtualDevice#unregisterInputDevice} before
            // quitting, which removes this death recipient. If this is invoked, the remote end
            // died, or they disposed of the object without properly unregistering.
            Slog.e(TAG, "Virtual input controller binder died");
            unregisterInputDevice(mDeviceToken);
        }
    }
}
