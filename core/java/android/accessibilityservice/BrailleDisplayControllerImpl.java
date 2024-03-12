/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.accessibilityservice;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.Flags;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FunctionalUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Default implementation of {@link BrailleDisplayController}.
 *
 * @hide
 */
// BrailleDisplayControllerImpl is not an API, but it implements BrailleDisplayController APIs.
// This @FlaggedApi annotation tells the linter that this method delegates API checks to its
// callers.
@FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class BrailleDisplayControllerImpl implements BrailleDisplayController {

    private final AccessibilityService mAccessibilityService;
    private final Object mLock;
    private final boolean mIsHidrawSupported;

    private IBrailleDisplayConnection mBrailleDisplayConnection;
    private Executor mCallbackExecutor;
    private BrailleDisplayCallback mCallback;

    /**
     * Read-only property that returns whether HIDRAW access is supported on this device.
     *
     * <p>Defaults to true.
     *
     * <p>Device manufacturers without HIDRAW kernel support can set this to false in
     * the device's product makefile.
     */
    private static final boolean IS_HIDRAW_SUPPORTED = SystemProperties.getBoolean(
            "ro.accessibility.support_hidraw", true);

    BrailleDisplayControllerImpl(AccessibilityService accessibilityService,
            Object lock) {
        this(accessibilityService, lock, IS_HIDRAW_SUPPORTED);
    }

    @VisibleForTesting
    public BrailleDisplayControllerImpl(AccessibilityService accessibilityService,
            Object lock, boolean isHidrawSupported) {
        mAccessibilityService = accessibilityService;
        mLock = lock;
        mIsHidrawSupported = isHidrawSupported;
    }

    @Override
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void connect(@NonNull BluetoothDevice bluetoothDevice,
            @NonNull BrailleDisplayCallback callback) {
        connect(bluetoothDevice, mAccessibilityService.getMainExecutor(), callback);
    }

    @Override
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void connect(@NonNull BluetoothDevice bluetoothDevice,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull BrailleDisplayCallback callback) {
        Objects.requireNonNull(bluetoothDevice);
        Objects.requireNonNull(callbackExecutor);
        Objects.requireNonNull(callback);
        connect(serviceConnection -> serviceConnection.connectBluetoothBrailleDisplay(
                        bluetoothDevice.getAddress(), new IBrailleDisplayControllerWrapper()),
                callbackExecutor, callback);
    }

    @Override
    public void connect(@NonNull UsbDevice usbDevice,
            @NonNull BrailleDisplayCallback callback) {
        connect(usbDevice, mAccessibilityService.getMainExecutor(), callback);
    }

    @Override
    public void connect(@NonNull UsbDevice usbDevice,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull BrailleDisplayCallback callback) {
        Objects.requireNonNull(usbDevice);
        Objects.requireNonNull(callbackExecutor);
        Objects.requireNonNull(callback);
        connect(serviceConnection -> serviceConnection.connectUsbBrailleDisplay(
                        usbDevice, new IBrailleDisplayControllerWrapper()),
                callbackExecutor, callback);
    }

    /**
     * Shared implementation for the {@code connect()} API methods.
     *
     * <p>Performs a blocking call to system_server to create the connection. Success is
     * returned through {@link BrailleDisplayCallback#onConnected} while normal connection
     * errors are returned through {@link BrailleDisplayCallback#onConnectionFailed}. This
     * connection is implemented using cached data from the HIDRAW driver so it returns
     * quickly without needing to perform any I/O with the Braille display.
     *
     * <p>The AIDL call to system_server is blocking (not posted to a handler thread) so
     * that runtime exceptions signaling abnormal connection errors from API misuse
     * (e.g. lacking permissions, providing an invalid BluetoothDevice, calling connect
     * while already connected) are propagated to the API caller.
     */
    private void connect(
            FunctionalUtils.RemoteExceptionIgnoringConsumer<IAccessibilityServiceConnection>
                    createConnection,
            @NonNull Executor callbackExecutor, @NonNull BrailleDisplayCallback callback) {
        BrailleDisplayController.checkApiFlagIsEnabled();
        if (!mIsHidrawSupported) {
            callbackExecutor.execute(() -> callback.onConnectionFailed(
                    BrailleDisplayCallback.FLAG_ERROR_CANNOT_ACCESS));
            return;
        }
        if (isConnected()) {
            throw new IllegalStateException(
                    "This service already has a connected Braille display");
        }
        final IAccessibilityServiceConnection serviceConnection =
                AccessibilityInteractionClient.getConnection(
                        mAccessibilityService.getConnectionId());
        if (serviceConnection == null) {
            throw new IllegalStateException("Accessibility service is not connected");
        }
        synchronized (mLock) {
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
        }
        try {
            createConnection.acceptOrThrow(serviceConnection);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isConnected() {
        BrailleDisplayController.checkApiFlagIsEnabled();
        return mBrailleDisplayConnection != null;
    }

    @Override
    public void write(@NonNull byte[] buffer) throws IOException {
        BrailleDisplayController.checkApiFlagIsEnabled();
        Objects.requireNonNull(buffer);
        if (buffer.length > IBinder.getSuggestedMaxIpcSizeBytes()) {
            // This same check must be performed in the system to prevent reflection misuse,
            // but perform it here too to prevent unnecessary IPCs from non-reflection callers.
            throw new IllegalArgumentException("Invalid write buffer size " + buffer.length);
        }
        synchronized (mLock) {
            if (mBrailleDisplayConnection == null) {
                throw new IOException("Braille display is not connected");
            }
            try {
                mBrailleDisplayConnection.write(buffer);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @Override
    public void disconnect() {
        BrailleDisplayController.checkApiFlagIsEnabled();
        synchronized (mLock) {
            try {
                if (mBrailleDisplayConnection != null) {
                    mBrailleDisplayConnection.disconnect();
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                clearConnectionLocked();
            }
        }
    }

    /**
     * Implementation of the {@code IBrailleDisplayController} AIDL interface provided to
     * system_server, which system_server uses to pass messages back to this
     * {@code BrailleDisplayController}.
     *
     * <p>Messages from system_server are routed to the {@link BrailleDisplayCallback} callbacks
     * implemented by the accessibility service.
     *
     * <p>Note: Per API Guidelines 7.5 the Binder identity must be cleared before invoking the
     * callback executor so that Binder identity checks in the callbacks are performed using the
     * app's identity.
     */
    private final class IBrailleDisplayControllerWrapper extends IBrailleDisplayController.Stub {
        /**
         * Called when the system successfully connects to a Braille display.
         */
        @Override
        public void onConnected(IBrailleDisplayConnection connection, byte[] hidDescriptor) {
            BrailleDisplayController.checkApiFlagIsEnabled();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mBrailleDisplayConnection = connection;
                    mCallbackExecutor.execute(() -> mCallback.onConnected(hidDescriptor));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Called when the system is unable to connect to a Braille display.
         */
        @Override
        public void onConnectionFailed(@BrailleDisplayCallback.ErrorCode int errorCode) {
            BrailleDisplayController.checkApiFlagIsEnabled();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mCallbackExecutor.execute(() -> mCallback.onConnectionFailed(errorCode));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Called when input is received from the currently connected Braille display.
         */
        @Override
        public void onInput(byte[] input) {
            BrailleDisplayController.checkApiFlagIsEnabled();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // Ignore input that arrives after disconnection.
                    if (mBrailleDisplayConnection != null) {
                        mCallbackExecutor.execute(() -> mCallback.onInput(input));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Called when the currently connected Braille display is disconnected.
         */
        @Override
        public void onDisconnected() {
            BrailleDisplayController.checkApiFlagIsEnabled();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mCallbackExecutor.execute(mCallback::onDisconnected);
                    clearConnectionLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void clearConnectionLocked() {
        mBrailleDisplayConnection = null;
    }

}
