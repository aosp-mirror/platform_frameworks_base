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
import android.os.IBinder;
import android.view.Surface;
import android.view.Surface.PhysicalDisplayInfo;

/**
 * A display adapter for the local displays managed by Surface Flinger.
 * <p>
 * Display adapters are not thread-safe and must only be accessed
 * on the display manager service's handler thread.
 * </p>
 */
public final class LocalDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "LocalDisplayAdapter";

    public LocalDisplayAdapter(Context context) {
        super(context, TAG);
    }

    @Override
    protected void onRegister() {
        // TODO: listen for notifications from Surface Flinger about
        // built-in displays being added or removed and rescan as needed.
        IBinder displayToken = Surface.getBuiltInDisplay(Surface.BUILT_IN_DISPLAY_ID_MAIN);
        sendDisplayDeviceEvent(new LocalDisplayDevice(displayToken, true),
                DISPLAY_DEVICE_EVENT_ADDED);
    }

    private final class LocalDisplayDevice extends DisplayDevice {
        private final boolean mIsDefault;

        public LocalDisplayDevice(IBinder displayToken, boolean isDefault) {
            super(LocalDisplayAdapter.this, displayToken);
            mIsDefault = isDefault;
        }

        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            PhysicalDisplayInfo phys = new PhysicalDisplayInfo();
            Surface.getDisplayInfo(getDisplayToken(), phys);

            outInfo.name = getContext().getResources().getString(
                    com.android.internal.R.string.display_manager_built_in_display_name);
            outInfo.width = phys.width;
            outInfo.height = phys.height;
            outInfo.refreshRate = phys.refreshRate;
            outInfo.densityDpi = (int)(phys.density * 160 + 0.5f);
            outInfo.xDpi = phys.xDpi;
            outInfo.yDpi = phys.yDpi;
            if (mIsDefault) {
                outInfo.flags = DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY
                        | DisplayDeviceInfo.FLAG_SECURE;
            }
        }
    }
}
