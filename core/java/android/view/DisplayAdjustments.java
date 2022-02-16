/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;

import java.util.Objects;

/** @hide */
public class DisplayAdjustments {
    public static final DisplayAdjustments DEFAULT_DISPLAY_ADJUSTMENTS = new DisplayAdjustments();

    private volatile CompatibilityInfo mCompatInfo = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
    private final Configuration mConfiguration = new Configuration(Configuration.EMPTY);
    private FixedRotationAdjustments mFixedRotationAdjustments;

    @UnsupportedAppUsage
    public DisplayAdjustments() {
    }

    public DisplayAdjustments(@Nullable Configuration configuration) {
        if (configuration != null) {
            mConfiguration.setTo(configuration);
        }
    }

    public DisplayAdjustments(@NonNull DisplayAdjustments daj) {
        setCompatibilityInfo(daj.mCompatInfo);
        mConfiguration.setTo(daj.getConfiguration());
        mFixedRotationAdjustments = daj.mFixedRotationAdjustments;
    }

    @UnsupportedAppUsage
    public void setCompatibilityInfo(@Nullable CompatibilityInfo compatInfo) {
        if (this == DEFAULT_DISPLAY_ADJUSTMENTS) {
            throw new IllegalArgumentException(
                    "setCompatbilityInfo: Cannot modify DEFAULT_DISPLAY_ADJUSTMENTS");
        }
        if (compatInfo != null && (compatInfo.isScalingRequired()
                || !compatInfo.supportsScreen())) {
            mCompatInfo = compatInfo;
        } else {
            mCompatInfo = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        }
    }

    public CompatibilityInfo getCompatibilityInfo() {
        return mCompatInfo;
    }

    /**
     * Updates the configuration for the DisplayAdjustments with new configuration.
     * Default to EMPTY configuration if new configuration is {@code null}
     * @param configuration new configuration
     * @throws IllegalArgumentException if trying to modify DEFAULT_DISPLAY_ADJUSTMENTS
     */
    public void setConfiguration(@Nullable Configuration configuration) {
        if (this == DEFAULT_DISPLAY_ADJUSTMENTS) {
            throw new IllegalArgumentException(
                    "setConfiguration: Cannot modify DEFAULT_DISPLAY_ADJUSTMENTS");
        }
        mConfiguration.setTo(configuration != null ? configuration : Configuration.EMPTY);
    }

    @UnsupportedAppUsage
    @NonNull
    public Configuration getConfiguration() {
        return mConfiguration;
    }

    public void setFixedRotationAdjustments(FixedRotationAdjustments fixedRotationAdjustments) {
        mFixedRotationAdjustments = fixedRotationAdjustments;
    }

    public FixedRotationAdjustments getFixedRotationAdjustments() {
        return mFixedRotationAdjustments;
    }

    /** Returns {@code false} if the width and height of display should swap. */
    private boolean noFlip(@Surface.Rotation int realRotation) {
        final FixedRotationAdjustments rotationAdjustments = mFixedRotationAdjustments;
        if (rotationAdjustments == null) {
            return true;
        }
        // Check if the delta is rotated by 90 degrees.
        return (realRotation - rotationAdjustments.mRotation + 4) % 2 == 0;
    }

    /** Adjusts the given size if possible. */
    public void adjustSize(@NonNull Point size, @Surface.Rotation int realRotation) {
        if (noFlip(realRotation)) {
            return;
        }
        final int w = size.x;
        size.x = size.y;
        size.y = w;
    }

    /** Adjusts the given metrics if possible. */
    public void adjustMetrics(@NonNull DisplayMetrics metrics, @Surface.Rotation int realRotation) {
        if (noFlip(realRotation)) {
            return;
        }
        int w = metrics.widthPixels;
        metrics.widthPixels = metrics.heightPixels;
        metrics.heightPixels = w;

        w = metrics.noncompatWidthPixels;
        metrics.noncompatWidthPixels = metrics.noncompatHeightPixels;
        metrics.noncompatHeightPixels = w;
    }

    /** Adjusts global display metrics that is available to applications. */
    public void adjustGlobalAppMetrics(@NonNull DisplayMetrics metrics) {
        final FixedRotationAdjustments rotationAdjustments = mFixedRotationAdjustments;
        if (rotationAdjustments == null) {
            return;
        }
        metrics.noncompatWidthPixels = metrics.widthPixels = rotationAdjustments.mAppWidth;
        metrics.noncompatHeightPixels = metrics.heightPixels = rotationAdjustments.mAppHeight;
    }

    /** Returns the adjusted cutout if available. Otherwise the original cutout is returned. */
    @Nullable
    public DisplayCutout getDisplayCutout(@Nullable DisplayCutout realCutout) {
        final FixedRotationAdjustments rotationAdjustments = mFixedRotationAdjustments;
        return rotationAdjustments != null && rotationAdjustments.mRotatedDisplayCutout != null
                ? rotationAdjustments.mRotatedDisplayCutout
                : realCutout;
    }

