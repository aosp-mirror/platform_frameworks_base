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

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * Represents a remote Bluetooth device.
 *
 * <p>Use {@link BluetoothAdapter#getRemoteDevice} to create a {@link
 * BluetoothDevice}.
 *
 * <p>This class is really just a thin wrapper for a Bluetooth hardware
 * address. Objects of this class are immutable. Operations on this class
 * are performed on the remote Bluetooth hardware address, using the
 * {@link BluetoothAdapter} that was used to create this {@link
 * BluetoothDevice}.
 *
 * TODO: unhide more of this class
 */
public final class BluetoothDevice implements Parcelable {
    private static final String TAG = "BluetoothDevice";

    /** We do not have a link key for the remote device, and are therefore not
     * bonded
     * @hide*/
    public static final int BOND_NOT_BONDED = 0;
    /** We have a link key for the remote device, and are probably bonded.
     *  @hide */
    public static final int BOND_BONDED = 1;
    /** We are currently attempting bonding
     *  @hide */
    public static final int BOND_BONDING = 2;

    /** Ask device picker to show all kinds of BT devices.
     *  @hide */
    public static final int DEVICE_PICKER_FILTER_TYPE_ALL = 0;
    /** Ask device picker to show BT devices that support AUDIO profiles.
     *  @hide */
    public static final int DEVICE_PICKER_FILTER_TYPE_AUDIO = 1;
    /** Ask device picker to show BT devices that support Object Transfer.
     *  @hide */
    public static final int DEVICE_PICKER_FILTER_TYPE_TRANSFER = 2;

    //TODO: Unify these result codes in BluetoothResult or BluetoothError
    /** A bond attempt failed because pins did not match, or remote device did
     * not respond to pin request in time 
     * @hide */
    public static final int UNBOND_REASON_AUTH_FAILED = 1;
    /** A bond attempt failed because the other side explicilty rejected
     * bonding
     * @hide */
    public static final int UNBOND_REASON_AUTH_REJECTED = 2;
    /** A bond attempt failed because we canceled the bonding process
     * @hide */
    public static final int UNBOND_REASON_AUTH_CANCELED = 3;
    /** A bond attempt failed because we could not contact the remote device
     * @hide */
    public static final int UNBOND_REASON_REMOTE_DEVICE_DOWN = 4;
    /** A bond attempt failed because a discovery is in progress
     * @hide */
    public static final int UNBOND_REASON_DISCOVERY_IN_PROGRESS = 5;
    /** An existing bond was explicitly revoked
     * @hide */
    public static final int UNBOND_REASON_REMOVED = 6;

    //TODO: Remove duplicates between here and BluetoothAdapter
    /** The user will be prompted to enter a pin
     * @hide */
    public static final int PAIRING_VARIANT_PIN = 0;
    /** The user will be prompted to enter a passkey
     * @hide */
    public static final int PAIRING_VARIANT_PASSKEY = 1;
    /** The user will be prompted to confirm the passkey displayed on the screen
     * @hide */
    public static final int PAIRING_VARIANT_CONFIRMATION = 2;

    private static final int ADDRESS_LENGTH = 17;

    private static IBluetooth sService;  /* Guarenteed constant after first object constructed */

    private final String mAddress;

    /**
     * Create a new BluetoothDevice
     * Bluetooth MAC address must be upper case, such as "00:11:22:33:AA:BB",
     * and is validated in this constructor.
     * @param address valid Bluetooth MAC address
     * @throws RuntimeException Bluetooth is not available on this platform
     * @throws IllegalArgumentException address is invalid
     * @hide
     */
    /*package*/ BluetoothDevice(String address) {
        synchronized (BluetoothDevice.class) {
            if (sService == null) {
                IBinder b = ServiceManager.getService(Context.BLUETOOTH_SERVICE);
                if (b == null) {
                    throw new RuntimeException("Bluetooth service not available");
                }
                sService = IBluetooth.Stub.asInterface(b);
            }
        }

        if (!checkBluetoothAddress(address)) {
            throw new IllegalArgumentException(address + " is not a valid Bluetooth address");
        }

        mAddress = address;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothDevice) {
            return mAddress.equals(((BluetoothDevice)o).getAddress());
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
     * @return string representation of this BluetoothDevice
     */
    @Override
    public String toString() {
        return mAddress;
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public static final Parcelable.Creator<BluetoothDevice> CREATOR =
            new Parcelable.Creator<BluetoothDevice>() {
        public BluetoothDevice createFromParcel(Parcel in) {
            return new BluetoothDevice(in.readString());
        }
        public BluetoothDevice[] newArray(int size) {
            return new BluetoothDevice[size];
        }
    };

    /** @hide */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAddress);
    }

