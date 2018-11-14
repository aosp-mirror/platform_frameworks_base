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
import android.content.Context;
import android.util.Log;

import androidx.annotation.IntDef;

import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * LocalMediaManager provide interface to get MediaDevice list and transfer media to MediaDevice.
 */
public class LocalMediaManager implements BluetoothCallback {

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
    private final MediaDeviceCallback mMediaDeviceCallback = new MediaDeviceCallback();

    private Context mContext;
    private List<MediaDevice> mMediaDevices = new ArrayList<>();
    private BluetoothMediaManager mBluetoothMediaManager;
    private InfoMediaManager mInfoMediaManager;

    private LocalBluetoothManager mLocalBluetoothManager;
    private MediaDevice mLastConnectedDevice;
    private MediaDevice mPhoneDevice;

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

    /**
     * Connect the MediaDevice to transfer media
     * @param connectDevice the MediaDevice
     */
    public void connectDevice(MediaDevice connectDevice) {
        if (connectDevice == mLastConnectedDevice) {
            return;
        }

        if (mLastConnectedDevice != null) {
            mLastConnectedDevice.disconnect();
        }

        connectDevice.connect();
        if (connectDevice.isConnected()) {
            mLastConnectedDevice = connectDevice;
        }

        final int state = connectDevice.isConnected()
                ? MediaDeviceState.STATE_CONNECTED
                : MediaDeviceState.STATE_DISCONNECTED;
        dispatchSelectedDeviceStateChanged(connectDevice, state);
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
            mMediaDevices.addAll(devices);
            addPhoneDeviceIfNecessary();
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
