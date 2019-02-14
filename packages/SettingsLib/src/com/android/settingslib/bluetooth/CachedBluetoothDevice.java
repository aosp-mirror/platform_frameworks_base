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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * CachedBluetoothDevice represents a remote Bluetooth device. It contains
 * attributes of the device (such as the address, name, RSSI, etc.) and
 * functionality that can be performed on the device (connect, pair, disconnect,
 * etc.).
 */
public class CachedBluetoothDevice implements Comparable<CachedBluetoothDevice> {
    private static final String TAG = "CachedBluetoothDevice";
    private static final boolean DEBUG = Utils.V;

    private final Context mContext;
    private final LocalBluetoothAdapter mLocalAdapter;
    private final LocalBluetoothProfileManager mProfileManager;
    private final AudioManager mAudioManager;
    private final BluetoothDevice mDevice;
    //TODO: consider remove, BluetoothDevice.getName() is already cached
    private String mName;
    private long mHiSyncId;
    // Need this since there is no method for getting RSSI
    private short mRssi;
    //TODO: consider remove, BluetoothDevice.getBluetoothClass() is already cached
    private BluetoothClass mBtClass;
    private HashMap<LocalBluetoothProfile, Integer> mProfileConnectionState;

    private final List<LocalBluetoothProfile> mProfiles =
            new ArrayList<LocalBluetoothProfile>();

    // List of profiles that were previously in mProfiles, but have been removed
    private final List<LocalBluetoothProfile> mRemovedProfiles =
            new ArrayList<LocalBluetoothProfile>();

    // Device supports PANU but not NAP: remove PanProfile after device disconnects from NAP
    private boolean mLocalNapRoleConnected;

    private boolean mJustDiscovered;

    private int mMessageRejectionCount;

    private final Collection<Callback> mCallbacks = new ArrayList<Callback>();

    // Following constants indicate the user's choices of Phone book/message access settings
    // User hasn't made any choice or settings app has wiped out the memory
    public final static int ACCESS_UNKNOWN = 0;
    // User has accepted the connection and let Settings app remember the decision
    public final static int ACCESS_ALLOWED = 1;
    // User has rejected the connection and let Settings app remember the decision
    public final static int ACCESS_REJECTED = 2;

    // How many times user should reject the connection to make the choice persist.
    private final static int MESSAGE_REJECTION_COUNT_LIMIT_TO_PERSIST = 2;

    private final static String MESSAGE_REJECTION_COUNT_PREFS_NAME = "bluetooth_message_reject";

    /**
     * When we connect to multiple profiles, we only want to display a single
     * error even if they all fail. This tracks that state.
     */
    private boolean mIsConnectingErrorPossible;

    public long getHiSyncId() {
        return mHiSyncId;
    }

    public void setHiSyncId(long id) {
        if (Utils.D) {
            Log.d(TAG, "setHiSyncId: mDevice " + mDevice + ", id " + id);
        }
        mHiSyncId = id;
    }

    /**
     * Last time a bt profile auto-connect was attempted.
     * If an ACTION_UUID intent comes in within
     * MAX_UUID_DELAY_FOR_AUTO_CONNECT milliseconds, we will try auto-connect
     * again with the new UUIDs
     */
    private long mConnectAttempted;

    // See mConnectAttempted
    private static final long MAX_UUID_DELAY_FOR_AUTO_CONNECT = 5000;
    private static final long MAX_HOGP_DELAY_FOR_AUTO_CONNECT = 30000;

    // Active device state
    private boolean mIsActiveDeviceA2dp = false;
    private boolean mIsActiveDeviceHeadset = false;
    private boolean mIsActiveDeviceHearingAid = false;
    /**
     * Describes the current device and profile for logging.
     *
     * @param profile Profile to describe
     * @return Description of the device and profile
     */
    private String describe(LocalBluetoothProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("Address:").append(mDevice);
        if (profile != null) {
            sb.append(" Profile:").append(profile);
        }

        return sb.toString();
    }

    void onProfileStateChanged(LocalBluetoothProfile profile, int newProfileState) {
        if (Utils.D) {
            Log.d(TAG, "onProfileStateChanged: profile " + profile + ", device=" + mDevice
                    + ", newProfileState " + newProfileState);
        }
        if (mLocalAdapter.getBluetoothState() == BluetoothAdapter.STATE_TURNING_OFF)
        {
            if (Utils.D) Log.d(TAG, " BT Turninig Off...Profile conn state change ignored...");
            return;
        }
        mProfileConnectionState.put(profile, newProfileState);
        if (newProfileState == BluetoothProfile.STATE_CONNECTED) {
            if (profile instanceof MapProfile) {
                profile.setPreferred(mDevice, true);
            }
            if (!mProfiles.contains(profile)) {
                mRemovedProfiles.remove(profile);
                mProfiles.add(profile);
                if (profile instanceof PanProfile &&
                        ((PanProfile) profile).isLocalRoleNap(mDevice)) {
                    // Device doesn't support NAP, so remove PanProfile on disconnect
                    mLocalNapRoleConnected = true;
                }
            }
        } else if (profile instanceof MapProfile &&
                newProfileState == BluetoothProfile.STATE_DISCONNECTED) {
            profile.setPreferred(mDevice, false);
        } else if (mLocalNapRoleConnected && profile instanceof PanProfile &&
                ((PanProfile) profile).isLocalRoleNap(mDevice) &&
                newProfileState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.d(TAG, "Removing PanProfile from device after NAP disconnect");
            mProfiles.remove(profile);
            mRemovedProfiles.add(profile);
            mLocalNapRoleConnected = false;
        }
        fetchActiveDevices();
    }

