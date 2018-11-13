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

import android.app.Notification;
import android.content.Context;
import android.util.Log;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

/**
 * InfoMediaManager provide interface to get InfoMediaDevice list.
 */
public class InfoMediaManager extends MediaManager {

    private static final String TAG = "InfoMediaManager";

    private final MediaRouterCallback mMediaRouterCallback = new MediaRouterCallback();

    private MediaRouter mMediaRouter;
    private String mPackageName;

    InfoMediaManager(Context context, String packageName, Notification notification) {
        super(context, notification);

        mMediaRouter = MediaRouter.getInstance(context);
        mPackageName = packageName;
    }

    @Override
    public void startScan() {
        mMediaDevices.clear();
        startScanCastDevice();
    }

    private void startScanCastDevice() {
        final MediaRouteSelector selector = new MediaRouteSelector.Builder()
                .addControlCategory(getControlCategoryByPackageName(mPackageName))
                .build();

        mMediaRouter.addCallback(selector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    private String getControlCategoryByPackageName(String packageName) {
        //TODO(b/117129183): Use package name to get ControlCategory.
        //Since api not ready, return fixed ControlCategory for prototype.
        return "com.google.android.gms.cast.CATEGORY_CAST/4F8B3483";
    }

    @Override
    public void stopScan() {
        mMediaRouter.removeCallback(mMediaRouterCallback);
    }

    class MediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) {
            MediaDevice mediaDevice = findMediaDevice(MediaDeviceUtils.getId(route));
            if (mediaDevice == null) {
                mediaDevice = new InfoMediaDevice(mContext, route);
                Log.d(TAG, "onRouteAdded() route : " + route.getName());
                mMediaDevices.add(mediaDevice);
                dispatchDeviceAdded(mediaDevice);
            }
        }

        @Override
        public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo route) {
            final MediaDevice mediaDevice = findMediaDevice(MediaDeviceUtils.getId(route));
            if (mediaDevice != null) {
                Log.d(TAG, "onRouteRemoved() route : " + route.getName());
                mMediaDevices.remove(mediaDevice);
                dispatchDeviceRemoved(mediaDevice);
            }
        }
    }
}
