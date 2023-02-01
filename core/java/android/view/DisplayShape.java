/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.Surface.ROTATION_0;

import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Matrix;
import android.graphics.Path;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayUtils;
import android.util.PathParser;
import android.util.RotationUtils;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * A class representing the shape of a display. It provides a {@link Path} of the display shape of
 * the display shape.
 *
 * {@link DisplayShape} is immutable.
 */
public final class DisplayShape implements Parcelable {

    /** @hide */
    public static final DisplayShape NONE = new DisplayShape("" /* displayShapeSpec */,
            0 /* displayWidth */, 0 /* displayHeight */, 0 /* physicalPixelDisplaySizeRatio */,
            0 /* rotation */);

    /** @hide */
    @VisibleForTesting
    public final String mDisplayShapeSpec;
    private final float mPhysicalPixelDisplaySizeRatio;
    private final int mDisplayWidth;
    private final int mDisplayHeight;
    private final int mRotation;
    private final int mOffsetX;
    private final int mOffsetY;
    private final float mScale;

    private DisplayShape(@NonNull String displayShapeSpec, int displayWidth, int displayHeight,
            float physicalPixelDisplaySizeRatio, int rotation) {
        this(displayShapeSpec, displayWidth, displayHeight, physicalPixelDisplaySizeRatio,
                rotation, 0, 0, 1f);
    }

    private DisplayShape(@NonNull String displayShapeSpec, int displayWidth, int displayHeight,
            float physicalPixelDisplaySizeRatio, int rotation, int offsetX, int offsetY,
            float scale) {
        mDisplayShapeSpec = displayShapeSpec;
        mDisplayWidth = displayWidth;
        mDisplayHeight = displayHeight;
        mPhysicalPixelDisplaySizeRatio = physicalPixelDisplaySizeRatio;
        mRotation = rotation;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mScale = scale;
    }

    /**
     * @hide
     */
    @NonNull
    public static DisplayShape fromResources(
            @NonNull Resources res, @NonNull String displayUniqueId, int physicalDisplayWidth,
            int physicalDisplayHeight, int displayWidth, int displayHeight) {
        final boolean isScreenRound = RoundedCorners.getBuiltInDisplayIsRound(res, displayUniqueId);
        final String spec = getSpecString(res, displayUniqueId);
        if (spec == null || spec.isEmpty()) {
            return createDefaultDisplayShape(displayWidth, displayHeight, isScreenRound);
        }
        final float physicalPixelDisplaySizeRatio = DisplayUtils.getPhysicalPixelDisplaySizeRatio(
                physicalDisplayWidth, physicalDisplayHeight, displayWidth, displayHeight);
        return fromSpecString(spec, physicalPixelDisplaySizeRatio, displayWidth, displayHeight);
    }

    /**
     * @hide
     */
    @NonNull
    public static DisplayShape createDefaultDisplayShape(
            int displayWidth, int displayHeight, boolean isScreenRound) {
        return fromSpecString(createDefaultSpecString(displayWidth, displayHeight, isScreenRound),
                1f, displayWidth, displayHeight);
    }

    /**
     * @hide
     */
    @TestApi
    @NonNull
    public static DisplayShape fromSpecString(@NonNull String spec,
            float physicalPixelDisplaySizeRatio, int displayWidth, int displayHeight) {
        return Cache.getDisplayShape(spec, physicalPixelDisplaySizeRatio, displayWidth,
                    displayHeight);
    }

    private static String createDefaultSpecString(int displayWidth, int displayHeight,
            boolean isCircular) {
        final String spec;
        if (isCircular) {
            final float xRadius = displayWidth / 2f;
            final float yRadius = displayHeight / 2f;
            // Draw a circular display shape.
            spec = "M0," + yRadius
                    // Draw upper half circle with arcTo command.
                    + " A" + xRadius + "," + yRadius + " 0 1,1 " + displayWidth + "," + yRadius
                    // Draw lower half circle with arcTo command.
                    + " A" + xRadius + "," + yRadius + " 0 1,1 0," + yRadius + " Z";
        } else {
            // Draw a rectangular display shape.
            spec = "M0,0"
                    // Draw top edge.
                    + " L" + displayWidth + ",0"
                    // Draw right edge.
                    + " L" + displayWidth + "," + displayHeight
                    // Draw bottom edge.
                    + " L0," + displayHeight
                    // Draw left edge by close command which draws a line from current position to
                    // the initial points (0,0).
                    + " Z";
        }
        return spec;
    }

