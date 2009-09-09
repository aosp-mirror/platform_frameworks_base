/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * Bluetooth API constants.
 *
 * TODO: Deprecate this class
 * @hide
 */
public interface BluetoothIntent {
    public static final String DEVICE =
        "android.bluetooth.intent.DEVICE";
    public static final String NAME =
        "android.bluetooth.intent.NAME";
    public static final String ALIAS =
        "android.bluetooth.intent.ALIAS";
    public static final String RSSI =
        "android.bluetooth.intent.RSSI";
    public static final String CLASS =
        "android.bluetooth.intent.CLASS";
    public static final String HEADSET_STATE =
        "android.bluetooth.intent.HEADSET_STATE";
    public static final String HEADSET_PREVIOUS_STATE =
        "android.bluetooth.intent.HEADSET_PREVIOUS_STATE";
    public static final String HEADSET_AUDIO_STATE =
        "android.bluetooth.intent.HEADSET_AUDIO_STATE";
    public static final String BOND_STATE =
        "android.bluetooth.intent.BOND_STATE";
    public static final String BOND_PREVIOUS_STATE =
        "android.bluetooth.intent.BOND_PREVIOUS_STATE";
    public static final String REASON =
        "android.bluetooth.intent.REASON";
    public static final String PAIRING_VARIANT =
        "android.bluetooth.intent.PAIRING_VARIANT";
    public static final String PASSKEY =
        "android.bluetooth.intent.PASSKEY";

    public static final String DEVICE_PICKER_NEED_AUTH =
        "android.bluetooth.intent.DEVICE_PICKER_NEED_AUTH";
    public static final String DEVICE_PICKER_FILTER_TYPE =
        "android.bluetooth.intent.DEVICE_PICKER_FILTER_TYPE";
    public static final String DEVICE_PICKER_LAUNCH_PACKAGE =
        "android.bluetooth.intent.DEVICE_PICKER_LAUNCH_PACKAGE";
    public static final String DEVICE_PICKER_LAUNCH_CLASS =
        "android.bluetooth.intent.DEVICE_PICKER_LAUNCH_CLASS";

     /**
     * Broadcast when one BT device is selected from BT device picker screen.
     * Selected BT device address is contained in extra string "BluetoothIntent.ADDRESS".
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String DEVICE_PICKER_DEVICE_SELECTED =
        "android.bluetooth.intent.action.DEVICE_SELECTED";

    /**
     * Broadcast when someone want to select one BT device from devices list.
     * This intent contains below extra data:
     * - BluetoothIntent.DEVICE_PICKER_NEED_AUTH (boolean): if need authentication
     * - BluetoothIntent.DEVICE_PICKER_FILTER_TYPE (int): what kinds of device should be listed
     * - BluetoothIntent.DEVICE_PICKER_LAUNCH_PACKAGE (string): where(which package) this intent come from
     * - BluetoothIntent.DEVICE_PICKER_LAUNCH_CLASS (string): where(which class) this intent come from
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String DEVICE_PICKER_DEVICE_PICKER =
        "android.bluetooth.intent.action.DEVICE_PICKER";

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NAME_CHANGED_ACTION  =
        "android.bluetooth.intent.action.NAME_CHANGED";

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String DISCOVERY_STARTED_ACTION          =
        "android.bluetooth.intent.action.DISCOVERY_STARTED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String DISCOVERY_COMPLETED_ACTION        =
        "android.bluetooth.intent.action.DISCOVERY_COMPLETED";

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String PAIRING_REQUEST_ACTION            =
        "android.bluetooth.intent.action.PAIRING_REQUEST";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String PAIRING_CANCEL_ACTION             =
        "android.bluetooth.intent.action.PAIRING_CANCEL";

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_DEVICE_FOUND_ACTION        =
        "android.bluetooth.intent.action.REMOTE_DEVICE_FOUND";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_DEVICE_DISAPPEARED_ACTION  =
        "android.bluetooth.intent.action.REMOTE_DEVICE_DISAPPEARED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_DEVICE_CLASS_UPDATED_ACTION  =
        "android.bluetooth.intent.action.REMOTE_DEVICE_DISAPPEARED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_DEVICE_CONNECTED_ACTION    =
        "android.bluetooth.intent.action.REMOTE_DEVICE_CONNECTED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_DEVICE_DISCONNECT_REQUESTED_ACTION =
        "android.bluetooth.intent.action.REMOTE_DEVICE_DISCONNECT_REQUESTED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_DEVICE_DISCONNECTED_ACTION =
        "android.bluetooth.intent.action.REMOTE_DEVICE_DISCONNECTED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_NAME_UPDATED_ACTION        =
        "android.bluetooth.intent.action.REMOTE_NAME_UPDATED";
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String REMOTE_NAME_FAILED_ACTION         =
        "android.bluetooth.intent.action.REMOTE_NAME_FAILED";

    /**
     * Broadcast when the bond state of a remote device changes.
     * Has string extra ADDRESS and int extras BOND_STATE and
     * BOND_PREVIOUS_STATE.
     * If BOND_STATE is BluetoothDevice.BOND_NOT_BONDED then will
     * also have an int extra REASON with a value of:
     * BluetoothDevice.BOND_RESULT_*
     * */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String BOND_STATE_CHANGED_ACTION      =
        "android.bluetooth.intent.action.BOND_STATE_CHANGED_ACTION";

    /**
     * TODO(API release): Move into BluetoothHeadset
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String HEADSET_STATE_CHANGED_ACTION      =
        "android.bluetooth.intent.action.HEADSET_STATE_CHANGED";

    /**
     * TODO(API release): Consider incorporating as new state in
     * HEADSET_STATE_CHANGED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String HEADSET_AUDIO_STATE_CHANGED_ACTION =
        "android.bluetooth.intent.action.HEADSET_ADUIO_STATE_CHANGED";
}
