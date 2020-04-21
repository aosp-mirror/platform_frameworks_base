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

import static android.media.MediaRoute2Info.TYPE_GROUP;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;

/**
 * InfoMediaDevice extends MediaDevice to represents wifi device.
 */
public class InfoMediaDevice extends MediaDevice {

    private static final String TAG = "InfoMediaDevice";

    InfoMediaDevice(Context context, MediaRouter2Manager routerManager, MediaRoute2Info info,
            String packageName) {
        super(context, routerManager, info, packageName);
        initDeviceRecord();
    }

    @Override
    public String getName() {
        return mRouteInfo.getName().toString();
    }

    @Override
    public String getSummary() {
        return mRouteInfo.getClientPackageName() != null
                ? mContext.getString(R.string.bluetooth_active_no_battery_level) : null;
    }

    @Override
    public Drawable getIcon() {
        //TODO(b/120669861): Return remote device icon uri once api is ready.
        return BluetoothUtils.buildBtRainbowDrawable(mContext,
                mContext.getDrawable(getDrawableResId()), getId().hashCode());
    }

    @VisibleForTesting
    int getDrawableResId() {
        int resId;
        switch (mRouteInfo.getType()) {
            case TYPE_GROUP:
                resId = R.drawable.ic_media_group_device;
                break;
            case TYPE_REMOTE_TV:
            case TYPE_REMOTE_SPEAKER:
            default:
                resId = R.drawable.ic_media_device;
                break;
        }
        return resId;
    }

    @Override
    public String getId() {
        return MediaDeviceUtils.getId(mRouteInfo);
    }

    public boolean isConnected() {
        return true;
    }
}
