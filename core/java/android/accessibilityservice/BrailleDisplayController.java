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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.Flags;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Used to communicate with a Braille display that supports the Braille display HID standard
 * (usage page 0x41).
 *
 * <p>Only one Braille display may be connected at a time.
 */
// This interface doesn't actually own resources. Its I/O connections are owned, monitored,
// and automatically closed by the system after the accessibility service is disconnected.
@SuppressLint("NotCloseable")
@FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
public interface BrailleDisplayController {

    /**
     * Throw {@link IllegalStateException} if this feature's aconfig flag is disabled.
     *
     * @hide
     */
    static void checkApiFlagIsEnabled() {
        if (!Flags.brailleDisplayHid()) {
            throw new IllegalStateException("Flag BRAILLE_DISPLAY_HID not enabled");
        }
    }

    /**
     * Interface provided to {@link BrailleDisplayController} connection methods to
     * receive callbacks from the system.
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    interface BrailleDisplayCallback {
        /**
         * The system cannot access connected HID devices.
         */
        @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
        int FLAG_ERROR_CANNOT_ACCESS = 1 << 0;
        /**
         * A unique Braille display matching the requested properties could not be identified.
         */
        @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
        int FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND = 1 << 1;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = "FLAG_ERROR_", value = {
                FLAG_ERROR_CANNOT_ACCESS,
                FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND,
        })
        @interface ErrorCode {
        }

        /**
         * Callback to observe a successful Braille display connection.
         *
         * <p>The provided HID report descriptor should be used to understand the input bytes
         * received from the Braille display via {@link #onInput} and to prepare
         * the output sent to the Braille display via {@link #write}.
         *
         * @param hidDescriptor The HID report descriptor for this Braille display.
         * @see #connect(BluetoothDevice, BrailleDisplayCallback)
         * @see #connect(UsbDevice, BrailleDisplayCallback)
         */
        @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
        void onConnected(@NonNull byte[] hidDescriptor);

        /**
         * Callback to observe a failed Braille display connection.
         *
         * @param errorFlags A bitmask of error codes for the connection failure.
         * @see #connect(BluetoothDevice, BrailleDisplayCallback)
         * @see #connect(UsbDevice, BrailleDisplayCallback)
         */
        @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
        void onConnectionFailed(@ErrorCode int errorFlags);

        /**
         * Callback to observe input bytes from the currently connected Braille display.
         *
         * @param input The input bytes from the Braille display, formatted according to the HID
         *              report descriptor and the HIDRAW kernel driver.
         */
        @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
        void onInput(@NonNull byte[] input);

        /**
         * Callback to observe when the currently connected Braille display is disconnected by the
         * system.
         */
        @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
        void onDisconnected();
    }

    /**
     * Connects to the requested bluetooth Braille display using the Braille
     * display HID standard (usage page 0x41).
     *
     * <p>If successful then the HID report descriptor will be provided to
     * {@link BrailleDisplayCallback#onConnected}
     * and the Braille display will start sending incoming input bytes to
     * {@link BrailleDisplayCallback#onInput}. If there is an error in reading input
     * then the system will disconnect the Braille display.
     *
     * <p>Note that the callbacks will be executed on the main thread using
     * {@link AccessibilityService#getMainExecutor()}. To specify the execution thread, use
     * {@link #connect(BluetoothDevice, Executor, BrailleDisplayCallback)}.
     *
     * @param bluetoothDevice The Braille display device.
     * @param callback        Callbacks used to provide connection results.
     * @see BrailleDisplayCallback#onConnected
     * @see BrailleDisplayCallback#onConnectionFailed
     * @throws IllegalStateException if a Braille display is already connected to this controller.
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void connect(@NonNull BluetoothDevice bluetoothDevice,
            @NonNull BrailleDisplayCallback callback);

    /**
     * Connects to the requested bluetooth Braille display using the Braille
     * display HID standard (usage page 0x41).
     *
     * <p>If successful then the HID report descriptor will be provided to
     * {@link BrailleDisplayCallback#onConnected}
     * and the Braille display will start sending incoming input bytes to
     * {@link BrailleDisplayCallback#onInput}. If there is an error in reading input
     * then the system will disconnect the Braille display.
     *
     * @param bluetoothDevice  The Braille display device.
     * @param callbackExecutor Executor for executing the provided callbacks.
     * @param callback         Callbacks used to provide connection results.
     * @see BrailleDisplayCallback#onConnected
     * @see BrailleDisplayCallback#onConnectionFailed
     * @throws IllegalStateException if a Braille display is already connected to this controller.
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    void connect(@NonNull BluetoothDevice bluetoothDevice,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull BrailleDisplayCallback callback);

    /**
     * Connects to the requested USB Braille display using the Braille
     * display HID standard (usage page 0x41).
     *
     * <p>If successful then the HID report descriptor will be provided to
     * {@link BrailleDisplayCallback#onConnected}
     * and the Braille display will start sending incoming input bytes to
     * {@link BrailleDisplayCallback#onInput}. If there is an error in reading input
     * then the system will disconnect the Braille display.
     *
     * <p>The accessibility service app must already have approval to access the USB device
     * from the standard {@link android.hardware.usb.UsbManager} access approval process.
     *
     * <p>Note that the callbacks will be executed on the main thread using
     * {@link AccessibilityService#getMainExecutor()}. To specify the execution thread, use
     * {@link #connect(UsbDevice, Executor, BrailleDisplayCallback)}.
     *
     * @param usbDevice        The Braille display device.
     * @param callback         Callbacks used to provide connection results.
     * @see BrailleDisplayCallback#onConnected
     * @see BrailleDisplayCallback#onConnectionFailed
     * @throws SecurityException if the caller does not have USB device approval.
     * @throws IllegalStateException if a Braille display is already connected to this controller.
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    void connect(@NonNull UsbDevice usbDevice,
            @NonNull BrailleDisplayCallback callback);

    /**
     * Connects to the requested USB Braille display using the Braille
     * display HID standard (usage page 0x41).
     *
     * <p>If successful then the HID report descriptor will be provided to
     * {@link BrailleDisplayCallback#onConnected}
     * and the Braille display will start sending incoming input bytes to
     * {@link BrailleDisplayCallback#onInput}. If there is an error in reading input
     * then the system will disconnect the Braille display.
     *
     * <p>The accessibility service app must already have approval to access the USB device
     * from the standard {@link android.hardware.usb.UsbManager} access approval process.
     *
     * @param usbDevice        The Braille display device.
     * @param callbackExecutor Executor for executing the provided callbacks.
     * @param callback         Callbacks used to provide connection results.
     * @see BrailleDisplayCallback#onConnected
     * @see BrailleDisplayCallback#onConnectionFailed
     * @throws SecurityException if the caller does not have USB device approval.
     * @throws IllegalStateException if a Braille display is already connected to this controller.
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    void connect(@NonNull UsbDevice usbDevice,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull BrailleDisplayCallback callback);

    /**
     * Returns true if a Braille display is currently connected, otherwise false.
     *
     * @see #connect
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    boolean isConnected();

    /**
     * Writes a HID report to the currently connected Braille display.
     *
     * <p>This method returns immediately after dispatching the write request to the system.
     * If the system experiences an error in writing output (e.g. the Braille display is unplugged
     * after the system receives the write request but before writing the bytes to the Braille
     * display) then the system will disconnect the Braille display, which calls
     * {@link BrailleDisplayCallback#onDisconnected()}.
     *
     * @param buffer The bytes to write to the Braille display. These bytes should be formatted
     *               according to the HID report descriptor and the HIDRAW kernel driver.
     * @throws IOException              if there is no currently connected Braille display.
     * @throws IllegalArgumentException if the buffer exceeds the maximum safe payload size for
     *                                  binder transactions of
     *                                  {@link IBinder#getSuggestedMaxIpcSizeBytes()}
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    void write(@NonNull byte[] buffer) throws IOException;

    /**
     * Disconnects from the currently connected Braille display.
     *
     * @see #isConnected()
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    void disconnect();

    /**
     * Provides test Braille display data to be used for automated CTS tests.
     *
     * <p>See {@code TEST_BRAILLE_DISPLAY_*} bundle keys.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @RequiresPermission(android.Manifest.permission.MANAGE_ACCESSIBILITY)
    @TestApi
    static void setTestBrailleDisplayData(
            @NonNull AccessibilityService service,
            @NonNull List<Bundle> brailleDisplays) {
        checkApiFlagIsEnabled();
        final IAccessibilityServiceConnection serviceConnection =
                AccessibilityInteractionClient.getConnection(service.getConnectionId());
        if (serviceConnection != null) {
            try {
                serviceConnection.setTestBrailleDisplayData(brailleDisplays);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** @hide */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @TestApi
    String TEST_BRAILLE_DISPLAY_HIDRAW_PATH = "HIDRAW_PATH";
    /** @hide */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @TestApi
    String TEST_BRAILLE_DISPLAY_DESCRIPTOR = "DESCRIPTOR";
    /** @hide */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @TestApi
    String TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH = "BUS_BLUETOOTH";
    /** @hide */
    @FlaggedApi(Flags.FLAG_BRAILLE_DISPLAY_HID)
    @TestApi
    String TEST_BRAILLE_DISPLAY_UNIQUE_ID = "UNIQUE_ID";
    /** @hide */
    String TEST_BRAILLE_DISPLAY_NAME = "NAME";
}
