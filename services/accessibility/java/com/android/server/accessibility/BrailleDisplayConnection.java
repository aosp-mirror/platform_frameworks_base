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

package com.android.server.accessibility;

import static android.accessibilityservice.BrailleDisplayController.BrailleDisplayCallback.FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND;
import static android.accessibilityservice.BrailleDisplayController.BrailleDisplayCallback.FLAG_ERROR_CANNOT_ACCESS;

import android.accessibilityservice.BrailleDisplayController;
import android.accessibilityservice.IBrailleDisplayConnection;
import android.accessibilityservice.IBrailleDisplayController;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresNoPermission;
import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * This class represents the connection between {@code system_server} and a connected
 * Braille Display using the Braille Display HID standard (usage page 0x41).
 */
class BrailleDisplayConnection extends IBrailleDisplayConnection.Stub {
    private static final String LOG_TAG = "BrailleDisplayConnection";

    /**
     * Represents the connection type of a Braille display.
     *
     * <p>The integer values must match the kernel's bus type values because this bus type is
     * used to locate the correct HIDRAW node using data from the kernel. These values come
     * from the UAPI header file bionic/libc/kernel/uapi/linux/input.h, which is guaranteed
     * to stay constant.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"BUS_"}, value = {
            BUS_UNKNOWN,
            BUS_USB,
            BUS_BLUETOOTH
    })
    @interface BusType {
    }
    static final int BUS_UNKNOWN = -1;
    static final int BUS_USB = 0x03;
    static final int BUS_BLUETOOTH = 0x05;

    // Access to this static object must be guarded by a lock that is shared for all instances
    // of this class: the singular Accessibility system_server lock (mLock).
    private static final Set<File> sConnectedNodes = new ArraySet<>();

    // Used to guard to AIDL methods from concurrent calls.
    // Lock must match the one used by AccessibilityServiceConnection, which itself
    // comes from AccessibilityManagerService.
    private final Object mLock;
    private final AccessibilityServiceConnection mServiceConnection;


    private File mHidrawNode;
    private IBrailleDisplayController mController;

    private Thread mInputThread;
    private OutputStream mOutputStream;
    private HandlerThread mOutputThread;

    // mScanner is not final because tests may modify this to use a test-only scanner.
    private BrailleDisplayScanner mScanner;

    BrailleDisplayConnection(@NonNull Object lock,
            @NonNull AccessibilityServiceConnection serviceConnection) {
        this.mLock = Objects.requireNonNull(lock);
        this.mScanner = getDefaultNativeScanner(new DefaultNativeInterface());
        this.mServiceConnection = Objects.requireNonNull(serviceConnection);
    }

    /**
     * Used for `cmd accessibility` to check hidraw access.
     */
    static BrailleDisplayScanner createScannerForShell() {
        return getDefaultNativeScanner(new DefaultNativeInterface());
    }

    /**
     * Interface to scan for properties of connected Braille displays.
     *
     * <p>Helps simplify testing Braille Display APIs using test data without requiring
     * a real Braille display to be connected to the device, by using a test implementation
     * of this interface.
     *
     * @see #getDefaultNativeScanner
     * @see #setTestData
     */
    interface BrailleDisplayScanner {
        Collection<Path> getHidrawNodePaths(@NonNull Path directory);

        byte[] getDeviceReportDescriptor(@NonNull Path path);

        String getUniqueId(@NonNull Path path);

        @BusType
        int getDeviceBusType(@NonNull Path path);

        String getName(@NonNull Path path);
    }

