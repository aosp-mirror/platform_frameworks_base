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
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.text.TextUtils;
import android.util.Log;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothUtils;

/**
 * InfoMediaDevice extends MediaDevice to represents wifi device.
 */
public class InfoMediaDevice extends MediaDevice {

    private static final String TAG = "InfoMediaDevice";

    InfoMediaDevice(Context context, MediaRouter2Manager routerManager, MediaRoute2Info info,
            String packageName) {
        super(context, MediaDeviceType.TYPE_CAST_DEVICE, routerManager, info, packageName);
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
    public void requestSetVolume(int volume) {
        mRouterManager.requestSetVolume(mRouteInfo, volume);
    }

    @Override
    public int getMaxVolume() {
        return mRouteInfo.getVolumeMax();
    }

    @Override
    public int getCurrentVolume() {
        return mRouteInfo.getVolume();
    }

    @Override
    public String getClientPackageName() {
        return mRouteInfo.getClientPackageName();
    }

    @Override
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

    public boolean isConnected() {
        return true;
    }
}
