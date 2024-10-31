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

import static com.android.settingslib.flags.Flags.enableSetPreferredTransportForLeAudioDevice;

import android.annotation.CallbackExecutor;
import android.annotation.StringRes;
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
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.R;
import com.android.settingslib.Utils;
import com.android.settingslib.media.flags.Flags;
import com.android.settingslib.utils.ThreadUtils;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

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
    private static final long MAX_LEAUDIO_DELAY_FOR_AUTO_CONNECT = 30000;
    private static final long MAX_MEDIA_PROFILE_CONNECT_DELAY = 60000;

    private static final int DEFAULT_LOW_BATTERY_THRESHOLD = 20;

    // To be used instead of a resource id to indicate that low battery states should not be
    // changed to a different color.
    private static final int SUMMARY_NO_COLOR_FOR_LOW_BATTERY = 0;

    private final Context mContext;
    private final BluetoothAdapter mLocalAdapter;
    private final LocalBluetoothProfileManager mProfileManager;
    private final Object mProfileLock = new Object();
    BluetoothDevice mDevice;
    private HearingAidInfo mHearingAidInfo;
    private int mGroupId;
    private Timestamp mBondTimestamp;
    private LocalBluetoothManager mBluetoothManager;

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

    private final Map<Callback, Executor> mCallbackExecutorMap = new ConcurrentHashMap<>();

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
    private boolean mIsActiveDeviceLeAudio = false;
    // Media profile connect state
    private boolean mIsA2dpProfileConnectedFail = false;
    private boolean mIsHeadsetProfileConnectedFail = false;
    private boolean mIsHearingAidProfileConnectedFail = false;
    private boolean mIsLeAudioProfileConnectedFail = false;
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
                case BluetoothProfile.LE_AUDIO:
                    mIsLeAudioProfileConnectedFail = true;
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
        mGroupId = BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        initDrawableCache();
        mUnpairing = false;
    }

    /** Clears any pending messages in the message queue. */
    public void release() {
        mHandler.removeCallbacksAndMessages(null);
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
                    + mDevice.getAnonymizedAddress() + ", newProfileState " + newProfileState);
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
                    || profile instanceof HearingAidProfile || profile instanceof LeAudioProfile) {
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
                            if (profile.getConnectionPolicy(mDevice) >
                                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
                                /*
                                 * If we received state DISCONNECTED and previous state was
                                 * CONNECTING and connection policy is FORBIDDEN or UNKNOWN
                                 * then it's not really a failure to connect.
                                 *
                                 * Connection profile is considered as failed when connection
                                 * policy indicates that profile should be connected
                                 * but it got disconnected.
                                 */
                                Log.w(TAG, "onProfileStateChanged(): Failed to connect profile");
                                setProfileConnectedStatus(profile.getProfileId(), true);
                            }
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
                if (enableSetPreferredTransportForLeAudioDevice()
                        && profile instanceof HidProfile) {
                    updatePreferredTransport();
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

            if (enableSetPreferredTransportForLeAudioDevice()
                    && profile instanceof LeAudioProfile) {
                updatePreferredTransport();
            }

            HearingAidStatsLogUtils.updateHistoryIfNeeded(mContext, this, profile, newProfileState);
        }

        fetchActiveDevices();
    }

    private void updatePreferredTransport() {
        LeAudioProfile leAudioProfile =
                (LeAudioProfile)
                        mProfiles.stream()
                                .filter(p -> p instanceof LeAudioProfile)
                                .findFirst()
                                .orElse(null);
        HidProfile hidProfile =
                (HidProfile)
                        mProfiles.stream()
                                .filter(p -> p instanceof HidProfile)
                                .findFirst()
                                .orElse(null);
        if (leAudioProfile == null || hidProfile == null) {
            return;
        }
        // Both LeAudioProfile and HidProfile are connectable.
        if (!hidProfile.setPreferredTransport(
                mDevice,
                leAudioProfile.isEnabled(mDevice)
                        ? BluetoothDevice.TRANSPORT_LE
                        : BluetoothDevice.TRANSPORT_BREDR)) {
            Log.w(TAG, "Fail to set preferred transport");
        }
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
            case BluetoothProfile.LE_AUDIO:
                mIsLeAudioProfileConnectedFail = isFailed;
                break;
            default:
                Log.w(TAG, "setProfileConnectedStatus(): unknown profile id : " + profileId);
                break;
        }
    }

    public void disconnect() {
        synchronized (mProfileLock) {
            if (getGroupId() != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                for (CachedBluetoothDevice member : getMemberDevice()) {
                    Log.d(TAG, "Disconnect the member:" + member);
                    member.disconnect();
                }
            }
            Log.d(TAG, "Disconnect " + this);
            mDevice.disconnect();
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
        connectDevice();
    }

    public void setHearingAidInfo(HearingAidInfo hearingAidInfo) {
        mHearingAidInfo = hearingAidInfo;
        dispatchAttributesChanged();
    }

    public HearingAidInfo getHearingAidInfo() {
        return mHearingAidInfo;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is hearing aid device
     */
    public boolean isHearingAidDevice() {
        return mHearingAidInfo != null;
    }

    public int getDeviceSide() {
        return mHearingAidInfo != null
                ? mHearingAidInfo.getSide() : HearingAidInfo.DeviceSide.SIDE_INVALID;
    }

    public int getDeviceMode() {
        return mHearingAidInfo != null
                ? mHearingAidInfo.getMode() : HearingAidInfo.DeviceMode.MODE_INVALID;
    }

    public long getHiSyncId() {
        return mHearingAidInfo != null
                ? mHearingAidInfo.getHiSyncId() : BluetoothHearingAid.HI_SYNC_ID_INVALID;
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
        Log.d(TAG, this.getDevice().getAnonymizedAddress() + " set GroupId " + id);
        mGroupId = id;
    }

    void onBondingDockConnect() {
        // Attempt to connect if UUIDs are available. Otherwise,
        // we will connect when the ACTION_UUID intent arrives.
        connect();
    }

    private void connectDevice() {
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
            Log.d(TAG, "connect " + this);
            mDevice.connect();
            if (getGroupId() != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                for (CachedBluetoothDevice member : getMemberDevice()) {
                    Log.d(TAG, "connect the member:" + member);
                    member.connect();
                }
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
    void fillData() {
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
     * Get identity address from remote device
     * @return {@link BluetoothDevice#getIdentityAddress()} if
     * {@link BluetoothDevice#getIdentityAddress()} is not null otherwise return
     * {@link BluetoothDevice#getAddress()}
     */
    public String getIdentityAddress() {
        final String identityAddress = mDevice.getIdentityAddress();
        return TextUtils.isEmpty(identityAddress) ? getAddress() : identityAddress;
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
        if (TextUtils.isEmpty(name) || TextUtils.equals(name, getName())) {
            return;
        }
        mDevice.setAlias(name);
        dispatchAttributesChanged();

        for (CachedBluetoothDevice cbd : mMemberDevices) {
            cbd.setName(name);
        }
        if (mSubDevice != null) {
            mSubDevice.setName(name);
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
        LeAudioProfile leAudioProfile = mProfileManager.getLeAudioProfile();
        if ((leAudioProfile != null) && isConnectedProfile(leAudioProfile)) {
            if (leAudioProfile.setActiveDevice(getDevice())) {
                Log.i(TAG, "OnPreferenceClickListener: LeAudio active device=" + this);
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

    /**
     * Get the lowest battery level from remote device and its member devices
     * @return battery level in percentage [0-100] or
     * {@link BluetoothDevice#BATTERY_LEVEL_UNKNOWN}
     */
    public int getMinBatteryLevelWithMemberDevices() {
        return Stream.concat(Stream.of(this), mMemberDevices.stream())
                .mapToInt(cachedDevice -> cachedDevice.getBatteryLevel())
                .filter(batteryLevel -> batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN)
                .min()
                .orElse(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
    }

    /**
     * Get the lowest battery level from remote device and its member devices if it's greater than
     * BluetoothDevice.BATTERY_LEVEL_UNKNOWN.
     *
     * <p>Android framework should only set mBatteryLevel to valid range [0-100],
     * BluetoothDevice.BATTERY_LEVEL_BLUETOOTH_OFF, or BluetoothDevice.BATTERY_LEVEL_UNKNOWN, any
     * other value should be a framework bug. Thus assume here that if value is greater than
     * BluetoothDevice.BATTERY_LEVEL_UNKNOWN, it must be valid
     *
     * @return battery level in String [0-100] or Null if this lower than
     *     BluetoothDevice.BATTERY_LEVEL_UNKNOWN
     */
    @Nullable
    private String getValidMinBatteryLevelWithMemberDevices() {
        final int batteryLevel = getMinBatteryLevelWithMemberDevices();
        return batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN
                ? com.android.settingslib.Utils.formatPercentage(batteryLevel)
                : null;
    }

    void refresh() {
        ListenableFuture<Void> future = ThreadUtils.getBackgroundExecutor().submit(() -> {
            if (BluetoothUtils.isAdvancedDetailsHeader(mDevice)) {
                Uri uri = BluetoothUtils.getUriMetaData(getDevice(),
                        BluetoothDevice.METADATA_MAIN_ICON);
                if (uri != null && mDrawableCache.get(uri.toString()) == null) {
                    mDrawableCache.put(uri.toString(),
                            (BitmapDrawable) BluetoothUtils.getBtDrawableWithDescription(
                                    mContext, this).first);
                }
            }
            return null;
        });
        Futures.addCallback(future, new FutureCallback<>() {
            @Override
            public void onSuccess(Void result) {
                dispatchAttributesChanged();
            }

            @Override
            public void onFailure(Throwable t) {}
        }, mContext.getMainExecutor());
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
        if (BluetoothUtils.D) {
            Log.d(TAG, "onActiveDeviceChanged: "
                    + "profile " + BluetoothProfile.getProfileName(bluetoothProfile)
                    + ", device " + mDevice.getAnonymizedAddress()
                    + ", isActive " + isActive);
        }
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
        case BluetoothProfile.LE_AUDIO:
            changed = (mIsActiveDeviceLeAudio != isActive);
            mIsActiveDeviceLeAudio = isActive;
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
     * Notify that the audio category has changed.
     */
    public void onAudioDeviceCategoryChanged() {
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
            case BluetoothProfile.LE_AUDIO:
                return mIsActiveDeviceLeAudio;
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

        List<ParcelUuid> uuidsList = mLocalAdapter.getUuidsList();
        ParcelUuid[] localUuids = new ParcelUuid[uuidsList.size()];
        uuidsList.toArray(localUuids);

        /*
         * Now we know if the device supports PBAP, update permissions...
         */
        processPhonebookAccess();

        synchronized (mProfileLock) {
            mProfileManager.updateProfiles(uuids, localUuids, mProfiles, mRemovedProfiles,
                    mLocalNapRoleConnected, mDevice);
        }

        if (BluetoothUtils.D) {
            Log.d(TAG, "updating profiles for " + mDevice.getAnonymizedAddress());
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
        LeAudioProfile leAudio = mProfileManager.getLeAudioProfile();
        if (leAudio != null) {
            mIsActiveDeviceLeAudio = leAudio.getActiveDevices().contains(mDevice);
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
        } else if (ArrayUtils.contains(uuids, BluetoothUuid.LE_AUDIO)) {
            timeout = MAX_LEAUDIO_DELAY_FOR_AUTO_CONNECT;
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
            Log.d(TAG, "onUuidChanged: triggering connectDevice");
            connectDevice();
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

            mBondTimestamp = null;
        }

        refresh();

        if (bondState == BluetoothDevice.BOND_BONDED) {
            mBondTimestamp = new Timestamp(System.currentTimeMillis());

            if (mDevice.isBondingInitiatedLocally()) {
                connect();
            }

            // Saves this device as just bonded and checks if it's an hearing device after
            // profiles are connected. This is for judging whether to display the survey.
            HearingAidStatsLogUtils.addToJustBonded(getAddress());
        }
    }

    public Timestamp getBondTimestamp() {
        return mBondTimestamp;
    }

    public BluetoothClass getBtClass() {
        return mDevice.getBluetoothClass();
    }

    /**
     * Returns a list of {@link LocalBluetoothProfile} supported by the device.
     */
    public List<LocalBluetoothProfile> getProfiles() {
        return new ArrayList<>(mProfiles);
    }

    /**
     * Returns a list of {@link LocalBluetoothProfile} that are user-accessible from UI to
     * initiate a connection.
     *
     * Note: Use {@link #getProfiles()} to retrieve all supported profiles on the device.
     */
    public List<LocalBluetoothProfile> getUiAccessibleProfiles() {
        List<LocalBluetoothProfile> accessibleProfiles = new ArrayList<>();
        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : mProfiles) {
                if (profile.accessProfileEnabled()) {
                    accessibleProfiles.add(profile);
                }
            }
        }
        return accessibleProfiles;
    }

    public List<LocalBluetoothProfile> getRemovedProfiles() {
        return new ArrayList<>(mRemovedProfiles);
    }

    /**
     * @deprecated Use {@link #registerCallback(Executor, Callback)}.
     */
    @Deprecated
    public void registerCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Registers a {@link Callback} that will be invoked when the bluetooth device attribute is
     * changed.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link Callback}
     */
    public void registerCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull Callback callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        mCallbackExecutorMap.put(callback, executor);
    }

    public void unregisterCallback(Callback callback) {
        mCallbacks.remove(callback);
        mCallbackExecutorMap.remove(callback);
    }

    void dispatchAttributesChanged() {
        for (Callback callback : mCallbacks) {
            callback.onDeviceAttributesChanged();
        }
        mCallbackExecutorMap.forEach((callback, executor) ->
                executor.execute(callback::onDeviceAttributesChanged));
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("CachedBluetoothDevice{");
        builder.append("anonymizedAddress=").append(mDevice.getAnonymizedAddress());
        builder.append(", name=").append(getName());
        builder.append(", groupId=").append(mGroupId);
        builder.append(", member=").append(mMemberDevices);
        if (isHearingAidDevice()) {
            builder.append(", hearingAidInfo=").append(mHearingAidInfo);
            builder.append(", subDevice=").append(mSubDevice);
        }
        builder.append("}");
        return builder.toString();
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
            mDevice.getPhonebookAccessPermission();
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
     * Return summary that describes connection state of this device. Summary depends on: 1. Whether
     * device has battery info 2. Whether device is in active usage(or in phone call) 3. Whether
     * device is in audio sharing process
     *
     * @param shortSummary {@code true} if need to return short version summary
     */
    public String getConnectionSummary(boolean shortSummary) {
        CharSequence summary = null;
        if (BluetoothUtils.isAudioSharingEnabled()) {
            if (mBluetoothManager == null) {
                mBluetoothManager = LocalBluetoothManager.getInstance(mContext, null);
            }
            if (BluetoothUtils.isBroadcasting(mBluetoothManager)) {
                summary = getBroadcastConnectionSummary(shortSummary);
            }
        }
        if (summary == null) {
            summary =
                    getConnectionSummary(
                            shortSummary,
                            false /* isTvSummary */,
                            SUMMARY_NO_COLOR_FOR_LOW_BATTERY);
        }
        return summary != null ? summary.toString() : null;
    }

    /**
     * Returns the connection summary of this device during le audio sharing.
     *
     * @param shortSummary {@code true} if need to return short version summary
     */
    @Nullable
    private String getBroadcastConnectionSummary(boolean shortSummary) {
        if (isProfileConnectedFail() && isConnected()) {
            return mContext.getString(R.string.profile_connect_timeout_subtext);
        }

        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : getProfiles()) {
                int connectionStatus = getProfileConnectionState(profile);
                if (connectionStatus == BluetoothProfile.STATE_CONNECTING
                        || connectionStatus == BluetoothProfile.STATE_DISCONNECTING) {
                    return mContext.getString(
                            BluetoothUtils.getConnectionStateSummary(connectionStatus));
                }
            }
        }

        int leftBattery =
                BluetoothUtils.getIntMetaData(
                        mDevice, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY);
        int rightBattery =
                BluetoothUtils.getIntMetaData(
                        mDevice, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY);
        String batteryLevelPercentageString = getValidMinBatteryLevelWithMemberDevices();

        if (mBluetoothManager == null) {
            mBluetoothManager = LocalBluetoothManager.getInstance(mContext, null);
        }
        if (BluetoothUtils.hasConnectedBroadcastSource(this, mBluetoothManager)) {
            // Gets summary for the buds which are in the audio sharing.
            int groupId = BluetoothUtils.getGroupId(this);
            int primaryGroupId = BluetoothUtils.getPrimaryGroupIdForBroadcast(
                    mContext.getContentResolver());
            if ((primaryGroupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID)
                    ? (groupId == primaryGroupId) : isActiveDevice(BluetoothProfile.LE_AUDIO)) {
                // The buds are primary buds
                return getSummaryWithBatteryInfo(
                        R.string.bluetooth_active_battery_level_untethered,
                        R.string.bluetooth_active_battery_level,
                        R.string.bluetooth_active_no_battery_level,
                        leftBattery,
                        rightBattery,
                        batteryLevelPercentageString,
                        shortSummary);
            } else {
                // The buds are not primary buds
                return getSummaryWithBatteryInfo(
                        R.string.bluetooth_active_media_only_battery_level_untethered,
                        R.string.bluetooth_active_media_only_battery_level,
                        R.string.bluetooth_active_media_only_no_battery_level,
                        leftBattery,
                        rightBattery,
                        batteryLevelPercentageString,
                        shortSummary);
            }
        } else {
            // Gets summary for the buds which are not in the audio sharing.
            if (getProfiles().stream()
                    .anyMatch(
                            profile ->
                                    profile instanceof LeAudioProfile
                                            && profile.isEnabled(getDevice()))) {
                // The buds support le audio.
                if (isConnected()) {
                    return getSummaryWithBatteryInfo(
                            R.string.bluetooth_battery_level_untethered_lea_support,
                            R.string.bluetooth_battery_level_lea_support,
                            R.string.bluetooth_no_battery_level_lea_support,
                            leftBattery,
                            rightBattery,
                            batteryLevelPercentageString,
                            shortSummary);
                } else {
                    return mContext.getString(R.string.bluetooth_saved_device_lea_support);
                }
            }
        }
        return null;
    }

    /**
     * Returns the summary with correct format depending the battery info.
     *
     * @param untetheredBatteryResId resource id for untethered device with battery info
     * @param batteryResId resource id for device with single battery info
     * @param noBatteryResId resource id for device with no battery info
     * @param shortSummary {@code true} if need to return short version summary
     */
    private String getSummaryWithBatteryInfo(
            @StringRes int untetheredBatteryResId,
            @StringRes int batteryResId,
            @StringRes int noBatteryResId,
            int leftBattery,
            int rightBattery,
            String batteryLevelPercentageString,
            boolean shortSummary) {
        if (isTwsBatteryAvailable(leftBattery, rightBattery) && !shortSummary) {
            return mContext.getString(
                    untetheredBatteryResId,
                    Utils.formatPercentage(leftBattery),
                    Utils.formatPercentage(rightBattery));
        } else if (batteryLevelPercentageString != null && !shortSummary) {
            return mContext.getString(batteryResId, batteryLevelPercentageString);
        } else {
            return mContext.getString(noBatteryResId);
        }
    }

    /**
     * Returns android tv string that describes the connection state of this device.
     */
    public CharSequence getTvConnectionSummary() {
        return getTvConnectionSummary(SUMMARY_NO_COLOR_FOR_LOW_BATTERY);
    }

    /**
     * Returns android tv string that describes the connection state of this device, with low
     * battery states highlighted in color.
     *
     * @param lowBatteryColorRes - resource id for the color that should be used for the part of the
     *                           CharSequence that contains low battery information.
     */
    public CharSequence getTvConnectionSummary(int lowBatteryColorRes) {
        return getConnectionSummary(false /* shortSummary */, true /* isTvSummary */,
                lowBatteryColorRes);
    }

    /**
     * Return summary that describes connection state of this device. Summary depends on:
     * 1. Whether device has battery info
     * 2. Whether device is in active usage(or in phone call)
     *
     * @param shortSummary       {@code true} if need to return short version summary
     * @param isTvSummary        {@code true} if the summary should be TV specific
     * @param lowBatteryColorRes Resource id of the color to be used for low battery strings. Use
     *                           {@link SUMMARY_NO_COLOR_FOR_LOW_BATTERY} if no separate color
     *                           should be used.
     */
    private CharSequence getConnectionSummary(boolean shortSummary, boolean isTvSummary,
            int lowBatteryColorRes) {
        boolean profileConnected = false;    // Updated as long as BluetoothProfile is connected
        boolean a2dpConnected = true;        // A2DP is connected
        boolean hfpConnected = true;         // HFP is connected
        boolean hearingAidConnected = true;  // Hearing Aid is connected
        boolean leAudioConnected = true;        // LeAudio is connected
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
                            } else if (profile instanceof LeAudioProfile) {
                                leAudioConnected = false;
                            }
                        }
                        break;
                }
            }
        }

        String batteryLevelPercentageString = getValidMinBatteryLevelWithMemberDevices();
        int stringRes = R.string.bluetooth_pairing;
        //when profile is connected, information would be available
        if (profileConnected) {
            leftBattery = getLeftBatteryLevel();
            rightBattery = getRightBatteryLevel();

            // Set default string with battery level in device connected situation.
            if (isTwsBatteryAvailable(leftBattery, rightBattery)) {
                stringRes = R.string.bluetooth_battery_level_untethered;
            } else if (batteryLevelPercentageString != null && !shortSummary) {
                stringRes = R.string.bluetooth_battery_level;
            }

            // Set active string in following device connected situation, also show battery
            // information if they have.
            //    1. Hearing Aid device active.
            //    2. Headset device active with in-calling state.
            //    3. A2DP device active without in-calling state.
            //    4. Le Audio device active
            if (a2dpConnected || hfpConnected || hearingAidConnected || leAudioConnected) {
                final boolean isOnCall = Utils.isAudioModeOngoingCall(mContext);
                if ((mIsActiveDeviceHearingAid)
                        || (mIsActiveDeviceHeadset && isOnCall)
                        || (mIsActiveDeviceA2dp && !isOnCall)
                        || mIsActiveDeviceLeAudio) {
                    if (isTwsBatteryAvailable(leftBattery, rightBattery) && !shortSummary) {
                        stringRes = R.string.bluetooth_active_battery_level_untethered;
                    } else if (batteryLevelPercentageString != null && !shortSummary) {
                        stringRes = R.string.bluetooth_active_battery_level;
                    } else {
                        stringRes = R.string.bluetooth_active_no_battery_level;
                    }
                }

                // Try to show left/right information for hearing
                // aids specifically.
                boolean isActiveAshaHearingAid = mIsActiveDeviceHearingAid;
                boolean isActiveLeAudioHearingAid = mIsActiveDeviceLeAudio
                        && isConnectedHapClientDevice();
                if (isActiveAshaHearingAid || isActiveLeAudioHearingAid) {
                    stringRes = getHearingDeviceSummaryRes(leftBattery, rightBattery, shortSummary);
                }
            }
        }

        if (stringRes == R.string.bluetooth_pairing
                && getBondState() != BluetoothDevice.BOND_BONDING) {
            return null;
        }

        boolean summaryIncludesBatteryLevel = stringRes == R.string.bluetooth_battery_level
                || stringRes == R.string.bluetooth_active_battery_level
                || stringRes == R.string.bluetooth_active_battery_level_untethered
                || stringRes == R.string.bluetooth_active_battery_level_untethered_left
                || stringRes == R.string.bluetooth_active_battery_level_untethered_right
                || stringRes == R.string.bluetooth_battery_level_untethered;
        if (isTvSummary && summaryIncludesBatteryLevel && Flags.enableTvMediaOutputDialog()) {
            return getTvBatterySummary(
                    getMinBatteryLevelWithMemberDevices(),
                    leftBattery,
                    rightBattery,
                    lowBatteryColorRes);
        }

        if (isTwsBatteryAvailable(leftBattery, rightBattery)) {
            return mContext.getString(stringRes, Utils.formatPercentage(leftBattery),
                    Utils.formatPercentage(rightBattery));
        } else if (leftBattery > BluetoothDevice.BATTERY_LEVEL_UNKNOWN
                && !BluetoothUtils.getBooleanMetaData(mDevice,
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            return mContext.getString(stringRes, Utils.formatPercentage(leftBattery));
        } else if (rightBattery > BluetoothDevice.BATTERY_LEVEL_UNKNOWN
                && !BluetoothUtils.getBooleanMetaData(mDevice,
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            return mContext.getString(stringRes, Utils.formatPercentage(rightBattery));
        } else {
            return mContext.getString(stringRes, batteryLevelPercentageString);
        }
    }

    private CharSequence getTvBatterySummary(int mainBattery, int leftBattery, int rightBattery,
            int lowBatteryColorRes) {
        // Since there doesn't seem to be a way to use format strings to add the
        // percentages and also mark which part of the string is left and right to color
        // them, we are using one string resource per battery.
        Resources res = mContext.getResources();
        SpannableStringBuilder spannableBuilder = new SpannableStringBuilder();
        if (leftBattery >= 0 || rightBattery >= 0) {
            // Not switching the left and right for RTL to keep the left earbud always on
            // the left.
            if (leftBattery >= 0) {
                String left = res.getString(
                        R.string.tv_bluetooth_battery_level_untethered_left,
                        Utils.formatPercentage(leftBattery));
                addBatterySpan(spannableBuilder, left, isBatteryLow(leftBattery,
                                BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD),
                        lowBatteryColorRes);
            }
            if (rightBattery >= 0) {
                if (spannableBuilder.length() > 0) {
                    spannableBuilder.append(" ");
                }
                String right = res.getString(
                        R.string.tv_bluetooth_battery_level_untethered_right,
                        Utils.formatPercentage(rightBattery));
                addBatterySpan(spannableBuilder, right, isBatteryLow(rightBattery,
                                BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD),
                        lowBatteryColorRes);
            }
        } else {
            addBatterySpan(spannableBuilder, res.getString(R.string.tv_bluetooth_battery_level,
                            Utils.formatPercentage(mainBattery)),
                    isBatteryLow(mainBattery, BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD),
                    lowBatteryColorRes);
        }
        return spannableBuilder;
    }

    private int getHearingDeviceSummaryRes(int leftBattery, int rightBattery,
            boolean shortSummary) {
        boolean isLeftDeviceConnected = getConnectedHearingAidSide(
                HearingAidInfo.DeviceSide.SIDE_LEFT).isPresent();
        boolean isRightDeviceConnected = getConnectedHearingAidSide(
                HearingAidInfo.DeviceSide.SIDE_RIGHT).isPresent();
        boolean shouldShowLeftBattery =
                !shortSummary && (leftBattery > BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        boolean shouldShowRightBattery =
                !shortSummary && (rightBattery > BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        if (isLeftDeviceConnected && isRightDeviceConnected) {
            return (shouldShowLeftBattery && shouldShowRightBattery)
                    ? R.string.bluetooth_active_battery_level_untethered
                    : R.string.bluetooth_hearing_aid_left_and_right_active;
        }
        if (isLeftDeviceConnected) {
            return shouldShowLeftBattery
                    ? R.string.bluetooth_active_battery_level_untethered_left
                    : R.string.bluetooth_hearing_aid_left_active;
        }
        if (isRightDeviceConnected) {
            return shouldShowRightBattery
                    ? R.string.bluetooth_active_battery_level_untethered_right
                    : R.string.bluetooth_hearing_aid_right_active;
        }

        return R.string.bluetooth_active_no_battery_level;
    }

    private void addBatterySpan(SpannableStringBuilder builder,
            String batteryString, boolean lowBattery, int lowBatteryColorRes) {
        if (lowBattery && lowBatteryColorRes != SUMMARY_NO_COLOR_FOR_LOW_BATTERY) {
            builder.append(batteryString,
                    new ForegroundColorSpan(mContext.getResources().getColor(lowBatteryColorRes)),
                    0 /* flags */);
        } else {
            builder.append(batteryString);
        }
    }

    private boolean isBatteryLow(int batteryLevel, int metadataKey) {
        int lowBatteryThreshold = BluetoothUtils.getIntMetaData(mDevice, metadataKey);
        if (lowBatteryThreshold <= 0) {
            lowBatteryThreshold = DEFAULT_LOW_BATTERY_THRESHOLD;
        }
        return batteryLevel <= lowBatteryThreshold;
    }

    private boolean isTwsBatteryAvailable(int leftBattery, int rightBattery) {
        return leftBattery >= 0 && rightBattery >= 0;
    }

    private Optional<CachedBluetoothDevice> getConnectedHearingAidSide(
            @HearingAidInfo.DeviceSide int side) {
        return Stream.concat(Stream.of(this, mSubDevice), mMemberDevices.stream())
                .filter(Objects::nonNull)
                .filter(device -> device.getDeviceSide() == side
                        || device.getDeviceSide() == HearingAidInfo.DeviceSide.SIDE_LEFT_AND_RIGHT)
                .filter(device -> device.getDevice().isConnected())
                // For hearing aids, we should expect only one device assign to one side, but if
                // it happens, we don't care which one.
                .findAny();
    }

    private int getLeftBatteryLevel() {
        int leftBattery = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        if (BluetoothUtils.getBooleanMetaData(mDevice,
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            leftBattery = BluetoothUtils.getIntMetaData(mDevice,
                    BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY);
        }

        // Retrieve hearing aids (ASHA, HAP) individual side battery level
        if (leftBattery == BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
            leftBattery = getConnectedHearingAidSide(HearingAidInfo.DeviceSide.SIDE_LEFT)
                    .map(CachedBluetoothDevice::getBatteryLevel)
                    .filter(batteryLevel -> batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN)
                    .orElse(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        }

        return leftBattery;
    }

    private int getRightBatteryLevel() {
        int rightBattery = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
        if (BluetoothUtils.getBooleanMetaData(
                mDevice, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            rightBattery = BluetoothUtils.getIntMetaData(mDevice,
                    BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY);
        }

        // Retrieve hearing aids (ASHA, HAP) individual side battery level
        if (rightBattery == BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
            rightBattery = getConnectedHearingAidSide(HearingAidInfo.DeviceSide.SIDE_RIGHT)
                    .map(CachedBluetoothDevice::getBatteryLevel)
                    .filter(batteryLevel -> batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN)
                    .orElse(BluetoothDevice.BATTERY_LEVEL_UNKNOWN);
        }

        return rightBattery;
    }

    private boolean isProfileConnectedFail() {
        Log.d(TAG, "anonymizedAddress=" + mDevice.getAnonymizedAddress()
                + " mIsA2dpProfileConnectedFail=" + mIsA2dpProfileConnectedFail
                + " mIsHearingAidProfileConnectedFail=" + mIsHearingAidProfileConnectedFail
                + " mIsLeAudioProfileConnectedFail=" + mIsLeAudioProfileConnectedFail
                + " mIsHeadsetProfileConnectedFail=" + mIsHeadsetProfileConnectedFail
                + " isConnectedSapDevice()=" + isConnectedSapDevice());

        return mIsA2dpProfileConnectedFail || mIsHearingAidProfileConnectedFail
                || (!isConnectedSapDevice() && mIsHeadsetProfileConnectedFail)
                || mIsLeAudioProfileConnectedFail;
    }

    /**
     * See {@link #getCarConnectionSummary(boolean, boolean)}
     */
    public String getCarConnectionSummary() {
        return getCarConnectionSummary(false /* shortSummary */);
    }

    /**
     * See {@link #getCarConnectionSummary(boolean, boolean)}
     */
    public String getCarConnectionSummary(boolean shortSummary) {
        return getCarConnectionSummary(shortSummary, true /* useDisconnectedString */);
    }

    /**
     * Returns android auto string that describes the connection state of this device.
     *
     * @param shortSummary {@code true} if need to return short version summary
     * @param useDisconnectedString {@code true} if need to return disconnected summary string
     */
    public String getCarConnectionSummary(boolean shortSummary, boolean useDisconnectedString) {
        boolean profileConnected = false;       // at least one profile is connected
        boolean a2dpNotConnected = false;       // A2DP is preferred but not connected
        boolean hfpNotConnected = false;        // HFP is preferred but not connected
        boolean hearingAidNotConnected = false; // Hearing Aid is preferred but not connected
        boolean leAudioNotConnected = false;       // LeAudio is preferred but not connected

        synchronized (mProfileLock) {
            for (LocalBluetoothProfile profile : getProfiles()) {
                int connectionStatus = getProfileConnectionState(profile);

                switch (connectionStatus) {
                    case BluetoothProfile.STATE_CONNECTING:
                    case BluetoothProfile.STATE_DISCONNECTING:
                        return mContext.getString(
                                BluetoothUtils.getConnectionStateSummary(connectionStatus));

                    case BluetoothProfile.STATE_CONNECTED:
                        if (shortSummary) {
                            return mContext.getString(BluetoothUtils.getConnectionStateSummary(
                                    connectionStatus), /* formatArgs= */ "");
                        }
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
                            } else if (profile instanceof  LeAudioProfile) {
                                leAudioNotConnected = true;
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
        final int batteryLevel = getMinBatteryLevelWithMemberDevices();
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

        if (!leAudioNotConnected && mIsActiveDeviceLeAudio) {
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

        if (getBondState() == BluetoothDevice.BOND_BONDING) {
            return mContext.getString(R.string.bluetooth_pairing);
        }
        return useDisconnectedString ? mContext.getString(R.string.bluetooth_disconnected) : null;
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
     * @return {@code true} if {@code cachedBluetoothDevice} is ASHA hearing aid device
     */
    public boolean isConnectedAshaHearingAidDevice() {
        HearingAidProfile hearingAidProfile = mProfileManager.getHearingAidProfile();
        return hearingAidProfile != null && hearingAidProfile.getConnectionStatus(mDevice) ==
                BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is HAP device
     */
    public boolean isConnectedHapClientDevice() {
        HapClientProfile hapClientProfile = mProfileManager.getHapClientProfile();
        return hapClientProfile != null && hapClientProfile.getConnectionStatus(mDevice)
                == BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is LeAudio hearing aid device
     */
    public boolean isConnectedLeAudioHearingAidDevice() {
        return isConnectedHapClientDevice() && isConnectedLeAudioDevice();
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is hearing aid device
     *
     * The device may be an ASHA hearing aid that supports {@link HearingAidProfile} or a LeAudio
     * hearing aid that supports {@link HapClientProfile} and {@link LeAudioProfile}.
     */
    public boolean isConnectedHearingAidDevice() {
        return isConnectedAshaHearingAidDevice() || isConnectedLeAudioHearingAidDevice();
    }

    /**
     * @return {@code true} if {@code cachedBluetoothDevice} is LeAudio device
     */
    public boolean isConnectedLeAudioDevice() {
        LeAudioProfile leAudio = mProfileManager.getLeAudioProfile();
        return leAudio != null && leAudio.getConnectionStatus(mDevice) ==
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
        final short tmpRssi = mRssi;
        final boolean tmpJustDiscovered = mJustDiscovered;
        final HearingAidInfo tmpHearingAidInfo = mHearingAidInfo;
        // Set main device from sub device
        release();
        mDevice = mSubDevice.mDevice;
        mRssi = mSubDevice.mRssi;
        mJustDiscovered = mSubDevice.mJustDiscovered;
        mHearingAidInfo = mSubDevice.mHearingAidInfo;
        // Set sub device from backup
        mSubDevice.release();
        mSubDevice.mDevice = tmpDevice;
        mSubDevice.mRssi = tmpRssi;
        mSubDevice.mJustDiscovered = tmpJustDiscovered;
        mSubDevice.mHearingAidInfo = tmpHearingAidInfo;
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
    public void addMemberDevice(CachedBluetoothDevice memberDevice) {
        Log.d(TAG, this + " addMemberDevice = " + memberDevice);
        mMemberDevices.add(memberDevice);
    }

    /**
     * Remove a device from the member device sets.
     */
    public void removeMemberDevice(CachedBluetoothDevice memberDevice) {
        memberDevice.release();
        mMemberDevices.remove(memberDevice);
    }

    /**
     * In order to show the preference for the whole group, we always set the main device as the
     * first connected device in the coordinated set, and then switch the content of the main
     * device and member devices.
     *
     * @param newMainDevice the new Main device which is from the previous main device's member
     *                      list.
     */
    public void switchMemberDeviceContent(CachedBluetoothDevice newMainDevice) {
        // Remove the sub device from mMemberDevices first to prevent hash mismatch problem due
        // to mDevice switch
        removeMemberDevice(newMainDevice);

        // Backup from current main device
        final BluetoothDevice tmpDevice = mDevice;
        final short tmpRssi = mRssi;
        final boolean tmpJustDiscovered = mJustDiscovered;
        final HearingAidInfo tmpHearingAidInfo = mHearingAidInfo;

        // Set main device from sub device
        release();
        mDevice = newMainDevice.mDevice;
        mRssi = newMainDevice.mRssi;
        mJustDiscovered = newMainDevice.mJustDiscovered;
        mHearingAidInfo = newMainDevice.mHearingAidInfo;
        fillData();

        // Set sub device from backup
        newMainDevice.release();
        newMainDevice.mDevice = tmpDevice;
        newMainDevice.mRssi = tmpRssi;
        newMainDevice.mJustDiscovered = tmpJustDiscovered;
        newMainDevice.mHearingAidInfo = tmpHearingAidInfo;
        newMainDevice.fillData();

        // Add the sub device back into mMemberDevices with correct hash
        addMemberDevice(newMainDevice);
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

        return BluetoothUtils.getBtRainbowDrawableWithDescription(mContext, this);
    }

    void releaseLruCache() {
        mDrawableCache.evictAll();
    }

    boolean getUnpairing() {
        return mUnpairing;
    }

    @VisibleForTesting
    void setLocalBluetoothManager(LocalBluetoothManager bluetoothManager) {
        mBluetoothManager = bluetoothManager;
    }
}
