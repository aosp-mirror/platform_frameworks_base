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
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;

/**
 * InfoMediaDevice extends MediaDevice to represents wifi device.
 */
public class InfoMediaDevice extends MediaDevice {

    private static final String TAG = "InfoMediaDevice";

    private final MediaRoute2Info mRouteInfo;
    private final MediaRouter2Manager mRouterManager;
    private final String mPackageName;

    InfoMediaDevice(Context context, MediaRouter2Manager routerManager, MediaRoute2Info info,
            String packageName) {
        super(context, MediaDeviceType.TYPE_CAST_DEVICE);
        mRouterManager = routerManager;
        mRouteInfo = info;
        mPackageName = packageName;
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
                mContext.getDrawable(R.drawable.ic_media_device), getId().hashCode());
    }

    @Override
    public String getId() {
        return MediaDeviceUtils.getId(mRouteInfo);
    }

    @Override
    public boolean connect() {
        setConnectedRecord();
        mRouterManager.selectRoute(mPackageName, mRouteInfo);
        return true;
    }

    @Override
    public void disconnect() {
        //TODO(b/144535188): disconnected last select device
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
