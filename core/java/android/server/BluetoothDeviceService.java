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

/**
 * TODO: Move this to
 * java/services/com/android/server/BluetoothDeviceService.java
 * and make the contructor package private again.
 *
 * @hide
 */

package android.server;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;  // just for dump()
import android.bluetooth.BluetoothIntent;
import android.bluetooth.IBluetoothDevice;
import android.bluetooth.IBluetoothDeviceCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.SystemService;

import java.io.IOException;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;

public class BluetoothDeviceService extends IBluetoothDevice.Stub {
    private static final String TAG = "BluetoothDeviceService";
    private int mNativeData;
    private BluetoothEventLoop mEventLoop;
    private IntentFilter mIntentFilter;
    private boolean mIsAirplaneSensitive;
    private volatile boolean mIsEnabled;  // local cache of isEnabledNative()
    private boolean mIsDiscovering;

    private final Context mContext;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    static {
        classInitNative();
    }
    private native static void classInitNative();

    public BluetoothDeviceService(Context context) {
        mContext = context;
    }

    /** Must be called after construction, and before any other method.
     */
    public synchronized void init() {
        initializeNativeDataNative();
        mIsEnabled = (isEnabledNative() == 1);
        mIsDiscovering = false;
        mEventLoop = new BluetoothEventLoop(mContext, this);
        registerForAirplaneMode();
        
        disableEsco();  // TODO: enable eSCO support once its fully supported
    }
    private native void initializeNativeDataNative();

    @Override
    protected void finalize() throws Throwable {
        if (mIsAirplaneSensitive) {
            mContext.unregisterReceiver(mReceiver);
        }
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }
    private native void cleanupNativeDataNative();

