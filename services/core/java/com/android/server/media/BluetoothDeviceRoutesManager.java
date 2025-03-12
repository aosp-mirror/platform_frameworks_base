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
import android.media.MediaRoute2Info;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.media.flags.Flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maintains a list of connected {@link BluetoothDevice bluetooth devices} and allows their
 * activation.
 *
 * <p>This class also serves as ground truth for assigning {@link MediaRoute2Info#getId() route ids}
 * for bluetooth routes via {@link #getRouteIdForBluetoothAddress}.
 */
/* package */ class BluetoothDeviceRoutesManager {
    private static final String TAG = SystemMediaRoute2Provider.TAG;

    private static final String HEARING_AID_ROUTE_ID_PREFIX = "HEARING_AID_";
    private static final String LE_AUDIO_ROUTE_ID_PREFIX = "LE_AUDIO_";

    @NonNull
    private final AdapterStateChangedReceiver mAdapterStateChangedReceiver =
            new AdapterStateChangedReceiver();

    @NonNull
    private final DeviceStateChangedReceiver mDeviceStateChangedReceiver =
            new DeviceStateChangedReceiver();

    @NonNull private Map<String, BluetoothDevice> mAddressToBondedDevice = new HashMap<>();
    @NonNull private final Map<String, BluetoothRouteInfo> mBluetoothRoutes = new HashMap<>();

    @NonNull
    private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final BluetoothAdapter mBluetoothAdapter;
    @NonNull
    private final BluetoothRouteController.BluetoothRoutesUpdatedListener mListener;
    @NonNull
    private final BluetoothProfileMonitor mBluetoothProfileMonitor;

    BluetoothDeviceRoutesManager(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull BluetoothAdapter bluetoothAdapter,
            @NonNull BluetoothRouteController.BluetoothRoutesUpdatedListener listener) {
        this(
                context,
                handler,
                bluetoothAdapter,
                new BluetoothProfileMonitor(context, bluetoothAdapter),
                listener);
    }

    @VisibleForTesting
    BluetoothDeviceRoutesManager(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull BluetoothAdapter bluetoothAdapter,
            @NonNull BluetoothProfileMonitor bluetoothProfileMonitor,
            @NonNull BluetoothRouteController.BluetoothRoutesUpdatedListener listener) {
        mContext = Objects.requireNonNull(context);
        mHandler = handler;
        mBluetoothAdapter = Objects.requireNonNull(bluetoothAdapter);
        mBluetoothProfileMonitor = Objects.requireNonNull(bluetoothProfileMonitor);
        mListener = Objects.requireNonNull(listener);
    }

    public void start(UserHandle user) {
        mBluetoothProfileMonitor.start();

        IntentFilter adapterStateChangedIntentFilter = new IntentFilter();

        adapterStateChangedIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiverAsUser(mAdapterStateChangedReceiver, user,
                adapterStateChangedIntentFilter, null, null);

        IntentFilter deviceStateChangedIntentFilter = new IntentFilter();

        deviceStateChangedIntentFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        deviceStateChangedIntentFilter.addAction(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED);
        deviceStateChangedIntentFilter.addAction(
                BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        deviceStateChangedIntentFilter.addAction(
                BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        deviceStateChangedIntentFilter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);

        mContext.registerReceiverAsUser(mDeviceStateChangedReceiver, user,
                deviceStateChangedIntentFilter, null, null);
        updateBluetoothRoutes();
    }

    public void stop() {
        mContext.unregisterReceiver(mAdapterStateChangedReceiver);
        mContext.unregisterReceiver(mDeviceStateChangedReceiver);
    }

    /** Returns true if the given address corresponds to a currently-bonded Bluetooth device. */
    public synchronized boolean containsBondedDeviceWithAddress(@Nullable String address) {
        return mAddressToBondedDevice.containsKey(address);
    }

    @Nullable
    public synchronized String getRouteIdForBluetoothAddress(@Nullable String address) {
        BluetoothDevice bluetoothDevice = mAddressToBondedDevice.get(address);
        return bluetoothDevice != null
                ? getRouteIdForType(bluetoothDevice, getDeviceType(bluetoothDevice))
                : null;
    }

    @Nullable
    public synchronized String getNameForBluetoothAddress(@NonNull String address) {
        BluetoothDevice bluetoothDevice = mAddressToBondedDevice.get(address);
        return bluetoothDevice != null ? getDeviceName(bluetoothDevice) : null;
    }

    public synchronized void activateBluetoothDeviceWithAddress(String address) {
        BluetoothRouteInfo btRouteInfo = mBluetoothRoutes.get(address);

        if (btRouteInfo == null) {
            Slog.w(TAG, "activateBluetoothDeviceWithAddress: Ignoring unknown address " + address);
            return;
        }
        mBluetoothAdapter.setActiveDevice(btRouteInfo.mBtDevice, ACTIVE_DEVICE_AUDIO);
    }

    private void updateBluetoothRoutes() {
        Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();

        synchronized (this) {
            mBluetoothRoutes.clear();
            if (bondedDevices == null) {
                // Bonded devices is null upon running into a BluetoothAdapter error.
                Log.w(TAG, "BluetoothAdapter.getBondedDevices returned null.");
                return;
            }
            // We don't clear bonded devices if we receive a null getBondedDevices result, because
            // that probably means that the bluetooth stack ran into an issue. Not that all devices
            // have been unpaired.
            mAddressToBondedDevice =
                    bondedDevices.stream()
                            .collect(
                                    Collectors.toMap(
                                            BluetoothDevice::getAddress, Function.identity()));
            for (BluetoothDevice device : bondedDevices) {
                if (device.isConnected()) {
                    BluetoothRouteInfo newBtRoute = createBluetoothRoute(device);
                    if (newBtRoute.mConnectedProfiles.size() > 0) {
                        mBluetoothRoutes.put(device.getAddress(), newBtRoute);
                    }
                }
            }
        }
    }

    @NonNull
    public List<MediaRoute2Info> getAvailableBluetoothRoutes() {
        List<MediaRoute2Info> routes = new ArrayList<>();
        Set<String> routeIds = new HashSet<>();

        synchronized (this) {
            for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
                // See createBluetoothRoute for info on why we do this.
                if (routeIds.add(btRoute.mRoute.getId())) {
                    routes.add(btRoute.mRoute);
                }
            }
        }
        return routes;
    }

    private void notifyBluetoothRoutesUpdated() {
        mListener.onBluetoothRoutesUpdated();
    }

    /**
     * Creates a new {@link BluetoothRouteInfo}, including its member {@link
     * BluetoothRouteInfo#mRoute}.
     *
     * <p>The most important logic in this method is around the {@link MediaRoute2Info#getId() route
     * id} assignment. In some cases we want to group multiple {@link BluetoothDevice bluetooth
     * devices} as a single media route. For example, the left and right hearing aids get exposed as
     * two different BluetoothDevice instances, but we want to show them as a single route. In this
     * case, we assign the same route id to all "group" bluetooth devices (like left and right
     * hearing aids), so that a single route is exposed for both of them.
     *
     * <p>Deduplication by id happens downstream because we need to be able to refer to all
     * bluetooth devices individually, since the audio stack refers to a bluetooth device group by
     * any of its member devices.
     */
    private BluetoothRouteInfo createBluetoothRoute(BluetoothDevice device) {
        BluetoothRouteInfo
                newBtRoute = new BluetoothRouteInfo();
        newBtRoute.mBtDevice = device;
        String deviceName = getDeviceName(device);

        int type = getDeviceType(device);
        String routeId = getRouteIdForType(device, type);

        newBtRoute.mConnectedProfiles = getConnectedProfiles(device);
        // Note that volume is only relevant for active bluetooth routes, and those are managed via
        // AudioManager.
        newBtRoute.mRoute =
                new MediaRoute2Info.Builder(routeId, deviceName)
                        .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                        .addFeature(MediaRoute2Info.FEATURE_LOCAL_PLAYBACK)
                        .setConnectionState(MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
                        .setDescription(
                                mContext.getResources()
                                        .getText(R.string.bluetooth_a2dp_audio_route_name)
                                        .toString())
                        .setType(type)
                        .setAddress(device.getAddress())
                        .build();
        return newBtRoute;
    }

    private String getDeviceName(BluetoothDevice device) {
        String deviceName =
                Flags.enableUseOfBluetoothDeviceGetAliasForMr2infoGetName()
                        ? device.getAlias()
                        : device.getName();
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = mContext.getResources().getText(R.string.unknownName).toString();
        }
        return deviceName;
    }
    private SparseBooleanArray getConnectedProfiles(@NonNull BluetoothDevice device) {
        SparseBooleanArray connectedProfiles = new SparseBooleanArray();
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.A2DP, device)) {
            connectedProfiles.put(BluetoothProfile.A2DP, true);
        }
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.HEARING_AID, device)) {
            connectedProfiles.put(BluetoothProfile.HEARING_AID, true);
        }
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.LE_AUDIO, device)) {
            connectedProfiles.put(BluetoothProfile.LE_AUDIO, true);
        }

        return connectedProfiles;
    }

    private int getDeviceType(@NonNull BluetoothDevice device) {
        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.LE_AUDIO, device)) {
            return MediaRoute2Info.TYPE_BLE_HEADSET;
        }

        if (mBluetoothProfileMonitor.isProfileSupported(BluetoothProfile.HEARING_AID, device)) {
            return MediaRoute2Info.TYPE_HEARING_AID;
        }

        return MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
    }

    private String getRouteIdForType(@NonNull BluetoothDevice device, int type) {
        return switch (type) {
            case (MediaRoute2Info.TYPE_BLE_HEADSET) ->
                    LE_AUDIO_ROUTE_ID_PREFIX
                            + mBluetoothProfileMonitor.getGroupId(
                                    BluetoothProfile.LE_AUDIO, device);
            case (MediaRoute2Info.TYPE_HEARING_AID) ->
                    HEARING_AID_ROUTE_ID_PREFIX
                            + mBluetoothProfileMonitor.getGroupId(
                                    BluetoothProfile.HEARING_AID, device);
            // TYPE_BLUETOOTH_A2DP
            default -> device.getAddress();
        };
    }

    private void handleBluetoothAdapterStateChange(int state) {
        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            synchronized (BluetoothDeviceRoutesManager.this) {
                mBluetoothRoutes.clear();
            }
            notifyBluetoothRoutesUpdated();
        } else if (state == BluetoothAdapter.STATE_ON) {
            updateBluetoothRoutes();

            boolean shouldCallListener;
            synchronized (BluetoothDeviceRoutesManager.this) {
                shouldCallListener = !mBluetoothRoutes.isEmpty();
            }

            if (shouldCallListener) {
                notifyBluetoothRoutesUpdated();
            }
        }
    }

    private static class BluetoothRouteInfo {
        private BluetoothDevice mBtDevice;
        private MediaRoute2Info mRoute;
        private SparseBooleanArray mConnectedProfiles;
    }

    private class AdapterStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (Flags.enableMr2ServiceNonMainBgThread()) {
                mHandler.post(() -> handleBluetoothAdapterStateChange(state));
            } else {
                handleBluetoothAdapterStateChange(state);
            }
        }
    }

    private class DeviceStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                case BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED:
                case BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED:
                case BluetoothDevice.ACTION_ALIAS_CHANGED:
                    if (Flags.enableMr2ServiceNonMainBgThread()) {
                        mHandler.post(
                                () -> {
                                    updateBluetoothRoutes();
                                    notifyBluetoothRoutesUpdated();
                                });
                    } else {
                        updateBluetoothRoutes();
                        notifyBluetoothRoutesUpdated();
                    }
            }
        }
    }
}
