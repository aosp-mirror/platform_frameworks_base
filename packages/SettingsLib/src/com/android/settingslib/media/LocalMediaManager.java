/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.app.Notification;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * LocalMediaManager provide interface to get MediaDevice list and transfer media to MediaDevice.
 */
public class LocalMediaManager implements BluetoothCallback {
    private static final Comparator<MediaDevice> COMPARATOR = Comparator.naturalOrder();
    private static final String TAG = "LocalMediaManager";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MediaDeviceState.STATE_CONNECTED,
            MediaDeviceState.STATE_CONNECTING,
            MediaDeviceState.STATE_DISCONNECTED})
    public @interface MediaDeviceState {
        int STATE_CONNECTED = 1;
        int STATE_CONNECTING = 2;
        int STATE_DISCONNECTED = 3;
    }

    private final Collection<DeviceCallback> mCallbacks = new ArrayList<>();
    @VisibleForTesting
    final MediaDeviceCallback mMediaDeviceCallback = new MediaDeviceCallback();

    private Context mContext;
    private LocalBluetoothManager mLocalBluetoothManager;
    private InfoMediaManager mInfoMediaManager;
    private String mPackageName;

    @VisibleForTesting
    List<MediaDevice> mMediaDevices = new ArrayList<>();
    @VisibleForTesting
    MediaDevice mPhoneDevice;
    @VisibleForTesting
    MediaDevice mCurrentConnectedDevice;

    /**
     * Register to start receiving callbacks for MediaDevice events.
     */
    public void registerCallback(DeviceCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    /**
     * Unregister to stop receiving callbacks for MediaDevice events
     */
    public void unregisterCallback(DeviceCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    public LocalMediaManager(Context context, String packageName, Notification notification) {
        mContext = context;
        mPackageName = packageName;
        mLocalBluetoothManager =
                LocalBluetoothManager.getInstance(context, /* onInitCallback= */ null);
        if (mLocalBluetoothManager == null) {
            Log.e(TAG, "Bluetooth is not supported on this device");
            return;
        }

        mInfoMediaManager =
                new InfoMediaManager(context, packageName, notification, mLocalBluetoothManager);
    }

    @VisibleForTesting
    LocalMediaManager(Context context, LocalBluetoothManager localBluetoothManager,
            InfoMediaManager infoMediaManager, String packageName) {
        mContext = context;
        mLocalBluetoothManager = localBluetoothManager;
        mInfoMediaManager = infoMediaManager;
        mPackageName = packageName;

    }

    /**
     * Connect the MediaDevice to transfer media
     * @param connectDevice the MediaDevice
     */
    public void connectDevice(MediaDevice connectDevice) {
        final MediaDevice device = getMediaDeviceById(mMediaDevices, connectDevice.getId());
        if (device instanceof BluetoothMediaDevice) {
            final CachedBluetoothDevice cachedDevice =
                    ((BluetoothMediaDevice) device).getCachedDevice();
            if (!cachedDevice.isConnected() && !cachedDevice.isBusy()) {
                cachedDevice.connect();
                return;
            }
        }

        if (device == mCurrentConnectedDevice) {
            Log.d(TAG, "connectDevice() this device all ready connected! : " + device.getName());
            return;
        }

        if (mCurrentConnectedDevice != null) {
            mCurrentConnectedDevice.disconnect();
        }

        boolean isConnected = false;
        if (TextUtils.isEmpty(mPackageName)) {
            isConnected = mInfoMediaManager.connectDeviceWithoutPackageName(device);
        } else {
            isConnected = device.connect();
        }
        if (isConnected) {
            mCurrentConnectedDevice = device;
        }

        final int state = isConnected
                ? MediaDeviceState.STATE_CONNECTED
                : MediaDeviceState.STATE_DISCONNECTED;
        dispatchSelectedDeviceStateChanged(device, state);
    }

    void dispatchSelectedDeviceStateChanged(MediaDevice device, @MediaDeviceState int state) {
        synchronized (mCallbacks) {
            for (DeviceCallback callback : mCallbacks) {
                callback.onSelectedDeviceStateChanged(device, state);
            }
        }
    }

    /**
     * Start scan connected MediaDevice
     */
    public void startScan() {
        mMediaDevices.clear();
        mInfoMediaManager.registerCallback(mMediaDeviceCallback);
        mInfoMediaManager.startScan();
    }

    void dispatchDeviceListUpdate() {
        synchronized (mCallbacks) {
            Collections.sort(mMediaDevices, COMPARATOR);
            for (DeviceCallback callback : mCallbacks) {
                callback.onDeviceListUpdate(new ArrayList<>(mMediaDevices));
            }
        }
    }

    void dispatchDeviceAttributesChanged() {
        synchronized (mCallbacks) {
            for (DeviceCallback callback : mCallbacks) {
                callback.onDeviceAttributesChanged();
            }
        }
    }

    /**
     * Stop scan MediaDevice
     */
    public void stopScan() {
        mInfoMediaManager.unregisterCallback(mMediaDeviceCallback);
        mInfoMediaManager.stopScan();
    }

    /**
     * Find the MediaDevice through id.
     *
     * @param devices the list of MediaDevice
     * @param id the unique id of MediaDevice
     * @return MediaDevice
     */
    public MediaDevice getMediaDeviceById(List<MediaDevice> devices, String id) {
        for (MediaDevice mediaDevice : devices) {
            if (mediaDevice.getId().equals(id)) {
                return mediaDevice;
            }
        }
        Log.i(TAG, "getMediaDeviceById() can't found device");
        return null;
    }

    /**
     * Find the MediaDevice from all media devices by id.
     *
     * @param id the unique id of MediaDevice
     * @return MediaDevice
     */
    public MediaDevice getMediaDeviceById(String id) {
        for (MediaDevice mediaDevice : mMediaDevices) {
            if (mediaDevice.getId().equals(id)) {
                return mediaDevice;
            }
        }
        Log.i(TAG, "Unable to find device " + id);
        return null;
    }

    /**
     * Find the current connected MediaDevice.
     *
     * @return MediaDevice
     */
    public MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    /**
     * Find the active MediaDevice.
     *
     * @param type the media device type.
     * @return MediaDevice list
     */
    public List<MediaDevice> getActiveMediaDevice(@MediaDevice.MediaDeviceType int type) {
        final List<MediaDevice> devices = new ArrayList<>();
        for (MediaDevice device : mMediaDevices) {
            if (type == device.mType && device.getClientPackageName() != null) {
                devices.add(device);
            }
        }
        return devices;
    }

    private MediaDevice updateCurrentConnectedDevice() {
        MediaDevice phoneMediaDevice = null;
        for (MediaDevice device : mMediaDevices) {
            if (device instanceof  BluetoothMediaDevice) {
                if (isConnected(((BluetoothMediaDevice) device).getCachedDevice())) {
                    return device;
                }
            } else if (device instanceof PhoneMediaDevice) {
                phoneMediaDevice = device;
            }
        }
        return mMediaDevices.contains(phoneMediaDevice) ? phoneMediaDevice : null;
    }

    private boolean isConnected(CachedBluetoothDevice device) {
        return device.isActiveDevice(BluetoothProfile.A2DP)
                || device.isActiveDevice(BluetoothProfile.HEARING_AID);
    }

    class MediaDeviceCallback implements MediaManager.MediaDeviceCallback {
        @Override
        public void onDeviceAdded(MediaDevice device) {
            if (!mMediaDevices.contains(device)) {
                mMediaDevices.add(device);
                dispatchDeviceListUpdate();
            }
        }

        @Override
        public void onDeviceListAdded(List<MediaDevice> devices) {
            mMediaDevices.clear();
            mMediaDevices.addAll(devices);
            final MediaDevice infoMediaDevice = mInfoMediaManager.getCurrentConnectedDevice();
            mCurrentConnectedDevice = infoMediaDevice != null
                    ? infoMediaDevice : updateCurrentConnectedDevice();
            dispatchDeviceListUpdate();
        }

        @Override
        public void onDeviceRemoved(MediaDevice device) {
            if (mMediaDevices.contains(device)) {
                mMediaDevices.remove(device);
                dispatchDeviceListUpdate();
            }
        }

        @Override
        public void onDeviceListRemoved(List<MediaDevice> devices) {
            mMediaDevices.removeAll(devices);
            dispatchDeviceListUpdate();
        }

        @Override
        public void onConnectedDeviceChanged(String id) {
            final MediaDevice connectDevice = getMediaDeviceById(mMediaDevices, id);

            if (connectDevice == mCurrentConnectedDevice) {
                Log.d(TAG, "onConnectedDeviceChanged() this device all ready connected!");
                return;
            }
            mCurrentConnectedDevice = connectDevice;
            dispatchDeviceAttributesChanged();
        }

        @Override
        public void onDeviceAttributesChanged() {
            dispatchDeviceAttributesChanged();
        }
    }


    /**
     * Callback for notifying device information updating
     */
    public interface DeviceCallback {
        /**
         * Callback for notifying device list updated.
         *
         * @param devices MediaDevice list
         */
        default void onDeviceListUpdate(List<MediaDevice> devices) {};

        /**
         * Callback for notifying the connected device is changed.
         *
         * @param device the changed connected MediaDevice
         * @param state the current MediaDevice state, the possible values are:
         * {@link MediaDeviceState#STATE_CONNECTED},
         * {@link MediaDeviceState#STATE_CONNECTING},
         * {@link MediaDeviceState#STATE_DISCONNECTED}
         */
        default void onSelectedDeviceStateChanged(MediaDevice device,
                @MediaDeviceState int state) {};

        /**
         * Callback for notifying the device attributes is changed.
         */
        default void onDeviceAttributesChanged() {};
    }
}
