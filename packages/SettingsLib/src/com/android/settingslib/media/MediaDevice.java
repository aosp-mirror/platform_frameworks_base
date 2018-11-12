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

import android.content.Context;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * MediaDevice represents a media device(such like Bluetooth device, cast device and phone device).
 */
public abstract class MediaDevice {

    private static final String TAG = "MediaDevice";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MediaDeviceType.TYPE_BLUETOOTH_DEVICE,
            MediaDeviceType.TYPE_CAST_DEVICE,
            MediaDeviceType.TYPE_PHONE_DEVICE})
    public @interface MediaDeviceType {
        int TYPE_BLUETOOTH_DEVICE = 1;
        int TYPE_CAST_DEVICE = 2;
        int TYPE_PHONE_DEVICE = 3;
    }

    protected boolean mIsConnected = false;
    protected Context mContext;
    protected int mType;

    MediaDevice(Context context, @MediaDeviceType int type) {
        mType = type;
        mContext = context;
    }

    /**
     * Check the MediaDevice is be connected to transfer.
     *
     * @return true if the MediaDevice is be connected to transfer, false otherwise.
     */
    protected boolean isConnected() {
        return mIsConnected;
    }

    /**
     * Get name from MediaDevice.
     *
     * @return name of MediaDevice.
     */
    public abstract String getName();

    /**
     * Get resource id of MediaDevice.
     *
     * @return resource id of MediaDevice.
     */
    public abstract int getIcon();

    /**
     * Get unique ID that represent MediaDevice
     * @return unique id of MediaDevice
     */
    public abstract String getId();

    /**
     * Transfer MediaDevice for media
     */
    public abstract void connect();

    /**
     * Stop transfer MediaDevice
     */
    public abstract void disconnect();
}
