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
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.R;
import com.android.settingslib.Utils;
import com.android.settingslib.utils.ThreadUtils;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CachedBluetoothDevice represents a remote Bluetooth device. It contains
 * attributes of the device (such as the address, name, RSSI, etc.) and
 * functionality that can be performed on the device (connect, pair, disconnect,
 * etc.).
 */
public class CachedBluetoothDevice implements Comparable<CachedBluetoothDevice> {
    private static final String TAG = "CachedBluetoothDevice";

    // See mConnectAttempted
    private static final long MAX_UUID_DELAY_FOR_AUTO_CONNECT = 5000;
    // Some Hearing Aids (especially the 2nd device) needs more time to do service discovery
    private static final long MAX_HEARING_AIDS_DELAY_FOR_AUTO_CONNECT = 15000;
    private static final long MAX_HOGP_DELAY_FOR_AUTO_CONNECT = 30000;
    private static final long MAX_MEDIA_PROFILE_CONNECT_DELAY = 60000;

    private final Context mContext;
    private final BluetoothAdapter mLocalAdapter;
    private final LocalBluetoothProfileManager mProfileManager;
    private final Object mProfileLock = new Object();
    BluetoothDevice mDevice;
    private long mHiSyncId;
    private int mGroupId;
    // Need this since there is no method for getting RSSI
    short mRssi;
    // mProfiles and mRemovedProfiles does not do swap() between main and sub device. It is
    // because current sub device is only for HearingAid and its profile is the same.
    private final Collection<LocalBluetoothProfile> mProfiles = new CopyOnWriteArrayList<>();

    // List of profiles that were previously in mProfiles, but have been removed
    private final Collection<LocalBluetoothProfile> mRemovedProfiles = new CopyOnWriteArrayList<>();

    // Device supports PANU but not NAP: remove PanProfile after device disconnects from NAP
    private boolean mLocalNapRoleConnected;

    boolean mJustDiscovered;

    boolean mIsCoordinatedSetMember = false;

    private final Collection<Callback> mCallbacks = new CopyOnWriteArrayList<>();

    /**
     * Last time a bt profile auto-connect was attempted.
     * If an ACTION_UUID intent comes in within
     * MAX_UUID_DELAY_FOR_AUTO_CONNECT milliseconds, we will try auto-connect
     * again with the new UUIDs
     */
    private long mConnectAttempted;

