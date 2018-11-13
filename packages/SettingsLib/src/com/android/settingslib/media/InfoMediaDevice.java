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

import androidx.mediarouter.media.MediaRouter;

import com.android.settingslib.R;

/**
 * InfoMediaDevice extends MediaDevice to represents wifi device.
 */
public class InfoMediaDevice extends MediaDevice {

    private static final String TAG = "InfoMediaDevice";

    private MediaRouter.RouteInfo mRouteInfo;

    InfoMediaDevice(Context context, MediaRouter.RouteInfo info) {
        super(context, MediaDeviceType.TYPE_CAST_DEVICE);
        mRouteInfo = info;
    }

    @Override
    public String getName() {
        return mRouteInfo.getName();
    }

    @Override
    public int getIcon() {
        //TODO(b/117129183): This is not final icon for cast device, just for demo.
        return R.drawable.ic_settings_print;
    }

    @Override
    public String getId() {
        return MediaDeviceUtils.getId(mRouteInfo);
    }

    @Override
    public void connect() {
        //TODO(b/117129183): use MediaController2 to transfer media
        mIsConnected = true;
    }

    @Override
    public void disconnect() {
        //TODO(b/117129183): disconnected last select device
        mIsConnected = false;
    }
}
