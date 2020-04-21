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
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;

/**
 * PhoneMediaDevice extends MediaDevice to represents Phone device.
 */
public class PhoneMediaDevice extends MediaDevice {

    private static final String TAG = "PhoneMediaDevice";

    public static final String ID = "phone_media_device_id_1";

    private String mSummary = "";

    PhoneMediaDevice(Context context, MediaRouter2Manager routerManager, MediaRoute2Info info,
            String packageName) {
        super(context, routerManager, info, packageName);

        initDeviceRecord();
    }

    @Override
    public String getName() {
        CharSequence name;
        switch (mRouteInfo.getType()) {
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
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
        return BluetoothUtils.buildBtRainbowDrawable(mContext,
                mContext.getDrawable(getDrawableResId()), getId().hashCode());
    }

    @VisibleForTesting
    int getDrawableResId() {
        int resId;
        switch (mRouteInfo.getType()) {
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                resId = com.android.internal.R.drawable.ic_bt_headphones_a2dp;
                break;
            case TYPE_BUILTIN_SPEAKER:
            default:
                resId = R.drawable.ic_smartphone;
                break;
        }
        return resId;
    }

    @Override
    public String getId() {
        return ID;
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
