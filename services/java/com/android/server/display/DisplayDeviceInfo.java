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
import android.view.Display;
import android.view.Surface;

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
     * Flag: Indicates that the orientation of this display device is coupled to the
     * rotation of its associated logical display.
     * <p>
     * This flag should be applied to the default display to indicate that the user
     * physically rotates the display when content is presented in a different orientation.
     * The display manager will apply a coordinate transformation assuming that the
     * physical orientation of the display matches the logical orientation of its content.
     * </p><p>
     * The flag should not be set when the display device is mounted in a fixed orientation
     * such as on a desk.  The display manager will apply a coordinate transformation
     * such as a scale and translation to letterbox or pillarbox format under the
     * assumption that the physical orientation of the display is invariant.
     * </p>
     */
    public static final int FLAG_ROTATES_WITH_CONTENT = 1 << 1;

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
     * Flag: Indicates that the display device is owned by a particular application
     * and that no other application should be able to interact with it.
     */
    public static final int FLAG_PRIVATE = 1 << 4;

    /**
     * Flag: Indicates that the display device is not blanked automatically by
     * the power manager.
     */
    public static final int FLAG_NEVER_BLANK = 1 << 5;

    /**
     * Flag: Indicates that the display is suitable for presentations.
     */
    public static final int FLAG_PRESENTATION = 1 << 6;

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

    /**
     * The additional rotation to apply to all content presented on the display device
     * relative to its physical coordinate system.  Default is {@link Surface#ROTATION_0}.
     * <p>
     * This field can be used to compensate for the fact that the display has been
     * physically rotated relative to its natural orientation such as an HDMI monitor
     * that has been mounted sideways to appear to be portrait rather than landscape.
     * </p>
     */
    public int rotation = Surface.ROTATION_0;

    /**
     * Display type.
     */
    public int type;

    /**
     * Display address, or null if none.
     * Interpretation varies by display type.
     */
    public String address;

    /**
     * The UID of the application that owns this display, or zero if it is owned by the system.
     * <p>
     * If the display is private, then only the owner can use it.
     * </p>
     */
    public int ownerUid;

    /**
     * The package name of the application that owns this display, or null if it is
     * owned by the system.
     * <p>
     * If the display is private, then only the owner can use it.
     * </p>
     */
    public String ownerPackageName;

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
                && touch == other.touch
                && rotation == other.rotation
                && type == other.type
                && Objects.equal(address, other.address)
                && ownerUid == other.ownerUid
                && Objects.equal(ownerPackageName, other.ownerPackageName);
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
        rotation = other.rotation;
        type = other.type;
        address = other.address;
        ownerUid = other.ownerUid;
        ownerPackageName = other.ownerPackageName;
    }

    // For debugging purposes
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DisplayDeviceInfo{\"");
        sb.append(name).append("\": ").append(width).append(" x ").append(height);
        sb.append(", ").append(refreshRate).append(" fps, ");
        sb.append("density ").append(densityDpi);
        sb.append(", ").append(xDpi).append(" x ").append(yDpi).append(" dpi");
        sb.append(", touch ").append(touchToString(touch));
        sb.append(", rotation ").append(rotation);
        sb.append(", type ").append(Display.typeToString(type));
        if (address != null) {
            sb.append(", address ").append(address);
        }
        if (ownerUid != 0 || ownerPackageName != null) {
            sb.append(", owner ").append(ownerPackageName);
            sb.append(" (uid ").append(ownerUid).append(")");
        }
        sb.append(flagsToString(flags));
        sb.append("}");
        return sb.toString();
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
        if ((flags & FLAG_ROTATES_WITH_CONTENT) != 0) {
            msg.append(", FLAG_ROTATES_WITH_CONTENT");
        }
        if ((flags & FLAG_SECURE) != 0) {
            msg.append(", FLAG_SECURE");
        }
        if ((flags & FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) {
            msg.append(", FLAG_SUPPORTS_PROTECTED_BUFFERS");
        }
        if ((flags & FLAG_PRIVATE) != 0) {
            msg.append(", FLAG_PRIVATE");
        }
        if ((flags & FLAG_NEVER_BLANK) != 0) {
            msg.append(", FLAG_NEVER_BLANK");
        }
        if ((flags & FLAG_PRESENTATION) != 0) {
            msg.append(", FLAG_PRESENTATION");
        }
        return msg.toString();
    }
}
