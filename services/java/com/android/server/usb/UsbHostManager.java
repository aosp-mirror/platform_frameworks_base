/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

/**
 * UsbHostManager manages USB state in host mode.
 */
public class UsbHostManager {
    private static final String TAG = UsbHostManager.class.getSimpleName();
    private static final boolean LOG = false;

    // contains all connected USB devices
    private final HashMap<String, UsbDevice> mDevices = new HashMap<String, UsbDevice>();

    // USB busses to exclude from USB host support
    private final String[] mHostBlacklist;

    private final Context mContext;
    private final Object mLock = new Object();

    // @GuardedBy("mLock")
    private UsbSettingsManager mCurrentSettings;

    public UsbHostManager(Context context) {
        mContext = context;
        mHostBlacklist = context.getResources().getStringArray(
                com.android.internal.R.array.config_usbHostBlacklist);
    }

    public void setCurrentSettings(UsbSettingsManager settings) {
        synchronized (mLock) {
            mCurrentSettings = settings;
        }
    }

    private UsbSettingsManager getCurrentSettings() {
        synchronized (mLock) {
            return mCurrentSettings;
        }
    }

    private boolean isBlackListed(String deviceName) {
        int count = mHostBlacklist.length;
        for (int i = 0; i < count; i++) {
            if (deviceName.startsWith(mHostBlacklist[i])) {
                return true;
            }
        }
        return false;
    }

    /* returns true if the USB device should not be accessible by applications */
    private boolean isBlackListed(int clazz, int subClass, int protocol) {
        // blacklist hubs
        if (clazz == UsbConstants.USB_CLASS_HUB) return true;

        // blacklist HID boot devices (mouse and keyboard)
        if (clazz == UsbConstants.USB_CLASS_HID &&
                subClass == UsbConstants.USB_INTERFACE_SUBCLASS_BOOT) {
            return true;
        }

        return false;
    }

    /* Called from JNI in monitorUsbHostBus() to report new USB devices */
    private void usbDeviceAdded(String deviceName, int vendorID, int productID,
            int deviceClass, int deviceSubclass, int deviceProtocol,
            /* array of quintuples containing id, class, subclass, protocol
               and number of endpoints for each interface */
            int[] interfaceValues,
           /* array of quadruples containing address, attributes, max packet size
              and interval for each endpoint */
            int[] endpointValues) {

        if (isBlackListed(deviceName) ||
                isBlackListed(deviceClass, deviceSubclass, deviceProtocol)) {
            return;
        }

        synchronized (mLock) {
            if (mDevices.get(deviceName) != null) {
                Slog.w(TAG, "device already on mDevices list: " + deviceName);
                return;
            }

            int numInterfaces = interfaceValues.length / 5;
            Parcelable[] interfaces = new UsbInterface[numInterfaces];
            try {
                // repackage interfaceValues as an array of UsbInterface
                int intf, endp, ival = 0, eval = 0;
                for (intf = 0; intf < numInterfaces; intf++) {
                    int interfaceId = interfaceValues[ival++];
                    int interfaceClass = interfaceValues[ival++];
                    int interfaceSubclass = interfaceValues[ival++];
                    int interfaceProtocol = interfaceValues[ival++];
                    int numEndpoints = interfaceValues[ival++];

                    Parcelable[] endpoints = new UsbEndpoint[numEndpoints];
                    for (endp = 0; endp < numEndpoints; endp++) {
                        int address = endpointValues[eval++];
                        int attributes = endpointValues[eval++];
                        int maxPacketSize = endpointValues[eval++];
                        int interval = endpointValues[eval++];
                        endpoints[endp] = new UsbEndpoint(address, attributes,
                                maxPacketSize, interval);
                    }

                    // don't allow if any interfaces are blacklisted
                    if (isBlackListed(interfaceClass, interfaceSubclass, interfaceProtocol)) {
                        return;
                    }
                    interfaces[intf] = new UsbInterface(interfaceId, interfaceClass,
                            interfaceSubclass, interfaceProtocol, endpoints);
                }
            } catch (Exception e) {
                // beware of index out of bound exceptions, which might happen if
                // a device does not set bNumEndpoints correctly
                Slog.e(TAG, "error parsing USB descriptors", e);
                return;
            }

            UsbDevice device = new UsbDevice(deviceName, vendorID, productID,
                    deviceClass, deviceSubclass, deviceProtocol, interfaces);
            mDevices.put(deviceName, device);
            getCurrentSettings().deviceAttached(device);
        }
    }

    /* Called from JNI in monitorUsbHostBus to report USB device removal */
    private void usbDeviceRemoved(String deviceName) {
        synchronized (mLock) {
            UsbDevice device = mDevices.remove(deviceName);
            if (device != null) {
                getCurrentSettings().deviceDetached(device);
            }
        }
    }

    public void systemReady() {
        synchronized (mLock) {
            // Create a thread to call into native code to wait for USB host events.
            // This thread will call us back on usbDeviceAdded and usbDeviceRemoved.
            Runnable runnable = new Runnable() {
                public void run() {
                    monitorUsbHostBus();
                }
            };
            new Thread(null, runnable, "UsbService host thread").start();
        }
    }

    /* Returns a list of all currently attached USB devices */
    public void getDeviceList(Bundle devices) {
        synchronized (mLock) {
            for (String name : mDevices.keySet()) {
                devices.putParcelable(name, mDevices.get(name));
            }
        }
    }

    /* Opens the specified USB device */
    public ParcelFileDescriptor openDevice(String deviceName) {
        synchronized (mLock) {
            if (isBlackListed(deviceName)) {
                throw new SecurityException("USB device is on a restricted bus");
            }
            UsbDevice device = mDevices.get(deviceName);
            if (device == null) {
                // if it is not in mDevices, it either does not exist or is blacklisted
                throw new IllegalArgumentException(
                        "device " + deviceName + " does not exist or is restricted");
            }
            getCurrentSettings().checkPermission(device);
            return nativeOpenDevice(deviceName);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  USB Host State:");
            for (String name : mDevices.keySet()) {
                pw.println("    " + name + ": " + mDevices.get(name));
            }
        }
    }

    private native void monitorUsbHostBus();
    private native ParcelFileDescriptor nativeOpenDevice(String deviceName);
}
