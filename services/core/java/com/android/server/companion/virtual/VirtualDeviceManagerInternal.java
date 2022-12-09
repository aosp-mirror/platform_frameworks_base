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
import android.companion.virtual.IVirtualDevice;

import java.util.Set;

/**
 * Virtual device manager local service interface.
 * Only for use within system server.
 */
public abstract class VirtualDeviceManagerInternal {

    /** Interface to listen to the creation and destruction of virtual displays. */
    public interface VirtualDisplayListener {
        /** Notifies that a virtual display was created. */
        void onVirtualDisplayCreated(int displayId);

        /** Notifies that a virtual display was removed. */
        void onVirtualDisplayRemoved(int displayId);
    }


    /** Interface to listen to the changes on the list of app UIDs running on any virtual device. */
    public interface AppsOnVirtualDeviceListener {
        /** Notifies that running apps on any virtual device has changed */
        void onAppsOnAnyVirtualDeviceChanged(Set<Integer> allRunningUids);
    }

    /** Register a listener for the creation and destruction of virtual displays. */
    public abstract void registerVirtualDisplayListener(
            @NonNull VirtualDisplayListener listener);

    /** Unregister a listener for the creation and destruction of virtual displays. */
    public abstract void unregisterVirtualDisplayListener(
            @NonNull VirtualDisplayListener listener);

    /** Register a listener for changes of running app UIDs on any virtual device. */
    public abstract void registerAppsOnVirtualDeviceListener(
            @NonNull AppsOnVirtualDeviceListener listener);

    /** Unregister a listener for changes of running app UIDs on any virtual device. */
    public abstract void unregisterAppsOnVirtualDeviceListener(
            @NonNull AppsOnVirtualDeviceListener listener);

    /**
     * Notifies that the set of apps running on virtual devices has changed.
     * This method only notifies the listeners when the union of running UIDs on all virtual devices
     * has changed.
     */
    public abstract void onAppsOnVirtualDeviceChanged();

    /**
     * Validate the virtual device.
     */
    public abstract boolean isValidVirtualDevice(IVirtualDevice virtualDevice);

    /**
     * Notifies that a virtual display is created.
     *
     * @param displayId The display id of the created virtual display.
     */
    public abstract void onVirtualDisplayCreated(int displayId);

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
     * Returns true if the given {@code uid} is the owner of any virtual devices that are
     * currently active.
     */
    public abstract boolean isAppOwnerOfAnyVirtualDevice(int uid);

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
}
