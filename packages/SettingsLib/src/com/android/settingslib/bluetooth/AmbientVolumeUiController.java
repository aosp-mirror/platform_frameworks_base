/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.bluetooth.BluetoothDevice.BOND_BONDED;

import static com.android.settingslib.bluetooth.AmbientVolumeUi.SIDE_UNIFIED;
import static com.android.settingslib.bluetooth.AmbientVolumeUi.VALID_SIDES;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.ArraySet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.utils.ThreadUtils;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import java.util.Map;
import java.util.Set;

/** This class controls ambient volume UI with local and remote ambient data. */
public class AmbientVolumeUiController implements
        AmbientVolumeController.AmbientVolumeControlCallback,
        BluetoothCallback, CachedBluetoothDevice.Callback {

    private final Context mContext;
    private final LocalBluetoothProfileManager mProfileManager;
    private final BluetoothEventManager mEventManager;
    private final AmbientVolumeUi mAmbientLayout;
    private final AmbientVolumeController mVolumeController;

    private final Set<CachedBluetoothDevice> mCachedDevices = new ArraySet<>();
    private final BiMap<Integer, BluetoothDevice> mSideToDeviceMap = HashBiMap.create();
    private CachedBluetoothDevice mCachedDevice;
    private boolean mShowUiWhenLocalDataExist = true;

    public AmbientVolumeUiController(@NonNull Context context,
            @NonNull LocalBluetoothManager bluetoothManager,
            @NonNull AmbientVolumeUi ambientLayout) {
        mContext = context;
        mProfileManager = bluetoothManager.getProfileManager();
        mEventManager = bluetoothManager.getEventManager();
        mAmbientLayout = ambientLayout;
        mVolumeController = new AmbientVolumeController(mProfileManager, this);
    }

    @VisibleForTesting
    public AmbientVolumeUiController(@NonNull Context context,
            @NonNull LocalBluetoothManager bluetoothManager,
            @NonNull AmbientVolumeUi ambientLayout,
            @NonNull AmbientVolumeController volumeController) {
        mContext = context;
        mProfileManager = bluetoothManager.getProfileManager();
        mEventManager = bluetoothManager.getEventManager();
        mAmbientLayout = ambientLayout;
        mVolumeController = volumeController;
    }

    @Override
    public void onVolumeControlServiceConnected() {
        mCachedDevices.forEach(device -> mVolumeController.registerCallback(
                ThreadUtils.getBackgroundExecutor(), device.getDevice()));
    }

    @Override
    public void onAmbientChanged(@NonNull BluetoothDevice device, int gainSettings) {
    }

    @Override
    public void onMuteChanged(@NonNull BluetoothDevice device, int mute) {
    }

    @Override
    public void onCommandFailed(@NonNull BluetoothDevice device) {
    }

    @Override
    public void onProfileConnectionStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int state, int bluetoothProfile) {
        if (bluetoothProfile == BluetoothProfile.VOLUME_CONTROL
                && state == BluetoothProfile.STATE_CONNECTED
                && mCachedDevices.contains(cachedDevice)) {
            // After VCP connected, AICS may not ready yet and still return invalid value, delay
            // a while to wait AICS ready as a workaround
            postDelayedOnMainThread(this::refresh, 1000L);
        }
    }

    @Override
    public void onDeviceAttributesChanged() {
        mCachedDevices.forEach(device -> {
            device.unregisterCallback(this);
            mVolumeController.unregisterCallback(device.getDevice());
        });
        postOnMainThread(()-> {
            loadDevice(mCachedDevice);
            ThreadUtils.postOnBackgroundThread(()-> {
                mCachedDevices.forEach(device -> {
                    device.registerCallback(ThreadUtils.getBackgroundExecutor(), this);
                    mVolumeController.registerCallback(ThreadUtils.getBackgroundExecutor(),
                            device.getDevice());
                });
            });
        });
    }

    /**
     * Registers callbacks and listeners, this should be called when needs to start listening to
     * events.
     */
    public void start() {
        mEventManager.registerCallback(this);
        mCachedDevices.forEach(device -> {
            device.registerCallback(ThreadUtils.getBackgroundExecutor(), this);
            mVolumeController.registerCallback(ThreadUtils.getBackgroundExecutor(),
                    device.getDevice());
        });
    }

    /**
     * Unregisters callbacks and listeners, this should be called when no longer needs to listen to
     * events.
     */
    public void stop() {
        mEventManager.unregisterCallback(this);
        mCachedDevices.forEach(device -> {
            device.unregisterCallback(this);
            mVolumeController.unregisterCallback(device.getDevice());
        });
    }

    /**
     * Loads all devices in the same set with {@code cachedDevice} and create corresponding sliders.
     *
     * <p>If the devices has valid ambient control points, the ambient volume UI will be visible.
     * @param cachedDevice the remote device
     */
    public void loadDevice(CachedBluetoothDevice cachedDevice) {
        mCachedDevice = cachedDevice;
        mSideToDeviceMap.clear();
        mCachedDevices.clear();
        boolean deviceSupportVcp =
                cachedDevice != null && cachedDevice.getProfiles().stream().anyMatch(
                        p -> p instanceof VolumeControlProfile);
        if (!deviceSupportVcp) {
            mAmbientLayout.setVisible(false);
            return;
        }

        // load devices in the same set
        if (VALID_SIDES.contains(cachedDevice.getDeviceSide())
                && cachedDevice.getBondState() == BOND_BONDED) {
            mSideToDeviceMap.put(cachedDevice.getDeviceSide(), cachedDevice.getDevice());
            mCachedDevices.add(cachedDevice);
        }
        for (CachedBluetoothDevice memberDevice : cachedDevice.getMemberDevice()) {
            if (VALID_SIDES.contains(memberDevice.getDeviceSide())
                    && memberDevice.getBondState() == BOND_BONDED) {
                mSideToDeviceMap.put(memberDevice.getDeviceSide(), memberDevice.getDevice());
                mCachedDevices.add(memberDevice);
            }
        }

        mAmbientLayout.setExpandable(mSideToDeviceMap.size() >  1);
        mAmbientLayout.setupSliders(mSideToDeviceMap);
        refresh();
    }

    /** Refreshes the ambient volume UI. */
    public void refresh() {
        if (isAmbientControlAvailable()) {
            mAmbientLayout.setVisible(true);
            updateSliderUi();
        } else {
            mAmbientLayout.setVisible(false);
        }
    }

    /** Sets if the ambient volume UI should be visible when local ambient data exist. */
    public void setShowUiWhenLocalDataExist(boolean shouldShow) {
        mShowUiWhenLocalDataExist = shouldShow;
    }

    /** Updates the ambient sliders according to current state. */
    private void updateSliderUi() {
        boolean isAnySliderEnabled = false;
        for (Map.Entry<Integer, BluetoothDevice> entry : mSideToDeviceMap.entrySet()) {
            final int side = entry.getKey();
            final BluetoothDevice device = entry.getValue();
            final boolean enabled = isDeviceConnectedToVcp(device)
                    && mVolumeController.isAmbientControlAvailable(device);
            isAnySliderEnabled |= enabled;
            mAmbientLayout.setSliderEnabled(side, enabled);
        }
        mAmbientLayout.setSliderEnabled(SIDE_UNIFIED, isAnySliderEnabled);
        mAmbientLayout.updateLayout();
    }

    /** Checks if any device in the same set has valid ambient control points */
    private boolean isAmbientControlAvailable() {
        for (BluetoothDevice device : mSideToDeviceMap.values()) {
            if (mShowUiWhenLocalDataExist) {
                // TODO: check if local data is available
            }
            // Found remote ambient control points
            if (mVolumeController.isAmbientControlAvailable(device)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeviceConnectedToVcp(@Nullable BluetoothDevice device) {
        return device != null && device.isConnected()
                && mProfileManager.getVolumeControlProfile().getConnectionStatus(device)
                == BluetoothProfile.STATE_CONNECTED;
    }

    private void postOnMainThread(Runnable runnable) {
        mContext.getMainThreadHandler().post(runnable);
    }

    private void postDelayedOnMainThread(Runnable runnable, long delay) {
        mContext.getMainThreadHandler().postDelayed(runnable, delay);
    }

    private void showErrorToast(int stringResId) {
        Toast.makeText(mContext, stringResId, Toast.LENGTH_SHORT).show();
    }
}
