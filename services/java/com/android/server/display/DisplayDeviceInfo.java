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

import android.util.DisplayMetrics;

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
     * Flag: Indicates that this display device can rotate to show contents in a
     * different orientation.  Otherwise the rotation is assumed to be fixed in the
     * natural orientation and the display manager should transform the content to fit.
     */
    public static final int FLAG_SUPPORTS_ROTATION = 1 << 1;

    /**
     * Flag: Indicates that this display device has secure video output, such as HDCP.
     */
    public static final int FLAG_SECURE = 1 << 2;

    /**
     * Flag: Indicates that this display device supports compositing
     * from gralloc protected buffers.
     */
    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 1 << 3;

    /**
     * Touch attachment: Display does not receive touch.
     */
    public static final int TOUCH_NONE = 0;

    /**
     * Touch attachment: Touch input is via the internal interface.
     */
    public static final int TOUCH_INTERNAL = 1;

    /**
     * Touch attachment: Touch input is via an external interface, such as USB.
     */
    public static final int TOUCH_EXTERNAL = 2;

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

    /**
     * The refresh rate of the display.
     */
    public float refreshRate;

    /**
     * The nominal apparent density of the display in DPI used for layout calculations.
     * This density is sensitive to the viewing distance.  A big TV and a tablet may have
     * the same apparent density even though the pixels on the TV are much bigger than
     * those on the tablet.
     */
    public int densityDpi;

    /**
     * The physical density of the display in DPI in the X direction.
     * This density should specify the physical size of each pixel.
     */
    public float xDpi;

    /**
     * The physical density of the display in DPI in the X direction.
     * This density should specify the physical size of each pixel.
     */
    public float yDpi;

    /**
     * Display flags.
     */
    public int flags;

    /**
     * The touch attachment, per {@link DisplayViewport#touch}.
     */
    public int touch;

    public void setAssumedDensityForExternalDisplay(int width, int height) {
        densityDpi = Math.min(width, height) * DisplayMetrics.DENSITY_XHIGH / 1080;
        // Technically, these values should be smaller than the apparent density
        // but we don't know the physical size of the display.
        xDpi = densityDpi;
        yDpi = densityDpi;
    }

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
                && flags == other.flags
                && touch == other.touch;
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
        touch = other.touch;
    }

    // For debugging purposes
    @Override
    public String toString() {
        return "DisplayDeviceInfo{\"" + name + "\": " + width + " x " + height + ", " + refreshRate + " fps, "
                + "density " + densityDpi + ", " + xDpi + " x " + yDpi + " dpi"
                + ", touch " + touchToString(touch) + flagsToString(flags) + "}";
    }

    private static String touchToString(int touch) {
        switch (touch) {
            case TOUCH_NONE:
                return "NONE";
            case TOUCH_INTERNAL:
                return "INTERNAL";
            case TOUCH_EXTERNAL:
                return "EXTERNAL";
            default:
                return Integer.toString(touch);
        }
    }

    private static String flagsToString(int flags) {
        StringBuilder msg = new StringBuilder();
        if ((flags & FLAG_DEFAULT_DISPLAY) != 0) {
            msg.append(", FLAG_DEFAULT_DISPLAY");
        }
        if ((flags & FLAG_SUPPORTS_ROTATION) != 0) {
            msg.append(", FLAG_SUPPORTS_ROTATION");
        }
        if ((flags & FLAG_SECURE) != 0) {
            msg.append(", FLAG_SECURE");
        }
        if ((flags & FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) {
            msg.append(", FLAG_SUPPORTS_PROTECTED_BUFFERS");
        }
        return msg.toString();
    }
}