    // Active device state
    private boolean mIsActiveDeviceA2dp = false;
    private boolean mIsActiveDeviceHeadset = false;
    private boolean mIsActiveDeviceHearingAid = false;
    // Media profile connect state
    private boolean mIsA2dpProfileConnectedFail = false;
    private boolean mIsHeadsetProfileConnectedFail = false;
    private boolean mIsHearingAidProfileConnectedFail = false;
    private boolean mUnpairing;
    // Group second device for Hearing Aid
    private CachedBluetoothDevice mSubDevice;
    // Group member devices for the coordinated set
    private Set<CachedBluetoothDevice> mMemberDevices = new HashSet<CachedBluetoothDevice>();
    @VisibleForTesting
    LruCache<String, BitmapDrawable> mDrawableCache;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BluetoothProfile.A2DP:
                    mIsA2dpProfileConnectedFail = true;
                    break;
                case BluetoothProfile.HEADSET:
                    mIsHeadsetProfileConnectedFail = true;
                    break;
                case BluetoothProfile.HEARING_AID:
                    mIsHearingAidProfileConnectedFail = true;
                    break;
                default:
                    Log.w(TAG, "handleMessage(): unknown message : " + msg.what);
                    break;
            }
            Log.w(TAG, "Connect to profile : " + msg.what + " timeout, show error message !");
            refresh();
        }
    };

    CachedBluetoothDevice(Context context, LocalBluetoothProfileManager profileManager,
            BluetoothDevice device) {
        mContext = context;
        mLocalAdapter = BluetoothAdapter.getDefaultAdapter();
        mProfileManager = profileManager;
        mDevice = device;
        fillData();
        mHiSyncId = BluetoothHearingAid.HI_SYNC_ID_INVALID;
        mGroupId = BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        initDrawableCache();
        mUnpairing = false;
    }

    private void initDrawableCache() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;

        mDrawableCache = new LruCache<String, BitmapDrawable>(cacheSize) {
            @Override
            protected int sizeOf(String key, BitmapDrawable bitmap) {
                return bitmap.getBitmap().getByteCount() / 1024;
            }
        };
    }

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
        if (BluetoothUtils.D) {
            Log.d(TAG, "onProfileStateChanged: profile " + profile + ", device "
                    + mDevice.getAlias() + ", newProfileState " + newProfileState);
        }
        if (mLocalAdapter.getState() == BluetoothAdapter.STATE_TURNING_OFF)
        {
            if (BluetoothUtils.D) {
                Log.d(TAG, " BT Turninig Off...Profile conn state change ignored...");
            }
            return;
        }

        synchronized (mProfileLock) {
            if (profile instanceof A2dpProfile || profile instanceof HeadsetProfile
                    || profile instanceof HearingAidProfile) {
                setProfileConnectedStatus(profile.getProfileId(), false);
                switch (newProfileState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        mHandler.removeMessages(profile.getProfileId());
                        break;
                    case BluetoothProfile.STATE_CONNECTING:
                        mHandler.sendEmptyMessageDelayed(profile.getProfileId(),
                                MAX_MEDIA_PROFILE_CONNECT_DELAY);
                        break;
                    case BluetoothProfile.STATE_DISCONNECTING:
                        if (mHandler.hasMessages(profile.getProfileId())) {
                            mHandler.removeMessages(profile.getProfileId());
                        }
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                        if (mHandler.hasMessages(profile.getProfileId())) {
                            mHandler.removeMessages(profile.getProfileId());
                            setProfileConnectedStatus(profile.getProfileId(), true);
                        }
                        break;
                    default:
                        Log.w(TAG, "onProfileStateChanged(): unknown profile state : "
                                + newProfileState);
                        break;
                }
            }

            if (newProfileState == BluetoothProfile.STATE_CONNECTED) {
                if (profile instanceof MapProfile) {
                    profile.setEnabled(mDevice, true);
                }
                if (!mProfiles.contains(profile)) {
                    mRemovedProfiles.remove(profile);
                    mProfiles.add(profile);
                    if (profile instanceof PanProfile
                            && ((PanProfile) profile).isLocalRoleNap(mDevice)) {
                        // Device doesn't support NAP, so remove PanProfile on disconnect
                        mLocalNapRoleConnected = true;
                    }
                }
            } else if (profile instanceof MapProfile
                    && newProfileState == BluetoothProfile.STATE_DISCONNECTED) {
                profile.setEnabled(mDevice, false);
            } else if (mLocalNapRoleConnected && profile instanceof PanProfile
                    && ((PanProfile) profile).isLocalRoleNap(mDevice)
                    && newProfileState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Removing PanProfile from device after NAP disconnect");
                mProfiles.remove(profile);
                mRemovedProfiles.add(profile);
                mLocalNapRoleConnected = false;
            }
        }

        fetchActiveDevices();
    }

    @VisibleForTesting
    void setProfileConnectedStatus(int profileId, boolean isFailed) {
        switch (profileId) {
            case BluetoothProfile.A2DP:
                mIsA2dpProfileConnectedFail = isFailed;
                break;
            case BluetoothProfile.HEADSET:
                mIsHeadsetProfileConnectedFail = isFailed;
                break;
            case BluetoothProfile.HEARING_AID:
                mIsHearingAidProfileConnectedFail = isFailed;
                break;
            default:
                Log.w(TAG, "setProfileConnectedStatus(): unknown profile id : " + profileId);
                break;
        }
    }

    public void disconnect() {
        synchronized (mProfileLock) {
            mLocalAdapter.disconnectAllEnabledProfiles(mDevice);
        }
        // Disconnect  PBAP server in case its connected
        // This is to ensure all the profiles are disconnected as some CK/Hs do not
        // disconnect  PBAP connection when HF connection is brought down
        PbapServerProfile PbapProfile = mProfileManager.getPbapProfile();
        if (PbapProfile != null && isConnectedProfile(PbapProfile))
        {
            PbapProfile.setEnabled(mDevice, false);
        }
    }

    public void disconnect(LocalBluetoothProfile profile) {
        if (profile.setEnabled(mDevice, false)) {
            if (BluetoothUtils.D) {
                Log.d(TAG, "Command sent successfully:DISCONNECT " + describe(profile));
            }
        }
    }

    /**
     * Connect this device.
     *
     * @param connectAllProfiles {@code true} to connect all profile, {@code false} otherwise.
     *
     * @deprecated use {@link #connect()} instead.
     */
    @Deprecated
    public void connect(boolean connectAllProfiles) {
        connect();
    }

    /**
     * Connect this device.
     */
    public void connect() {
        if (!ensurePaired()) {
            return;
        }

        mConnectAttempted = SystemClock.elapsedRealtime();
        connectAllEnabledProfiles();
    }

    public long getHiSyncId() {
        return mHiSyncId;
    }

    public void setHiSyncId(long id) {
        mHiSyncId = id;
    }

    public boolean isHearingAidDevice() {
        return mHiSyncId != BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    /**
     * Mark the discovered device as member of coordinated set.
     *
     * @param isCoordinatedSetMember {@code true}, if the device is a member of a coordinated set.
     */
    public void setIsCoordinatedSetMember(boolean isCoordinatedSetMember) {
        mIsCoordinatedSetMember = isCoordinatedSetMember;
    }

    /**
     * Check if the device is a CSIP member device.
     *
     * @return {@code true}, if this device supports CSIP, otherwise returns {@code false}.
     */
    public boolean isCoordinatedSetMemberDevice() {
        return mIsCoordinatedSetMember;
    }

    /**
    * Get the coordinated set group id.
    *
    * @return the group id.
    */
    public int getGroupId() {
        return mGroupId;
    }

    /**
    * Set the coordinated set group id.
    *
    * @param id the group id from the CSIP.
    */
    public void setGroupId(int id) {
        mGroupId = id;
    }

    void onBondingDockConnect() {
        // Attempt to connect if UUIDs are available. Otherwise,
        // we will connect when the ACTION_UUID intent arrives.
        connect();
    }

    private void connectAllEnabledProfiles() {
        synchronized (mProfileLock) {
            // Try to initialize the profiles if they were not.
            if (mProfiles.isEmpty()) {
                // if mProfiles is empty, then do not invoke updateProfiles. This causes a race
                // condition with carkits during pairing, wherein RemoteDevice.UUIDs have been
                // updated from bluetooth stack but ACTION.uuid is not sent yet.
                // Eventually ACTION.uuid will be received which shall trigger the connection of the
                // various profiles
                // If UUIDs are not available yet, connect will be happen
                // upon arrival of the ACTION_UUID intent.
                Log.d(TAG, "No profiles. Maybe we will connect later for device " + mDevice);
                return;
            }

            mLocalAdapter.connectAllEnabledProfiles(mDevice);
        }
    }

    /**
     * Connect this device to the specified profile.
     *
     * @param profile the profile to use with the remote device
     */
    public void connectProfile(LocalBluetoothProfile profile) {
        mConnectAttempted = SystemClock.elapsedRealtime();
        connectInt(profile);
        // Refresh the UI based on profile.connect() call
        refresh();
    }

    synchronized void connectInt(LocalBluetoothProfile profile) {
        if (!ensurePaired()) {
            return;
        }
        if (profile.setEnabled(mDevice, true)) {
            if (BluetoothUtils.D) {
                Log.d(TAG, "Command sent successfully:CONNECT " + describe(profile));
            }
            return;
        }
        Log.i(TAG, "Failed to connect " + profile.toString() + " to " + getName());
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

    public void unpair() {
        int state = getBondState();

        if (state == BluetoothDevice.BOND_BONDING) {
            mDevice.cancelBondProcess();
        }

        if (state != BluetoothDevice.BOND_NONE) {
            final BluetoothDevice dev = mDevice;
            if (dev != null) {
                mUnpairing = true;
                final boolean successful = dev.removeBond();
                if (successful) {
                    releaseLruCache();
                    if (BluetoothUtils.D) {
                        Log.d(TAG, "Command sent successfully:REMOVE_BOND " + describe(null));
                    }
                } else if (BluetoothUtils.V) {
                    Log.v(TAG, "Framework rejected command immediately:REMOVE_BOND " +
                        describe(null));
                }
            }
        }
    }

    public int getProfileConnectionState(LocalBluetoothProfile profile) {
        return profile != null
                ? profile.getConnectionStatus(mDevice)
                : BluetoothProfile.STATE_DISCONNECTED;
    }

    // TODO: do any of these need to run async on a background thread?
    private void fillData() {
        updateProfiles();
        fetchActiveDevices();
        migratePhonebookPermissionChoice();
        migrateMessagePermissionChoice();

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

    /**
     * Get name from remote device
     * @return {@link BluetoothDevice#getAlias()} if
     * {@link BluetoothDevice#getAlias()} is not null otherwise return
     * {@link BluetoothDevice#getAddress()}
     */
    public String getName() {
        final String aliasName = mDevice.getAlias();
        return TextUtils.isEmpty(aliasName) ? getAddress() : aliasName;
    }

    /**
     * User changes the device name
     * @param name new alias name to be set, should never be null
     */
    public void setName(String name) {
        // Prevent getName() to be set to null if setName(null) is called
        if (name != null && !TextUtils.equals(name, getName())) {
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
        if (BluetoothUtils.D) {
            Log.d(TAG, "Device name: " + getName());
        }
        dispatchAttributesChanged();
    }

    /**
     * Checks if device has a human readable name besides MAC address
     * @return true if device's alias name is not null nor empty, false otherwise
     */
    public boolean hasHumanReadableName() {
        return !TextUtils.isEmpty(mDevice.getAlias());
    }

    /**
     * Get battery level from remote device
     * @return battery level in percentage [0-100],
     * {@link BluetoothDevice#BATTERY_LEVEL_BLUETOOTH_OFF}, or
     * {@link BluetoothDevice#BATTERY_LEVEL_UNKNOWN}
     */
    public int getBatteryLevel() {
        return mDevice.getBatteryLevel();
    }

    void refresh() {
        ThreadUtils.postOnBackgroundThread(() -> {
            if (BluetoothUtils.isAdvancedDetailsHeader(mDevice)) {
                Uri uri = BluetoothUtils.getUriMetaData(getDevice(),
                        BluetoothDevice.METADATA_MAIN_ICON);
                if (uri != null && mDrawableCache.get(uri.toString()) == null) {
                    mDrawableCache.put(uri.toString(),
                            (BitmapDrawable) BluetoothUtils.getBtDrawableWithDescription(
                                    mContext, this).first);
                }
            }

            ThreadUtils.postOnMainThread(() -> {
                dispatchAttributesChanged();
            });
        });
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
        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : mProfiles) {
                int status = getProfileConnectionState(profile);
                if (status == BluetoothProfile.STATE_CONNECTED) {
                    return true;
                }
            }

            return false;
        }
    }

    public boolean isConnectedProfile(LocalBluetoothProfile profile) {
        int status = getProfileConnectionState(profile);
        return status == BluetoothProfile.STATE_CONNECTED;

    }

    public boolean isBusy() {
        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : mProfiles) {
                int status = getProfileConnectionState(profile);
                if (status == BluetoothProfile.STATE_CONNECTING
                        || status == BluetoothProfile.STATE_DISCONNECTING) {
                    return true;
                }
            }
            return getBondState() == BluetoothDevice.BOND_BONDING;
        }
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

        synchronized (mProfileLock) {
            mProfileManager.updateProfiles(uuids, localUuids, mProfiles, mRemovedProfiles,
                    mLocalNapRoleConnected, mDevice);
        }

        if (BluetoothUtils.D) {
            Log.d(TAG, "updating profiles for " + mDevice.getAlias());
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
     * Refreshes the UI when framework alerts us of a UUID change.
     */
    void onUuidChanged() {
        updateProfiles();
        ParcelUuid[] uuids = mDevice.getUuids();

        long timeout = MAX_UUID_DELAY_FOR_AUTO_CONNECT;
        if (ArrayUtils.contains(uuids, BluetoothUuid.HOGP)) {
            timeout = MAX_HOGP_DELAY_FOR_AUTO_CONNECT;
        } else if (ArrayUtils.contains(uuids, BluetoothUuid.HEARING_AID)) {
            timeout = MAX_HEARING_AIDS_DELAY_FOR_AUTO_CONNECT;
        }

        if (BluetoothUtils.D) {
            Log.d(TAG, "onUuidChanged: Time since last connect="
                    + (SystemClock.elapsedRealtime() - mConnectAttempted));
        }

        /*
         * If a connect was attempted earlier without any UUID, we will do the connect now.
         * Otherwise, allow the connect on UUID change.
         */
        if ((mConnectAttempted + timeout) > SystemClock.elapsedRealtime()) {
            Log.d(TAG, "onUuidChanged: triggering connectAllEnabledProfiles");
            connectAllEnabledProfiles();
        }

        dispatchAttributesChanged();
    }

    void onBondingStateChanged(int bondState) {
        if (bondState == BluetoothDevice.BOND_NONE) {
            synchronized (mProfileLock) {
                mProfiles.clear();
            }
            mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_UNKNOWN);
            mDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_UNKNOWN);
            mDevice.setSimAccessPermission(BluetoothDevice.ACCESS_UNKNOWN);
        }

        refresh();

        if (bondState == BluetoothDevice.BOND_BONDED && mDevice.isBondingInitiatedLocally()) {
            connect();
        }
    }

    public BluetoothClass getBtClass() {
        return mDevice.getBluetoothClass();
    }

    public List<LocalBluetoothProfile> getProfiles() {
        return new ArrayList<>(mProfiles);
    }

    public List<LocalBluetoothProfile> getConnectableProfiles() {
        List<LocalBluetoothProfile> connectableProfiles =
                new ArrayList<LocalBluetoothProfile>();
        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : mProfiles) {
                if (profile.accessProfileEnabled()) {
                    connectableProfiles.add(profile);
                }
            }
        }
        return connectableProfiles;
    }

    public List<LocalBluetoothProfile> getRemovedProfiles() {
        return new ArrayList<>(mRemovedProfiles);
    }

    public void registerCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void unregisterCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    void dispatchAttributesChanged() {
        for (Callback callback : mCallbacks) {
            callback.onDeviceAttributesChanged();
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
        return getName().compareTo(another.getName());
    }

    public interface Callback {
        void onDeviceAttributesChanged();
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
            int oldPermission =
                    preferences.getInt(mDevice.getAddress(), BluetoothDevice.ACCESS_UNKNOWN);
            if (oldPermission == BluetoothDevice.ACCESS_ALLOWED) {
                mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
            } else if (oldPermission == BluetoothDevice.ACCESS_REJECTED) {
                mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_REJECTED);
            }
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(mDevice.getAddress());
        editor.commit();
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
            int oldPermission =
                    preferences.getInt(mDevice.getAddress(), BluetoothDevice.ACCESS_UNKNOWN);
            if (oldPermission == BluetoothDevice.ACCESS_ALLOWED) {
                mDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
            } else if (oldPermission == BluetoothDevice.ACCESS_REJECTED) {
                mDevice.setMessageAccessPermission(BluetoothDevice.ACCESS_REJECTED);
            }
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(mDevice.getAddress());
        editor.commit();
    }

    private void processPhonebookAccess() {
        if (mDevice.getBondState() != BluetoothDevice.BOND_BONDED) return;

        ParcelUuid[] uuids = mDevice.getUuids();
        if (BluetoothUuid.containsAnyUuid(uuids, PbapServerProfile.PBAB_CLIENT_UUIDS)) {
            // The pairing dialog now warns of phone-book access for paired devices.
            // No separate prompt is displayed after pairing.
            final BluetoothClass bluetoothClass = mDevice.getBluetoothClass();
            if (mDevice.getPhonebookAccessPermission() == BluetoothDevice.ACCESS_UNKNOWN) {
                if (bluetoothClass != null && (bluetoothClass.getDeviceClass()
                        == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                        || bluetoothClass.getDeviceClass()
                        == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)) {
                    EventLog.writeEvent(0x534e4554, "138529441", -1, "");
                }
                mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_REJECTED);
            }
        }
    }

    public int getMaxConnectionState() {
        int maxState = BluetoothProfile.STATE_DISCONNECTED;
        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : getProfiles()) {
                int connectionStatus = getProfileConnectionState(profile);
                if (connectionStatus > maxState) {
                    maxState = connectionStatus;
                }
            }
        }
        return maxState;
    }

    /**
     * Return full summary that describes connection state of this device
     *
     * @see #getConnectionSummary(boolean shortSummary)
     */
    public String getConnectionSummary() {
        return getConnectionSummary(false /* shortSummary */);
    }

    /**
     * Return summary that describes connection state of this device. Summary depends on:
     * 1. Whether device has battery info
     * 2. Whether device is in active usage(or in phone call)
     *
     * @param shortSummary {@code true} if need to return short version summary
     */
    public String getConnectionSummary(boolean shortSummary) {
        boolean profileConnected = false;    // Updated as long as BluetoothProfile is connected
        boolean a2dpConnected = true;        // A2DP is connected
        boolean hfpConnected = true;         // HFP is connected
        boolean hearingAidConnected = true;  // Hearing Aid is connected
        int leftBattery = -1;
        int rightBattery = -1;

        if (isProfileConnectedFail() && isConnected()) {
            return mContext.getString(R.string.profile_connect_timeout_subtext);
        }

        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : getProfiles()) {
                int connectionStatus = getProfileConnectionState(profile);

                switch (connectionStatus) {
                    case BluetoothProfile.STATE_CONNECTING:
                    case BluetoothProfile.STATE_DISCONNECTING:
                        return mContext.getString(
                                BluetoothUtils.getConnectionStateSummary(connectionStatus));

                    case BluetoothProfile.STATE_CONNECTED:
                        profileConnected = true;
                        break;

                    case BluetoothProfile.STATE_DISCONNECTED:
                        if (profile.isProfileReady()) {
                            if (profile instanceof A2dpProfile
                                    || profile instanceof A2dpSinkProfile) {
                                a2dpConnected = false;
                            } else if (profile instanceof HeadsetProfile
                                    || profile instanceof HfpClientProfile) {
                                hfpConnected = false;
                            } else if (profile instanceof HearingAidProfile) {
                                hearingAidConnected = false;
                            }
                        }
                        break;
                }
            }
        }

        String batteryLevelPercentageString = null;
        // Android framework should only set mBatteryLevel to valid range [0-100],
        // BluetoothDevice.BATTERY_LEVEL_BLUETOOTH_OFF, or BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
        // any other value should be a framework bug. Thus assume here that if value is greater
        // than BluetoothDevice.BATTERY_LEVEL_UNKNOWN, it must be valid
        final int batteryLevel = getBatteryLevel();
        if (batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
            // TODO: name com.android.settingslib.bluetooth.Utils something different
            batteryLevelPercentageString =
                    com.android.settingslib.Utils.formatPercentage(batteryLevel);
        }

        int stringRes = R.string.bluetooth_pairing;
        //when profile is connected, information would be available
        if (profileConnected) {
            // Update Meta data for connected device
            if (BluetoothUtils.getBooleanMetaData(
                    mDevice, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
                leftBattery = BluetoothUtils.getIntMetaData(mDevice,
                        BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY);
                rightBattery = BluetoothUtils.getIntMetaData(mDevice,
                        BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY);
            }

            // Set default string with battery level in device connected situation.
            if (isTwsBatteryAvailable(leftBattery, rightBattery)) {
                stringRes = R.string.bluetooth_battery_level_untethered;
            } else if (batteryLevelPercentageString != null) {
                stringRes = R.string.bluetooth_battery_level;
            }

            // Set active string in following device connected situation.
            //    1. Hearing Aid device active.
            //    2. Headset device active with in-calling state.
            //    3. A2DP device active without in-calling state.
            if (a2dpConnected || hfpConnected || hearingAidConnected) {
                final boolean isOnCall = Utils.isAudioModeOngoingCall(mContext);
                if ((mIsActiveDeviceHearingAid)
                        || (mIsActiveDeviceHeadset && isOnCall)
                        || (mIsActiveDeviceA2dp && !isOnCall)) {
                    if (isTwsBatteryAvailable(leftBattery, rightBattery) && !shortSummary) {
                        stringRes = R.string.bluetooth_active_battery_level_untethered;
                    } else if (batteryLevelPercentageString != null && !shortSummary) {
                        stringRes = R.string.bluetooth_active_battery_level;
                    } else {
                        stringRes = R.string.bluetooth_active_no_battery_level;
                    }
                }
            }
        }

        if (stringRes != R.string.bluetooth_pairing
                || getBondState() == BluetoothDevice.BOND_BONDING) {
            if (isTwsBatteryAvailable(leftBattery, rightBattery)) {
                return mContext.getString(stringRes, Utils.formatPercentage(leftBattery),
                        Utils.formatPercentage(rightBattery));
            } else {
                return mContext.getString(stringRes, batteryLevelPercentageString);
            }
        } else {
            return null;
        }
    }

    private boolean isTwsBatteryAvailable(int leftBattery, int rightBattery) {
        return leftBattery >= 0 && rightBattery >= 0;
    }

    private boolean isProfileConnectedFail() {
        return mIsA2dpProfileConnectedFail || mIsHearingAidProfileConnectedFail
                || (!isConnectedSapDevice() && mIsHeadsetProfileConnectedFail);
    }

    /**
     * @return resource for android auto string that describes the connection state of this device.
     */
    public String getCarConnectionSummary() {
        boolean profileConnected = false;       // at least one profile is connected
        boolean a2dpNotConnected = false;       // A2DP is preferred but not connected
        boolean hfpNotConnected = false;        // HFP is preferred but not connected
        boolean hearingAidNotConnected = false; // Hearing Aid is preferred but not connected

        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : getProfiles()) {
                int connectionStatus = getProfileConnectionState(profile);

                switch (connectionStatus) {
                    case BluetoothProfile.STATE_CONNECTING:
                    case BluetoothProfile.STATE_DISCONNECTING:
                        return mContext.getString(
                                BluetoothUtils.getConnectionStateSummary(connectionStatus));

                    case BluetoothProfile.STATE_CONNECTED:
                        profileConnected = true;
                        break;

                    case BluetoothProfile.STATE_DISCONNECTED:
                        if (profile.isProfileReady()) {
                            if (profile instanceof A2dpProfile
                                    || profile instanceof A2dpSinkProfile) {
                                a2dpNotConnected = true;
                            } else if (profile instanceof HeadsetProfile
                                    || profile instanceof HfpClientProfile) {
                                hfpNotConnected = true;
                            } else if (profile instanceof HearingAidProfile) {
                                hearingAidNotConnected = true;
                            }
                        }
                        break;
                }
            }
        }

        String batteryLevelPercentageString = null;
        // Android framework should only set mBatteryLevel to valid range [0-100],
        // BluetoothDevice.BATTERY_LEVEL_BLUETOOTH_OFF, or BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
        // any other value should be a framework bug. Thus assume here that if value is greater
        // than BluetoothDevice.BATTERY_LEVEL_UNKNOWN, it must be valid
        final int batteryLevel = getBatteryLevel();
        if (batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
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
    public boolean isConnectedA2dpDevice() {
        A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
        return a2dpProfile != null && a2dpProfile.getConnectionStatus(mDevice) ==
                BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is HFP device
     */
    public boolean isConnectedHfpDevice() {
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

    private boolean isConnectedSapDevice() {
        SapProfile sapProfile = mProfileManager.getSapProfile();
        return sapProfile != null && sapProfile.getConnectionStatus(mDevice)
                == BluetoothProfile.STATE_CONNECTED;
    }

    public CachedBluetoothDevice getSubDevice() {
        return mSubDevice;
    }

    public void setSubDevice(CachedBluetoothDevice subDevice) {
        mSubDevice = subDevice;
    }

    public void switchSubDeviceContent() {
        // Backup from main device
        BluetoothDevice tmpDevice = mDevice;
        short tmpRssi = mRssi;
        boolean tmpJustDiscovered = mJustDiscovered;
        // Set main device from sub device
        mDevice = mSubDevice.mDevice;
        mRssi = mSubDevice.mRssi;
        mJustDiscovered = mSubDevice.mJustDiscovered;
        // Set sub device from backup
        mSubDevice.mDevice = tmpDevice;
        mSubDevice.mRssi = tmpRssi;
        mSubDevice.mJustDiscovered = tmpJustDiscovered;
        fetchActiveDevices();
    }

    /**
     * @return a set of member devices that are in the same coordinated set with this device.
     */
    public Set<CachedBluetoothDevice> getMemberDevice() {
        return mMemberDevices;
    }

    /**
     * Store the member devices that are in the same coordinated set.
     */
    public void setMemberDevice(CachedBluetoothDevice memberDevice) {
        mMemberDevices.add(memberDevice);
    }

    /**
     * Remove a device from the member device sets.
     */
    public void removeMemberDevice(CachedBluetoothDevice memberDevice) {
        mMemberDevices.remove(memberDevice);
    }

    /**
     * In order to show the preference for the whole group, we always set the main device as the
     * first connected device in the coordinated set, and then switch the content of the main
     * device and member devices.
     *
     * @param prevMainDevice the previous Main device, it will be added into the member device set.
     * @param newMainDevie the new Main device, it will be removed from the member device set.
     */
    public void switchMemberDeviceContent(CachedBluetoothDevice prevMainDevice,
            CachedBluetoothDevice newMainDevie) {
        // Backup from main device
        final BluetoothDevice tmpDevice = mDevice;
        final short tmpRssi = mRssi;
        final boolean tmpJustDiscovered = mJustDiscovered;
        // Set main device from sub device
        mDevice = newMainDevie.mDevice;
        mRssi = newMainDevie.mRssi;
        mJustDiscovered = newMainDevie.mJustDiscovered;
        setMemberDevice(prevMainDevice);
        mMemberDevices.remove(newMainDevie);
        // Set sub device from backup
        newMainDevie.mDevice = tmpDevice;
        newMainDevie.mRssi = tmpRssi;
        newMainDevie.mJustDiscovered = tmpJustDiscovered;
        fetchActiveDevices();
    }

    /**
     * Get cached bluetooth icon with description
     */
    public Pair<Drawable, String> getDrawableWithDescription() {
        Uri uri = BluetoothUtils.getUriMetaData(mDevice, BluetoothDevice.METADATA_MAIN_ICON);
        Pair<Drawable, String> pair = BluetoothUtils.getBtClassDrawableWithDescription(
                mContext, this);

        if (BluetoothUtils.isAdvancedDetailsHeader(mDevice) && uri != null) {
            BitmapDrawable drawable = mDrawableCache.get(uri.toString());
            if (drawable != null) {
                Resources resources = mContext.getResources();
                return new Pair<>(new AdaptiveOutlineDrawable(
                        resources, drawable.getBitmap()), pair.second);
            }

            refresh();
        }

        return new Pair<>(BluetoothUtils.buildBtRainbowDrawable(
                        mContext, pair.first, getAddress().hashCode()), pair.second);
    }

    void releaseLruCache() {
        mDrawableCache.evictAll();
    }

    boolean getUnpairing() {
        return mUnpairing;
    }
}
