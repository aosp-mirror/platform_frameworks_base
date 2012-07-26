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

/**
 * A display adapter for the displays managed by Surface Flinger.
 */
public final class SurfaceFlingerDisplayAdapter extends DisplayAdapter {
    private static native void nativeGetDefaultDisplayDeviceInfo(DisplayDeviceInfo outInfo);

    private final DisplayDevice mDefaultDisplay = new DisplayDevice() {
        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            nativeGetDefaultDisplayDeviceInfo(outInfo);
        }
    };

    @Override
    public String getName() {
        return "SurfaceFlingerDisplayAdapter";
    }

    @Override
    public DisplayDevice[] getDisplayDevices() {
        return new DisplayDevice[] { mDefaultDisplay };
    }
}
