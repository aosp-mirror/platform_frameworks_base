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
    private BluetoothMediaManager mBluetoothMediaManager;
    private InfoMediaManager mInfoMediaManager;
    private LocalBluetoothManager mLocalBluetoothManager;

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
        mLocalBluetoothManager =
                LocalBluetoothManager.getInstance(context, /* onInitCallback= */ null);
        if (mLocalBluetoothManager == null) {
            Log.e(TAG, "Bluetooth is not supported on this device");
            return;
        }

        mBluetoothMediaManager =
                new BluetoothMediaManager(context, mLocalBluetoothManager, notification);
        mInfoMediaManager = new InfoMediaManager(context, packageName, notification);
    }

    @VisibleForTesting
    LocalMediaManager(Context context, LocalBluetoothManager localBluetoothManager,
            BluetoothMediaManager bluetoothMediaManager, InfoMediaManager infoMediaManager) {
        mContext = context;
        mLocalBluetoothManager = localBluetoothManager;
        mBluetoothMediaManager = bluetoothMediaManager;
        mInfoMediaManager = infoMediaManager;
    }

    /**
     * Connect the MediaDevice to transfer media
     * @param connectDevice the MediaDevice
     */
    public void connectDevice(MediaDevice connectDevice) {
        final MediaDevice device = getMediaDeviceById(mMediaDevices, connectDevice.getId());
        if (device == mCurrentConnectedDevice) {
            Log.d(TAG, "connectDevice() this device all ready connected! : " + device.getName());
            return;
        }

        //TODO(b/121083246): Update it once remote media API is ready.
        if (mCurrentConnectedDevice != null && !(connectDevice instanceof InfoMediaDevice)) {
            mCurrentConnectedDevice.disconnect();
        }

        final boolean isConnected = device.connect();
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
        mBluetoothMediaManager.registerCallback(mMediaDeviceCallback);
        mInfoMediaManager.registerCallback(mMediaDeviceCallback);
        mBluetoothMediaManager.startScan();
        mInfoMediaManager.startScan();
    }

    private void addPhoneDeviceIfNecessary() {
        // add phone device to list if there have any Bluetooth device and cast device.
        if (mMediaDevices.size() > 0 && !mMediaDevices.contains(mPhoneDevice)) {
            if (mPhoneDevice == null) {
                mPhoneDevice = new PhoneMediaDevice(mContext, mLocalBluetoothManager);
            }
            mMediaDevices.add(mPhoneDevice);
        }
    }

    private void removePhoneMediaDeviceIfNecessary() {
        // if PhoneMediaDevice is the last item in the list, remove it.
        if (mMediaDevices.size() == 1 && mMediaDevices.contains(mPhoneDevice)) {
            mMediaDevices.clear();
        }
    }

    void dispatchDeviceListUpdate() {
        synchronized (mCallbacks) {
            Collections.sort(mMediaDevices, COMPARATOR);
            for (DeviceCallback callback : mCallbacks) {
                callback.onDeviceListUpdate(new ArrayList<>(mMediaDevices));
            }
        }
    }

    /**
     * Stop scan MediaDevice
     */
    public void stopScan() {
        mBluetoothMediaManager.unregisterCallback(mMediaDeviceCallback);
        mInfoMediaManager.unregisterCallback(mMediaDeviceCallback);
        mBluetoothMediaManager.stopScan();
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
     * Find the current connected MediaDevice.
     *
     * @return MediaDevice
     */
    public MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    private MediaDevice updateCurrentConnectedDevice() {
        for (MediaDevice device : mMediaDevices) {
            if (device instanceof  BluetoothMediaDevice) {
                if (isConnected(((BluetoothMediaDevice) device).getCachedDevice())) {
                    return device;
                }
            }
        }
        return mMediaDevices.contains(mPhoneDevice) ? mPhoneDevice : null;
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
                addPhoneDeviceIfNecessary();
                dispatchDeviceListUpdate();
            }
        }

        @Override
        public void onDeviceListAdded(List<MediaDevice> devices) {
            for (MediaDevice device : devices) {
                if (getMediaDeviceById(mMediaDevices, device.getId()) == null) {
                    mMediaDevices.add(device);
                }
            }
            addPhoneDeviceIfNecessary();
            mCurrentConnectedDevice = updateCurrentConnectedDevice();
            dispatchDeviceListUpdate();
        }

        @Override
        public void onDeviceRemoved(MediaDevice device) {
            if (mMediaDevices.contains(device)) {
                mMediaDevices.remove(device);
                removePhoneMediaDeviceIfNecessary();
                dispatchDeviceListUpdate();
            }
        }

        @Override
        public void onDeviceListRemoved(List<MediaDevice> devices) {
            mMediaDevices.removeAll(devices);
            removePhoneMediaDeviceIfNecessary();
            dispatchDeviceListUpdate();
        }

        @Override
        public void onDeviceAttributesChanged() {
            dispatchDeviceListUpdate();
        }

        @Override
        public void onConnectedDeviceChanged(String id) {
            final MediaDevice connectDevice = getMediaDeviceById(mMediaDevices, id);

            if (connectDevice == mCurrentConnectedDevice) {
                Log.d(TAG, "onConnectedDeviceChanged() this device all ready connected! : "
                        + connectDevice.getName());
                return;
            }
            mCurrentConnectedDevice = connectDevice;

            dispatchDeviceListUpdate();
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
        void onDeviceListUpdate(List<MediaDevice> devices);

        /**
         * Callback for notifying the connected device is changed.
         *
         * @param device the changed connected MediaDevice
         * @param state the current MediaDevice state, the possible values are:
         * {@link MediaDeviceState#STATE_CONNECTED},
         * {@link MediaDeviceState#STATE_CONNECTING},
         * {@link MediaDeviceState#STATE_DISCONNECTED}
         */
        void onSelectedDeviceStateChanged(MediaDevice device, @MediaDeviceState int state);
    }
}
