/*
 * Copyright 2020 The Android Open Source Project
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
import static android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothManager;
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
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class BluetoothRouteProvider {
    private static final String TAG = "BTRouteProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String HEARING_AID_ROUTE_ID_PREFIX = "HEARING_AID_";
    private static final String LE_AUDIO_ROUTE_ID_PREFIX = "LE_AUDIO_";

    // Maps hardware address to BluetoothRouteInfo
    private final Map<String, BluetoothRouteInfo> mBluetoothRoutes = new HashMap<>();
    private final List<BluetoothRouteInfo> mActiveRoutes = new ArrayList<>();

    // Route type -> volume map
    private final SparseIntArray mVolumeMap = new SparseIntArray();

    private final Context mContext;
    private final BluetoothAdapter mBluetoothAdapter;
    private final BluetoothRoutesUpdatedListener mListener;
    private final AudioManager mAudioManager;
    private final BluetoothProfileListener mProfileListener = new BluetoothProfileListener();

    private final AdapterStateChangedReceiver mAdapterStateChangedReceiver =
            new AdapterStateChangedReceiver();
    private final DeviceStateChangedReceiver mDeviceStateChangedReceiver =
            new DeviceStateChangedReceiver();

    private BluetoothA2dp mA2dpProfile;
    private BluetoothHearingAid mHearingAidProfile;
    private BluetoothLeAudio mLeAudioProfile;

    /**
     * Create an instance of {@link BluetoothRouteProvider}.
     * It may return {@code null} if Bluetooth is not supported on this hardware platform.
     */
    @Nullable
    static BluetoothRouteProvider createInstance(@NonNull Context context,
            @NonNull BluetoothRoutesUpdatedListener listener) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(listener);

        BluetoothManager bluetoothManager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = bluetoothManager.getAdapter();
        if (btAdapter == null) {
            return null;
        }
        return new BluetoothRouteProvider(context, btAdapter, listener);
    }

    private BluetoothRouteProvider(Context context, BluetoothAdapter btAdapter,
            BluetoothRoutesUpdatedListener listener) {
        mContext = context;
        mBluetoothAdapter = btAdapter;
        mListener = listener;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        buildBluetoothRoutes();
    }

    /**
     * Registers listener to bluetooth status changes as the provided user.
     *
     * The registered receiver listens to {@link BluetoothA2dp#ACTION_ACTIVE_DEVICE_CHANGED} and
     * {@link BluetoothA2dp#ACTION_CONNECTION_STATE_CHANGED } events for {@link BluetoothProfile#A2DP},
     * {@link BluetoothProfile#HEARING_AID}, and {@link BluetoothProfile#LE_AUDIO} bluetooth profiles.
     *
     * @param user {@code UserHandle} as which receiver is registered
     */
    void start(UserHandle user) {
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.A2DP);
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEARING_AID);
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.LE_AUDIO);

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

    void stop() {
        mContext.unregisterReceiver(mAdapterStateChangedReceiver);
        mContext.unregisterReceiver(mDeviceStateChangedReceiver);
    }

    /**
     * Transfers to a given bluetooth route.
     * The dedicated BT device with the route would be activated.
     *
     * @param routeId the id of the Bluetooth device. {@code null} denotes to clear the use of
     *               BT routes.
     */
    void transferTo(@Nullable String routeId) {
        if (routeId == null) {
            clearActiveDevices();
            return;
        }

        BluetoothRouteInfo btRouteInfo = findBluetoothRouteWithRouteId(routeId);

        if (btRouteInfo == null) {
            Slog.w(TAG, "transferTo: Unknown route. ID=" + routeId);
            return;
        }

        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.setActiveDevice(btRouteInfo.mBtDevice, ACTIVE_DEVICE_AUDIO);
        }
    }

    private BluetoothRouteInfo findBluetoothRouteWithRouteId(String routeId) {
        if (routeId == null) {
            return null;
        }
        for (BluetoothRouteInfo btRouteInfo : mBluetoothRoutes.values()) {
            if (TextUtils.equals(btRouteInfo.mRoute.getId(), routeId)) {
                return btRouteInfo;
            }
        }
        return null;
    }

    /**
     * Clears the active device for all known profiles.
     */
    private void clearActiveDevices() {
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.removeActiveDevice(ACTIVE_DEVICE_AUDIO);
        }
    }

    private void buildBluetoothRoutes() {
        mBluetoothRoutes.clear();
        Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();
        if (bondedDevices != null) {
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

    @Nullable
    MediaRoute2Info getSelectedRoute() {
        // For now, active routes can be multiple only when a pair of hearing aid devices is active.
        // Let the first active device represent them.
        return (mActiveRoutes.isEmpty() ? null : mActiveRoutes.get(0).mRoute);
    }

    @NonNull
    List<MediaRoute2Info> getTransferableRoutes() {
        List<MediaRoute2Info> routes = getAllBluetoothRoutes();
        for (BluetoothRouteInfo btRoute : mActiveRoutes) {
            routes.remove(btRoute.mRoute);
        }
        return routes;
    }

    @NonNull
    List<MediaRoute2Info> getAllBluetoothRoutes() {
        List<MediaRoute2Info> routes = new ArrayList<>();
        List<String> routeIds = new ArrayList<>();

        MediaRoute2Info selectedRoute = getSelectedRoute();
        if (selectedRoute != null) {
            routes.add(selectedRoute);
            routeIds.add(selectedRoute.getId());
        }

        for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
            // A pair of hearing aid devices or having the same hardware address
            if (routeIds.contains(btRoute.mRoute.getId())) {
                continue;
            }
            routes.add(btRoute.mRoute);
            routeIds.add(btRoute.mRoute.getId());
        }
        return routes;
    }

    /**
     * Updates the volume for {@link AudioManager#getDevicesForStream(int) devices}.
     *
     * @return true if devices can be handled by the provider.
     */
    boolean updateVolumeForDevices(int devices, int volume) {
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
        mVolumeMap.put(routeType, volume);

        boolean shouldNotify = false;
        for (BluetoothRouteInfo btRoute : mActiveRoutes) {
            if (btRoute.mRoute.getType() != routeType) {
                continue;
            }
            btRoute.mRoute = new MediaRoute2Info.Builder(btRoute.mRoute)
                    .setVolume(volume)
                    .build();
            shouldNotify = true;
        }
        if (shouldNotify) {
            notifyBluetoothRoutesUpdated();
        }
        return true;
    }

    private void notifyBluetoothRoutesUpdated() {
        if (mListener != null) {
            mListener.onBluetoothRoutesUpdated(getAllBluetoothRoutes());
        }
    }

    private BluetoothRouteInfo createBluetoothRoute(BluetoothDevice device) {
        BluetoothRouteInfo newBtRoute = new BluetoothRouteInfo();
        newBtRoute.mBtDevice = device;

        String routeId = device.getAddress();
        String deviceName = device.getName();
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = mContext.getResources().getText(R.string.unknownName).toString();
        }
        int type = MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        newBtRoute.mConnectedProfiles = new SparseBooleanArray();
        if (mA2dpProfile != null && mA2dpProfile.getConnectedDevices().contains(device)) {
            newBtRoute.mConnectedProfiles.put(BluetoothProfile.A2DP, true);
        }
        if (mHearingAidProfile != null
                && mHearingAidProfile.getConnectedDevices().contains(device)) {
            newBtRoute.mConnectedProfiles.put(BluetoothProfile.HEARING_AID, true);
            // Intentionally assign the same ID for a pair of devices to publish only one of them.
            routeId = HEARING_AID_ROUTE_ID_PREFIX + mHearingAidProfile.getHiSyncId(device);
            type = MediaRoute2Info.TYPE_HEARING_AID;
        }
        if (mLeAudioProfile != null
                && mLeAudioProfile.getConnectedDevices().contains(device)) {
            newBtRoute.mConnectedProfiles.put(BluetoothProfile.LE_AUDIO, true);
            routeId = LE_AUDIO_ROUTE_ID_PREFIX + mLeAudioProfile.getGroupId(device);
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
            builder.setVolume(mVolumeMap.get(btRoute.getRouteType(), 0));
        }
        btRoute.mRoute = builder.build();
    }

    private void addActiveRoute(BluetoothRouteInfo btRoute) {
        if (btRoute == null) {
            Slog.w(TAG, "addActiveRoute: btRoute is null");
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Adding active route: " + btRoute.mRoute);
        }
        if (mActiveRoutes.contains(btRoute)) {
            Slog.w(TAG, "addActiveRoute: btRoute is already added.");
            return;
        }
        setRouteConnectionState(btRoute, STATE_CONNECTED);
        mActiveRoutes.add(btRoute);
    }

    private void removeActiveRoute(BluetoothRouteInfo btRoute) {
        if (DEBUG) {
            Log.d(TAG, "Removing active route: " + btRoute.mRoute);
        }
        if (mActiveRoutes.remove(btRoute)) {
            setRouteConnectionState(btRoute, STATE_DISCONNECTED);
        }
    }

    private void clearActiveRoutesWithType(int type) {
        if (DEBUG) {
            Log.d(TAG, "Clearing active routes with type. type=" + type);
        }
        Iterator<BluetoothRouteInfo> iter = mActiveRoutes.iterator();
        while (iter.hasNext()) {
            BluetoothRouteInfo btRoute = iter.next();
            if (btRoute.mRoute.getType() == type) {
                iter.remove();
                setRouteConnectionState(btRoute, STATE_DISCONNECTED);
            }
        }
    }

    private void addActiveDevices(BluetoothDevice device) {
        // Let the given device be the first active device
        BluetoothRouteInfo activeBtRoute = mBluetoothRoutes.get(device.getAddress());
        // This could happen if ACTION_ACTIVE_DEVICE_CHANGED is sent before
        // ACTION_CONNECTION_STATE_CHANGED is sent.
        if (activeBtRoute == null) {
            activeBtRoute = createBluetoothRoute(device);
            mBluetoothRoutes.put(device.getAddress(), activeBtRoute);
        }
        addActiveRoute(activeBtRoute);

        // A bluetooth route with the same route ID should be added.
        for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
            if (TextUtils.equals(btRoute.mRoute.getId(), activeBtRoute.mRoute.getId())
                    && !TextUtils.equals(btRoute.mBtDevice.getAddress(),
                    activeBtRoute.mBtDevice.getAddress())) {
                addActiveRoute(btRoute);
            }
        }
    }
    private void addActiveHearingAidDevices(BluetoothDevice device) {
        if (DEBUG) {
            Log.d(TAG, "Setting active hearing aid devices. device=" + device);
        }

        addActiveDevices(device);
    }

    private void addActiveLeAudioDevices(BluetoothDevice device) {
        if (DEBUG) {
            Log.d(TAG, "Setting active le audio devices. device=" + device);
        }

        addActiveDevices(device);
    }

    interface BluetoothRoutesUpdatedListener {
        void onBluetoothRoutesUpdated(@NonNull List<MediaRoute2Info> routes);
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

    // These callbacks run on the main thread.
    private final class BluetoothProfileListener implements BluetoothProfile.ServiceListener {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            List<BluetoothDevice> activeDevices;
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mA2dpProfile = (BluetoothA2dp) proxy;
                    // It may contain null.
                    activeDevices = mBluetoothAdapter.getActiveDevices(BluetoothProfile.A2DP);
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHearingAidProfile = (BluetoothHearingAid) proxy;
                    activeDevices = mBluetoothAdapter.getActiveDevices(
                            BluetoothProfile.HEARING_AID);
                    break;
                case BluetoothProfile.LE_AUDIO:
                    mLeAudioProfile = (BluetoothLeAudio) proxy;
                    activeDevices = mBluetoothAdapter.getActiveDevices(BluetoothProfile.LE_AUDIO);
                    break;
                default:
                    return;
            }
            for (BluetoothDevice device : proxy.getConnectedDevices()) {
                BluetoothRouteInfo btRoute = mBluetoothRoutes.get(device.getAddress());
                if (btRoute == null) {
                    btRoute = createBluetoothRoute(device);
                    mBluetoothRoutes.put(device.getAddress(), btRoute);
                }
                if (activeDevices.contains(device)) {
                    addActiveRoute(btRoute);
                }
            }
            notifyBluetoothRoutesUpdated();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mA2dpProfile = null;
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHearingAidProfile = null;
                    break;
                case BluetoothProfile.LE_AUDIO:
                    mLeAudioProfile = null;
                    break;
                default:
                    return;
            }
        }
    }

    private class AdapterStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF
                    || state == BluetoothAdapter.STATE_TURNING_OFF) {
                mBluetoothRoutes.clear();
                notifyBluetoothRoutesUpdated();
            } else if (state == BluetoothAdapter.STATE_ON) {
                buildBluetoothRoutes();
                if (!mBluetoothRoutes.isEmpty()) {
                    notifyBluetoothRoutesUpdated();
                }
            }
        }
    }

    private class DeviceStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice.class);

            switch (intent.getAction()) {
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                    clearActiveRoutesWithType(MediaRoute2Info.TYPE_BLUETOOTH_A2DP);
                    if (device != null) {
                        addActiveRoute(mBluetoothRoutes.get(device.getAddress()));
                    }
                    notifyBluetoothRoutesUpdated();
                    break;
                case BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED:
                    clearActiveRoutesWithType(MediaRoute2Info.TYPE_HEARING_AID);
                    if (device != null) {
                        addActiveHearingAidDevices(device);
                    }
                    notifyBluetoothRoutesUpdated();
                    break;
                case BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED:
                    clearActiveRoutesWithType(MediaRoute2Info.TYPE_BLE_HEADSET);
                    if (device != null) {
                        addActiveLeAudioDevices(device);
                    }
                    notifyBluetoothRoutesUpdated();
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    handleConnectionStateChanged(BluetoothProfile.A2DP, intent, device);
                    break;
                case BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED:
                    handleConnectionStateChanged(BluetoothProfile.HEARING_AID, intent, device);
                    break;
                case BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED:
                    handleConnectionStateChanged(BluetoothProfile.LE_AUDIO, intent, device);
                    break;
            }
        }

        private void handleConnectionStateChanged(int profile, Intent intent,
                BluetoothDevice device) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            BluetoothRouteInfo btRoute = mBluetoothRoutes.get(device.getAddress());
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (btRoute == null) {
                    btRoute = createBluetoothRoute(device);
                    if (btRoute.mConnectedProfiles.size() > 0) {
                        mBluetoothRoutes.put(device.getAddress(), btRoute);
                        notifyBluetoothRoutesUpdated();
                    }
                } else {
                    btRoute.mConnectedProfiles.put(profile, true);
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTING
                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                if (btRoute != null) {
                    btRoute.mConnectedProfiles.delete(profile);
                    if (btRoute.mConnectedProfiles.size() == 0) {
                        removeActiveRoute(mBluetoothRoutes.remove(device.getAddress()));
                        notifyBluetoothRoutesUpdated();
                    }
                }
            }
        }
    }
}
