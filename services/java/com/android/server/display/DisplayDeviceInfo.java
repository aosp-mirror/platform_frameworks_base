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

import libcore.util.Objects;

/**
 * Describes the characteristics of a physical display device.
 */
final class DisplayDeviceInfo {
    /**
     * Flag: Indicates that this display device should be considered the default display
     * device of the system.
     */
    public static final int FLAG_DEFAULT_DISPLAY = 1 << 0;

    /**
     * Flag: Indicates that this display device can show secure surfaces.
     */
    public static final int FLAG_SECURE = 1 << 1;

    /**
     * Flag: Indicates that this display device can rotate to show contents in a
     * different orientation.  Otherwise the rotation is assumed to be fixed in the
     * natural orientation and the display manager should transform the content to fit.
     */
    public static final int FLAG_SUPPORTS_ROTATION = 1 << 2;

    /**
     * Gets the name of the display device, which may be derived from
     * EDID or other sources.  The name may be displayed to the user.
     */
    public String name;

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
    public int densityDpi;
    public float xDpi;
    public float yDpi;

    public int flags;

    @Override
    public boolean equals(Object o) {
        return o instanceof DisplayDeviceInfo && equals((DisplayDeviceInfo)o);
    }

    public boolean equals(DisplayDeviceInfo other) {
        return other != null
                && Objects.equal(name, other.name)
                && width == other.width
                && height == other.height
                && refreshRate == other.refreshRate
                && densityDpi == other.densityDpi
                && xDpi == other.xDpi
                && yDpi == other.yDpi
                && flags == other.flags;
    }

    @Override
    public int hashCode() {
        return 0; // don't care
    }

    public void copyFrom(DisplayDeviceInfo other) {
        name = other.name;
        width = other.width;
        height = other.height;
        refreshRate = other.refreshRate;
        densityDpi = other.densityDpi;
        xDpi = other.xDpi;
        yDpi = other.yDpi;
        flags = other.flags;
    }

    // For debugging purposes
    @Override
    public String toString() {
        return "DisplayDeviceInfo{\"" + name + "\": " + width + " x " + height + ", " + refreshRate + " fps, "
                + "density " + densityDpi + ", " + xDpi + " x " + yDpi + " dpi"
                + flagsToString(flags) + "}";
    }

    private static String flagsToString(int flags) {
        StringBuilder msg = new StringBuilder();
        if ((flags & FLAG_DEFAULT_DISPLAY) != 0) {
            msg.append(", FLAG_DEFAULT_DISPLAY");
        }
        if ((flags & FLAG_SECURE) != 0) {
            msg.append(", FLAG_SECURE");
        }
        return msg.toString();
    }
}
