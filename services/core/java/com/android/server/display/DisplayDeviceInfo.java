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

import static android.view.Display.Mode.INVALID_MODE_ID;

import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayViewport;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.DisplayShape;
import android.view.RoundedCorners;
import android.view.Surface;

import com.android.internal.display.BrightnessSynchronizer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Describes the characteristics of a physical display device.
 */
final class DisplayDeviceInfo {
    /**
     * Flag: Indicates that this display device should be considered the default display
     * device of the system.
     */
    public static final int FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY = 1 << 0;

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
     * Flag: This display can show its content when non-secure keyguard is shown.
     */
    // TODO (b/114338689): Remove the flag and use IWindowManager#shouldShowWithInsecureKeyguard
    public static final int FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 9;

    /**
     * Flag: This display will destroy its content on removal.
     * @hide
     */
    // TODO (b/114338689): Remove the flag and use WindowManager#REMOVE_CONTENT_MODE_DESTROY
    public static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 10;

    /**
     * Flag: The display cutout of this display is masked.
     * @hide
     */
    public static final int FLAG_MASK_DISPLAY_CUTOUT = 1 << 11;

    /**
     * Flag: This flag identifies secondary displays that should show system decorations, such as
     * navigation bar, home activity or wallpaper.
     * <p>Note that this flag doesn't work without {@link #FLAG_TRUSTED}</p>
     * @hide
     */
    public static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 12;

    /**
     * Flag: The display is trusted to show system decorations and receive inputs without users'
     * touch.
     * @see #FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     */
    public static final int FLAG_TRUSTED = 1 << 13;

    /**
     * Flag: Indicates that the display should not be a part of the default {@link DisplayGroup} and
     * instead be part of a new {@link DisplayGroup}.
     *
     * @hide
     */
    public static final int FLAG_OWN_DISPLAY_GROUP = 1 << 14;

    /**
     * Flag: Indicates that the display should always be unlocked. Only valid on virtual displays
     * that aren't in the default display group.
     * @see #FLAG_OWN_DISPLAY_GROUP and #FLAG_DEVICE_DISPLAY_GROUP
     * @hide
     */
    public static final int FLAG_ALWAYS_UNLOCKED = 1 << 15;

    /**
     * Flag: Indicates that the display should not play sound effects or perform haptic feedback
     * when the user touches the screen.
     *
     * @hide
     */
    public static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 16;

    /**
     * Flag: Indicates that the display maintains its own focus and touch mode.
     *
     * This flag is similar to {@link com.android.internal.R.bool.config_perDisplayFocusEnabled} in
     * behavior, but only applies to the specific display instead of system-wide to all displays.
     *
     * Note: The display must be trusted in order to have its own focus.
     *
     * @see #FLAG_TRUSTED
     * @hide
     */
    public static final int FLAG_OWN_FOCUS = 1 << 17;

    /**
     * Flag: indicates that the display should not be a part of the default {@link DisplayGroup} and
     * instead be part of a {@link DisplayGroup} associated with the Virtual Device.
     *
     * @hide
     */
    public static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 18;

    /**
     * Flag: Indicates that the display should not become the top focused display by stealing the
     * top focus from another display.
     *
     * @see Display#FLAG_STEAL_TOP_FOCUS_DISABLED
     * @hide
     */
    public static final int FLAG_STEAL_TOP_FOCUS_DISABLED = 1 << 19;

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
     * Touch attachment: Touch input is via an input device matching {@link VirtualDisplay}'s
     * uniqueId.
     * @hide
     */
    public static final int TOUCH_VIRTUAL = 3;

    /**
     * Diff result: Other fields differ.
     */
    public static final int DIFF_OTHER = 1 << 0;

    /**
     * Diff result: The {@link #state} or {@link #committedState} fields differ.
     */
    public static final int DIFF_STATE = 1 << 1;

    /**
     * Diff result: The committed state differs. Note this is slightly different from the state,
     * which is what most of the device should care about.
     */
    public static final int DIFF_COMMITTED_STATE = 1 << 2;

