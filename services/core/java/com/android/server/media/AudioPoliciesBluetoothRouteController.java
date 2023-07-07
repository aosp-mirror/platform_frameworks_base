/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media;

import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_AUDIO;
import static android.bluetooth.BluetoothAdapter.STATE_CONNECTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.MediaRoute2Info;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Controls bluetooth routes and provides selected route override.
 *
 * <p>The controller offers similar functionality to {@link LegacyBluetoothRouteController} but does
 * not support routes selection logic. Instead, relies on external clients to make a decision
 * about currently selected route.
 *
 * <p>Selected route override should be used by {@link AudioManager} which is aware of Audio
 * Policies.
 */
/* package */ class AudioPoliciesBluetoothRouteController
        implements BluetoothRouteController {
    private static final String TAG = "APBtRouteController";

    private static final String HEARING_AID_ROUTE_ID_PREFIX = "HEARING_AID_";
    private static final String LE_AUDIO_ROUTE_ID_PREFIX = "LE_AUDIO_";

    @NonNull
    private final AdapterStateChangedReceiver mAdapterStateChangedReceiver =
            new AdapterStateChangedReceiver();

    @NonNull
    private final DeviceStateChangedReceiver mDeviceStateChangedReceiver =
            new DeviceStateChangedReceiver();

    @NonNull
    private final Map<String, BluetoothRouteInfo> mBluetoothRoutes = new HashMap<>();

    @NonNull
    private final SparseIntArray mVolumeMap = new SparseIntArray();

    @NonNull
    private final Context mContext;
    @NonNull
    private final BluetoothAdapter mBluetoothAdapter;
    @NonNull
    private final BluetoothRouteController.BluetoothRoutesUpdatedListener mListener;
    @NonNull
    private final BluetoothProfileMonitor mBluetoothProfileMonitor;
    @NonNull
    private final AudioManager mAudioManager;

    @Nullable
    private BluetoothRouteInfo mSelectedBluetoothRoute;

    AudioPoliciesBluetoothRouteController(@NonNull Context context,
            @NonNull BluetoothAdapter bluetoothAdapter,
            @NonNull BluetoothRouteController.BluetoothRoutesUpdatedListener listener) {
        this(context, bluetoothAdapter,
                new BluetoothProfileMonitor(context, bluetoothAdapter), listener);
    }

    @VisibleForTesting
    AudioPoliciesBluetoothRouteController(@NonNull Context context,
            @NonNull BluetoothAdapter bluetoothAdapter,
            @NonNull BluetoothProfileMonitor bluetoothProfileMonitor,
            @NonNull BluetoothRouteController.BluetoothRoutesUpdatedListener listener) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(bluetoothAdapter);
        Objects.requireNonNull(bluetoothProfileMonitor);
        Objects.requireNonNull(listener);

        mContext = context;
        mBluetoothAdapter = bluetoothAdapter;
        mBluetoothProfileMonitor = bluetoothProfileMonitor;
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mListener = listener;

        updateBluetoothRoutes();
    }

    @Override
    public void start(UserHandle user) {
        mBluetoothProfileMonitor.start();

        IntentFilter adapterStateChangedIntentFilter = new IntentFilter();

        adapterStateChangedIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiverAsUser(mAdapterStateChangedReceiver, user,
                adapterStateChangedIntentFilter, null, null);

        IntentFilter deviceStateChangedIntentFilter = new IntentFilter();

        deviceStateChangedIntentFilter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        deviceStateChangedIntentFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        deviceStateChangedIntentFilter.addAction(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        deviceStateChangedIntentFilter.addAction(
                BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        deviceStateChangedIntentFilter.addAction(
                BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        deviceStateChangedIntentFilter.addAction(
                BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED);

        mContext.registerReceiverAsUser(mDeviceStateChangedReceiver, user,
                deviceStateChangedIntentFilter, null, null);
    }

    @Override
    public void stop() {
        mContext.unregisterReceiver(mAdapterStateChangedReceiver);
        mContext.unregisterReceiver(mDeviceStateChangedReceiver);
    }

    @Override
    public boolean selectRoute(@Nullable String deviceAddress) {
        synchronized (this) {
            // Fetch all available devices in order to avoid race conditions with Bluetooth stack.
            updateBluetoothRoutes();

            if (deviceAddress == null) {
                mSelectedBluetoothRoute = null;
                return true;
            }

            BluetoothRouteInfo bluetoothRouteInfo = mBluetoothRoutes.get(deviceAddress);

            if (bluetoothRouteInfo == null) {
                Slog.w(TAG, "Cannot find bluetooth route for " + deviceAddress);
                return false;
            }

            mSelectedBluetoothRoute = bluetoothRouteInfo;
            setRouteConnectionState(mSelectedBluetoothRoute, STATE_CONNECTED);

            updateConnectivityStateForDevicesInTheSameGroup();

            return true;
        }
    }

    /**
     * Updates connectivity state for devices in the same devices group.
     *
     * <p>{@link BluetoothProfile#LE_AUDIO} and {@link BluetoothProfile#HEARING_AID} support
     * grouping devices. Devices that belong to the same group should have the same routeId but
     * different physical address.
     *
     * <p>In case one of the devices from the group is selected then other devices should also
     * reflect this by changing their connectivity status to
     * {@link MediaRoute2Info#CONNECTION_STATE_CONNECTED}.
     */
    private void updateConnectivityStateForDevicesInTheSameGroup() {
        synchronized (this) {
            for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
                if (TextUtils.equals(btRoute.mRoute.getId(), mSelectedBluetoothRoute.mRoute.getId())
                        && !TextUtils.equals(btRoute.mBtDevice.getAddress(),
                        mSelectedBluetoothRoute.mBtDevice.getAddress())) {
                    setRouteConnectionState(btRoute, STATE_CONNECTED);
                }
            }
        }
    }

    @Override
    public void transferTo(@Nullable String routeId) {
        if (routeId == null) {
            mBluetoothAdapter.removeActiveDevice(ACTIVE_DEVICE_AUDIO);
            return;
        }

        BluetoothRouteInfo btRouteInfo = findBluetoothRouteWithRouteId(routeId);

        if (btRouteInfo == null) {
            Slog.w(TAG, "transferTo: Unknown route. ID=" + routeId);
            return;
        }

        mBluetoothAdapter.setActiveDevice(btRouteInfo.mBtDevice, ACTIVE_DEVICE_AUDIO);
    }

    @Nullable
    private BluetoothRouteInfo findBluetoothRouteWithRouteId(@Nullable String routeId) {
        if (routeId == null) {
            return null;
        }
        synchronized (this) {
            for (BluetoothRouteInfo btRouteInfo : mBluetoothRoutes.values()) {
                if (TextUtils.equals(btRouteInfo.mRoute.getId(), routeId)) {
                    return btRouteInfo;
                }
            }
        }
        return null;
    }

    private void updateBluetoothRoutes() {
        Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();

        if (bondedDevices == null) {
            return;
        }

        synchronized (this) {
            mBluetoothRoutes.clear();

            // We need to query all available to BT stack devices in order to avoid inconsistency
            // between external services, like, AndroidManager, and BT stack.
            for (BluetoothDevice device : bondedDevices) {
                if (isDeviceConnected(device)) {
                    BluetoothRouteInfo newBtRoute = createBluetoothRoute(device);
                    if (newBtRoute.mConnectedProfiles.size() > 0) {
                        mBluetoothRoutes.put(device.getAddress(), newBtRoute);
                    }
                }
            }
        }
    }

    @VisibleForTesting
        /* package */ boolean isDeviceConnected(@NonNull BluetoothDevice device) {
        return device.isConnected();
    }

    @Nullable
    @Override
    public MediaRoute2Info getSelectedRoute() {
        synchronized (this) {
            if (mSelectedBluetoothRoute == null) {
                return null;
            }

            return mSelectedBluetoothRoute.mRoute;
        }
    }

    @NonNull
    @Override
    public List<MediaRoute2Info> getTransferableRoutes() {
        List<MediaRoute2Info> routes = getAllBluetoothRoutes();
        synchronized (this) {
            if (mSelectedBluetoothRoute != null) {
                routes.remove(mSelectedBluetoothRoute.mRoute);
            }
        }
        return routes;
    }

    @NonNull
    @Override
    public List<MediaRoute2Info> getAllBluetoothRoutes() {
        List<MediaRoute2Info> routes = new ArrayList<>();
        List<String> routeIds = new ArrayList<>();

        MediaRoute2Info selectedRoute = getSelectedRoute();
        if (selectedRoute != null) {
            routes.add(selectedRoute);
            routeIds.add(selectedRoute.getId());
        }

        synchronized (this) {
            for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
                // A pair of hearing aid devices or having the same hardware address
                if (routeIds.contains(btRoute.mRoute.getId())) {
                    continue;
                }
                routes.add(btRoute.mRoute);
                routeIds.add(btRoute.mRoute.getId());
            }
        }
        return routes;
    }

    @Override
    public boolean updateVolumeForDevices(int devices, int volume) {
        int routeType;
        if ((devices & (AudioSystem.DEVICE_OUT_HEARING_AID)) != 0) {
            routeType = MediaRoute2Info.TYPE_HEARING_AID;
        } else if ((devices & (AudioManager.DEVICE_OUT_BLUETOOTH_A2DP
                | AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES
                | AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER)) != 0) {
            routeType = MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        } else if ((devices & (AudioManager.DEVICE_OUT_BLE_HEADSET)) != 0) {
            routeType = MediaRoute2Info.TYPE_BLE_HEADSET;
        } else {
            return false;
        }

        synchronized (this) {
            mVolumeMap.put(routeType, volume);
            if (mSelectedBluetoothRoute == null
                    || mSelectedBluetoothRoute.mRoute.getType() != routeType) {
                return false;
            }

            mSelectedBluetoothRoute.mRoute =
                    new MediaRoute2Info.Builder(mSelectedBluetoothRoute.mRoute)
                            .setVolume(volume)
                            .build();
        }

        notifyBluetoothRoutesUpdated();
        return true;
    }

    private void notifyBluetoothRoutesUpdated() {
        mListener.onBluetoothRoutesUpdated(getAllBluetoothRoutes());
    }

    private BluetoothRouteInfo createBluetoothRoute(BluetoothDevice device) {
        BluetoothRouteInfo
                newBtRoute = new BluetoothRouteInfo();
        newBtRoute.mBtDevice = device;

        String routeId = device.getAddress();
        String deviceName = device.getName();
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = mContext.getResources().getText(R.string.unknownName).toString();
        }
        int type = MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        newBtRoute.mConnectedProfiles = new SparseBooleanArray();
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.A2DP, device)) {
            newBtRoute.mConnectedProfiles.put(BluetoothProfile.A2DP, true);
        }
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.HEARING_AID, device)) {
            newBtRoute.mConnectedProfiles.put(BluetoothProfile.HEARING_AID, true);
            // Intentionally assign the same ID for a pair of devices to publish only one of them.
            routeId = HEARING_AID_ROUTE_ID_PREFIX
                    + mBluetoothProfileMonitor.getGroupId(BluetoothProfile.HEARING_AID, device);
            type = MediaRoute2Info.TYPE_HEARING_AID;
        }
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.LE_AUDIO, device)) {
            newBtRoute.mConnectedProfiles.put(BluetoothProfile.LE_AUDIO, true);
            routeId = LE_AUDIO_ROUTE_ID_PREFIX
                    + mBluetoothProfileMonitor.getGroupId(BluetoothProfile.LE_AUDIO, device);
            type = MediaRoute2Info.TYPE_BLE_HEADSET;
        }

        // Current volume will be set when connected.
        newBtRoute.mRoute = new MediaRoute2Info.Builder(routeId, deviceName)
                .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                .addFeature(MediaRoute2Info.FEATURE_LOCAL_PLAYBACK)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
                .setDescription(mContext.getResources().getText(
                        R.string.bluetooth_a2dp_audio_route_name).toString())
                .setType(type)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setAddress(device.getAddress())
                .build();
        return newBtRoute;
    }

    private void setRouteConnectionState(@NonNull BluetoothRouteInfo btRoute,
            @MediaRoute2Info.ConnectionState int state) {
        if (btRoute == null) {
            Slog.w(TAG, "setRouteConnectionState: route shouldn't be null");
            return;
        }
        if (btRoute.mRoute.getConnectionState() == state) {
            return;
        }

        MediaRoute2Info.Builder builder = new MediaRoute2Info.Builder(btRoute.mRoute)
                .setConnectionState(state);
        builder.setType(btRoute.getRouteType());



        if (state == MediaRoute2Info.CONNECTION_STATE_CONNECTED) {
            int currentVolume;
            synchronized (this) {
                currentVolume = mVolumeMap.get(btRoute.getRouteType(), 0);
            }
            builder.setVolume(currentVolume);
        }

        btRoute.mRoute = builder.build();
    }

    private static class BluetoothRouteInfo {
        private BluetoothDevice mBtDevice;
        private MediaRoute2Info mRoute;
        private SparseBooleanArray mConnectedProfiles;

        @MediaRoute2Info.Type
        int getRouteType() {
            // Let hearing aid profile have a priority.
            if (mConnectedProfiles.get(BluetoothProfile.HEARING_AID, false)) {
                return MediaRoute2Info.TYPE_HEARING_AID;
            }

            if (mConnectedProfiles.get(BluetoothProfile.LE_AUDIO, false)) {
                return MediaRoute2Info.TYPE_BLE_HEADSET;
            }

            return MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        }
    }

    private class AdapterStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF
                    || state == BluetoothAdapter.STATE_TURNING_OFF) {
                synchronized (AudioPoliciesBluetoothRouteController.this) {
                    mBluetoothRoutes.clear();
                }
                notifyBluetoothRoutesUpdated();
            } else if (state == BluetoothAdapter.STATE_ON) {
                updateBluetoothRoutes();

                boolean shouldCallListener;
                synchronized (AudioPoliciesBluetoothRouteController.this) {
                    shouldCallListener = !mBluetoothRoutes.isEmpty();
                }

                if (shouldCallListener) {
                    notifyBluetoothRoutesUpdated();
                }
            }
        }
    }

    private class DeviceStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                case BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED:
                case BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED:
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                case BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED:
                case BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED:
                    updateBluetoothRoutes();
                    notifyBluetoothRoutesUpdated();
            }
        }
    }
}
