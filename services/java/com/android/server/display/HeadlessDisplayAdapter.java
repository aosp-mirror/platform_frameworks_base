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
 * <p>
 * Display adapters are not thread-safe and must only be accessed
 * on the display manager service's handler thread.
 * </p>
 */
public final class HeadlessDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "HeadlessDisplayAdapter";

    public HeadlessDisplayAdapter(Context context) {
        super(context, TAG);
    }

    @Override
    protected void onRegister() {
        sendDisplayDeviceEvent(new HeadlessDisplayDevice(), DISPLAY_DEVICE_EVENT_ADDED);
    }

    private final class HeadlessDisplayDevice extends DisplayDevice {
        public HeadlessDisplayDevice() {
            super(HeadlessDisplayAdapter.this, null);
        }

        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            outInfo.name = getContext().getResources().getString(
                    com.android.internal.R.string.display_manager_built_in_display_name);
            outInfo.width = 640;
            outInfo.height = 480;
            outInfo.refreshRate = 60;
            outInfo.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
            outInfo.xDpi = 160;
            outInfo.yDpi = 160;
            outInfo.flags = DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY
                    | DisplayDeviceInfo.FLAG_SECURE;
        }
    }
}