    CachedBluetoothDevice(Context context,
                          LocalBluetoothAdapter adapter,
                          LocalBluetoothProfileManager profileManager,
                          BluetoothDevice device) {
        mContext = context;
        mLocalAdapter = adapter;
        mProfileManager = profileManager;
        mAudioManager = context.getSystemService(AudioManager.class);
        mDevice = device;
        mProfileConnectionState = new HashMap<LocalBluetoothProfile, Integer>();
        fillData();
        mHiSyncId = BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    public void disconnect() {
        for (LocalBluetoothProfile profile : mProfiles) {
            disconnect(profile);
        }
        // Disconnect  PBAP server in case its connected
        // This is to ensure all the profiles are disconnected as some CK/Hs do not
        // disconnect  PBAP connection when HF connection is brought down
        PbapServerProfile PbapProfile = mProfileManager.getPbapProfile();
        if (PbapProfile != null && isConnectedProfile(PbapProfile))
        {
            PbapProfile.disconnect(mDevice);
        }
    }

    public void disconnect(LocalBluetoothProfile profile) {
        if (profile.disconnect(mDevice)) {
            if (Utils.D) {
                Log.d(TAG, "Command sent successfully:DISCONNECT " + describe(profile));
            }
        }
    }

    public void connect(boolean connectAllProfiles) {
        if (!ensurePaired()) {
            return;
        }

        mConnectAttempted = SystemClock.elapsedRealtime();
        connectWithoutResettingTimer(connectAllProfiles);
    }

    public boolean isHearingAidDevice() {
        return mHiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    void onBondingDockConnect() {
        // Attempt to connect if UUIDs are available. Otherwise,
        // we will connect when the ACTION_UUID intent arrives.
        connect(false);
    }

    private void connectWithoutResettingTimer(boolean connectAllProfiles) {
        // Try to initialize the profiles if they were not.
        if (mProfiles.isEmpty()) {
            // if mProfiles is empty, then do not invoke updateProfiles. This causes a race
            // condition with carkits during pairing, wherein RemoteDevice.UUIDs have been updated
            // from bluetooth stack but ACTION.uuid is not sent yet.
            // Eventually ACTION.uuid will be received which shall trigger the connection of the
            // various profiles
            // If UUIDs are not available yet, connect will be happen
            // upon arrival of the ACTION_UUID intent.
            Log.d(TAG, "No profiles. Maybe we will connect later");
            return;
        }

        // Reset the only-show-one-error-dialog tracking variable
        mIsConnectingErrorPossible = true;

        int preferredProfiles = 0;
        for (LocalBluetoothProfile profile : mProfiles) {
            if (connectAllProfiles ? profile.accessProfileEnabled() : profile.isAutoConnectable()) {
                if (profile.isPreferred(mDevice)) {
                    ++preferredProfiles;
                    connectInt(profile);
                }
            }
        }
        if (DEBUG) Log.d(TAG, "Preferred profiles = " + preferredProfiles);

        if (preferredProfiles == 0) {
            connectAutoConnectableProfiles();
        }
    }

    private void connectAutoConnectableProfiles() {
        if (!ensurePaired()) {
            return;
        }
        // Reset the only-show-one-error-dialog tracking variable
        mIsConnectingErrorPossible = true;

        for (LocalBluetoothProfile profile : mProfiles) {
            if (profile.isAutoConnectable()) {
                profile.setPreferred(mDevice, true);
                connectInt(profile);
            }
        }
    }

    /**
     * Connect this device to the specified profile.
     *
     * @param profile the profile to use with the remote device
     */
    public void connectProfile(LocalBluetoothProfile profile) {
        mConnectAttempted = SystemClock.elapsedRealtime();
        // Reset the only-show-one-error-dialog tracking variable
        mIsConnectingErrorPossible = true;
        connectInt(profile);
        // Refresh the UI based on profile.connect() call
        refresh();
    }

    synchronized void connectInt(LocalBluetoothProfile profile) {
        if (!ensurePaired()) {
            return;
        }
        if (profile.connect(mDevice)) {
            if (Utils.D) {
                Log.d(TAG, "Command sent successfully:CONNECT " + describe(profile));
            }
            return;
        }
        Log.i(TAG, "Failed to connect " + profile.toString() + " to " + mName);
    }

    private boolean ensurePaired() {
        if (getBondState() == BluetoothDevice.BOND_NONE) {
            startPairing();
            return false;
        } else {
            return true;
        }
    }

    public boolean startPairing() {
        // Pairing is unreliable while scanning, so cancel discovery
        if (mLocalAdapter.isDiscovering()) {
            mLocalAdapter.cancelDiscovery();
        }

        if (!mDevice.createBond()) {
            return false;
        }

        return true;
    }

    /**
     * Return true if user initiated pairing on this device. The message text is
     * slightly different for local vs. remote initiated pairing dialogs.
     */
    boolean isUserInitiatedPairing() {
        return mDevice.isBondingInitiatedLocally();
    }

    public void unpair() {
        int state = getBondState();

        if (state == BluetoothDevice.BOND_BONDING) {
            mDevice.cancelBondProcess();
        }

        if (state != BluetoothDevice.BOND_NONE) {
            final BluetoothDevice dev = mDevice;
            if (dev != null) {
                final boolean successful = dev.removeBond();
                if (successful) {
                    if (Utils.D) {
                        Log.d(TAG, "Command sent successfully:REMOVE_BOND " + describe(null));
                    }
                } else if (Utils.V) {
                    Log.v(TAG, "Framework rejected command immediately:REMOVE_BOND " +
                        describe(null));
                }
            }
        }
    }

    public int getProfileConnectionState(LocalBluetoothProfile profile) {
        if (mProfileConnectionState.get(profile) == null) {
            // If cache is empty make the binder call to get the state
            int state = profile.getConnectionStatus(mDevice);
            mProfileConnectionState.put(profile, state);
        }
        return mProfileConnectionState.get(profile);
    }

    public void clearProfileConnectionState ()
    {
        if (Utils.D) {
            Log.d(TAG," Clearing all connection state for dev:" + mDevice.getName());
        }
        for (LocalBluetoothProfile profile :getProfiles()) {
            mProfileConnectionState.put(profile, BluetoothProfile.STATE_DISCONNECTED);
        }
    }

    // TODO: do any of these need to run async on a background thread?
    private void fillData() {
        fetchName();
        fetchBtClass();
        updateProfiles();
        fetchActiveDevices();
        migratePhonebookPermissionChoice();
        migrateMessagePermissionChoice();
        fetchMessageRejectionCount();

        dispatchAttributesChanged();
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Convenience method that can be mocked - it lets tests avoid having to call getDevice() which
     * causes problems in tests since BluetoothDevice is final and cannot be mocked.
     * @return the address of this device
     */
    public String getAddress() {
        return mDevice.getAddress();
    }

    public String getName() {
        return mName;
    }

    /**
     * Populate name from BluetoothDevice.ACTION_FOUND intent
     */
    void setNewName(String name) {
        if (mName == null) {
            mName = name;
            if (mName == null || TextUtils.isEmpty(mName)) {
                mName = mDevice.getAddress();
            }
            dispatchAttributesChanged();
        }
    }

    /**
     * User changes the device name
     * @param name new alias name to be set, should never be null
     */
    public void setName(String name) {
        // Prevent mName to be set to null if setName(null) is called
        if (name != null && !TextUtils.equals(name, mName)) {
            mName = name;
            mDevice.setAlias(name);
            dispatchAttributesChanged();
        }
    }

    /**
     * Set this device as active device
     * @return true if at least one profile on this device is set to active, false otherwise
     */
    public boolean setActive() {
        boolean result = false;
        A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
        if (a2dpProfile != null && isConnectedProfile(a2dpProfile)) {
            if (a2dpProfile.setActiveDevice(getDevice())) {
                Log.i(TAG, "OnPreferenceClickListener: A2DP active device=" + this);
                result = true;
            }
        }
        HeadsetProfile headsetProfile = mProfileManager.getHeadsetProfile();
        if ((headsetProfile != null) && isConnectedProfile(headsetProfile)) {
            if (headsetProfile.setActiveDevice(getDevice())) {
                Log.i(TAG, "OnPreferenceClickListener: Headset active device=" + this);
                result = true;
            }
        }
        HearingAidProfile hearingAidProfile = mProfileManager.getHearingAidProfile();
        if ((hearingAidProfile != null) && isConnectedProfile(hearingAidProfile)) {
            if (hearingAidProfile.setActiveDevice(getDevice())) {
                Log.i(TAG, "OnPreferenceClickListener: Hearing Aid active device=" + this);
                result = true;
            }
        }
        return result;
    }

    void refreshName() {
        fetchName();
        dispatchAttributesChanged();
    }

    private void fetchName() {
        mName = mDevice.getAliasName();

        if (TextUtils.isEmpty(mName)) {
            mName = mDevice.getAddress();
            if (DEBUG) Log.d(TAG, "Device has no name (yet), use address: " + mName);
        }
    }

    /**
     * Checks if device has a human readable name besides MAC address
     * @return true if device's alias name is not null nor empty, false otherwise
     */
    public boolean hasHumanReadableName() {
        return !TextUtils.isEmpty(mDevice.getAliasName());
    }

    /**
     * Get battery level from remote device
     * @return battery level in percentage [0-100], or {@link BluetoothDevice#BATTERY_LEVEL_UNKNOWN}
     */
    public int getBatteryLevel() {
        return mDevice.getBatteryLevel();
    }

    void refresh() {
        dispatchAttributesChanged();
    }

    public void setJustDiscovered(boolean justDiscovered) {
        if (mJustDiscovered != justDiscovered) {
            mJustDiscovered = justDiscovered;
            dispatchAttributesChanged();
        }
    }

    public int getBondState() {
        return mDevice.getBondState();
    }

    /**
     * Update the device status as active or non-active per Bluetooth profile.
     *
     * @param isActive true if the device is active
     * @param bluetoothProfile the Bluetooth profile
     */
    public void onActiveDeviceChanged(boolean isActive, int bluetoothProfile) {
        boolean changed = false;
        switch (bluetoothProfile) {
        case BluetoothProfile.A2DP:
            changed = (mIsActiveDeviceA2dp != isActive);
            mIsActiveDeviceA2dp = isActive;
            break;
        case BluetoothProfile.HEADSET:
            changed = (mIsActiveDeviceHeadset != isActive);
            mIsActiveDeviceHeadset = isActive;
            break;
        case BluetoothProfile.HEARING_AID:
            changed = (mIsActiveDeviceHearingAid != isActive);
            mIsActiveDeviceHearingAid = isActive;
            break;
        default:
            Log.w(TAG, "onActiveDeviceChanged: unknown profile " + bluetoothProfile +
                    " isActive " + isActive);
            break;
        }
        if (changed) {
            dispatchAttributesChanged();
        }
    }

    /**
     * Update the profile audio state.
     */
    void onAudioModeChanged() {
        dispatchAttributesChanged();
    }
    /**
     * Get the device status as active or non-active per Bluetooth profile.
     *
     * @param bluetoothProfile the Bluetooth profile
     * @return true if the device is active
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public boolean isActiveDevice(int bluetoothProfile) {
        switch (bluetoothProfile) {
            case BluetoothProfile.A2DP:
                return mIsActiveDeviceA2dp;
            case BluetoothProfile.HEADSET:
                return mIsActiveDeviceHeadset;
            case BluetoothProfile.HEARING_AID:
                return mIsActiveDeviceHearingAid;
            default:
                Log.w(TAG, "getActiveDevice: unknown profile " + bluetoothProfile);
                break;
        }
        return false;
    }

    void setRssi(short rssi) {
        if (mRssi != rssi) {
            mRssi = rssi;
            dispatchAttributesChanged();
        }
    }

    /**
     * Checks whether we are connected to this device (any profile counts).
     *
     * @return Whether it is connected.
     */
    public boolean isConnected() {
        for (LocalBluetoothProfile profile : mProfiles) {
            int status = getProfileConnectionState(profile);
            if (status == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }
        }

        return false;
    }

    public boolean isConnectedProfile(LocalBluetoothProfile profile) {
        int status = getProfileConnectionState(profile);
        return status == BluetoothProfile.STATE_CONNECTED;

    }

    public boolean isBusy() {
        for (LocalBluetoothProfile profile : mProfiles) {
            int status = getProfileConnectionState(profile);
            if (status == BluetoothProfile.STATE_CONNECTING
                    || status == BluetoothProfile.STATE_DISCONNECTING) {
                return true;
            }
        }
        return getBondState() == BluetoothDevice.BOND_BONDING;
    }

    /**
     * Fetches a new value for the cached BT class.
     */
    private void fetchBtClass() {
        mBtClass = mDevice.getBluetoothClass();
    }

    private boolean updateProfiles() {
        ParcelUuid[] uuids = mDevice.getUuids();
        if (uuids == null) return false;

        ParcelUuid[] localUuids = mLocalAdapter.getUuids();
        if (localUuids == null) return false;

        /*
         * Now we know if the device supports PBAP, update permissions...
         */
        processPhonebookAccess();

        mProfileManager.updateProfiles(uuids, localUuids, mProfiles, mRemovedProfiles,
                                       mLocalNapRoleConnected, mDevice);

        if (DEBUG) {
            Log.e(TAG, "updating profiles for " + mDevice.getAliasName());
            BluetoothClass bluetoothClass = mDevice.getBluetoothClass();

            if (bluetoothClass != null) Log.v(TAG, "Class: " + bluetoothClass.toString());
            Log.v(TAG, "UUID:");
            for (ParcelUuid uuid : uuids) {
                Log.v(TAG, "  " + uuid);
            }
        }
        return true;
    }

    private void fetchActiveDevices() {
        A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
        if (a2dpProfile != null) {
            mIsActiveDeviceA2dp = mDevice.equals(a2dpProfile.getActiveDevice());
        }
        HeadsetProfile headsetProfile = mProfileManager.getHeadsetProfile();
        if (headsetProfile != null) {
            mIsActiveDeviceHeadset = mDevice.equals(headsetProfile.getActiveDevice());
        }
        HearingAidProfile hearingAidProfile = mProfileManager.getHearingAidProfile();
        if (hearingAidProfile != null) {
            mIsActiveDeviceHearingAid = hearingAidProfile.getActiveDevices().contains(mDevice);
        }
    }

    /**
     * Refreshes the UI for the BT class, including fetching the latest value
     * for the class.
     */
    void refreshBtClass() {
        fetchBtClass();
        dispatchAttributesChanged();
    }

    /**
     * Refreshes the UI when framework alerts us of a UUID change.
     */
    void onUuidChanged() {
        updateProfiles();
        ParcelUuid[] uuids = mDevice.getUuids();

        long timeout = MAX_UUID_DELAY_FOR_AUTO_CONNECT;
        if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hogp)) {
            timeout = MAX_HOGP_DELAY_FOR_AUTO_CONNECT;
        }

        if (DEBUG) {
            Log.d(TAG, "onUuidChanged: Time since last connect"
                    + (SystemClock.elapsedRealtime() - mConnectAttempted));
        }

        /*
         * If a connect was attempted earlier without any UUID, we will do the connect now.
         * Otherwise, allow the connect on UUID change.
         */
        if (!mProfiles.isEmpty()
                && ((mConnectAttempted + timeout) > SystemClock.elapsedRealtime())) {
            connectWithoutResettingTimer(false);
        }

        dispatchAttributesChanged();
    }

    void onBondingStateChanged(int bondState) {
        if (bondState == BluetoothDevice.BOND_NONE) {
            mProfiles.clear();
            setPhonebookPermissionChoice(ACCESS_UNKNOWN);
            setMessagePermissionChoice(ACCESS_UNKNOWN);
            setSimPermissionChoice(ACCESS_UNKNOWN);
            mMessageRejectionCount = 0;
            saveMessageRejectionCount();
        }

        refresh();

        if (bondState == BluetoothDevice.BOND_BONDED) {
            if (mDevice.isBluetoothDock()) {
                onBondingDockConnect();
            } else if (mDevice.isBondingInitiatedLocally()) {
                connect(false);
            }
        }
    }

    void setBtClass(BluetoothClass btClass) {
        if (btClass != null && mBtClass != btClass) {
            mBtClass = btClass;
            dispatchAttributesChanged();
        }
    }

    public BluetoothClass getBtClass() {
        return mBtClass;
    }

    public List<LocalBluetoothProfile> getProfiles() {
        return Collections.unmodifiableList(mProfiles);
    }

    public List<LocalBluetoothProfile> getConnectableProfiles() {
        List<LocalBluetoothProfile> connectableProfiles =
                new ArrayList<LocalBluetoothProfile>();
        for (LocalBluetoothProfile profile : mProfiles) {
            if (profile.accessProfileEnabled()) {
                connectableProfiles.add(profile);
            }
        }
        return connectableProfiles;
    }

    public List<LocalBluetoothProfile> getRemovedProfiles() {
        return mRemovedProfiles;
    }

    public void registerCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    public void unregisterCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    private void dispatchAttributesChanged() {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onDeviceAttributesChanged();
            }
        }
    }

    @Override
    public String toString() {
        return mDevice.toString();
    }

    @Override
    public boolean equals(Object o) {
        if ((o == null) || !(o instanceof CachedBluetoothDevice)) {
            return false;
        }
        return mDevice.equals(((CachedBluetoothDevice) o).mDevice);
    }

    @Override
    public int hashCode() {
        return mDevice.getAddress().hashCode();
    }

    // This comparison uses non-final fields so the sort order may change
    // when device attributes change (such as bonding state). Settings
    // will completely refresh the device list when this happens.
    public int compareTo(CachedBluetoothDevice another) {
        // Connected above not connected
        int comparison = (another.isConnected() ? 1 : 0) - (isConnected() ? 1 : 0);
        if (comparison != 0) return comparison;

        // Paired above not paired
        comparison = (another.getBondState() == BluetoothDevice.BOND_BONDED ? 1 : 0) -
            (getBondState() == BluetoothDevice.BOND_BONDED ? 1 : 0);
        if (comparison != 0) return comparison;

        // Just discovered above discovered in the past
        comparison = (another.mJustDiscovered ? 1 : 0) - (mJustDiscovered ? 1 : 0);
        if (comparison != 0) return comparison;

        // Stronger signal above weaker signal
        comparison = another.mRssi - mRssi;
        if (comparison != 0) return comparison;

        // Fallback on name
        return mName.compareTo(another.mName);
    }

    public interface Callback {
        void onDeviceAttributesChanged();
    }

    public int getPhonebookPermissionChoice() {
        int permission = mDevice.getPhonebookAccessPermission();
        if (permission == BluetoothDevice.ACCESS_ALLOWED) {
            return ACCESS_ALLOWED;
        } else if (permission == BluetoothDevice.ACCESS_REJECTED) {
            return ACCESS_REJECTED;
        }
        return ACCESS_UNKNOWN;
    }

    public void setPhonebookPermissionChoice(int permissionChoice) {
        int permission = BluetoothDevice.ACCESS_UNKNOWN;
        if (permissionChoice == ACCESS_ALLOWED) {
            permission = BluetoothDevice.ACCESS_ALLOWED;
        } else if (permissionChoice == ACCESS_REJECTED) {
            permission = BluetoothDevice.ACCESS_REJECTED;
        }
        mDevice.setPhonebookAccessPermission(permission);
    }

    // Migrates data from old data store (in Settings app's shared preferences) to new (in Bluetooth
    // app's shared preferences).
    private void migratePhonebookPermissionChoice() {
        SharedPreferences preferences = mContext.getSharedPreferences(
                "bluetooth_phonebook_permission", Context.MODE_PRIVATE);
        if (!preferences.contains(mDevice.getAddress())) {
            return;
        }

        if (mDevice.getPhonebookAccessPermission() == BluetoothDevice.ACCESS_UNKNOWN) {
            int oldPermission = preferences.getInt(mDevice.getAddress(), ACCESS_UNKNOWN);
            if (oldPermission == ACCESS_ALLOWED) {
                mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
            } else if (oldPermission == ACCESS_REJECTED) {
                mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_REJECTED);
            }
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(mDevice.getAddress());
        editor.commit();
    }

    public int getMessagePermissionChoice() {
        int permission = mDevice.getMessageAccessPermission();
        if (permission == BluetoothDevice.ACCESS_ALLOWED) {
            return ACCESS_ALLOWED;
        } else if (permission == BluetoothDevice.ACCESS_REJECTED) {
            return ACCESS_REJECTED;
        }
        return ACCESS_UNKNOWN;
    }

    public void setMessagePermissionChoice(int permissionChoice) {
        int permission = BluetoothDevice.ACCESS_UNKNOWN;
        if (permissionChoice == ACCESS_ALLOWED) {
            permission = BluetoothDevice.ACCESS_ALLOWED;
        } else if (permissionChoice == ACCESS_REJECTED) {
            permission = BluetoothDevice.ACCESS_REJECTED;
        }
        mDevice.setMessageAccessPermission(permission);
    }

    public int getSimPermissionChoice() {
        int permission = mDevice.getSimAccessPermission();
        if (permission == BluetoothDevice.ACCESS_ALLOWED) {
            return ACCESS_ALLOWED;
        } else if (permission == BluetoothDevice.ACCESS_REJECTED) {
            return ACCESS_REJECTED;
        }
        return ACCESS_UNKNOWN;
    }

    void setSimPermissionChoice(int permissionChoice) {
        int permission = BluetoothDevice.ACCESS_UNKNOWN;
        if (permissionChoice == ACCESS_ALLOWED) {
            permission = BluetoothDevice.ACCESS_ALLOWED;
        } else if (permissionChoice == ACCESS_REJECTED) {
            permission = BluetoothDevice.ACCESS_REJECTED;
        }
        mDevice.setSimAccessPermission(permission);
    }

    // Migrates data from old data store (in Settings app's shared preferences) to new (in Bluetooth
    // app's shared preferences).
    private void migrateMessagePermissionChoice() {
        SharedPreferences preferences = mContext.getSharedPreferences(
                "bluetooth_message_permission", Context.MODE_PRIVATE);
        if (!preferences.contains(mDevice.getAddress())) {
            return;
        }

        if (mDevice.getMessageAccessPermission() == BluetoothDevice.ACCESS_UNKNOWN) {
            int oldPermission = preferences.getInt(mDevice.getAddress(), ACCESS_UNKNOWN);
            if (oldPermission == ACCESS_ALLOWED) {
                mDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
            } else if (oldPermission == ACCESS_REJECTED) {
                mDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_REJECTED);
            }
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(mDevice.getAddress());
        editor.commit();
    }

    /**
     * @return Whether this rejection should persist.
     */
    public boolean checkAndIncreaseMessageRejectionCount() {
        if (mMessageRejectionCount < MESSAGE_REJECTION_COUNT_LIMIT_TO_PERSIST) {
            mMessageRejectionCount++;
            saveMessageRejectionCount();
        }
        return mMessageRejectionCount >= MESSAGE_REJECTION_COUNT_LIMIT_TO_PERSIST;
    }

    private void fetchMessageRejectionCount() {
        SharedPreferences preference = mContext.getSharedPreferences(
                MESSAGE_REJECTION_COUNT_PREFS_NAME, Context.MODE_PRIVATE);
        mMessageRejectionCount = preference.getInt(mDevice.getAddress(), 0);
    }

    private void saveMessageRejectionCount() {
        SharedPreferences.Editor editor = mContext.getSharedPreferences(
                MESSAGE_REJECTION_COUNT_PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (mMessageRejectionCount == 0) {
            editor.remove(mDevice.getAddress());
        } else {
            editor.putInt(mDevice.getAddress(), mMessageRejectionCount);
        }
        editor.commit();
    }

    private void processPhonebookAccess() {
        if (mDevice.getBondState() != BluetoothDevice.BOND_BONDED) return;

        ParcelUuid[] uuids = mDevice.getUuids();
        if (BluetoothUuid.containsAnyUuid(uuids, PbapServerProfile.PBAB_CLIENT_UUIDS)) {
            // The pairing dialog now warns of phone-book access for paired devices.
            // No separate prompt is displayed after pairing.
            if (getPhonebookPermissionChoice() == CachedBluetoothDevice.ACCESS_UNKNOWN) {
                if (mDevice.getBluetoothClass().getDeviceClass()
                        == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                    mDevice.getBluetoothClass().getDeviceClass()
                        == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET) {
                    setPhonebookPermissionChoice(CachedBluetoothDevice.ACCESS_ALLOWED);
                } else {
                    setPhonebookPermissionChoice(CachedBluetoothDevice.ACCESS_REJECTED);
                }
            }
        }
    }

    public int getMaxConnectionState() {
        int maxState = BluetoothProfile.STATE_DISCONNECTED;
        for (LocalBluetoothProfile profile : getProfiles()) {
            int connectionStatus = getProfileConnectionState(profile);
            if (connectionStatus > maxState) {
                maxState = connectionStatus;
            }
        }
        return maxState;
    }

    /**
     * @return resource for string that discribes the connection state of this device.
     * case 1: idle or playing media, show "Active" on the only one A2DP active device.
     * case 2: in phone call, show "Active" on the only one HFP active device
     */
    public String getConnectionSummary() {
        boolean profileConnected = false;    // Updated as long as BluetoothProfile is connected
        boolean a2dpConnected = true;        // A2DP is connected
        boolean hfpConnected = true;         // HFP is connected
        boolean hearingAidConnected = true;  // Hearing Aid is connected

        for (LocalBluetoothProfile profile : getProfiles()) {
            int connectionStatus = getProfileConnectionState(profile);

            switch (connectionStatus) {
                case BluetoothProfile.STATE_CONNECTING:
                case BluetoothProfile.STATE_DISCONNECTING:
                    return mContext.getString(Utils.getConnectionStateSummary(connectionStatus));

                case BluetoothProfile.STATE_CONNECTED:
                    profileConnected = true;
                    break;

                case BluetoothProfile.STATE_DISCONNECTED:
                    if (profile.isProfileReady()) {
                        if ((profile instanceof A2dpProfile) ||
                                (profile instanceof A2dpSinkProfile)) {
                            a2dpConnected = false;
                        } else if ((profile instanceof HeadsetProfile) ||
                                (profile instanceof HfpClientProfile)) {
                            hfpConnected = false;
                        } else if (profile instanceof HearingAidProfile) {
                            hearingAidConnected = false;
                        }
                    }
                    break;
            }
        }

        String batteryLevelPercentageString = null;
        // Android framework should only set mBatteryLevel to valid range [0-100] or
        // BluetoothDevice.BATTERY_LEVEL_UNKNOWN, any other value should be a framework bug.
        // Thus assume here that if value is not BluetoothDevice.BATTERY_LEVEL_UNKNOWN, it must
        // be valid
        final int batteryLevel = getBatteryLevel();
        if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
            // TODO: name com.android.settingslib.bluetooth.Utils something different
            batteryLevelPercentageString =
                    com.android.settingslib.Utils.formatPercentage(batteryLevel);
        }

        int stringRes = R.string.bluetooth_pairing;
        //when profile is connected, information would be available
        if (profileConnected) {
            if (a2dpConnected || hfpConnected || hearingAidConnected) {
                //contain battery information
                if (batteryLevelPercentageString != null) {
                    //device is in phone call
                    if (com.android.settingslib.Utils.isAudioModeOngoingCall(mContext)) {
                        if (mIsActiveDeviceHeadset) {
                            stringRes = R.string.bluetooth_active_battery_level;
                        } else {
                            stringRes = R.string.bluetooth_battery_level;
                        }
                    } else {//device is not in phone call(ex. idle or playing media)
                        //need to check if A2DP and HearingAid are exclusive
                        if (mIsActiveDeviceHearingAid || mIsActiveDeviceA2dp) {
                            stringRes = R.string.bluetooth_active_battery_level;
                        } else {
                            stringRes = R.string.bluetooth_battery_level;
                        }
                    }
                } else {
                    //no battery information
                    if (com.android.settingslib.Utils.isAudioModeOngoingCall(mContext)) {
                        if (mIsActiveDeviceHeadset) {
                            stringRes = R.string.bluetooth_active_no_battery_level;
                        }
                    } else {
                        if (mIsActiveDeviceHearingAid || mIsActiveDeviceA2dp) {
                            stringRes = R.string.bluetooth_active_no_battery_level;
                        }
                    }
                }
            } else {//unknown profile with battery information
                if (batteryLevelPercentageString != null) {
                    stringRes = R.string.bluetooth_battery_level;
                }
            }
        }

        return (stringRes != R.string.bluetooth_pairing
                || getBondState() == BluetoothDevice.BOND_BONDING)
                ? mContext.getString(stringRes, batteryLevelPercentageString)
                : null;
    }

    /**
     * @return resource for android auto string that describes the connection state of this device.
     */
    public String getCarConnectionSummary() {
        boolean profileConnected = false;       // at least one profile is connected
        boolean a2dpNotConnected = false;       // A2DP is preferred but not connected
        boolean hfpNotConnected = false;        // HFP is preferred but not connected
        boolean hearingAidNotConnected = false; // Hearing Aid is preferred but not connected

        for (LocalBluetoothProfile profile : getProfiles()) {
            int connectionStatus = getProfileConnectionState(profile);

            switch (connectionStatus) {
                case BluetoothProfile.STATE_CONNECTING:
                case BluetoothProfile.STATE_DISCONNECTING:
                    return mContext.getString(Utils.getConnectionStateSummary(connectionStatus));

                case BluetoothProfile.STATE_CONNECTED:
                    profileConnected = true;
                    break;

                case BluetoothProfile.STATE_DISCONNECTED:
                    if (profile.isProfileReady()) {
                        if ((profile instanceof A2dpProfile) ||
                                (profile instanceof A2dpSinkProfile)){
                            a2dpNotConnected = true;
                        } else if ((profile instanceof HeadsetProfile) ||
                                (profile instanceof HfpClientProfile)) {
                            hfpNotConnected = true;
                        } else if (profile instanceof  HearingAidProfile) {
                            hearingAidNotConnected = true;
                        }
                    }
                    break;
            }
        }

        String batteryLevelPercentageString = null;
        // Android framework should only set mBatteryLevel to valid range [0-100] or
        // BluetoothDevice.BATTERY_LEVEL_UNKNOWN, any other value should be a framework bug.
        // Thus assume here that if value is not BluetoothDevice.BATTERY_LEVEL_UNKNOWN, it must
        // be valid
        final int batteryLevel = getBatteryLevel();
        if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
            // TODO: name com.android.settingslib.bluetooth.Utils something different
            batteryLevelPercentageString =
                    com.android.settingslib.Utils.formatPercentage(batteryLevel);
        }

        // Prepare the string for the Active Device summary
        String[] activeDeviceStringsArray = mContext.getResources().getStringArray(
                R.array.bluetooth_audio_active_device_summaries);
        String activeDeviceString = activeDeviceStringsArray[0];  // Default value: not active
        if (mIsActiveDeviceA2dp && mIsActiveDeviceHeadset) {
            activeDeviceString = activeDeviceStringsArray[1];     // Active for Media and Phone
        } else {
            if (mIsActiveDeviceA2dp) {
                activeDeviceString = activeDeviceStringsArray[2]; // Active for Media only
            }
            if (mIsActiveDeviceHeadset) {
                activeDeviceString = activeDeviceStringsArray[3]; // Active for Phone only
            }
        }
        if (!hearingAidNotConnected && mIsActiveDeviceHearingAid) {
            activeDeviceString = activeDeviceStringsArray[1];
            return mContext.getString(R.string.bluetooth_connected, activeDeviceString);
        }

        if (profileConnected) {
            if (a2dpNotConnected && hfpNotConnected) {
                if (batteryLevelPercentageString != null) {
                    return mContext.getString(
                            R.string.bluetooth_connected_no_headset_no_a2dp_battery_level,
                            batteryLevelPercentageString, activeDeviceString);
                } else {
                    return mContext.getString(R.string.bluetooth_connected_no_headset_no_a2dp,
                            activeDeviceString);
                }

            } else if (a2dpNotConnected) {
                if (batteryLevelPercentageString != null) {
                    return mContext.getString(R.string.bluetooth_connected_no_a2dp_battery_level,
                            batteryLevelPercentageString, activeDeviceString);
                } else {
                    return mContext.getString(R.string.bluetooth_connected_no_a2dp,
                            activeDeviceString);
                }

            } else if (hfpNotConnected) {
                if (batteryLevelPercentageString != null) {
                    return mContext.getString(R.string.bluetooth_connected_no_headset_battery_level,
                            batteryLevelPercentageString, activeDeviceString);
                } else {
                    return mContext.getString(R.string.bluetooth_connected_no_headset,
                            activeDeviceString);
                }
            } else {
                if (batteryLevelPercentageString != null) {
                    return mContext.getString(R.string.bluetooth_connected_battery_level,
                            batteryLevelPercentageString, activeDeviceString);
                } else {
                    return mContext.getString(R.string.bluetooth_connected, activeDeviceString);
                }
            }
        }

        return getBondState() == BluetoothDevice.BOND_BONDING ?
                mContext.getString(R.string.bluetooth_pairing) : null;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is a2dp device
     */
    public boolean isA2dpDevice() {
        A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
        return a2dpProfile != null && a2dpProfile.getConnectionStatus(mDevice) ==
                BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is HFP device
     */
    public boolean isHfpDevice() {
        HeadsetProfile headsetProfile = mProfileManager.getHeadsetProfile();
        return headsetProfile != null && headsetProfile.getConnectionStatus(mDevice) ==
                BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is Hearing Aid device
     */
    public boolean isConnectedHearingAidDevice() {
        HearingAidProfile hearingAidProfile = mProfileManager.getHearingAidProfile();
        return hearingAidProfile != null && hearingAidProfile.getConnectionStatus(mDevice) ==
                BluetoothProfile.STATE_CONNECTED;
    }
}
