/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Handler;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.UUID;

/**
 * Represents a remote Bluetooth device. A {@link BluetoothDevice} lets you
 * create a connection with the respective device or query information about
 * it, such as the name, address, class, and bonding state.
 *
 * <p>This class is really just a thin wrapper for a Bluetooth hardware
 * address. Objects of this class are immutable. Operations on this class
 * are performed on the remote Bluetooth hardware address, using the
 * {@link BluetoothAdapter} that was used to create this {@link
 * BluetoothDevice}.
 *
 * <p>To get a {@link BluetoothDevice}, use
 * {@link BluetoothAdapter#getRemoteDevice(String)
 * BluetoothAdapter.getRemoteDevice(String)} to create one representing a device
 * of a known MAC address (which you can get through device discovery with
 * {@link BluetoothAdapter}) or get one from the set of bonded devices
 * returned by {@link BluetoothAdapter#getBondedDevices()
 * BluetoothAdapter.getBondedDevices()}. You can then open a
 * {@link BluetoothSocket} for communication with the remote device, using
 * {@link #createRfcommSocketToServiceRecord(UUID)}.
 *
 * <p class="note"><strong>Note:</strong>
 * Requires the {@link android.Manifest.permission#BLUETOOTH} permission.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>
 * For more information about using Bluetooth, read the <a href=
 * "{@docRoot}guide/topics/connectivity/bluetooth.html">Bluetooth</a> developer
 * guide.
 * </p>
 * </div>
 *
 * {@see BluetoothAdapter}
 * {@see BluetoothSocket}
 */
public final class BluetoothDevice implements Parcelable {
    private static final String TAG = "BluetoothDevice";
    private static final boolean DBG = false;

    /**
     * Connection state bitmask as returned by getConnectionState.
     */
    private static final int CONNECTION_STATE_DISCONNECTED = 0;
    private static final int CONNECTION_STATE_CONNECTED = 1;
    private static final int CONNECTION_STATE_ENCRYPTED_BREDR = 2;
    private static final int CONNECTION_STATE_ENCRYPTED_LE = 4;

    /**
     * Sentinel error value for this class. Guaranteed to not equal any other
     * integer constant in this class. Provided as a convenience for functions
     * that require a sentinel error value, for example:
     * <p><code>Intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
     * BluetoothDevice.ERROR)</code>
     */
    public static final int ERROR = Integer.MIN_VALUE;

    /**
     * Broadcast Action: Remote device discovered.
     * <p>Sent when a remote device is found during discovery.
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link
     * #EXTRA_CLASS}. Can contain the extra fields {@link #EXTRA_NAME} and/or
     * {@link #EXTRA_RSSI} if they are available.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} and
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} to receive.
     */
    // TODO: Change API to not broadcast RSSI if not available (incoming connection)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_FOUND =
            "android.bluetooth.device.action.FOUND";

    /**
     * Broadcast Action: Bluetooth class of a remote device has changed.
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link
     * #EXTRA_CLASS}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     * {@see BluetoothClass}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CLASS_CHANGED =
            "android.bluetooth.device.action.CLASS_CHANGED";

    /**
     * Broadcast Action: Indicates a low level (ACL) connection has been
     * established with a remote device.
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     * <p>ACL connections are managed automatically by the Android Bluetooth
     * stack.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACL_CONNECTED =
            "android.bluetooth.device.action.ACL_CONNECTED";

    /**
     * Broadcast Action: Indicates that a low level (ACL) disconnection has
     * been requested for a remote device, and it will soon be disconnected.
     * <p>This is useful for graceful disconnection. Applications should use
     * this intent as a hint to immediately terminate higher level connections
     * (RFCOMM, L2CAP, or profile connections) to the remote device.
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACL_DISCONNECT_REQUESTED =
            "android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED";

    /**
     * Broadcast Action: Indicates a low level (ACL) disconnection from a
     * remote device.
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     * <p>ACL connections are managed automatically by the Android Bluetooth
     * stack.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ACL_DISCONNECTED =
            "android.bluetooth.device.action.ACL_DISCONNECTED";

    /**
     * Broadcast Action: Indicates the friendly name of a remote device has
     * been retrieved for the first time, or changed since the last retrieval.
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link
     * #EXTRA_NAME}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NAME_CHANGED =
            "android.bluetooth.device.action.NAME_CHANGED";

    /**
     * Broadcast Action: Indicates the alias of a remote device has been
     * changed.
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage
    public static final String ACTION_ALIAS_CHANGED =
            "android.bluetooth.device.action.ALIAS_CHANGED";

    /**
     * Broadcast Action: Indicates a change in the bond state of a remote
     * device. For example, if a device is bonded (paired).
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE}, {@link
     * #EXTRA_BOND_STATE} and {@link #EXTRA_PREVIOUS_BOND_STATE}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    // Note: When EXTRA_BOND_STATE is BOND_NONE then this will also
    // contain a hidden extra field EXTRA_REASON with the result code.
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BOND_STATE_CHANGED =
            "android.bluetooth.device.action.BOND_STATE_CHANGED";

    /**
     * Broadcast Action: Indicates the battery level of a remote device has
     * been retrieved for the first time, or changed since the last retrieval
     * <p>Always contains the extra fields {@link #EXTRA_DEVICE} and {@link
     * #EXTRA_BATTERY_LEVEL}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";

    /**
     * Used as an Integer extra field in {@link #ACTION_BATTERY_LEVEL_CHANGED}
     * intent. It contains the most recently retrieved battery level information
     * ranging from 0% to 100% for a remote device, {@link #BATTERY_LEVEL_UNKNOWN}
     * when the valid is unknown or there is an error
     *
     * @hide
     */
    public static final String EXTRA_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL";

    /**
     * Used as the unknown value for {@link #EXTRA_BATTERY_LEVEL} and {@link #getBatteryLevel()}
     *
     * @hide
     */
    public static final int BATTERY_LEVEL_UNKNOWN = -1;

    /**
     * Used as a Parcelable {@link BluetoothDevice} extra field in every intent
     * broadcast by this class. It contains the {@link BluetoothDevice} that
     * the intent applies to.
     */
    public static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";

    /**
     * Used as a String extra field in {@link #ACTION_NAME_CHANGED} and {@link
     * #ACTION_FOUND} intents. It contains the friendly Bluetooth name.
     */
    public static final String EXTRA_NAME = "android.bluetooth.device.extra.NAME";

    /**
     * Used as an optional short extra field in {@link #ACTION_FOUND} intents.
     * Contains the RSSI value of the remote device as reported by the
     * Bluetooth hardware.
     */
    public static final String EXTRA_RSSI = "android.bluetooth.device.extra.RSSI";

    /**
     * Used as a Parcelable {@link BluetoothClass} extra field in {@link
     * #ACTION_FOUND} and {@link #ACTION_CLASS_CHANGED} intents.
     */
    public static final String EXTRA_CLASS = "android.bluetooth.device.extra.CLASS";

