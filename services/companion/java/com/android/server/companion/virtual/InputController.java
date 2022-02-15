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
import android.annotation.StringDef;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Controls virtual input devices, including device lifecycle and event dispatch. */
class InputController {

    private static final String TAG = "VirtualInputController";

    private static final AtomicLong sNextPhysId = new AtomicLong(1);

    static final String PHYS_TYPE_KEYBOARD = "Keyboard";
    static final String PHYS_TYPE_MOUSE = "Mouse";
    static final String PHYS_TYPE_TOUCHSCREEN = "Touchscreen";
    @StringDef(prefix = { "PHYS_TYPE_" }, value = {
            PHYS_TYPE_KEYBOARD,
            PHYS_TYPE_MOUSE,
            PHYS_TYPE_TOUCHSCREEN,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PhysType {
    }

    private final Object mLock;

    /* Token -> file descriptor associations. */
    @VisibleForTesting
    @GuardedBy("mLock")
    final Map<IBinder, InputDeviceDescriptor> mInputDeviceDescriptors = new ArrayMap<>();

    private final NativeWrapper mNativeWrapper;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final InputManagerInternal mInputManagerInternal;

    InputController(@NonNull Object lock) {
        this(lock, new NativeWrapper());
    }

    @VisibleForTesting
    InputController(@NonNull Object lock, @NonNull NativeWrapper nativeWrapper) {
        mLock = lock;
        mNativeWrapper = nativeWrapper;
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
    }

    void close() {
        synchronized (mLock) {
            final Iterator<Map.Entry<IBinder, InputDeviceDescriptor>> iterator =
                    mInputDeviceDescriptors.entrySet().iterator();
            if (iterator.hasNext()) {
                final Map.Entry<IBinder, InputDeviceDescriptor> entry = iterator.next();
                final IBinder token = entry.getKey();
                final InputDeviceDescriptor inputDeviceDescriptor = entry.getValue();
                iterator.remove();
                closeInputDeviceDescriptorLocked(token, inputDeviceDescriptor);
            }
        }
    }

    void createKeyboard(@NonNull String deviceName,
            int vendorId,
            int productId,
            @NonNull IBinder deviceToken,
            int displayId) {
        final String phys = createPhys(PHYS_TYPE_KEYBOARD);
        setUniqueIdAssociation(displayId, phys);
        final int fd = mNativeWrapper.openUinputKeyboard(deviceName, vendorId, productId, phys);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating keyboard: " + -fd);
        }
        final BinderDeathRecipient binderDeathRecipient = new BinderDeathRecipient(deviceToken);
        synchronized (mLock) {
            mInputDeviceDescriptors.put(deviceToken,
                    new InputDeviceDescriptor(fd, binderDeathRecipient,
                            InputDeviceDescriptor.TYPE_KEYBOARD, displayId, phys));
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
        final String phys = createPhys(PHYS_TYPE_MOUSE);
        setUniqueIdAssociation(displayId, phys);
        final int fd = mNativeWrapper.openUinputMouse(deviceName, vendorId, productId, phys);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating mouse: " + -fd);
        }
        final BinderDeathRecipient binderDeathRecipient = new BinderDeathRecipient(deviceToken);
        synchronized (mLock) {
            mInputDeviceDescriptors.put(deviceToken,
                    new InputDeviceDescriptor(fd, binderDeathRecipient,
                            InputDeviceDescriptor.TYPE_MOUSE, displayId, phys));
            mInputManagerInternal.setVirtualMousePointerDisplayId(displayId);
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
        final String phys = createPhys(PHYS_TYPE_TOUCHSCREEN);
        setUniqueIdAssociation(displayId, phys);
        final int fd = mNativeWrapper.openUinputTouchscreen(deviceName, vendorId, productId, phys,
                screenSize.y, screenSize.x);
        if (fd < 0) {
            throw new RuntimeException(
                    "A native error occurred when creating touchscreen: " + -fd);
        }
        final BinderDeathRecipient binderDeathRecipient = new BinderDeathRecipient(deviceToken);
        synchronized (mLock) {
            mInputDeviceDescriptors.put(deviceToken,
                    new InputDeviceDescriptor(fd, binderDeathRecipient,
                            InputDeviceDescriptor.TYPE_TOUCHSCREEN, displayId, phys));
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
            closeInputDeviceDescriptorLocked(token, inputDeviceDescriptor);
        }
    }

    @GuardedBy("mLock")
    private void closeInputDeviceDescriptorLocked(IBinder token,
            InputDeviceDescriptor inputDeviceDescriptor) {
        token.unlinkToDeath(inputDeviceDescriptor.getDeathRecipient(), /* flags= */ 0);
        mNativeWrapper.closeUinput(inputDeviceDescriptor.getFileDescriptor());
        InputManager.getInstance().removeUniqueIdAssociation(inputDeviceDescriptor.getPhys());

        // Reset values to the default if all virtual mice are unregistered, or set display
        // id if there's another mouse (choose the most recent). The inputDeviceDescriptor must be
        // removed from the mInputDeviceDescriptors instance variable prior to this point.
        if (inputDeviceDescriptor.isMouse()) {
            if (mInputManagerInternal.getVirtualMousePointerDisplayId()
                    == inputDeviceDescriptor.getDisplayId()) {
                updateActivePointerDisplayIdLocked();
            }
        }
    }

    void setShowPointerIcon(boolean visible, int displayId) {
        mInputManagerInternal.setPointerIconVisible(visible, displayId);
    }

    void setPointerAcceleration(float pointerAcceleration, int displayId) {
        mInputManagerInternal.setPointerAcceleration(pointerAcceleration, displayId);
    }

    void setDisplayEligibilityForPointerCapture(boolean isEligible, int displayId) {
        mInputManagerInternal.setDisplayEligibilityForPointerCapture(displayId, isEligible);
    }

    @GuardedBy("mLock")
    private void updateActivePointerDisplayIdLocked() {
        InputDeviceDescriptor mostRecentlyCreatedMouse = null;
        for (InputDeviceDescriptor otherInputDeviceDescriptor : mInputDeviceDescriptors.values()) {
            if (otherInputDeviceDescriptor.isMouse()) {
                if (mostRecentlyCreatedMouse == null
                        || (otherInputDeviceDescriptor.getCreationOrderNumber()
                        > mostRecentlyCreatedMouse.getCreationOrderNumber())) {
                    mostRecentlyCreatedMouse = otherInputDeviceDescriptor;
                }
            }
        }
        if (mostRecentlyCreatedMouse != null) {
            mInputManagerInternal.setVirtualMousePointerDisplayId(
                    mostRecentlyCreatedMouse.getDisplayId());
        } else {
            // All mice have been unregistered
            mInputManagerInternal.setVirtualMousePointerDisplayId(Display.INVALID_DISPLAY);
        }
    }

    private static String createPhys(@PhysType String type) {
        return String.format("virtual%s:%d", type, sNextPhysId.getAndIncrement());
    }

    private void setUniqueIdAssociation(int displayId, String phys) {
        final String displayUniqueId = mDisplayManagerInternal.getDisplayInfo(displayId).uniqueId;
        InputManager.getInstance().addUniqueIdAssociation(phys, displayUniqueId);
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
            if (inputDeviceDescriptor.getDisplayId()
                    != mInputManagerInternal.getVirtualMousePointerDisplayId()) {
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
            if (inputDeviceDescriptor.getDisplayId()
                    != mInputManagerInternal.getVirtualMousePointerDisplayId()) {
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
            if (inputDeviceDescriptor.getDisplayId()
                    != mInputManagerInternal.getVirtualMousePointerDisplayId()) {
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
            if (inputDeviceDescriptor.getDisplayId()
                    != mInputManagerInternal.getVirtualMousePointerDisplayId()) {
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
                fout.println("          phys: " + inputDeviceDescriptor.getPhys());
            }
        }
    }

    private static native int nativeOpenUinputKeyboard(String deviceName, int vendorId,
            int productId, String phys);
    private static native int nativeOpenUinputMouse(String deviceName, int vendorId, int productId,
            String phys);
    private static native int nativeOpenUinputTouchscreen(String deviceName, int vendorId,
            int productId, String phys, int height, int width);
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
        public int openUinputKeyboard(String deviceName, int vendorId, int productId, String phys) {
            return nativeOpenUinputKeyboard(deviceName, vendorId, productId, phys);
        }

        public int openUinputMouse(String deviceName, int vendorId, int productId, String phys) {
            return nativeOpenUinputMouse(deviceName, vendorId, productId, phys);
        }

        public int openUinputTouchscreen(String deviceName, int vendorId,
                int productId, String phys, int height, int width) {
            return nativeOpenUinputTouchscreen(deviceName, vendorId, productId, phys, height,
                    width);
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
        private final String mPhys;
        // Monotonically increasing number; devices with lower numbers were created earlier.
        private final long mCreationOrderNumber;

        InputDeviceDescriptor(int fd, IBinder.DeathRecipient deathRecipient, @Type int type,
                int displayId, String phys) {
            mFd = fd;
            mDeathRecipient = deathRecipient;
            mType = type;
            mDisplayId = displayId;
            mPhys = phys;
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

        public String getPhys() {
            return mPhys;
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
