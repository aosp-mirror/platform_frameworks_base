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
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * InfoMediaManager provide interface to get InfoMediaDevice list.
 */
public class InfoMediaManager extends MediaManager {

    private static final String TAG = "InfoMediaManager";

    @VisibleForTesting
    final RouterManagerCallback mMediaRouterCallback = new RouterManagerCallback();
    @VisibleForTesting
    final Executor mExecutor = Executors.newSingleThreadExecutor();
    @VisibleForTesting
    MediaRouter2Manager mRouterManager;

    private String mPackageName;
    private MediaDevice mCurrentConnectedDevice;

    public InfoMediaManager(Context context, String packageName, Notification notification) {
        super(context, notification);

        mRouterManager = MediaRouter2Manager.getInstance(context);
        if (packageName != null) {
            mPackageName = packageName;
        }
    }

    @Override
    public void startScan() {
        mMediaDevices.clear();
        mRouterManager.registerCallback(mExecutor, mMediaRouterCallback);
    }

    @VisibleForTesting
    String getControlCategoryByPackageName(String packageName) {
        //TODO(b/117129183): Use package name to get ControlCategory.
        //Since api not ready, return fixed ControlCategory for prototype.
        return "com.google.android.gms.cast.CATEGORY_CAST";
    }

    @Override
    public void stopScan() {
        mRouterManager.unregisterCallback(mMediaRouterCallback);
    }

    /**
     * Get current device that played media.
     * @return MediaDevice
     */
    public MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    class RouterManagerCallback extends MediaRouter2Manager.Callback {

        private void refreshDevices() {
            mMediaDevices.clear();
            mCurrentConnectedDevice = null;
            for (MediaRoute2Info route : mRouterManager.getAvailableRoutes(mPackageName)) {
                final MediaDevice device = new InfoMediaDevice(mContext, mRouterManager, route,
                        mPackageName);
                if (TextUtils.equals(route.getClientPackageName(), mPackageName)) {
                    mCurrentConnectedDevice = device;
                }
                mMediaDevices.add(device);
            }
            dispatchDeviceListAdded();
        }

        @Override
        public void onRoutesAdded(List<MediaRoute2Info> routes) {
            refreshDevices();
        }

        @Override
        public void onControlCategoriesChanged(String packageName, List<String> controlCategories) {
            if (TextUtils.equals(mPackageName, packageName)) {
                refreshDevices();
            }
        }
    }
}
