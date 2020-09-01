/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.hardware.camera2.params;

import static com.android.internal.util.Preconditions.checkArgumentNonnegative;
import static com.android.internal.util.Preconditions.checkArgumentPositive;

import android.annotation.NonNull;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.util.Range;
import android.util.Size;

/**
 * Immutable class to store the camera capability, its corresponding maximum
 * streaming dimension and zoom range.
 *
 * @see CameraCharacteristics#CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES
 */

public final class Capability {
    /**
     * @hide
     */
    public static final int COUNT = 3;

    private final int mMode;
    private final int mMaxStreamingWidth;
    private final int mMaxStreamingHeight;
    private final float mMinZoomRatio;
    private final float mMaxZoomRatio;

    /**
     * Create a new Capability object.
     *
     * @param mode supported mode for a camera capability.
     * @param maxStreamingWidth The width of the maximum streaming size for this mode
     * @param maxStreamingHeight The height of the maximum streaming size for this mode
     * @param minZoomRatio the minimum zoom ratio this mode supports
     * @param maxZoomRatio the maximum zoom ratio this mode supports
     *
     * @throws IllegalArgumentException if any of the argument is not valid
     * @hide
     */
    public Capability(int mode, int maxStreamingWidth, int maxStreamingHeight,
            float minZoomRatio, float maxZoomRatio) {
        mMode = mode;
        mMaxStreamingWidth = checkArgumentNonnegative(maxStreamingWidth,
                "maxStreamingWidth must be nonnegative");
        mMaxStreamingHeight = checkArgumentNonnegative(maxStreamingHeight,
                "maxStreamingHeight must be nonnegative");

        if (minZoomRatio > maxZoomRatio) {
            throw new IllegalArgumentException("minZoomRatio " + minZoomRatio
                    + " is greater than maxZoomRatio " + maxZoomRatio);
        }
        mMinZoomRatio = checkArgumentPositive(minZoomRatio,
                "minZoomRatio must be positive");
        mMaxZoomRatio = checkArgumentPositive(maxZoomRatio,
                "maxZoomRatio must be positive");
    }

    /**
     * Return the supported mode for this capability.
     *
     * @return One of supported modes for the capability. For example, for available extended
     * scene modes, this will be one of {@link CameraMetadata#CONTROL_EXTENDED_SCENE_MODE_DISABLED},
     * {@link CameraMetadata#CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE}, and
     * {@link CameraMetadata#CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS}.
     */
    public int getMode() {
        return mMode;
    }

    /**
     * Return the maximum streaming dimension of this capability.
     *
     * @return a new {@link Size} with non-negative width and height
     */
    public @NonNull Size getMaxStreamingSize() {
        return new Size(mMaxStreamingWidth, mMaxStreamingHeight);
    }

    /**
     * Return the zoom ratio range of this capability.
     *
     * @return The supported zoom ratio range supported by this capability
     */
    public @NonNull Range<Float> getZoomRatioRange() {
        return new Range<Float>(mMinZoomRatio, mMaxZoomRatio);
    }


    /**
     * Compare two Capability objects to see if they are equal.
     *
     * @param obj Another Capability object
     *
     * @return {@code true} if the mode, max size and zoom ratio range are equal,
     *         {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof Capability) {
            final Capability other = (Capability) obj;
            return (mMode == other.mMode
                    && mMaxStreamingWidth == other.mMaxStreamingWidth
                    && mMaxStreamingHeight == other.mMaxStreamingHeight
                    && mMinZoomRatio == other.mMinZoomRatio
                    && mMaxZoomRatio == other.mMaxZoomRatio);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mMode, mMaxStreamingWidth, mMaxStreamingHeight,
                mMinZoomRatio, mMaxZoomRatio);
    }

    /**
     * Return the Capability as a string representation
     * {@code "(mode:%d, maxStreamingSize:%d x %d, zoomRatio: %f-%f)"}.
     *
     * @return string representation of the capability and max streaming size.
     */
    @Override
    public String toString() {
        return String.format("(mode:%d, maxStreamingSize:%d x %d, zoomRatio: %f-%f)",
                mMode, mMaxStreamingWidth, mMaxStreamingHeight, mMinZoomRatio,
                mMaxZoomRatio);
    }
}
