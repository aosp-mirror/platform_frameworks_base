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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MediaManager provide interface to get MediaDevice list.
 */
public abstract class MediaManager {

    private static final String TAG = "MediaManager";

    protected final Collection<MediaDeviceCallback> mCallbacks = new CopyOnWriteArrayList<>();
    protected final List<MediaDevice> mMediaDevices = new ArrayList<>();

    protected Context mContext;
    protected Notification mNotification;

    MediaManager(Context context, Notification notification) {
        mContext = context;
        mNotification = notification;
    }

    protected void registerCallback(MediaDeviceCallback callback) {
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
        }
    }

    protected void unregisterCallback(MediaDeviceCallback callback) {
        if (mCallbacks.contains(callback)) {
            mCallbacks.remove(callback);
        }
    }

    /**
     * Start scan connected MediaDevice
     */
    public abstract void startScan();

    /**
     * Stop scan MediaDevice
     */
    public abstract void stopScan();

    protected MediaDevice findMediaDevice(String id) {
        for (MediaDevice mediaDevice : mMediaDevices) {
            if (mediaDevice.getId().equals(id)) {
                return mediaDevice;
            }
        }
        Log.e(TAG, "findMediaDevice() can't found device");
        return null;
    }

    protected void dispatchDeviceAdded(MediaDevice mediaDevice) {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onDeviceAdded(mediaDevice);
        }
    }

    protected void dispatchDeviceRemoved(MediaDevice mediaDevice) {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onDeviceRemoved(mediaDevice);
        }
    }

    protected void dispatchDeviceListAdded() {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onDeviceListAdded(new ArrayList<>(mMediaDevices));
        }
    }

    protected void dispatchDeviceListRemoved(List<MediaDevice> devices) {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onDeviceListRemoved(devices);
        }
    }

    protected void dispatchConnectedDeviceChanged(String id) {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onConnectedDeviceChanged(id);
        }
    }

    protected void dispatchDataChanged() {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onDeviceAttributesChanged();
        }
    }

    protected void dispatchOnRequestFailed(int reason) {
        for (MediaDeviceCallback callback : getCallbacks()) {
            callback.onRequestFailed(reason);
        }
    }

    private Collection<MediaDeviceCallback> getCallbacks() {
        return new CopyOnWriteArrayList<>(mCallbacks);
    }

    /**
     * Callback for notifying device is added, removed and attributes changed.
     */
    public interface MediaDeviceCallback {
        /**
         * Callback for notifying MediaDevice is added.
         *
         * @param device the MediaDevice
         */
        void onDeviceAdded(MediaDevice device);

        /**
         * Callback for notifying MediaDevice list is added.
         *
         * @param devices the MediaDevice list
         */
        void onDeviceListAdded(List<MediaDevice> devices);

        /**
         * Callback for notifying MediaDevice is removed.
         *
         * @param device the MediaDevice
         */
        void onDeviceRemoved(MediaDevice device);

        /**
         * Callback for notifying MediaDevice list is removed.
         *
         * @param devices the MediaDevice list
         */
        void onDeviceListRemoved(List<MediaDevice> devices);

        /**
         * Callback for notifying connected MediaDevice is changed.
         *
         * @param id the id of MediaDevice
         */
        void onConnectedDeviceChanged(String id);

        /**
         * Callback for notifying that MediaDevice attributes
         * (e.g: device name, connection state, subtitle) is changed.
         */
        void onDeviceAttributesChanged();

        /**
         * Callback for notifying that transferring is failed.
         *
         * @param reason the reason that the request has failed. Can be one of followings:
         * {@link android.media.MediaRoute2ProviderService#REASON_UNKNOWN_ERROR},
         * {@link android.media.MediaRoute2ProviderService#REASON_REJECTED},
         * {@link android.media.MediaRoute2ProviderService#REASON_NETWORK_ERROR},
         * {@link android.media.MediaRoute2ProviderService#REASON_ROUTE_NOT_AVAILABLE},
         * {@link android.media.MediaRoute2ProviderService#REASON_INVALID_COMMAND},
         */
        void onRequestFailed(int reason);
    }
}
