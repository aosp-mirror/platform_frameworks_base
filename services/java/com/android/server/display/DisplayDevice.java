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

import android.os.IBinder;

/**
 * Represents a physical display device such as the built-in display
 * an external monitor, or a WiFi display.
 * <p>
 * Display devices are not thread-safe and must only be accessed
 * on the display manager service's handler thread.
 * </p>
 */
public abstract class DisplayDevice {
    private final DisplayAdapter mDisplayAdapter;
    private final IBinder mDisplayToken;

    public DisplayDevice(DisplayAdapter displayAdapter, IBinder displayToken) {
        mDisplayAdapter = displayAdapter;
        mDisplayToken = displayToken;
    }

    /**
     * Gets the display adapter that owns the display device.
     *
     * @return The display adapter.
     */
    public final DisplayAdapter getAdapter() {
        return mDisplayAdapter;
    }

    /**
     * Gets the Surface Flinger display token for this display.
     *
     * @return The display token, or null if the display is not being managed
     * by Surface Flinger.
     */
    public final IBinder getDisplayToken() {
        return mDisplayToken;
    }

    /**
     * Gets information about the display device.
     *
     * @param outInfo The object to populate with the information.
     */
    public abstract void getInfo(DisplayDeviceInfo outInfo);

    // For debugging purposes.
    @Override
    public String toString() {
        DisplayDeviceInfo info = new DisplayDeviceInfo();
        getInfo(info);
        return info.toString() + ", owner=\"" + mDisplayAdapter.getName() + "\"";
    }
}
