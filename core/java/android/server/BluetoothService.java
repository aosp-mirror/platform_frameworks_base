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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDeviceProfileState;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHealthAppConfiguration;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfileState;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothHealthCallback;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;

import com.android.internal.app.IBatteryStats;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BluetoothService extends IBluetooth.Stub {
    private static final String TAG = "BluetoothService";
    private static final boolean DBG = true;

    private int mNativeData;
    private BluetoothEventLoop mEventLoop;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothInputDevice mInputDevice;
    private BluetoothPan mPan;
    private boolean mIsAirplaneSensitive;
    private boolean mIsAirplaneToggleable;
    private BluetoothAdapterStateMachine mBluetoothState;
    private int[] mAdapterSdpHandles;
    private ParcelUuid[] mAdapterUuids;

    private BluetoothAdapter mAdapter;  // constant after init()
    private final BluetoothBondState mBondState;  // local cache of bondings
    private final IBatteryStats mBatteryStats;
    private final Context mContext;
    private Map<Integer, IBluetoothStateChangeCallback> mStateChangeTracker =
        Collections.synchronizedMap(new HashMap<Integer, IBluetoothStateChangeCallback>());

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String DOCK_ADDRESS_PATH = "/sys/class/switch/dock/bt_addr";
    private static final String DOCK_PIN_PATH = "/sys/class/switch/dock/bt_pin";

    private static final String SHARED_PREFERENCE_DOCK_ADDRESS = "dock_bluetooth_address";
    private static final String SHARED_PREFERENCES_NAME = "bluetooth_service_settings";

    private static final int MESSAGE_UUID_INTENT = 1;
    private static final int MESSAGE_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 2;
    private static final int MESSAGE_REMOVE_SERVICE_RECORD = 3;

    private static final int RFCOMM_RECORD_REAPER = 10;
    private static final int STATE_CHANGE_REAPER = 11;

    // The time (in millisecs) to delay the pairing attempt after the first
    // auto pairing attempt fails. We use an exponential delay with
    // INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY as the initial value and
    // MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY as the max value.
    private static final long INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 3000;
    private static final long MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY = 12000;

    // The timeout used to sent the UUIDs Intent
    // This timeout should be greater than the page timeout
    private static final int UUID_INTENT_DELAY = 6000;

    /** Always retrieve RFCOMM channel for these SDP UUIDs */
    private static final ParcelUuid[] RFCOMM_UUIDS = {
            BluetoothUuid.Handsfree,
            BluetoothUuid.HSP,
            BluetoothUuid.ObexObjectPush };

    private final BluetoothAdapterProperties mAdapterProperties;
    private final BluetoothDeviceProperties mDeviceProperties;

    private final HashMap<String, Map<ParcelUuid, Integer>> mDeviceServiceChannelCache;
    private final ArrayList<String> mUuidIntentTracker;
    private final HashMap<RemoteService, IBluetoothCallback> mUuidCallbackTracker;

    private final HashMap<Integer, Integer> mServiceRecordToPid;

    private final HashMap<String, BluetoothDeviceProfileState> mDeviceProfileState;
    private final BluetoothProfileState mA2dpProfileState;
    private final BluetoothProfileState mHfpProfileState;

    private BluetoothA2dpService mA2dpService;
    private final HashMap<String, Pair<byte[], byte[]>> mDeviceOobData;

    private int mProfilesConnected = 0, mProfilesConnecting = 0, mProfilesDisconnecting = 0;

    private static String mDockAddress;
    private String mDockPin;

    private int mAdapterConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
    private BluetoothPanProfileHandler mBluetoothPanProfileHandler;
    private BluetoothInputProfileHandler mBluetoothInputProfileHandler;
    private BluetoothHealthProfileHandler mBluetoothHealthProfileHandler;
    private static final String INCOMING_CONNECTION_FILE =
      "/data/misc/bluetooth/incoming_connection.conf";
    private HashMap<String, Pair<Integer, String>> mIncomingConnections;
    private HashMap<Integer, Pair<Integer, Integer>> mProfileConnectionState;

    private static class RemoteService {
        public String address;
        public ParcelUuid uuid;
        public RemoteService(String address, ParcelUuid uuid) {
            this.address = address;
            this.uuid = uuid;
        }
        @Override
        public boolean equals(Object o) {
            if (o instanceof RemoteService) {
                RemoteService service = (RemoteService)o;
                return address.equals(service.address) && uuid.equals(service.uuid);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            hash = hash * 31 + (address == null ? 0 : address.hashCode());
            hash = hash * 31 + (uuid == null ? 0 : uuid.hashCode());
            return hash;
        }
    }

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

        mBondState = new BluetoothBondState(context, this);
        mAdapterProperties = new BluetoothAdapterProperties(context, this);
        mDeviceProperties = new BluetoothDeviceProperties(this);

        mDeviceServiceChannelCache = new HashMap<String, Map<ParcelUuid, Integer>>();
        mDeviceOobData = new HashMap<String, Pair<byte[], byte[]>>();
        mUuidIntentTracker = new ArrayList<String>();
        mUuidCallbackTracker = new HashMap<RemoteService, IBluetoothCallback>();
        mServiceRecordToPid = new HashMap<Integer, Integer>();
        mDeviceProfileState = new HashMap<String, BluetoothDeviceProfileState>();
        mA2dpProfileState = new BluetoothProfileState(mContext, BluetoothProfileState.A2DP);
        mHfpProfileState = new BluetoothProfileState(mContext, BluetoothProfileState.HFP);

        mHfpProfileState.start();
        mA2dpProfileState.start();

        IntentFilter filter = new IntentFilter();
        registerForAirplaneMode(filter);

        filter.addAction(Intent.ACTION_DOCK_EVENT);
        mContext.registerReceiver(mReceiver, filter);
        mBluetoothInputProfileHandler = BluetoothInputProfileHandler.getInstance(mContext, this);
        mBluetoothPanProfileHandler = BluetoothPanProfileHandler.getInstance(mContext, this);
        mBluetoothHealthProfileHandler = BluetoothHealthProfileHandler.getInstance(mContext, this);
        mIncomingConnections = new HashMap<String, Pair<Integer, String>>();
        mProfileConnectionState = new HashMap<Integer, Pair<Integer, Integer>>();
    }

    public static synchronized String readDockBluetoothAddress() {
        if (mDockAddress != null) return mDockAddress;

        BufferedInputStream file = null;
        String dockAddress;
        try {
            file = new BufferedInputStream(new FileInputStream(DOCK_ADDRESS_PATH));
            byte[] address = new byte[17];
            file.read(address);
            dockAddress = new String(address);
            dockAddress = dockAddress.toUpperCase();
            if (BluetoothAdapter.checkBluetoothAddress(dockAddress)) {
                mDockAddress = dockAddress;
                return mDockAddress;
            } else {
                Log.e(TAG, "CheckBluetoothAddress failed for car dock address: "
                        + dockAddress);
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException while trying to read dock address");
        } catch (IOException e) {
            Log.e(TAG, "IOException while trying to read dock address");
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        mDockAddress = null;
        return null;
    }

    private synchronized boolean writeDockPin() {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(DOCK_PIN_PATH));

            // Generate a random 4 digit pin between 0000 and 9999
            // This is not truly random but good enough for our purposes.
            int pin = (int) Math.floor(Math.random() * 10000);

            mDockPin = String.format("%04d", pin);
            out.write(mDockPin);
            return true;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException while trying to write dock pairing pin");
        } catch (IOException e) {
            Log.e(TAG, "IOException while while trying to write dock pairing pin");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        mDockPin = null;
        return false;
    }

    /*package*/ synchronized String getDockPin() {
        return mDockPin;
    }

    public synchronized void initAfterRegistration() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState = new BluetoothAdapterStateMachine(mContext, this, mAdapter);
        mBluetoothState.start();
        mEventLoop = mBluetoothState.getBluetoothEventLoop();
    }

    public synchronized void initAfterA2dpRegistration() {
        mEventLoop.getProfileProxy();
    }

    @Override
    protected void finalize() throws Throwable {
        mContext.unregisterReceiver(mReceiver);
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }

    public boolean isEnabled() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return isEnabledInternal();
    }

    private boolean isEnabledInternal() {
        return (getBluetoothStateInternal() == BluetoothAdapter.STATE_ON);
    }

    public int getBluetoothState() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getBluetoothStateInternal();
    }

    int getBluetoothStateInternal() {
        return mBluetoothState.getBluetoothAdapterState();
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
     * @param saveSetting If true, persist the new setting
     */
    public synchronized boolean disable(boolean saveSetting) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");

        int adapterState = getBluetoothStateInternal();

        switch (adapterState) {
        case BluetoothAdapter.STATE_OFF:
            return true;
        case BluetoothAdapter.STATE_ON:
            break;
        default:
            return false;
        }

        mBluetoothState.sendMessage(BluetoothAdapterStateMachine.USER_TURN_OFF, saveSetting);
        return true;
    }

    synchronized void disconnectDevices() {
        // Disconnect devices handled by BluetoothService.
        for (BluetoothDevice device: getConnectedInputDevices()) {
            disconnectInputDevice(device);
        }

        for (BluetoothDevice device: getConnectedPanDevices()) {
            disconnectPanDevice(device);
        }
    }

    /**
     * The Bluetooth has been turned off, but hot. Do bonding, profile,
     * and internal cleanup
     */
    synchronized void finishDisable() {
        // mark in progress bondings as cancelled
        for (String address : mBondState.listInState(BluetoothDevice.BOND_BONDING)) {
            mBondState.setBondState(address, BluetoothDevice.BOND_NONE,
                                    BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        }

        // Stop the profile state machine for bonded devices.
        for (String address : mBondState.listInState(BluetoothDevice.BOND_BONDED)) {
            removeProfileState(address);
        }

        // update mode
        Intent intent = new Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);

        mAdapterProperties.clear();
        mServiceRecordToPid.clear();

        mProfilesConnected = 0;
        mProfilesConnecting = 0;
        mProfilesDisconnecting = 0;
        mAdapterConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
        mAdapterUuids = null;
        mAdapterSdpHandles = null;

        // Log bluetooth off to battery stats.
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteBluetoothOff();
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * power off Bluetooth
     */
    synchronized void shutoffBluetooth() {
        if (mAdapterSdpHandles != null) removeReservedServiceRecordsNative(mAdapterSdpHandles);
        setBluetoothTetheringNative(false, BluetoothPanProfileHandler.NAP_ROLE,
                BluetoothPanProfileHandler.NAP_BRIDGE);
        tearDownNativeDataNative();
    }

    /**
     * Data clean up after Bluetooth shutoff
     */
    synchronized void cleanNativeAfterShutoffBluetooth() {
        // Ths method is called after shutdown of event loop in the Bluetooth shut down
        // procedure

        // the adapter property could be changed before event loop is stoped, clear it again
        mAdapterProperties.clear();
        disableNative();
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
        if (mIsAirplaneSensitive && isAirplaneModeOn() && !mIsAirplaneToggleable) {
            return false;
        }
        mBluetoothState.sendMessage(BluetoothAdapterStateMachine.USER_TURN_ON, saveSetting);
        return true;
    }

    /**
     * Turn on Bluetooth Module, Load firmware, and do all the preparation
     * needed to get the Bluetooth Module ready but keep it not discoverable
     * and not connectable.
     */
    /* package */ synchronized boolean prepareBluetooth() {
        if (!setupNativeDataNative()) {
            return false;
        }
        switchConnectable(false);
        updateSdpRecords();
        return true;
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_UUID_INTENT:
                String address = (String)msg.obj;
                if (address != null) {
                    sendUuidIntent(address);
                    makeServiceChannelCallbacks(address);
                }
                break;
            case MESSAGE_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY:
                address = (String)msg.obj;
                if (address == null) return;
                int attempt = mBondState.getAttempt(address);

                // Try only if attemps are in progress and cap it 2 attempts
                // The 2 attempts cap is a fail safe if the stack returns
                // an incorrect error code for bonding failures and if the pin
                // is entered wrongly twice we should abort.
                if (attempt > 0 && attempt <= 2) {
                    mBondState.attempt(address);
                    createBond(address);
                    return;
                }
                if (attempt > 0) mBondState.clearPinAttempts(address);
                break;
            case MESSAGE_REMOVE_SERVICE_RECORD:
                Pair<Integer, Integer> pair = (Pair<Integer, Integer>) msg.obj;
                checkAndRemoveRecord(pair.first, pair.second);
                break;
            }
        }
    };

    private synchronized void addReservedSdpRecords(final ArrayList<ParcelUuid> uuids) {
        //Register SDP records.
        int[] svcIdentifiers = new int[uuids.size()];
        for (int i = 0; i < uuids.size(); i++) {
            svcIdentifiers[i] = BluetoothUuid.getServiceIdentifierFromParcelUuid(uuids.get(i));
        }
        mAdapterSdpHandles = addReservedServiceRecordsNative(svcIdentifiers);
    }

    private synchronized void updateSdpRecords() {
        ArrayList<ParcelUuid> uuids = new ArrayList<ParcelUuid>();

        // Add the default records
        uuids.add(BluetoothUuid.HSP_AG);
        uuids.add(BluetoothUuid.ObexObjectPush);

        if (mContext.getResources().
                getBoolean(com.android.internal.R.bool.config_voice_capable)) {
            uuids.add(BluetoothUuid.Handsfree_AG);
            uuids.add(BluetoothUuid.PBAP_PSE);
        }

        // Add SDP records for profiles maintained by Android userspace
        addReservedSdpRecords(uuids);

        // Enable profiles maintained by Bluez userspace.
        setBluetoothTetheringNative(true, BluetoothPanProfileHandler.NAP_ROLE,
                BluetoothPanProfileHandler.NAP_BRIDGE);

        // Add SDP records for profiles maintained by Bluez userspace
        uuids.add(BluetoothUuid.AudioSource);
        uuids.add(BluetoothUuid.AvrcpTarget);
        uuids.add(BluetoothUuid.NAP);

        // Cannot cast uuids.toArray directly since ParcelUuid is parcelable
        mAdapterUuids = new ParcelUuid[uuids.size()];
        for (int i = 0; i < uuids.size(); i++) {
            mAdapterUuids[i] = uuids.get(i);
        }
    }

    /**
     * This function is called from Bluetooth Event Loop when onPropertyChanged
     * for adapter comes in with UUID property.
     * @param uuidsThe uuids of adapter as reported by Bluez.
     */
    /*package*/ synchronized void updateBluetoothState(String uuids) {
        ParcelUuid[] adapterUuids = convertStringToParcelUuid(uuids);

        if (mAdapterUuids != null &&
            BluetoothUuid.containsAllUuids(adapterUuids, mAdapterUuids)) {
            mBluetoothState.sendMessage(BluetoothAdapterStateMachine.SERVICE_RECORD_LOADED);
        }
    }

    /**
     * This method is called immediately before Bluetooth module is turned on after
     * the adapter became pariable.
     * It inits bond state and profile state before STATE_ON intent is broadcasted.
     */
    /*package*/ void initBluetoothAfterTurningOn() {
        String discoverable = getProperty("Discoverable", false);
        String timeout = getProperty("DiscoverableTimeout", false);
        if (discoverable.equals("true") && Integer.valueOf(timeout) != 0) {
            setAdapterPropertyBooleanNative("Discoverable", 0);
        }
        mBondState.initBondState();
        initProfileState();
    }

    /**
     * This method is called immediately after Bluetooth module is turned on.
     * It starts auto-connection and places bluetooth on sign onto the battery
     * stats
     */
    /*package*/ void runBluetooth() {
        autoConnect();

        // Log bluetooth on to battery stats.
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteBluetoothOn();
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /*package*/ synchronized boolean attemptAutoPair(String address) {
        if (!mBondState.hasAutoPairingFailed(address) &&
                !mBondState.isAutoPairingBlacklisted(address)) {
            mBondState.attempt(address);
            setPin(address, BluetoothDevice.convertPinToBytes("0000"));
            return true;
        }
        return false;
    }

    /*package*/ synchronized boolean isFixedPinZerosAutoPairKeyboard(String address) {
        // Check for keyboards which have fixed PIN 0000 as the pairing pin
        return mBondState.isFixedPinZerosAutoPairKeyboard(address);
    }

    /*package*/ synchronized void onCreatePairedDeviceResult(String address, int result) {
        if (result == BluetoothDevice.BOND_SUCCESS) {
            setBondState(address, BluetoothDevice.BOND_BONDED);
            if (mBondState.isAutoPairingAttemptsInProgress(address)) {
                mBondState.clearPinAttempts(address);
            }
        } else if (result == BluetoothDevice.UNBOND_REASON_AUTH_FAILED &&
                mBondState.getAttempt(address) == 1) {
            mBondState.addAutoPairingFailure(address);
            pairingAttempt(address, result);
        } else if (result == BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN &&
              mBondState.isAutoPairingAttemptsInProgress(address)) {
            pairingAttempt(address, result);
        } else {
            setBondState(address, BluetoothDevice.BOND_NONE, result);
            if (mBondState.isAutoPairingAttemptsInProgress(address)) {
                mBondState.clearPinAttempts(address);
            }
        }
    }

    /*package*/ synchronized String getPendingOutgoingBonding() {
        return mBondState.getPendingOutgoingBonding();
    }

    private void pairingAttempt(String address, int result) {
        // This happens when our initial guess of "0000" as the pass key
        // fails. Try to create the bond again and display the pin dialog
        // to the user. Use back-off while posting the delayed
        // message. The initial value is
        // INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY and the max value is
        // MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY. If the max value is
        // reached, display an error to the user.
        int attempt = mBondState.getAttempt(address);
        if (attempt * INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY >
                    MAX_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY) {
            mBondState.clearPinAttempts(address);
            setBondState(address, BluetoothDevice.BOND_NONE, result);
            return;
        }

        Message message = mHandler.obtainMessage(MESSAGE_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY);
        message.obj = address;
        boolean postResult =  mHandler.sendMessageDelayed(message,
                                        attempt * INIT_AUTO_PAIRING_FAILURE_ATTEMPT_DELAY);
        if (!postResult) {
            mBondState.clearPinAttempts(address);
            setBondState(address,
                    BluetoothDevice.BOND_NONE, result);
            return;
        }
    }

    /*package*/ BluetoothDevice getRemoteDevice(String address) {
        return mAdapter.getRemoteDevice(address);
    }

    private static String toBondStateString(int bondState) {
        switch (bondState) {
        case BluetoothDevice.BOND_NONE:
            return "not bonded";
        case BluetoothDevice.BOND_BONDING:
            return "bonding";
        case BluetoothDevice.BOND_BONDED:
            return "bonded";
        default:
            return "??????";
        }
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
        if (!isEnabledInternal()) return false;
        return setAdapterPropertyStringNative(key, value);
    }

    private boolean setPropertyInteger(String key, int value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;
        return setAdapterPropertyIntegerNative(key, value);
    }

    private boolean setPropertyBoolean(String key, boolean value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;
        return setAdapterPropertyBooleanNative(key, value ? 1 : 0);
    }

    /**
     * Set the discoverability window for the device.  A timeout of zero
     * makes the device permanently discoverable (if the device is
     * discoverable).  Setting the timeout to a nonzero value does not make
     * a device discoverable; you need to call setMode() to make the device
     * explicitly discoverable.
     *
     * @param timeout The discoverable timeout in seconds.
     */
    public synchronized boolean setDiscoverableTimeout(int timeout) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        return setPropertyInteger("DiscoverableTimeout", timeout);
    }

    public synchronized boolean setScanMode(int mode, int duration) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                                                "Need WRITE_SECURE_SETTINGS permission");
        boolean pairable;
        boolean discoverable;

        switch (mode) {
        case BluetoothAdapter.SCAN_MODE_NONE:
            pairable = false;
            discoverable = false;
            break;
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
            pairable = true;
            discoverable = false;
            break;
        case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
            pairable = true;
            discoverable = true;
            if (DBG) Log.d(TAG, "BT Discoverable for " + duration + " seconds");
            break;
        default:
            Log.w(TAG, "Requested invalid scan mode " + mode);
            return false;
        }

        setPropertyBoolean("Discoverable", discoverable);
        setPropertyBoolean("Pairable", pairable);
        return true;
    }

    /**
     * @param on true set the local Bluetooth module to be connectable
     *                The dicoverability is recovered to what it was before
     *                switchConnectable(false) call
     *           false set the local Bluetooth module to be not connectable
     *                 and not dicoverable
     */
    /*package*/ synchronized void switchConnectable(boolean on) {
        setAdapterPropertyBooleanNative("Powered", on ? 1 : 0);
    }

    /*package*/ synchronized void setPairable() {
        String pairableString = getProperty("Pairable", false);
        if (pairableString == null) {
            Log.e(TAG, "null pairableString");
            return;
        }
        if (pairableString.equals("false")) {
            setAdapterPropertyBooleanNative("Pairable", 1);
        }
    }

    /*package*/ synchronized String getProperty(String name, boolean checkState) {
        // If checkState is false, check if the event loop is running.
        // before making the call to Bluez
        if (checkState) {
            if (!isEnabledInternal()) return null;
        } else if (!mEventLoop.isEventLoopRunning()) {
            return null;
        }

        return mAdapterProperties.getProperty(name);
    }

    BluetoothAdapterProperties getAdapterProperties() {
        return mAdapterProperties;
    }

    BluetoothDeviceProperties getDeviceProperties() {
        return mDeviceProperties;
    }

    boolean isRemoteDeviceInCache(String address) {
        return mDeviceProperties.isInCache(address);
    }

    void setRemoteDeviceProperty(String address, String name, String value) {
        mDeviceProperties.setProperty(address, name, value);
    }

    void updateRemoteDevicePropertiesCache(String address) {
        mDeviceProperties.updateCache(address);
    }

    public synchronized String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        // Don't check state since we want to provide address, even if BT is off
        return getProperty("Address", false);
    }

    public synchronized String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        // Don't check state since we want to provide name, even if BT is off
        return getProperty("Name", false);
    }

    public synchronized ParcelUuid[] getUuids() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        String value =  getProperty("UUIDs", true);
        if (value == null) return null;
        return convertStringToParcelUuid(value);
    }

    private synchronized ParcelUuid[] convertStringToParcelUuid(String value) {
        String[] uuidStrings = null;
        // The UUIDs are stored as a "," separated string.
        uuidStrings = value.split(",");
        ParcelUuid[] uuids = new ParcelUuid[uuidStrings.length];

        for (int i = 0; i < uuidStrings.length; i++) {
            uuids[i] = ParcelUuid.fromString(uuidStrings[i]);
        }
        return uuids;
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
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }
        return mDeviceProperties.getProperty(address, "Name");
    }

    /**
     * Returns alias of a remote device.  This value is returned from our
     * local cache, which is updated when onPropertyChange event is received.
     *
     * @param address Bluetooth address of remote device.
     *
     * @return The alias of the specified remote device.
     */
    public synchronized String getRemoteAlias(String address) {

        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }
        return mDeviceProperties.getProperty(address, "Alias");
    }

    /**
     * Set the alias of a remote device.
     *
     * @param address Bluetooth address of remote device.
     * @param alias new alias to change to
     * @return true on success, false on error
     */
    public synchronized boolean setRemoteAlias(String address, String alias) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }

        return setDevicePropertyStringNative(getObjectPathFromAddress(address),
                                             "Alias", alias);
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
        String timeout = getProperty("DiscoverableTimeout", true);
        if (timeout != null)
           return Integer.valueOf(timeout);
        else
            return -1;
    }

    public synchronized int getScanMode() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal())
            return BluetoothAdapter.SCAN_MODE_NONE;

        boolean pairable = getProperty("Pairable", true).equals("true");
        boolean discoverable = getProperty("Discoverable", true).equals("true");
        return bluezStringToScanMode (pairable, discoverable);
    }

    public synchronized boolean startDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        return startDiscoveryNative();
    }

    public synchronized boolean cancelDiscovery() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        return stopDiscoveryNative();
    }

    public synchronized boolean isDiscovering() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        String discoveringProperty = getProperty("Discovering", false);
        if (discoveringProperty == null) {
            return false;
        }

        return discoveringProperty.equals("true");
    }

    private boolean isBondingFeasible(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();

        if (mBondState.getPendingOutgoingBonding() != null) {
            Log.d(TAG, "Ignoring createBond(): another device is bonding");
            // a different device is currently bonding, fail
            return false;
        }

        // Check for bond state only if we are not performing auto
        // pairing exponential back-off attempts.
        if (!mBondState.isAutoPairingAttemptsInProgress(address) &&
                mBondState.getBondState(address) != BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Ignoring createBond(): this device is already bonding or bonded");
            return false;
        }

        if (address.equals(mDockAddress)) {
            if (!writeDockPin()) {
                Log.e(TAG, "Error while writing Pin for the dock");
                return false;
            }
        }
        return true;
    }

    public synchronized boolean createBond(String address) {
        if (!isBondingFeasible(address)) return false;

        if (!createPairedDeviceNative(address, 60000  /*1 minute*/ )) {
            return false;
        }

        mBondState.setPendingOutgoingBonding(address);
        mBondState.setBondState(address, BluetoothDevice.BOND_BONDING);

        return true;
    }

    public synchronized boolean createBondOutOfBand(String address, byte[] hash,
                                                    byte[] randomizer) {
        if (!isBondingFeasible(address)) return false;

        if (!createPairedDeviceOutOfBandNative(address, 60000 /* 1 minute */)) {
            return false;
        }

        setDeviceOutOfBandData(address, hash, randomizer);
        mBondState.setPendingOutgoingBonding(address);
        mBondState.setBondState(address, BluetoothDevice.BOND_BONDING);

        return true;
    }

    public synchronized boolean setDeviceOutOfBandData(String address, byte[] hash,
            byte[] randomizer) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        Pair <byte[], byte[]> value = new Pair<byte[], byte[]>(hash, randomizer);

        if (DBG) {
            Log.d(TAG, "Setting out of band data for: " + address + ":" +
                  Arrays.toString(hash) + ":" + Arrays.toString(randomizer));
        }

        mDeviceOobData.put(address, value);
        return true;
    }

    Pair<byte[], byte[]> getDeviceOutOfBandData(BluetoothDevice device) {
        return mDeviceOobData.get(device.getAddress());
    }


    public synchronized byte[] readOutOfBandData() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return null;

        return readAdapterOutOfBandDataNative();
    }

    public synchronized boolean cancelBondProcess(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        address = address.toUpperCase();
        if (mBondState.getBondState(address) != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        mBondState.setBondState(address, BluetoothDevice.BOND_NONE,
                                BluetoothDevice.UNBOND_REASON_AUTH_CANCELED);
        cancelDeviceCreationNative(address);
        return true;
    }

    public synchronized boolean removeBond(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            state.sendMessage(BluetoothDeviceProfileState.UNPAIR);
            return true;
        } else {
            return false;
        }
    }

    public synchronized boolean removeBondInternal(String address) {
        // Unset the trusted device state and then unpair
        setTrust(address, false);
        return removeDeviceNative(getObjectPathFromAddress(address));
    }

    public synchronized String[] listBonds() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mBondState.listInState(BluetoothDevice.BOND_BONDED);
    }

    /*package*/ synchronized String[] listInState(int state) {
      return mBondState.listInState(state);
    }

    public synchronized int getBondState(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return BluetoothDevice.ERROR;
        }
        return mBondState.getBondState(address.toUpperCase());
    }

    /*package*/ synchronized boolean setBondState(String address, int state) {
        return setBondState(address, state, 0);
    }

    /*package*/ synchronized boolean setBondState(String address, int state, int reason) {
        mBondState.setBondState(address.toUpperCase(), state, reason);
        return true;
    }

    public synchronized boolean isBluetoothDock(String address) {
        SharedPreferences sp = mContext.getSharedPreferences(SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);

        return sp.contains(SHARED_PREFERENCE_DOCK_ADDRESS + address);
    }

    /*package*/ String[] getRemoteDeviceProperties(String address) {
        if (!isEnabledInternal()) return null;

        String objectPath = getObjectPathFromAddress(address);
        return (String [])getDevicePropertiesNative(objectPath);
    }

    /**
     * Sets the remote device trust state.
     *
     * @return boolean to indicate operation success or fail
     */
    public synchronized boolean setTrust(String address, boolean value) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH_ADMIN permission");
            return false;
        }

        if (!isEnabledInternal()) return false;

        return setDevicePropertyBooleanNative(
                getObjectPathFromAddress(address), "Trusted", value ? 1 : 0);
    }

    /**
     * Gets the remote device trust state as boolean.
     * Note: this value may be
     * retrieved from cache if we retrieved the data before *
     *
     * @return boolean to indicate trusted or untrusted state
     */
    public synchronized boolean getTrustState(String address) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return false;
        }

        String val = mDeviceProperties.getProperty(address, "Trusted");
        if (val == null) {
            return false;
        } else {
            return val.equals("true");
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
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return BluetoothClass.ERROR;
        }
        String val = mDeviceProperties.getProperty(address, "Class");
        if (val == null)
            return BluetoothClass.ERROR;
        else {
            return Integer.valueOf(val);
        }
    }


    /**
     * Gets the UUIDs supported by the remote device
     *
     * @return array of 128bit ParcelUuids
     */
    public synchronized ParcelUuid[] getRemoteUuids(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }
        return getUuidFromCache(address);
    }

    ParcelUuid[] getUuidFromCache(String address) {
        String value = mDeviceProperties.getProperty(address, "UUIDs");
        if (value == null) return null;

        String[] uuidStrings = null;
        // The UUIDs are stored as a "," separated string.
        uuidStrings = value.split(",");
        ParcelUuid[] uuids = new ParcelUuid[uuidStrings.length];

        for (int i = 0; i < uuidStrings.length; i++) {
            uuids[i] = ParcelUuid.fromString(uuidStrings[i]);
        }
        return uuids;
    }

    /**
     * Connect and fetch new UUID's using SDP.
     * The UUID's found are broadcast as intents.
     * Optionally takes a uuid and callback to fetch the RFCOMM channel for the
     * a given uuid.
     * TODO: Don't wait UUID_INTENT_DELAY to broadcast UUID intents on success
     * TODO: Don't wait UUID_INTENT_DELAY to handle the failure case for
     * callback and broadcast intents.
     */
    public synchronized boolean fetchRemoteUuids(String address, ParcelUuid uuid,
            IBluetoothCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }

        RemoteService service = new RemoteService(address, uuid);
        if (uuid != null && mUuidCallbackTracker.get(service) != null) {
            // An SDP query for this address & uuid is already in progress
            // Do not add this callback for the uuid
            return false;
        }

        if (mUuidIntentTracker.contains(address)) {
            // An SDP query for this address is already in progress
            // Add this uuid onto the in-progress SDP query
            if (uuid != null) {
                mUuidCallbackTracker.put(new RemoteService(address, uuid), callback);
            }
            return true;
        }

        // If the device is already created, we will
        // do the SDP on the callback of createDeviceNative.
        boolean ret= createDeviceNative(address);

        mUuidIntentTracker.add(address);
        if (uuid != null) {
            mUuidCallbackTracker.put(new RemoteService(address, uuid), callback);
        }

        Message message = mHandler.obtainMessage(MESSAGE_UUID_INTENT);
        message.obj = address;
        mHandler.sendMessageDelayed(message, UUID_INTENT_DELAY);
        return ret;
    }

    /**
     * Gets the rfcomm channel associated with the UUID.
     * Pulls records from the cache only.
     *
     * @param address Address of the remote device
     * @param uuid ParcelUuid of the service attribute
     *
     * @return rfcomm channel associated with the service attribute
     *         -1 on error
     */
    public int getRemoteServiceChannel(String address, ParcelUuid uuid) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return -1;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return BluetoothDevice.ERROR;
        }
        // Check if we are recovering from a crash.
        if (mDeviceProperties.isEmpty()) {
            if (mDeviceProperties.updateCache(address) == null)
                return -1;
        }

        Map<ParcelUuid, Integer> value = mDeviceServiceChannelCache.get(address);
        if (value != null && value.containsKey(uuid))
            return value.get(uuid);
        return -1;
    }

    public synchronized boolean setPin(String address, byte[] pin) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (pin == null || pin.length <= 0 || pin.length > 16 ||
            !BluetoothAdapter.checkBluetoothAddress(address)) {
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
        if (!isEnabledInternal()) return false;

        if (passkey < 0 || passkey > 999999 || !BluetoothAdapter.checkBluetoothAddress(address)) {
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
        if (!isEnabledInternal()) return false;

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

    public synchronized boolean setRemoteOutOfBandData(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;
        address = address.toUpperCase();
        Integer data = mEventLoop.getPasskeyAgentRequestData().remove(address);
        if (data == null) {
            Log.w(TAG, "setRemoteOobData(" + address + ") called but no native data available, " +
                  "ignoring. Maybe the PasskeyAgent Request was cancelled by the remote device" +
                  " or by bluez.\n");
            return false;
        }

        Pair<byte[], byte[]> val = mDeviceOobData.get(address);
        byte[] hash, randomizer;
        if (val == null) {
            // TODO: check what should be passed in this case.
            hash = new byte[16];
            randomizer = new byte[16];
        } else {
            hash = val.first;
            randomizer = val.second;
        }
        return setRemoteOutOfBandDataNative(address, hash, randomizer, data.intValue());
    }

    public synchronized boolean cancelPairingUserInput(String address) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        if (!isEnabledInternal()) return false;

        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        mBondState.setBondState(address, BluetoothDevice.BOND_NONE,
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

    /*package*/ void updateDeviceServiceChannelCache(String address) {
        if (DBG) Log.d(TAG, "updateDeviceServiceChannelCache(" + address + ")");

        // We are storing the rfcomm channel numbers only for the uuids
        // we are interested in.
        ParcelUuid[] deviceUuids = getRemoteUuids(address);

        ArrayList<ParcelUuid> applicationUuids = new ArrayList<ParcelUuid>();

        synchronized (this) {
            for (RemoteService service : mUuidCallbackTracker.keySet()) {
                if (service.address.equals(address)) {
                    applicationUuids.add(service.uuid);
                }
            }
        }

        Map <ParcelUuid, Integer> uuidToChannelMap = new HashMap<ParcelUuid, Integer>();

        // Retrieve RFCOMM channel for default uuids
        for (ParcelUuid uuid : RFCOMM_UUIDS) {
            if (BluetoothUuid.isUuidPresent(deviceUuids, uuid)) {
                int channel = getDeviceServiceChannelForUuid(address, uuid);
                uuidToChannelMap.put(uuid, channel);
                if (DBG) Log.d(TAG, "\tuuid(system): " + uuid + " " + channel);
            }
        }
        // Retrieve RFCOMM channel for application requested uuids
        for (ParcelUuid uuid : applicationUuids) {
            if (BluetoothUuid.isUuidPresent(deviceUuids, uuid)) {
                int channel = getDeviceServiceChannelForUuid(address, uuid);
                uuidToChannelMap.put(uuid, channel);
                if (DBG) Log.d(TAG, "\tuuid(application): " + uuid + " " + channel);
            }
        }

        synchronized (this) {
            // Make application callbacks
            for (Iterator<RemoteService> iter = mUuidCallbackTracker.keySet().iterator();
                    iter.hasNext();) {
                RemoteService service = iter.next();
                if (service.address.equals(address)) {
                    if (uuidToChannelMap.containsKey(service.uuid)) {
                        int channel = uuidToChannelMap.get(service.uuid);

                        if (DBG) Log.d(TAG, "Making callback for " + service.uuid +
                                    " with result " + channel);
                        IBluetoothCallback callback = mUuidCallbackTracker.get(service);
                        if (callback != null) {
                            try {
                                callback.onRfcommChannelFound(channel);
                            } catch (RemoteException e) {Log.e(TAG, "", e);}
                        }

                        iter.remove();
                    }
                }
            }

            // Update cache
            mDeviceServiceChannelCache.put(address, uuidToChannelMap);
        }
    }

    private int getDeviceServiceChannelForUuid(String address,
            ParcelUuid uuid) {
        return getDeviceServiceChannelNative(getObjectPathFromAddress(address),
                uuid.toString(), 0x0004);
    }

    /**
     * b is a handle to a Binder instance, so that this service can be notified
     * for Applications that terminate unexpectedly, to clean there service
     * records
     */
    public synchronized int addRfcommServiceRecord(String serviceName, ParcelUuid uuid,
            int channel, IBinder b) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (!isEnabledInternal()) return -1;

        if (serviceName == null || uuid == null || channel < 1 ||
                channel > BluetoothSocket.MAX_RFCOMM_CHANNEL) {
            return -1;
        }
        if (BluetoothUuid.isUuidPresent(BluetoothUuid.RESERVED_UUIDS, uuid)) {
            Log.w(TAG, "Attempted to register a reserved UUID: " + uuid);
            return -1;
        }
        int handle = addRfcommServiceRecordNative(serviceName,
                uuid.getUuid().getMostSignificantBits(), uuid.getUuid().getLeastSignificantBits(),
                (short)channel);
        if (DBG) Log.d(TAG, "new handle " + Integer.toHexString(handle));
        if (handle == -1) {
            return -1;
        }

        int pid = Binder.getCallingPid();
        mServiceRecordToPid.put(new Integer(handle), new Integer(pid));
        try {
            b.linkToDeath(new Reaper(handle, pid, RFCOMM_RECORD_REAPER), 0);
        } catch (RemoteException e) {}
        return handle;
    }

    public void removeServiceRecord(int handle) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message message = mHandler.obtainMessage(MESSAGE_REMOVE_SERVICE_RECORD);
        message.obj = new Pair<Integer, Integer>(handle, Binder.getCallingPid());
        mHandler.sendMessage(message);
    }

    private synchronized void checkAndRemoveRecord(int handle, int pid) {
        Integer handleInt = new Integer(handle);
        Integer owner = mServiceRecordToPid.get(handleInt);
        if (owner != null && pid == owner.intValue()) {
            if (DBG) Log.d(TAG, "Removing service record " +
                Integer.toHexString(handle) + " for pid " + pid);
            mServiceRecordToPid.remove(handleInt);
            removeServiceRecordNative(handle);
        }
    }

    private class Reaper implements IBinder.DeathRecipient {
        int mPid;
        int mHandle;
        int mType;

        Reaper(int handle, int pid, int type) {
            mPid = pid;
            mHandle = handle;
            mType = type;
        }

        Reaper(int pid, int type) {
            mPid = pid;
            mType = type;
        }

        @Override
        public void binderDied() {
            synchronized (BluetoothService.this) {
                if (DBG) Log.d(TAG, "Tracked app " + mPid + " died" + "Type:" + mType);
                if (mType == RFCOMM_RECORD_REAPER) {
                    checkAndRemoveRecord(mHandle, mPid);
                } else if (mType == STATE_CHANGE_REAPER) {
                    mStateChangeTracker.remove(mPid);
                }
            }
        }
    }


    @Override
    public boolean changeApplicationBluetoothState(boolean on,
            IBluetoothStateChangeCallback callback, IBinder binder) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        int pid = Binder.getCallingPid();
        //mStateChangeTracker is a synchronized map
        if (!mStateChangeTracker.containsKey(pid)) {
            if (on) {
                mStateChangeTracker.put(pid, callback);
            } else {
                return false;
            }
        } else if (!on) {
            mStateChangeTracker.remove(pid);
        }

        if (binder != null) {
            try {
                binder.linkToDeath(new Reaper(pid, STATE_CHANGE_REAPER), 0);
            } catch (RemoteException e) {
                return false;
            }
        }

        int type;
        if (on) {
            type = BluetoothAdapterStateMachine.PER_PROCESS_TURN_ON;
        } else {
            type = BluetoothAdapterStateMachine.PER_PROCESS_TURN_OFF;
        }

        mBluetoothState.sendMessage(type, callback);
        return true;
    }

    boolean isApplicationStateChangeTrackerEmpty() {
        return mStateChangeTracker.isEmpty();
    }

    void clearApplicationStateChangeTracker() {
        mStateChangeTracker.clear();
    }

    Collection<IBluetoothStateChangeCallback> getApplicationStateChangeCallbacks() {
        return mStateChangeTracker.values();
    }

    int getNumberOfApplicationStateChangeTrackers() {
        return mStateChangeTracker.size();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                ContentResolver resolver = context.getContentResolver();
                // Query the airplane mode from Settings.System just to make sure that
                // some random app is not sending this intent and disabling bluetooth
                if (isAirplaneModeOn()) {
                    mBluetoothState.sendMessage(BluetoothAdapterStateMachine.AIRPLANE_MODE_ON);
                } else {
                    mBluetoothState.sendMessage(BluetoothAdapterStateMachine.AIRPLANE_MODE_OFF);
                }
            } else if (Intent.ACTION_DOCK_EVENT.equals(action)) {
                int state = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (DBG) Log.v(TAG, "Received ACTION_DOCK_EVENT with State:" + state);
                if (state == Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                    mDockAddress = null;
                    mDockPin = null;
                } else {
                    SharedPreferences.Editor editor =
                        mContext.getSharedPreferences(SHARED_PREFERENCES_NAME,
                                mContext.MODE_PRIVATE).edit();
                    editor.putBoolean(SHARED_PREFERENCE_DOCK_ADDRESS + mDockAddress, true);
                    editor.apply();
                }
            }
        }
    };

    private void registerForAirplaneMode(IntentFilter filter) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String airplaneModeRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_RADIOS);
        final String toggleableRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);

        mIsAirplaneSensitive = airplaneModeRadios == null ? true :
                airplaneModeRadios.contains(Settings.System.RADIO_BLUETOOTH);
        mIsAirplaneToggleable = toggleableRadios == null ? false :
                toggleableRadios.contains(Settings.System.RADIO_BLUETOOTH);

        if (mIsAirplaneSensitive) {
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    /* Returns true if airplane mode is currently on */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    /* Broadcast the Uuid intent */
    /*package*/ synchronized void sendUuidIntent(String address) {
        ParcelUuid[] uuid = getUuidFromCache(address);
        Intent intent = new Intent(BluetoothDevice.ACTION_UUID);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mAdapter.getRemoteDevice(address));
        intent.putExtra(BluetoothDevice.EXTRA_UUID, uuid);
        mContext.sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);
        mUuidIntentTracker.remove(address);
    }

    /*package*/ synchronized void makeServiceChannelCallbacks(String address) {
        for (Iterator<RemoteService> iter = mUuidCallbackTracker.keySet().iterator();
                iter.hasNext();) {
            RemoteService service = iter.next();
            if (service.address.equals(address)) {
                if (DBG) Log.d(TAG, "Cleaning up failed UUID channel lookup: "
                    + service.address + " " + service.uuid);
                IBluetoothCallback callback = mUuidCallbackTracker.get(service);
                if (callback != null) {
                    try {
                        callback.onRfcommChannelFound(-1);
                    } catch (RemoteException e) {Log.e(TAG, "", e);}
                }

                iter.remove();
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (getBluetoothStateInternal() != BluetoothAdapter.STATE_ON) {
            return;
        }

        pw.println("mIsAirplaneSensitive = " + mIsAirplaneSensitive);
        pw.println("mIsAirplaneToggleable = " + mIsAirplaneToggleable);

        pw.println("Local address = " + getAddress());
        pw.println("Local name = " + getName());
        pw.println("isDiscovering() = " + isDiscovering());

        mAdapter.getProfileProxy(mContext,
                                 mBluetoothProfileServiceListener, BluetoothProfile.HEADSET);
        mAdapter.getProfileProxy(mContext,
                mBluetoothProfileServiceListener, BluetoothProfile.INPUT_DEVICE);
        mAdapter.getProfileProxy(mContext,
                mBluetoothProfileServiceListener, BluetoothProfile.PAN);

        dumpKnownDevices(pw);
        dumpAclConnectedDevices(pw);
        dumpHeadsetService(pw);
        dumpInputDeviceProfile(pw);
        dumpPanProfile(pw);
        dumpApplicationServiceRecords(pw);
        dumpProfileState(pw);
    }

    private void dumpProfileState(PrintWriter pw) {
        pw.println("\n--Profile State dump--");
        pw.println("\n Headset profile state:" +
                mAdapter.getProfileConnectionState(BluetoothProfile.HEADSET));
        pw.println("\n A2dp profile state:" +
                mAdapter.getProfileConnectionState(BluetoothProfile.A2DP));
        pw.println("\n HID profile state:" +
                mAdapter.getProfileConnectionState(BluetoothProfile.INPUT_DEVICE));
        pw.println("\n PAN profile state:" +
                mAdapter.getProfileConnectionState(BluetoothProfile.PAN));
    }

    private void dumpHeadsetService(PrintWriter pw) {
        pw.println("\n--Headset Service--");
        if (mBluetoothHeadset != null) {
            List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();
            if (deviceList.size() == 0) {
                pw.println("No headsets connected");
            } else {
                BluetoothDevice device = deviceList.get(0);
                pw.println("\ngetConnectedDevices[0] = " + device);
                dumpHeadsetConnectionState(pw, device);
                pw.println("getBatteryUsageHint() = " +
                             mBluetoothHeadset.getBatteryUsageHint(device));
            }

            deviceList.clear();
            deviceList = mBluetoothHeadset.getDevicesMatchingConnectionStates(new int[] {
                     BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED});
            pw.println("--Connected and Disconnected Headsets");
            for (BluetoothDevice device: deviceList) {
                pw.println(device);
                if (mBluetoothHeadset.isAudioConnected(device)) {
                    pw.println("SCO audio connected to device:" + device);
                }
            }
        }
        mAdapter.closeProfileProxy(BluetoothProfile.HEADSET, mBluetoothHeadset);
    }

    private void dumpInputDeviceProfile(PrintWriter pw) {
        pw.println("\n--Bluetooth Service- Input Device Profile");
        if (mInputDevice != null) {
            List<BluetoothDevice> deviceList = mInputDevice.getConnectedDevices();
            if (deviceList.size() == 0) {
                pw.println("No input devices connected");
            } else {
                pw.println("Number of connected devices:" + deviceList.size());
                BluetoothDevice device = deviceList.get(0);
                pw.println("getConnectedDevices[0] = " + device);
                pw.println("Priority of Connected device = " + mInputDevice.getPriority(device));

                switch (mInputDevice.getConnectionState(device)) {
                    case BluetoothInputDevice.STATE_CONNECTING:
                        pw.println("getConnectionState() = STATE_CONNECTING");
                        break;
                    case BluetoothInputDevice.STATE_CONNECTED:
                        pw.println("getConnectionState() = STATE_CONNECTED");
                        break;
                    case BluetoothInputDevice.STATE_DISCONNECTING:
                        pw.println("getConnectionState() = STATE_DISCONNECTING");
                        break;
                }
            }
            deviceList.clear();
            deviceList = mInputDevice.getDevicesMatchingConnectionStates(new int[] {
                     BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED});
            pw.println("--Connected and Disconnected input devices");
            for (BluetoothDevice device: deviceList) {
                pw.println(device);
            }
        }
        mAdapter.closeProfileProxy(BluetoothProfile.INPUT_DEVICE, mBluetoothHeadset);
    }

    private void dumpPanProfile(PrintWriter pw) {
        pw.println("\n--Bluetooth Service- Pan Profile");
        if (mPan != null) {
            List<BluetoothDevice> deviceList = mPan.getConnectedDevices();
            if (deviceList.size() == 0) {
                pw.println("No Pan devices connected");
            } else {
                pw.println("Number of connected devices:" + deviceList.size());
                BluetoothDevice device = deviceList.get(0);
                pw.println("getConnectedDevices[0] = " + device);

                switch (mPan.getConnectionState(device)) {
                    case BluetoothInputDevice.STATE_CONNECTING:
                        pw.println("getConnectionState() = STATE_CONNECTING");
                        break;
                    case BluetoothInputDevice.STATE_CONNECTED:
                        pw.println("getConnectionState() = STATE_CONNECTED");
                        break;
                    case BluetoothInputDevice.STATE_DISCONNECTING:
                        pw.println("getConnectionState() = STATE_DISCONNECTING");
                        break;
                }
            }
            deviceList.clear();
            deviceList = mPan.getDevicesMatchingConnectionStates(new int[] {
                     BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED});
            pw.println("--Connected and Disconnected Pan devices");
            for (BluetoothDevice device: deviceList) {
                pw.println(device);
            }
        }
    }

    private void dumpHeadsetConnectionState(PrintWriter pw,
            BluetoothDevice device) {
        switch (mBluetoothHeadset.getConnectionState(device)) {
            case BluetoothHeadset.STATE_CONNECTING:
                pw.println("getConnectionState() = STATE_CONNECTING");
                break;
            case BluetoothHeadset.STATE_CONNECTED:
                pw.println("getConnectionState() = STATE_CONNECTED");
                break;
            case BluetoothHeadset.STATE_DISCONNECTING:
                pw.println("getConnectionState() = STATE_DISCONNECTING");
                break;
            case BluetoothHeadset.STATE_AUDIO_CONNECTED:
                pw.println("getConnectionState() = STATE_AUDIO_CONNECTED");
                break;
        }
    }

    private void dumpApplicationServiceRecords(PrintWriter pw) {
        pw.println("\n--Application Service Records--");
        for (Integer handle : mServiceRecordToPid.keySet()) {
            Integer pid = mServiceRecordToPid.get(handle);
            pw.println("\tpid " + pid + " handle " + Integer.toHexString(handle));
        }
        mAdapter.closeProfileProxy(BluetoothProfile.PAN, mBluetoothHeadset);
    }

    private void dumpAclConnectedDevices(PrintWriter pw) {
        String[] devicesObjectPath = getKnownDevices();
        pw.println("\n--ACL connected devices--");
        if (devicesObjectPath != null) {
            for (String device : devicesObjectPath) {
                pw.println(getAddressFromObjectPath(device));
            }
        }
    }

    private void dumpKnownDevices(PrintWriter pw) {
        pw.println("\n--Known devices--");
        for (String address : mDeviceProperties.keySet()) {
            int bondState = mBondState.getBondState(address);
            pw.printf("%s %10s (%d) %s\n", address,
                       toBondStateString(bondState),
                       mBondState.getAttempt(address),
                       getRemoteName(address));

            Map<ParcelUuid, Integer> uuidChannels = mDeviceServiceChannelCache.get(address);
            if (uuidChannels == null) {
                pw.println("\tuuids = null");
            } else {
                for (ParcelUuid uuid : uuidChannels.keySet()) {
                    Integer channel = uuidChannels.get(uuid);
                    if (channel == null) {
                        pw.println("\t" + uuid);
                    } else {
                        pw.println("\t" + uuid + " RFCOMM channel = " + channel);
                    }
                }
            }
            for (RemoteService service : mUuidCallbackTracker.keySet()) {
                if (service.address.equals(address)) {
                    pw.println("\tPENDING CALLBACK: " + service.uuid);
                }
            }
        }
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                mBluetoothHeadset = (BluetoothHeadset) proxy;
            } else if (profile == BluetoothProfile.INPUT_DEVICE) {
                mInputDevice = (BluetoothInputDevice) proxy;
            } else if (profile == BluetoothProfile.PAN) {
                mPan = (BluetoothPan) proxy;
            }
        }
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) {
                mBluetoothHeadset = null;
            } else if (profile == BluetoothProfile.INPUT_DEVICE) {
                mInputDevice = null;
            } else if (profile == BluetoothProfile.PAN) {
                mPan = null;
            }
        }
    };

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
        String adapterObjectPath = mAdapterProperties.getObjectPath();
        if (adapterObjectPath == null || objectPath == null) {
            Log.e(TAG, "getAddressFromObjectPath: AdapterObjectPath:" + adapterObjectPath +
                    "  or deviceObjectPath:" + objectPath + " is null");
            return null;
        }
        if (!objectPath.startsWith(adapterObjectPath)) {
            Log.e(TAG, "getAddressFromObjectPath: AdapterObjectPath:" + adapterObjectPath +
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
        String path = mAdapterProperties.getObjectPath();
        if (path == null) {
            Log.e(TAG, "Error: Object Path is null");
            return null;
        }
        path = path + address.replace(":", "_");
        return path;
    }

    /*package */ void setLinkTimeout(String address, int num_slots) {
        String path = getObjectPathFromAddress(address);
        boolean result = setLinkTimeoutNative(path, num_slots);

        if (!result) Log.d(TAG, "Set Link Timeout to " + num_slots + " slots failed");
    }

    /**** Handlers for PAN  Profile ****/
    // TODO: This needs to be converted to a state machine.

    public boolean isTetheringOn() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.isTetheringOn();
        }
    }

    /*package*/boolean allowIncomingTethering() {
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.allowIncomingTethering();
        }
    }

    public void setBluetoothTethering(boolean value) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothPanProfileHandler) {
            mBluetoothPanProfileHandler.setBluetoothTethering(value);
        }
    }

    public int getPanDeviceConnectionState(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.getPanDeviceConnectionState(device);
        }
    }

    public boolean connectPanDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
            "Need BLUETOOTH_ADMIN permission");
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.connectPanDevice(device);
        }
    }

    public List<BluetoothDevice> getConnectedPanDevices() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.getConnectedPanDevices();
        }
    }

    public List<BluetoothDevice> getPanDevicesMatchingConnectionStates(
            int[] states) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.getPanDevicesMatchingConnectionStates(states);
        }
    }

    public boolean disconnectPanDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
            "Need BLUETOOTH_ADMIN permission");
        synchronized (mBluetoothPanProfileHandler) {
            return mBluetoothPanProfileHandler.disconnectPanDevice(device);
        }
    }

    /*package*/void handlePanDeviceStateChange(BluetoothDevice device,
                                                             String iface,
                                                             int state,
                                                             int role) {
        synchronized (mBluetoothPanProfileHandler) {
            mBluetoothPanProfileHandler.handlePanDeviceStateChange(device, iface, state, role);
        }
    }

    /*package*/void handlePanDeviceStateChange(BluetoothDevice device,
                                                             int state, int role) {
        synchronized (mBluetoothPanProfileHandler) {
            mBluetoothPanProfileHandler.handlePanDeviceStateChange(device, null, state, role);
        }
    }

    /**** Handlers for Input Device Profile ****/
    // This needs to be converted to state machine

    public boolean connectInputDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        BluetoothDeviceProfileState state = mDeviceProfileState.get(device.getAddress());
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.connectInputDevice(device, state);
        }
    }

    public boolean connectInputDeviceInternal(BluetoothDevice device) {
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.connectInputDeviceInternal(device);
        }
    }

    public boolean disconnectInputDevice(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        BluetoothDeviceProfileState state = mDeviceProfileState.get(device.getAddress());
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.disconnectInputDevice(device, state);
        }
    }

    public boolean disconnectInputDeviceInternal(BluetoothDevice device) {
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.disconnectInputDeviceInternal(device);
        }
    }

    public int getInputDeviceConnectionState(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.getInputDeviceConnectionState(device);
        }
    }

    public List<BluetoothDevice> getConnectedInputDevices() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.getConnectedInputDevices();
        }
    }

    public List<BluetoothDevice> getInputDevicesMatchingConnectionStates(
            int[] states) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.getInputDevicesMatchingConnectionStates(states);
        }
    }


    public int getInputDevicePriority(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.getInputDevicePriority(device);
        }
    }

    public boolean setInputDevicePriority(BluetoothDevice device, int priority) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.setInputDevicePriority(device, priority);
        }
    }

    /**
     * Handle incoming profile acceptance for profiles handled by Bluetooth Service,
     * currently PAN and HID. This also is the catch all for all rejections for profiles
     * that is not supported.
     *
     * @param device - Bluetooth Device
     * @param allow - true / false
     * @return
     */
    public boolean allowIncomingProfileConnect(BluetoothDevice device, boolean allow) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH_ADMIN permission");
        String address = device.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }

        Integer data = getAuthorizationAgentRequestData(address);
        if (data == null) {
            Log.w(TAG, "allowIncomingProfileConnect(" + device +
                  ") called but no native data available");
            return false;
        }
        if (DBG) log("allowIncomingProfileConnect: " + device + " : " + allow + " : " + data);
        return setAuthorizationNative(address, allow, data.intValue());
    }

    /*package*/List<BluetoothDevice> lookupInputDevicesMatchingStates(int[] states) {
        synchronized (mBluetoothInputProfileHandler) {
            return mBluetoothInputProfileHandler.lookupInputDevicesMatchingStates(states);
        }
    }

    /*package*/void handleInputDevicePropertyChange(String address, boolean connected) {
        synchronized (mBluetoothInputProfileHandler) {
            mBluetoothInputProfileHandler.handleInputDevicePropertyChange(address, connected);
        }
    }

    /**** Handlers for Health Device Profile ****/
    // TODO: All these need to be converted to a state machine.

    public boolean registerAppConfiguration(BluetoothHealthAppConfiguration config,
                                            IBluetoothHealthCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
                return mBluetoothHealthProfileHandler.registerAppConfiguration(config, callback);
        }
    }

    public boolean unregisterAppConfiguration(BluetoothHealthAppConfiguration config) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
                return mBluetoothHealthProfileHandler.unregisterAppConfiguration(config);
        }
    }


    public boolean connectChannelToSource(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.connectChannelToSource(device,
                    config);
        }
    }

    public boolean connectChannelToSink(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, int channelType) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.connectChannel(device, config,
                    channelType);
        }
    }

    public boolean disconnectChannel(BluetoothDevice device,
            BluetoothHealthAppConfiguration config, ParcelFileDescriptor fd) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.disconnectChannel(device, config, fd);
        }
    }

    public ParcelFileDescriptor getMainChannelFd(BluetoothDevice device,
            BluetoothHealthAppConfiguration config) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.getMainChannelFd(device, config);
        }
    }

    /*package*/ void onHealthDevicePropertyChanged(String devicePath,
            String channelPath) {
        synchronized (mBluetoothHealthProfileHandler) {
            mBluetoothHealthProfileHandler.onHealthDevicePropertyChanged(devicePath,
                    channelPath);
        }
    }

    /*package*/ void onHealthDeviceChannelChanged(String devicePath,
            String channelPath, boolean exists) {
        synchronized(mBluetoothHealthProfileHandler) {
            mBluetoothHealthProfileHandler.onHealthDeviceChannelChanged(devicePath,
                    channelPath, exists);
        }
    }

    public int getHealthDeviceConnectionState(BluetoothDevice device) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.getHealthDeviceConnectionState(device);
        }
    }

    public List<BluetoothDevice> getConnectedHealthDevices() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.getConnectedHealthDevices();
        }
    }

    public List<BluetoothDevice> getHealthDevicesMatchingConnectionStates(
            int[] states) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        synchronized (mBluetoothHealthProfileHandler) {
            return mBluetoothHealthProfileHandler.
                    getHealthDevicesMatchingConnectionStates(states);
        }
    }

    /*package*/boolean notifyIncomingHidConnection(String address) {
        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state == null) {
            return false;
        }
        Message msg = new Message();
        msg.what = BluetoothDeviceProfileState.CONNECT_HID_INCOMING;
        state.sendMessage(msg);
        return true;
    }

    public boolean connectHeadset(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.CONNECT_HFP_OUTGOING;
            msg.obj = state;
            mHfpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    public boolean disconnectHeadset(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.DISCONNECT_HFP_OUTGOING;
            msg.obj = state;
            mHfpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    public boolean connectSink(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.CONNECT_A2DP_OUTGOING;
            msg.obj = state;
            mA2dpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    public boolean disconnectSink(String address) {
        if (getBondState(address) != BluetoothDevice.BOND_BONDED) return false;

        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.arg1 = BluetoothDeviceProfileState.DISCONNECT_A2DP_OUTGOING;
            msg.obj = state;
            mA2dpProfileState.sendMessage(msg);
            return true;
        }
        return false;
    }

    BluetoothDeviceProfileState addProfileState(String address, boolean setTrust) {
        BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
        if (state != null) return state;

        state = new BluetoothDeviceProfileState(mContext, address, this, mA2dpService, setTrust);
        mDeviceProfileState.put(address, state);
        state.start();
        return state;
    }

    void removeProfileState(String address) {
        mDeviceProfileState.remove(address);
    }

    synchronized String[] getKnownDevices() {
        String[] bonds = null;
        String val = getProperty("Devices", true);
        if (val != null) {
            bonds = val.split(",");
        }
        return bonds;
    }

    private void initProfileState() {
        String[] bonds = null;
        String val = getProperty("Devices", false);
        if (val != null) {
            bonds = val.split(",");
        }
        if (bonds == null) {
            return;
        }
        for (String path : bonds) {
            String address = getAddressFromObjectPath(path);
            BluetoothDeviceProfileState state = addProfileState(address, false);
        }
    }

    private void autoConnect() {
        String[] bonds = getKnownDevices();
        if (bonds == null) {
            return;
        }
        for (String path : bonds) {
            String address = getAddressFromObjectPath(path);
            BluetoothDeviceProfileState state = mDeviceProfileState.get(address);
            if (state != null) {
                Message msg = new Message();
                msg.what = BluetoothDeviceProfileState.AUTO_CONNECT_PROFILES;
                state.sendMessage(msg);
            }
        }
    }

    public boolean notifyIncomingConnection(String address) {
        BluetoothDeviceProfileState state =
             mDeviceProfileState.get(address);
        if (state != null) {
            Message msg = new Message();
            msg.what = BluetoothDeviceProfileState.CONNECT_HFP_INCOMING;
            state.sendMessage(msg);
            return true;
        }
        return false;
    }

    /*package*/ boolean notifyIncomingA2dpConnection(String address) {
       BluetoothDeviceProfileState state =
            mDeviceProfileState.get(address);
       if (state != null) {
           Message msg = new Message();
           msg.what = BluetoothDeviceProfileState.CONNECT_A2DP_INCOMING;
           state.sendMessage(msg);
           return true;
       }
       return false;
    }

    /*package*/ void setA2dpService(BluetoothA2dpService a2dpService) {
        mA2dpService = a2dpService;
    }

    /*package*/ Integer getAuthorizationAgentRequestData(String address) {
        Integer data = mEventLoop.getAuthorizationAgentRequestData().remove(address);
        return data;
    }

    public void sendProfileStateMessage(int profile, int cmd) {
        Message msg = new Message();
        msg.what = cmd;
        if (profile == BluetoothProfileState.HFP) {
            mHfpProfileState.sendMessage(msg);
        } else if (profile == BluetoothProfileState.A2DP) {
            mA2dpProfileState.sendMessage(msg);
        }
    }

    public int getAdapterConnectionState() {
        return mAdapterConnectionState;
    }

    public int getProfileConnectionState(int profile) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        Pair<Integer, Integer> state = mProfileConnectionState.get(profile);
        if (state == null) return BluetoothProfile.STATE_DISCONNECTED;

        return state.first;
    }

    private void updateProfileConnectionState(int profile, int newState, int oldState) {
        // mProfileConnectionState is a hashmap -
        // <Integer, Pair<Integer, Integer>>
        // The key is the profile, the value is a pair. first element
        // is the state and the second element is the number of devices
        // in that state.
        int numDev = 1;
        int newHashState = newState;
        boolean update = true;

        // The following conditions are considered in this function:
        // 1. If there is no record of profile and state - update
        // 2. If a new device's state is current hash state - increment
        //    number of devices in the state.
        // 3. If a state change has happened to Connected or Connecting
        //    (if current state is not connected), update.
        // 4. If numDevices is 1 and that device state is being updated, update
        // 5. If numDevices is > 1 and one of the devices is changing state,
        //    decrement numDevices but maintain oldState if it is Connected or
        //    Connecting
        Pair<Integer, Integer> stateNumDev = mProfileConnectionState.get(profile);
        if (stateNumDev != null) {
            int currHashState = stateNumDev.first;
            numDev = stateNumDev.second;

            if (newState == currHashState) {
                numDev ++;
            } else if (newState == BluetoothProfile.STATE_CONNECTED ||
                   (newState == BluetoothProfile.STATE_CONNECTING &&
                    currHashState != BluetoothProfile.STATE_CONNECTED)) {
                 numDev = 1;
            } else if (numDev == 1 && oldState == currHashState) {
                 update = true;
            } else if (numDev > 1 && oldState == currHashState) {
                 numDev --;

                 if (currHashState == BluetoothProfile.STATE_CONNECTED ||
                     currHashState == BluetoothProfile.STATE_CONNECTING) {
                    newHashState = currHashState;
                 }
            } else {
                 update = false;
            }
        }

        if (update) {
            mProfileConnectionState.put(profile, new Pair<Integer, Integer>(newHashState,
                    numDev));
        }
    }

    public synchronized void sendConnectionStateChange(BluetoothDevice
            device, int profile, int state, int prevState) {
        // Since this is a binder call check if Bluetooth is on still
        if (getBluetoothStateInternal() == BluetoothAdapter.STATE_OFF) return;

        if (!validateProfileConnectionState(state) ||
                !validateProfileConnectionState(prevState)) {
            // Previously, an invalid state was broadcast anyway,
            // with the invalid state converted to -1 in the intent.
            // Better to log an error and not send an intent with
            // invalid contents or set mAdapterConnectionState to -1.
            Log.e(TAG, "Error in sendConnectionStateChange: "
                    + "prevState " + prevState + " state " + state);
            return;
        }

        updateProfileConnectionState(profile, state, prevState);

        if (updateCountersAndCheckForConnectionStateChange(state, prevState)) {
            mAdapterConnectionState = state;

            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                mBluetoothState.sendMessage(BluetoothAdapterStateMachine.ALL_DEVICES_DISCONNECTED);
            }

            Intent intent = new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            intent.putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    convertToAdapterState(state));
            intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE,
                    convertToAdapterState(prevState));
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcast(intent, BLUETOOTH_PERM);
            Log.d(TAG, "CONNECTION_STATE_CHANGE: " + device + ": "
                    + prevState + " -> " + state);
        }
    }

    private boolean validateProfileConnectionState(int state) {
        return (state == BluetoothProfile.STATE_DISCONNECTED ||
                state == BluetoothProfile.STATE_CONNECTING ||
                state == BluetoothProfile.STATE_CONNECTED ||
                state == BluetoothProfile.STATE_DISCONNECTING);
    }

    private int convertToAdapterState(int state) {
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return BluetoothAdapter.STATE_DISCONNECTED;
            case BluetoothProfile.STATE_DISCONNECTING:
                return BluetoothAdapter.STATE_DISCONNECTING;
            case BluetoothProfile.STATE_CONNECTED:
                return BluetoothAdapter.STATE_CONNECTED;
            case BluetoothProfile.STATE_CONNECTING:
                return BluetoothAdapter.STATE_CONNECTING;
        }
        Log.e(TAG, "Error in convertToAdapterState");
        return -1;
    }

    private boolean updateCountersAndCheckForConnectionStateChange(int state, int prevState) {
        switch (prevState) {
            case BluetoothProfile.STATE_CONNECTING:
                mProfilesConnecting--;
                break;

            case BluetoothProfile.STATE_CONNECTED:
                mProfilesConnected--;
                break;

            case BluetoothProfile.STATE_DISCONNECTING:
                mProfilesDisconnecting--;
                break;
        }

        switch (state) {
            case BluetoothProfile.STATE_CONNECTING:
                mProfilesConnecting++;
                return (mProfilesConnected == 0 && mProfilesConnecting == 1);

            case BluetoothProfile.STATE_CONNECTED:
                mProfilesConnected++;
                return (mProfilesConnected == 1);

            case BluetoothProfile.STATE_DISCONNECTING:
                mProfilesDisconnecting++;
                return (mProfilesConnected == 0 && mProfilesDisconnecting == 1);

            case BluetoothProfile.STATE_DISCONNECTED:
                return (mProfilesConnected == 0 && mProfilesConnecting == 0);

            default:
                return true;
        }
    }

    private void createIncomingConnectionStateFile() {
        File f = new File(INCOMING_CONNECTION_FILE);
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "IOException: cannot create file");
            }
        }
    }

    /** @hide */
    public Pair<Integer, String> getIncomingState(String address) {
        if (mIncomingConnections.isEmpty()) {
            createIncomingConnectionStateFile();
            readIncomingConnectionState();
        }
        return mIncomingConnections.get(address);
    }

    private void readIncomingConnectionState() {
        synchronized(mIncomingConnections) {
            FileInputStream fstream = null;
            try {
              fstream = new FileInputStream(INCOMING_CONNECTION_FILE);
              DataInputStream in = new DataInputStream(fstream);
              BufferedReader file = new BufferedReader(new InputStreamReader(in));
              String line;
              while((line = file.readLine()) != null) {
                  line = line.trim();
                  if (line.length() == 0) continue;
                  String[] value = line.split(",");
                  if (value != null && value.length == 3) {
                      Integer val1 = Integer.parseInt(value[1]);
                      Pair<Integer, String> val = new Pair(val1, value[2]);
                      mIncomingConnections.put(value[0], val);
                  }
              }
            } catch (FileNotFoundException e) {
                log("FileNotFoundException: readIncomingConnectionState" + e.toString());
            } catch (IOException e) {
                log("IOException: readIncomingConnectionState" + e.toString());
            } finally {
                if (fstream != null) {
                    try {
                        fstream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }

    private void truncateIncomingConnectionFile() {
        RandomAccessFile r = null;
        try {
            r = new RandomAccessFile(INCOMING_CONNECTION_FILE, "rw");
            r.setLength(0);
        } catch (FileNotFoundException e) {
            log("FileNotFoundException: truncateIncomingConnectionState" + e.toString());
        } catch (IOException e) {
            log("IOException: truncateIncomingConnectionState" + e.toString());
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException e) {
                    // ignore
                 }
            }
        }
    }

    /** @hide */
    public void writeIncomingConnectionState(String address, Pair<Integer, String> data) {
        synchronized(mIncomingConnections) {
            mIncomingConnections.put(address, data);

            truncateIncomingConnectionFile();
            BufferedWriter out = null;
            StringBuilder value = new StringBuilder();
            try {
                out = new BufferedWriter(new FileWriter(INCOMING_CONNECTION_FILE, true));
                for (String devAddress: mIncomingConnections.keySet()) {
                  Pair<Integer, String> val = mIncomingConnections.get(devAddress);
                  value.append(devAddress);
                  value.append(",");
                  value.append(val.first.toString());
                  value.append(",");
                  value.append(val.second);
                  value.append("\n");
                }
                out.write(value.toString());
            } catch (FileNotFoundException e) {
                log("FileNotFoundException: writeIncomingConnectionState" + e.toString());
            } catch (IOException e) {
                log("IOException: writeIncomingConnectionState" + e.toString());
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private native static void classInitNative();
    private native void initializeNativeDataNative();
    private native boolean setupNativeDataNative();
    private native boolean tearDownNativeDataNative();
    private native void cleanupNativeDataNative();
    /*package*/ native String getAdapterPathNative();

    private native int isEnabledNative();
    /*package*/ native int enableNative();
    /*package*/ native int disableNative();

    /*package*/ native Object[] getAdapterPropertiesNative();
    private native Object[] getDevicePropertiesNative(String objectPath);
    private native boolean setAdapterPropertyStringNative(String key, String value);
    private native boolean setAdapterPropertyIntegerNative(String key, int value);
    private native boolean setAdapterPropertyBooleanNative(String key, int value);

    private native boolean startDiscoveryNative();
    private native boolean stopDiscoveryNative();

    private native boolean createPairedDeviceNative(String address, int timeout_ms);
    private native boolean createPairedDeviceOutOfBandNative(String address, int timeout_ms);
    private native byte[] readAdapterOutOfBandDataNative();

    private native boolean cancelDeviceCreationNative(String address);
    private native boolean removeDeviceNative(String objectPath);
    private native int getDeviceServiceChannelNative(String objectPath, String uuid,
            int attributeId);

    private native boolean cancelPairingUserInputNative(String address, int nativeData);
    private native boolean setPinNative(String address, String pin, int nativeData);
    private native boolean setPasskeyNative(String address, int passkey, int nativeData);
    private native boolean setPairingConfirmationNative(String address, boolean confirm,
            int nativeData);
    private native boolean setRemoteOutOfBandDataNative(String address, byte[] hash,
                                                        byte[] randomizer, int nativeData);

    private native boolean setDevicePropertyBooleanNative(String objectPath, String key,
            int value);
    private native boolean setDevicePropertyStringNative(String objectPath, String key,
            String value);
    private native boolean createDeviceNative(String address);
    /*package*/ native boolean discoverServicesNative(String objectPath, String pattern);

    private native int addRfcommServiceRecordNative(String name, long uuidMsb, long uuidLsb,
            short channel);
    private native boolean removeServiceRecordNative(int handle);
    private native boolean setLinkTimeoutNative(String path, int num_slots);

    native boolean connectInputDeviceNative(String path);
    native boolean disconnectInputDeviceNative(String path);

    native boolean setBluetoothTetheringNative(boolean value, String nap, String bridge);
    native boolean connectPanDeviceNative(String path, String dstRole);
    native boolean disconnectPanDeviceNative(String path);
    native boolean disconnectPanServerDeviceNative(String path,
            String address, String iface);

    private native int[] addReservedServiceRecordsNative(int[] uuuids);
    private native boolean removeReservedServiceRecordsNative(int[] handles);

    // Health API
    native String registerHealthApplicationNative(int dataType, String role, String name,
            String channelType);
    native String registerHealthApplicationNative(int dataType, String role, String name);
    native boolean unregisterHealthApplicationNative(String path);
    native boolean createChannelNative(String devicePath, String appPath, String channelType);
    native boolean destroyChannelNative(String devicePath, String channelpath);
    native String getMainChannelNative(String path);
    native String getChannelApplicationNative(String channelPath);
    native ParcelFileDescriptor getChannelFdNative(String channelPath);
    native boolean releaseChannelFdNative(String channelPath);
    native boolean setAuthorizationNative(String address, boolean value, int data);
}
