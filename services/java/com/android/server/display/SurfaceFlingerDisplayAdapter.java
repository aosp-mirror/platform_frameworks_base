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

/**
 * A display adapter for the displays managed by Surface Flinger.
 */
public final class SurfaceFlingerDisplayAdapter extends DisplayAdapter {
    private final Context mContext;
    private final SurfaceFlingerDisplayDevice mDefaultDisplayDevice;

    private static native void nativeGetDefaultDisplayDeviceInfo(DisplayDeviceInfo outInfo);

    public SurfaceFlingerDisplayAdapter(Context context) {
        mContext = context;
        mDefaultDisplayDevice = new SurfaceFlingerDisplayDevice();
    }

    @Override
    public String getName() {
        return "SurfaceFlingerDisplayAdapter";
    }

    @Override
    public void register(Listener listener) {
        listener.onDisplayDeviceAdded(mDefaultDisplayDevice);
    }

    private final class SurfaceFlingerDisplayDevice extends DisplayDevice {
        @Override
        public DisplayAdapter getAdapter() {
            return SurfaceFlingerDisplayAdapter.this;
        }

        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            outInfo.name = mContext.getResources().getString(
                    com.android.internal.R.string.display_manager_built_in_display);
            nativeGetDefaultDisplayDeviceInfo(outInfo);
        }
    }
}
