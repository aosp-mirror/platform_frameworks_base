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
import android.text.TextUtils;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * MediaDevice represents a media device(such like Bluetooth device, cast device and phone device).
 */
public abstract class MediaDevice implements Comparable<MediaDevice> {
    private static final String TAG = "MediaDevice";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MediaDeviceType.TYPE_CAST_DEVICE,
            MediaDeviceType.TYPE_BLUETOOTH_DEVICE,
            MediaDeviceType.TYPE_PHONE_DEVICE})
    public @interface MediaDeviceType {
        int TYPE_CAST_DEVICE = 1;
        int TYPE_BLUETOOTH_DEVICE = 2;
        int TYPE_PHONE_DEVICE = 3;
    }

    private int mConnectedRecord;

    protected Context mContext;
    protected int mType;

    MediaDevice(Context context, @MediaDeviceType int type) {
        mType = type;
        mContext = context;
    }

    void initDeviceRecord() {
        ConnectionRecordManager.getInstance().fetchLastSelectedDevice(mContext);
        mConnectedRecord = ConnectionRecordManager.getInstance().fetchConnectionRecord(mContext,
                getId());
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
     *
     * @return result of transfer media
     */
    public abstract boolean connect();

    void setConnectedRecord() {
        mConnectedRecord++;
        ConnectionRecordManager.getInstance().setConnectionRecord(mContext, getId(),
                mConnectedRecord);
    }

    /**
     * Stop transfer MediaDevice
     */
    public abstract void disconnect();

    /**
     * Rules:
     * 1. If there is one of the connected devices identified as a carkit, this carkit will
     * be always on the top of the device list. Rule 2 and Rule 3 can’t overrule this rule.
     * 2. For devices without any usage data yet
     * WiFi device group sorted by alphabetical order + BT device group sorted by alphabetical
     * order + phone speaker
     * 3. For devices with usage record.
     * The most recent used one + device group with usage info sorted by how many times the
     * device has been used.
     *
     * So the device list will look like 4 slots ranked as below.
     * Rule 1 + the most recently used device + Rule 3 + Rule 2
     * Any slot could be empty. And available device will belong to one of the slots.
     *
     * @return a negative integer, zero, or a positive integer
     * as this object is less than, equal to, or greater than the specified object.
     */
    @Override
    public int compareTo(MediaDevice another) {
        // Check carkit
        if (isCarKitDevice()) {
            return -1;
        } else if (another.isCarKitDevice()) {
            return 1;
        }
        // Set last used device at the first item
        String lastSelectedDevice = ConnectionRecordManager.getInstance().getLastSelectedDevice();
        if (TextUtils.equals(lastSelectedDevice, getId())) {
            return -1;
        } else if (TextUtils.equals(lastSelectedDevice, another.getId())) {
            return 1;
        }
        // Sort by how many times the device has been used if there is usage record
        if ((mConnectedRecord != another.mConnectedRecord)
                && (another.mConnectedRecord > 0 || mConnectedRecord > 0)) {
            return (another.mConnectedRecord - mConnectedRecord);
        }
        // Both devices have never been used
        // To devices with the same type, sort by alphabetical order
        if (mType == another.mType) {
            final String s1 = getName();
            final String s2 = another.getName();
            return s1.compareToIgnoreCase(s2);
        }
        // Both devices have never been used, the priority is Cast > Bluetooth > Phone
        return mType - another.mType;
    }

    /**
     * Check if it is CarKit device
     * @return true if it is CarKit device
     */
    protected boolean isCarKitDevice() {
        return false;
    }
}
