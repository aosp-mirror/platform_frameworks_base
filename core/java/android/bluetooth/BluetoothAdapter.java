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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

/**
 * Represents the local Bluetooth adapter.
 *
 * <p>Use {@link android.content.Context#getSystemService} with {@link
 * android.content.Context#BLUETOOTH_SERVICE} to get the default local
 * Bluetooth adapter. On most Android devices there is only one local
 * Bluetotoh adapter.
 *
 * <p>Use the {@link BluetoothDevice} class for operations on remote Bluetooth
 * devices.
 *
 * <p>TODO: unhide more of this class
 */
public final class BluetoothAdapter {
    private static final String TAG = "BluetoothAdapter";

    /**
     * Broadcast Action: The state of the local Bluetooth adapter has been
     * changed.
     * <p>For example, Bluetooth has been turned on or off.
     * <p>Contains the extra fields {@link #EXTRA_STATE} and {@link
     * #EXTRA_PREVIOUS_STATE} containing the new and old states
     * respectively.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_STATE_CHANGED =
            "android.bluetooth.intent.action.STATE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the current power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     */
    public static final String EXTRA_STATE =
            "android.bluetooth.intent.extra.STATE";
    /**
     * Used as an int extra field in {@link #ACTION_STATE_CHANGED}
     * intents to request the previous power state. Possible values are:
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF},
     */
    public static final String EXTRA_PREVIOUS_STATE =
            "android.bluetooth.intent.extra.PREVIOUS_STATE";

    /**
     * Indicates the local Bluetooth adapter is off.
     */
    public static final int STATE_OFF = 40;
    /**
     * Indicates the local Bluetooth adapter is turning on. However local
     * clients should wait for {@link #STATE_ON} before attempting to
     * use the adapter.
     */
    public static final int STATE_TURNING_ON = 41;
    /**
     * Indicates the local Bluetooth adapter is on, and ready for use.
     */
    public static final int STATE_ON = 42;
    /**
     * Indicates the local Bluetooth adapter is turning off. Local clients
     * should immediately attempt graceful disconnection of any remote links.
     */
    public static final int STATE_TURNING_OFF = 43;

    /**
     * Broadcast Action: Indicates the Bluetooth scan mode of the local Adapter
     * has changed.
     * <p>Contains the extra fields {@link #EXTRA_SCAN_MODE} and {@link
     * #EXTRA_PREVIOUS_SCAN_MODE} containing the new and old scan modes
     * respectively.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCAN_MODE_CHANGED =
            "android.bluetooth.intent.action.SCAN_MODE_CHANGED";

    /**
     * Used as an int extra field in {@link #ACTION_SCAN_MODE_CHANGED}
     * intents to request the current scan mode. Possible values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE},
     */
    public static final String EXTRA_SCAN_MODE = "android.bluetooth.intent.extra.SCAN_MODE";
    /**
     * Used as an int extra field in {@link #ACTION_SCAN_MODE_CHANGED}
     * intents to request the previous scan mode. Possible values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE},
     */
    public static final String EXTRA_PREVIOUS_SCAN_MODE =
            "android.bluetooth.intent.extra.PREVIOUS_SCAN_MODE";

    /**
     * Indicates that both inquiry scan and page scan are disabled on the local
     * Bluetooth adapter. Therefore this device is neither discoverable
     * nor connectable from remote Bluetooth devices.
     */
    public static final int SCAN_MODE_NONE = 50;
    /**
     * Indicates that inquiry scan is disabled, but page scan is enabled on the
     * local Bluetooth adapter. Therefore this device is not discoverable from
     * remote Bluetooth devices, but is connectable from remote devices that
     * have previously discovered this device.
     */
    public static final int SCAN_MODE_CONNECTABLE = 51;
    /**
     * Indicates that both inquiry scan and page scan are enabled on the local
     * Bluetooth adapter. Therefore this device is both discoverable and
     * connectable from remote Bluetooth devices.
     */
    public static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE = 53;

    /** The user will be prompted to enter a pin
     * @hide */
    public static final int PAIRING_VARIANT_PIN = 0;
    /** The user will be prompted to enter a passkey
     * @hide */
    public static final int PAIRING_VARIANT_PASSKEY = 1;
    /** The user will be prompted to confirm the passkey displayed on the screen
     * @hide */
    public static final int PAIRING_VARIANT_CONFIRMATION = 2;

    private final IBluetooth mService;

