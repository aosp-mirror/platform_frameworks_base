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
 * java/services/com/android/server/BluetoothService.java
 * and make the contructor package private again.
 *
 * @hide
 */

package android.server;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemService;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.app.IBatteryStats;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class BluetoothService extends IBluetooth.Stub {
    private static final String TAG = "BluetoothService";
    private static final boolean DBG = true;

    private int mNativeData;
    private BluetoothEventLoop mEventLoop;
    private IntentFilter mIntentFilter;
    private boolean mIsAirplaneSensitive;
    private int mBluetoothState;
    private boolean mRestart = false;  // need to call enable() after disable()
    private boolean mIsDiscovering;

    private BluetoothAdapter mAdapter;  // constant after init()
    private final BondState mBondState = new BondState();  // local cache of bondings
    private final IBatteryStats mBatteryStats;
    private final Context mContext;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final int MESSAGE_REGISTER_SDP_RECORDS = 1;
    private static final int MESSAGE_FINISH_DISABLE = 2;

    private final Map<String, String> mAdapterProperties;
    private final HashMap <String, Map<String, String>> mDeviceProperties;

    static {
        classInitNative();
    }

    public BluetoothService(Context context) {
        mContext = context;

        // Need to do this in place of:
        // mBatteryStats = BatteryStatsService.getService();
        // Since we can not import BatteryStatsService from here. This class really needs to be
        // moved to java/services/com/android/server/
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

        initializeNativeDataNative();

        if (isEnabledNative() == 1) {
            Log.w(TAG, "Bluetooth daemons already running - runtime restart? ");
            disableNative();
        }

        mBluetoothState = BluetoothAdapter.BLUETOOTH_STATE_OFF;
        mIsDiscovering = false;
        mAdapterProperties = new HashMap<String, String>();
        mDeviceProperties = new HashMap<String, Map<String,String>>();
        registerForAirplaneMode();
    }

    public synchronized void initAfterRegistration() {
        mAdapter = (BluetoothAdapter) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mEventLoop = new BluetoothEventLoop(mContext, mAdapter, this);
    }

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

    public boolean isEnabled() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothState == BluetoothAdapter.BLUETOOTH_STATE_ON;
    }

    public int getBluetoothState() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBluetoothState;
    }


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
     */
    public synchronized boolean disable(boolean saveSetting) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        switch (mBluetoothState) {
        case BluetoothAdapter.BLUETOOTH_STATE_OFF:
            return true;
        case BluetoothAdapter.BLUETOOTH_STATE_ON:
            break;
        default:
            return false;
        }
        if (mEnableThread != null && mEnableThread.isAlive()) {
            return false;
        }
        setBluetoothState(BluetoothAdapter.BLUETOOTH_STATE_TURNING_OFF);

        // Allow 3 seconds for profiles to gracefully disconnect
        // TODO: Introduce a callback mechanism so that each profile can notify
        // BluetoothService when it is done shutting down
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MESSAGE_FINISH_DISABLE, saveSetting ? 1 : 0, 0), 3000);
        return true;
    }


    private synchronized void finishDisable(boolean saveSetting) {
        if (mBluetoothState != BluetoothAdapter.BLUETOOTH_STATE_TURNING_OFF) {
            return;
        }
        mEventLoop.stop();
        tearDownNativeDataNative();
        disableNative();

        // mark in progress bondings as cancelled
        for (String address : mBondState.listInState(BluetoothDevice.BOND_BONDING)) {
            mBondState.setBondState(address, BluetoothDevice.BOND_NOT_BONDED,
                                    BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        }

        // update mode
        Intent intent = new Intent(BluetoothIntent.SCAN_MODE_CHANGED_ACTION);
        intent.putExtra(BluetoothIntent.SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);

        mIsDiscovering = false;
        mAdapterProperties.clear();

        if (saveSetting) {
            persistBluetoothOnSetting(false);
        }

        setBluetoothState(BluetoothAdapter.BLUETOOTH_STATE_OFF);

        // Log bluetooth off to battery stats.
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteBluetoothOff();
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (mRestart) {
            mRestart = false;
            enable();
        }
    }

    /** Bring up BT and persist BT on in settings */
    public boolean enable() {
        return enable(true);
    }

    /**
     * Enable this Bluetooth device, asynchronously.
     * This turns on/off the underlying hardware.
     *
     * @param saveSetting If true, persist the new state of BT in settings
     * @return True on success (so far)
     */
    public synchronized boolean enable(boolean saveSetting) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");

        // Airplane mode can prevent Bluetooth radio from being turned on.
        if (mIsAirplaneSensitive && isAirplaneModeOn()) {
            return false;
        }
        if (mBluetoothState != BluetoothAdapter.BLUETOOTH_STATE_OFF) {
            return false;
        }
        if (mEnableThread != null && mEnableThread.isAlive()) {
            return false;
        }
        setBluetoothState(BluetoothAdapter.BLUETOOTH_STATE_TURNING_ON);
        mEnableThread = new EnableThread(saveSetting);
        mEnableThread.start();
        return true;
    }

    /** Forcibly restart Bluetooth if it is on */
    /* package */ synchronized void restart() {
        if (mBluetoothState != BluetoothAdapter.BLUETOOTH_STATE_ON) {
            return;
        }
        mRestart = true;
        if (!disable(false)) {
            mRestart = false;
        }
    }

    private synchronized void setBluetoothState(int state) {
        if (state == mBluetoothState) {
            return;
        }

        if (DBG) log("Bluetooth state " + mBluetoothState + " -> " + state);

        Intent intent = new Intent(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION);
        intent.putExtra(BluetoothIntent.BLUETOOTH_PREVIOUS_STATE, mBluetoothState);
        intent.putExtra(BluetoothIntent.BLUETOOTH_STATE, state);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        mBluetoothState = state;

        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_REGISTER_SDP_RECORDS:
                //TODO: Don't assume HSP/HFP is running, don't use sdptool,
                if (isEnabled()) {
                    SystemService.start("hsag");
                    SystemService.start("hfag");
                    SystemService.start("opush");
                    SystemService.start("pbap");
                }
                break;
            case MESSAGE_FINISH_DISABLE:
                finishDisable(msg.arg1 != 0);
                break;
            }
        }
    };

    private EnableThread mEnableThread;

    private class EnableThread extends Thread {
        private final boolean mSaveSetting;
        public EnableThread(boolean saveSetting) {
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


            if (res) {
                if (!setupNativeDataNative()) {
                    return;
                }
                if (mSaveSetting) {
                    persistBluetoothOnSetting(true);
                }
                mIsDiscovering = false;
                mBondState.loadBondState();
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MESSAGE_REGISTER_SDP_RECORDS),
                                            3000);

                // Log bluetooth on to battery stats.
                long ident = Binder.clearCallingIdentity();
                try {
                    mBatteryStats.noteBluetoothOn();
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            mEnableThread = null;

            setBluetoothState(res ?
                              BluetoothAdapter.BLUETOOTH_STATE_ON :
                              BluetoothAdapter.BLUETOOTH_STATE_OFF);

            if (res) {
                // Update mode
                String[] propVal = {"Pairable", getProperty("Pairable")};
                mEventLoop.onPropertyChanged(propVal);
            }

            if (mIsAirplaneSensitive && isAirplaneModeOn()) {
                disable(false);
            }

        }
    }

    private void persistBluetoothOnSetting(boolean bluetoothOn) {
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        Settings.Secure.putInt(mContext.getContentResolver(), Settings.Secure.BLUETOOTH_ON,
                bluetoothOn ? 1 : 0);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

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
        // List of all the vendor_id prefix of Bluetooth addresses for
        // which auto pairing is not attempted.
        // The following companies are included in the list below:
        // ALPS (lexus), Murata (Prius 2007, Nokia 616), TEMIC SDS (Porsche, Audi),
        // Parrot, Zhongshan General K-mate Electronics, Great Well
        // Electronics, Flaircomm Electronics, Jatty Electronics, Delphi,
        // Clarion, Novero, Denso (Lexus, Toyota), Johnson Controls (Acura),
        // Continental Automotive, Harman/Becker
        private final ArrayList<String>  mAutoPairingBlacklisted =
                new ArrayList<String>(Arrays.asList(
                        "00:02:C7", "00:16:FE", "00:19:C1", "00:1B:FB", "00:1E:3D", "00:21:4F",
                        "00:23:06", "00:24:33", "00:A0:79", "00:0E:6D", "00:13:E0", "00:21:E8",
                        "00:60:57", "00:0E:9F", "00:12:1C", "00:18:91", "00:18:96", "00:13:04",
                        "00:16:FD", "00:22:A0", "00:0B:4C", "00:60:6F", "00:23:3D", "00:C0:59",
                        "00:0A:30", "00:1E:AE", "00:1C:D7"
                        ));

        public synchronized void loadBondState() {
            if (mBluetoothState != BluetoothAdapter.BLUETOOTH_STATE_TURNING_ON) {
                return;
            }
            String []bonds = null;
            String val = getProperty("Devices");
            if (val != null) {
                bonds = val.split(",");
            }
            if (bonds == null) {
                return;
            }
            mState.clear();
            if (DBG) log("found " + bonds.length + " bonded devices");
            for (String device : bonds) {
                mState.put(getAddressFromObjectPath(device).toUpperCase(),
                        BluetoothDevice.BOND_BONDED);
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
            intent.putExtra(BluetoothIntent.DEVICE, mAdapter.getRemoteDevice(address));
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

    /*package*/synchronized void getAllProperties() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        mAdapterProperties.clear();

        String properties[] = (String [])getAdapterPropertiesNative();
        // The String Array consists of key-value pairs.
        if (properties == null) {
            Log.e(TAG, "*Error*: GetAdapterProperties returned NULL");
            return;
        }

        for (int i = 0; i < properties.length; i++) {
            String name = properties[i];
            String newValue;
            int len;
            if (name == null) {
                Log.e(TAG, "Error:Adapter Property at index" + i + "is null");
                continue;
            }
            if (name.equals("Devices")) {
                len = Integer.valueOf(properties[++i]);
                if (len != 0)
                    newValue = "";
                else
                    newValue = null;
                for (int j = 0; j < len; j++) {
                    newValue += properties[++i] + ",";
                }
            } else {
                newValue = properties[++i];
            }
            mAdapterProperties.put(name, newValue);
        }

        // Add adapter object path property.
        String adapterPath = getAdapterPathNative();
        if (adapterPath != null)
            mAdapterProperties.put("ObjectPath", adapterPath + "/dev_");
    }

    /* package */ synchronized void setProperty(String name, String value) {
        mAdapterProperties.put(name, value);
    }

    public synchronized boolean setName(String name) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (name == null) {
            return false;
        }
        return setPropertyString("Name", name);
    }

    //TODO(): setPropertyString, setPropertyInteger, setPropertyBoolean
    // Either have a single property function with Object as the parameter
    // or have a function for each property and then obfuscate in the JNI layer.
    // The following looks dirty.
    private boolean setPropertyString(String key, String value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return setAdapterPropertyStringNative(key, value);
    }

    private boolean setPropertyInteger(String key, int value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return setAdapterPropertyIntegerNative(key, value);
    }

    private boolean setPropertyBoolean(String key, boolean value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return setAdapterPropertyBooleanNative(key, value ? 1 : 0);
    }

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
        return setPropertyInteger("DiscoverableTimeout", timeout);
    }

    public synchronized boolean setScanMode(int mode) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        boolean pairable = false, discoverable = false;
        String modeString = scanModeToBluezString(mode);
        if (modeString.equals("off")) {
            pairable = false;
            discoverable = false;
        } else if (modeString.equals("pariable")) {
            pairable = true;
            discoverable = false;
        } else if (modeString.equals("discoverable")) {
            pairable = true;
            discoverable = true;
        }
        setPropertyBoolean("Pairable", pairable);
        setPropertyBoolean("Discoverable", discoverable);

        return true;
    }

    /*package*/ synchronized String getProperty (String name) {
        if (!mAdapterProperties.isEmpty())
            return mAdapterProperties.get(name);
        getAllProperties();
        return mAdapterProperties.get(name);
    }

    public synchronized String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getProperty("Address");
    }

    public synchronized String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getProperty("Name");
    }

    /**
     * Returns the user-friendly name of a remote device.  This value is
     * returned from our local cache, which is updated when onPropertyChange
     * event is received.
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
        Map <String, String> properties = mDeviceProperties.get(address);
        if (properties != null) return properties.get("Name");
        return null;
    }

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
        String timeout = getProperty("DiscoverableTimeout");
        if (timeout != null)
           return Integer.valueOf(timeout);
        else
            return -1;
    }

    public synchronized int getScanMode() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabled())
            return BluetoothError.ERROR;

        boolean pairable = getProperty("Pairable").equals("true");
        boolean discoverable = getProperty("Discoverable").equals("true");
        return bluezStringToScanMode (pairable, discoverable);
    }

    public synchronized boolean startDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabled()) {
            return false;
        }
        return startDiscoveryNative();
    }

    public synchronized boolean cancelDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return stopDiscoveryNative();
    }

    public synchronized boolean isDiscovering() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mIsDiscovering;
    }

    /* package */ void setIsDiscovering(boolean isDiscovering) {
        mIsDiscovering = isDiscovering;
    }

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

        if (!createPairedDeviceNative(address, 60000 /* 1 minute */)) {
            return false;
        }

        mBondState.setBondState(address, BluetoothDevice.BOND_BONDING);
        return true;
    }

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
        cancelDeviceCreationNative(address);
        return true;
    }

    public synchronized boolean removeBond(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        return removeDeviceNative(getObjectPathFromAddress(address));
    }

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

    /*package*/ boolean isRemoteDeviceInCache(String address) {
        return (mDeviceProperties.get(address) != null);
    }

    /*package*/ String[] getRemoteDeviceProperties(String address) {
        String objectPath = getObjectPathFromAddress(address);
        return (String [])getDevicePropertiesNative(objectPath);
    }

    /*package*/ synchronized String getRemoteDeviceProperty(String address, String property) {
        Map<String, String> properties = mDeviceProperties.get(address);
        if (properties != null) {
            return properties.get(property);
        } else {
            // Query for remote device properties, again.
            // We will need to reload the cache when we switch Bluetooth on / off
            // or if we crash.
            String[] propValues = getRemoteDeviceProperties(address);
            if (propValues != null) {
                addRemoteDeviceProperties(address, propValues);
                return getRemoteDeviceProperty(address, property);
            }
        }
        Log.e(TAG, "getRemoteDeviceProperty: " + property + "not present:" + address);
        return null;
    }

    /* package */ synchronized void addRemoteDeviceProperties(String address, String[] properties) {
        /*
         * We get a DeviceFound signal every time RSSI changes or name changes.
         * Don't create a new Map object every time */
        Map<String, String> propertyValues = mDeviceProperties.get(address);
        if (propertyValues != null) {
            propertyValues.clear();
        } else {
            propertyValues = new HashMap<String, String>();
        }

        for (int i = 0; i < properties.length; i++) {
            String name = properties[i];
            String newValue;
            int len;
            if (name == null) {
                Log.e(TAG, "Error: Remote Device Property at index" + i + "is null");
                continue;
            }
            if (name.equals("UUIDs") || name.equals("Nodes")) {
                len = Integer.valueOf(properties[++i]);
                if (len != 0)
                    newValue = "";
                else
                    newValue = null;
                for (int j = 0; j < len; j++) {
                    newValue += properties[++i] + ",";
                }
            } else {
                newValue = properties[++i];
            }
            propertyValues.put(name, newValue);
        }
        mDeviceProperties.put(address, propertyValues);
    }

    /* package */ void removeRemoteDeviceProperties(String address) {
        mDeviceProperties.remove(address);
    }

    /* package */ synchronized void setRemoteDeviceProperty(String address, String name,
                                                              String value) {
        Map <String, String> propVal = mDeviceProperties.get(address);
        if (propVal != null) {
            propVal.put(name, value);
            mDeviceProperties.put(address, propVal);
        } else {
            Log.e(TAG, "setRemoteDeviceProperty for a device not in cache:" + address);
        }
    }

    /**
     * Gets the remote major, minor classes encoded as a 32-bit
     * integer.
     *
     * Note: this value is retrieved from cache, because we get it during
     *       remote-device discovery.
     *
     * @return 32-bit integer encoding the remote major, minor, and service
     *         classes.
     */
    public synchronized int getRemoteClass(String address) {
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return BluetoothClass.ERROR;
        }
        String val = getRemoteDeviceProperty(address, "Class");
        if (val == null)
            return BluetoothClass.ERROR;
        else {
            return Integer.valueOf(val);
        }
    }


    /**
     * Gets the remote features encoded as bit mask.
     *
     * Note: This method may be obsoleted soon.
     *
     * @return String array of 128bit UUIDs
     */
    public synchronized String[] getRemoteUuids(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return null;
        }
        String value = getRemoteDeviceProperty(address, "UUIDs");
        String[] uuids = null;
        // The UUIDs are stored as a "," separated string.
        if (value != null)
             uuids = value.split(",");
        return uuids;
    }

    /**
     * Gets the rfcomm channel associated with the UUID.
     *
     * @param address Address of the remote device
     * @param uuid UUID of the service attribute
     *
     * @return rfcomm channel associated with the service attribute
     */
    public int getRemoteServiceChannel(String address, String uuid) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return BluetoothError.ERROR_IPC;
        }
        return getDeviceServiceChannelNative(getObjectPathFromAddress(address), uuid, 0x0004);
    }

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

    public synchronized boolean setPasskey(String address, int passkey) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (passkey < 0 || passkey > 999999 || !BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setPasskey(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }
        return setPasskeyNative(address, passkey, data.intValue());
    }

    public synchronized boolean setPairingConfirmation(String address, boolean confirm) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setPasskey(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }
        return setPairingConfirmationNative(address, confirm, data.intValue());
    }

    public synchronized boolean cancelPairingUserInput(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!BluetoothDevice.checkBluetoothAddress(address)) {
            return false;
        }
        mBondState.setBondState(address, BluetoothDevice.BOND_NOT_BONDED,
                BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "cancelUserInputNative(" + address + ") called but no native data " +
                "available, ignoring. Maybe the PasskeyAgent Request was already cancelled " +
                "by the remote or by bluez.\n");
            return false;
        }
        return cancelPairingUserInputNative(address, data.intValue());
    }

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
                        enable(false);
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
        pw.println("\nmIsAirplaneSensitive = " + mIsAirplaneSensitive + "\n");

        switch(mBluetoothState) {
        case BluetoothAdapter.BLUETOOTH_STATE_OFF:
            pw.println("\nBluetooth OFF\n");
            return;
        case BluetoothAdapter.BLUETOOTH_STATE_TURNING_ON:
            pw.println("\nBluetooth TURNING ON\n");
            return;
        case BluetoothAdapter.BLUETOOTH_STATE_TURNING_OFF:
            pw.println("\nBluetooth TURNING OFF\n");
            return;
        case BluetoothAdapter.BLUETOOTH_STATE_ON:
            pw.println("\nBluetooth ON\n");
        }

        pw.println("\nLocal address = " + getAddress());
        pw.println("\nLocal name = " + getName());
        pw.println("\nisDiscovering() = " + isDiscovering());

        BluetoothHeadset headset = new BluetoothHeadset(mContext, null);

        pw.println("\n--Known devices--");
        for (String address : mDeviceProperties.keySet()) {
            pw.printf("%s %10s (%d) %s\n", address,
                       toBondStateString(mBondState.getBondState(address)),
                       mBondState.getAttempt(address),
                       getRemoteName(address));
        }

        String value = getProperty("Devices");
        String []devicesObjectPath = null;
        if (value != null) {
            devicesObjectPath = value.split(",");
        }
        pw.println("\n--ACL connected devices--");
        for (String device : devicesObjectPath) {
            pw.println(getAddressFromObjectPath(device));
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
        pw.println("getCurrentHeadset() = " + headset.getCurrentHeadset());
        pw.println("getBatteryUsageHint() = " + headset.getBatteryUsageHint());

        headset.close();
    }

    /* package */ static int bluezStringToScanMode(boolean pairable, boolean discoverable) {
        if (pairable && discoverable)
            return BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        else if (pairable && !discoverable)
            return BluetoothAdapter.SCAN_MODE_CONNECTABLE;
        else
            return BluetoothAdapter.SCAN_MODE_NONE;
    }

    /* package */ static String scanModeToBluezString(int mode) {
        switch (mode) {
        case BluetoothAdapter.SCAN_MODE_NONE:
            return "off";
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
            return "connectable";
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
            return "discoverable";
        }
        return null;
    }

    /*package*/ String getAddressFromObjectPath(String objectPath) {
        String adapterObjectPath = getProperty("ObjectPath");
        if (adapterObjectPath == null || objectPath == null) {
            Log.e(TAG, "getAddressFromObjectPath: AdpaterObjectPath:" + adapterObjectPath +
                    "  or deviceObjectPath:" + objectPath + " is null");
            return null;
        }
        if (!objectPath.startsWith(adapterObjectPath)) {
            Log.e(TAG, "getAddressFromObjectPath: AdpaterObjectPath:" + adapterObjectPath +
                    "  is not a prefix of deviceObjectPath:" + objectPath +
                    "bluetoothd crashed ?");
            return null;
        }
        String address = objectPath.substring(adapterObjectPath.length());
        if (address != null) return address.replace('_', ':');

        Log.e(TAG, "getAddressFromObjectPath: Address being returned is null");
        return null;
    }

    /*package*/ String getObjectPathFromAddress(String address) {
        String path = getProperty("ObjectPath");
        if (path == null) {
            Log.e(TAG, "Error: Object Path is null");
            return null;
        }
        path = path + address.replace(":", "_");
        return path;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private native static void classInitNative();
    private native void initializeNativeDataNative();
    private native boolean setupNativeDataNative();
    private native boolean tearDownNativeDataNative();
    private native void cleanupNativeDataNative();
    private native String getAdapterPathNative();

    private native int isEnabledNative();
    private native int enableNative();
    private native int disableNative();

    private native Object[] getAdapterPropertiesNative();
    private native Object[] getDevicePropertiesNative(String objectPath);
    private native boolean setAdapterPropertyStringNative(String key, String value);
    private native boolean setAdapterPropertyIntegerNative(String key, int value);
    private native boolean setAdapterPropertyBooleanNative(String key, int value);

    private native boolean startDiscoveryNative();
    private native boolean stopDiscoveryNative();

    private native boolean createPairedDeviceNative(String address, int timeout_ms);
    private native boolean cancelDeviceCreationNative(String address);
    private native boolean removeDeviceNative(String objectPath);
    private native int getDeviceServiceChannelNative(String objectPath, String uuid,
            int attributeId);

    private native boolean cancelPairingUserInputNative(String address, int nativeData);
    private native boolean setPinNative(String address, String pin, int nativeData);
    private native boolean setPasskeyNative(String address, int passkey, int nativeData);
    private native boolean setPairingConfirmationNative(String address, boolean confirm,
            int nativeData);

}
