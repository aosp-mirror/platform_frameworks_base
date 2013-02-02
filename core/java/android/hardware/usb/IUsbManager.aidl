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
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** @hide */
interface IUsbManager
{
    /* Returns a list of all currently attached USB devices */
    void getDeviceList(out Bundle devices);

    /* Returns a file descriptor for communicating with the USB device.
     * The native fd can be passed to usb_device_new() in libusbhost.
     */
    ParcelFileDescriptor openDevice(String deviceName);

    /* Returns the currently attached USB accessory */
    UsbAccessory getCurrentAccessory();

    /* Returns a file descriptor for communicating with the USB accessory.
     * This file descriptor can be used with standard Java file operations.
     */
    ParcelFileDescriptor openAccessory(in UsbAccessory accessory);

    /* Sets the default package for a USB device
     * (or clears it if the package name is null)
     */
    void setDevicePackage(in UsbDevice device, String packageName, int userId);

    /* Sets the default package for a USB accessory
     * (or clears it if the package name is null)
     */
    void setAccessoryPackage(in UsbAccessory accessory, String packageName, int userId);

    /* Returns true if the caller has permission to access the device. */
    boolean hasDevicePermission(in UsbDevice device);

    /* Returns true if the caller has permission to access the accessory. */
    boolean hasAccessoryPermission(in UsbAccessory accessory);

    /* Requests permission for the given package to access the device.
     * Will display a system dialog to query the user if permission
     * had not already been given.
     */
    void requestDevicePermission(in UsbDevice device, String packageName, in PendingIntent pi);

    /* Requests permission for the given package to access the accessory.
     * Will display a system dialog to query the user if permission
     * had not already been given. Result is returned via pi.
     */
    void requestAccessoryPermission(in UsbAccessory accessory, String packageName,
            in PendingIntent pi);

    /* Grants permission for the given UID to access the device */
    void grantDevicePermission(in UsbDevice device, int uid);

    /* Grants permission for the given UID to access the accessory */
    void grantAccessoryPermission(in UsbAccessory accessory, int uid);

    /* Returns true if the USB manager has default preferences or permissions for the package */
    boolean hasDefaults(String packageName, int userId);

    /* Clears default preferences and permissions for the package */
    void clearDefaults(String packageName, int userId);

    /* Sets the current USB function. */
    void setCurrentFunction(String function, boolean makeDefault);

    /* Sets the file path for USB mass storage backing file. */
    void setMassStorageBackingFile(String path);

    /* Allow USB debugging from the attached host. If alwaysAllow is true, add the
     * the public key to list of host keys that the user has approved.
     */
    void allowUsbDebugging(boolean alwaysAllow, String publicKey);

    /* Deny USB debugging from the attached host */
    void denyUsbDebugging();

    /* Clear public keys installed for secure USB debugging */
    void clearUsbDebuggingKeys();
}
