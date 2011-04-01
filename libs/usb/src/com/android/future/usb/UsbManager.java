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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.IUsbManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * This is a wrapper class for the USB Manager to support USB accessories.
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

    /**
     * Name of extra added to the {@link android.app.PendingIntent}
     * passed into {#requestPermission} or {#requestPermission}
     * containing a boolean value indicating whether the user granted permission or not.
     */
    public static final String EXTRA_PERMISSION_GRANTED = "permission";

    private final Context mContext;
    private final IUsbManager mService;

    private UsbManager(Context context, IUsbManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns a new instance of this class.
     *
     * @param context the caller's {@link android.content.Context}
     * @return UsbManager instance.
     */
    public static UsbManager getInstance(Context context) {
        IBinder b = ServiceManager.getService(Context.USB_SERVICE);
        return new UsbManager(context, IUsbManager.Stub.asInterface(b));
    }

    /**
     * Returns the {@link com.google.android.usb.UsbAccessory} for
     * a {@link #ACTION_USB_ACCESSORY_ATTACHED} or {@link #ACTION_USB_ACCESSORY_ATTACHED}
     * broadcast Intent. This can also be used to retrieve the accessory from the result
     * of a call to {#requestPermission}.
     *
     * @return UsbAccessory for the intent.
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
                    accessory.getDescription(), accessory.getVersion(),
                    accessory.getUri(), accessory.getSerial()));
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openAccessory" , e);
            return null;
        }
    }

    /**
     * Returns true if the caller has permission to access the accessory.
     * Permission might have been granted temporarily via
     * {@link #requestPermission} or
     * by the user choosing the caller as the default application for the accessory.
     *
     * @param accessory to check permissions for
     * @return true if caller has permission
     */
    public boolean hasPermission(UsbAccessory accessory) {
        try {
            return mService.hasAccessoryPermission(new android.hardware.usb.UsbAccessory(
                    accessory.getManufacturer(),accessory.getModel(),
                    accessory.getDescription(), accessory.getVersion(),
                    accessory.getUri(), accessory.getSerial()));
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in hasPermission", e);
            return false;
        }
    }

    /**
     * Requests temporary permission for the given package to access the accessory.
     * This may result in a system dialog being displayed to the user
     * if permission had not already been granted.
     * Success or failure is returned via the {@link android.app.PendingIntent} pi.
     * The boolean extra {@link #EXTRA_PERMISSION_GRANTED} will be attached to the
     * PendingIntent to indicate success or failure.
     * If successful, this grants the caller permission to access the device only
     * until the device is disconnected.
     *
     * @param accessory to request permissions for
     * @param pi PendingIntent for returning result
     */
    public void requestPermission(UsbAccessory accessory, PendingIntent pi) {
        try {
            mService.requestAccessoryPermission(new android.hardware.usb.UsbAccessory(
                    accessory.getManufacturer(),accessory.getModel(),
                    accessory.getDescription(), accessory.getVersion(),
                    accessory.getUri(), accessory.getSerial()),
                    mContext.getPackageName(), pi);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in requestPermission", e);
        }
    }
}