    public boolean isEnabled() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mIsEnabled;
    }
    private native int isEnabledNative();

    /**
     * Disable bluetooth. Returns true on success.
     */
    public synchronized boolean disable() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");

        if (mEnableThread != null && mEnableThread.isAlive()) {
            return false;
        }
        if (!mIsEnabled) {
            return true;
        }
        mEventLoop.stop();
        disableNative();
        mIsEnabled = false;
        Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.BLUETOOTH_ON, 0);
        mIsDiscovering = false;
        Intent intent = new Intent(BluetoothIntent.DISABLED_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        return true;
    }

    /**
     * Enable this Bluetooth device, asynchronously.
     * This turns on/off the underlying hardware.
     *
     * @return True on success (so far), guarenteeing the callback with be
     * notified when complete.
     */
    public synchronized boolean enable(IBluetoothDeviceCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");

        // Airplane mode can prevent Bluetooth radio from being turned on.
        if (mIsAirplaneSensitive && isAirplaneModeOn()) {
            return false;
        }
        if (mIsEnabled) {
            return false;
        }
        if (mEnableThread != null && mEnableThread.isAlive()) {
            return false;
        }
        mEnableThread = new EnableThread(callback);
        mEnableThread.start();
        return true;
    }

    private static final int REGISTER_SDP_RECORDS = 1;
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case REGISTER_SDP_RECORDS:
                //TODO: Don't assume HSP/HFP is running, don't use sdptool,
                if (isEnabled()) {
                    SystemService.start("hsag");
                    SystemService.start("hfag");
                }
            }
        }
    };

    private EnableThread mEnableThread;
    private String mOutgoingBondingDevAddress = null;

    private class EnableThread extends Thread {
        private final IBluetoothDeviceCallback mEnableCallback;
        public EnableThread(IBluetoothDeviceCallback callback) {
            mEnableCallback = callback;
        }
        public void run() {
            boolean res = (enableNative() == 0);
            if (res) {
                mEventLoop.start();
            }

            if (mEnableCallback != null) {
                try {
                    mEnableCallback.onEnableResult(res ?
                                                   BluetoothDevice.RESULT_SUCCESS :
                                                   BluetoothDevice.RESULT_FAILURE);
                } catch (RemoteException e) {}
            }

            if (res) {
                mIsEnabled = true;
                Settings.Secure.putInt(mContext.getContentResolver(),
                                       Settings.Secure.BLUETOOTH_ON, 1);
                mIsDiscovering = false;
                Intent intent = new Intent(BluetoothIntent.ENABLED_ACTION);
                mContext.sendBroadcast(intent, BLUETOOTH_PERM);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(REGISTER_SDP_RECORDS), 3000);
            }
            mEnableThread = null;
        }
    };

    private native int enableNative();
    private native int disableNative();

    public synchronized String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getAddressNative();
    }
    private native String getAddressNative();

    public synchronized String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getNameNative();
    }
    private native String getNameNative();

    public synchronized boolean setName(String name) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (name == null) {
            return false;
        }
        // hcid handles persistance of the bluetooth name
        return setNameNative(name);
    }
    private native boolean setNameNative(String name);

    public synchronized String[] listBondings() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return listBondingsNative();
    }
    private native String[] listBondingsNative();

    public synchronized String getMajorClass() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getMajorClassNative();
    }
    private native String getMajorClassNative();

    public synchronized String getMinorClass() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getMinorClassNative();
    }
    private native String getMinorClassNative();

    /**
     * Returns the user-friendly name of a remote device.  This value is
     * retrned from our local cache, which is updated during device discovery.
     * Do not expect to retrieve the updated remote name immediately after
     * changing the name on the remote device.
     *
     * @param address Bluetooth address of remote device.
     *
     * @return The user-friendly name of the specified remote device.
     */
    public synchronized String getRemoteName(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteNameNative(address);
    }
    private native String getRemoteNameNative(String address);

    /* pacakge */ native String getAdapterPathNative();

    /**
     * Initiate a remote-device-discovery procedure.  This procedure may be
     * canceled by calling {@link #stopDiscovery}.  Remote-device discoveries
     * are returned as intents
     * <p>
     * Typically, when a remote device is found, your
     * android.bluetooth.DiscoveryEventNotifier#notifyRemoteDeviceFound
     *  method will be invoked, and subsequently, your
     * android.bluetooth.RemoteDeviceEventNotifier#notifyRemoteNameUpdated
     * will tell you the user-friendly name of the remote device.  However,
     * it is possible that the name update may fail for various reasons, so you
     * should display the device's Bluetooth address as soon as you get a
     * notifyRemoteDeviceFound event, and update the name when you get the
     * remote name.
     *
     * @return true if discovery has started,
     *         false otherwise.
     */
    public synchronized boolean startDiscovery(boolean resolveNames) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return startDiscoveryNative(resolveNames);
    }
    private native boolean startDiscoveryNative(boolean resolveNames);

    /**
     * Cancel a remote-device discovery.
     *
     * Note: you may safely call this method even when discovery has not been
     *       started.
     */
    public synchronized boolean cancelDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return cancelDiscoveryNative();
    }
    private native boolean cancelDiscoveryNative();

    public synchronized boolean isDiscovering() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mIsDiscovering;
    }

    /* package */ void setIsDiscovering(boolean isDiscovering) {
        mIsDiscovering = isDiscovering;
    }

    public synchronized boolean startPeriodicDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return startPeriodicDiscoveryNative();
    }
    private native boolean startPeriodicDiscoveryNative();

    public synchronized boolean stopPeriodicDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return stopPeriodicDiscoveryNative();
    }
    private native boolean stopPeriodicDiscoveryNative();

    public synchronized boolean isPeriodicDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return isPeriodicDiscoveryNative();
    }
    private native boolean isPeriodicDiscoveryNative();

    /**
     * Set the discoverability window for the device.  A timeout of zero
     * makes the device permanently discoverable (if the device is
     * discoverable).  Setting the timeout to a nonzero value does not make
     * a device discoverable; you need to call setMode() to make the device
     * explicitly discoverable.
     *
     * @param timeout_s The discoverable timeout in seconds.
     */
    public synchronized boolean setDiscoverableTimeout(int timeout) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return setDiscoverableTimeoutNative(timeout);
    }
    private native boolean setDiscoverableTimeoutNative(int timeout_s);

    /**
     * Get the discoverability window for the device.  A timeout of zero
     * means that the device is permanently discoverable (if the device is
     * in the discoverable mode).
     *
     * @return The discoverability window of the device, in seconds.  A negative
     *         value indicates an error.
     */
    public synchronized int getDiscoverableTimeout() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getDiscoverableTimeoutNative();
    }
    private native int getDiscoverableTimeoutNative();

    public synchronized boolean isAclConnected(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return isConnectedNative(address);
    }
    private native boolean isConnectedNative(String address);

    /**
     * Detetermines whether this device is connectable (that is, whether remote
     * devices can connect to it.)
     * <p>
     * Note: A Bluetooth adapter has separate connectable and discoverable
     *       states, and you could have any combination of those.  Although
     *       any combination is possible (such as discoverable but not
     *       connectable), we restrict the possible combinations to one of
     *       three possibilities: discoverable and connectable, connectable
     *       but not discoverable, and neither connectable nor discoverable.
     *
     * @return true if this adapter is connectable
     *         false otherwise
     *
     * @see #isDiscoverable
     * @see #getMode
     * @see #setMode
     */
    public synchronized boolean isConnectable() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return isConnectableNative();
    }
    private native boolean isConnectableNative();

    /**
     * Detetermines whether this device is discoverable.
     *
     * Note: a Bluetooth adapter has separate connectable and discoverable
     *       states, and you could have any combination of those.  Although
     *       any combination is possible (such as discoverable but not
     *       connectable), we restrict the possible combinations to one of
     *       three possibilities: discoverable and connectable, connectable
     *       but not discoverable, and neither connectable nor discoverable.
     *
     * @return true if this adapter is discoverable
     *         false otherwise
     *
     * @see #isConnectable
     * @see #getMode
     * @see #setMode
     */
    public synchronized boolean isDiscoverable() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return isDiscoverableNative();
    }
    private native boolean isDiscoverableNative();

    /**
     * Determines which one of three modes this adapter is in: discoverable and
     * connectable, not discoverable but connectable, or neither.
     *
     * @return Mode enumeration containing the current mode.
     *
     * @see #setMode
     */
    public synchronized int getMode() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        String mode = getModeNative();
        if (mode == null) {
            return BluetoothDevice.MODE_UNKNOWN;
        }
        if (mode.equalsIgnoreCase("off")) {
            return BluetoothDevice.MODE_OFF;
        }
        else if (mode.equalsIgnoreCase("connectable")) {
            return BluetoothDevice.MODE_CONNECTABLE;
        }
        else if (mode.equalsIgnoreCase("discoverable")) {
            return BluetoothDevice.MODE_DISCOVERABLE;
        }
        else {
            return BluetoothDevice.MODE_UNKNOWN;
        }
    }
    private native String getModeNative();

    /**
     * Set the discoverability and connectability mode of this adapter.  The
     * possibilities are discoverable and connectable (MODE_DISCOVERABLE),
     * connectable but not discoverable (MODE_CONNECTABLE), and neither
     * (MODE_OFF).
     *
     * Note: MODE_OFF does not mean that the adapter is physically off.  It
     *       may be neither discoverable nor connectable, but it could still
     *       initiate outgoing connections, or could participate in a
     *       connection initiated by a remote device before its mode was set
     *       to MODE_OFF.
     *
     * @param mode the new mode
     * @see #getMode
     */
    public synchronized boolean setMode(int mode) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        switch (mode) {
        case BluetoothDevice.MODE_OFF:
            return setModeNative("off");
        case BluetoothDevice.MODE_CONNECTABLE:
            return setModeNative("connectable");
        case BluetoothDevice.MODE_DISCOVERABLE:
            return setModeNative("discoverable");
        }
        return false;
    }
    private native boolean setModeNative(String mode);

    /**
     * Retrieves the alias of a remote device.  The alias is a local feature,
     * and allows us to associate a name with a remote device that is different
     * from that remote device's user-friendly name.  The remote device knows
     * nothing about this.  The alias can be changed with
     * {@link #setRemoteAlias}, and it may be removed with
     * {@link #clearRemoteAlias}
     *
     * @param address Bluetooth address of remote device.
     *
     * @return The alias of the remote device.
     */
    public synchronized String getRemoteAlias(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteAliasNative(address);
    }
    private native String getRemoteAliasNative(String address);

    /**
     * Changes the alias of a remote device.  The alias is a local feature,
     * from that remote device's user-friendly name.  The remote device knows
     * nothing about this.  The alias can be retrieved with
     * {@link #getRemoteAlias}, and it may be removed with
     * {@link #clearRemoteAlias}.
     *
     * @param address Bluetooth address of remote device
     * @param alias Alias for the remote device
     */
    public synchronized boolean setRemoteAlias(String address, String alias) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (alias == null || !BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return setRemoteAliasNative(address, alias);
    }
    private native boolean setRemoteAliasNative(String address, String alias);

    /**
     * Removes the alias of a remote device.  The alias is a local feature,
     * from that remote device's user-friendly name.  The remote device knows
     * nothing about this.  The alias can be retrieved with
     * {@link #getRemoteAlias}.
     *
     * @param address Bluetooth address of remote device
     */
    public synchronized boolean clearRemoteAlias(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return clearRemoteAliasNative(address);
    }
    private native boolean clearRemoteAliasNative(String address);

    public synchronized boolean disconnectRemoteDeviceAcl(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return disconnectRemoteDeviceNative(address);
    }
    private native boolean disconnectRemoteDeviceNative(String address);

    private static final int MAX_OUTSTANDING_ASYNC = 32;
    /**
     * This method initiates a Bonding request to a remote device.
     *
     *
     * @param address The Bluetooth address of the remote device
     *
     * @see #createBonding
     * @see #cancelBondingProcess
     * @see #removeBonding
     * @see #hasBonding
     * @see #listBondings
     *
     * @see android.bluetooth.PasskeyAgent
     */
    public synchronized boolean createBonding(String address, IBluetoothDeviceCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }

        HashMap<String, IBluetoothDeviceCallback> callbacks = mEventLoop.getCreateBondingCallbacks();
        if (callbacks.containsKey(address)) {
            Log.w(TAG, "createBonding() already in progress for " + address);
            return false;
        }

        // Protect from malicious clients - limit number of outstanding requests
        if (callbacks.size() > MAX_OUTSTANDING_ASYNC) {
            Log.w(TAG, "Too many outstanding bonding requests, dropping request for " + address);
            return false;
        }

        callbacks.put(address, callback);
        if (!createBondingNative(address, 60000 /* 1 minute */)) {
            callbacks.remove(address);
            return false;
        }
        mOutgoingBondingDevAddress = address;
        return true;
    }
           
    private native boolean createBondingNative(String address, int timeout_ms);
    
    /*package*/ String getOutgoingBondingDevAddress() {
        return mOutgoingBondingDevAddress;
    }

    /*package*/ void setOutgoingBondingDevAddress(String outgoingBondingDevAddress) {
        mOutgoingBondingDevAddress = outgoingBondingDevAddress;
    }

    /**
     * This method cancels a pending bonding request.
     *
     * @param address The Bluetooth address of the remote device to which a
     *        bonding request has been initiated.
     *
     * Note: When a request is canceled, method
     *       {@link CreateBondingResultNotifier#notifyAuthenticationFailed}
     *       will be called on the object passed to method
     *       {@link #createBonding}.
     *
     * Note: it is safe to call this method when there is no outstanding
     *       bonding request.
     *
     * @see #createBonding
     * @see #cancelBondingProcess
     * @see #removeBonding
     * @see #hasBonding
     * @see #listBondings
     */
    public synchronized boolean cancelBondingProcess(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return cancelBondingProcessNative(address);
    }
    private native boolean cancelBondingProcessNative(String address);

    /**
     * This method removes a bonding to a remote device.  This is a local
     * operation only, resulting in this adapter "forgetting" the bonding
     * information about the specified remote device.  The other device itself
     * does not know what the bonding has been torn down.  The next time either
     * device attemps to connect to the other, the connection will fail, and
     * the pairing procedure will have to be re-initiated.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @see #createBonding
     * @see #cancelBondingProcess
     * @see #removeBonding
     * @see #hasBonding
     * @see #listBondings
     */
    public synchronized boolean removeBonding(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return removeBondingNative(address);
    }
    private native boolean removeBondingNative(String address);

    public synchronized boolean hasBonding(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return hasBondingNative(address);
    }
    private native boolean hasBondingNative(String address);

    public synchronized String[] listAclConnections() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return listConnectionsNative();
    }
    private native String[] listConnectionsNative();

    /**
     * This method lists all remote devices that this adapter is aware of.
     * This is a list not only of all most-recently discovered devices, but of
     * all devices discovered by this adapter up to some point in the past.
     * Note that many of these devices may not be in the neighborhood anymore,
     * and attempting to connect to them will result in an error.
     *
     * @return An array of strings representing the Bluetooth addresses of all
     *         remote devices that this adapter is aware of.
     */
    public synchronized String[] listRemoteDevices() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return listRemoteDevicesNative();
    }
    private native String[] listRemoteDevicesNative();

    /**
     * Returns the version of the Bluetooth chip. This version is compiled from
     * the LMP version. In case of EDR the features attribute must be checked.
     * Example: "Bluetooth 2.0 + EDR".
     *
     * @return a String representation of the this Adapter's underlying
     *         Bluetooth-chip version.
     */
    public synchronized String getVersion() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getVersionNative();
    }
    private native String getVersionNative();

    /**
     * Returns the revision of the Bluetooth chip. This is a vendor-specific
     * value and in most cases it represents the firmware version. This might
     * derive from the HCI revision and LMP subversion values or via extra
     * vendord specific commands.
     * In case the revision of a chip is not available. This method should
     * return the LMP subversion value as a string.
     * Example: "HCI 19.2"
     *
     * @return The HCI revision of this adapter.
     */
    public synchronized String getRevision() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getRevisionNative();
    }
    private native String getRevisionNative();

    /**
     * Returns the manufacturer of the Bluetooth chip. If the company id is not
     * known the sting "Company ID %d" where %d should be replaced with the
     * numeric value from the manufacturer field.
     * Example: "Cambridge Silicon Radio"
     *
     * @return Manufacturer name.
     */
    public synchronized String getManufacturer() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getManufacturerNative();
    }
    private native String getManufacturerNative();

    /**
     * Returns the company name from the OUI database of the Bluetooth device
     * address. This function will need a valid and up-to-date oui.txt from
     * the IEEE. This value will be different from the manufacturer string in
     * the most cases.
     * If the oui.txt file is not present or the OUI part of the Bluetooth
     * address is not listed, it should return the string "OUI %s" where %s is
     * the actual OUI.
     *
     * Example: "Apple Computer"
     *
     * @return company name
     */
    public synchronized String getCompany() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getCompanyNative();
    }
    private native String getCompanyNative();

    /**
     * Like getVersion(), but for a remote device.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @return remote-device Bluetooth version
     *
     * @see #getVersion
     */
    public synchronized String getRemoteVersion(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteVersionNative(address);
    }
    private native String getRemoteVersionNative(String address);

    /**
     * Like getRevision(), but for a remote device.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @return remote-device HCI revision
     *
     * @see #getRevision
     */
    public synchronized String getRemoteRevision(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteRevisionNative(address);
    }
    private native String getRemoteRevisionNative(String address);

    /**
     * Like getManufacturer(), but for a remote device.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @return remote-device Bluetooth chip manufacturer
     *
     * @see #getManufacturer
     */
    public synchronized String getRemoteManufacturer(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteManufacturerNative(address);
    }
    private native String getRemoteManufacturerNative(String address);

    /**
     * Like getCompany(), but for a remote device.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @return remote-device company
     *
     * @see #getCompany
     */
    public synchronized String getRemoteCompany(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteCompanyNative(address);
    }
    private native String getRemoteCompanyNative(String address);

    /**
     * Returns the date and time when the specified remote device has been seen
     * by a discover procedure.
     * Example: "2006-02-08 12:00:00 GMT"
     *
     * @return a String with the timestamp.
     */
    public synchronized String lastSeen(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return lastSeenNative(address);
    }
    private native String lastSeenNative(String address);

    /**
     * Returns the date and time when the specified remote device has last been
     * connected to
     * Example: "2006-02-08 12:00:00 GMT"
     *
     * @return a String with the timestamp.
     */
    public synchronized String lastUsed(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return lastUsedNative(address);
    }
    private native String lastUsedNative(String address);

    /**
     * Gets the major device class of the specified device.
     * Example: "computer"
     *
     * Note:  This is simply a string desciption of the major class of the
     *        device-class information, which is returned as a 32-bit value
     *        during device discovery.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @return remote-device major class
     *
     * @see #getRemoteClass
     */
    public synchronized String getRemoteMajorClass(String address) {
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return null;
        }
        return getRemoteMajorClassNative(address);
    }
    private native String getRemoteMajorClassNative(String address);

    /**
     * Gets the minor device class of the specified device.
     * Example: "laptop"
     *
     * Note:  This is simply a string desciption of the minor class of the
     *        device-class information, which is returned as a 32-bit value
     *        during device discovery.
     *
     * @param address The Bluetooth address of the remote device.
     *
     * @return remote-device minor class
     *
     * @see #getRemoteClass
     */
    public synchronized String getRemoteMinorClass(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteMinorClassNative(address);
    }
    private native String getRemoteMinorClassNative(String address);

    /**
     * Gets the service classes of the specified device.
     * Example: ["networking", "object transfer"]
     *
     * @return a String array with the descriptions of the service classes.
     *
     * @see #getRemoteClass
     */
    public synchronized String[] getRemoteServiceClasses(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteServiceClassesNative(address);
    }
    private native String[] getRemoteServiceClassesNative(String address);

    /**
     * Gets the remote major, minor, and service classes encoded as a 32-bit
     * integer.
     *
     * Note: this value is retrieved from cache, because we get it during
     *       remote-device discovery.
     *
     * @return 32-bit integer encoding the remote major, minor, and service
     *         classes.
     *
     * @see #getRemoteMajorClass
     * @see #getRemoteMinorClass
     * @see #getRemoteServiceClasses
     */
    public synchronized int getRemoteClass(String address) {
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return -1;
        }
        return getRemoteClassNative(address);
    }
    private native int getRemoteClassNative(String address);

    /**
     * Gets the remote features encoded as bit mask.
     *
     * Note: This method may be obsoleted soon.
     *
     * @return byte array of features.
     */
    public synchronized byte[] getRemoteFeatures(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteFeaturesNative(address);
    }
    private native byte[] getRemoteFeaturesNative(String address);

    /**
     * This method and {@link #getRemoteServiceRecord} query the SDP service
     * on a remote device.  They do not interpret the data, but simply return
     * it raw to the user.  To read more about SDP service handles and records,
     * consult the Bluetooth core documentation (www.bluetooth.com).
     *
     * @param address Bluetooth address of remote device.
     * @param match a String match to narrow down the service-handle search.
     *        The only supported value currently is "hsp" for the headset
     *        profile.  To retrieve all service handles, simply pass an empty
     *        match string.
     *
     * @return all service handles corresponding to the string match.
     *
     * @see #getRemoteServiceRecord
     */
    public synchronized int[] getRemoteServiceHandles(String address, String match) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        if (match == null) {
            match = "";
        }
        return getRemoteServiceHandlesNative(address, match);
    }
    private native int[] getRemoteServiceHandlesNative(String address, String match);

    /**
     * This method retrieves the service records corresponding to a given
     * service handle (method {@link #getRemoteServiceHandles} retrieves the
     * service handles.)
     *
     * This method and {@link #getRemoteServiceHandles} do not interpret their
     * data, but simply return it raw to the user.  To read more about SDP
     * service handles and records, consult the Bluetooth core documentation
     * (www.bluetooth.com).
     *
     * @param address Bluetooth address of remote device.
     * @param handle Service handle returned by {@link #getRemoteServiceHandles}
     *
     * @return a byte array of all service records corresponding to the
     *         specified service handle.
     *
     * @see #getRemoteServiceHandles
     */
    public synchronized byte[] getRemoteServiceRecord(String address, int handle) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        return getRemoteServiceRecordNative(address, handle);
    }
    private native byte[] getRemoteServiceRecordNative(String address, int handle);

    // AIDL does not yet support short's
    public synchronized boolean getRemoteServiceChannel(String address, int uuid16,
            IBluetoothDeviceCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        HashMap<String, IBluetoothDeviceCallback> callbacks =
            mEventLoop.getRemoteServiceChannelCallbacks();
        if (callbacks.containsKey(address)) {
            Log.w(TAG, "SDP request already in progress for " + address);
            return false;
        }
        // Protect from malicious clients - only allow 32 bonding requests per minute.
        if (callbacks.size() > MAX_OUTSTANDING_ASYNC) {
            Log.w(TAG, "Too many outstanding SDP requests, dropping request for " + address);
            return false;
        }
        callbacks.put(address, callback);

        if (!getRemoteServiceChannelNative(address, (short)uuid16)) {
            callbacks.remove(address);
            return false;
        }
        return true;
    }
    private native boolean getRemoteServiceChannelNative(String address, short uuid16);

    public synchronized boolean setPin(String address, byte[] pin) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (pin == null || pin.length <= 0 || pin.length > 16 ||
            !BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setPin(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }
        // bluez API wants pin as a string
        String pinString;
        try {
            pinString = new String(pin, "UTF8");
        } catch (UnsupportedEncodingException uee) {
            Log.e(TAG, "UTF8 not supported?!?");
            return false;
        }
        return setPinNative(address, pinString, data.intValue());
    }
    private native boolean setPinNative(String address, String pin, int nativeData);

    public synchronized boolean cancelPin(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "cancelPin(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was already cancelled by the remote " +
                  "or by bluez.\n");
            return false;
        }
        return cancelPinNative(address, data.intValue());
    }
    private native boolean cancelPinNative(String address, int natveiData);

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                ContentResolver resolver = context.getContentResolver();
                // Query the airplane mode from Settings.System just to make sure that
                // some random app is not sending this intent and disabling bluetooth
                boolean enabled = !isAirplaneModeOn();
                // If bluetooth is currently expected to be on, then enable or disable bluetooth
                if (Settings.Secure.getInt(resolver, Settings.Secure.BLUETOOTH_ON, 0) > 0) {
                    if (enabled) {
                        enable(null);
                    } else {
                        disable();
                    }
                }
            }
        }
    };

    private void registerForAirplaneMode() {
        String airplaneModeRadios = Settings.System.getString(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_RADIOS);
        mIsAirplaneSensitive = airplaneModeRadios == null
                ? true : airplaneModeRadios.contains(Settings.System.RADIO_BLUETOOTH);
        if (mIsAirplaneSensitive) {
            mIntentFilter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            mContext.registerReceiver(mReceiver, mIntentFilter);
        }
    }

    /* Returns true if airplane mode is currently on */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    private static final String DISABLE_ESCO_PATH = "/sys/module/sco/parameters/disable_esco";
    private static void disableEsco() {
        try {
            FileWriter file = new FileWriter(DISABLE_ESCO_PATH);
            file.write("Y");
            file.close();
        } catch (FileNotFoundException e) {
        } catch (IOException e) {}
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mIsEnabled) {
            pw.println("\nBluetooth ENABLED: " + getAddress() + " (" + getName() + ")");
            pw.println("\nisDiscovering() = " + isDiscovering());

            BluetoothHeadset headset = new BluetoothHeadset(mContext, null);

            pw.println("\n--Bondings--");
            String[] addresses = listBondings();
            for (String address : addresses) {
                String name = getRemoteName(address);
                pw.println(address + " (" + name + ")");
            }

            pw.println("\n--Current ACL Connections--");
            addresses = listAclConnections();
            for (String address : addresses) {
                String name = getRemoteName(address);
                pw.println(address + " (" + name + ")");
            }

            pw.println("\n--Known Devices--");
            addresses = listRemoteDevices();
            for (String address : addresses) {
                String name = getRemoteName(address);
                pw.println(address + " (" + name + ")");
            }

            // Rather not do this from here, but no-where else and I need this
            // dump
            pw.println("\n--Headset Service--");
            switch (headset.getState()) {
            case BluetoothHeadset.STATE_DISCONNECTED:
                pw.println("getState() = STATE_DISCONNECTED");
                break;
            case BluetoothHeadset.STATE_CONNECTING:
                pw.println("getState() = STATE_CONNECTING");
                break;
            case BluetoothHeadset.STATE_CONNECTED:
                pw.println("getState() = STATE_CONNECTED");
                break;
            case BluetoothHeadset.STATE_ERROR:
                pw.println("getState() = STATE_ERROR");
                break;
            }
            pw.println("getHeadsetAddress() = " + headset.getHeadsetAddress());
            headset.close();

        } else {
            pw.println("\nBluetooth DISABLED");
        }
        pw.println("\nmIsAirplaneSensitive = " + mIsAirplaneSensitive);
    }
}
