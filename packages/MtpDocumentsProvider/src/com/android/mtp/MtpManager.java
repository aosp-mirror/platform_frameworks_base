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

package com.android.mtp;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.mtp.MtpConstants;
import android.mtp.MtpDevice;
import android.mtp.MtpDeviceInfo;
import android.mtp.MtpEvent;
import android.mtp.MtpObjectInfo;
import android.mtp.MtpStorageInfo;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/**
 * The model wrapping android.mtp API.
 */
class MtpManager {
    final static int OBJECT_HANDLE_ROOT_CHILDREN = -1;

    /**
     * Subclass for PTP.
     */
    private static final int SUBCLASS_STILL_IMAGE_CAPTURE = 1;

    /**
     * Subclass for Android style MTP.
     */
    private static final int SUBCLASS_MTP = 0xff;

    /**
     * Protocol for Picture Transfer Protocol (PIMA 15470).
     */
    private static final int PROTOCOL_PICTURE_TRANSFER = 1;

    /**
     * Protocol for Android style MTP.
     */
    private static final int PROTOCOL_MTP = 0;

    private final UsbManager mManager;
    private final SparseArray<MtpDevice> mDevices = new SparseArray<>();

    MtpManager(Context context) {
        mManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    synchronized MtpDeviceRecord openDevice(int deviceId) throws IOException {
        UsbDevice rawDevice = null;
        for (final UsbDevice candidate : mManager.getDeviceList().values()) {
            if (candidate.getDeviceId() == deviceId) {
                rawDevice = candidate;
                break;
            }
        }

        ensureNotNull(rawDevice, "Not found USB device: " + deviceId);

        if (!mManager.hasPermission(rawDevice)) {
            mManager.grantPermission(rawDevice);
            if (!mManager.hasPermission(rawDevice)) {
                throw new IOException("Failed to grant a device permission.");
            }
        }

        final MtpDevice device = new MtpDevice(rawDevice);

        final UsbDeviceConnection connection = ensureNotNull(
                mManager.openDevice(rawDevice),
                "Failed to open a USB connection.");

        if (!device.open(connection)) {
            // We cannot open connection when another application use the device.
            throw new BusyDeviceException();
        }

        // Handle devices that fail to obtain storages just after opening a MTP session.
        final int[] storageIds = ensureNotNull(
                device.getStorageIds(),
                "Not found MTP storages in the device.");

        mDevices.put(deviceId, device);
        return createDeviceRecord(rawDevice);
    }

    synchronized void closeDevice(int deviceId) throws IOException {
        getDevice(deviceId).close();
        mDevices.remove(deviceId);
    }

    synchronized MtpDeviceRecord[] getDevices() {
        final ArrayList<MtpDeviceRecord> devices = new ArrayList<>();
        for (UsbDevice device : mManager.getDeviceList().values()) {
            if (!isMtpDevice(device)) {
                continue;
            }
            devices.add(createDeviceRecord(device));
        }
        return devices.toArray(new MtpDeviceRecord[devices.size()]);
    }

    MtpObjectInfo getObjectInfo(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return ensureNotNull(
                    device.getObjectInfo(objectHandle),
                    "Failed to get object info: " + objectHandle);
        }
    }

