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
import android.media.MediaRoute2Info;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseBooleanArray;

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
    BluetoothA2dp mA2dpProfile;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    BluetoothHearingAid mHearingAidProfile;

    private final Context mContext;
    private final BluetoothAdapter mBluetoothAdapter;
    private final BluetoothRoutesUpdatedListener mListener;
    private final AudioManager mAudioManager;
    private final Map<String, BluetoothEventReceiver> mEventReceiverMap = new HashMap<>();
    private final IntentFilter mIntentFilter = new IntentFilter();
    private final BroadcastReceiver mBroadcastReceiver = new BluetoothBroadcastReceiver();
    private final BluetoothProfileListener mProfileListener = new BluetoothProfileListener();

    private BluetoothDevice mActiveDevice = null;

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

        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.A2DP);
        mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEARING_AID);

        // Bluetooth on/off broadcasts
        addEventReceiver(BluetoothAdapter.ACTION_STATE_CHANGED, new AdapterStateChangedReceiver());

        // Pairing broadcasts
        addEventReceiver(BluetoothDevice.ACTION_BOND_STATE_CHANGED, new BondStateChangedReceiver());

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
     * Clears the active device for all known profiles.
     */
    public void clearActiveDevices() {
        BluetoothA2dp a2dpProfile = mA2dpProfile;
        BluetoothHearingAid hearingAidProfile = mHearingAidProfile;
        if (a2dpProfile != null) {
            a2dpProfile.setActiveDevice(null);
        }
        if (hearingAidProfile != null) {
            hearingAidProfile.setActiveDevice(null);
        }
    }

    /**
     * Sets the active device.
     * @param deviceId the id of the Bluetooth device
     */
    public void setActiveDevice(@NonNull String deviceId) {
        BluetoothRouteInfo btRouteInfo = mBluetoothRoutes.get(deviceId);
        if (btRouteInfo == null) {
            Slog.w(TAG, "setActiveDevice: unknown device id=" + deviceId);
            return;
        }
        BluetoothA2dp a2dpProfile = mA2dpProfile;
        BluetoothHearingAid hearingAidProfile = mHearingAidProfile;

        if (a2dpProfile != null
                && btRouteInfo.connectedProfiles.get(BluetoothProfile.A2DP, false)) {
            a2dpProfile.setActiveDevice(btRouteInfo.btDevice);
        }
        if (hearingAidProfile != null
                && btRouteInfo.connectedProfiles.get(BluetoothProfile.HEARING_AID, false)) {
            hearingAidProfile.setActiveDevice(btRouteInfo.btDevice);
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
                mBluetoothRoutes.put(device.getAddress(), newBtRoute);
            }
        }
    }

    @NonNull List<MediaRoute2Info> getBluetoothRoutes() {
        ArrayList<MediaRoute2Info> routes = new ArrayList<>();
        for (BluetoothRouteInfo btRoute : mBluetoothRoutes.values()) {
            routes.add(btRoute.route);
        }
        return routes;
    }

    @Nullable String getActiveDeviceAddress() {
        BluetoothDevice device = mActiveDevice;
        if (device == null) {
            return null;
        }
        return device.getAddress();
    }

    private void notifyBluetoothRoutesUpdated() {
        if (mListener != null) {
            mListener.onBluetoothRoutesUpdated(getBluetoothRoutes());
        }
    }

    private BluetoothRouteInfo createBluetoothRoute(BluetoothDevice device) {
        BluetoothRouteInfo newBtRoute = new BluetoothRouteInfo();
        newBtRoute.btDevice = device;
        // Current / Max volume will be set when connected.
        // TODO: Is there any BT device which has fixed volume?
        newBtRoute.route = new MediaRoute2Info.Builder(device.getAddress(), device.getName())
                .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
                .setDescription(mContext.getResources().getText(
                        R.string.bluetooth_a2dp_audio_route_name).toString())
                .setDeviceType(MediaRoute2Info.DEVICE_TYPE_BLUETOOTH)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .build();
        newBtRoute.connectedProfiles = new SparseBooleanArray();
        return newBtRoute;
    }

    private void setRouteConnectionStateForDevice(BluetoothDevice device,
            @MediaRoute2Info.ConnectionState int state) {
        if (device == null) {
            Slog.w(TAG, "setRouteConnectionStateForDevice: device shouldn't be null");
            return;
        }
        BluetoothRouteInfo btRoute = mBluetoothRoutes.get(device.getAddress());
        if (btRoute == null) {
            Slog.w(TAG, "setRouteConnectionStateForDevice: route shouldn't be null");
            return;
        }
        if (btRoute.route.getConnectionState() == state) {
            return;
        }

        // Update volume when the connection state is changed.
        MediaRoute2Info.Builder builder = new MediaRoute2Info.Builder(btRoute.route)
                .setConnectionState(state);

        if (state == MediaRoute2Info.CONNECTION_STATE_CONNECTED) {
            int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            builder.setVolumeMax(maxVolume).setVolume(currentVolume);
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
            //TODO: Check a pair of HAP devices whether there exist two or more active devices.
            for (BluetoothDevice device : proxy.getConnectedDevices()) {
                BluetoothRouteInfo btRoute = mBluetoothRoutes.get(device.getAddress());
                if (btRoute == null) {
                    btRoute = createBluetoothRoute(device);
                    mBluetoothRoutes.put(device.getAddress(), btRoute);
                }
                if (activeDevices.contains(device)) {
                    mActiveDevice = device;
                    setRouteConnectionStateForDevice(device,
                            MediaRoute2Info.CONNECTION_STATE_CONNECTED);
                }

                btRoute.connectedProfiles.put(profile, true);
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

    private class BondStateChangedReceiver implements BluetoothEventReceiver {
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR);
            BluetoothRouteInfo btRoute = mBluetoothRoutes.get(device.getAddress());
            if (bondState == BluetoothDevice.BOND_BONDED && btRoute == null) {
                btRoute = createBluetoothRoute(device);
                if (mA2dpProfile != null && mA2dpProfile.getConnectedDevices().contains(device)) {
                    btRoute.connectedProfiles.put(BluetoothProfile.A2DP, true);
                }
                if (mHearingAidProfile != null
                        && mHearingAidProfile.getConnectedDevices().contains(device)) {
                    btRoute.connectedProfiles.put(BluetoothProfile.HEARING_AID, true);
                }
                mBluetoothRoutes.put(device.getAddress(), btRoute);
                notifyBluetoothRoutesUpdated();
            } else if (bondState == BluetoothDevice.BOND_NONE
                    && mBluetoothRoutes.remove(device.getAddress()) != null) {
                notifyBluetoothRoutesUpdated();
            }
        }
    }

    private class DeviceStateChangedRecevier implements BluetoothEventReceiver {
        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            switch (intent.getAction()) {
                case BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED:
                    String prevActiveDeviceAddress =
                            (mActiveDevice == null) ? null : mActiveDevice.getAddress();
                    String curActiveDeviceAddress =
                            (device == null) ? null : device.getAddress();
                    if (!TextUtils.equals(prevActiveDeviceAddress, curActiveDeviceAddress)) {
                        if (mActiveDevice != null) {
                            setRouteConnectionStateForDevice(mActiveDevice,
                                    MediaRoute2Info.CONNECTION_STATE_DISCONNECTED);
                        }
                        if (device != null) {
                            setRouteConnectionStateForDevice(device,
                                    MediaRoute2Info.CONNECTION_STATE_CONNECTED);
                        }
                        mActiveDevice = device;
                        notifyBluetoothRoutesUpdated();
                    }
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    handleConnectionStateChanged(BluetoothProfile.A2DP, intent, device);
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
                    mBluetoothRoutes.put(device.getAddress(), btRoute);
                    btRoute.connectedProfiles.put(profile, true);
                    notifyBluetoothRoutesUpdated();
                } else {
                    btRoute.connectedProfiles.put(profile, true);
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTING
                    || state == BluetoothProfile.STATE_DISCONNECTED) {
                if (btRoute != null) {
                    btRoute.connectedProfiles.delete(profile);
                    if (btRoute.connectedProfiles.size() == 0) {
                        mBluetoothRoutes.remove(device.getAddress());
                        if (mActiveDevice != null
                                && TextUtils.equals(mActiveDevice.getAddress(),
                                device.getAddress())) {
                            mActiveDevice = null;
                        }
                        notifyBluetoothRoutesUpdated();
                    }
                }
            }
        }
    }
}
