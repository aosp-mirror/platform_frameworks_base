/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.sensor.VirtualSensor;
import android.os.LocaleList;
import android.util.ArraySet;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Virtual device manager local service interface.
 * Only for use within system server.
 */
public abstract class VirtualDeviceManagerInternal {

    /** Interface to listen to the changes on the list of app UIDs running on any virtual device. */
    public interface AppsOnVirtualDeviceListener {
        /** Notifies that running apps on any virtual device has changed */
        void onAppsOnAnyVirtualDeviceChanged(Set<Integer> allRunningUids);
    }

    /** Register a listener for changes of running app UIDs on any virtual device. */
    public abstract void registerAppsOnVirtualDeviceListener(
            @NonNull AppsOnVirtualDeviceListener listener);

    /** Unregister a listener for changes of running app UIDs on any virtual device. */
    public abstract void unregisterAppsOnVirtualDeviceListener(
            @NonNull AppsOnVirtualDeviceListener listener);

    /** Register a listener for removal of persistent device IDs. */
    public abstract void registerPersistentDeviceIdRemovedListener(
            @NonNull Consumer<String> persistentDeviceIdRemovedListener);

    /** Unregister a listener for the removal of persistent device IDs. */
    public abstract void unregisterPersistentDeviceIdRemovedListener(
            @NonNull Consumer<String> persistentDeviceIdRemovedListener);

    /**
     * Notifies that the set of apps running on virtual devices has changed.
     * This method only notifies the listeners when the union of running UIDs on all virtual devices
     * has changed.
     */
    public abstract void onAppsOnVirtualDeviceChanged();

    /**
     * Notifies that an authentication prompt is about to be shown for an app with the given uid.
     */
    public abstract void onAuthenticationPrompt(int uid);

    /**
     * Notifies the given persistent device IDs have been removed.
     */
    public abstract void onPersistentDeviceIdsRemoved(Set<String> removedPersistentDeviceIds);

    /**
     * Gets the owner uid for a deviceId.
     *
     * @param deviceId which device we're asking about
     * @return the uid of the app which created and owns the VirtualDevice with the given deviceId,
     * or {@link android.os.Process#INVALID_UID} if no such device exists.
     */
    public abstract int getDeviceOwnerUid(int deviceId);

    /**
     * Returns the VirtualSensor for the given deviceId and sensor handle, if any.
     *
     * @param deviceId the virtual device that owns the sensor
     * @param handle the sensor handle
     * @return the VirtualSensor with the given handle, or {@code null} if no such sensor exists.
     */
    public abstract @Nullable VirtualSensor getVirtualSensor(int deviceId, int handle);

    /**
     * Finds VirtualDevices where an app is running.
     *
     * @param uid - the app's uid
     * @return a set of id's of VirtualDevices where the app with the given uid is running.
     * *Note* this only checks VirtualDevices, and does not include information about whether
     * the app is running on the default device or not.
     */
    public abstract @NonNull ArraySet<Integer> getDeviceIdsForUid(int uid);

    /**
     * Notifies that a virtual display is removed.
     *
     * @param virtualDevice The virtual device where the virtual display located.
     * @param displayId     The display id of the removed virtual display.
     */
    public abstract void onVirtualDisplayRemoved(IVirtualDevice virtualDevice, int displayId);

    /**
     * Returns the flags that should be added to any virtual displays created on this virtual
     * device.
     */
    public abstract int getBaseVirtualDisplayFlags(IVirtualDevice virtualDevice);

    /**
     * Returns the preferred locale hints of the Virtual Device on which the given app is running,
     * or {@code null} if the hosting virtual device doesn't have a virtual keyboard or the app is
     * not on any virtual device.
     *
     * If an app is on multiple virtual devices, the locale of the virtual device created the
     * earliest will be returned.
     *
     * See {@link android.hardware.input.VirtualKeyboardConfig#setLanguageTag() for how the locale
     * is specified for virtual keyboard.
     */
    @Nullable
    public abstract LocaleList getPreferredLocaleListForUid(int uid);

    /**
     * Returns true if the given {@code uid} is currently running on any virtual devices. This is
     * determined by whether the app has any activities in the task stack on a virtual-device-owned
     * display.
     */
    public abstract boolean isAppRunningOnAnyVirtualDevice(int uid);

    /**
     * Returns true if the {@code displayId} is owned by any virtual device
     */
    public abstract boolean isDisplayOwnedByAnyVirtualDevice(int displayId);

    /**
     * Gets the ids of VirtualDisplays owned by a VirtualDevice.
     *
     * @param deviceId which device we're asking about
     * @return the set of display ids for all VirtualDisplays owned by the device
     */
    public abstract @NonNull ArraySet<Integer> getDisplayIdsForDevice(int deviceId);

    /**
     * Gets the persistent ID for the VirtualDevice with the given device ID.
     *
     * @param deviceId which device we're asking about
     * @return the persistent ID for this device, or {@code null} if no such ID exists.
     *
     * @see VirtualDevice#getPersistentDeviceId()
     */
    public abstract @Nullable String getPersistentIdForDevice(int deviceId);
}
