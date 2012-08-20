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
 * Represents a physical display device such as the built-in display
 * an external monitor, or a WiFi display.
 */
public abstract class DisplayDevice {
    /**
     * Gets the display adapter that makes the display device available.
     *
     * @return The display adapter.
     */
    public abstract DisplayAdapter getAdapter();

    /**
     * Gets information about the display device.
     *
     * @param outInfo The object to populate with the information.
     */
    public abstract void getInfo(DisplayDeviceInfo outInfo);
}
