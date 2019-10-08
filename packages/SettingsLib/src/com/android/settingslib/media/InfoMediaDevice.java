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
import android.widget.Toast;

import androidx.mediarouter.media.MediaRouter;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;

/**
 * InfoMediaDevice extends MediaDevice to represents wifi device.
 */
public class InfoMediaDevice extends MediaDevice {

    private static final String TAG = "InfoMediaDevice";

    private MediaRouter.RouteInfo mRouteInfo;

    InfoMediaDevice(Context context, MediaRouter.RouteInfo info) {
        super(context, MediaDeviceType.TYPE_CAST_DEVICE);
        mRouteInfo = info;
        initDeviceRecord();
    }

    @Override
    public String getName() {
        return mRouteInfo.getName();
    }

    @Override
    public String getSummary() {
        return null;
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
        //TODO(b/121083246): use SystemApi to transfer media
        setConnectedRecord();
        Toast.makeText(mContext, "This is cast device !", Toast.LENGTH_SHORT).show();
        return false;
    }

    @Override
    public void disconnect() {
        //TODO(b/121083246): disconnected last select device
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