    /**
     * Diff result: The color mode fields differ.
     */
    public static final int DIFF_COLOR_MODE = 1 << 3;

    /**
     * Diff result: The hdr/sdr ratio differs
     */
    public static final int DIFF_HDR_SDR_RATIO = 1 << 4;

    /**
     * Diff result: The rotation differs
     */
    public static final int DIFF_ROTATION = 1 << 5;

    /**
     * Diff result: The render timings. Note this could be any of {@link #renderFrameRate},
     * {@link #presentationDeadlineNanos}, or {@link #appVsyncOffsetNanos}.
     */
    public static final int DIFF_RENDER_TIMINGS = 1 << 6;

    /**
     * Diff result: The mode ID differs.
     */
    public static final int DIFF_MODE_ID = 1 << 7;

    /**
     * Diff result: Catch-all for "everything changed"
     */
    public static final int DIFF_EVERYTHING = 0XFFFFFFFF;

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
     * The render frame rate this display is scheduled at.
     * @see android.view.DisplayInfo#renderFrameRate for more details.
     */
    public float renderFrameRate;

    /**
     * The default mode of the display.
     */
    public int defaultModeId;

    /**
     * The mode of the display which is preferred by user.
     */
    public int userPreferredModeId = INVALID_MODE_ID;

    /**
     * The supported modes of the display.
     */
    public Display.Mode[] supportedModes = Display.Mode.EMPTY_ARRAY;

    /** The active color mode of the display */
    public int colorMode;

    /** The supported color modes of the display */
    public int[] supportedColorModes = { Display.COLOR_MODE_DEFAULT };

    /**
     * The HDR capabilities this display claims to support.
     */
    public Display.HdrCapabilities hdrCapabilities;

    /** When true, all HDR capabilities are hidden from public APIs */
    public boolean isForceSdr;

    /**
     * Indicates whether this display supports Auto Low Latency Mode.
     */
    public boolean allmSupported;

    /**
     * Indicates whether this display supports Game content type.
     */
    public boolean gameContentTypeSupported;

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
     * The {@link DisplayCutout} if present or {@code null} otherwise.
     */
    public DisplayCutout displayCutout;

    /**
     * The {@link RoundedCorners} if present or {@code null} otherwise.
     */
    public RoundedCorners roundedCorners;

    /**
     * The {@link RoundedCorners} if present or {@code null} otherwise.
     */
    public DisplayShape displayShape;

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
    public DisplayAddress address;

    /**
     * Product-specific information about the display or the directly connected device on the
     * display chain. For example, if the display is transitively connected, this field may contain
     * product information about the intermediate device.
     */
    public DeviceProductInfo deviceProductInfo;

    /**
     * Display state.
     */
    public int state = Display.STATE_ON;

    /**
     * Display committed state.
     *
     * This matches {@link DisplayDeviceInfo#state} only after the power state change finishes.
     */
    public int committedState = Display.STATE_UNKNOWN;

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

    public DisplayEventReceiver.FrameRateOverride[] frameRateOverrides =
            new DisplayEventReceiver.FrameRateOverride[0];

    public float brightnessMinimum;
    public float brightnessMaximum;
    public float brightnessDefault;

    // NaN means unsupported
    public float hdrSdrRatio = Float.NaN;

