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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;

/**
 * Describes the characteristics of a particular logical display.
 * @hide
 */
public final class DisplayInfo implements Parcelable {
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
     * The rotation of the display relative to its natural orientation.
     * May be one of {@link android.view.Surface#ROTATION_0},
     * {@link android.view.Surface#ROTATION_90}, {@link android.view.Surface#ROTATION_180},
     * {@link android.view.Surface#ROTATION_270}.
     * <p>
     * The value of this field is indeterminate if the logical display is presented on
     * more than one physical display.
     * </p>
     */
    public int rotation;

    /**
     * The refresh rate of this display in frames per second.
     * <p>
     * The value of this field is indeterminate if the logical display is presented on
     * more than one physical display.
     * </p>
     */
    public float refreshRate;

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

    public static final Creator<DisplayInfo> CREATOR = new Creator<DisplayInfo>() {
        public DisplayInfo createFromParcel(Parcel source) {
            return new DisplayInfo(source);
        }

        public DisplayInfo[] newArray(int size) {
            return new DisplayInfo[size];
        }
    };

    public DisplayInfo() {
    }

    private DisplayInfo(Parcel source) {
        readFromParcel(source);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void copyFrom(DisplayInfo other) {
        appWidth = other.appWidth;
        appHeight = other.appHeight;
        smallestNominalAppWidth = other.smallestNominalAppWidth;
        smallestNominalAppHeight = other.smallestNominalAppHeight;
        largestNominalAppWidth = other.largestNominalAppWidth;
        largestNominalAppHeight = other.largestNominalAppHeight;
        logicalWidth = other.logicalWidth;
        logicalHeight = other.logicalHeight;
        rotation = other.rotation;
        refreshRate = other.refreshRate;
        logicalDensityDpi = other.logicalDensityDpi;
        physicalXDpi = other.physicalXDpi;
        physicalYDpi = other.physicalYDpi;
    }

    public void readFromParcel(Parcel source) {
        appWidth = source.readInt();
        appHeight = source.readInt();
        smallestNominalAppWidth = source.readInt();
        smallestNominalAppHeight = source.readInt();
        largestNominalAppWidth = source.readInt();
        largestNominalAppHeight = source.readInt();
        logicalWidth = source.readInt();
        logicalHeight = source.readInt();
        rotation = source.readInt();
        refreshRate = source.readFloat();
        logicalDensityDpi = source.readInt();
        physicalXDpi = source.readFloat();
        physicalYDpi = source.readFloat();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(appWidth);
        dest.writeInt(appHeight);
        dest.writeInt(smallestNominalAppWidth);
        dest.writeInt(smallestNominalAppHeight);
        dest.writeInt(largestNominalAppWidth);
        dest.writeInt(largestNominalAppHeight);
        dest.writeInt(logicalWidth);
        dest.writeInt(logicalHeight);
        dest.writeInt(rotation);
        dest.writeFloat(refreshRate);
        dest.writeInt(logicalDensityDpi);
        dest.writeFloat(physicalXDpi);
        dest.writeFloat(physicalYDpi);
    }

    public void getAppMetrics(DisplayMetrics outMetrics, CompatibilityInfoHolder cih) {
        getMetricsWithSize(outMetrics, cih, appWidth, appHeight);
    }

    public void getLogicalMetrics(DisplayMetrics outMetrics, CompatibilityInfoHolder cih) {
        getMetricsWithSize(outMetrics, cih, logicalWidth, logicalHeight);
    }

    private void getMetricsWithSize(DisplayMetrics outMetrics, CompatibilityInfoHolder cih,
            int width, int height) {
        outMetrics.densityDpi = outMetrics.noncompatDensityDpi = logicalDensityDpi;
        outMetrics.noncompatWidthPixels  = outMetrics.widthPixels = width;
        outMetrics.noncompatHeightPixels = outMetrics.heightPixels = height;

        outMetrics.density = outMetrics.noncompatDensity =
                logicalDensityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        outMetrics.scaledDensity = outMetrics.noncompatScaledDensity = outMetrics.density;
        outMetrics.xdpi = outMetrics.noncompatXdpi = physicalXDpi;
        outMetrics.ydpi = outMetrics.noncompatYdpi = physicalYDpi;

        if (cih != null) {
            CompatibilityInfo ci = cih.getIfNeeded();
            if (ci != null) {
                ci.applyToDisplayMetrics(outMetrics);
            }
        }
    }

    // For debugging purposes
    @Override
    public String toString() {
        return "app " + appWidth + " x " + appHeight
                + ", real " + logicalWidth + " x " + logicalHeight
                + ", largest app " + largestNominalAppWidth + " x " + largestNominalAppHeight
                + ", smallest app " + smallestNominalAppWidth + " x " + smallestNominalAppHeight
                + ", " + refreshRate + " fps"
                + ", rotation " + rotation
                + ", density " + logicalDensityDpi
                + ", " + physicalXDpi + " x " + physicalYDpi + " dpi";
    }
}
