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


package android.bluetooth;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;

import java.util.Set;

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
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> Currently, the system supports only 1 connection to the
     * A2DP and Headset/Handsfree profile. The API will automatically
     * disconnect connected devices before connecting.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error,
     *               true otherwise
     * @hide
     */
    public boolean connect(BluetoothDevice device);

    /**
     * Initiate disconnection from a profile
     *
     * <p> This API will return false in scenarios like the profile on the
     * Bluetooth device is not in connected state etc. When this API returns,
     * true, it is guaranteed that the connection state change
     * intent will be broadcasted with the state. Users can get the
     * disconnection state of the profile from this intent.
     *
     * <p> If the disconnection is initiated by a remote device, the state
     * will transition from {@link #STATE_CONNECTED} to
     * {@link #STATE_DISCONNECTED}. If the disconnect is initiated by the
     * host (local) device the state will transition from
     * {@link #STATE_CONNECTED} to state {@link #STATE_DISCONNECTING} to
     * state {@link #STATE_DISCONNECTED}. The transition to
     * {@link #STATE_DISCONNECTING} can be used to distinguish between the
     * two scenarios.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error,
     *               true otherwise
     * @hide
     */
    public boolean disconnect(BluetoothDevice device);

    /**
     * Get connected devices for this specific profile.
     *
     * <p> Return the set of devices which are in state {@link #STATE_CONNECTED}
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return An unmodifiable set of devices. The set will be empty on error.
     */
    public Set<BluetoothDevice> getConnectedDevices();

    /**
     * Get a set of devices that match any of the given connection
     * states.
     *
     * <p> If none of devices match any of the given states,
     * an empty set will be returned.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param states Array of states. States can be one of
     *              {@link #STATE_CONNECTED}, {@link #STATE_CONNECTING},
     *              {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING},
     * @return An unmodifiable set of devices. The set will be empty on error.
     */
    public Set<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states);

    /**
     * Get the current connection state of the profile
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param device Remote bluetooth device.
     * @return State of the profile connection. One of
     *               {@link #STATE_CONNECTED}, {@link #STATE_CONNECTING},
     *               {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING}
     */
    public int getConnectionState(BluetoothDevice device);

    /**
     * Set priority of the profile
     *
     * <p> The device should already be paired.
     *  Priority can be one of {@link #PRIORITY_ON} or
     * {@link #PRIORITY_OFF},
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param device Paired bluetooth device
     * @param priority
     * @return true if priority is set, false on error
     * @hide
     */
    public boolean setPriority(BluetoothDevice device, int priority);

    /**
     * Get the priority of the profile.
     *
     * <p> The priority can be any of:
     * {@link #PRIORITY_AUTO_CONNECT}, {@link #PRIORITY_OFF},
     * {@link #PRIORITY_ON}, {@link #PRIORITY_UNDEFINED}
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param device Bluetooth device
     * @return priority of the device
     * @hide
     */
    public int getPriority(BluetoothDevice device);

    /**
     * An interface for notifying BluetoothProfile IPC clients when they have
     * been connected or disconnected to the service.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when the proxy object has been
         * connected to the service.
         * @param profile - One of {@link #HEADSET} or
         *                  {@link #A2DP}
         * @param proxy - One of {@link BluetoothHeadset} or
         *                {@link BluetoothA2dp}
         */
        public void onServiceConnected(int profile, BluetoothProfile proxy);

        /**
         * Called to notify the client that this proxy object has been
         * disconnected from the service.
         * @param profile - One of {@link #HEADSET} or
         *                  {@link #A2DP}
         */
        public void onServiceDisconnected(int profile);
    }
}