    /**
     * Install orientation of display panel relative to its natural orientation.
     */
    @Surface.Rotation
    public int installOrientation = Surface.ROTATION_0;

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
        if (committedState != other.committedState) {
            diff |= DIFF_COMMITTED_STATE;
        }
        if (colorMode != other.colorMode) {
            diff |= DIFF_COLOR_MODE;
        }
        if (!BrightnessSynchronizer.floatEquals(hdrSdrRatio, other.hdrSdrRatio)) {
            diff |= DIFF_HDR_SDR_RATIO;
        }
        if (rotation != other.rotation) {
            diff |= DIFF_ROTATION;
        }
        if (renderFrameRate != other.renderFrameRate
                || presentationDeadlineNanos != other.presentationDeadlineNanos
                || appVsyncOffsetNanos != other.appVsyncOffsetNanos) {
            diff |= DIFF_RENDER_TIMINGS;
        }
        if (modeId != other.modeId) {
            diff |= DIFF_MODE_ID;
        }
        if (!Objects.equals(name, other.name)
                || !Objects.equals(uniqueId, other.uniqueId)
                || width != other.width
                || height != other.height
                || defaultModeId != other.defaultModeId
                || userPreferredModeId != other.userPreferredModeId
                || !Arrays.equals(supportedModes, other.supportedModes)
                || !Arrays.equals(supportedColorModes, other.supportedColorModes)
                || !Objects.equals(hdrCapabilities, other.hdrCapabilities)
                || isForceSdr != other.isForceSdr
                || allmSupported != other.allmSupported
                || gameContentTypeSupported != other.gameContentTypeSupported
                || densityDpi != other.densityDpi
                || xDpi != other.xDpi
                || yDpi != other.yDpi
                || flags != other.flags
                || !Objects.equals(displayCutout, other.displayCutout)
                || touch != other.touch
                || type != other.type
                || !Objects.equals(address, other.address)
                || !Objects.equals(deviceProductInfo, other.deviceProductInfo)
                || ownerUid != other.ownerUid
                || !Objects.equals(ownerPackageName, other.ownerPackageName)
                || !Arrays.equals(frameRateOverrides, other.frameRateOverrides)
                || !BrightnessSynchronizer.floatEquals(brightnessMinimum, other.brightnessMinimum)
                || !BrightnessSynchronizer.floatEquals(brightnessMaximum, other.brightnessMaximum)
                || !BrightnessSynchronizer.floatEquals(brightnessDefault,
                other.brightnessDefault)
                || !Objects.equals(roundedCorners, other.roundedCorners)
                || installOrientation != other.installOrientation
                || !Objects.equals(displayShape, other.displayShape)) {
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
        renderFrameRate = other.renderFrameRate;
        defaultModeId = other.defaultModeId;
        userPreferredModeId = other.userPreferredModeId;
        supportedModes = other.supportedModes;
        colorMode = other.colorMode;
        supportedColorModes = other.supportedColorModes;
        hdrCapabilities = other.hdrCapabilities;
        isForceSdr = other.isForceSdr;
        allmSupported = other.allmSupported;
        gameContentTypeSupported = other.gameContentTypeSupported;
        densityDpi = other.densityDpi;
        xDpi = other.xDpi;
        yDpi = other.yDpi;
        appVsyncOffsetNanos = other.appVsyncOffsetNanos;
        presentationDeadlineNanos = other.presentationDeadlineNanos;
        flags = other.flags;
        displayCutout = other.displayCutout;
        touch = other.touch;
        rotation = other.rotation;
        type = other.type;
        address = other.address;
        deviceProductInfo = other.deviceProductInfo;
        state = other.state;
        committedState = other.committedState;
        ownerUid = other.ownerUid;
        ownerPackageName = other.ownerPackageName;
        frameRateOverrides = other.frameRateOverrides;
        brightnessMinimum = other.brightnessMinimum;
        brightnessMaximum = other.brightnessMaximum;
        brightnessDefault = other.brightnessDefault;
        hdrSdrRatio = other.hdrSdrRatio;
        roundedCorners = other.roundedCorners;
        installOrientation = other.installOrientation;
        displayShape = other.displayShape;
    }

