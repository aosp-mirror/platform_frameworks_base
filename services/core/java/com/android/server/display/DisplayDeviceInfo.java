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

import android.hardware.display.DisplayViewport;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

import java.util.Arrays;

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
     * Should typically be used together with {@link #FLAG_OWN_CONTENT_ONLY}.
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
     * Flag: Only show this display's own content; do not mirror
     * the content of another display.
     */
    public static final int FLAG_OWN_CONTENT_ONLY = 1 << 7;

    /**
     * Flag: This display device has a round shape.
     */
    public static final int FLAG_ROUND = 1 << 8;

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
     * Diff result: The {@link #state} fields differ.
     */
    public static final int DIFF_STATE = 1 << 0;

    /**
     * Diff result: Other fields differ.
     */
    public static final int DIFF_OTHER = 1 << 1;

    /**
     * Gets the name of the display device, which may be derived from EDID or
     * other sources. The name may be localized and displayed to the user.
     */
    public String name;

    /**
     * Unique Id of display device.
     */
    public String uniqueId;

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
     * The active mode of the display.
     */
    public int modeId;

    /**
     * The default mode of the display.
     */
    public int defaultModeId;

    /**
     * The supported modes of the display.
     */
    public Display.Mode[] supportedModes = Display.Mode.EMPTY_ARRAY;

    /** The active color transform of the display */
    public int colorTransformId;

    /** The default color transform of the display */
    public int defaultColorTransformId;

    /** The supported color transforms of the display */
    public Display.ColorTransform[] supportedColorTransforms = Display.ColorTransform.EMPTY_ARRAY;

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
     * This is a positive value indicating the phase offset of the VSYNC events provided by
     * Choreographer relative to the display refresh.  For example, if Choreographer reports
     * that the refresh occurred at time N, it actually occurred at (N - appVsyncOffsetNanos).
     */
    public long appVsyncOffsetNanos;

    /**
     * This is how far in advance a buffer must be queued for presentation at
     * a given time.  If you want a buffer to appear on the screen at
     * time N, you must submit the buffer before (N - bufferDeadlineNanos).
     */
    public long presentationDeadlineNanos;

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
     * Display state.
     */
    public int state = Display.STATE_ON;

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
        return other != null && diff(other) == 0;
    }

    /**
     * Computes the difference between display device infos.
     * Assumes other is not null.
     */
    public int diff(DisplayDeviceInfo other) {
        int diff = 0;
        if (state != other.state) {
            diff |= DIFF_STATE;
        }
        if (!Objects.equal(name, other.name)
                || !Objects.equal(uniqueId, other.uniqueId)
                || width != other.width
                || height != other.height
                || modeId != other.modeId
                || defaultModeId != other.defaultModeId
                || !Arrays.equals(supportedModes, other.supportedModes)
                || colorTransformId != other.colorTransformId
                || defaultColorTransformId != other.defaultColorTransformId
                || !Arrays.equals(supportedColorTransforms, other.supportedColorTransforms)
                || densityDpi != other.densityDpi
                || xDpi != other.xDpi
                || yDpi != other.yDpi
                || appVsyncOffsetNanos != other.appVsyncOffsetNanos
                || presentationDeadlineNanos != other.presentationDeadlineNanos
                || flags != other.flags
                || touch != other.touch
                || rotation != other.rotation
                || type != other.type
                || !Objects.equal(address, other.address)
                || ownerUid != other.ownerUid
                || !Objects.equal(ownerPackageName, other.ownerPackageName)) {
            diff |= DIFF_OTHER;
        }
        return diff;
    }

    @Override
    public int hashCode() {
        return 0; // don't care
    }

    public void copyFrom(DisplayDeviceInfo other) {
        name = other.name;
        uniqueId = other.uniqueId;
        width = other.width;
        height = other.height;
        modeId = other.modeId;
        defaultModeId = other.defaultModeId;
        supportedModes = other.supportedModes;
        colorTransformId = other.colorTransformId;
        defaultColorTransformId = other.defaultColorTransformId;
        supportedColorTransforms = other.supportedColorTransforms;
        densityDpi = other.densityDpi;
        xDpi = other.xDpi;
        yDpi = other.yDpi;
        appVsyncOffsetNanos = other.appVsyncOffsetNanos;
        presentationDeadlineNanos = other.presentationDeadlineNanos;
        flags = other.flags;
        touch = other.touch;
        rotation = other.rotation;
        type = other.type;
        address = other.address;
        state = other.state;
        ownerUid = other.ownerUid;
        ownerPackageName = other.ownerPackageName;
    }

    // For debugging purposes
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DisplayDeviceInfo{\"");
        sb.append(name).append("\": uniqueId=\"").append(uniqueId).append("\", ");
        sb.append(width).append(" x ").append(height);
        sb.append(", modeId ").append(modeId);
        sb.append(", defaultModeId ").append(defaultModeId);
        sb.append(", supportedModes ").append(Arrays.toString(supportedModes));
        sb.append(", colorTransformId ").append(colorTransformId);
        sb.append(", defaultColorTransformId ").append(defaultColorTransformId);
        sb.append(", supportedColorTransforms ").append(Arrays.toString(supportedColorTransforms));
        sb.append(", density ").append(densityDpi);
        sb.append(", ").append(xDpi).append(" x ").append(yDpi).append(" dpi");
        sb.append(", appVsyncOff ").append(appVsyncOffsetNanos);
        sb.append(", presDeadline ").append(presentationDeadlineNanos);
        sb.append(", touch ").append(touchToString(touch));
        sb.append(", rotation ").append(rotation);
        sb.append(", type ").append(Display.typeToString(type));
        if (address != null) {
            sb.append(", address ").append(address);
        }
        sb.append(", state ").append(Display.stateToString(state));
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
        if ((flags & FLAG_OWN_CONTENT_ONLY) != 0) {
            msg.append(", FLAG_OWN_CONTENT_ONLY");
        }
        if ((flags & FLAG_ROUND) != 0) {
            msg.append(", FLAG_ROUND");
        }
        return msg.toString();
    }
}
