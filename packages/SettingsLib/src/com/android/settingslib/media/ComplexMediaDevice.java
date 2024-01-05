/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.media.RouteListingPreference;

import com.android.settingslib.R;

/**
 * ComplexMediaDevice extends MediaDevice to represents device with signals from a number of
 * sources.
 */
public class ComplexMediaDevice extends MediaDevice {

    private final String mSummary = "";

    ComplexMediaDevice(
            Context context,
            MediaRoute2Info info,
            String packageName,
            RouteListingPreference.Item item) {
        super(context, info, packageName, item);
    }

    // MediaRoute2Info.getName was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    @Override
    public String getName() {
        return mRouteInfo.getName().toString();
    }

    @Override
    public String getSummary() {
        return mSummary;
    }

    @Override
    public Drawable getIcon() {
        return mContext.getDrawable(R.drawable.ic_media_avr_device);
    }

    @Override
    public Drawable getIconWithoutBackground() {
        return mContext.getDrawable(R.drawable.ic_media_avr_device);
    }

    @Override
    public String getId() {
        return mRouteInfo.getId();
    }

    public boolean isConnected() {
        return true;
    }
}
