/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * Provides a fake default display for headless systems.
 */
public final class HeadlessDisplayAdapter extends DisplayAdapter {
    private final Context mContext;
    private final HeadlessDisplayDevice mDefaultDisplayDevice;

    public HeadlessDisplayAdapter(Context context) {
        mContext = context;
        mDefaultDisplayDevice = new HeadlessDisplayDevice();
    }

    @Override
    public String getName() {
        return "HeadlessDisplayAdapter";
    }

    @Override
    public void register(Listener listener) {
        listener.onDisplayDeviceAdded(mDefaultDisplayDevice);
    }

    private final class HeadlessDisplayDevice extends DisplayDevice {
        @Override
        public DisplayAdapter getAdapter() {
            return HeadlessDisplayAdapter.this;
        }

        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            outInfo.name = mContext.getResources().getString(
                    com.android.internal.R.string.display_manager_built_in_display);
            outInfo.width = 640;
            outInfo.height = 480;
            outInfo.refreshRate = 60;
            outInfo.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
            outInfo.xDpi = 160;
            outInfo.yDpi = 160;
        }
    }
}
