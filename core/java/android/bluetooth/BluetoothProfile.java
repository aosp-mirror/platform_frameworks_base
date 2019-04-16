/*
 * Copyright (C) 2010-2016 The Android Open Source Project
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

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";

    /**
     * Extra for the connection state intents of the individual profiles.
     *
     * This extra represents the previous connection state of the profile of the
     * Bluetooth device.
     */
    String EXTRA_PREVIOUS_STATE =
            "android.bluetooth.profile.extra.PREVIOUS_STATE";

    /** The profile is in disconnected state */
    int STATE_DISCONNECTED = 0;
    /** The profile is in connecting state */
    int STATE_CONNECTING = 1;
    /** The profile is in connected state */
    int STATE_CONNECTED = 2;
    /** The profile is in disconnecting state */
    int STATE_DISCONNECTING = 3;

    /** @hide */
    @IntDef({
            STATE_DISCONNECTED,
            STATE_CONNECTING,
            STATE_CONNECTED,
            STATE_DISCONNECTING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BtProfileState {}

    /**
     * Headset and Handsfree profile
     */
    int HEADSET = 1;

    /**
     * A2DP profile.
     */
    int A2DP = 2;

    /**
     * Health Profile
     *
     * @deprecated Health Device Profile (HDP) and MCAP protocol are no longer used. New
     * apps should use Bluetooth Low Energy based solutions such as {@link BluetoothGatt},
     * {@link BluetoothAdapter#listenUsingL2capChannel()}, or
     * {@link BluetoothDevice#createL2capChannel(int)}
     */
    @Deprecated
    int HEALTH = 3;

    /**
     * HID Host
     *
     * @hide
     */
    int HID_HOST = 4;

    /**
     * PAN Profile
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    int PAN = 5;

    /**
     * PBAP
     *
     * @hide
     */
    int PBAP = 6;

    /**
     * GATT
     */
    int GATT = 7;

    /**
     * GATT_SERVER
     */
    int GATT_SERVER = 8;

    /**
     * MAP Profile
     *
     * @hide
     */
    int MAP = 9;

    /*
     * SAP Profile
     * @hide
     */
    int SAP = 10;

    /**
     * A2DP Sink Profile
     *
     * @hide
     */
    @UnsupportedAppUsage
    int A2DP_SINK = 11;

    /**
     * AVRCP Controller Profile
     *
     * @hide
     */
    @UnsupportedAppUsage
    int AVRCP_CONTROLLER = 12;

    /**
     * AVRCP Target Profile
     *
     * @hide
     */
    int AVRCP = 13;

    /**
     * Headset Client - HFP HF Role
     *
     * @hide
     */
    int HEADSET_CLIENT = 16;

    /**
     * PBAP Client
     *
     * @hide
     */
    int PBAP_CLIENT = 17;

    /**
     * MAP Messaging Client Equipment (MCE)
     *
     * @hide
     */
    int MAP_CLIENT = 18;

    /**
     * HID Device
     */
    int HID_DEVICE = 19;

    /**
     * Object Push Profile (OPP)
     *
     * @hide
     */
    int OPP = 20;

    /**
     * Hearing Aid Device
     *
     */
    int HEARING_AID = 21;

    /**
     * Max profile ID. This value should be updated whenever a new profile is added to match
     * the largest value assigned to a profile.
     *
     * @hide
     */
    int MAX_PROFILE_ID = 21;

    /**
     * Default priority for devices that we try to auto-connect to and
     * and allow incoming connections for the profile
     *
     * @hide
     **/
    @UnsupportedAppUsage
    int PRIORITY_AUTO_CONNECT = 1000;

    /**
     * Default priority for devices that allow incoming
     * and outgoing connections for the profile
     *
     * @hide
     **/
    @SystemApi
    int PRIORITY_ON = 100;

    /**
     * Default priority for devices that does not allow incoming
     * connections and outgoing connections for the profile.
     *
     * @hide
     **/
    @SystemApi
    int PRIORITY_OFF = 0;

    /**
     * Default priority when not set or when the device is unpaired
     *
     * @hide
     */
    @UnsupportedAppUsage
    int PRIORITY_UNDEFINED = -1;

    /**
     * Get connected devices for this specific profile.
     *
     * <p> Return the set of devices which are in state {@link #STATE_CONNECTED}
     *
     * @return List of devices. The list will be empty on error.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public List<BluetoothDevice> getConnectedDevices();

    /**
     * Get a list of devices that match any of the given connection
     * states.
     *
     * <p> If none of the devices match any of the given states,
     * an empty list will be returned.
     *
     * @param states Array of states. States can be one of {@link #STATE_CONNECTED}, {@link
     * #STATE_CONNECTING}, {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING},
     * @return List of devices. The list will be empty on error.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states);

    /**
     * Get the current connection state of the profile
     *
     * @param device Remote bluetooth device.
     * @return State of the profile connection. One of {@link #STATE_CONNECTED}, {@link
     * #STATE_CONNECTING}, {@link #STATE_DISCONNECTED}, {@link #STATE_DISCONNECTING}
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @BtProfileState int getConnectionState(BluetoothDevice device);

    /**
     * An interface for notifying BluetoothProfile IPC clients when they have
     * been connected or disconnected to the service.
     */
    public interface ServiceListener {
        /**
         * Called to notify the client when the proxy object has been
         * connected to the service.
         *
         * @param profile - One of {@link #HEADSET} or {@link #A2DP}
         * @param proxy - One of {@link BluetoothHeadset} or {@link BluetoothA2dp}
         */
        public void onServiceConnected(int profile, BluetoothProfile proxy);

        /**
         * Called to notify the client that this proxy object has been
         * disconnected from the service.
         *
         * @param profile - One of {@link #HEADSET} or {@link #A2DP}
         */
        public void onServiceDisconnected(int profile);
    }

    /**
     * Convert an integer value of connection state into human readable string
     *
     * @param connectionState - One of {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, or {@link #STATE_DISCONNECTED}
     * @return a string representation of the connection state, STATE_UNKNOWN if the state
     * is not defined
     * @hide
     */
    static String getConnectionStateName(int connectionState) {
        switch (connectionState) {
            case STATE_DISCONNECTED:
                return "STATE_DISCONNECTED";
            case STATE_CONNECTING:
                return "STATE_CONNECTING";
            case STATE_CONNECTED:
                return "STATE_CONNECTED";
            case STATE_DISCONNECTING:
                return "STATE_DISCONNECTING";
            default:
                return "STATE_UNKNOWN";
        }
    }
}