    /**
     * Returns the adjusted {@link RoundedCorners} if available. Otherwise the original
     * {@link RoundedCorners} is returned.
     */
    @Nullable
    public RoundedCorners adjustRoundedCorner(@Nullable RoundedCorners realRoundedCorners,
            @Surface.Rotation int realRotation, int displayWidth, int displayHeight) {
        final FixedRotationAdjustments rotationAdjustments = mFixedRotationAdjustments;
        if (realRoundedCorners == null || rotationAdjustments == null
                || rotationAdjustments.mRotation == realRotation) {
            return realRoundedCorners;
        }

        return realRoundedCorners.rotate(
                rotationAdjustments.mRotation, displayWidth, displayHeight);
    }

    /** Returns the adjusted rotation if available. Otherwise the original rotation is returned. */
    @Surface.Rotation
    public int getRotation(@Surface.Rotation int realRotation) {
        final FixedRotationAdjustments rotationAdjustments = mFixedRotationAdjustments;
        return rotationAdjustments != null ? rotationAdjustments.mRotation : realRotation;
    }

    @Override
    public int hashCode() {
        int hash = 17;
        hash = hash * 31 + Objects.hashCode(mCompatInfo);
        hash = hash * 31 + Objects.hashCode(mConfiguration);
        hash = hash * 31 + Objects.hashCode(mFixedRotationAdjustments);
        return hash;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof DisplayAdjustments)) {
            return false;
        }
        DisplayAdjustments daj = (DisplayAdjustments)o;
        return Objects.equals(daj.mCompatInfo, mCompatInfo)
                && Objects.equals(daj.mConfiguration, mConfiguration)
                && Objects.equals(daj.mFixedRotationAdjustments, mFixedRotationAdjustments);
    }

    /**
     * An application can be launched in different rotation than the real display. This class
     * provides the information to adjust the values returned by {@link Display}.
     * @hide
     */
    public static class FixedRotationAdjustments implements Parcelable {
        /** The application-based rotation. */
        @Surface.Rotation
        final int mRotation;

        /**
         * The rotated {@link DisplayInfo#appWidth}. The value cannot be simply swapped according
         * to rotation because it minus the region of screen decorations.
         */
        final int mAppWidth;

        /** The rotated {@link DisplayInfo#appHeight}. */
        final int mAppHeight;

        /** Non-null if the device has cutout. */
        @Nullable
        final DisplayCutout mRotatedDisplayCutout;

        public FixedRotationAdjustments(@Surface.Rotation int rotation, int appWidth, int appHeight,
                DisplayCutout cutout) {
            mRotation = rotation;
            mAppWidth = appWidth;
            mAppHeight = appHeight;
            mRotatedDisplayCutout = cutout;
        }

        @Override
        public int hashCode() {
            int hash = 17;
            hash = hash * 31 + mRotation;
            hash = hash * 31 + mAppWidth;
            hash = hash * 31 + mAppHeight;
            hash = hash * 31 + Objects.hashCode(mRotatedDisplayCutout);
            return hash;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof FixedRotationAdjustments)) {
                return false;
            }
            final FixedRotationAdjustments other = (FixedRotationAdjustments) o;
            return mRotation == other.mRotation
                    && mAppWidth == other.mAppWidth && mAppHeight == other.mAppHeight
                    && Objects.equals(mRotatedDisplayCutout, other.mRotatedDisplayCutout);
        }

        @Override
        public String toString() {
            return "FixedRotationAdjustments{rotation=" + Surface.rotationToString(mRotation)
                    + " appWidth=" + mAppWidth + " appHeight=" + mAppHeight
                    + " cutout=" + mRotatedDisplayCutout + "}";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mRotation);
            dest.writeInt(mAppWidth);
            dest.writeInt(mAppHeight);
            dest.writeTypedObject(
                    new DisplayCutout.ParcelableWrapper(mRotatedDisplayCutout), flags);
        }

        private FixedRotationAdjustments(Parcel in) {
            mRotation = in.readInt();
            mAppWidth = in.readInt();
            mAppHeight = in.readInt();
            final DisplayCutout.ParcelableWrapper cutoutWrapper =
                    in.readTypedObject(DisplayCutout.ParcelableWrapper.CREATOR);
            mRotatedDisplayCutout = cutoutWrapper != null ? cutoutWrapper.get() : null;
        }

        public static final Creator<FixedRotationAdjustments> CREATOR =
                new Creator<FixedRotationAdjustments>() {
            public FixedRotationAdjustments createFromParcel(Parcel in) {
                return new FixedRotationAdjustments(in);
            }

            public FixedRotationAdjustments[] newArray(int size) {
                return new FixedRotationAdjustments[size];
            }
        };
    }
}
