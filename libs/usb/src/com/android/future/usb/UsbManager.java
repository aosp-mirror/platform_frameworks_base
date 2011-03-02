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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.future.usb;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.IUsbManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * This class allows you to access the state of USB, both in host and device mode.
 *
 * <p>You can obtain an instance of this class by calling {@link #getInstance}
 *
 */
public class UsbManager {
    private static final String TAG = "UsbManager";

   /**
     * Broadcast Action:  A broadcast for USB accessory attached event.
     *
     * This intent is sent when a USB accessory is attached.
     * Call {@link #getAccessory(android.content.Intent)} to retrieve the
     * {@link com.google.android.usb.UsbAccessory} for the attached accessory.
     */
    public static final String ACTION_USB_ACCESSORY_ATTACHED =
            "android.hardware.usb.action.USB_ACCESSORY_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for USB accessory detached event.
     *
     * This intent is sent when a USB accessory is detached.
     * Call {@link #getAccessory(android.content.Intent)} to retrieve the
     * {@link com.google.android.usb.UsbAccessory} for the attached accessory that was detached.
     */
    public static final String ACTION_USB_ACCESSORY_DETACHED =
            "android.hardware.usb.action.USB_ACCESSORY_DETACHED";

    private final IUsbManager mService;

    private UsbManager(IUsbManager service) {
        mService = service;
    }

    /**
     * Returns a new instance of this class.
     *
     * @return UsbManager instance.
     */
    public static UsbManager getInstance() {
        IBinder b = ServiceManager.getService(Context.USB_SERVICE);
        return new UsbManager(IUsbManager.Stub.asInterface(b));
    }

    /**
     * Returns the {@link com.google.android.usb.UsbAccessory} for
     * a {@link #ACTION_USB_ACCESSORY_ATTACHED} or {@link #ACTION_USB_ACCESSORY_ATTACHED}
     * broadcast Intent
     *
     * @return UsbAccessory for the broadcast.
     */
    public static UsbAccessory getAccessory(Intent intent) {
        android.hardware.usb.UsbAccessory accessory =
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_ACCESSORY);
        if (accessory == null) {
            return null;
        } else {
            return new UsbAccessory(accessory);
        }
    }

    /**
     * Returns a list of currently attached USB accessories.
     * (in the current implementation there can be at most one)
     *
     * @return list of USB accessories, or null if none are attached.
     */
    public UsbAccessory[] getAccessoryList() {
        try {
            android.hardware.usb.UsbAccessory accessory = mService.getCurrentAccessory();
            if (accessory == null) {
                return null;
            } else {
                return new UsbAccessory[] { new UsbAccessory(accessory) };
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getAccessoryList" , e);
            return null;
        }
    }

    /**
     * Opens a file descriptor for reading and writing data to the USB accessory.
     *
     * @param accessory the USB accessory to open
     * @return file descriptor, or null if the accessor could not be opened.
     */
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        try {
            return mService.openAccessory(new android.hardware.usb.UsbAccessory(
                    accessory.getManufacturer(),accessory.getModel(),
                    accessory.getType(), accessory.getVersion()));
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openAccessory" , e);
            return null;
        }
    }
}
