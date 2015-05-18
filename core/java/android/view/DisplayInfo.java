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

package android.view;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.DisplayMetrics;

import java.util.Arrays;

import libcore.util.Objects;

/**
 * Describes the characteristics of a particular logical display.
 * @hide
 */
public final class DisplayInfo implements Parcelable {
    /**
     * The surface flinger layer stack associated with this logical display.
     */
    public int layerStack;

    /**
     * Display flags.
     */
    public int flags;

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
     * The human-readable name of the display.
     */
    public String name;

    /**
     * Unique identifier for the display. Shouldn't be displayed to the user.
     */
    public String uniqueId;

    /**
     * The width of the portion of the display that is available to applications, in pixels.
     * Represents the size of the display minus any system decorations.
     */
    public int appWidth;

    /**
     * The height of the portion of the display that is available to applications, in pixels.
     * Represents the size of the display minus any system decorations.
     */
    public int appHeight;

    /**
     * The smallest value of {@link #appWidth} that an application is likely to encounter,
     * in pixels, excepting cases where the width may be even smaller due to the presence
     * of a soft keyboard, for example.
     */
    public int smallestNominalAppWidth;

    /**
     * The smallest value of {@link #appHeight} that an application is likely to encounter,
     * in pixels, excepting cases where the height may be even smaller due to the presence
     * of a soft keyboard, for example.
     */
    public int smallestNominalAppHeight;

    /**
     * The largest value of {@link #appWidth} that an application is likely to encounter,
     * in pixels, excepting cases where the width may be even larger due to system decorations
     * such as the status bar being hidden, for example.
     */
    public int largestNominalAppWidth;

    /**
     * The largest value of {@link #appHeight} that an application is likely to encounter,
     * in pixels, excepting cases where the height may be even larger due to system decorations
     * such as the status bar being hidden, for example.
     */
    public int largestNominalAppHeight;

    /**
     * The logical width of the display, in pixels.
     * Represents the usable size of the display which may be smaller than the
     * physical size when the system is emulating a smaller display.
     */
    public int logicalWidth;

    /**
     * The logical height of the display, in pixels.
     * Represents the usable size of the display which may be smaller than the
     * physical size when the system is emulating a smaller display.
     */
    public int logicalHeight;

    /**
     * @hide
     * Number of overscan pixels on the left side of the display.
     */
    public int overscanLeft;

    /**
     * @hide
     * Number of overscan pixels on the top side of the display.
     */
    public int overscanTop;

    /**
     * @hide
     * Number of overscan pixels on the right side of the display.
     */
    public int overscanRight;

    /**
     * @hide
     * Number of overscan pixels on the bottom side of the display.
     */
    public int overscanBottom;

    /**
     * The rotation of the display relative to its natural orientation.
     * May be one of {@link android.view.Surface#ROTATION_0},
     * {@link android.view.Surface#ROTATION_90}, {@link android.view.Surface#ROTATION_180},
     * {@link android.view.Surface#ROTATION_270}.
     * <p>
     * The value of this field is indeterminate if the logical display is presented on
     * more than one physical display.
     * </p>
     */
    @Surface.Rotation
    public int rotation;

    /**
     * The active display mode.
     */
    public int modeId;

    /**
     * The default display mode.
     */
    public int defaultModeId;

    /**
     * The supported modes of this display.
     */
    public Display.Mode[] supportedModes = Display.Mode.EMPTY_ARRAY;

    /**
     * The logical display density which is the basis for density-independent
     * pixels.
     */
    public int logicalDensityDpi;

    /**
     * The exact physical pixels per inch of the screen in the X dimension.
     * <p>
     * The value of this field is indeterminate if the logical display is presented on
     * more than one physical display.
     * </p>
     */
    public float physicalXDpi;