    /**
     * Finds the Braille display HIDRAW node associated with the provided unique ID.
     *
     * <p>If found, saves instance state for this connection and starts a thread to
     * read from the Braille display.
     *
     * @param expectedUniqueId The expected unique ID of the device to connect, from
     *                         {@link UsbDevice#getSerialNumber()} or
     *                         {@link BluetoothDevice#getAddress()}.
     * @param expectedName     The expected name of the device to connect, from
     *                         {@link BluetoothDevice#getName()} or
     *                         {@link UsbDevice#getProductName()}.
     * @param expectedBusType  The expected bus type from {@link BusType}.
     * @param controller       Interface containing oneway callbacks used to communicate with the
     *                         {@link android.accessibilityservice.BrailleDisplayController}.
     */
    void connectLocked(
            @NonNull String expectedUniqueId,
            @Nullable String expectedName,
            @BusType int expectedBusType,
            @NonNull IBrailleDisplayController controller) {
        Objects.requireNonNull(expectedUniqueId);
        this.mController = Objects.requireNonNull(controller);

        final Path devicePath = Path.of("/dev");
        final List<Pair<File, byte[]>> result = new ArrayList<>();
        final Collection<Path> hidrawNodePaths = mScanner.getHidrawNodePaths(devicePath);
        if (hidrawNodePaths == null) {
            Slog.w(LOG_TAG, "Unable to access the HIDRAW node directory");
            sendConnectionErrorLocked(FLAG_ERROR_CANNOT_ACCESS);
            return;
        }
        boolean unableToGetDescriptor = false;
        // For every present HIDRAW device node:
        for (Path path : hidrawNodePaths) {
            final byte[] descriptor = mScanner.getDeviceReportDescriptor(path);
            if (descriptor == null) {
                unableToGetDescriptor = true;
                continue;
            }
            final boolean matchesIdentifier;
            final String uniqueId = mScanner.getUniqueId(path);
            if (uniqueId != null) {
                matchesIdentifier = expectedUniqueId.equalsIgnoreCase(uniqueId);
            } else {
                // HIDIOCGRAWUNIQ was added in kernel version 5.7.
                // If the device has an older kernel that does not support that ioctl then as a
                // fallback we can check against the device name (from HIDIOCGRAWNAME).
                final String name = mScanner.getName(path);
                matchesIdentifier = !TextUtils.isEmpty(expectedName) && expectedName.equals(name);
            }
            if (isBrailleDisplay(descriptor)
                    && mScanner.getDeviceBusType(path) == expectedBusType
                    && matchesIdentifier) {
                result.add(Pair.create(path.toFile(), descriptor));
            }
        }

        // Return success only when exactly one matching device node is found.
        if (result.size() != 1) {
            @BrailleDisplayController.BrailleDisplayCallback.ErrorCode int errorCode =
                    FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND;
            // If we were unable to get some /dev/hidraw* descriptor then tell the accessibility
            // service that the device may not have proper access to these device nodes.
            if (unableToGetDescriptor) {
                Slog.w(LOG_TAG, "Unable to access some HIDRAW node's descriptor");
                errorCode |= FLAG_ERROR_CANNOT_ACCESS;
            } else {
                Slog.w(LOG_TAG,
                        "Unable to find a unique Braille display matching the provided device");
            }
            sendConnectionErrorLocked(errorCode);
            return;
        }

        this.mHidrawNode = result.get(0).first;
        final byte[] reportDescriptor = result.get(0).second;

        // Only one connection instance should exist for this hidraw node, across
        // all currently running accessibility services.
        if (sConnectedNodes.contains(this.mHidrawNode)) {
            Slog.w(LOG_TAG,
                    "Unable to find an unused Braille display matching the provided device");
            sendConnectionErrorLocked(FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND);
            return;
        }
        sConnectedNodes.add(this.mHidrawNode);

        startReadingLocked();

        try {
            mServiceConnection.onBrailleDisplayConnectedLocked(this);
            mController.onConnected(this, reportDescriptor);
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Error calling onConnected", e);
            disconnect();
        }
    }

    private void sendConnectionErrorLocked(
            @BrailleDisplayController.BrailleDisplayCallback.ErrorCode int errorCode) {
        try {
            mController.onConnectionFailed(errorCode);
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Error calling onConnectionFailed", e);
        }
    }