    /**
     * Returns the hardware address of this BluetoothDevice.
     * <p> For example, "00:11:22:AA:BB:CC".
     * @return Bluetooth hardware address as string
     */
    public String getAddress() {
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
     * @hide
     */
    public String getName() {
        try {
            return sService.getRemoteName(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Create a bonding with a remote bluetooth device.
     *
     * This is an asynchronous call. The result of this bonding attempt can be
     * observed through BluetoothIntent.BOND_STATE_CHANGED_ACTION intents.
     *
     * @param address the remote device Bluetooth address.
     * @return false If there was an immediate problem creating the bonding,
     *         true otherwise.
     * @hide
     */
    public boolean createBond() {
        try {
            return sService.createBond(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Cancel an in-progress bonding request started with createBond.
     * @hide
     */
    public boolean cancelBondProcess() {
        try {
            return sService.cancelBondProcess(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Removes the remote device and the pairing information associated
     * with it.
     *
     * @return true if the device was disconnected, false otherwise and on
     *         error.
     * @hide
     */
    public boolean removeBond() {
        try {
            return sService.removeBond(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Get the bonding state of a remote device.
     *
     * Result is one of:
     * BluetoothError.*
     * BOND_*
     *
     * @param address Bluetooth hardware address of the remote device to check.
     * @return Result code
     * @hide
     */
    public int getBondState() {
        try {
            return sService.getBondState(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return BluetoothError.ERROR_IPC;
    }

    /**
     * Get trust state of a remote device.
     * @hide
     */
    public boolean getTrustState() {
        try {
            return sService.getTrustState(mAddress);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /**
     * Set trust state for a remote device.
     * @param value the trust state value (true or false)
     * @hide
     */
    public boolean setTrust(boolean value) {
        try {
            return sService.setTrust(mAddress, value);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
        return false;
    }

    /** @hide */
    public int getBluetoothClass() {
        try {
            return sService.getRemoteClass(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return BluetoothError.ERROR_IPC;
    }

    /** @hide */
     public String[] getUuids() {
        try {
            return sService.getRemoteUuids(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /** @hide */
    public int getServiceChannel(String uuid) {
         try {
             return sService.getRemoteServiceChannel(mAddress, uuid);
         } catch (RemoteException e) {Log.e(TAG, "", e);}
         return BluetoothError.ERROR_IPC;
    }

    /** @hide */
    public boolean setPin(byte[] pin) {
        try {
            return sService.setPin(mAddress, pin);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /** @hide */
    public boolean setPasskey(int passkey) {
        try {
            return sService.setPasskey(mAddress, passkey);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /** @hide */
    public boolean setPairingConfirmation(boolean confirm) {
        try {
            return sService.setPairingConfirmation(mAddress, confirm);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /** @hide */
    public boolean cancelPairingUserInput() {
        try {
            return sService.cancelPairingUserInput(mAddress);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Create an RFCOMM {@link BluetoothSocket} ready to start a secure
     * outgoing connection to this remote device.
     * <p>The remote device will be authenticated and communication on this
     * socket will be encrypted.
     * <p>Use {@link BluetoothSocket#connect} to intiate the outgoing
     * connection.
     * <p>Valid RFCOMM channels are in range 1 to 30.
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH}
     * @param channel RFCOMM channel to connect to
     * @return a RFCOMM BluetoothServerSocket ready for an outgoing connection
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions
     */
    public BluetoothSocket createRfcommSocket(int channel) throws IOException {
        return new BluetoothSocket(BluetoothSocket.TYPE_RFCOMM, -1, true, true, this, channel);
    }

    /**
     * Construct an insecure RFCOMM socket ready to start an outgoing
     * connection.
     * Call #connect on the returned #BluetoothSocket to begin the connection.
     * The remote device will not be authenticated and communication on this
     * socket will not be encrypted.
     * @param port    remote port
     * @return An RFCOMM BluetoothSocket
     * @throws IOException On error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public BluetoothSocket createInsecureRfcommSocket(int port) throws IOException {
        return new BluetoothSocket(BluetoothSocket.TYPE_RFCOMM, -1, false, false, this, port);
    }

    /**
     * Construct a SCO socket ready to start an outgoing connection.
     * Call #connect on the returned #BluetoothSocket to begin the connection.
     * @return a SCO BluetoothSocket
     * @throws IOException on error, for example Bluetooth not available, or
     *                     insufficient permissions.
     * @hide
     */
    public BluetoothSocket createScoSocket() throws IOException {
        return new BluetoothSocket(BluetoothSocket.TYPE_SCO, -1, true, true, this, -1);
    }

    /**
     * Check that a pin is valid and convert to byte array.
     *
     * Bluetooth pin's are 1 to 16 bytes of UTF8 characters.
     * @param pin pin as java String
     * @return the pin code as a UTF8 byte array, or null if it is an invalid
     *         Bluetooth pin.
     * @hide
     */
    public static byte[] convertPinToBytes(String pin) {
        if (pin == null) {
            return null;
        }
        byte[] pinBytes;
        try {
            pinBytes = pin.getBytes("UTF8");
        } catch (UnsupportedEncodingException uee) {
            Log.e(TAG, "UTF8 not supported?!?");  // this should not happen
            return null;
        }
        if (pinBytes.length <= 0 || pinBytes.length > 16) {
            return null;
        }
        return pinBytes;
    }

    /** Sanity check a bluetooth address, such as "00:43:A8:23:10:F0"
     * @hide */
    public static boolean checkBluetoothAddress(String address) {
        if (address == null || address.length() != ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            char c = address.charAt(i);
            switch (i % 3) {
            case 0:
            case 1:
                if (Character.digit(c, 16) != -1) {
                    break;  // hex character, OK
                }
                return false;
            case 2:
                if (c == ':') {
                    break;  // OK
                }
                return false;
            }
        }
        return true;
    }
}
