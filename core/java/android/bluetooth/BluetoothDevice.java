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

import android.os.RemoteException;
import android.util.Log;

import java.io.UnsupportedEncodingException;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 *
 * Manages the local Bluetooth device. Scan for devices, create bondings,
 * power up and down the adapter.
 *
 * @hide
 */
public class BluetoothDevice {
    public static final int MODE_UNKNOWN = -1;
    public static final int MODE_OFF = 0;
    public static final int MODE_CONNECTABLE = 1;
    public static final int MODE_DISCOVERABLE = 2;

    public static final int RESULT_FAILURE = -1;
    public static final int RESULT_SUCCESS = 0;

    private static final String TAG = "BluetoothDevice";
    
    private final IBluetoothDevice mService;
    /**
     * @hide - hide this because it takes a parameter of type
     * IBluetoothDevice, which is a System private class.
     * Also note that Context.getSystemService is a factory that
     * returns a BlueToothDevice. That is the right way to get
     * a BluetoothDevice.
     */
    public BluetoothDevice(IBluetoothDevice service) {
        mService = service;
    }

    /**
     * Get the current status of Bluetooth hardware.
     *
     * @return true if Bluetooth enabled, false otherwise.
     */
    public boolean isEnabled() {
        try {
            return mService.isEnabled();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Enable the Bluetooth device.
     * Turn on the underlying hardware.
     * This is an asynchronous call, BluetoothIntent.ENABLED_ACTION will be
     * sent if and when the device is successfully enabled.
     * @return false if we cannot enable the Bluetooth device. True does not
     * imply the device was enabled, it only implies that so far there were no
     * problems.
     */
    public boolean enable() {
        return enable(null);
    }

    /**
     * Enable the Bluetooth device.
     * Turns on the underlying hardware.
     * This is an asynchronous call. onEnableResult() of your callback will be
     * called when the call is complete, with either RESULT_SUCCESS or
     * RESULT_FAILURE.
     *
     * Your callback will be called from a binder thread, not the main thread.
     *
     * In addition to the callback, BluetoothIntent.ENABLED_ACTION will be
     * broadcast if the device is successfully enabled.
     *
     * @param callback Your callback, null is ok.
     * @return true if your callback was successfully registered, or false if
     * there was an error, implying your callback will never be called.
     */
    public boolean enable(IBluetoothDeviceCallback callback) {
        try {
            return mService.enable(callback);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Disable the Bluetooth device.
     * This turns off the underlying hardware.
     *
     * @return true if successful, false otherwise.
     */
    public boolean disable() {
        try {
            return mService.disable();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public String getAddress() {
        try {
            return mService.getAddress();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Get the friendly Bluetooth name of this device.
     *
     * This name is visible to remote Bluetooth devices. Currently it is only
     * possible to retrieve the Bluetooth name when Bluetooth is enabled.
     *
     * @return the Bluetooth name, or null if there was a problem.
     */
    public String getName() {
        try {
            return mService.getName();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Set the friendly Bluetooth name of this device.
     *
     * This name is visible to remote Bluetooth devices. The Bluetooth Service
     * is responsible for persisting this name.
     *
     * @param name the name to set
     * @return     true, if the name was successfully set. False otherwise.
     */
    public boolean setName(String name) {
        try {
            return mService.setName(name);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public String getMajorClass() {
        try {
            return mService.getMajorClass();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getMinorClass() {
        try {
            return mService.getMinorClass();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getVersion() {
        try {
            return mService.getVersion();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getRevision() {
        try {
            return mService.getRevision();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getManufacturer() {
        try {
            return mService.getManufacturer();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getCompany() {
        try {
            return mService.getCompany();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    public int getMode() {
        try {
            return mService.getMode();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return MODE_UNKNOWN;
    }
    public void setMode(int mode) {
        try {
            mService.setMode(mode);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
    }

    public int getDiscoverableTimeout() {
        try {
            return mService.getDiscoverableTimeout();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return -1;
    }
    public void setDiscoverableTimeout(int timeout) {
        try {
            mService.setDiscoverableTimeout(timeout);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
    }

    public boolean startDiscovery() {
        return startDiscovery(true);
    }
    public boolean startDiscovery(boolean resolveNames) {
        try {
            return mService.startDiscovery(resolveNames);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public void cancelDiscovery() {
        try {
            mService.cancelDiscovery();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
    }

    public boolean isDiscovering() {
        try {
            return mService.isDiscovering();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public boolean startPeriodicDiscovery() {
        try {
            return mService.startPeriodicDiscovery();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }
    public boolean stopPeriodicDiscovery() {
        try {
            return mService.stopPeriodicDiscovery();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }
    public boolean isPeriodicDiscovery() {
        try {
            return mService.isPeriodicDiscovery();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public String[] listRemoteDevices() {
        try {
            return mService.listRemoteDevices();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * List remote devices that have a low level (ACL) connection.
     *
     * RFCOMM, SDP and L2CAP are all built on ACL connections. Devices can have
     * an ACL connection even when not paired - this is common for SDP queries
     * or for in-progress pairing requests.
     *
     * In most cases you probably want to test if a higher level protocol is
     * connected, rather than testing ACL connections.
     *
     * @return bluetooth hardware addresses of remote devices with a current
     *         ACL connection. Array size is 0 if no devices have a
     *         connection. Null on error.
     */
    public String[] listAclConnections() {
        try {
            return mService.listAclConnections();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Check if a specified remote device has a low level (ACL) connection.
     *
     * RFCOMM, SDP and L2CAP are all built on ACL connections. Devices can have
     * an ACL connection even when not paired - this is common for SDP queries
     * or for in-progress pairing requests.
     *
     * In most cases you probably want to test if a higher level protocol is
     * connected, rather than testing ACL connections.
     *
     * @param address the Bluetooth hardware address you want to check.
     * @return true if there is an ACL connection, false otherwise and on
     *         error.
     */
    public boolean isAclConnected(String address) {
        try {
            return mService.isAclConnected(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Perform a low level (ACL) disconnection of a remote device.
     *
     * This forcably disconnects the ACL layer connection to a remote device,
     * which will cause all RFCOMM, SDP and L2CAP connections to this remote
     * device to close.
     *
     * @param address the Bluetooth hardware address you want to disconnect.
     * @return true if the device was disconnected, false otherwise and on
     *         error.
     */
    public boolean disconnectRemoteDeviceAcl(String address) {
        try {
            return mService.disconnectRemoteDeviceAcl(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Create a bonding with a remote bluetooth device.
     *
     * This is an asynchronous call. BluetoothIntent.BONDING_CREATED_ACTION
     * will be broadcast if and when the remote device is successfully bonded.
     *
     * @param address the remote device Bluetooth address.
     * @return false if we cannot create a bonding to that device, true if
     * there were no problems beginning the bonding process.
     */
    public boolean createBonding(String address) {
        return createBonding(address, null);
    }

    /**
     * Create a bonding with a remote bluetooth device.
     *
     * This is an asynchronous call. onCreateBondingResult() of your callback
     * will be called when the call is complete, with either RESULT_SUCCESS or
     * RESULT_FAILURE.
     *
     * In addition to the callback, BluetoothIntent.BONDING_CREATED_ACTION will
     * be broadcast if the remote device is successfully bonded.
     *
     * @param address The remote device Bluetooth address.
     * @param callback Your callback, null is ok.
     * @return true if your callback was successfully registered, or false if
     * there was an error, implying your callback will never be called.
     */
    public boolean createBonding(String address, IBluetoothDeviceCallback callback) {
        try {
            return mService.createBonding(address, callback);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public boolean cancelBondingProcess(String address) {
        try {
            return mService.cancelBondingProcess(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * List remote devices that are bonded (paired) to the local device.
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
     * @return bluetooth hardware addresses of remote devices that are
     *         bonded. Array size is 0 if no devices are bonded. Null on error.
     */
    public String[] listBondings() {
        try {
            return mService.listBondings();
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Check if a remote device is bonded (paired) to the local device.
     *
     * Bonding (pairing) is the process by which the user enters a pin code for
     * the device, which generates a shared link key, allowing for
     * authentication and encryption of future connections. In Android we
     * require bonding before RFCOMM or SCO connections can be made to a remote
     * device.
     *
     * This function checks if we have a link key with the remote device. It
     * does not cause any RF transmission, and does not check if the remote
     * device still has it's link key with us. If the other side no longer has
     * a link key then the RFCOMM or SCO connection attempt will result in an
     * error.
     *
     * This function does not check if the remote device is in range.
     *
     * @param address Bluetooth hardware address of the remote device to check.
     * @return true if bonded, false otherwise and on error.
     */
    public boolean hasBonding(String address) {
        try {
            return mService.hasBonding(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public boolean removeBonding(String address) {
        try {
            return mService.removeBonding(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public String getRemoteName(String address) {
        try {
            return mService.getRemoteName(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    public String getRemoteAlias(String address) {
        try {
            return mService.getRemoteAlias(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public boolean setRemoteAlias(String address, String alias) {
        try {
            return mService.setRemoteAlias(address, alias);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }
    public boolean clearRemoteAlias(String address) {
        try {
            return mService.clearRemoteAlias(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }
    public String getRemoteVersion(String address) {
        try {
            return mService.getRemoteVersion(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getRemoteRevision(String address) {
        try {
            return mService.getRemoteRevision(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getRemoteManufacturer(String address) {
        try {
            return mService.getRemoteManufacturer(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getRemoteCompany(String address) {
        try {
            return mService.getRemoteCompany(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getRemoteMajorClass(String address) {
        try {
            return mService.getRemoteMajorClass(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String getRemoteMinorClass(String address) {
        try {
            return mService.getRemoteMinorClass(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String[] getRemoteServiceClasses(String address) {
        try {
            return mService.getRemoteServiceClasses(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    /**
     * Returns the RFCOMM channel associated with the 16-byte UUID on
     * the remote Bluetooth address.
     *
     * Performs a SDP ServiceSearchAttributeRequest transaction. The provided
     * uuid is verified in the returned record. If there was a problem, or the
     * specified uuid does not exist, -1 is returned.
     */
    public boolean getRemoteServiceChannel(String address, short uuid16,
            IBluetoothDeviceCallback callback) {
        try {
            return mService.getRemoteServiceChannel(address, uuid16, callback);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    public int getRemoteClass(String address) {
        try {
            return mService.getRemoteClass(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return BluetoothClass.ERROR;
    }
    public byte[] getRemoteFeatures(String address) {
        try {
            return mService.getRemoteFeatures(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String lastSeen(String address) {
        try {
            return mService.lastSeen(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }
    public String lastUsed(String address) {
        try {
            return mService.lastUsed(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return null;
    }

    public boolean setPin(String address, byte[] pin) {
        try {
            return mService.setPin(address, pin);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }
    public boolean cancelPin(String address) {
        try {
            return mService.cancelPin(address);
        } catch (RemoteException e) {Log.e(TAG, "", e);}
        return false;
    }

    /**
     * Check that a pin is valid and convert to byte array.
     *
     * Bluetooth pin's are 1 to 16 bytes of UTF8 characters.
     * @param pin pin as java String
     * @return the pin code as a UTF8 byte array, or null if it is an invalid
     *         Bluetooth pin.
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
    

    /* Sanity check a bluetooth address, such as "00:43:A8:23:10:F0" */
    private static final int ADDRESS_LENGTH = 17;
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
