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

import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_GROUP;
import static android.media.MediaRoute2Info.TYPE_HEARING_AID;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;
import static android.media.MediaRoute2Info.TYPE_UNKNOWN;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * MediaDevice represents a media device(such like Bluetooth device, cast device and phone device).
 */
public abstract class MediaDevice implements Comparable<MediaDevice> {
    private static final String TAG = "MediaDevice";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE,
            MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE,
            MediaDeviceType.TYPE_FAST_PAIR_BLUETOOTH_DEVICE,
            MediaDeviceType.TYPE_BLUETOOTH_DEVICE,
            MediaDeviceType.TYPE_CAST_DEVICE,
            MediaDeviceType.TYPE_CAST_GROUP_DEVICE,
            MediaDeviceType.TYPE_PHONE_DEVICE})
    public @interface MediaDeviceType {
        int TYPE_USB_C_AUDIO_DEVICE = 1;
        int TYPE_3POINT5_MM_AUDIO_DEVICE = 2;
        int TYPE_FAST_PAIR_BLUETOOTH_DEVICE = 3;
        int TYPE_BLUETOOTH_DEVICE = 4;
        int TYPE_CAST_DEVICE = 5;
        int TYPE_CAST_GROUP_DEVICE = 6;
        int TYPE_PHONE_DEVICE = 7;
    }

    @VisibleForTesting
    int mType;

    private int mConnectedRecord;
    private int mState;

    protected final Context mContext;
    protected final MediaRoute2Info mRouteInfo;
    protected final MediaRouter2Manager mRouterManager;
    protected final String mPackageName;

    MediaDevice(Context context, MediaRouter2Manager routerManager, MediaRoute2Info info,
            String packageName) {
        mContext = context;
        mRouteInfo = info;
        mRouterManager = routerManager;
        mPackageName = packageName;
        setType(info);
    }

    private void setType(MediaRoute2Info info) {
        if (info == null) {
            mType = MediaDeviceType.TYPE_BLUETOOTH_DEVICE;
            return;
        }

        switch (info.getType()) {
            case TYPE_GROUP:
                mType = MediaDeviceType.TYPE_CAST_GROUP_DEVICE;
                break;
            case TYPE_BUILTIN_SPEAKER:
                mType = MediaDeviceType.TYPE_PHONE_DEVICE;
                break;
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                mType = MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE;
                break;
            case TYPE_HEARING_AID:
            case TYPE_BLUETOOTH_A2DP:
                mType = MediaDeviceType.TYPE_BLUETOOTH_DEVICE;
                break;
            case TYPE_UNKNOWN:
            case TYPE_REMOTE_TV:
            case TYPE_REMOTE_SPEAKER:
            default:
                mType = MediaDeviceType.TYPE_CAST_DEVICE;
                break;
        }
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
     * Get summary from MediaDevice.
     *
     * @return summary of MediaDevice.
     */
    public abstract String getSummary();

    /**
     * Get icon of MediaDevice.
     *
     * @return drawable of icon.
     */
    public abstract Drawable getIcon();

    /**
     * Get unique ID that represent MediaDevice
     * @return unique id of MediaDevice
     */
    public abstract String getId();

    void setConnectedRecord() {
        mConnectedRecord++;
        ConnectionRecordManager.getInstance().setConnectionRecord(mContext, getId(),
                mConnectedRecord);
    }

    /**
     * According the MediaDevice type to check whether we are connected to this MediaDevice.
     *
     * @return Whether it is connected.
     */
    public abstract boolean isConnected();

    /**
     * Request to set volume.
     *
     * @param volume is the new value.
     */

    public void requestSetVolume(int volume) {
        mRouterManager.requestSetVolume(mRouteInfo, volume);
    }

    /**
     * Get max volume from MediaDevice.
     *
     * @return max volume.
     */
    public int getMaxVolume() {
        return mRouteInfo.getVolumeMax();
    }

    /**
     * Get current volume from MediaDevice.
     *
     * @return current volume.
     */
    public int getCurrentVolume() {
        return mRouteInfo.getVolume();
    }

    /**
     * Get application package name.
     *
     * @return package name.
     */
    public String getClientPackageName() {
        return mRouteInfo.getClientPackageName();
    }

    /**
     * Get application label from MediaDevice.
     *
     * @return application label.
     */
    public String getClientAppLabel() {
        final String packageName = mRouteInfo.getClientPackageName();
        if (TextUtils.isEmpty(packageName)) {
            Log.d(TAG, "Client package name is empty");
            return mContext.getResources().getString(R.string.unknown);
        }
        try {
            final PackageManager packageManager = mContext.getPackageManager();
            final String appLabel = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)).toString();
            if (!TextUtils.isEmpty(appLabel)) {
                return appLabel;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "unable to find " + packageName);
        }
        return mContext.getResources().getString(R.string.unknown);
    }

    /**
     * Get application label from MediaDevice.
     *
     * @return application label.
     */
    public int getDeviceType() {
        return mType;
    }

    /**
     * Transfer MediaDevice for media
     *
     * @return result of transfer media
     */
    public boolean connect() {
        setConnectedRecord();
        mRouterManager.selectRoute(mPackageName, mRouteInfo);
        return true;
    }

    /**
     * Stop transfer MediaDevice
     */
    public void disconnect() {
    }

    /**
     * Set current device's state
     */
    public void setState(@LocalMediaManager.MediaDeviceState int state) {
        mState = state;
    }

    /**
     * Get current device's state
     *
     * @return state of device
     */
    public @LocalMediaManager.MediaDeviceState int getState() {
        return mState;
    }

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
     * 4. Phone device always in the top and the connected Bluetooth devices, cast devices and
     * phone device will be always above on the disconnect Bluetooth devices.
     *
     * So the device list will look like 5 slots ranked as below.
     * Rule 4 + Rule 1 + the most recently used device + Rule 3 + Rule 2
     * Any slot could be empty. And available device will belong to one of the slots.
     *
     * @return a negative integer, zero, or a positive integer
     * as this object is less than, equal to, or greater than the specified object.
     */
    @Override
    public int compareTo(MediaDevice another) {
        // Check Bluetooth device is have same connection state
        if (isConnected() ^ another.isConnected()) {
            if (isConnected()) {
                return -1;
            } else {
                return 1;
            }
        }

        // Phone device always in the top.
        if (mType == MediaDeviceType.TYPE_PHONE_DEVICE) {
            return -1;
        } else if (another.mType == MediaDeviceType.TYPE_PHONE_DEVICE) {
            return 1;
        }
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
        // Both devices have never been used, the priority is Phone > Cast > Bluetooth
        return mType - another.mType;
    }

    /**
     * Check if it is CarKit device
     * @return true if it is CarKit device
     */
    protected boolean isCarKitDevice() {
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MediaDevice)) {
            return false;
        }
        final MediaDevice otherDevice = (MediaDevice) obj;
        return otherDevice.getId().equals(getId());
    }
}
