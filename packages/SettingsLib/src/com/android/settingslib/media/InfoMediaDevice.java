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
import static android.media.MediaRoute2Info.TYPE_REMOTE_CAR;
import static android.media.MediaRoute2Info.TYPE_REMOTE_COMPUTER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_GAME_CONSOLE;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SMARTPHONE;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SMARTWATCH;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TABLET;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TABLET_DOCKED;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.RouteListingPreference;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

/**
 * InfoMediaDevice extends MediaDevice to represents wifi device.
 */
public class InfoMediaDevice extends MediaDevice {

    private static final String TAG = "InfoMediaDevice";

    InfoMediaDevice(
            Context context,
            MediaRoute2Info info,
            String packageName,
            RouteListingPreference.Item item) {
        super(context, info, packageName, item);
        initDeviceRecord();
    }

    InfoMediaDevice(Context context, MediaRoute2Info info, String packageName) {
        this(context, info, packageName, null);
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
        return getIconWithoutBackground();
    }

    @Override
    public Drawable getIconWithoutBackground() {
        return mContext.getDrawable(getDrawableResIdByType());
    }

    @VisibleForTesting
    @SuppressWarnings("NewApi")
    int getDrawableResIdByType() {
        int resId;
        switch (mRouteInfo.getType()) {
            case TYPE_GROUP:
                resId = R.drawable.ic_media_group_device;
                break;
            case TYPE_REMOTE_TV:
                resId = R.drawable.ic_media_display_device;
                break;
            case TYPE_REMOTE_TABLET:
                resId = R.drawable.ic_media_tablet;
                break;
            case TYPE_REMOTE_TABLET_DOCKED:
                resId = R.drawable.ic_dock_device;
                break;
            case TYPE_REMOTE_COMPUTER:
                resId = R.drawable.ic_media_computer;
                break;
            case TYPE_REMOTE_GAME_CONSOLE:
                resId = R.drawable.ic_media_game_console;
                break;
            case TYPE_REMOTE_CAR:
                resId = R.drawable.ic_media_car;
                break;
            case TYPE_REMOTE_SMARTWATCH:
                resId = R.drawable.ic_media_smartwatch;
                break;
            case TYPE_REMOTE_SMARTPHONE:
                resId = R.drawable.ic_smartphone;
                break;
            case TYPE_REMOTE_SPEAKER:
            default:
                resId = R.drawable.ic_media_speaker_device;
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