    /**
     * Used as an int extra field in {@link #ACTION_BOND_STATE_CHANGED} intents.
     * Contains the bond state of the remote device.
     * <p>Possible values are:
     * {@link #BOND_NONE},
     * {@link #BOND_BONDING},
     * {@link #BOND_BONDED}.
     */
    public static final String EXTRA_BOND_STATE = "android.bluetooth.device.extra.BOND_STATE";
    /**
     * Used as an int extra field in {@link #ACTION_BOND_STATE_CHANGED} intents.
     * Contains the previous bond state of the remote device.
     * <p>Possible values are:
     * {@link #BOND_NONE},
     * {@link #BOND_BONDING},
     * {@link #BOND_BONDED}.
     */
    public static final String EXTRA_PREVIOUS_BOND_STATE =
            "android.bluetooth.device.extra.PREVIOUS_BOND_STATE";
    /**
     * Indicates the remote device is not bonded (paired).
     * <p>There is no shared link key with the remote device, so communication
     * (if it is allowed at all) will be unauthenticated and unencrypted.
     */
    public static final int BOND_NONE = 10;
    /**
     * Indicates bonding (pairing) is in progress with the remote device.
     */
    public static final int BOND_BONDING = 11;
    /**
     * Indicates the remote device is bonded (paired).
     * <p>A shared link keys exists locally for the remote device, so
     * communication can be authenticated and encrypted.
     * <p><i>Being bonded (paired) with a remote device does not necessarily
     * mean the device is currently connected. It just means that the pending
     * procedure was completed at some earlier time, and the link key is still
     * stored locally, ready to use on the next connection.
     * </i>
     */
    public static final int BOND_BONDED = 12;

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST}
     * intents for unbond reason.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final String EXTRA_REASON = "android.bluetooth.device.extra.REASON";

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST}
     * intents to indicate pairing method used. Possible values are:
     * {@link #PAIRING_VARIANT_PIN},
     * {@link #PAIRING_VARIANT_PASSKEY_CONFIRMATION},
     */
    public static final String EXTRA_PAIRING_VARIANT =
            "android.bluetooth.device.extra.PAIRING_VARIANT";

    /**
     * Used as an int extra field in {@link #ACTION_PAIRING_REQUEST}
     * intents as the value of passkey.
     */
    public static final String EXTRA_PAIRING_KEY = "android.bluetooth.device.extra.PAIRING_KEY";

    /**
     * Bluetooth device type, Unknown
     */
    public static final int DEVICE_TYPE_UNKNOWN = 0;

    /**
     * Bluetooth device type, Classic - BR/EDR devices
     */
    public static final int DEVICE_TYPE_CLASSIC = 1;

    /**
     * Bluetooth device type, Low Energy - LE-only
     */
    public static final int DEVICE_TYPE_LE = 2;

    /**
     * Bluetooth device type, Dual Mode - BR/EDR/LE
     */
    public static final int DEVICE_TYPE_DUAL = 3;


    /** @hide */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage
    public static final String ACTION_SDP_RECORD =
            "android.bluetooth.device.action.SDP_RECORD";

    /**
     * Maximum length of a metadata entry, this is to avoid exploding Bluetooth
     * disk usage
     * @hide
     */
    @SystemApi
    public static final int METADATA_MAX_LENGTH = 2048;

    /**
     * Manufacturer name of this Bluetooth device
     * @hide
     */
    @SystemApi
    public static final int METADATA_MANUFACTURER_NAME = 0;

    /**
     * Model name of this Bluetooth device
     * @hide
     */
    @SystemApi
    public static final int METADATA_MODEL_NAME = 1;

    /**
     * Software version of this Bluetooth device
     * @hide
     */
    @SystemApi
    public static final int METADATA_SOFTWARE_VERSION = 2;

    /**
     * Hardware version of this Bluetooth device
     * @hide
     */
    @SystemApi
    public static final int METADATA_HARDWARE_VERSION = 3;

    /**
     * Package name of the companion app, if any
     * @hide
     */
    @SystemApi
    public static final int METADATA_COMPANION_APP = 4;

    /**
     * URI to the main icon shown on the settings UI
     * @hide
     */
    @SystemApi
    public static final int METADATA_MAIN_ICON = 5;

    /**
     * Whether this device is an untethered headset with left, right and case
     * @hide
     */
    @SystemApi
    public static final int METADATA_IS_UNTHETHERED_HEADSET = 6;

    /**
     * URI to icon of the left headset
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_LEFT_ICON = 7;

    /**
     * URI to icon of the right headset
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_RIGHT_ICON = 8;

    /**
     * URI to icon of the headset charging case
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_CASE_ICON = 9;

    /**
     * Battery level (0-100), {@link BluetoothDevice#BATTERY_LEVEL_UNKNOWN}
     * is invalid, of the left headset
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_LEFT_BATTERY = 10;

    /**
     * Battery level (0-100), {@link BluetoothDevice#BATTERY_LEVEL_UNKNOWN}
     * is invalid, of the right headset
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_RIGHT_BATTERY = 11;

    /**
     * Battery level (0-100), {@link BluetoothDevice#BATTERY_LEVEL_UNKNOWN}
     * is invalid, of the headset charging case
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_CASE_BATTERY = 12;

    /**
     * Whether the left headset is charging
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_LEFT_CHARGING = 13;

    /**
     * Whether the right headset is charging
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_RIGHT_CHARGING = 14;

    /**
     * Whether the headset charging case is charging
     * @hide
     */
    @SystemApi
    public static final int METADATA_UNTHETHERED_CASE_CHARGING = 15;

    /**
     * URI to the enhanced settings UI slice, null or empty String means
     * the UI does not exist
     * @hide
     */
    @SystemApi
    public static final int METADATA_ENHANCED_SETTINGS_UI_URI = 16;

    /**
     * Broadcast Action: This intent is used to broadcast the {@link UUID}
     * wrapped as a {@link android.os.ParcelUuid} of the remote device after it
     * has been fetched. This intent is sent only when the UUIDs of the remote
     * device are requested to be fetched using Service Discovery Protocol
     * <p> Always contains the extra field {@link #EXTRA_DEVICE}
     * <p> Always contains the extra field {@link #EXTRA_UUID}
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_UUID =
            "android.bluetooth.device.action.UUID";

    /** @hide */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MAS_INSTANCE =
            "android.bluetooth.device.action.MAS_INSTANCE";

    /**
     * Broadcast Action: Indicates a failure to retrieve the name of a remote
     * device.
     * <p>Always contains the extra field {@link #EXTRA_DEVICE}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     *
     * @hide
     */
    //TODO: is this actually useful?
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NAME_FAILED =
            "android.bluetooth.device.action.NAME_FAILED";

    /**
     * Broadcast Action: This intent is used to broadcast PAIRING REQUEST
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN} to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PAIRING_REQUEST =
            "android.bluetooth.device.action.PAIRING_REQUEST";
    /** @hide */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @UnsupportedAppUsage
    public static final String ACTION_PAIRING_CANCEL =
            "android.bluetooth.device.action.PAIRING_CANCEL";

    /** @hide */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_ACCESS_REQUEST =
            "android.bluetooth.device.action.CONNECTION_ACCESS_REQUEST";

    /** @hide */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_ACCESS_REPLY =
            "android.bluetooth.device.action.CONNECTION_ACCESS_REPLY";

    /** @hide */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_ACCESS_CANCEL =
            "android.bluetooth.device.action.CONNECTION_ACCESS_CANCEL";

    /**
     * Intent to broadcast silence mode changed.
     * Alway contains the extra field {@link #EXTRA_DEVICE}
     * Alway contains the extra field {@link #EXTRA_SILENCE_ENABLED}
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_SILENCE_MODE_CHANGED =
            "android.bluetooth.device.action.SILENCE_MODE_CHANGED";

    /**
     * Used as an extra field in {@link #ACTION_SILENCE_MODE_CHANGED} intent,
     * contains whether device is in silence mode as boolean.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SILENCE_ENABLED =
            "android.bluetooth.device.extra.SILENCE_ENABLED";

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REQUEST} intent.
     *
     * @hide
     */
    public static final String EXTRA_ACCESS_REQUEST_TYPE =
            "android.bluetooth.device.extra.ACCESS_REQUEST_TYPE";

    /** @hide */
    public static final int REQUEST_TYPE_PROFILE_CONNECTION = 1;

    /** @hide */
    public static final int REQUEST_TYPE_PHONEBOOK_ACCESS = 2;

    /** @hide */
    public static final int REQUEST_TYPE_MESSAGE_ACCESS = 3;

    /** @hide */
    public static final int REQUEST_TYPE_SIM_ACCESS = 4;

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REQUEST} intents,
     * Contains package name to return reply intent to.
     *
     * @hide
     */
    public static final String EXTRA_PACKAGE_NAME = "android.bluetooth.device.extra.PACKAGE_NAME";

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REQUEST} intents,
     * Contains class name to return reply intent to.
     *
     * @hide
     */
    public static final String EXTRA_CLASS_NAME = "android.bluetooth.device.extra.CLASS_NAME";

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REPLY} intent.
     *
     * @hide
     */
    public static final String EXTRA_CONNECTION_ACCESS_RESULT =
            "android.bluetooth.device.extra.CONNECTION_ACCESS_RESULT";

    /** @hide */
    public static final int CONNECTION_ACCESS_YES = 1;

    /** @hide */
    public static final int CONNECTION_ACCESS_NO = 2;

    /**
     * Used as an extra field in {@link #ACTION_CONNECTION_ACCESS_REPLY} intents,
     * Contains boolean to indicate if the allowed response is once-for-all so that
     * next request will be granted without asking user again.
     *
     * @hide
     */
    public static final String EXTRA_ALWAYS_ALLOWED =
            "android.bluetooth.device.extra.ALWAYS_ALLOWED";

    /**
     * A bond attempt succeeded
     *
     * @hide
     */
    public static final int BOND_SUCCESS = 0;

    /**
     * A bond attempt failed because pins did not match, or remote device did
     * not respond to pin request in time
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_AUTH_FAILED = 1;

    /**
     * A bond attempt failed because the other side explicitly rejected
     * bonding
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_AUTH_REJECTED = 2;

    /**
     * A bond attempt failed because we canceled the bonding process
     *
     * @hide
     */
    public static final int UNBOND_REASON_AUTH_CANCELED = 3;

    /**
     * A bond attempt failed because we could not contact the remote device
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_REMOTE_DEVICE_DOWN = 4;

    /**
     * A bond attempt failed because a discovery is in progress
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_DISCOVERY_IN_PROGRESS = 5;

    /**
     * A bond attempt failed because of authentication timeout
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_AUTH_TIMEOUT = 6;

    /**
     * A bond attempt failed because of repeated attempts
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_REPEATED_ATTEMPTS = 7;

    /**
     * A bond attempt failed because we received an Authentication Cancel
     * by remote end
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int UNBOND_REASON_REMOTE_AUTH_CANCELED = 8;

    /**
     * An existing bond was explicitly revoked
     *
     * @hide
     */
    public static final int UNBOND_REASON_REMOVED = 9;

    /**
     * The user will be prompted to enter a pin or
     * an app will enter a pin for user.
     */
    public static final int PAIRING_VARIANT_PIN = 0;

    /**
     * The user will be prompted to enter a passkey
     *
     * @hide
     */
    public static final int PAIRING_VARIANT_PASSKEY = 1;

    /**
     * The user will be prompted to confirm the passkey displayed on the screen or
     * an app will confirm the passkey for the user.
     */
    public static final int PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2;

    /**
     * The user will be prompted to accept or deny the incoming pairing request
     *
     * @hide
     */
    public static final int PAIRING_VARIANT_CONSENT = 3;

    /**
     * The user will be prompted to enter the passkey displayed on remote device
     * This is used for Bluetooth 2.1 pairing.
     *
     * @hide
     */
    public static final int PAIRING_VARIANT_DISPLAY_PASSKEY = 4;

    /**
     * The user will be prompted to enter the PIN displayed on remote device.
     * This is used for Bluetooth 2.0 pairing.
     *
     * @hide
     */
    public static final int PAIRING_VARIANT_DISPLAY_PIN = 5;

    /**
     * The user will be prompted to accept or deny the OOB pairing request
     *
     * @hide
     */
    public static final int PAIRING_VARIANT_OOB_CONSENT = 6;

    /**
     * The user will be prompted to enter a 16 digit pin or
     * an app will enter a 16 digit pin for user.
     *
     * @hide
     */
    public static final int PAIRING_VARIANT_PIN_16_DIGITS = 7;

    /**
     * Used as an extra field in {@link #ACTION_UUID} intents,
     * Contains the {@link android.os.ParcelUuid}s of the remote device which
     * is a parcelable version of {@link UUID}.
     */
    public static final String EXTRA_UUID = "android.bluetooth.device.extra.UUID";

    /** @hide */
    public static final String EXTRA_SDP_RECORD =
            "android.bluetooth.device.extra.SDP_RECORD";

    /** @hide */
    @UnsupportedAppUsage
    public static final String EXTRA_SDP_SEARCH_STATUS =
            "android.bluetooth.device.extra.SDP_SEARCH_STATUS";
    /**
     * For {@link #getPhonebookAccessPermission}, {@link #setPhonebookAccessPermission},
     * {@link #getMessageAccessPermission} and {@link #setMessageAccessPermission}.
     *
     * @hide
     */
    @SystemApi
    public static final int ACCESS_UNKNOWN = 0;

    /**
     * For {@link #getPhonebookAccessPermission}, {@link #setPhonebookAccessPermission},
     * {@link #getMessageAccessPermission} and {@link #setMessageAccessPermission}.
     *
     * @hide
     */
    @SystemApi
    public static final int ACCESS_ALLOWED = 1;

    /**
     * For {@link #getPhonebookAccessPermission}, {@link #setPhonebookAccessPermission},
     * {@link #getMessageAccessPermission} and {@link #setMessageAccessPermission}.
     *
     * @hide
     */
    @SystemApi
    public static final int ACCESS_REJECTED = 2;

    /**
     * No preference of physical transport for GATT connections to remote dual-mode devices
     */
    public static final int TRANSPORT_AUTO = 0;

    /**
     * Prefer BR/EDR transport for GATT connections to remote dual-mode devices
     */
    public static final int TRANSPORT_BREDR = 1;

    /**
     * Prefer LE transport for GATT connections to remote dual-mode devices
     */
    public static final int TRANSPORT_LE = 2;

    /**
     * Bluetooth LE 1M PHY. Used to refer to LE 1M Physical Channel for advertising, scanning or
     * connection.
     */
    public static final int PHY_LE_1M = 1;

    /**
     * Bluetooth LE 2M PHY. Used to refer to LE 2M Physical Channel for advertising, scanning or
     * connection.
     */
    public static final int PHY_LE_2M = 2;

    /**
     * Bluetooth LE Coded PHY. Used to refer to LE Coded Physical Channel for advertising, scanning
     * or connection.
     */
    public static final int PHY_LE_CODED = 3;

    /**
     * Bluetooth LE 1M PHY mask. Used to specify LE 1M Physical Channel as one of many available
     * options in a bitmask.
     */
    public static final int PHY_LE_1M_MASK = 1;

    /**
     * Bluetooth LE 2M PHY mask. Used to specify LE 2M Physical Channel as one of many available
     * options in a bitmask.
     */
    public static final int PHY_LE_2M_MASK = 2;

    /**
     * Bluetooth LE Coded PHY mask. Used to specify LE Coded Physical Channel as one of many
     * available options in a bitmask.
     */
    public static final int PHY_LE_CODED_MASK = 4;

    /**
     * No preferred coding when transmitting on the LE Coded PHY.
     */
    public static final int PHY_OPTION_NO_PREFERRED = 0;

    /**
     * Prefer the S=2 coding to be used when transmitting on the LE Coded PHY.
     */
    public static final int PHY_OPTION_S2 = 1;

    /**
     * Prefer the S=8 coding to be used when transmitting on the LE Coded PHY.
     */
    public static final int PHY_OPTION_S8 = 2;


    /** @hide */
    public static final String EXTRA_MAS_INSTANCE =
            "android.bluetooth.device.extra.MAS_INSTANCE";

    /**
     * Lazy initialization. Guaranteed final after first object constructed, or
     * getService() called.
     * TODO: Unify implementation of sService amongst BluetoothFoo API's
     */
    private static volatile IBluetooth sService;

    private final String mAddress;

    /*package*/
    @UnsupportedAppUsage
    static IBluetooth getService() {
        synchronized (BluetoothDevice.class) {
            if (sService == null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                sService = adapter.getBluetoothService(sStateChangeCallback);
            }
        }
        return sService;
    }

    static IBluetoothManagerCallback sStateChangeCallback = new IBluetoothManagerCallback.Stub() {

        public void onBluetoothServiceUp(IBluetooth bluetoothService)
                throws RemoteException {
            synchronized (BluetoothDevice.class) {
                if (sService == null) {
                    sService = bluetoothService;
                }
            }
        }

        public void onBluetoothServiceDown()
                throws RemoteException {
            synchronized (BluetoothDevice.class) {
                sService = null;
            }
        }

        public void onBrEdrDown() {
            if (DBG) Log.d(TAG, "onBrEdrDown: reached BLE ON state");
        }
    };

    /**
     * Create a new BluetoothDevice
     * Bluetooth MAC address must be upper case, such as "00:11:22:33:AA:BB",
     * and is validated in this constructor.
     *
     * @param address valid Bluetooth MAC address
     * @throws RuntimeException Bluetooth is not available on this platform
     * @throws IllegalArgumentException address is invalid
     * @hide
     */
    @UnsupportedAppUsage
    /*package*/ BluetoothDevice(String address) {
        getService();  // ensures sService is initialized
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException(address + " is not a valid Bluetooth address");
        }

        mAddress = address;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothDevice) {
            return mAddress.equals(((BluetoothDevice) o).getAddress());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mAddress.hashCode();
    }

    /**
     * Returns a string representation of this BluetoothDevice.
     * <p>Currently this is the Bluetooth hardware address, for example
     * "00:11:22:AA:BB:CC". However, you should always use {@link #getAddress}
     * if you explicitly require the Bluetooth hardware address in case the
     * {@link #toString} representation changes in the future.
     *
     * @return string representation of this BluetoothDevice
     */
    @Override
    public String toString() {
        return mAddress;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothDevice> CREATOR =
            new Parcelable.Creator<BluetoothDevice>() {
                public BluetoothDevice createFromParcel(Parcel in) {
                    return new BluetoothDevice(in.readString());
                }

                public BluetoothDevice[] newArray(int size) {
                    return new BluetoothDevice[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAddress);
    }

    /**
     * Returns the hardware address of this BluetoothDevice.
     * <p> For example, "00:11:22:AA:BB:CC".
     *
     * @return Bluetooth hardware address as string
     */
    public String getAddress() {
        if (DBG) Log.d(TAG, "mAddress: " + mAddress);
        return mAddress;
    }

    /**
     * Get the friendly Bluetooth name of the remote device.
     *
     * <p>The local adapter will automatically retrieve remote names when
     * performing a device scan, and will cache them. This method just returns
     * the name for this device from the cache.
     *
     * @return the Bluetooth name, or null if there was a problem.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public String getName() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot get Remote Device name");
            return null;
        }
        try {
            String name = service.getRemoteName(this);
            if (name != null) {
                return name.replaceAll("[\\t\\n\\r]+", " ");
            }
            return null;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return null;
    }

    /**
     * Get the Bluetooth device type of the remote device.
     *
     * @return the device type {@link #DEVICE_TYPE_CLASSIC}, {@link #DEVICE_TYPE_LE} {@link
     * #DEVICE_TYPE_DUAL}. {@link #DEVICE_TYPE_UNKNOWN} if it's not available
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getType() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot get Remote Device type");
            return DEVICE_TYPE_UNKNOWN;
        }
        try {
            return service.getRemoteType(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return DEVICE_TYPE_UNKNOWN;
    }

    /**
     * Get the Bluetooth alias of the remote device.
     * <p>Alias is the locally modified name of a remote device.
     *
     * @return the Bluetooth alias, or null if no alias or there was a problem
     * @hide
     */
    @UnsupportedAppUsage
    public String getAlias() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot get Remote Device Alias");
            return null;
        }
        try {
            return service.getRemoteAlias(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return null;
    }

    /**
     * Set the Bluetooth alias of the remote device.
     * <p>Alias is the locally modified name of a remote device.
     * <p>This methoid overwrites the alias. The changed
     * alias is saved in the local storage so that the change
     * is preserved over power cycle.
     *
     * @return true on success, false on error
     * @hide
     */
    @UnsupportedAppUsage
    public boolean setAlias(String alias) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot set Remote Device name");
            return false;
        }
        try {
            return service.setRemoteAlias(this, alias);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Get the Bluetooth alias of the remote device.
     * If Alias is null, get the Bluetooth name instead.
     *
     * @return the Bluetooth alias, or null if no alias or there was a problem
     * @hide
     * @see #getAlias()
     * @see #getName()
     */
    @UnsupportedAppUsage
    public String getAliasName() {
        String name = getAlias();
        if (name == null) {
            name = getName();
        }
        return name;
    }

    /**
     * Get the most recent identified battery level of this Bluetooth device
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return Battery level in percents from 0 to 100, or {@link #BATTERY_LEVEL_UNKNOWN} if
     * Bluetooth is disabled, or device is disconnected, or does not have any battery reporting
     * service, or return value is invalid
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @UnsupportedAppUsage
    public int getBatteryLevel() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "Bluetooth disabled. Cannot get remote device battery level");
            return BATTERY_LEVEL_UNKNOWN;
        }
        try {
            return service.getBatteryLevel(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return BATTERY_LEVEL_UNKNOWN;
    }

    /**
     * Start the bonding (pairing) process with the remote device.
     * <p>This is an asynchronous call, it will return immediately. Register
     * for {@link #ACTION_BOND_STATE_CHANGED} intents to be notified when
     * the bonding process completes, and its result.
     * <p>Android system services will handle the necessary user interactions
     * to confirm and complete the bonding process.
     *
     * @return false on immediate error, true if bonding will begin
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
    public boolean createBond() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot create bond to Remote Device");
            return false;
        }
        try {
            Log.i(TAG, "createBond() for device " + getAddress()
                    + " called by pid: " + Process.myPid()
                    + " tid: " + Process.myTid());
            return service.createBond(this, TRANSPORT_AUTO);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Start the bonding (pairing) process with the remote device using the
     * specified transport.
     *
     * <p>This is an asynchronous call, it will return immediately. Register
     * for {@link #ACTION_BOND_STATE_CHANGED} intents to be notified when
     * the bonding process completes, and its result.
     * <p>Android system services will handle the necessary user interactions
     * to confirm and complete the bonding process.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @param transport The transport to use for the pairing procedure.
     * @return false on immediate error, true if bonding will begin
     * @throws IllegalArgumentException if an invalid transport was specified
     * @hide
     */
    @UnsupportedAppUsage
    public boolean createBond(int transport) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot create bond to Remote Device");
            return false;
        }
        if (TRANSPORT_AUTO > transport || transport > TRANSPORT_LE) {
            throw new IllegalArgumentException(transport + " is not a valid Bluetooth transport");
        }
        try {
            Log.i(TAG, "createBond() for device " + getAddress()
                    + " called by pid: " + Process.myPid()
                    + " tid: " + Process.myTid());
            return service.createBond(this, transport);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Start the bonding (pairing) process with the remote device using the
     * Out Of Band mechanism.
     *
     * <p>This is an asynchronous call, it will return immediately. Register
     * for {@link #ACTION_BOND_STATE_CHANGED} intents to be notified when
     * the bonding process completes, and its result.
     *
     * <p>Android system services will handle the necessary user interactions
     * to confirm and complete the bonding process.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @param transport - Transport to use
     * @param oobData - Out Of Band data
     * @return false on immediate error, true if bonding will begin
     * @hide
     */
    public boolean createBondOutOfBand(int transport, OobData oobData) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.w(TAG, "BT not enabled, createBondOutOfBand failed");
            return false;
        }
        try {
            return service.createBondOutOfBand(this, transport, oobData);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean isBondingInitiatedLocally() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.w(TAG, "BT not enabled, isBondingInitiatedLocally failed");
            return false;
        }
        try {
            return service.isBondingInitiatedLocally(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Set the Out Of Band data for a remote device to be used later
     * in the pairing mechanism. Users can obtain this data through other
     * trusted channels
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @param hash Simple Secure pairing hash
     * @param randomizer The random key obtained using OOB
     * @return false on error; true otherwise
     * @hide
     */
    public boolean setDeviceOutOfBandData(byte[] hash, byte[] randomizer) {
        //TODO(BT)
      /*
      try {
        return sService.setDeviceOutOfBandData(this, hash, randomizer);
      } catch (RemoteException e) {Log.e(TAG, "", e);} */
        return false;
    }

    /**
     * Cancel an in-progress bonding request started with {@link #createBond}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @return true on success, false on error
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
    public boolean cancelBondProcess() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot cancel Remote Device bond");
            return false;
        }
        try {
            Log.i(TAG, "cancelBondProcess() for device " + getAddress()
                    + " called by pid: " + Process.myPid()
                    + " tid: " + Process.myTid());
            return service.cancelBondProcess(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Remove bond (pairing) with the remote device.
     * <p>Delete the link key associated with the remote device, and
     * immediately terminate connections to that device that require
     * authentication and encryption.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @return true on success, false on error
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADMIN)
    public boolean removeBond() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot remove Remote Device bond");
            return false;
        }
        try {
            Log.i(TAG, "removeBond() for device " + getAddress()
                    + " called by pid: " + Process.myPid()
                    + " tid: " + Process.myTid());
            return service.removeBond(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Get the bond state of the remote device.
     * <p>Possible values for the bond state are:
     * {@link #BOND_NONE},
     * {@link #BOND_BONDING},
     * {@link #BOND_BONDED}.
     *
     * @return the bond state
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public int getBondState() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot get bond state");
            return BOND_NONE;
        }
        try {
            return service.getBondState(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return BOND_NONE;
    }

    /**
     * Returns whether there is an open connection to this device.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return True if there is at least one open connection to this device.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH)
    public boolean isConnected() {
        final IBluetooth service = sService;
        if (service == null) {
            // BT is not enabled, we cannot be connected.
            return false;
        }
        try {
            return service.getConnectionState(this) != CONNECTION_STATE_DISCONNECTED;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Returns whether there is an open connection to this device
     * that has been encrypted.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return True if there is at least one encrypted connection to this device.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH)
    public boolean isEncrypted() {
        final IBluetooth service = sService;
        if (service == null) {
            // BT is not enabled, we cannot be connected.
            return false;
        }
        try {
            return service.getConnectionState(this) > CONNECTION_STATE_CONNECTED;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Get the Bluetooth class of the remote device.
     *
     * @return Bluetooth class object, or null on error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothClass getBluetoothClass() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot get Bluetooth Class");
            return null;
        }
        try {
            int classInt = service.getRemoteClass(this);
            if (classInt == BluetoothClass.ERROR) return null;
            return new BluetoothClass(classInt);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return null;
    }

    /**
     * Returns the supported features (UUIDs) of the remote device.
     *
     * <p>This method does not start a service discovery procedure to retrieve the UUIDs
     * from the remote device. Instead, the local cached copy of the service
     * UUIDs are returned.
     * <p>Use {@link #fetchUuidsWithSdp} if fresh UUIDs are desired.
     *
     * @return the supported features (UUIDs) of the remote device, or null on error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public ParcelUuid[] getUuids() {
        final IBluetooth service = sService;
        if (service == null || !isBluetoothEnabled()) {
            Log.e(TAG, "BT not enabled. Cannot get remote device Uuids");
            return null;
        }
        try {
            return service.getRemoteUuids(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return null;
    }

    /**
     * Perform a service discovery on the remote device to get the UUIDs supported.
     *
     * <p>This API is asynchronous and {@link #ACTION_UUID} intent is sent,
     * with the UUIDs supported by the remote end. If there is an error
     * in getting the SDP records or if the process takes a long time,
     * {@link #ACTION_UUID} intent is sent with the UUIDs that is currently
     * present in the cache. Clients should use the {@link #getUuids} to get UUIDs
     * if service discovery is not to be performed.
     *
     * @return False if the sanity check fails, True if the process of initiating an ACL connection
     * to the remote device was started.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public boolean fetchUuidsWithSdp() {
        final IBluetooth service = sService;
        if (service == null || !isBluetoothEnabled()) {
            Log.e(TAG, "BT not enabled. Cannot fetchUuidsWithSdp");
            return false;
        }
        try {
            return service.fetchRemoteUuids(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Perform a service discovery on the remote device to get the SDP records associated
     * with the specified UUID.
     *
     * <p>This API is asynchronous and {@link #ACTION_SDP_RECORD} intent is sent,
     * with the SDP records found on the remote end. If there is an error
     * in getting the SDP records or if the process takes a long time,
     * {@link #ACTION_SDP_RECORD} intent is sent with an status value in
     * {@link #EXTRA_SDP_SEARCH_STATUS} different from 0.
     * Detailed status error codes can be found by members of the Bluetooth package in
     * the AbstractionLayer class.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}.
     * The SDP record data will be stored in the intent as {@link #EXTRA_SDP_RECORD}.
     * The object type will match one of the SdpXxxRecord types, depending on the UUID searched
     * for.
     *
     * @return False if the sanity check fails, True if the process
     *               of initiating an ACL connection to the remote device
     *               was started.
     */
    /** @hide */
    public boolean sdpSearch(ParcelUuid uuid) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot query remote device sdp records");
            return false;
        }
        try {
            return service.sdpSearch(this, uuid);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Set the pin during pairing when the pairing method is {@link #PAIRING_VARIANT_PIN}
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}.
     *
     * @return true pin has been set false for error
     */
    public boolean setPin(byte[] pin) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot set Remote Device pin");
            return false;
        }
        try {
            return service.setPin(this, true, pin.length, pin);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean setPasskey(int passkey) {
        //TODO(BT)
        /*
        try {
            return sService.setPasskey(this, true, 4, passkey);
        } catch (RemoteException e) {Log.e(TAG, "", e);}*/
        return false;
    }

    /**
     * Confirm passkey for {@link #PAIRING_VARIANT_PASSKEY_CONFIRMATION} pairing.
     *
     * @return true confirmation has been sent out false for error
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setPairingConfirmation(boolean confirm) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot set pairing confirmation");
            return false;
        }
        try {
            return service.setPairingConfirmation(this, confirm);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /** @hide */
    public boolean setRemoteOutOfBandData() {
        // TODO(BT)
        /*
        try {
          return sService.setRemoteOutOfBandData(this);
      } catch (RemoteException e) {Log.e(TAG, "", e);}*/
        return false;
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean cancelPairingUserInput() {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "BT not enabled. Cannot create pairing user input");
            return false;
        }
        try {
            return service.cancelBondProcess(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean isBluetoothDock() {
        // TODO(BT)
        /*
        try {
            return sService.isBluetoothDock(this);
        } catch (RemoteException e) {Log.e(TAG, "", e);}*/
        return false;
    }

    boolean isBluetoothEnabled() {
        boolean ret = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            ret = true;
        }
        return ret;
    }

    /**
     * Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return Whether the phonebook access is allowed to this device. Can be {@link
     * #ACCESS_UNKNOWN}, {@link #ACCESS_ALLOWED} or {@link #ACCESS_REJECTED}.
     * @hide
     */
    @UnsupportedAppUsage
    public int getPhonebookAccessPermission() {
        final IBluetooth service = sService;
        if (service == null) {
            return ACCESS_UNKNOWN;
        }
        try {
            return service.getPhonebookAccessPermission(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return ACCESS_UNKNOWN;
    }

    /**
     * Set the Bluetooth device silence mode.
     *
     * When the {@link BluetoothDevice} enters silence mode, and the {@link BluetoothDevice}
     * is an active device (for A2DP or HFP), the active device for that profile
     * will be set to null.
     * If the {@link BluetoothDevice} exits silence mode while the A2DP or HFP
     * active device is null, the {@link BluetoothDevice} will be set as the
     * active device for that profile.
     * If the {@link BluetoothDevice} is disconnected, it exits silence mode.
     * If the {@link BluetoothDevice} is set as the active device for A2DP or
     * HFP, while silence mode is enabled, then the device will exit silence mode.
     * If the {@link BluetoothDevice} is in silence mode, AVRCP position change
     * event and HFP AG indicators will be disabled.
     * If the {@link BluetoothDevice} is not connected with A2DP or HFP, it cannot
     * enter silence mode.
     *
     * <p> Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}.
     *
     * @param silence true to enter silence mode, false to exit
     * @return true on success, false on error.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setSilenceMode(boolean silence) {
        final IBluetooth service = sService;
        if (service == null) {
            return false;
        }
        try {
            if (getSilenceMode() == silence) {
                return true;
            }
            return service.setSilenceMode(this, silence);
        } catch (RemoteException e) {
            Log.e(TAG, "setSilenceMode fail", e);
            return false;
        }
    }

    /**
     * Get the device silence mode status
     *
     * <p> Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}.
     *
     * @return true on device in silence mode, otherwise false.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean getSilenceMode() {
        final IBluetooth service = sService;
        if (service == null) {
            return false;
        }
        try {
            return service.getSilenceMode(this);
        } catch (RemoteException e) {
            Log.e(TAG, "getSilenceMode fail", e);
            return false;
        }
    }

    /**
     * Sets whether the phonebook access is allowed to this device.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}.
     *
     * @param value Can be {@link #ACCESS_UNKNOWN}, {@link #ACCESS_ALLOWED} or {@link
     * #ACCESS_REJECTED}.
     * @return Whether the value has been successfully set.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setPhonebookAccessPermission(int value) {
        final IBluetooth service = sService;
        if (service == null) {
            return false;
        }
        try {
            return service.setPhonebookAccessPermission(this, value);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return Whether the message access is allowed to this device. Can be {@link #ACCESS_UNKNOWN},
     * {@link #ACCESS_ALLOWED} or {@link #ACCESS_REJECTED}.
     * @hide
     */
    @UnsupportedAppUsage
    public int getMessageAccessPermission() {
        final IBluetooth service = sService;
        if (service == null) {
            return ACCESS_UNKNOWN;
        }
        try {
            return service.getMessageAccessPermission(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return ACCESS_UNKNOWN;
    }

    /**
     * Sets whether the message access is allowed to this device.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}.
     *
     * @param value Can be {@link #ACCESS_UNKNOWN}, {@link #ACCESS_ALLOWED} or {@link
     * #ACCESS_REJECTED}.
     * @return Whether the value has been successfully set.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean setMessageAccessPermission(int value) {
        final IBluetooth service = sService;
        if (service == null) {
            return false;
        }
        try {
            return service.setMessageAccessPermission(this, value);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Requires {@link android.Manifest.permission#BLUETOOTH}.
     *
     * @return Whether the Sim access is allowed to this device. Can be {@link #ACCESS_UNKNOWN},
     * {@link #ACCESS_ALLOWED} or {@link #ACCESS_REJECTED}.
     * @hide
     */
    public int getSimAccessPermission() {
        final IBluetooth service = sService;
        if (service == null) {
            return ACCESS_UNKNOWN;
        }
        try {
            return service.getSimAccessPermission(this);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return ACCESS_UNKNOWN;
    }

    /**
     * Sets whether the Sim access is allowed to this device.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED}.
     *
     * @param value Can be {@link #ACCESS_UNKNOWN}, {@link #ACCESS_ALLOWED} or {@link
     * #ACCESS_REJECTED}.
     * @return Whether the value has been successfully set.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean setSimAccessPermission(int value) {
        final IBluetooth service = sService;
        if (service == null) {
            return false;
        }
        try {
            return service.setSimAccessPermission(this, value);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} ready to start a secure
     * outgoing connection to this remote device on given channel.
     * <p>The remote device will be authenticated and communication on this
     * socket will be encrypted.
     * <p> Use this socket only if an authenticated socket link is possible.
     * Authentication refers to the authentication of the link key to
     * prevent man-in-the-middle type of attacks.
     * For example, for Bluetooth 2.1 devices, if any of the devices does not
     * have an input and output capability or just has the ability to
     * display a numeric key, a secure socket connection is not possible.
     * In such a case, use {@link createInsecureRfcommSocket}.
     * For more details, refer to the Security Model section 5.2 (vol 3) of
     * Bluetooth Core Specification version 2.1 + EDR.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing
     * connection.
     * <p>Valid RFCOMM channels are in range 1 to 30.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param channel RFCOMM channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothSocket createRfcommSocket(int channel) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        return new BluetoothSocket(BluetoothSocket.TYPE_RFCOMM, -1, true, true, this, channel,
                null);
    }

    /**
     * Create an L2cap {@link BluetoothSocket} ready to start a secure
     * outgoing connection to this remote device on given channel.
     * <p>The remote device will be authenticated and communication on this
     * socket will be encrypted.
     * <p> Use this socket only if an authenticated socket link is possible.
     * Authentication refers to the authentication of the link key to
     * prevent man-in-the-middle type of attacks.
     * For example, for Bluetooth 2.1 devices, if any of the devices does not
     * have an input and output capability or just has the ability to
     * display a numeric key, a secure socket connection is not possible.
     * In such a case, use {@link createInsecureRfcommSocket}.
     * For more details, refer to the Security Model section 5.2 (vol 3) of
     * Bluetooth Core Specification version 2.1 + EDR.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing
     * connection.
     * <p>Valid L2CAP PSM channels are in range 1 to 2^16.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param channel L2cap PSM/channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     * @hide
     */
    public BluetoothSocket createL2capSocket(int channel) throws IOException {
        return new BluetoothSocket(BluetoothSocket.TYPE_L2CAP, -1, true, true, this, channel,
                null);
    }

    /**
     * Create an L2cap {@link BluetoothSocket} ready to start an insecure
     * outgoing connection to this remote device on given channel.
     * <p>The remote device will be not authenticated and communication on this
     * socket will not be encrypted.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing
     * connection.
     * <p>Valid L2CAP PSM channels are in range 1 to 2^16.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @param channel L2cap PSM/channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     * @hide
     */
    public BluetoothSocket createInsecureL2capSocket(int channel) throws IOException {
        return new BluetoothSocket(BluetoothSocket.TYPE_L2CAP, -1, false, false, this, channel,
                null);
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} ready to start a secure
     * outgoing connection to this remote device using SDP lookup of uuid.
     * <p>This is designed to be used with {@link
     * BluetoothAdapter#listenUsingRfcommWithServiceRecord} for peer-peer
     * Bluetooth applications.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing
     * connection. This will also perform an SDP lookup of the given uuid to
     * determine which channel to connect to.
     * <p>The remote device will be authenticated and communication on this
     * socket will be encrypted.
     * <p> Use this socket only if an authenticated socket link is possible.
     * Authentication refers to the authentication of the link key to
     * prevent man-in-the-middle type of attacks.
     * For example, for Bluetooth 2.1 devices, if any of the devices does not
     * have an input and output capability or just has the ability to
     * display a numeric key, a secure socket connection is not possible.
     * In such a case, use {@link #createInsecureRfcommSocketToServiceRecord}.
     * For more details, refer to the Security Model section 5.2 (vol 3) of
     * Bluetooth Core Specification version 2.1 + EDR.
     * <p>Hint: If you are connecting to a Bluetooth serial board then try
     * using the well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB.
     * However if you are connecting to an Android peer then please generate
     * your own unique UUID.
     *
     * @param uuid service record uuid to lookup RFCOMM channel
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothSocket createRfcommSocketToServiceRecord(UUID uuid) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }

        return new BluetoothSocket(BluetoothSocket.TYPE_RFCOMM, -1, true, true, this, -1,
                new ParcelUuid(uuid));
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} socket ready to start an insecure
     * outgoing connection to this remote device using SDP lookup of uuid.
     * <p> The communication channel will not have an authenticated link key
     * i.e it will be subject to man-in-the-middle attacks. For Bluetooth 2.1
     * devices, the link key will be encrypted, as encryption is mandatory.
     * For legacy devices (pre Bluetooth 2.1 devices) the link key will
     * be not be encrypted. Use {@link #createRfcommSocketToServiceRecord} if an
     * encrypted and authenticated communication channel is desired.
     * <p>This is designed to be used with {@link
     * BluetoothAdapter#listenUsingInsecureRfcommWithServiceRecord} for peer-peer
     * Bluetooth applications.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing
     * connection. This will also perform an SDP lookup of the given uuid to
     * determine which channel to connect to.
     * <p>The remote device will be authenticated and communication on this
     * socket will be encrypted.
     * <p>Hint: If you are connecting to a Bluetooth serial board then try
     * using the well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB.
     * However if you are connecting to an Android peer then please generate
     * your own unique UUID.
     *
     * @param uuid service record uuid to lookup RFCOMM channel
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothSocket createInsecureRfcommSocketToServiceRecord(UUID uuid) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        return new BluetoothSocket(BluetoothSocket.TYPE_RFCOMM, -1, false, false, this, -1,
                new ParcelUuid(uuid));
    }

    /**
     * Construct an insecure RFCOMM socket ready to start an outgoing
     * connection.
     * Call #connect on the returned #BluetoothSocket to begin the connection.
     * The remote device will not be authenticated and communication on this
     * socket will not be encrypted.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param port remote port
     * @return An RFCOMM BluetoothSocket
     * @throws IOException On error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothSocket createInsecureRfcommSocket(int port) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        return new BluetoothSocket(BluetoothSocket.TYPE_RFCOMM, -1, false, false, this, port,
                null);
    }

    /**
     * Construct a SCO socket ready to start an outgoing connection.
     * Call #connect on the returned #BluetoothSocket to begin the connection.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @return a SCO BluetoothSocket
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions.
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothSocket createScoSocket() throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            throw new IOException();
        }
        return new BluetoothSocket(BluetoothSocket.TYPE_SCO, -1, true, true, this, -1, null);
    }

    /**
     * Check that a pin is valid and convert to byte array.
     *
     * Bluetooth pin's are 1 to 16 bytes of UTF-8 characters.
     *
     * @param pin pin as java String
     * @return the pin code as a UTF-8 byte array, or null if it is an invalid Bluetooth pin.
     * @hide
     */
    @UnsupportedAppUsage
    public static byte[] convertPinToBytes(String pin) {
        if (pin == null) {
            return null;
        }
        byte[] pinBytes;
        try {
            pinBytes = pin.getBytes("UTF-8");
        } catch (UnsupportedEncodingException uee) {
            Log.e(TAG, "UTF-8 not supported?!?");  // this should not happen
            return null;
        }
        if (pinBytes.length <= 0 || pinBytes.length > 16) {
            return null;
        }
        return pinBytes;
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client.
     * The callback is used to deliver results to Caller, such as connection status as well
     * as any further GATT client operations.
     * The method returns a BluetoothGatt instance. You can use BluetoothGatt to conduct
     * GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     * automatically connect as soon as the remote device becomes available (true).
     * @throws IllegalArgumentException if callback is null
     */
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback) {
        return (connectGatt(context, autoConnect, callback, TRANSPORT_AUTO));
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client.
     * The callback is used to deliver results to Caller, such as connection status as well
     * as any further GATT client operations.
     * The method returns a BluetoothGatt instance. You can use BluetoothGatt to conduct
     * GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     * automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     * BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     * BluetoothDevice#TRANSPORT_LE}
     * @throws IllegalArgumentException if callback is null
     */
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport) {
        return (connectGatt(context, autoConnect, callback, transport, PHY_LE_1M_MASK));
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client.
     * The callback is used to deliver results to Caller, such as connection status as well
     * as any further GATT client operations.
     * The method returns a BluetoothGatt instance. You can use BluetoothGatt to conduct
     * GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     * automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     * BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     * BluetoothDevice#TRANSPORT_LE}
     * @param phy preferred PHY for connections to remote LE device. Bitwise OR of any of {@link
     * BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, and {@link
     * BluetoothDevice#PHY_LE_CODED_MASK}. This option does not take effect if {@code autoConnect}
     * is set to true.
     * @throws NullPointerException if callback is null
     */
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport, int phy) {
        return connectGatt(context, autoConnect, callback, transport, phy, null);
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client.
     * The callback is used to deliver results to Caller, such as connection status as well
     * as any further GATT client operations.
     * The method returns a BluetoothGatt instance. You can use BluetoothGatt to conduct
     * GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     * automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     * BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     * BluetoothDevice#TRANSPORT_LE}
     * @param phy preferred PHY for connections to remote LE device. Bitwise OR of any of {@link
     * BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, an d{@link
     * BluetoothDevice#PHY_LE_CODED_MASK}. This option does not take effect if {@code autoConnect}
     * is set to true.
     * @param handler The handler to use for the callback. If {@code null}, callbacks will happen on
     * an un-specified background thread.
     * @throws NullPointerException if callback is null
     */
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport, int phy,
            Handler handler) {
        return connectGatt(context, autoConnect, callback, transport, false, phy, handler);
    }

    /**
     * Connect to GATT Server hosted by this device. Caller acts as GATT client.
     * The callback is used to deliver results to Caller, such as connection status as well
     * as any further GATT client operations.
     * The method returns a BluetoothGatt instance. You can use BluetoothGatt to conduct
     * GATT client operations.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     * automatically connect as soon as the remote device becomes available (true).
     * @param transport preferred transport for GATT connections to remote dual-mode devices {@link
     * BluetoothDevice#TRANSPORT_AUTO} or {@link BluetoothDevice#TRANSPORT_BREDR} or {@link
     * BluetoothDevice#TRANSPORT_LE}
     * @param opportunistic Whether this GATT client is opportunistic. An opportunistic GATT client
     * does not hold a GATT connection. It automatically disconnects when no other GATT connections
     * are active for the remote device.
     * @param phy preferred PHY for connections to remote LE device. Bitwise OR of any of {@link
     * BluetoothDevice#PHY_LE_1M_MASK}, {@link BluetoothDevice#PHY_LE_2M_MASK}, an d{@link
     * BluetoothDevice#PHY_LE_CODED_MASK}. This option does not take effect if {@code autoConnect}
     * is set to true.
     * @param handler The handler to use for the callback. If {@code null}, callbacks will happen on
     * an un-specified background thread.
     * @return A BluetoothGatt instance. You can use BluetoothGatt to conduct GATT client
     * operations.
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothGatt connectGatt(Context context, boolean autoConnect,
            BluetoothGattCallback callback, int transport,
            boolean opportunistic, int phy, Handler handler) {
        if (callback == null) {
            throw new NullPointerException("callback is null");
        }

        // TODO(Bluetooth) check whether platform support BLE
        //     Do the check here or in GattServer?
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        IBluetoothManager managerService = adapter.getBluetoothManager();
        try {
            IBluetoothGatt iGatt = managerService.getBluetoothGatt();
            if (iGatt == null) {
                // BLE is not supported
                return null;
            }
            BluetoothGatt gatt = new BluetoothGatt(iGatt, this, transport, opportunistic, phy);
            gatt.connect(autoConnect, callback, handler);
            return gatt;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return null;
    }

    /**
     * Create a Bluetooth L2CAP Connection-oriented Channel (CoC) {@link BluetoothSocket} that can
     * be used to start a secure outgoing connection to the remote device with the same dynamic
     * protocol/service multiplexer (PSM) value. The supported Bluetooth transport is LE only.
     * <p>This is designed to be used with {@link BluetoothAdapter#listenUsingL2capChannel()} for
     * peer-peer Bluetooth applications.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     * <p>Application using this API is responsible for obtaining PSM value from remote device.
     * <p>The remote device will be authenticated and communication on this socket will be
     * encrypted.
     * <p> Use this socket if an authenticated socket link is possible. Authentication refers
     * to the authentication of the link key to prevent man-in-the-middle type of attacks.
     *
     * @param psm dynamic PSM value from remote device
     * @return a CoC #BluetoothSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothSocket createL2capChannel(int psm) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "createL2capChannel: Bluetooth is not enabled");
            throw new IOException();
        }
        if (DBG) Log.d(TAG, "createL2capChannel: psm=" + psm);
        return new BluetoothSocket(BluetoothSocket.TYPE_L2CAP_LE, -1, true, true, this, psm,
                null);
    }

    /**
     * TODO: Remove this hidden method once all the SL4A and other tests are updated to use the new
     * API name, createL2capChannel.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothSocket createL2capCocSocket(int transport, int psm) throws IOException {
        Log.e(TAG, "createL2capCocSocket: PLEASE USE THE OFFICIAL API, createL2capChannel");
        return createL2capChannel(psm);
    }

    /**
     * Create a Bluetooth L2CAP Connection-oriented Channel (CoC) {@link BluetoothSocket} that can
     * be used to start a secure outgoing connection to the remote device with the same dynamic
     * protocol/service multiplexer (PSM) value. The supported Bluetooth transport is LE only.
     * <p>This is designed to be used with {@link
     * BluetoothAdapter#listenUsingInsecureL2capChannel()} for peer-peer Bluetooth applications.
     * <p>Use {@link BluetoothSocket#connect} to initiate the outgoing connection.
     * <p>Application using this API is responsible for obtaining PSM value from remote device.
     * <p> The communication channel may not have an authenticated link key, i.e. it may be subject
     * to man-in-the-middle attacks. Use {@link #createL2capChannel(int)} if an encrypted and
     * authenticated communication channel is possible.
     *
     * @param psm dynamic PSM value from remote device
     * @return a CoC #BluetoothSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or insufficient
     * permissions
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothSocket createInsecureL2capChannel(int psm) throws IOException {
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "createInsecureL2capChannel: Bluetooth is not enabled");
            throw new IOException();
        }
        if (DBG) {
            Log.d(TAG, "createInsecureL2capChannel: psm=" + psm);
        }
        return new BluetoothSocket(BluetoothSocket.TYPE_L2CAP_LE, -1, false, false, this, psm,
                null);
    }

    /**
     * TODO: Remove this hidden method once all the SL4A and other tests are updated to use the new
     * API name, createInsecureL2capChannel.
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public BluetoothSocket createInsecureL2capCocSocket(int transport, int psm) throws IOException {
        Log.e(TAG, "createL2capCocSocket: PLEASE USE THE OFFICIAL API, createInsecureL2capChannel");
        return createInsecureL2capChannel(psm);
    }

    /**
     * Set a keyed metadata of this {@link BluetoothDevice} to a
     * {@link String} value.
     * Only bonded devices's metadata will be persisted across Bluetooth
     * restart.
     * Metadata will be removed when the device's bond state is moved to
     * {@link #BOND_NONE}.
     *
     * @param key must be within the list of BluetoothDevice.METADATA_*
     * @param value the string data to set for key. Must be less than
     * {@link BluetoothAdapter#METADATA_MAX_LENGTH} characters in length
     * @return true on success, false on error
     * @hide
    */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setMetadata(int key, String value) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "Bluetooth is not enabled. Cannot set metadata");
            return false;
        }
        if (value.length() > METADATA_MAX_LENGTH) {
            throw new IllegalArgumentException("value length is " + value.length()
                    + ", should not over " + METADATA_MAX_LENGTH);
        }
        try {
            return service.setMetadata(this, key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "setMetadata fail", e);
            return false;
        }
    }

    /**
     * Get a keyed metadata for this {@link BluetoothDevice} as {@link String}
     *
     * @param key must be within the list of BluetoothDevice.METADATA_*
     * @return Metadata of the key as string, null on error or not found
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public String getMetadata(int key) {
        final IBluetooth service = sService;
        if (service == null) {
            Log.e(TAG, "Bluetooth is not enabled. Cannot get metadata");
            return null;
        }
        try {
            return service.getMetadata(this, key);
        } catch (RemoteException e) {
            Log.e(TAG, "getMetadata fail", e);
            return null;
        }
    }
}