    /** Returns true if this descriptor includes usages for the Braille display usage page 0x41. */
    @VisibleForTesting
    static boolean isBrailleDisplay(byte[] descriptor) {
        boolean foundMatch = false;
        for (int i = 0; i < descriptor.length; i++) {
            // HID Spec "6.2.2.2 Short Items" defines that the report descriptor is a collection of
            // items: each item is a collection of bytes where the first byte defines info about
            // the type of item and the following 0, 1, 2, or 4 bytes are data bytes for that item.
            // All items in the HID descriptor are expected to be Short Items.
            final byte itemInfo = descriptor[i];
            if (!isHidItemShort(itemInfo)) {
                Slog.w(LOG_TAG, "Item " + itemInfo + " declares unsupported long type");
                return false;
            }
            final int dataSize = getHidItemDataSize(itemInfo);
            if (i + dataSize >= descriptor.length) {
                Slog.w(LOG_TAG, "Item " + itemInfo + " specifies size past the remaining bytes");
                return false;
            }
            // The item we're looking for (usage page declaration) should have size 1.
            if (dataSize == 1) {
                final byte itemData = descriptor[i + 1];
                if (isHidItemBrailleDisplayUsagePage(itemInfo, itemData)) {
                    foundMatch = true;
                }
            }
            // Move to the next item by skipping past all data bytes in this item.
            i += dataSize;
        }
        return foundMatch;
    }

    private static boolean isHidItemShort(byte itemInfo) {
        // Info bits 7-4 describe the item type, and HID Spec "6.2.2.3 Long Items" says that long
        // items always have type bits 1111. Otherwise, the item is a short item.
        return (itemInfo & 0b1111_0000) != 0b1111_0000;
    }

    private static int getHidItemDataSize(byte itemInfo) {
        // HID Spec "6.2.2.2 Short Items" says that info bits 0-1 specify the optional data size:
        // 0, 1, 2, or 4 bytes.
        return switch (itemInfo & 0b0000_0011) {
            case 0b00 -> 0;
            case 0b01 -> 1;
            case 0b10 -> 2;
            default -> 4;
        };
    }

    private static boolean isHidItemBrailleDisplayUsagePage(byte itemInfo, byte itemData) {
        // From HID Spec "6.2.2.7 Global Items"
        final byte usagePageType = 0b0000_0100;
        // From HID Usage Tables version 1.2.
        final byte brailleDisplayUsagePage = 0x41;
        // HID Spec "6.2.2.2 Short Items" says item info bits 2-7 describe the type and
        // function of the item.
        final byte itemType = (byte) (itemInfo & 0b1111_1100);
        return itemType == usagePageType && itemData == brailleDisplayUsagePage;
    }

    /**
     * Checks that the AccessibilityService that owns this BrailleDisplayConnection
     * is still connected to the system.
     *
     * @throws IllegalStateException if not connected
     */
    private void assertServiceIsConnectedLocked() {
        if (!mServiceConnection.isConnectedLocked()) {
            throw new IllegalStateException("Accessibility service is not connected");
        }
    }

    /**
     * Disconnects from this Braille display. This object is no longer valid after
     * this call returns.
     */
    @Override
    // This is a cleanup method, so allow the call even if the calling service was disabled.
    @RequiresNoPermission
    public void disconnect() {
        synchronized (mLock) {
            closeInputLocked();
            closeOutputLocked();
            mServiceConnection.onBrailleDisplayDisconnectedLocked();
            try {
                mController.onDisconnected();
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Error calling onDisconnected");
            }
            sConnectedNodes.remove(this.mHidrawNode);
        }
    }

    /**
     * Writes the provided HID bytes to this Braille display.
     *
     * <p>Writes are posted to a background thread handler.
     *
     * @param buffer The bytes to write to the Braille display. These bytes should be formatted
     *               according to the report descriptor.
     */
    @Override
    @PermissionManuallyEnforced // by assertServiceIsConnectedLocked()
    public void write(@NonNull byte[] buffer) {
        Objects.requireNonNull(buffer);
        if (buffer.length > IBinder.getSuggestedMaxIpcSizeBytes()) {
            Slog.e(LOG_TAG, "Requested write of size " + buffer.length
                    + " which is larger than maximum " + IBinder.getSuggestedMaxIpcSizeBytes());
            // The caller only got here by bypassing the AccessibilityService-side check with
            // reflection, so disconnect this connection to prevent further attempts.
            disconnect();
            return;
        }
        synchronized (mLock) {
            assertServiceIsConnectedLocked();
            if (mOutputThread == null) {
                try {
                    mOutputStream = new FileOutputStream(mHidrawNode);
                } catch (Exception e) {
                    Slog.e(LOG_TAG, "Unable to create write stream", e);
                    disconnect();
                    return;
                }
                mOutputThread = new HandlerThread("BrailleDisplayConnection output thread",
                        Process.THREAD_PRIORITY_BACKGROUND);
                mOutputThread.setDaemon(true);
                mOutputThread.start();
            }
            // TODO: b/316035785 - Proactively disconnect a misbehaving Braille display by calling
            //  disconnect() if the mOutputThread handler queue grows too large.
            mOutputThread.getThreadHandler().post(() -> {
                try {
                    mOutputStream.write(buffer);
                } catch (IOException e) {
                    Slog.d(LOG_TAG, "Error writing to connected Braille display", e);
                    disconnect();
                }
            });
        }
    }

