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

import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_USB_ACCESSORY;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_USB_HEADSET;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

/**
 * PhoneMediaDevice extends MediaDevice to represents Phone device.
 */
public class PhoneMediaDevice extends MediaDevice {

    private static final String TAG = "PhoneMediaDevice";

    public static final String PHONE_ID = "phone_media_device_id";
    // For 3.5 mm wired headset
    public static final String WIRED_HEADSET_ID = "wired_headset_media_device_id";
    public static final String USB_HEADSET_ID = "usb_headset_media_device_id";

    private String mSummary = "";

    private final DeviceIconUtil mDeviceIconUtil;

    PhoneMediaDevice(Context context, MediaRouter2Manager routerManager, MediaRoute2Info info,
            String packageName) {
        super(context, routerManager, info, packageName, null);
        mDeviceIconUtil = new DeviceIconUtil();
        initDeviceRecord();
    }

    @Override
    public String getName() {
        CharSequence name;
        switch (mRouteInfo.getType()) {
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
                name = mContext.getString(R.string.media_transfer_wired_usb_device_name);
                break;
            case TYPE_DOCK:
            case TYPE_HDMI:
                name = mRouteInfo.getName();
                break;
            case TYPE_BUILTIN_SPEAKER:
            default:
                name = mContext.getString(R.string.media_transfer_this_device_name);
                break;
        }
        return name.toString();
    }

    @Override
    public String getSummary() {
        return mSummary;
    }

    @Override
    public Drawable getIcon() {
        return getIconWithoutBackground();
    }

    @Override
    public Drawable getIconWithoutBackground() {
        return mContext.getDrawable(getDrawableResId());
    }

    @VisibleForTesting
    int getDrawableResId() {
        return mDeviceIconUtil.getIconResIdFromMediaRouteType(mRouteInfo.getType());
    }

    @Override
    public String getId() {
        String id;
        switch (mRouteInfo.getType()) {
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                id = WIRED_HEADSET_ID;
                break;
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
            case TYPE_DOCK:
            case TYPE_HDMI:
                id = USB_HEADSET_ID;
                break;
            case TYPE_BUILTIN_SPEAKER:
            default:
                id = PHONE_ID;
                break;
        }
        return id;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    /**
     * According current active device is {@link PhoneMediaDevice} or not to update summary.
     */
    public void updateSummary(boolean isActive) {
        mSummary = isActive
                ? mContext.getString(R.string.bluetooth_active_no_battery_level)
                : "";
    }
}