    /**
     * Gets the display shape svg spec string of a display which is determined by the given display
     * unique id.
     *
     * Loads the default config {@link R.string#config_mainDisplayShape} if
     * {@link R.array#config_displayUniqueIdArray} is not set.
     *
     * @hide
     */
    public static String getSpecString(Resources res, String displayUniqueId) {
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_displayShapeArray);
        final String spec;
        if (index >= 0 && index < array.length()) {
            spec = array.getString(index);
        } else {
            spec = res.getString(R.string.config_mainDisplayShape);
        }
        array.recycle();
        return spec;
    }

    /**
     * @hide
     */
    public DisplayShape setRotation(int rotation) {
        return new DisplayShape(mDisplayShapeSpec, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, rotation, mOffsetX, mOffsetY, mScale);
    }

    /**
     * @hide
     */
    public DisplayShape setOffset(int offsetX, int offsetY) {
        return new DisplayShape(mDisplayShapeSpec, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, mRotation, offsetX, offsetY, mScale);
    }

    /**
     * @hide
     */
    public DisplayShape setScale(float scale) {
        return new DisplayShape(mDisplayShapeSpec, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, mRotation, mOffsetX, mOffsetY, scale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayShapeSpec, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, mRotation, mOffsetX, mOffsetY, mScale);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayShape) {
            DisplayShape ds = (DisplayShape) o;
            return Objects.equals(mDisplayShapeSpec, ds.mDisplayShapeSpec)
                    && mDisplayWidth == ds.mDisplayWidth && mDisplayHeight == ds.mDisplayHeight
                    && mPhysicalPixelDisplaySizeRatio == ds.mPhysicalPixelDisplaySizeRatio
                    && mRotation == ds.mRotation && mOffsetX == ds.mOffsetX
                    && mOffsetY == ds.mOffsetY && mScale == ds.mScale;
        }
        return false;
    }

    @Override
    public String toString() {
        return "DisplayShape{"
                + " spec=" + mDisplayShapeSpec
                + " displayWidth=" + mDisplayWidth
                + " displayHeight=" + mDisplayHeight
                + " physicalPixelDisplaySizeRatio=" + mPhysicalPixelDisplaySizeRatio
                + " rotation=" + mRotation
                + " offsetX=" + mOffsetX
                + " offsetY=" + mOffsetY
                + " scale=" + mScale + "}";
    }

    /**
     * Returns a {@link Path} of the display shape.
     *
     * @return a {@link Path} of the display shape.
     */
    @NonNull
    public Path getPath() {
        return Cache.getPath(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mDisplayShapeSpec);
        dest.writeInt(mDisplayWidth);
        dest.writeInt(mDisplayHeight);
        dest.writeFloat(mPhysicalPixelDisplaySizeRatio);
        dest.writeInt(mRotation);
        dest.writeInt(mOffsetX);
        dest.writeInt(mOffsetY);
        dest.writeFloat(mScale);
    }

    public static final @NonNull Creator<DisplayShape> CREATOR = new Creator<DisplayShape>() {
        @Override
        public DisplayShape createFromParcel(Parcel in) {
            final String spec = in.readString8();
            final int displayWidth = in.readInt();
            final int displayHeight = in.readInt();
            final float ratio = in.readFloat();
            final int rotation = in.readInt();
            final int offsetX = in.readInt();
            final int offsetY = in.readInt();
            final float scale = in.readFloat();
            return new DisplayShape(spec, displayWidth, displayHeight, ratio, rotation, offsetX,
                    offsetY, scale);
        }

        @Override
        public DisplayShape[] newArray(int size) {
            return new DisplayShape[size];
        }
    };

    private static final class Cache {
        private static final Object CACHE_LOCK = new Object();

        @GuardedBy("CACHE_LOCK")
        private static String sCachedSpec;
        @GuardedBy("CACHE_LOCK")
        private static int sCachedDisplayWidth;
        @GuardedBy("CACHE_LOCK")
        private static int sCachedDisplayHeight;
        @GuardedBy("CACHE_LOCK")
        private static float sCachedPhysicalPixelDisplaySizeRatio;
        @GuardedBy("CACHE_LOCK")
        private static DisplayShape sCachedDisplayShape;

        @GuardedBy("CACHE_LOCK")
        private static DisplayShape sCacheForPath;
        @GuardedBy("CACHE_LOCK")
        private static Path sCachedPath;

        static DisplayShape getDisplayShape(String spec, float physicalPixelDisplaySizeRatio,
                int displayWidth, int displayHeight) {
            synchronized (CACHE_LOCK) {
                if (spec.equals(sCachedSpec)
                        && sCachedDisplayWidth == displayWidth
                        && sCachedDisplayHeight == displayHeight
                        && sCachedPhysicalPixelDisplaySizeRatio == physicalPixelDisplaySizeRatio) {
                    return sCachedDisplayShape;
                }
            }

            final DisplayShape shape = new DisplayShape(spec, displayWidth, displayHeight,
                    physicalPixelDisplaySizeRatio, ROTATION_0);

            synchronized (CACHE_LOCK) {
                sCachedSpec = spec;
                sCachedDisplayWidth = displayWidth;
                sCachedDisplayHeight = displayHeight;
                sCachedPhysicalPixelDisplaySizeRatio = physicalPixelDisplaySizeRatio;
                sCachedDisplayShape = shape;
            }
            return shape;
        }

        static Path getPath(@NonNull DisplayShape shape) {
            synchronized (CACHE_LOCK) {
                if (shape.equals(sCacheForPath)) {
                    return sCachedPath;
                }
            }

            final Path path = PathParser.createPathFromPathData(shape.mDisplayShapeSpec);

            if (!path.isEmpty()) {
                final Matrix matrix = new Matrix();
                if (shape.mRotation != ROTATION_0) {
                    RotationUtils.transformPhysicalToLogicalCoordinates(
                            shape.mRotation, shape.mDisplayWidth, shape.mDisplayHeight, matrix);
                }
                if (shape.mPhysicalPixelDisplaySizeRatio != 1f) {
                    matrix.preScale(shape.mPhysicalPixelDisplaySizeRatio,
                            shape.mPhysicalPixelDisplaySizeRatio);
                }
                if (shape.mOffsetX != 0 || shape.mOffsetY != 0) {
                    matrix.postTranslate(shape.mOffsetX, shape.mOffsetY);
                }
                if (shape.mScale != 1f) {
                    matrix.postScale(shape.mScale, shape.mScale);
                }
                path.transform(matrix);
            }

            synchronized (CACHE_LOCK) {
                sCacheForPath = shape;
                sCachedPath = path;
            }
            return path;
        }
    }
}
