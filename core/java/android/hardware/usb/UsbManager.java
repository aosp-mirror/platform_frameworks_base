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


package android.hardware.usb;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.util.HashMap;

/**
 * This class allows you to access the state of USB and communicate with USB devices.
 * Currently only host mode is supported in the public API.
 *
 * <p>You can obtain an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
 *
 * {@samplecode
 * UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);}
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about communicating with USB hardware, read the
 * <a href="{@docRoot}guide/topics/usb/index.html">USB</a> developer guide.</p>
 * </div>
 */
public class UsbManager {
    private static final String TAG = "UsbManager";

   /**
     * Broadcast Action:  A sticky broadcast for USB state change events when in device mode.
     *
     * This is a sticky broadcast for clients that includes USB connected/disconnected state,
     * <ul>
     * <li> {@link #USB_CONNECTED} boolean indicating whether USB is connected or disconnected.
     * <li> {@link #USB_CONFIGURED} boolean indicating whether USB is configured.
     * currently zero if not configured, one for configured.
     * <li> {@link #USB_FUNCTION_MASS_STORAGE} boolean extra indicating whether the
     * mass storage function is enabled
     * <li> {@link #USB_FUNCTION_ADB} boolean extra indicating whether the
     * adb function is enabled
     * <li> {@link #USB_FUNCTION_RNDIS} boolean extra indicating whether the
     * RNDIS ethernet function is enabled
     * <li> {@link #USB_FUNCTION_MTP} boolean extra indicating whether the
     * MTP function is enabled
     * <li> {@link #USB_FUNCTION_PTP} boolean extra indicating whether the
     * PTP function is enabled
     * <li> {@link #USB_FUNCTION_PTP} boolean extra indicating whether the
     * accessory function is enabled
     * <li> {@link #USB_FUNCTION_AUDIO_SOURCE} boolean extra indicating whether the
     * audio source function is enabled
     * </ul>
     *
     * {@hide}
     */
    public static final String ACTION_USB_STATE =
            "android.hardware.usb.action.USB_STATE";

   /**
     * Broadcast Action:  A broadcast for USB device attached event.
     *
     * This intent is sent when a USB device is attached to the USB bus when in host mode.
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the {@link android.hardware.usb.UsbDevice}
     * for the attached device
     * </ul>
     */
    public static final String ACTION_USB_DEVICE_ATTACHED =
            "android.hardware.usb.action.USB_DEVICE_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for USB device detached event.
     *
     * This intent is sent when a USB device is detached from the USB bus when in host mode.
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the {@link android.hardware.usb.UsbDevice}
     * for the detached device
     * </ul>
     */
    public static final String ACTION_USB_DEVICE_DETACHED =
            "android.hardware.usb.action.USB_DEVICE_DETACHED";

   /**
     * Broadcast Action:  A broadcast for USB accessory attached event.
     *
     * This intent is sent when a USB accessory is attached.
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the {@link android.hardware.usb.UsbAccessory}
     * for the attached accessory
     * </ul>
     */
    public static final String ACTION_USB_ACCESSORY_ATTACHED =
            "android.hardware.usb.action.USB_ACCESSORY_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for USB accessory detached event.
     *
     * This intent is sent when a USB accessory is detached.
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the {@link UsbAccessory}
     * for the attached accessory that was detached
     * </ul>
     */
    public static final String ACTION_USB_ACCESSORY_DETACHED =
            "android.hardware.usb.action.USB_ACCESSORY_DETACHED";

    /**
     * Boolean extra indicating whether USB is connected or disconnected.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     *
     * {@hide}
     */
    public static final String USB_CONNECTED = "connected";

    /**
     * Boolean extra indicating whether USB is configured.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     *
     * {@hide}
     */
    public static final String USB_CONFIGURED = "configured";

