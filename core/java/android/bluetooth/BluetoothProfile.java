/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
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


package android.bluetooth;

import java.util.List;

/**
 * Public APIs for the Bluetooth Profiles.
 *
 * <p> Clients should call {@link BluetoothAdapter#getProfileProxy},
 * to get the Profile Proxy. Each public profile implements this
 * interface.
 */
public interface BluetoothProfile {

    /**
     * Extra for the connection state intents of the individual profiles.
     *
     * This extra represents the current connection state of the profile of the
     * Bluetooth device.
     */
    public static final String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";

    /**
     * Extra for the connection state intents of the individual profiles.
     *
     * This extra represents the previous connection state of the profile of the
     * Bluetooth device.
     */
    public static final String EXTRA_PREVIOUS_STATE =
        "android.bluetooth.profile.extra.PREVIOUS_STATE";

    /** The profile is in disconnected state */
    public static final int STATE_DISCONNECTED  = 0;
    /** The profile is in connecting state */
    public static final int STATE_CONNECTING    = 1;
    /** The profile is in connected state */
    public static final int STATE_CONNECTED     = 2;
    /** The profile is in disconnecting state */
    public static final int STATE_DISCONNECTING = 3;

    /**
     * Headset and Handsfree profile
     */
    public static final int HEADSET = 1;

    /**
     * A2DP profile.
     */
    public static final int A2DP = 2;

    /**
     * Health Profile
     */
    public static final int HEALTH = 3;

    /**
     * Input Device Profile
     * @hide
     */
    public static final int INPUT_DEVICE = 4;

    /**
     * PAN Profile
     * @hide
     */
    public static final int PAN = 5;

    /**
     * PBAP
     * @hide
     */
    public static final int PBAP = 6;

    /**
     * GATT
     */
    static public final int GATT = 7;

    /**
     * GATT_SERVER
     */
    static public final int GATT_SERVER = 8;

    /**
     * MAP Profile
     * @hide
     */
    public static final int MAP = 9;

    /**
     * SAP
     * @hide
     */
    public static final int SAP = 20;

     /**
     * Handsfree Client - HFP HF Role
     * @hide
     */
    public static final int HANDSFREE_CLIENT = 10;

    /**
     * DUN
     * @hide
     */
    public static final int DUN = 21;

    /**
     * Default priority for devices that we try to auto-connect to and
     * and allow incoming connections for the profile
     * @hide
     **/
    public static final int PRIORITY_AUTO_CONNECT = 1000;

    /**
     *  Default priority for devices that allow incoming
     * and outgoing connections for the profile
     * @hide
     **/
    public static final int PRIORITY_ON = 100;

    /**
     * Default priority for devices that does not allow incoming
     * connections and outgoing connections for the profile.
     * @hide
     **/
    public static final int PRIORITY_OFF = 0;

    /**
     * Default priority when not set or when the device is unpaired
     * @hide
     * */
    public static final int PRIORITY_UNDEFINED = -1;

    /**
     * Get connected devices for this specific profile.
     *
     * <p> Return the set of devices which are in state {@link #STATE_CONNECTED}
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @return List of devices. The list will be empty on error.
     */
    public List<BluetoothDevice> getConnectedDevices();

    /**
     * Get a list of devices that match any of the given connection
     * states.
     *
     * <p> If none of the devices match any of the given states,
     * an empty list will be returned.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param states Array of states. States can be one of
     *              {@link #STATE_CONNECTED}, {@link #STATE_CONNECTING},
     *              {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING},
     * @return List of devices. The list will be empty on error.
     */
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states);

    /**
     * Get the current connection state of the profile
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param device Remote bluetooth device.
     * @return State of the profile connection. One of
     *               {@link #STATE_CONNECTED}, {@link #STATE_CONNECTING},
     *               {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING}
     */
    public int getConnectionState(BluetoothDevice device);

    /**
     * An interface for notifying BluetoothProfile IPC clients when they have
     * been connected or disconnected to the service.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when the proxy object has been
         * connected to the service.
         * @param profile - One of {@link #HEALTH}, {@link #HEADSET} or
         *                  {@link #A2DP}
         * @param proxy - One of {@link BluetoothHealth}, {@link BluetoothHeadset} or
         *                {@link BluetoothA2dp}
         */
        public void onServiceConnected(int profile, BluetoothProfile proxy);

        /**
         * Called to notify the client that this proxy object has been
         * disconnected from the service.
         * @param profile - One of {@link #HEALTH}, {@link #HEADSET} or
         *                  {@link #A2DP}
         */
        public void onServiceDisconnected(int profile);
    }
}