    /**
     * Starts reading HID bytes from this Braille display.
     *
     * <p>Reads are performed on a background thread.
     */
    private void startReadingLocked() {
        mInputThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try (InputStream inputStream = new FileInputStream(mHidrawNode)) {
                final byte[] buffer = new byte[IBinder.getSuggestedMaxIpcSizeBytes()];
                int readSize;
                while (!Thread.interrupted()) {
                    if (!mHidrawNode.exists()) {
                        disconnect();
                        break;
                    }
                    // Reading from the HIDRAW character device node will block
                    // until bytes are available.
                    readSize = inputStream.read(buffer);
                    if (readSize > 0) {
                        try {
                            // Send the input to the AccessibilityService.
                            mController.onInput(Arrays.copyOfRange(buffer, 0, readSize));
                        } catch (RemoteException e) {
                            // Error communicating with the AccessibilityService.
                            Slog.e(LOG_TAG, "Error calling onInput", e);
                            disconnect();
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                Slog.d(LOG_TAG, "Error reading from connected Braille display", e);
                disconnect();
            }
        }, "BrailleDisplayConnection input thread");
        mInputThread.setDaemon(true);
        mInputThread.start();
    }

    /** Stop the Input thread. */
    private void closeInputLocked() {
        if (mInputThread != null) {
            mInputThread.interrupt();
        }
        mInputThread = null;
    }

    /** Stop the Output thread and close the Output stream. */
    private void closeOutputLocked() {
        if (mOutputThread != null) {
            mOutputThread.quit();
        }
        mOutputThread = null;
        if (mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Unable to close output stream", e);
            }
        }
        mOutputStream = null;
    }

