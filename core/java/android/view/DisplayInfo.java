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

import static android.view.DisplayInfoProto.APP_HEIGHT;
import static android.view.DisplayInfoProto.APP_WIDTH;
import static android.view.DisplayInfoProto.FLAGS;
import static android.view.DisplayInfoProto.LOGICAL_HEIGHT;
import static android.view.DisplayInfoProto.LOGICAL_WIDTH;
import static android.view.DisplayInfoProto.NAME;

import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DeviceProductInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.proto.ProtoOutputStream;

import java.util.Arrays;
import java.util.Objects;

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
     * Logical display identifier.
     */
    public int displayId;

    /**
     * Display Group identifier.
     */
    public int displayGroupId;

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
    @UnsupportedAppUsage
    public int logicalWidth;

    /**
     * The logical height of the display, in pixels.
     * Represents the usable size of the display which may be smaller than the
     * physical size when the system is emulating a smaller display.
     */
    @UnsupportedAppUsage
    public int logicalHeight;

    /**
     * The {@link DisplayCutout} if present, otherwise {@code null}.
     *
     * @hide
     */
    // Remark on @UnsupportedAppUsage: Display.getCutout should be used instead
    @Nullable
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public DisplayCutout displayCutout;

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
    @UnsupportedAppUsage
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

    /** The active color mode. */
    public int colorMode;

    /** The list of supported color modes */
    public int[] supportedColorModes = { Display.COLOR_MODE_DEFAULT };

    /** The display's HDR capabilities */
    public Display.HdrCapabilities hdrCapabilities;

    /** The formats disabled by user **/
    public int[] userDisabledHdrTypes = {};

    /**
     * Indicates whether the display can be switched into a mode with minimal post
     * processing.
     *
     * @see android.view.Display#isMinimalPostProcessingSupported
     */
    public boolean minimalPostProcessingSupported;

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

    /**
     * The refresh rate override for this app. 0 means no override.
     */
    public float refreshRateOverride;

    /**
     * @hide
     * Get current remove mode of the display - what actions should be performed with the display's
     * content when it is removed.
     *
     * @see Display#getRemoveMode()
     */
    // TODO (b/114338689): Remove the flag and use IWindowManager#getRemoveContentMode
    public int removeMode = Display.REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY;

    /**
     * @hide
     * The current minimum brightness constraint of the display. Value between 0.0 and 1.0,
     * derived from the config constraints of the display device of this logical display.
     */
    public float brightnessMinimum;

    /**
     * @hide
     * The current maximum brightness constraint of the display. Value between 0.0 and 1.0,
     * derived from the config constraints of the display device of this logical display.
     */
    public float brightnessMaximum;

    /**
     * @hide
     * The current default brightness of the display. Value between 0.0 and 1.0,
     * derived from the configuration of the display device of this logical display.
     */
    public float brightnessDefault;

    /**
     * @hide
     * True if Display#getRealSize and getRealMetrics should be constrained for Launcher, false
     * otherwise.
     */
    public boolean shouldConstrainMetricsForLauncher = false;

    /**
     * The {@link RoundedCorners} if present, otherwise {@code null}.
     */
    @Nullable
    public RoundedCorners roundedCorners;

    public static final @android.annotation.NonNull Creator<DisplayInfo> CREATOR = new Creator<DisplayInfo>() {
        @Override
        public DisplayInfo createFromParcel(Parcel source) {
            return new DisplayInfo(source);
        }

        @Override
        public DisplayInfo[] newArray(int size) {
            return new DisplayInfo[size];
        }
    };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769467)
    public DisplayInfo() {
    }

    public DisplayInfo(DisplayInfo other) {
        copyFrom(other);
    }

    private DisplayInfo(Parcel source) {
        readFromParcel(source);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof DisplayInfo && equals((DisplayInfo)o);
    }

    public boolean equals(DisplayInfo other) {
        return other != null
                && layerStack == other.layerStack
                && flags == other.flags
                && type == other.type
                && displayId == other.displayId
                && displayGroupId == other.displayGroupId
                && Objects.equals(address, other.address)
                && Objects.equals(deviceProductInfo, other.deviceProductInfo)
                && Objects.equals(uniqueId, other.uniqueId)
                && appWidth == other.appWidth
                && appHeight == other.appHeight
                && smallestNominalAppWidth == other.smallestNominalAppWidth
                && smallestNominalAppHeight == other.smallestNominalAppHeight
                && largestNominalAppWidth == other.largestNominalAppWidth
                && largestNominalAppHeight == other.largestNominalAppHeight
                && logicalWidth == other.logicalWidth
                && logicalHeight == other.logicalHeight
                && Objects.equals(displayCutout, other.displayCutout)
                && rotation == other.rotation
                && modeId == other.modeId
                && defaultModeId == other.defaultModeId
                && Arrays.equals(supportedModes, other.supportedModes)
                && colorMode == other.colorMode
                && Arrays.equals(supportedColorModes, other.supportedColorModes)
                && Objects.equals(hdrCapabilities, other.hdrCapabilities)
                && Arrays.equals(userDisabledHdrTypes, other.userDisabledHdrTypes)
                && minimalPostProcessingSupported == other.minimalPostProcessingSupported
                && logicalDensityDpi == other.logicalDensityDpi
                && physicalXDpi == other.physicalXDpi
                && physicalYDpi == other.physicalYDpi
                && appVsyncOffsetNanos == other.appVsyncOffsetNanos
                && presentationDeadlineNanos == other.presentationDeadlineNanos
                && state == other.state
                && ownerUid == other.ownerUid
                && Objects.equals(ownerPackageName, other.ownerPackageName)
                && removeMode == other.removeMode
                && getRefreshRate() == other.getRefreshRate()
                && brightnessMinimum == other.brightnessMinimum
                && brightnessMaximum == other.brightnessMaximum
                && brightnessDefault == other.brightnessDefault
                && Objects.equals(roundedCorners, other.roundedCorners)
                && shouldConstrainMetricsForLauncher == other.shouldConstrainMetricsForLauncher;
    }

    @Override
    public int hashCode() {
        return 0; // don't care
    }

    public void copyFrom(DisplayInfo other) {
        layerStack = other.layerStack;
        flags = other.flags;
        type = other.type;
        displayId = other.displayId;
        displayGroupId = other.displayGroupId;
        address = other.address;
        deviceProductInfo = other.deviceProductInfo;
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
        displayCutout = other.displayCutout;
        rotation = other.rotation;
        modeId = other.modeId;
        defaultModeId = other.defaultModeId;
        supportedModes = Arrays.copyOf(other.supportedModes, other.supportedModes.length);
        colorMode = other.colorMode;
        supportedColorModes = Arrays.copyOf(
                other.supportedColorModes, other.supportedColorModes.length);
        hdrCapabilities = other.hdrCapabilities;
        userDisabledHdrTypes = other.userDisabledHdrTypes;
        minimalPostProcessingSupported = other.minimalPostProcessingSupported;
        logicalDensityDpi = other.logicalDensityDpi;
        physicalXDpi = other.physicalXDpi;
        physicalYDpi = other.physicalYDpi;
        appVsyncOffsetNanos = other.appVsyncOffsetNanos;
        presentationDeadlineNanos = other.presentationDeadlineNanos;
        state = other.state;
        ownerUid = other.ownerUid;
        ownerPackageName = other.ownerPackageName;
        removeMode = other.removeMode;
        refreshRateOverride = other.refreshRateOverride;
        brightnessMinimum = other.brightnessMinimum;
        brightnessMaximum = other.brightnessMaximum;
        brightnessDefault = other.brightnessDefault;
        roundedCorners = other.roundedCorners;
        shouldConstrainMetricsForLauncher = other.shouldConstrainMetricsForLauncher;
    }

    public void readFromParcel(Parcel source) {
        layerStack = source.readInt();
        flags = source.readInt();
        type = source.readInt();
        displayId = source.readInt();
        displayGroupId = source.readInt();
        address = source.readParcelable(null);
        deviceProductInfo = source.readParcelable(null);
        name = source.readString8();
        appWidth = source.readInt();
        appHeight = source.readInt();
        smallestNominalAppWidth = source.readInt();
        smallestNominalAppHeight = source.readInt();
        largestNominalAppWidth = source.readInt();
        largestNominalAppHeight = source.readInt();
        logicalWidth = source.readInt();
        logicalHeight = source.readInt();
        displayCutout = DisplayCutout.ParcelableWrapper.readCutoutFromParcel(source);
        rotation = source.readInt();
        modeId = source.readInt();
        defaultModeId = source.readInt();
        int nModes = source.readInt();
        supportedModes = new Display.Mode[nModes];
        for (int i = 0; i < nModes; i++) {
            supportedModes[i] = Display.Mode.CREATOR.createFromParcel(source);
        }
        colorMode = source.readInt();
        int nColorModes = source.readInt();
        supportedColorModes = new int[nColorModes];
        for (int i = 0; i < nColorModes; i++) {
            supportedColorModes[i] = source.readInt();
        }
        hdrCapabilities = source.readParcelable(null);
        minimalPostProcessingSupported = source.readBoolean();
        logicalDensityDpi = source.readInt();
        physicalXDpi = source.readFloat();
        physicalYDpi = source.readFloat();
        appVsyncOffsetNanos = source.readLong();
        presentationDeadlineNanos = source.readLong();
        state = source.readInt();
        ownerUid = source.readInt();
        ownerPackageName = source.readString8();
        uniqueId = source.readString8();
        removeMode = source.readInt();
        refreshRateOverride = source.readFloat();
        brightnessMinimum = source.readFloat();
        brightnessMaximum = source.readFloat();
        brightnessDefault = source.readFloat();
        roundedCorners = source.readTypedObject(RoundedCorners.CREATOR);
        int numUserDisabledFormats = source.readInt();
        userDisabledHdrTypes = new int[numUserDisabledFormats];
        for (int i = 0; i < numUserDisabledFormats; i++) {
            userDisabledHdrTypes[i] = source.readInt();
        }
        shouldConstrainMetricsForLauncher = source.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(layerStack);
        dest.writeInt(this.flags);
        dest.writeInt(type);
        dest.writeInt(displayId);
        dest.writeInt(displayGroupId);
        dest.writeParcelable(address, flags);
        dest.writeParcelable(deviceProductInfo, flags);
        dest.writeString8(name);
        dest.writeInt(appWidth);
        dest.writeInt(appHeight);
        dest.writeInt(smallestNominalAppWidth);
        dest.writeInt(smallestNominalAppHeight);
        dest.writeInt(largestNominalAppWidth);
        dest.writeInt(largestNominalAppHeight);
        dest.writeInt(logicalWidth);
        dest.writeInt(logicalHeight);
        DisplayCutout.ParcelableWrapper.writeCutoutToParcel(displayCutout, dest, flags);
        dest.writeInt(rotation);
        dest.writeInt(modeId);
        dest.writeInt(defaultModeId);
        dest.writeInt(supportedModes.length);
        for (int i = 0; i < supportedModes.length; i++) {
            supportedModes[i].writeToParcel(dest, flags);
        }
        dest.writeInt(colorMode);
        dest.writeInt(supportedColorModes.length);
        for (int i = 0; i < supportedColorModes.length; i++) {
            dest.writeInt(supportedColorModes[i]);
        }
        dest.writeParcelable(hdrCapabilities, flags);
        dest.writeBoolean(minimalPostProcessingSupported);
        dest.writeInt(logicalDensityDpi);
        dest.writeFloat(physicalXDpi);
        dest.writeFloat(physicalYDpi);
        dest.writeLong(appVsyncOffsetNanos);
        dest.writeLong(presentationDeadlineNanos);
        dest.writeInt(state);
        dest.writeInt(ownerUid);
        dest.writeString8(ownerPackageName);
        dest.writeString8(uniqueId);
        dest.writeInt(removeMode);
        dest.writeFloat(refreshRateOverride);
        dest.writeFloat(brightnessMinimum);
        dest.writeFloat(brightnessMaximum);
        dest.writeFloat(brightnessDefault);
        dest.writeTypedObject(roundedCorners, flags);
        dest.writeInt(userDisabledHdrTypes.length);
        for (int i = 0; i < userDisabledHdrTypes.length; i++) {
            dest.writeInt(userDisabledHdrTypes[i]);
        }
        dest.writeBoolean(shouldConstrainMetricsForLauncher);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the refresh rate the application would experience.
     */
    public float getRefreshRate() {
        if (refreshRateOverride > 0) {
            return refreshRateOverride;
        }

        return getMode().getRefreshRate();
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
    @Nullable
    public Display.Mode findDefaultModeByRefreshRate(float refreshRate) {
        Display.Mode[] modes = supportedModes;
        Display.Mode defaultMode = getDefaultMode();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].matches(
                    defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), refreshRate)) {
                return modes[i];
            }
        }
        return null;
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

    /**
     * Populates {@code outMetrics} with details of the logical display. Bounds are limited
     * by the logical size of the display.
     *
     * @param outMetrics the {@link DisplayMetrics} to be populated
     * @param compatInfo the {@link CompatibilityInfo} to be applied
     * @param configuration the {@link Configuration}
     */
    public void getLogicalMetrics(DisplayMetrics outMetrics, CompatibilityInfo compatInfo,
            Configuration configuration) {
        getMetricsWithSize(outMetrics, compatInfo, configuration, logicalWidth, logicalHeight);
    }

    /**
     * Similar to {@link #getLogicalMetrics}, but the limiting bounds are determined from
     * {@link WindowConfiguration#getMaxBounds()}
     */
    public void getMaxBoundsMetrics(DisplayMetrics outMetrics, CompatibilityInfo compatInfo,
            Configuration configuration) {
        Rect bounds = configuration.windowConfiguration.getMaxBounds();
        // Pass in null configuration to ensure width and height are not overridden to app bounds.
        getMetricsWithSize(outMetrics, compatInfo, /* configuration= */ null,
                bounds.width(), bounds.height());
    }

    public int getNaturalWidth() {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 ?
                logicalWidth : logicalHeight;
    }

    public int getNaturalHeight() {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 ?
                logicalHeight : logicalWidth;
    }

    public boolean isHdr() {
        int[] types = hdrCapabilities != null ? hdrCapabilities.getSupportedHdrTypes() : null;
        return types != null && types.length > 0;
    }

    public boolean isWideColorGamut() {
        for (int colorMode : supportedColorModes) {
            if (colorMode == Display.COLOR_MODE_DCI_P3 || colorMode > Display.COLOR_MODE_SRGB) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the specified UID has access to this display.
     */
    public boolean hasAccess(int uid) {
        return Display.hasAccess(uid, flags, ownerUid, displayId);
    }

    private void getMetricsWithSize(DisplayMetrics outMetrics, CompatibilityInfo compatInfo,
            Configuration configuration, int width, int height) {
        outMetrics.densityDpi = outMetrics.noncompatDensityDpi = logicalDensityDpi;
        outMetrics.density = outMetrics.noncompatDensity =
                logicalDensityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        outMetrics.scaledDensity = outMetrics.noncompatScaledDensity = outMetrics.density;
        outMetrics.xdpi = outMetrics.noncompatXdpi = physicalXDpi;
        outMetrics.ydpi = outMetrics.noncompatYdpi = physicalYDpi;

        final Rect appBounds = configuration != null
                ? configuration.windowConfiguration.getAppBounds() : null;
        width = appBounds != null ? appBounds.width() : width;
        height = appBounds != null ? appBounds.height() : height;

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
        sb.append("\", displayId ");
        sb.append(displayId);
        sb.append("\", displayGroupId ");
        sb.append(displayGroupId);
        sb.append(flagsToString(flags));
        sb.append(", real ");
        sb.append(logicalWidth);
        sb.append(" x ");
        sb.append(logicalHeight);
        sb.append(", largest app ");
        sb.append(largestNominalAppWidth);
        sb.append(" x ");
        sb.append(largestNominalAppHeight);
        sb.append(", smallest app ");
        sb.append(smallestNominalAppWidth);
        sb.append(" x ");
        sb.append(smallestNominalAppHeight);
        sb.append(", appVsyncOff ");
        sb.append(appVsyncOffsetNanos);
        sb.append(", presDeadline ");
        sb.append(presentationDeadlineNanos);
        sb.append(", mode ");
        sb.append(modeId);
        sb.append(", defaultMode ");
        sb.append(defaultModeId);
        sb.append(", modes ");
        sb.append(Arrays.toString(supportedModes));
        sb.append(", hdrCapabilities ");
        sb.append(hdrCapabilities);
        sb.append(", userDisabledHdrTypes ");
        sb.append(Arrays.toString(userDisabledHdrTypes));
        sb.append(", minimalPostProcessingSupported ");
        sb.append(minimalPostProcessingSupported);
        sb.append(", rotation ");
        sb.append(rotation);
        sb.append(", state ");
        sb.append(Display.stateToString(state));

        if (Process.myUid() != Process.SYSTEM_UID) {
            sb.append("}");
            return sb.toString();
        }

        sb.append(", type ");
        sb.append(Display.typeToString(type));
        sb.append(", uniqueId \"");
        sb.append(uniqueId);
        sb.append("\", app ");
        sb.append(appWidth);
        sb.append(" x ");
        sb.append(appHeight);
        sb.append(", density ");
        sb.append(logicalDensityDpi);
        sb.append(" (");
        sb.append(physicalXDpi);
        sb.append(" x ");
        sb.append(physicalYDpi);
        sb.append(") dpi, layerStack ");
        sb.append(layerStack);
        sb.append(", colorMode ");
        sb.append(colorMode);
        sb.append(", supportedColorModes ");
        sb.append(Arrays.toString(supportedColorModes));
        if (address != null) {
            sb.append(", address ").append(address);
        }
        sb.append(", deviceProductInfo ");
        sb.append(deviceProductInfo);
        if (ownerUid != 0 || ownerPackageName != null) {
            sb.append(", owner ").append(ownerPackageName);
            sb.append(" (uid ").append(ownerUid).append(")");
        }
        sb.append(", removeMode ");
        sb.append(removeMode);
        sb.append(", refreshRateOverride ");
        sb.append(refreshRateOverride);
        sb.append(", brightnessMinimum ");
        sb.append(brightnessMinimum);
        sb.append(", brightnessMaximum ");
        sb.append(brightnessMaximum);
        sb.append(", brightnessDefault ");
        sb.append(brightnessDefault);
        sb.append(", shouldConstrainMetricsForLauncher ");
        sb.append(shouldConstrainMetricsForLauncher);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Write to a protocol buffer output stream.
     * Protocol buffer message definition at {@link android.view.DisplayInfoProto}
     *
     * @param protoOutputStream Stream to write the Rect object to.
     * @param fieldId           Field Id of the DisplayInfoProto as defined in the parent message
     */
    public void dumpDebug(ProtoOutputStream protoOutputStream, long fieldId) {
        final long token = protoOutputStream.start(fieldId);
        protoOutputStream.write(LOGICAL_WIDTH, logicalWidth);
        protoOutputStream.write(LOGICAL_HEIGHT, logicalHeight);
        protoOutputStream.write(APP_WIDTH, appWidth);
        protoOutputStream.write(APP_HEIGHT, appHeight);
        protoOutputStream.write(NAME, name);
        protoOutputStream.write(FLAGS, flags);
        protoOutputStream.end(token);
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
        if ((flags & Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0) {
            result.append(", FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD");
        }
        if ((flags & Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0) {
            result.append(", FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS");
        }
        if ((flags & Display.FLAG_TRUSTED) != 0) {
            result.append(", FLAG_TRUSTED");
        }
        if ((flags & Display.FLAG_OWN_DISPLAY_GROUP) != 0) {
            result.append(", FLAG_OWN_DISPLAY_GROUP");
        }
        return result.toString();
    }
}
