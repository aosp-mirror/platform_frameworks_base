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
 * Describes the characteristics of a physical display device.
 */
public final class DisplayDeviceInfo {
    /**
     * The width of the display in its natural orientation, in pixels.
     * This value is not affected by display rotation.
     */
    public int width;

    /**
     * The height of the display in its natural orientation, in pixels.
     * This value is not affected by display rotation.
     */
    public int height;

    public float refreshRate;
    public float density;
    public float xDpi;
    public float yDpi;

    public void copyFrom(DisplayDeviceInfo other) {
        width = other.width;
        height = other.height;
        refreshRate = other.refreshRate;
        density = other.density;
        xDpi = other.xDpi;
        yDpi = other.yDpi;
    }

    @Override
    public String toString() {
        return width + " x " + height + ", " + refreshRate + " fps, "
                + "density " + density + ", " + xDpi + " x " + yDpi + " dpi";
    }
}
