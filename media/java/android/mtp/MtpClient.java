/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.mtp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.UsbConstants;
import android.hardware.UsbDevice;
import android.hardware.UsbInterface;
import android.hardware.UsbManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class helps an application manage a list of connected MTP devices.
 * It listens for MTP devices being attached and removed from the USB host bus
 * and notifies the application when the MTP device list changes.
 * {@hide}
 */
public class MtpClient {

    private static final String TAG = "MtpClient";

    private final Context mContext;
    private final UsbManager mUsbManager;
    private final ArrayList<Listener> mListeners = new ArrayList<Listener>();
    private final ArrayList<MtpDevice> mDeviceList = new ArrayList<MtpDevice>();

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String deviceName = intent.getStringExtra(UsbManager.EXTRA_DEVICE_NAME);

            synchronized (mDeviceList) {
                MtpDevice mtpDevice = getDeviceLocked(deviceName);

                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                    if (mtpDevice == null) {
                        UsbDevice usbDevice =
                                (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        mtpDevice = openDevice(usbDevice);
                    }
                    if (mtpDevice != null) {
                        mDeviceList.add(mtpDevice);
                        for (Listener listener : mListeners) {
                            listener.deviceAdded(mtpDevice);
                        }
                    }
                } else if (mtpDevice != null) {
                    mDeviceList.remove(mtpDevice);
                    for (Listener listener : mListeners) {
                        listener.deviceRemoved(mtpDevice);
                    }
                }
            }
        }
    };

    public interface Listener {
        public void deviceAdded(MtpDevice device);
        public void deviceRemoved(MtpDevice device);
    }

   static public boolean isCamera(UsbDevice device) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.getInterfaceSubclass() == 1 &&
                    intf.getInterfaceProtocol() == 1) {
                return true;
            }
        }
        return false;
    }

    private MtpDevice openDevice(UsbDevice usbDevice) {
        if (isCamera(usbDevice)) {
            MtpDevice mtpDevice = new MtpDevice(usbDevice);
            if (mtpDevice.open(mUsbManager)) {
                return mtpDevice;
            }
        }
        return null;
    }

    public MtpClient(Context context) {
        mContext = context;
        mUsbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(mUsbReceiver, filter);

        for (UsbDevice usbDevice : mUsbManager.getDeviceList().values()) {
            MtpDevice mtpDevice = getDeviceLocked(usbDevice.getDeviceName());
            if (mtpDevice == null) {
                mtpDevice = openDevice(usbDevice);
            }
            if (mtpDevice != null) {
                mDeviceList.add(mtpDevice);
            }
        }
    }

    public void close() {
        mContext.unregisterReceiver(mUsbReceiver);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    public void addListener(Listener listener) {
        synchronized (mDeviceList) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (mDeviceList) {
            mListeners.remove(listener);
        }
    }

    public MtpDevice getDevice(String deviceName) {
        synchronized (mDeviceList) {
            return getDeviceLocked(deviceName);
        }
    }

    public MtpDevice getDevice(int id) {
        synchronized (mDeviceList) {
            return getDeviceLocked(UsbDevice.getDeviceName(id));
        }
    }

    private MtpDevice getDeviceLocked(String deviceName) {
        for (MtpDevice device : mDeviceList) {
            if (device.getDeviceName().equals(deviceName)) {
                return device;
            }
        }
        return null;
    }

    public List<MtpDevice> getDeviceList() {
        synchronized (mDeviceList) {
            return new ArrayList<MtpDevice>(mDeviceList);
        }
    }

    public List<MtpStorageInfo> getStorageList(String deviceName) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return null;
        }
        int[] storageIds = device.getStorageIds();
        if (storageIds == null) {
            return null;
        }

        int length = storageIds.length;
        ArrayList<MtpStorageInfo> storageList = new ArrayList<MtpStorageInfo>(length);
        for (int i = 0; i < length; i++) {
            MtpStorageInfo info = device.getStorageInfo(storageIds[i]);
            if (info == null) {
                Log.w(TAG, "getStorageInfo failed");
            } else {
                storageList.add(info);
            }
        }
        return storageList;
    }

    public MtpObjectInfo getObjectInfo(String deviceName, int objectHandle) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return null;
        }
        return device.getObjectInfo(objectHandle);
    }

    public boolean deleteObject(String deviceName, int objectHandle) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return false;
        }
        return device.deleteObject(objectHandle);
    }

    public List<MtpObjectInfo> getObjectList(String deviceName, int storageId, int objectHandle) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return null;
        }
        if (objectHandle == 0) {
            // all objects in root of storage
            objectHandle = 0xFFFFFFFF;
        }
        int[] handles = device.getObjectHandles(storageId, 0, objectHandle);
        if (handles == null) {
            return null;
        }

        int length = handles.length;
        ArrayList<MtpObjectInfo> objectList = new ArrayList<MtpObjectInfo>(length);
        for (int i = 0; i < length; i++) {
            MtpObjectInfo info = device.getObjectInfo(handles[i]);
            if (info == null) {
                Log.w(TAG, "getObjectInfo failed");
            } else {
                objectList.add(info);
            }
        }
        return objectList;
    }

    public byte[] getObject(String deviceName, int objectHandle, int objectSize) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return null;
        }
        return device.getObject(objectHandle, objectSize);
    }

    public byte[] getThumbnail(String deviceName, int objectHandle) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return null;
        }
        return device.getThumbnail(objectHandle);
    }

    public boolean importFile(String deviceName, int objectHandle, String destPath) {
        MtpDevice device = getDevice(deviceName);
        if (device == null) {
            return false;
        }
        return device.importFile(objectHandle, destPath);
    }
}
