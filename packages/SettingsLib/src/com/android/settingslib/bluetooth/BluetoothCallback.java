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


/**
 * BluetoothCallback provides a callback interface for the settings
 * UI to receive events from {@link BluetoothEventManager}.
 */
public interface BluetoothCallback {
    /**
     * It will be called when the state of the local Bluetooth adapter has been changed.
     * It is listening {@link android.bluetooth.BluetoothAdapter#ACTION_STATE_CHANGED}.
     * For example, Bluetooth has been turned on or off.
     *
     * @param bluetoothState the current Bluetooth state, the possible values are:
     * {@link android.bluetooth.BluetoothAdapter#STATE_OFF},
     * {@link android.bluetooth.BluetoothAdapter#STATE_TURNING_ON},
     * {@link android.bluetooth.BluetoothAdapter#STATE_ON},
     * {@link android.bluetooth.BluetoothAdapter#STATE_TURNING_OFF}.
     */
    default void onBluetoothStateChanged(int bluetoothState) {}

    /**
     * It will be called when the local Bluetooth adapter has started
     * or finished the remote device discovery process.
     * It is listening {@link android.bluetooth.BluetoothAdapter#ACTION_DISCOVERY_STARTED} and
     * {@link android.bluetooth.BluetoothAdapter#ACTION_DISCOVERY_FINISHED}.
     *
     * @param started indicate the current process is started or finished.
     */
    default void onScanningStateChanged(boolean started) {}

    /**
     * It will be called in following situations:
     * 1. In scanning mode, when a new device has been found.
     * 2. When a profile service is connected and existing connected devices has been found.
     * This API only invoked once for each device and all devices will be cached in
     * {@link CachedBluetoothDeviceManager}.
     *
     * @param cachedDevice the Bluetooth device.
     */
    default void onDeviceAdded(CachedBluetoothDevice cachedDevice) {}

    /**
     * It will be called when requiring to remove a remote device from CachedBluetoothDevice list
     *
     * @param cachedDevice the Bluetooth device.
     */
    default void onDeviceDeleted(CachedBluetoothDevice cachedDevice) {}

    /**
     * It will be called when bond state of a remote device is changed.
     * It is listening {@link android.bluetooth.BluetoothDevice#ACTION_BOND_STATE_CHANGED}
     *
     * @param cachedDevice the Bluetooth device.
     * @param bondState the Bluetooth device bond state, the possible values are:
     * {@link android.bluetooth.BluetoothDevice#BOND_NONE},
     * {@link android.bluetooth.BluetoothDevice#BOND_BONDING},
     * {@link android.bluetooth.BluetoothDevice#BOND_BONDED}.
     */
    default void onDeviceBondStateChanged(CachedBluetoothDevice cachedDevice, int bondState) {}

    /**
     * It will be called in following situations:
     * 1. When the adapter is not connected to any profiles of any remote devices
     * and it attempts a connection to a profile.
     * 2. When the adapter disconnects from the last profile of the last device.
     * It is listening {@link android.bluetooth.BluetoothAdapter#ACTION_CONNECTION_STATE_CHANGED}
     *
     * @param cachedDevice the Bluetooth device.
     * @param state the Bluetooth device connection state, the possible values are:
     * {@link android.bluetooth.BluetoothAdapter#STATE_DISCONNECTED},
     * {@link android.bluetooth.BluetoothAdapter#STATE_CONNECTING},
     * {@link android.bluetooth.BluetoothAdapter#STATE_CONNECTED},
     * {@link android.bluetooth.BluetoothAdapter#STATE_DISCONNECTING}.
     */
    default void onConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {}

    /**
     * It will be called when device been set as active for {@code bluetoothProfile}
     * It is listening in following intent:
     * {@link android.bluetooth.BluetoothA2dp#ACTION_ACTIVE_DEVICE_CHANGED}
     * {@link android.bluetooth.BluetoothHeadset#ACTION_ACTIVE_DEVICE_CHANGED}
     * {@link android.bluetooth.BluetoothHearingAid#ACTION_ACTIVE_DEVICE_CHANGED}
     *
     * @param activeDevice the active Bluetooth device.
     * @param bluetoothProfile the profile of active Bluetooth device.
     */
    default void onActiveDeviceChanged(CachedBluetoothDevice activeDevice, int bluetoothProfile) {}

    /**
     * It will be called in following situations:
     * 1. When the call state on the device is changed.
     * 2. When the audio connection state of the A2DP profile is changed.
     * It is listening in following intent:
     * {@link android.bluetooth.BluetoothHeadset#ACTION_AUDIO_STATE_CHANGED}
     * {@link android.telephony.TelephonyManager#ACTION_PHONE_STATE_CHANGED}
     */
    default void onAudioModeChanged() {}

    /**
     * It will be called when one of the bluetooth device profile connection state is changed.
     *
     * @param cachedDevice the active Bluetooth device.
     * @param state the BluetoothProfile connection state, the possible values are:
     * {@link android.bluetooth.BluetoothProfile#STATE_CONNECTED},
     * {@link android.bluetooth.BluetoothProfile#STATE_CONNECTING},
     * {@link android.bluetooth.BluetoothProfile#STATE_DISCONNECTED},
     * {@link android.bluetooth.BluetoothProfile#STATE_DISCONNECTING}.
     * @param bluetoothProfile the BluetoothProfile id.
     */
    default void onProfileConnectionStateChanged(CachedBluetoothDevice cachedDevice,
            int state, int bluetoothProfile) {
    }

    /**
     * Called when ACL connection state is changed. It listens to
     * {@link android.bluetooth.BluetoothDevice#ACTION_ACL_CONNECTED} and {@link
     * android.bluetooth.BluetoothDevice#ACTION_ACL_DISCONNECTED}
     *
     * @param cachedDevice Bluetooth device that changed
     * @param state        the Bluetooth device connection state, the possible values are:
     *                     {@link android.bluetooth.BluetoothAdapter#STATE_DISCONNECTED},
     *                     {@link android.bluetooth.BluetoothAdapter#STATE_CONNECTED}
     */
    default void onAclConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
    }
}
