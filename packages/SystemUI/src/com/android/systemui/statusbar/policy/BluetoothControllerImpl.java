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

package com.android.systemui.statusbar.policy;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.GuardedBy;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.bluetooth.BluetoothLogger;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.bluetooth.BluetoothRepository;
import com.android.systemui.statusbar.policy.bluetooth.ConnectionStatusModel;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controller for information about bluetooth connections.
 *
 * Note: Right now, this class and {@link BluetoothRepository} co-exist. Any new code should go in
 * {@link BluetoothRepository}, but external clients should query this file for now.
 */
@SysUISingleton
public class BluetoothControllerImpl implements BluetoothController, BluetoothCallback,
        CachedBluetoothDevice.Callback, LocalBluetoothProfileManager.ServiceListener {
    private static final String TAG = "BluetoothController";

    private final DumpManager mDumpManager;
    private final BluetoothLogger mLogger;
    private final BluetoothRepository mBluetoothRepository;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final UserManager mUserManager;
    private final int mCurrentUser;
    @GuardedBy("mConnectedDevices")
    private final List<CachedBluetoothDevice> mConnectedDevices = new ArrayList<>();

    private boolean mEnabled;
    @ConnectionState
    private int mConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
    private boolean mAudioProfileOnly;
    private boolean mIsActive;

    private final H mHandler;
    private int mState;

    private final BluetoothAdapter mAdapter;

    private final Executor mBackgroundExecutor;
    /**
     */
    @Inject
    public BluetoothControllerImpl(
            Context context,
            UserTracker userTracker,
            DumpManager dumpManager,
            BluetoothLogger logger,
            BluetoothRepository bluetoothRepository,
            @Background Executor executor,
            @Main Looper mainLooper,
            @Nullable LocalBluetoothManager localBluetoothManager,
            @Nullable BluetoothAdapter bluetoothAdapter) {
        mDumpManager = dumpManager;
        mLogger = logger;
        mBluetoothRepository = bluetoothRepository;
        mLocalBluetoothManager = localBluetoothManager;
        mHandler = new H(mainLooper);
        mBackgroundExecutor = executor;
        if (mLocalBluetoothManager != null) {
            mLocalBluetoothManager.getEventManager().registerCallback(this);
            mLocalBluetoothManager.getProfileManager().addServiceListener(this);
            onBluetoothStateChanged(
                    mLocalBluetoothManager.getBluetoothAdapter().getBluetoothState());
        }
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mCurrentUser = userTracker.getUserId();
        mDumpManager.registerDumpable(TAG, this);
        mAdapter = bluetoothAdapter;
    }

    @Override
    public boolean canConfigBluetooth() {
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH,
                UserHandle.of(mCurrentUser))
            && !mUserManager.hasUserRestriction(UserManager.DISALLOW_BLUETOOTH,
                UserHandle.of(mCurrentUser));
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("BluetoothController state:");
        pw.print("  mLocalBluetoothManager="); pw.println(mLocalBluetoothManager);
        if (mLocalBluetoothManager == null) {
            return;
        }
        pw.print("  mEnabled="); pw.println(mEnabled);
        pw.print("  mConnectionState="); pw.println(connectionStateToString(mConnectionState));
        pw.print("  mAudioProfileOnly="); pw.println(mAudioProfileOnly);
        pw.print("  mIsActive="); pw.println(mIsActive);
        pw.print("  mConnectedDevices="); pw.println(getConnectedDevices());
        pw.print("  mCallbacks.size="); pw.println(mHandler.mCallbacks.size());
        pw.println("  Bluetooth Devices:");
        for (CachedBluetoothDevice device : getDevices()) {
            pw.println("    " + getDeviceString(device));
        }
    }

    private static String connectionStateToString(@ConnectionState int state) {
        switch (state) {
            case BluetoothAdapter.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothAdapter.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothAdapter.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothAdapter.STATE_DISCONNECTING:
                return "DISCONNECTING";
        }
        return "UNKNOWN(" + state + ")";
    }

    private String getDeviceString(CachedBluetoothDevice device) {
        return device.getName()
                + " profiles=" + getDeviceProfilesString(device)
                + " connected=" + device.isConnected()
                + " active[A2DP]=" + device.isActiveDevice(BluetoothProfile.A2DP)
                + " active[HEADSET]=" + device.isActiveDevice(BluetoothProfile.HEADSET)
                + " active[HEARING_AID]=" + device.isActiveDevice(BluetoothProfile.HEARING_AID)
                + " active[LE_AUDIO]=" + device.isActiveDevice(BluetoothProfile.LE_AUDIO);
    }

    private String getDeviceProfilesString(CachedBluetoothDevice device) {
        List<String> profileIds = new ArrayList<>();
        for (LocalBluetoothProfile profile : device.getProfiles()) {
            profileIds.add(String.valueOf(profile.getProfileId()));
        }
        return "[" + String.join(",", profileIds) + "]";
    }

    @Override
    public List<CachedBluetoothDevice> getConnectedDevices() {
        List<CachedBluetoothDevice> out;
        synchronized (mConnectedDevices) {
            out = new ArrayList<>(mConnectedDevices);
        }
        return out;
    }

    @Override
    public void addCallback(@NonNull Callback cb) {
        mHandler.obtainMessage(H.MSG_ADD_CALLBACK, cb).sendToTarget();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void removeCallback(@NonNull Callback cb) {
        mHandler.obtainMessage(H.MSG_REMOVE_CALLBACK, cb).sendToTarget();
    }

    @Override
    public boolean isBluetoothEnabled() {
        return mEnabled;
    }

    @Override
    public int getBluetoothState() {
        return mState;
    }

    @Override
    public boolean isBluetoothConnected() {
        return mConnectionState == BluetoothAdapter.STATE_CONNECTED;
    }

    @Override
    public boolean isBluetoothConnecting() {
        return mConnectionState == BluetoothAdapter.STATE_CONNECTING;
    }

    @Override
    public boolean isBluetoothAudioProfileOnly() {
        return mAudioProfileOnly;
    }

    @Override
    public boolean isBluetoothAudioActive() {
        return mIsActive;
    }

    @WorkerThread
    @Override
    public void setBluetoothEnabled(boolean enabled) {
        if (mLocalBluetoothManager != null) {
            mLocalBluetoothManager.getBluetoothAdapter().setBluetoothEnabled(enabled);
        }
    }

    @Override
    public boolean isBluetoothSupported() {
        return mLocalBluetoothManager != null;
    }

    @WorkerThread
    @Override
    public String getConnectedDeviceName() {
        synchronized (mConnectedDevices) {
            if (mConnectedDevices.size() == 1) {
                return mConnectedDevices.get(0).getName();
            }
        }
        return null;
    }

    private Collection<CachedBluetoothDevice> getDevices() {
        return mLocalBluetoothManager != null
                ? mLocalBluetoothManager.getCachedDeviceManager().getCachedDevicesCopy()
                : Collections.emptyList();
    }

    private void updateConnected() {
        mBluetoothRepository.fetchConnectionStatusInBackground(
                getDevices(), this::onConnectionStatusFetched);
    }

    // Careful! This may be invoked in the main thread.
    private void onConnectionStatusFetched(ConnectionStatusModel status) {
        List<CachedBluetoothDevice> newList = status.getConnectedDevices();
        int state = status.getMaxConnectionState();
        synchronized (mConnectedDevices) {
            mConnectedDevices.clear();
            mConnectedDevices.addAll(newList);
        }
        if (state != mConnectionState) {
            mConnectionState = state;
            mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
        }
        updateAudioProfile();
    }

    private void updateActive() {
        boolean isActive = false;

        for (CachedBluetoothDevice device : getDevices()) {
            isActive |= device.isActiveDevice(BluetoothProfile.HEADSET)
                    || device.isActiveDevice(BluetoothProfile.A2DP)
                    || device.isActiveDevice(BluetoothProfile.HEARING_AID)
                    || device.isActiveDevice(BluetoothProfile.LE_AUDIO);
        }

        if (mIsActive != isActive) {
            mIsActive = isActive;
            mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
        }
    }

    private void updateAudioProfile() {
        // We want this in the background as calls inside `LocalBluetoothProfile` end up being
        // binder calls
        mBackgroundExecutor.execute(() -> {
            boolean audioProfileConnected = false;
            boolean otherProfileConnected = false;

            for (CachedBluetoothDevice device : getDevices()) {
                for (LocalBluetoothProfile profile : device.getProfiles()) {
                    int profileId = profile.getProfileId();
                    boolean isConnected = device.isConnectedProfile(profile);
                    if (profileId == BluetoothProfile.HEADSET
                            || profileId == BluetoothProfile.A2DP
                            || profileId == BluetoothProfile.HEARING_AID
                            || profileId == BluetoothProfile.LE_AUDIO) {
                        audioProfileConnected |= isConnected;
                    } else {
                        otherProfileConnected |= isConnected;
                    }
                }
            }

            boolean audioProfileOnly = (audioProfileConnected && !otherProfileConnected);
            if (audioProfileOnly != mAudioProfileOnly) {
                mAudioProfileOnly = audioProfileOnly;
                mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
            }
        });
    }

    @Override
    public void onBluetoothStateChanged(@AdapterState int bluetoothState) {
        mLogger.logStateChange(BluetoothAdapter.nameForState(bluetoothState));
        mEnabled = bluetoothState == BluetoothAdapter.STATE_ON
                || bluetoothState == BluetoothAdapter.STATE_TURNING_ON;
        mState = bluetoothState;
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onDeviceAdded(@NonNull CachedBluetoothDevice cachedDevice) {
        mLogger.logDeviceAdded(cachedDevice.getAddress());
        cachedDevice.registerCallback(this);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onDeviceDeleted(@NonNull CachedBluetoothDevice cachedDevice) {
        mLogger.logDeviceDeleted(cachedDevice.getAddress());
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onDeviceBondStateChanged(
            @NonNull CachedBluetoothDevice cachedDevice, int bondState) {
        mLogger.logBondStateChange(cachedDevice.getAddress(), bondState);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onDeviceAttributesChanged() {
        mLogger.logDeviceAttributesChanged();
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onConnectionStateChanged(
            @Nullable CachedBluetoothDevice cachedDevice,
            @ConnectionState int state) {
        mLogger.logDeviceConnectionStateChanged(
                getAddressOrNull(cachedDevice), connectionStateToString(state));
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onProfileConnectionStateChanged(
            @NonNull CachedBluetoothDevice cachedDevice,
            @ConnectionState int state,
            int bluetoothProfile) {
        mLogger.logProfileConnectionStateChanged(
                cachedDevice.getAddress(), connectionStateToString(state), bluetoothProfile);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onActiveDeviceChanged(
            @Nullable CachedBluetoothDevice activeDevice, int bluetoothProfile) {
        mLogger.logActiveDeviceChanged(getAddressOrNull(activeDevice), bluetoothProfile);
        updateActive();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onAclConnectionStateChanged(
            @NonNull CachedBluetoothDevice cachedDevice, int state) {
        mLogger.logAclConnectionStateChanged(
                cachedDevice.getAddress(), connectionStateToString(state));
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    public void addOnMetadataChangedListener(
            @NonNull CachedBluetoothDevice cachedDevice,
            Executor executor,
            BluetoothAdapter.OnMetadataChangedListener listener
    ) {
        if (mAdapter == null) return;
        mAdapter.addOnMetadataChangedListener(
                cachedDevice.getDevice(),
                executor,
                listener
        );
    }

    public void removeOnMetadataChangedListener(
            @NonNull CachedBluetoothDevice cachedDevice,
            BluetoothAdapter.OnMetadataChangedListener listener
    ) {
        if (mAdapter == null) return;
        mAdapter.removeOnMetadataChangedListener(
                cachedDevice.getDevice(),
                listener
        );
    }

    @Nullable
    private String getAddressOrNull(@Nullable CachedBluetoothDevice device) {
        return device == null ? null : device.getAddress();
    }

    @Override
    public void onServiceConnected() {
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onServiceDisconnected() {}

    private final class H extends Handler {
        private final ArrayList<BluetoothController.Callback> mCallbacks = new ArrayList<>();

        private static final int MSG_PAIRED_DEVICES_CHANGED = 1;
        private static final int MSG_STATE_CHANGED = 2;
        private static final int MSG_ADD_CALLBACK = 3;
        private static final int MSG_REMOVE_CALLBACK = 4;

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PAIRED_DEVICES_CHANGED:
                    firePairedDevicesChanged();
                    break;
                case MSG_STATE_CHANGED:
                    fireStateChange();
                    break;
                case MSG_ADD_CALLBACK:
                    mCallbacks.add((BluetoothController.Callback) msg.obj);
                    break;
                case MSG_REMOVE_CALLBACK:
                    mCallbacks.remove((BluetoothController.Callback) msg.obj);
                    break;
            }
        }

        private void firePairedDevicesChanged() {
            for (BluetoothController.Callback cb : mCallbacks) {
                cb.onBluetoothDevicesChanged();
            }
        }

        private void fireStateChange() {
            for (BluetoothController.Callback cb : mCallbacks) {
                fireStateChange(cb);
            }
        }

        private void fireStateChange(BluetoothController.Callback cb) {
            cb.onBluetoothStateChange(mEnabled);
        }
    }
}