    /**
     * Returns a {@link BrailleDisplayScanner} that opens {@link FileInputStream}s to read
     * from HIDRAW nodes and perform ioctls using the provided {@link NativeInterface}.
     */
    @VisibleForTesting
    static BrailleDisplayScanner getDefaultNativeScanner(@NonNull NativeInterface nativeInterface) {
        Objects.requireNonNull(nativeInterface);
        return new BrailleDisplayScanner() {
            private static final String HIDRAW_DEVICE_GLOB = "hidraw*";

            @Override
            public Collection<Path> getHidrawNodePaths(@NonNull Path directory) {
                final List<Path> result = new ArrayList<>();
                try (DirectoryStream<Path> hidrawNodePaths = Files.newDirectoryStream(
                        directory, HIDRAW_DEVICE_GLOB)) {
                    for (Path path : hidrawNodePaths) {
                        result.add(path);
                    }
                    return result;
                } catch (IOException e) {
                    return null;
                }
            }

            private <T> T readFromFileDescriptor(Path path, Function<Integer, T> readFn) {
                try (FileInputStream stream = new FileInputStream(path.toFile())) {
                    return readFn.apply(stream.getFD().getInt$());
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public byte[] getDeviceReportDescriptor(@NonNull Path path) {
                Objects.requireNonNull(path);
                return readFromFileDescriptor(path, fd -> {
                    final int descSize = nativeInterface.getHidrawDescSize(fd);
                    if (descSize > 0) {
                        return nativeInterface.getHidrawDesc(fd, descSize);
                    }
                    return null;
                });
            }

            @Override
            public String getUniqueId(@NonNull Path path) {
                Objects.requireNonNull(path);
                return readFromFileDescriptor(path, nativeInterface::getHidrawUniq);
            }

            @Override
            public int getDeviceBusType(@NonNull Path path) {
                Objects.requireNonNull(path);
                Integer busType = readFromFileDescriptor(path, nativeInterface::getHidrawBusType);
                return busType != null ? busType : BUS_UNKNOWN;
            }

            @Override
            public String getName(@NonNull Path path) {
                Objects.requireNonNull(path);
                return readFromFileDescriptor(path, nativeInterface::getHidrawName);
            }
        };
    }

    /**
     * Sets test data to be used by CTS tests.
     *
     * <p>Replaces the default {@link BrailleDisplayScanner} object for this connection,
     * and also returns it to allow unit testing this test-only implementation.
     *
     * @see BrailleDisplayController#setTestBrailleDisplayData
     */
    BrailleDisplayScanner setTestData(@NonNull List<Bundle> brailleDisplays) {
        Objects.requireNonNull(brailleDisplays);
        final Map<Path, Bundle> brailleDisplayMap = new ArrayMap<>();
        for (Bundle brailleDisplay : brailleDisplays) {
            Path hidrawNodePath = Path.of(brailleDisplay.getString(
                    BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH));
            brailleDisplayMap.put(hidrawNodePath, brailleDisplay);
        }
        synchronized (mLock) {
            mScanner = new BrailleDisplayScanner() {
                @Override
                public Collection<Path> getHidrawNodePaths(@NonNull Path directory) {
                    return brailleDisplayMap.isEmpty() ? null : brailleDisplayMap.keySet();
                }

                @Override
                public byte[] getDeviceReportDescriptor(@NonNull Path path) {
                    return brailleDisplayMap.get(path).getByteArray(
                            BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR);
                }

                @Override
                public String getUniqueId(@NonNull Path path) {
                    return brailleDisplayMap.get(path).getString(
                            BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID);
                }

                @Override
                public int getDeviceBusType(@NonNull Path path) {
                    return brailleDisplayMap.get(path).getBoolean(
                            BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH)
                            ? BUS_BLUETOOTH : BUS_USB;
                }

                @Override
                public String getName(@NonNull Path path) {
                    return brailleDisplayMap.get(path).getString(
                            BrailleDisplayController.TEST_BRAILLE_DISPLAY_NAME);
                }
            };
            return mScanner;
        }
    }

    /**
     * This interface exists to support unit testing {@link #getDefaultNativeScanner}.
     */
    @VisibleForTesting
    interface NativeInterface {
        /**
         * Returns the HIDRAW descriptor size for the file descriptor.
         *
         * @return the result of ioctl(HIDIOCGRDESCSIZE), or -1 if the ioctl fails.
         */
        int getHidrawDescSize(int fd);

        /**
         * Returns the HIDRAW descriptor for the file descriptor.
         *
         * @return the result of ioctl(HIDIOCGRDESC), or null if the ioctl fails.
         */
        byte[] getHidrawDesc(int fd, int descSize);

        /**
         * Returns the HIDRAW unique identifier for the file descriptor.
         *
         * @return the result of ioctl(HIDIOCGRAWUNIQ), or null if the ioctl fails.
         */
        String getHidrawUniq(int fd);

        /**
         * Returns the HIDRAW bus type for the file descriptor.
         *
         * @return the result of ioctl(HIDIOCGRAWINFO).bustype, or -1 if the ioctl fails.
         */
        int getHidrawBusType(int fd);

        String getHidrawName(int fd);
    }

    /** Native interface that actually calls native HIDRAW ioctls. */
    private static class DefaultNativeInterface implements NativeInterface {
        @Override
        public int getHidrawDescSize(int fd) {
            return nativeGetHidrawDescSize(fd);
        }

        @Override
        public byte[] getHidrawDesc(int fd, int descSize) {
            return nativeGetHidrawDesc(fd, descSize);
        }

        @Override
        public String getHidrawUniq(int fd) {
            return nativeGetHidrawUniq(fd);
        }

        @Override
        public int getHidrawBusType(int fd) {
            return nativeGetHidrawBusType(fd);
        }

        @Override
        public String getHidrawName(int fd) {
            return nativeGetHidrawName(fd);
        }
    }

    private static native int nativeGetHidrawDescSize(int fd);

    private static native byte[] nativeGetHidrawDesc(int fd, int descSize);

    private static native String nativeGetHidrawUniq(int fd);

    private static native int nativeGetHidrawBusType(int fd);

    private static native String nativeGetHidrawName(int fd);
}