    // For debugging purposes
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DisplayDeviceInfo{\"");
        sb.append(name).append("\": uniqueId=\"").append(uniqueId).append("\", ");
        sb.append(width).append(" x ").append(height);
        sb.append(", modeId ").append(modeId);
        sb.append(", renderFrameRate ").append(renderFrameRate);
        sb.append(", defaultModeId ").append(defaultModeId);
        sb.append(", userPreferredModeId ").append(userPreferredModeId);
        sb.append(", supportedModes ").append(Arrays.toString(supportedModes));
        sb.append(", colorMode ").append(colorMode);
        sb.append(", supportedColorModes ").append(Arrays.toString(supportedColorModes));
        sb.append(", hdrCapabilities ").append(hdrCapabilities);
        sb.append(", isForceSdr ").append(isForceSdr);
        sb.append(", allmSupported ").append(allmSupported);
        sb.append(", gameContentTypeSupported ").append(gameContentTypeSupported);
        sb.append(", density ").append(densityDpi);
        sb.append(", ").append(xDpi).append(" x ").append(yDpi).append(" dpi");
        sb.append(", appVsyncOff ").append(appVsyncOffsetNanos);
        sb.append(", presDeadline ").append(presentationDeadlineNanos);
        if (displayCutout != null) {
            sb.append(", cutout ").append(displayCutout);
        }
        sb.append(", touch ").append(touchToString(touch));
        sb.append(", rotation ").append(rotation);
        sb.append(", type ").append(Display.typeToString(type));
        if (address != null) {
            sb.append(", address ").append(address);
        }
        sb.append(", deviceProductInfo ").append(deviceProductInfo);
        sb.append(", state ").append(Display.stateToString(state));
        sb.append(", committedState ").append(Display.stateToString(committedState));
        if (ownerUid != 0 || ownerPackageName != null) {
            sb.append(", owner ").append(ownerPackageName);
            sb.append(" (uid ").append(ownerUid).append(")");
        }
        sb.append(", frameRateOverride ");
        for (DisplayEventReceiver.FrameRateOverride frameRateOverride : frameRateOverrides) {
            sb.append(frameRateOverride).append(" ");
        }
        sb.append(", brightnessMinimum ").append(brightnessMinimum);
        sb.append(", brightnessMaximum ").append(brightnessMaximum);
        sb.append(", brightnessDefault ").append(brightnessDefault);
        sb.append(", hdrSdrRatio ").append(hdrSdrRatio);
        if (roundedCorners != null) {
            sb.append(", roundedCorners ").append(roundedCorners);
        }
        sb.append(flagsToString(flags));
        sb.append(", installOrientation ").append(installOrientation);
        if (displayShape != null) {
            sb.append(", displayShape ").append(displayShape);
        }
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
            case TOUCH_VIRTUAL:
                return "VIRTUAL";
            default:
                return Integer.toString(touch);
        }
    }

    private static String flagsToString(int flags) {
        StringBuilder msg = new StringBuilder();
        if ((flags & FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY) != 0) {
            msg.append(", FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY");
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
        if ((flags & FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0) {
            msg.append(", FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD");
        }
        if ((flags & FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0) {
            msg.append(", FLAG_DESTROY_CONTENT_ON_REMOVAL");
        }
        if ((flags & FLAG_MASK_DISPLAY_CUTOUT) != 0) {
            msg.append(", FLAG_MASK_DISPLAY_CUTOUT");
        }
        if ((flags & FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0) {
            msg.append(", FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS");
        }
        if ((flags & FLAG_TRUSTED) != 0) {
            msg.append(", FLAG_TRUSTED");
        }
        if ((flags & FLAG_OWN_DISPLAY_GROUP) != 0) {
            msg.append(", FLAG_OWN_DISPLAY_GROUP");
        }
        if ((flags & FLAG_ALWAYS_UNLOCKED) != 0) {
            msg.append(", FLAG_ALWAYS_UNLOCKED");
        }
        if ((flags & FLAG_TOUCH_FEEDBACK_DISABLED) != 0) {
            msg.append(", FLAG_TOUCH_FEEDBACK_DISABLED");
        }
        if ((flags & FLAG_OWN_FOCUS) != 0) {
            msg.append(", FLAG_OWN_FOCUS");
        }
        if ((flags & FLAG_STEAL_TOP_FOCUS_DISABLED) != 0) {
            msg.append(", FLAG_STEAL_TOP_FOCUS_DISABLED");
        }
        return msg.toString();
    }
}