    /**
     * Name of the USB mass storage USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_MASS_STORAGE = "mass_storage";

    /**
     * Name of the adb USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_ADB = "adb";

    /**
     * Name of the RNDIS ethernet USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_RNDIS = "rndis";

    /**
     * Name of the MTP USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_MTP = "mtp";

    /**
     * Name of the PTP USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_PTP = "ptp";

    /**
     * Name of the audio source USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_AUDIO_SOURCE = "audio_source";

    /**
     * Name of the Accessory USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * {@hide}
     */
    public static final String USB_FUNCTION_ACCESSORY = "accessory";

    /**
     * Name of extra for {@link #ACTION_USB_DEVICE_ATTACHED} and
     * {@link #ACTION_USB_DEVICE_DETACHED} broadcasts
     * containing the UsbDevice object for the device.
     */

    public static final String EXTRA_DEVICE = "device";

    /**
     * Name of extra for {@link #ACTION_USB_ACCESSORY_ATTACHED} and
     * {@link #ACTION_USB_ACCESSORY_DETACHED} broadcasts
     * containing the UsbAccessory object for the accessory.
     */
    public static final String EXTRA_ACCESSORY = "accessory";

    /**
     * Name of extra added to the {@link android.app.PendingIntent}
     * passed into {@link #requestPermission(UsbDevice, PendingIntent)}
     * or {@link #requestPermission(UsbAccessory, PendingIntent)}
     * containing a boolean value indicating whether the user granted permission or not.
     */
    public static final String EXTRA_PERMISSION_GRANTED = "permission";

    private final Context mContext;
    private final IUsbManager mService;

