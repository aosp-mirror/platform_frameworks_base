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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;

/**
 * LocalBluetoothProfile is an interface defining the basic
 * functionality related to a Bluetooth profile.
 */
public interface LocalBluetoothProfile {

    /**
     * Return {@code true} if the user can initiate a connection for this profile in UI.
     */
    boolean accessProfileEnabled();

    /**
     * Returns true if the user can enable auto connection for this profile.
     */
    boolean isAutoConnectable();

    int getConnectionStatus(BluetoothDevice device);

    /**
     * Return {@code true} if the profile is enabled, otherwise return {@code false}.
     * @param device the device to query for enable status
     */
    boolean isEnabled(BluetoothDevice device);

    /**
     * Get the connection policy of the profile.
     * @param device the device to query for enable status
     */
    int getConnectionPolicy(BluetoothDevice device);

    /**
     * Enable the profile if {@code enabled} is {@code true}, otherwise disable profile.
     * @param device the device to set profile status
     * @param enabled {@code true} for enable profile, otherwise disable profile.
     */
    boolean setEnabled(BluetoothDevice device, boolean enabled);

    boolean isProfileReady();

    int getProfileId();

    /** Display order for device profile settings. */
    int getOrdinal();

    /**
     * Returns the string resource ID for the localized name for this profile.
     * @param device the Bluetooth device (to distinguish between PAN roles)
     */
    int getNameResource(BluetoothDevice device);

    /**
     * Returns the string resource ID for the summary text for this profile
     * for the specified device, e.g. "Use for media audio" or
     * "Connected to media audio".
     * @param device the device to query for profile connection status
     * @return a string resource ID for the profile summary text
     */
    int getSummaryResourceForDevice(BluetoothDevice device);

    int getDrawableResource(BluetoothClass btClass);
}
