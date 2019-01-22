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

/**
 * MediaManager provide interface to get MediaDevice list.
 */
public abstract class MediaManager {

    private static final String TAG = "MediaManager";

    protected final Collection<MediaDeviceCallback> mCallbacks = new ArrayList<>();
    protected final List<MediaDevice> mMediaDevices = new ArrayList<>();

    protected Context mContext;
    protected Notification mNotification;

    MediaManager(Context context, Notification notification) {
        mContext = context;
        mNotification = notification;
    }

    protected void registerCallback(MediaDeviceCallback callback) {
        synchronized (mCallbacks) {
            if (!mCallbacks.contains(callback)) {
                mCallbacks.add(callback);
            }
        }
    }

    protected void unregisterCallback(MediaDeviceCallback callback) {
        synchronized (mCallbacks) {
            if (mCallbacks.contains(callback)) {
                mCallbacks.remove(callback);
            }
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
        synchronized (mCallbacks) {
            for (MediaDeviceCallback callback : mCallbacks) {
                callback.onDeviceAdded(mediaDevice);
            }
        }
    }

    protected void dispatchDeviceRemoved(MediaDevice mediaDevice) {
        synchronized (mCallbacks) {
            for (MediaDeviceCallback callback : mCallbacks) {
                callback.onDeviceRemoved(mediaDevice);
            }
        }
    }

    protected void dispatchDeviceListAdded() {
        synchronized (mCallbacks) {
            for (MediaDeviceCallback callback : mCallbacks) {
                callback.onDeviceListAdded(new ArrayList<>(mMediaDevices));
            }
        }
    }

    protected void dispatchDeviceListRemoved(List<MediaDevice> devices) {
        synchronized (mCallbacks) {
            for (MediaDeviceCallback callback : mCallbacks) {
                callback.onDeviceListRemoved(devices);
            }
        }
    }

    protected void dispatchConnectedDeviceChanged(String id) {
        synchronized (mCallbacks) {
            for (MediaDeviceCallback callback : mCallbacks) {
                callback.onConnectedDeviceChanged(id);
            }
        }
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
         * Callback for notifying MediaDevice attributes is changed.
         */
        void onDeviceAttributesChanged();

        /**
         * Callback for notifying connected MediaDevice is changed.
         *
         * @param id the id of MediaDevice
         */
        void onConnectedDeviceChanged(String id);
    }
}