    /**
     * {@hide}
     */
    public UsbManager(Context context, IUsbManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns a HashMap containing all USB devices currently attached.
     * USB device name is the key for the returned HashMap.
     * The result will be empty if no devices are attached, or if
     * USB host mode is inactive or unsupported.
     *
     * @return HashMap containing all connected USB devices.
     */
    public HashMap<String,UsbDevice> getDeviceList() {
        Bundle bundle = new Bundle();
        try {
            mService.getDeviceList(bundle);
            HashMap<String,UsbDevice> result = new HashMap<String,UsbDevice>();
            for (String name : bundle.keySet()) {
                result.put(name, (UsbDevice)bundle.get(name));
            }
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDeviceList", e);
            return null;
        }
    }

    /**
     * Opens the device so it can be used to send and receive
     * data using {@link android.hardware.usb.UsbRequest}.
     *
     * @param device the device to open
     * @return a {@link UsbDeviceConnection}, or {@code null} if open failed
     */
    public UsbDeviceConnection openDevice(UsbDevice device) {
        try {
            String deviceName = device.getDeviceName();
            ParcelFileDescriptor pfd = mService.openDevice(deviceName);
            if (pfd != null) {
                UsbDeviceConnection connection = new UsbDeviceConnection(device);
                boolean result = connection.open(deviceName, pfd);
                pfd.close();
                if (result) {
                    return connection;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "exception in UsbManager.openDevice", e);
        }
        return null;
    }

    /**
     * Returns a list of currently attached USB accessories.
     * (in the current implementation there can be at most one)
     *
     * @return list of USB accessories, or null if none are attached.
     */
    public UsbAccessory[] getAccessoryList() {
        try {
            UsbAccessory accessory = mService.getCurrentAccessory();
            if (accessory == null) {
                return null;
            } else {
                return new UsbAccessory[] { accessory };
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getAccessoryList", e);
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
            return mService.openAccessory(accessory);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openAccessory", e);
            return null;
        }
    }

    /**
     * Returns true if the caller has permission to access the device.
     * Permission might have been granted temporarily via
     * {@link #requestPermission(UsbDevice, PendingIntent)} or
     * by the user choosing the caller as the default application for the device.
     *
     * @param device to check permissions for
     * @return true if caller has permission
     */
    public boolean hasPermission(UsbDevice device) {
        try {
            return mService.hasDevicePermission(device);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in hasPermission", e);
            return false;
        }
    }

    /**
     * Returns true if the caller has permission to access the accessory.
     * Permission might have been granted temporarily via
     * {@link #requestPermission(UsbAccessory, PendingIntent)} or
     * by the user choosing the caller as the default application for the accessory.
     *
     * @param accessory to check permissions for
     * @return true if caller has permission
     */
    public boolean hasPermission(UsbAccessory accessory) {
        try {
            return mService.hasAccessoryPermission(accessory);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in hasPermission", e);
            return false;
        }
    }

    /**
     * Requests temporary permission for the given package to access the device.
     * This may result in a system dialog being displayed to the user
     * if permission had not already been granted.
     * Success or failure is returned via the {@link android.app.PendingIntent} pi.
     * If successful, this grants the caller permission to access the device only
     * until the device is disconnected.
     *
     * The following extras will be added to pi:
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the device passed into this call
     * <li> {@link #EXTRA_PERMISSION_GRANTED} containing boolean indicating whether
     * permission was granted by the user
     * </ul>
     *
     * @param device to request permissions for
     * @param pi PendingIntent for returning result
     */
    public void requestPermission(UsbDevice device, PendingIntent pi) {
        try {
            mService.requestDevicePermission(device, mContext.getPackageName(), pi);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in requestPermission", e);
        }
    }

    /**
     * Requests temporary permission for the given package to access the accessory.
     * This may result in a system dialog being displayed to the user
     * if permission had not already been granted.
     * Success or failure is returned via the {@link android.app.PendingIntent} pi.
     * If successful, this grants the caller permission to access the accessory only
     * until the device is disconnected.
     *
     * The following extras will be added to pi:
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the accessory passed into this call
     * <li> {@link #EXTRA_PERMISSION_GRANTED} containing boolean indicating whether
     * permission was granted by the user
     * </ul>
     *
     * @param accessory to request permissions for
     * @param pi PendingIntent for returning result
     */
    public void requestPermission(UsbAccessory accessory, PendingIntent pi) {
        try {
            mService.requestAccessoryPermission(accessory, mContext.getPackageName(), pi);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in requestPermission", e);
        }
    }

    private static boolean propertyContainsFunction(String property, String function) {
        String functions = SystemProperties.get(property, "");
        int index = functions.indexOf(function);
        if (index < 0) return false;
        if (index > 0 && functions.charAt(index - 1) != ',') return false;
        int charAfter = index + function.length();
        if (charAfter < functions.length() && functions.charAt(charAfter) != ',') return false;
        return true;
    }

    /**
     * Returns true if the specified USB function is currently enabled.
     *
     * @param function name of the USB function
     * @return true if the USB function is enabled.
     *
     * {@hide}
     */
    public boolean isFunctionEnabled(String function) {
        return propertyContainsFunction("sys.usb.config", function);
    }

    /**
     * Returns the current default USB function.
     *
     * @return name of the default function.
     *
     * {@hide}
     */
    public String getDefaultFunction() {
        String functions = SystemProperties.get("persist.sys.usb.config", "");
        int commaIndex = functions.indexOf(',');
        if (commaIndex > 0) {
            return functions.substring(0, commaIndex);
        } else {
            return functions;
        }
    }

    /**
     * Sets the current USB function.
     * If function is null, then the current function is set to the default function.
     *
     * @param function name of the USB function, or null to restore the default function
     * @param makeDefault true if the function should be set as the new default function
     *
     * {@hide}
     */
    public void setCurrentFunction(String function, boolean makeDefault) {
        try {
            mService.setCurrentFunction(function, makeDefault);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setCurrentFunction", e);
        }
    }

    /**
     * Sets the file path for USB mass storage backing file.
     *
     * @param path backing file path
     *
     * {@hide}
     */
    public void setMassStorageBackingFile(String path) {
        try {
            mService.setMassStorageBackingFile(path);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setDefaultFunction", e);
        }
    }
}