    /**
     * The exact physical pixels per inch of the screen in the Y dimension.
     * <p>
     * The value of this field is indeterminate if the logical display is presented on
     * more than one physical display.
     * </p>
     */
    public float physicalYDpi;

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
     * The state of the display, such as {@link android.view.Display#STATE_ON}.
     */
    public int state;

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

    public static final Creator<DisplayInfo> CREATOR = new Creator<DisplayInfo>() {
        @Override
        public DisplayInfo createFromParcel(Parcel source) {
            return new DisplayInfo(source);
        }

        @Override
        public DisplayInfo[] newArray(int size) {
            return new DisplayInfo[size];
        }
    };

    public DisplayInfo() {
    }

    public DisplayInfo(DisplayInfo other) {
        copyFrom(other);
    }

    private DisplayInfo(Parcel source) {
        readFromParcel(source);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DisplayInfo && equals((DisplayInfo)o);
    }

    public boolean equals(DisplayInfo other) {
        return other != null
                && layerStack == other.layerStack
                && flags == other.flags
                && type == other.type
                && Objects.equal(address, other.address)
                && Objects.equal(uniqueId, other.uniqueId)
                && appWidth == other.appWidth
                && appHeight == other.appHeight
                && smallestNominalAppWidth == other.smallestNominalAppWidth
                && smallestNominalAppHeight == other.smallestNominalAppHeight
                && largestNominalAppWidth == other.largestNominalAppWidth
                && largestNominalAppHeight == other.largestNominalAppHeight
                && logicalWidth == other.logicalWidth
                && logicalHeight == other.logicalHeight
                && overscanLeft == other.overscanLeft
                && overscanTop == other.overscanTop
                && overscanRight == other.overscanRight
                && overscanBottom == other.overscanBottom
                && rotation == other.rotation
                && modeId == other.modeId
                && defaultModeId == other.defaultModeId
                && logicalDensityDpi == other.logicalDensityDpi
                && physicalXDpi == other.physicalXDpi
                && physicalYDpi == other.physicalYDpi
                && appVsyncOffsetNanos == other.appVsyncOffsetNanos
                && presentationDeadlineNanos == other.presentationDeadlineNanos
                && state == other.state
                && ownerUid == other.ownerUid
                && Objects.equal(ownerPackageName, other.ownerPackageName);
    }

    @Override
    public int hashCode() {
        return 0; // don't care
    }

    public void copyFrom(DisplayInfo other) {
        layerStack = other.layerStack;
        flags = other.flags;
        type = other.type;
        address = other.address;
        name = other.name;
        uniqueId = other.uniqueId;
        appWidth = other.appWidth;
        appHeight = other.appHeight;
        smallestNominalAppWidth = other.smallestNominalAppWidth;
        smallestNominalAppHeight = other.smallestNominalAppHeight;
        largestNominalAppWidth = other.largestNominalAppWidth;
        largestNominalAppHeight = other.largestNominalAppHeight;
        logicalWidth = other.logicalWidth;
        logicalHeight = other.logicalHeight;
        overscanLeft = other.overscanLeft;
        overscanTop = other.overscanTop;
        overscanRight = other.overscanRight;
        overscanBottom = other.overscanBottom;
        rotation = other.rotation;
        modeId = other.modeId;
        defaultModeId = other.defaultModeId;
        supportedModes = Arrays.copyOf(other.supportedModes, other.supportedModes.length);
        logicalDensityDpi = other.logicalDensityDpi;
        physicalXDpi = other.physicalXDpi;
        physicalYDpi = other.physicalYDpi;
        appVsyncOffsetNanos = other.appVsyncOffsetNanos;
        presentationDeadlineNanos = other.presentationDeadlineNanos;
        state = other.state;
        ownerUid = other.ownerUid;
        ownerPackageName = other.ownerPackageName;
    }

    public void readFromParcel(Parcel source) {
        layerStack = source.readInt();
        flags = source.readInt();
        type = source.readInt();
        address = source.readString();
        name = source.readString();
        appWidth = source.readInt();
        appHeight = source.readInt();
        smallestNominalAppWidth = source.readInt();
        smallestNominalAppHeight = source.readInt();
        largestNominalAppWidth = source.readInt();
        largestNominalAppHeight = source.readInt();
        logicalWidth = source.readInt();
        logicalHeight = source.readInt();
        overscanLeft = source.readInt();
        overscanTop = source.readInt();
        overscanRight = source.readInt();
        overscanBottom = source.readInt();
        rotation = source.readInt();
        modeId = source.readInt();
        defaultModeId = source.readInt();
        int nModes = source.readInt();
        supportedModes = new Display.Mode[nModes];
        for (int i = 0; i < nModes; i++) {
            supportedModes[i] = Display.Mode.CREATOR.createFromParcel(source);
        }
        logicalDensityDpi = source.readInt();
        physicalXDpi = source.readFloat();
        physicalYDpi = source.readFloat();
        appVsyncOffsetNanos = source.readLong();
        presentationDeadlineNanos = source.readLong();
        state = source.readInt();
        ownerUid = source.readInt();
        ownerPackageName = source.readString();
        uniqueId = source.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(layerStack);
        dest.writeInt(this.flags);
        dest.writeInt(type);
        dest.writeString(address);
        dest.writeString(name);
        dest.writeInt(appWidth);
        dest.writeInt(appHeight);
        dest.writeInt(smallestNominalAppWidth);
        dest.writeInt(smallestNominalAppHeight);
        dest.writeInt(largestNominalAppWidth);
        dest.writeInt(largestNominalAppHeight);
        dest.writeInt(logicalWidth);
        dest.writeInt(logicalHeight);
        dest.writeInt(overscanLeft);
        dest.writeInt(overscanTop);
        dest.writeInt(overscanRight);
        dest.writeInt(overscanBottom);
        dest.writeInt(rotation);
        dest.writeInt(modeId);
        dest.writeInt(defaultModeId);
        dest.writeInt(supportedModes.length);
        for (int i = 0; i < supportedModes.length; i++) {
            supportedModes[i].writeToParcel(dest, flags);
        }
        dest.writeInt(logicalDensityDpi);
        dest.writeFloat(physicalXDpi);
        dest.writeFloat(physicalYDpi);
        dest.writeLong(appVsyncOffsetNanos);
        dest.writeLong(presentationDeadlineNanos);
        dest.writeInt(state);
        dest.writeInt(ownerUid);
        dest.writeString(ownerPackageName);
        dest.writeString(uniqueId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public Display.Mode getMode() {
        return findMode(modeId);
    }

    public Display.Mode getDefaultMode() {
        return findMode(defaultModeId);
    }

    private Display.Mode findMode(int id) {
        for (int i = 0; i < supportedModes.length; i++) {
            if (supportedModes[i].getModeId() == id) {
                return supportedModes[i];
            }
        }
        throw new IllegalStateException("Unable to locate mode " + id);
    }

    /**
     * Returns the id of the "default" mode with the given refresh rate, or {@code 0} if no suitable
     * mode could be found.
     */
    public int findDefaultModeByRefreshRate(float refreshRate) {
        Display.Mode[] modes = supportedModes;
        Display.Mode defaultMode = getDefaultMode();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].matches(
                    defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), refreshRate)) {
                return modes[i].getModeId();
            }
        }
        return 0;
    }

    /**
     * Returns the list of supported refresh rates in the default mode.
     */
    public float[] getDefaultRefreshRates() {
        Display.Mode[] modes = supportedModes;
        ArraySet<Float> rates = new ArraySet<>();
        Display.Mode defaultMode = getDefaultMode();
        for (int i = 0; i < modes.length; i++) {
            Display.Mode mode = modes[i];
            if (mode.getPhysicalWidth() == defaultMode.getPhysicalWidth()
                    && mode.getPhysicalHeight() == defaultMode.getPhysicalHeight()) {
                rates.add(mode.getRefreshRate());
            }
        }
        float[] result = new float[rates.size()];
        int i = 0;
        for (Float rate : rates) {
            result[i++] = rate;
        }
        return result;
    }

    public void getAppMetrics(DisplayMetrics outMetrics) {
        getAppMetrics(outMetrics, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
    }

    public void getAppMetrics(DisplayMetrics outMetrics, DisplayAdjustments displayAdjustments) {
        getMetricsWithSize(outMetrics, displayAdjustments.getCompatibilityInfo(),
                displayAdjustments.getConfiguration(), appWidth, appHeight);
    }

    public void getAppMetrics(DisplayMetrics outMetrics, CompatibilityInfo ci,
            Configuration configuration) {
        getMetricsWithSize(outMetrics, ci, configuration, appWidth, appHeight);
    }

    public void getLogicalMetrics(DisplayMetrics outMetrics, CompatibilityInfo compatInfo,
            Configuration configuration) {
        getMetricsWithSize(outMetrics, compatInfo, configuration, logicalWidth, logicalHeight);
    }

    public int getNaturalWidth() {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 ?
                logicalWidth : logicalHeight;
    }

    public int getNaturalHeight() {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 ?
                logicalHeight : logicalWidth;
    }

    /**
     * Returns true if the specified UID has access to this display.
     */
    public boolean hasAccess(int uid) {
        return Display.hasAccess(uid, flags, ownerUid);
    }

    private void getMetricsWithSize(DisplayMetrics outMetrics, CompatibilityInfo compatInfo,
            Configuration configuration, int width, int height) {
        outMetrics.densityDpi = outMetrics.noncompatDensityDpi = logicalDensityDpi;
        outMetrics.density = outMetrics.noncompatDensity =
                logicalDensityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        outMetrics.scaledDensity = outMetrics.noncompatScaledDensity = outMetrics.density;
        outMetrics.xdpi = outMetrics.noncompatXdpi = physicalXDpi;
        outMetrics.ydpi = outMetrics.noncompatYdpi = physicalYDpi;

        width = (configuration != null
                && configuration.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED)
                ? (int)((configuration.screenWidthDp * outMetrics.density) + 0.5f) : width;
        height = (configuration != null
                && configuration.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED)
                ? (int)((configuration.screenHeightDp * outMetrics.density) + 0.5f) : height;

        outMetrics.noncompatWidthPixels  = outMetrics.widthPixels = width;
        outMetrics.noncompatHeightPixels = outMetrics.heightPixels = height;

        if (!compatInfo.equals(CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO)) {
            compatInfo.applyToDisplayMetrics(outMetrics);
        }
    }

    // For debugging purposes
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DisplayInfo{\"");
        sb.append(name);
        sb.append("\", uniqueId \"");
        sb.append(uniqueId);
        sb.append("\", app ");
        sb.append(appWidth);
        sb.append(" x ");
        sb.append(appHeight);
        sb.append(", real ");
        sb.append(logicalWidth);
        sb.append(" x ");
        sb.append(logicalHeight);
        if (overscanLeft != 0 || overscanTop != 0 || overscanRight != 0 || overscanBottom != 0) {
            sb.append(", overscan (");
            sb.append(overscanLeft);
            sb.append(",");
            sb.append(overscanTop);
            sb.append(",");
            sb.append(overscanRight);
            sb.append(",");
            sb.append(overscanBottom);
            sb.append(")");
        }
        sb.append(", largest app ");
        sb.append(largestNominalAppWidth);
        sb.append(" x ");
        sb.append(largestNominalAppHeight);
        sb.append(", smallest app ");
        sb.append(smallestNominalAppWidth);
        sb.append(" x ");
        sb.append(smallestNominalAppHeight);
        sb.append(", mode ");
        sb.append(modeId);
        sb.append(", defaultMode ");
        sb.append(defaultModeId);
        sb.append(", modes ");
        sb.append(Arrays.toString(supportedModes));
        sb.append(", rotation ");
        sb.append(rotation);
        sb.append(", density ");
        sb.append(logicalDensityDpi);
        sb.append(" (");
        sb.append(physicalXDpi);
        sb.append(" x ");
        sb.append(physicalYDpi);
        sb.append(") dpi, layerStack ");
        sb.append(layerStack);
        sb.append(", appVsyncOff ");
        sb.append(appVsyncOffsetNanos);
        sb.append(", presDeadline ");
        sb.append(presentationDeadlineNanos);
        sb.append(", type ");
        sb.append(Display.typeToString(type));
        if (address != null) {
            sb.append(", address ").append(address);
        }
        sb.append(", state ");
        sb.append(Display.stateToString(state));
        if (ownerUid != 0 || ownerPackageName != null) {
            sb.append(", owner ").append(ownerPackageName);
            sb.append(" (uid ").append(ownerUid).append(")");
        }
        sb.append(flagsToString(flags));
        sb.append("}");
        return sb.toString();
    }

    private static String flagsToString(int flags) {
        StringBuilder result = new StringBuilder();
        if ((flags & Display.FLAG_SECURE) != 0) {
            result.append(", FLAG_SECURE");
        }
        if ((flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) {
            result.append(", FLAG_SUPPORTS_PROTECTED_BUFFERS");
        }
        if ((flags & Display.FLAG_PRIVATE) != 0) {
            result.append(", FLAG_PRIVATE");
        }
        if ((flags & Display.FLAG_PRESENTATION) != 0) {
            result.append(", FLAG_PRESENTATION");
        }
        if ((flags & Display.FLAG_SCALING_DISABLED) != 0) {
            result.append(", FLAG_SCALING_DISABLED");
        }
        if ((flags & Display.FLAG_ROUND) != 0) {
            result.append(", FLAG_ROUND");
        }
        return result.toString();
    }
}
