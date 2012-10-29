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
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * Provides a fake default display for headless systems.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class HeadlessDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "HeadlessDisplayAdapter";

    // Called with SyncRoot lock held.
    public HeadlessDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
    }

    @Override
    public void registerLocked() {
        super.registerLocked();
        sendDisplayDeviceEventLocked(new HeadlessDisplayDevice(), DISPLAY_DEVICE_EVENT_ADDED);
    }

    private final class HeadlessDisplayDevice extends DisplayDevice {
        private DisplayDeviceInfo mInfo;

        public HeadlessDisplayDevice() {
            super(HeadlessDisplayAdapter.this, null);
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = getContext().getResources().getString(
                        com.android.internal.R.string.display_manager_built_in_display_name);
                mInfo.width = 640;
                mInfo.height = 480;
                mInfo.refreshRate = 60;
                mInfo.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
                mInfo.xDpi = 160;
                mInfo.yDpi = 160;
                mInfo.flags = DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY
                        | DisplayDeviceInfo.FLAG_SECURE
                        | DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
                mInfo.type = Display.TYPE_BUILT_IN;
                mInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
            }
            return mInfo;
        }
    }
}
