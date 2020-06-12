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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.MediaRoute2Info;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class BluetoothRouteProvider {
    private static final String TAG = "BTRouteProvider";
    private static BluetoothRouteProvider sInstance;

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Map<String, BluetoothRouteInfo> mBluetoothRoutes = new HashMap<>();
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    BluetoothRouteInfo mSelectedRoute = null;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    BluetoothA2dp mA2dpProfile;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    BluetoothHearingAid mHearingAidProfile;

    // Route type -> volume map
    private final SparseIntArray mVolumeMap = new SparseIntArray();

    private final Context mContext;
    private final BluetoothAdapter mBluetoothAdapter;
    private final BluetoothRoutesUpdatedListener mListener;
    private final AudioManager mAudioManager;
    private final Map<String, BluetoothEventReceiver> mEventReceiverMap = new HashMap<>();
    private final IntentFilter mIntentFilter = new IntentFilter();
    private final BroadcastReceiver mBroadcastReceiver = new BluetoothBroadcastReceiver();
    private final BluetoothProfileListener mProfileListener = new BluetoothProfileListener();

    static synchronized BluetoothRouteProvider getInstance(@NonNull Context context,
            @NonNull BluetoothRoutesUpdatedListener listener) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(listener);

        if (sInstance == null) {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter == null) {
                return null;
            }
            sInstance = new BluetoothRouteProvider(context, btAdapter, listener);
        }
        return sInstance;
    }

    private BluetoothRouteProvider(Context context, BluetoothAdapter btAdapter,
            BluetoothRoutesUpdatedListener listener) {
        mContext = context;
        mBluetoothAdapter = btAdapter;
        mListener = listener;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        buildBluetoothRoutes();
    }

    public void start() {
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.A2DP);
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEARING_AID);

        // Bluetooth on/off broadcasts
        addEventReceiver(BluetoothAdapter.ACTION_STATE_CHANGED, new AdapterStateChangedReceiver());

        DeviceStateChangedRecevier deviceStateChangedReceiver = new DeviceStateChangedRecevier();
        addEventReceiver(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED, deviceStateChangedReceiver);
        addEventReceiver(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, deviceStateChangedReceiver);
        addEventReceiver(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED,
                deviceStateChangedReceiver);
        addEventReceiver(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED,
                deviceStateChangedReceiver);

        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter, null, null);
    }

    /**
     * Transfers to a given bluetooth route.
     * The dedicated BT device with the route would be activated.
     *
     * @param routeId the id of the Bluetooth device. {@code null} denotes to clear the use of
     *               BT routes.
     */
    public void transferTo(@Nullable String routeId) {
        if (routeId == null) {
            clearActiveDevices();
            return;
        }

        BluetoothRouteInfo btRouteInfo = mBluetoothRoutes.get(routeId);
        if (btRouteInfo == null) {
            Slog.w(TAG, "transferTo: unknown route id=" + routeId);
            return;
        }

        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.setActiveDevice(btRouteInfo.btDevice, ACTIVE_DEVICE_AUDIO);
        }
    }

    /**
     * Clears the active device for all known profiles.
     */
    private void clearActiveDevices() {
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.removeActiveDevice(ACTIVE_DEVICE_AUDIO);
        }
    }

    private void addEventReceiver(String action, BluetoothEventReceiver eventReceiver) {
        mEventReceiverMap.put(action, eventReceiver);
        mIntentFilter.addAction(action);
    }

    private void buildBluetoothRoutes() {
        mBluetoothRoutes.clear();
        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            if (device.isConnected()) {
                BluetoothRouteInfo newBtRoute = createBluetoothRoute(device);
                if (newBtRoute.connectedProfiles.size() > 0) {
                    mBluetoothRoutes.put(device.getAddress(), newBtRoute);
                }
            }
        }
    }

    @Nullable
    MediaRoute2Info getSelectedRoute() {
        return (mSelectedRoute == null) ? null : mSelectedRoute.route;
    }

    @NonNull
    List<MediaRoute2Info> getTransferableRoutes() {
        List<MediaRoute2Info> routes = getAllBluetoothRoutes();
        if (mSelectedRoute != null) {
            routes.remove(mSelectedRoute.route);
        }
        return routes;
    }

    @NonNull
    List<MediaRoute2Info> getAllBluetoothRoutes() {
        ArrayList<MediaRoute2Info> routes = new ArrayList<>();
        for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
            routes.add(btRoute.route);
        }
        return routes;
    }

    /**
     * Updates the volume for {@link AudioManager#getDevicesForStream(int) devices}.
     *
     * @return true if devices can be handled by the provider.
     */
    public boolean updateVolumeForDevices(int devices, int volume) {
        int routeType;
        if ((devices & (AudioSystem.DEVICE_OUT_HEARING_AID)) != 0) {
            routeType = MediaRoute2Info.TYPE_HEARING_AID;
        } else if ((devices & (AudioManager.DEVICE_OUT_BLUETOOTH_A2DP
                | AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES
                | AudioManager.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER)) != 0) {
            routeType = MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        } else {
            return false;
        }
        mVolumeMap.put(routeType, volume);
        if (mSelectedRoute == null || mSelectedRoute.route.getType() != routeType) {
            return true;
        }
        mSelectedRoute.route = new MediaRoute2Info.Builder(mSelectedRoute.route)
                .setVolume(volume)
                .build();
        notifyBluetoothRoutesUpdated();
        return true;
    }

    private void notifyBluetoothRoutesUpdated() {
        if (mListener != null) {
            mListener.onBluetoothRoutesUpdated(getAllBluetoothRoutes());
        }
    }

    private BluetoothRouteInfo createBluetoothRoute(BluetoothDevice device) {
        BluetoothRouteInfo newBtRoute = new BluetoothRouteInfo();
        newBtRoute.btDevice = device;
        // Current volume will be set when connected.
        // TODO: Is there any BT device which has fixed volume?
        String deviceName = device.getName();
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = mContext.getResources().getText(R.string.unknownName).toString();
        }
        int type = MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        newBtRoute.connectedProfiles = new SparseBooleanArray();
        if (mA2dpProfile != null && mA2dpProfile.getConnectedDevices().contains(device)) {
            newBtRoute.connectedProfiles.put(BluetoothProfile.A2DP, true);
        }
        if (mHearingAidProfile != null
                && mHearingAidProfile.getConnectedDevices().contains(device)) {
            newBtRoute.connectedProfiles.put(BluetoothProfile.HEARING_AID, true);
            type = MediaRoute2Info.TYPE_HEARING_AID;
        }

        newBtRoute.route = new MediaRoute2Info.Builder(device.getAddress(), deviceName)
                .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
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
        if (btRoute.route.getConnectionState() == state) {
            return;
        }

        MediaRoute2Info.Builder builder = new MediaRoute2Info.Builder(btRoute.route)
                .setConnectionState(state);
        builder.setType(btRoute.getRouteType());

        if (state == MediaRoute2Info.CONNECTION_STATE_CONNECTED) {
            builder.setVolume(mVolumeMap.get(btRoute.getRouteType(), 0));
        }
        btRoute.route = builder.build();
    }

    interface BluetoothRoutesUpdatedListener {
        void onBluetoothRoutesUpdated(@NonNull List<MediaRoute2Info> routes);
    }

    private class BluetoothRouteInfo {
        public BluetoothDevice btDevice;
        public MediaRoute2Info route;
        public SparseBooleanArray connectedProfiles;

        @MediaRoute2Info.Type
        int getRouteType() {
            // Let hearing aid profile have a priority.
            if (connectedProfiles.get(BluetoothProfile.HEARING_AID, false)) {
                return MediaRoute2Info.TYPE_HEARING_AID;
            }
            return MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
        }
    }

    // These callbacks run on the main thread.
    private final class BluetoothProfileListener implements BluetoothProfile.ServiceListener {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            List<BluetoothDevice> activeDevices;
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mA2dpProfile = (BluetoothA2dp) proxy;
                    // It may contain null.
                    activeDevices = Collections.singletonList(mA2dpProfile.getActiveDevice());
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHearingAidProfile = (BluetoothHearingAid) proxy;
                    activeDevices = mHearingAidProfile.getActiveDevices();
                    break;
                default:
                    return;
            }
            //TODO(b/157708273): Handle two active devices in the binaural case.
            for (BluetoothDevice device : proxy.getConnectedDevices()) {
                BluetoothRouteInfo btRoute = mBluetoothRoutes.get(device.getAddress());
                if (btRoute == null) {
                    btRoute = createBluetoothRoute(device);
                    mBluetoothRoutes.put(device.getAddress(), btRoute);
                }
                if (activeDevices.contains(device)) {
                    mSelectedRoute = btRoute;
                    setRouteConnectionState(mSelectedRoute,
                            MediaRoute2Info.CONNECTION_STATE_CONNECTED);
                }
            }
            notifyBluetoothRoutesUpdated();
        }

        public void onServiceDisconnected(int profile) {
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mA2dpProfile = null;
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHearingAidProfile = null;
                    break;
                default:
                    return;
            }
        }
    }
    private class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            BluetoothEventReceiver receiver = mEventReceiverMap.get(action);
            if (receiver != null) {
                receiver.onReceive(context, intent, device);
            }
        }
    }

    private interface BluetoothEventReceiver {
        void onReceive(Context context, Intent intent, BluetoothDevice device);
    }

    private class AdapterStateChangedReceiver implements BluetoothEventReceiver {
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
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

    private class DeviceStateChangedRecevier implements BluetoothEventReceiver {
        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            switch (intent.getAction()) {
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                case BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED:
                    if (mSelectedRoute == null
                            || !mSelectedRoute.btDevice.equals(device)) {
                        if (mSelectedRoute != null) {
                            setRouteConnectionState(mSelectedRoute,
                                    MediaRoute2Info.CONNECTION_STATE_DISCONNECTED);
                        }
                        mSelectedRoute = (device == null) ? null
                                : mBluetoothRoutes.get(device.getAddress());
                        if (mSelectedRoute != null) {
                            setRouteConnectionState(mSelectedRoute,
                                    MediaRoute2Info.CONNECTION_STATE_CONNECTED);
                        }
                        notifyBluetoothRoutesUpdated();
                    }
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    handleConnectionStateChanged(BluetoothProfile.A2DP, intent, device);
                    break;
                case BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED:
                    handleConnectionStateChanged(BluetoothProfile.HEARING_AID, intent, device);
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
                    if (btRoute.connectedProfiles.size() > 0) {
                        mBluetoothRoutes.put(device.getAddress(), btRoute);
                        notifyBluetoothRoutesUpdated();
                    }
                } else {
                    btRoute.connectedProfiles.put(profile, true);
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTING
                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                if (btRoute != null) {
                    btRoute.connectedProfiles.delete(profile);
                    if (btRoute.connectedProfiles.size() == 0) {
                        mBluetoothRoutes.remove(device.getAddress());
                        if (mSelectedRoute != null && mSelectedRoute.btDevice.equals(device)) {
                            mSelectedRoute = null;
                        }
                        notifyBluetoothRoutesUpdated();
                    }
                }
            }
        }
    }
}
