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
 */
public final class LocalDisplayAdapter extends DisplayAdapter {
    private final Context mContext;
    private final LocalDisplayDevice mDefaultDisplayDevice;

    public LocalDisplayAdapter(Context context) {
        mContext = context;

        IBinder token = Surface.getBuiltInDisplay(Surface.BUILT_IN_DISPLAY_ID_MAIN);
        mDefaultDisplayDevice = new LocalDisplayDevice(token);
    }

    @Override
    public String getName() {
        return "LocalDisplayAdapter";
    }

    @Override
    public void register(Listener listener) {
        listener.onDisplayDeviceAdded(mDefaultDisplayDevice);
    }

    private final class LocalDisplayDevice extends DisplayDevice {
        private final IBinder mDisplayToken;

        public LocalDisplayDevice(IBinder token) {
            mDisplayToken = token;
        }

        @Override
        public DisplayAdapter getAdapter() {
            return LocalDisplayAdapter.this;
        }

        @Override
        public IBinder getDisplayToken() {
            return mDisplayToken;
        }

        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            PhysicalDisplayInfo phys = new PhysicalDisplayInfo();
            Surface.getDisplayInfo(mDisplayToken, phys);

            outInfo.name = mContext.getResources().getString(
                    com.android.internal.R.string.display_manager_built_in_display);
            outInfo.width = phys.width;
            outInfo.height = phys.height;
            outInfo.refreshRate = phys.refreshRate;
            outInfo.densityDpi = (int)(phys.density * 160 + 0.5f);
            outInfo.xDpi = phys.xDpi;
            outInfo.yDpi = phys.yDpi;
        }
    }
}
