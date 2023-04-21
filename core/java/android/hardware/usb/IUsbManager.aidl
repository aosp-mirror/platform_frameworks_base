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
import android.content.ComponentName;
import android.hardware.usb.IDisplayPortAltModeInfoListener;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;

/** @hide */
interface IUsbManager
{
    /* Returns a list of all currently attached USB devices */
    void getDeviceList(out Bundle devices);

    /* Returns a file descriptor for communicating with the USB device.
     * The native fd can be passed to usb_device_new() in libusbhost.
     */
    ParcelFileDescriptor openDevice(String deviceName, String packageName);

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

    /* Adds packages to the set of "denied and don't ask again" launch preferences for a device */
    void addDevicePackagesToPreferenceDenied(in UsbDevice device, in String[] packageNames, in UserHandle user);

    /* Adds packages to the set of "denied and don't ask again" launch preferences for an accessory */
    void addAccessoryPackagesToPreferenceDenied(in UsbAccessory accessory, in String[] packageNames, in UserHandle user);

    /* Removes packages from the set of "denied and don't ask again" launch preferences for a device */
    void removeDevicePackagesFromPreferenceDenied(in UsbDevice device, in String[] packageNames, in UserHandle user);

    /* Removes packages from the set of "denied and don't ask again" launch preferences for an accessory */
    void removeAccessoryPackagesFromPreferenceDenied(in UsbAccessory device, in String[] packageNames, in UserHandle user);

    /* Sets the persistent permission granted state for USB device
     */
    void setDevicePersistentPermission(in UsbDevice device, int uid, in UserHandle user, boolean shouldBeGranted);

    /* Sets the persistent permission granted state for USB accessory
     */
    void setAccessoryPersistentPermission(in UsbAccessory accessory, int uid, in UserHandle user, boolean shouldBeGranted);

    /* Returns true if the caller has permission to access the device. */
    boolean hasDevicePermission(in UsbDevice device, String packageName);

    /* Returns true if the given package/pid/uid has permission to access the device. */
    @EnforcePermission("MANAGE_USB")
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_USB)")
    boolean hasDevicePermissionWithIdentity(in UsbDevice device, String packageName,
            int pid, int uid);

    /* Returns true if the caller has permission to access the accessory. */
    boolean hasAccessoryPermission(in UsbAccessory accessory);

    /* Returns true if the given pid/uid has permission to access the accessory. */
    @EnforcePermission("MANAGE_USB")
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_USB)")
    boolean hasAccessoryPermissionWithIdentity(in UsbAccessory accessory, int pid, int uid);

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
    @EnforcePermission("MANAGE_USB")
    void grantDevicePermission(in UsbDevice device, int uid);

    /* Grants permission for the given UID to access the accessory */
    @EnforcePermission("MANAGE_USB")
    void grantAccessoryPermission(in UsbAccessory accessory, int uid);

    /* Returns true if the USB manager has default preferences or permissions for the package */
    boolean hasDefaults(String packageName, int userId);

    /* Clears default preferences and permissions for the package */
    void clearDefaults(String packageName, int userId);

    /* Returns true if the specified USB function is enabled. */
    boolean isFunctionEnabled(String function);

    /* Sets the current USB function. */
    @EnforcePermission("MANAGE_USB")
    void setCurrentFunctions(long functions, int operationId);

    /* Compatibility version of setCurrentFunctions(long). */
    void setCurrentFunction(String function, boolean usbDataUnlocked, int operationId);

    /* Gets the current USB functions. */
    @EnforcePermission("MANAGE_USB")
    long getCurrentFunctions();

    /* Gets the current USB Speed. */
    @EnforcePermission("MANAGE_USB")
    int getCurrentUsbSpeed();

    /* Gets the Gadget Hal Version. */
    @EnforcePermission("MANAGE_USB")
    int getGadgetHalVersion();

    /* Sets the screen unlocked USB function(s), which will be set automatically
     * when the screen is unlocked.
     */
    @EnforcePermission("MANAGE_USB")
    void setScreenUnlockedFunctions(long functions);

    /* Gets the current screen unlocked functions. */
    @EnforcePermission("MANAGE_USB")
    long getScreenUnlockedFunctions();

    /* Resets the USB gadget. */
    @EnforcePermission("MANAGE_USB")
    void resetUsbGadget();

    /* Resets the USB port. */
    void resetUsbPort(in String portId, int operationId, in IUsbOperationInternal callback);

    /* Set USB data on or off */
    boolean enableUsbData(in String portId, boolean enable, int operationId, in IUsbOperationInternal callback);

    /* Enable USB data when disabled due to docking event  */
    void enableUsbDataWhileDocked(in String portId, int operationId, in IUsbOperationInternal callback);

    /* Gets the USB Hal Version. */
    @EnforcePermission("MANAGE_USB")
    int getUsbHalVersion();

    /* Get the functionfs control handle for the given function. Usb
     * descriptors will already be written, and the handle will be
     * ready to use.
     */
    @EnforcePermission("ACCESS_MTP")
    ParcelFileDescriptor getControlFd(long function);

    /* Gets the list of USB ports. */
    @EnforcePermission("MANAGE_USB")
    List<ParcelableUsbPort> getPorts();

    /* Gets the status of the specified USB port. */
    UsbPortStatus getPortStatus(in String portId);

    /* Sets the port's current role. */
    void setPortRoles(in String portId, int powerRole, int dataRole);

    /* Limit power transfer in & out of the port within the allowed limit by the USB
     * specification.
     */
    void enableLimitPowerTransfer(in String portId, boolean limit, int operationId, in IUsbOperationInternal callback);

    /* Enable/disable contaminant detection */
    void enableContaminantDetection(in String portId, boolean enable);

    /* Sets USB device connection handler. */
    @EnforcePermission("MANAGE_USB")
    void setUsbDeviceConnectionHandler(in ComponentName usbDeviceConnectionHandler);

    /* Registers callback for Usb events */
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_USB)")
    boolean registerForDisplayPortEvents(IDisplayPortAltModeInfoListener listener);

    /* Unregisters Usb event callback */
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_USB)")
    void unregisterForDisplayPortEvents(IDisplayPortAltModeInfoListener listener);

}
