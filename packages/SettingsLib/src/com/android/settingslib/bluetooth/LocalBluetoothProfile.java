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
     * Returns true if the user can initiate a connection, false otherwise.
     */
    boolean isConnectable();

    /**
     * Returns true if the user can enable auto connection for this profile.
     */
    boolean isAutoConnectable();

    boolean connect(BluetoothDevice device);

    boolean disconnect(BluetoothDevice device);

    int getConnectionStatus(BluetoothDevice device);

    boolean isPreferred(BluetoothDevice device);

    int getPreferred(BluetoothDevice device);

    void setPreferred(BluetoothDevice device, boolean preferred);

    boolean isProfileReady();

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