    int[] getObjectHandles(int deviceId, int storageId, int parentObjectHandle)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return ensureNotNull(
                    device.getObjectHandles(storageId, 0 /* all format */, parentObjectHandle),
                    "Failed to fetch object handles.");
        }
    }

    byte[] getObject(int deviceId, int objectHandle, int expectedSize)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return ensureNotNull(
                    device.getObject(objectHandle, expectedSize),
                    "Failed to fetch object bytes");
        }
    }

    long getPartialObject(int deviceId, int objectHandle, long offset, long size, byte[] buffer)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return device.getPartialObject(objectHandle, offset, size, buffer);
        }
    }

    long getPartialObject64(int deviceId, int objectHandle, long offset, long size, byte[] buffer)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return device.getPartialObject64(objectHandle, offset, size, buffer);
        }
    }

    byte[] getThumbnail(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            return ensureNotNull(
                    device.getThumbnail(objectHandle),
                    "Failed to obtain thumbnail bytes");
        }
    }

    void deleteDocument(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            if (!device.deleteObject(objectHandle)) {
                throw new IOException("Failed to delete document");
            }
        }
    }

    int createDocument(int deviceId, MtpObjectInfo objectInfo,
            ParcelFileDescriptor source) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            final MtpObjectInfo sendObjectInfoResult = device.sendObjectInfo(objectInfo);
            if (sendObjectInfoResult == null) {
                throw new SendObjectInfoFailure();
            }
            if (objectInfo.getFormat() != MtpConstants.FORMAT_ASSOCIATION) {
                if (!device.sendObject(sendObjectInfoResult.getObjectHandle(),
                        sendObjectInfoResult.getCompressedSize(), source)) {
                    throw new IOException("Failed to send contents of a document");
                }
            }
            return sendObjectInfoResult.getObjectHandle();
        }
    }

    int getParent(int deviceId, int objectHandle) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            final int result = (int) device.getParent(objectHandle);
            if (result == 0xffffffff) {
                throw new FileNotFoundException("Not found parent object");
            }
            return result;
        }
    }

    void importFile(int deviceId, int objectHandle, ParcelFileDescriptor target)
            throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            if (!device.importFile(objectHandle, target)) {
                throw new IOException("Failed to import file to FD");
            }
        }
    }

    @VisibleForTesting
    MtpEvent readEvent(int deviceId, CancellationSignal signal) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        return device.readEvent(signal);
    }

    long getObjectSizeLong(int deviceId, int objectHandle, int format) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        return device.getObjectSizeLong(objectHandle, format);
    }

    private synchronized MtpDevice getDevice(int deviceId) throws IOException {
        return ensureNotNull(
                mDevices.get(deviceId),
                "USB device " + deviceId + " is not opened.");
    }

    private MtpRoot[] getRoots(int deviceId) throws IOException {
        final MtpDevice device = getDevice(deviceId);
        synchronized (device) {
            final int[] storageIds =
                    ensureNotNull(device.getStorageIds(), "Failed to obtain storage IDs.");
            final ArrayList<MtpRoot> roots = new ArrayList<>();
            for (int i = 0; i < storageIds.length; i++) {
                final MtpStorageInfo info = device.getStorageInfo(storageIds[i]);
                if (info == null) {
                    continue;
                }
                roots.add(new MtpRoot(device.getDeviceId(), info));
            }
            return roots.toArray(new MtpRoot[roots.size()]);
        }
    }

    private MtpDeviceRecord createDeviceRecord(UsbDevice device) {
        final MtpDevice mtpDevice = mDevices.get(device.getDeviceId());
        final boolean opened = mtpDevice != null;
        final String name = device.getProductName();
        MtpRoot[] roots;
        int[] operationsSupported = null;
        int[] eventsSupported = null;
        if (opened) {
            try {
                roots = getRoots(device.getDeviceId());
            } catch (IOException exp) {
                Log.e(MtpDocumentsProvider.TAG, "Failed to open device", exp);
                // If we failed to fetch roots for the device, we still returns device model
                // with an empty set of roots so that the device is shown DocumentsUI as long as
                // the device is physically connected.
                roots = new MtpRoot[0];
            }
            final MtpDeviceInfo info = mtpDevice.getDeviceInfo();
            if (info != null) {
                operationsSupported = mtpDevice.getDeviceInfo().getOperationsSupported();
                eventsSupported = mtpDevice.getDeviceInfo().getEventsSupported();
            }
        } else {
            roots = new MtpRoot[0];
        }
        return new MtpDeviceRecord(
                device.getDeviceId(), name, device.getSerialNumber(), opened, roots,
                operationsSupported, eventsSupported);
    }

    static boolean isMtpDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            final UsbInterface usbInterface = device.getInterface(i);
            if ((usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    usbInterface.getInterfaceSubclass() == SUBCLASS_STILL_IMAGE_CAPTURE &&
                    usbInterface.getInterfaceProtocol() == PROTOCOL_PICTURE_TRANSFER)) {
                return true;
            }
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_SUBCLASS_VENDOR_SPEC &&
                    usbInterface.getInterfaceSubclass() == SUBCLASS_MTP &&
                    usbInterface.getInterfaceProtocol() == PROTOCOL_MTP &&
                    "MTP".equals(usbInterface.getName())) {
                return true;
            }
        }
        return false;
    }

    private static <T> T ensureNotNull(@Nullable T t, String errorMessage) throws IOException {
        if (t != null) {
            return t;
        } else {
            throw new IOException(errorMessage);
        }
    }
}