    /**
     * Do not use this constructor. Use Context.getSystemService() instead.
     * @hide
     */
    public BluetoothAdapter(IBluetooth service) {
        if (service == null) {
            throw new IllegalArgumentException("service is null");
        }
        mService = service;
    }

    /**
     * Get a {@link BluetoothDevice} object for the given Bluetooth hardware
     * address.
     * <p>Valid Bluetooth hardware addresses must be upper case, in a format
     * such as "00:11:22:33:AA:BB".
     * <p>A {@link BluetoothDevice} will always be returned for a valid
     * hardware address, even if this adapter has never seen that device.
     *
     * @param address valid Bluetooth MAC address
     * @throws IllegalArgumentException if address is invalid
     */
    public BluetoothDevice getRemoteDevice(String address) {
        return new BluetoothDevice(address);
    }

    /**
     * Return true if Bluetooth is currently enabled and ready for use.
     * <p>Equivalent to:
     * <code>getBluetoothState() == STATE_ON</code>
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return true if the local adapter is turned on
     */
    public boolean isEnabled() {
        try {
            return mService.isEnabled();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Get the current state of the local Bluetooth adapter.
     * <p>Possible return values are
     * {@link #STATE_OFF},
     * {@link #STATE_TURNING_ON},
     * {@link #STATE_ON},
     * {@link #STATE_TURNING_OFF}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return current state of Bluetooth adapter
     */
    public int getState() {
        try {
            return mService.getBluetoothState();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return STATE_OFF;
    }

    /**
     * Turn on the local Bluetooth adapter.
     * <p>This powers on the underlying Bluetooth hardware, and starts all
     * Bluetooth system services.
     * <p>This is an asynchronous call: it will return immediatley, and
     * clients should listen for {@link #ACTION_STATE_CHANGED}
     * to be notified of subsequent adapter state changes. If this call returns
     * true, then the adapter state will immediately transition from {@link
     * #STATE_OFF} to {@link #STATE_TURNING_ON}, and some time
     * later transition to either {@link #STATE_OFF} or {@link
     * #STATE_ON}. If this call returns false then there was an
     * immediate problem that will prevent the adapter from being turned on -
     * such as Airplane mode, or the adapter is already turned on.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @return true to indicate adapter startup has begun, or false on
     *         immediate error
     */
    public boolean enable() {
        try {
            return mService.enable();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Turn off the local Bluetooth adapter.
     * <p>This gracefully shuts down all Bluetooth connections, stops Bluetooth
     * system services, and powers down the underlying Bluetooth hardware.
     * <p>This is an asynchronous call: it will return immediatley, and
     * clients should listen for {@link #ACTION_STATE_CHANGED}
     * to be notified of subsequent adapter state changes. If this call returns
     * true, then the adapter state will immediately transition from {@link
     * #STATE_ON} to {@link #STATE_TURNING_OFF}, and some time
     * later transition to either {@link #STATE_OFF} or {@link
     * #STATE_ON}. If this call returns false then there was an
     * immediate problem that will prevent the adapter from being turned off -
     * such as the adapter already being turned off.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @return true to indicate adapter shutdown has begun, or false on
     *         immediate error
     */
    public boolean disable() {
        try {
            return mService.disable(true);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Returns the hardware address of the local Bluetooth adapter.
     * <p>For example, "00:11:22:AA:BB:CC".
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return Bluetooth hardware address as string
     */
    public String getAddress() {
        try {
            return mService.getAddress();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Get the friendly Bluetooth name of the local Bluetooth adapter.
     * <p>This name is visible to remote Bluetooth devices.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return the Bluetooth name, or null on error
     */
    public String getName() {
        try {
            return mService.getName();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Set the friendly Bluetooth name of the local Bluetoth adapter.
     * <p>This name is visible to remote Bluetooth devices.
     * <p>Valid Bluetooth names are a maximum of 248 UTF-8 characters, however
     * many remote devices can only display the first 40 characters, and some
     * may be limited to just 20.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param name a valid Bluetooth name
     * @return     true if the name was set, false otherwise
     */
    public boolean setName(String name) {
        try {
            return mService.setName(name);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Get the current Bluetooth scan mode of the local Bluetooth adaper.
     * <p>The Bluetooth scan mode determines if the local adapter is
     * connectable and/or discoverable from remote Bluetooth devices.
     * <p>Possible values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     *
     * @return scan mode
     */
    public int getScanMode() {
        try {
            return mService.getScanMode();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return SCAN_MODE_NONE;
    }

    /**
     * Set the Bluetooth scan mode of the local Bluetooth adapter.
     * <p>The Bluetooth scan mode determines if the local adapter is
     * connectable and/or discoverable from remote Bluetooth devices.
     * <p>For privacy reasons, it is recommended to limit the duration of time
     * that the local adapter remains in a discoverable scan mode. For example,
     * 2 minutes is a generous time to allow a remote Bluetooth device to
     * initiate and complete its discovery process.
     * <p>Valid scan mode values are:
     * {@link #SCAN_MODE_NONE},
     * {@link #SCAN_MODE_CONNECTABLE},
     * {@link #SCAN_MODE_CONNECTABLE_DISCOVERABLE}.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH_ADMIN}
     *
     * @param mode valid scan mode
     * @return     true if the scan mode was set, false otherwise
     */
    public boolean setScanMode(int mode) {
        try {
            return mService.setScanMode(mode);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /** @hide */
    public int getDiscoverableTimeout() {
        try {
            return mService.getDiscoverableTimeout();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return -1;
    }

    /** @hide */
    public void setDiscoverableTimeout(int timeout) {
        try {
            mService.setDiscoverableTimeout(timeout);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
    }

    /** @hide */
    public boolean startDiscovery() {
        try {
            return mService.startDiscovery();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /** @hide */
    public void cancelDiscovery() {
        try {
            mService.cancelDiscovery();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
    }

    /** @hide */
    public boolean isDiscovering() {
        try {
            return mService.isDiscovering();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * List remote devices that are bonded (paired) to the local adapter.
     *
     * Bonding (pairing) is the process by which the user enters a pin code for
     * the device, which generates a shared link key, allowing for
     * authentication and encryption of future connections. In Android we
     * require bonding before RFCOMM or SCO connections can be made to a remote
     * device.
     *
     * This function lists which remote devices we have a link key for. It does
     * not cause any RF transmission, and does not check if the remote device
     * still has it's link key with us. If the other side no longer has its
     * link key then the RFCOMM or SCO connection attempt will result in an
     * error.
     *
     * This function does not check if the remote device is in range.
     *
     * Remote devices that have an in-progress bonding attempt are not
     * returned.
     *
     * @return unmodifiable set of bonded devices, or null on error
     * @hide
     */
    public Set<BluetoothDevice> getBondedDevices() {
        try {
            return toDeviceSet(mService.listBonds());
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Create a listening, secure RFCOMM Bluetooth socket.
     * <p>A remote device connecting to this socket will be authenticated and
     * communication on this socket will be encrypted.
     * <p>Use {@link BluetoothServerSocket#accept} to retrieve incoming
     * connections to listening {@link BluetoothServerSocket}.
     * <p>Valid RFCOMM channels are in range 1 to 30.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * @param channel RFCOMM channel to listen on
     * @return a listening RFCOMM BluetoothServerSocket
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions, or channel in use.
     */
    public BluetoothServerSocket listenUsingRfcommOn(int channel) throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_RFCOMM, true, true, channel);
        try {
            socket.mSocket.bindListen();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException e2) { }
            throw e;
        }
        return socket;
    }

    /**
     * Construct an unencrypted, unauthenticated, RFCOMM server socket.
     * Call #accept to retrieve connections to this socket.
     * @return An RFCOMM BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public BluetoothServerSocket listenUsingInsecureRfcommOn(int port) throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_RFCOMM, false, false, port);
        try {
            socket.mSocket.bindListen();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException e2) { }
            throw e;
        }
        return socket;
    }

    /**
     * Construct a SCO server socket.
     * Call #accept to retrieve connections to this socket.
     * @return A SCO BluetoothServerSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public static BluetoothServerSocket listenUsingScoOn() throws IOException {
        BluetoothServerSocket socket = new BluetoothServerSocket(
                BluetoothSocket.TYPE_SCO, false, false, -1);
        try {
            socket.mSocket.bindListen();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException e2) { }
            throw e;
        }
        return socket;
    }

    private Set<BluetoothDevice> toDeviceSet(String[] addresses) {
        Set<BluetoothDevice> devices = new HashSet<BluetoothDevice>(addresses.length);
        for (int i = 0; i < addresses.length; i++) {
            devices.add(getRemoteDevice(addresses[i]));
        }
        return Collections.unmodifiableSet(devices);
    }
}
