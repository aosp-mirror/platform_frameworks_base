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
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.IBluetoothDevice;
import android.bluetooth.IBluetoothDeviceCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemService;
import android.provider.Settings;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class BluetoothDeviceService extends IBluetoothDevice.Stub {
    private static final String TAG = "BluetoothDeviceService";
    private static final boolean DBG = true;

    private int mNativeData;
    private BluetoothEventLoop mEventLoop;
    private IntentFilter mIntentFilter;
    private boolean mIsAirplaneSensitive;
    private final BondState mBondState = new BondState();  // local cache of bondings
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

        if (isEnabledNative() == 1) {
            Log.w(TAG, "Bluetooth daemons already running - runtime restart? ");
            disableNative();
        }

        mIsEnabled = false;
        mIsDiscovering = false;
        mEventLoop = new BluetoothEventLoop(mContext, this);
        registerForAirplaneMode();
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
     * Bring down bluetooth and disable BT in settings. Returns true on success.
     */
    public boolean disable() {
        return disable(true);
    }

    /**
     * Bring down bluetooth. Returns true on success.
     *
     * @param saveSetting If true, disable BT in settings
     *
     */
    public synchronized boolean disable(boolean saveSetting) {
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

        // mark in progress bondings as cancelled
        for (String address : mBondState.listInState(BluetoothDevice.BOND_BONDING)) {
            mBondState.setBondState(address, BluetoothDevice.BOND_NOT_BONDED,
                                    BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        }

        // Remove remoteServiceChannelCallbacks
        HashMap<String, IBluetoothDeviceCallback> callbacksMap =
            mEventLoop.getRemoteServiceChannelCallbacks();
        IBluetoothDeviceCallback callback;

        for (String address : callbacksMap.keySet()) {
            callback = callbacksMap.get(address);
            try {
                callback.onGetRemoteServiceChannelResult(address, BluetoothError.ERROR_DISABLED);
            } catch (RemoteException e) {}
            callbacksMap.remove(address);
        }

        // update mode
        Intent intent = new Intent(BluetoothIntent.SCAN_MODE_CHANGED_ACTION);
        intent.putExtra(BluetoothIntent.SCAN_MODE, BluetoothDevice.SCAN_MODE_NONE);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);

        mIsEnabled = false;
        if (saveSetting) {
            persistBluetoothOnSetting(false);
        }
        mIsDiscovering = false;
        intent = new Intent(BluetoothIntent.DISABLED_ACTION);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        return true;
    }

    /**
     * Bring up bluetooth, asynchronously, and enable BT in settings.
     * This turns on/off the underlying hardware.
     *
     * @return True on success (so far), guaranteeing the callback with be
     * notified when complete.
     */
    public boolean enable(IBluetoothDeviceCallback callback) {
        return enable(callback, true);
    }

    /**
     * Enable this Bluetooth device, asynchronously.
     * This turns on/off the underlying hardware.
     *
     * @param saveSetting If true, enable BT in settings
     *
     * @return True on success (so far), guaranteeing the callback with be
     * notified when complete.
     */
    public synchronized boolean enable(IBluetoothDeviceCallback callback,
            boolean saveSetting) {
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
        mEnableThread = new EnableThread(callback, saveSetting);
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

    private class EnableThread extends Thread {
        private final IBluetoothDeviceCallback mEnableCallback;
        private final boolean mSaveSetting;
        public EnableThread(IBluetoothDeviceCallback callback, boolean saveSetting) {
            mEnableCallback = callback;
            mSaveSetting = saveSetting;
        }
        public void run() {
            boolean res = (enableNative() == 0);
            if (res) {
                int retryCount = 2;
                boolean running = false;
                while ((retryCount-- > 0) && !running) {
                    mEventLoop.start();
                    // it may take a momement for the other thread to do its 
                    // thing.  Check periodically for a while.
                    int pollCount = 5;
                    while ((pollCount-- > 0) && !running) {
                        if (mEventLoop.isEventLoopRunning()) {
                            running = true;
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {}
                    }
                }
                if (!running) {
                    log("bt EnableThread giving up");
                    res = false;
                    disableNative();
                }
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
                if (mSaveSetting) {
                    persistBluetoothOnSetting(true);
                }
                mIsDiscovering = false;
                mBondState.loadBondState();
                mHandler.sendMessageDelayed(mHandler.obtainMessage(REGISTER_SDP_RECORDS), 3000);

                // Update mode
                mEventLoop.onModeChanged(getModeNative());
            }
            Intent intent = null;
            if (res) {
                intent = new Intent(BluetoothIntent.ENABLED_ACTION);
            } else {
                intent = new Intent(BluetoothIntent.DISABLED_ACTION);
            }
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);

            mEnableThread = null;
        }
    }

    private void persistBluetoothOnSetting(boolean bluetoothOn) {
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.BLUETOOTH_ON,
                bluetoothOn ? 1 : 0);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

    private native int enableNative();
    private native int disableNative();

    /* package */ BondState getBondState() {
        return mBondState;
    }

    /** local cache of bonding state.
    /* we keep our own state to track the intermediate state BONDING, which
    /* bluez does not track.
     * All addreses must be passed in upper case.
     */
    public class BondState {
        private final HashMap<String, Integer> mState = new HashMap<String, Integer>();
        private final HashMap<String, Integer> mPinAttempt = new HashMap<String, Integer>();
        private final ArrayList<String> mAutoPairingFailures = new ArrayList<String>();
        // List of all the vendor_id prefix of Bluetooth addresses for which
        // auto pairing is not attempted
        private final ArrayList<String>  mAutoPairingBlacklisted =
                new ArrayList<String>(Arrays.asList(
                        "00:02:C7", "00:16:FE", "00:19:C1", "00:1B:FB", "00:1E:3D", //ALPS
                        "00:21:4F", "00:23:06", "00:24:33", "00:A0:79", // ALPS
                        "00:0E:6D", "00:13:E0", "00:21:E8", "00:60:57",// Murata for Prius 2007
                        "00:0E:9F" // TEMIC SDS  for Porsche
                        ));

        public synchronized void loadBondState() {
            if (!mIsEnabled) {
                return;
            }
            String[] bonds = listBondingsNative();
            if (bonds == null) {
                return;
            }
            mState.clear();
            if (DBG) log("found " + bonds.length + " bonded devices");
            for (String address : bonds) {
                mState.put(address.toUpperCase(), BluetoothDevice.BOND_BONDED);
            }
        }

        public synchronized void setBondState(String address, int state) {
            setBondState(address, state, 0);
        }

        /** reason is ignored unless state == BOND_NOT_BONDED */
        public synchronized void setBondState(String address, int state, int reason) {
            int oldState = getBondState(address);
            if (oldState == state) {
                return;
            }
            if (DBG) log(address + " bond state " + oldState + " -> " + state + " (" +
                         reason + ")");
            Intent intent = new Intent(BluetoothIntent.BOND_STATE_CHANGED_ACTION);
            intent.putExtra(BluetoothIntent.ADDRESS, address);
            intent.putExtra(BluetoothIntent.BOND_STATE, state);
            intent.putExtra(BluetoothIntent.BOND_PREVIOUS_STATE, oldState);
            if (state == BluetoothDevice.BOND_NOT_BONDED) {
                if (reason <= 0) {
                    Log.w(TAG, "setBondState() called to unbond device, but reason code is " +
                          "invalid. Overriding reason code with BOND_RESULT_REMOVED");
                    reason = BluetoothDevice.UNBOND_REASON_REMOVED;
                }
                intent.putExtra(BluetoothIntent.REASON, reason);
                mState.remove(address);
            } else {
                mState.put(address, state);
            }

            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        }

        public boolean isAutoPairingBlacklisted(String address) {
            for (String blacklistAddress : mAutoPairingBlacklisted) {
                if (address.startsWith(blacklistAddress)) return true;
            }
            return false;
        }

        public synchronized int getBondState(String address) {
            Integer state = mState.get(address);
            if (state == null) {
                return BluetoothDevice.BOND_NOT_BONDED;
            }
            return state.intValue();
        }

        private synchronized String[] listInState(int state) {
            ArrayList<String> result = new ArrayList<String>(mState.size());
            for (Map.Entry<String, Integer> e : mState.entrySet()) {
                if (e.getValue().intValue() == state) {
                    result.add(e.getKey());
                }
            }
            return result.toArray(new String[result.size()]);
        }

        public synchronized void addAutoPairingFailure(String address) {
            if (!mAutoPairingFailures.contains(address)) {
                mAutoPairingFailures.add(address);
            }
        }

        public synchronized boolean isAutoPairingAttemptsInProgress(String address) {
            return getAttempt(address) != 0;
        }

        public synchronized void clearPinAttempts(String address) {
            mPinAttempt.remove(address);
        }

        public synchronized boolean hasAutoPairingFailed(String address) {
            return mAutoPairingFailures.contains(address);
        }

        public synchronized int getAttempt(String address) {
            Integer attempt = mPinAttempt.get(address);
            if (attempt == null) {
                return 0;
            }
            return attempt.intValue();
        }

        public synchronized void attempt(String address) {
            Integer attempt = mPinAttempt.get(address);
            int newAttempt;
            if (attempt == null) {
                newAttempt = 1;
            } else {
                newAttempt = attempt.intValue() + 1;
            }
            mPinAttempt.put(address, new Integer(newAttempt));
        }

    }
    private native String[] listBondingsNative();

    private static String toBondStateString(int bondState) {
        switch (bondState) {
        case BluetoothDevice.BOND_NOT_BONDED:
            return "not bonded";
        case BluetoothDevice.BOND_BONDING:
            return "bonding";
        case BluetoothDevice.BOND_BONDED:
            return "bonded";
        default:
            return "??????";
        }
    }

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

    public synchronized boolean startDiscovery(boolean resolveNames) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return startDiscoveryNative(resolveNames);
    }
    private native boolean startDiscoveryNative(boolean resolveNames);

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

    public synchronized int getScanMode() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return bluezStringToScanMode(getModeNative());
    }
    private native String getModeNative();

    public synchronized boolean setScanMode(int mode) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        String bluezMode = scanModeToBluezString(mode);
        if (bluezMode != null) {
            return setModeNative(bluezMode);
        }
        return false;
    }
    private native boolean setModeNative(String mode);

    public synchronized boolean disconnectRemoteDeviceAcl(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return disconnectRemoteDeviceNative(address);
    }
    private native boolean disconnectRemoteDeviceNative(String address);

    public synchronized boolean createBond(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();

        String[] bonding = mBondState.listInState(BluetoothDevice.BOND_BONDING);
        if (bonding.length > 0 && !bonding[0].equals(address)) {
            log("Ignoring createBond(): another device is bonding");
            // a different device is currently bonding, fail
            return false;
        }

        // Check for bond state only if we are not performing auto
        // pairing exponential back-off attempts.
        if (!mBondState.isAutoPairingAttemptsInProgress(address) &&
                mBondState.getBondState(address) != BluetoothDevice.BOND_NOT_BONDED) {
            log("Ignoring createBond(): this device is already bonding or bonded");
            return false;
        }

        if (!createBondingNative(address, 60000 /* 1 minute */)) {
            return false;
        }

        mBondState.setBondState(address, BluetoothDevice.BOND_BONDING);
        return true;
    }
    private native boolean createBondingNative(String address, int timeout_ms);

    public synchronized boolean cancelBondProcess(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();
        if (mBondState.getBondState(address) != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        mBondState.setBondState(address, BluetoothDevice.BOND_NOT_BONDED,
                                BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        cancelBondingProcessNative(address);
        return true;
    }
    private native boolean cancelBondingProcessNative(String address);

    public synchronized boolean removeBond(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return removeBondingNative(address);
    }
    private native boolean removeBondingNative(String address);

    public synchronized String[] listBonds() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBondState.listInState(BluetoothDevice.BOND_BONDED);
    }

    public synchronized int getBondState(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR;
        }
        return mBondState.getBondState(address.toUpperCase());
    }

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

    private static final int MAX_OUTSTANDING_ASYNC = 32;

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
        address = address.toUpperCase();
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
        address = address.toUpperCase();
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
                        enable(null, false);
                    } else {
                        disable(false);
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mIsEnabled) {
            pw.println("\nBluetooth ENABLED: " + getAddress() + " (" + getName() + ")");
            pw.println("\nisDiscovering() = " + isDiscovering());

            BluetoothHeadset headset = new BluetoothHeadset(mContext, null);

            String[] addresses = listRemoteDevices();

            pw.println("\n--Known devices--");
            for (String address : addresses) {
                pw.printf("%s %10s (%d) %s\n", address,
                           toBondStateString(mBondState.getBondState(address)),
                           mBondState.getAttempt(address),
                           getRemoteName(address));
            }

            addresses = listAclConnections();
            pw.println("\n--ACL connected devices--");
            for (String address : addresses) {
                pw.println(address);
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

    /* package */ static int bluezStringToScanMode(String mode) {
        if (mode == null) {
            return BluetoothError.ERROR;
        }
        mode = mode.toLowerCase();
        if (mode.equals("off")) {
            return BluetoothDevice.SCAN_MODE_NONE;
        } else if (mode.equals("connectable")) {
            return BluetoothDevice.SCAN_MODE_CONNECTABLE;
        } else if (mode.equals("discoverable")) {
            return BluetoothDevice.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        } else {
            return BluetoothError.ERROR;
        }
    }

    /* package */ static String scanModeToBluezString(int mode) {
        switch (mode) {
        case BluetoothDevice.SCAN_MODE_NONE:
            return "off";
        case BluetoothDevice.SCAN_MODE_CONNECTABLE:
            return "connectable";
        case BluetoothDevice.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
            return "discoverable";
        }
        return null;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
