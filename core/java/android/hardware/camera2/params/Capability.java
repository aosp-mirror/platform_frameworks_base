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
    private final Size mMaxStreamingSize;
    private final Range<Float> mZoomRatioRange;

    /**
     * Create a new Capability object.
     *
     * <p>The mode argument can be any integer value. maxStreamingWidth and maxStreamingHeight
     * must be non-negative, while minZoomRatio and maxZoomRatio must be strictly
     * positive.</p>
     *
     * <p>This constructor is public to allow for easier application testing by
     * creating custom object instances. It's not necessary to construct these
     * objects during normal use of the camera API.</p>
     *
     * @param mode supported mode for a camera capability.
     * @param maxStreamingSize The maximum streaming size for this mode
     * @param zoomRatioRange the minimum/maximum zoom ratio this mode supports
     *
     * @throws IllegalArgumentException if any of the arguments are not valid
     */
    public Capability(int mode, @NonNull Size maxStreamingSize,
            @NonNull Range<Float> zoomRatioRange) {
        mMode = mode;
        checkArgumentNonnegative(maxStreamingSize.getWidth(),
                "maxStreamingSize.getWidth() must be nonnegative");
        checkArgumentNonnegative(maxStreamingSize.getHeight(),
                "maxStreamingSize.getHeight() must be nonnegative");
        mMaxStreamingSize = maxStreamingSize;

        if (zoomRatioRange.getLower() > zoomRatioRange.getUpper()) {
            throw new IllegalArgumentException("zoomRatioRange.getLower() "
                    + zoomRatioRange.getLower() + " is greater than zoomRatioRange.getUpper() "
                    + zoomRatioRange.getUpper());
        }
        checkArgumentPositive(zoomRatioRange.getLower(),
                "zoomRatioRange.getLower() must be positive");
        checkArgumentPositive(zoomRatioRange.getUpper(),
                "zoomRatioRange.getUpper() must be positive");
        mZoomRatioRange = zoomRatioRange;
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
        return mMaxStreamingSize;
    }

    /**
     * Return the zoom ratio range of this capability.
     *
     * @return The supported zoom ratio range supported by this capability
     */
    public @NonNull Range<Float> getZoomRatioRange() {
        return mZoomRatioRange;
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
                    && mMaxStreamingSize.equals(other.mMaxStreamingSize)
                    && mZoomRatioRange.equals(other.mZoomRatioRange));
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mMode, mMaxStreamingSize.getWidth(),
                mMaxStreamingSize.getHeight(), mZoomRatioRange.getLower(),
                mZoomRatioRange.getUpper());
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
                mMode, mMaxStreamingSize.getWidth(), mMaxStreamingSize.getHeight(),
                mZoomRatioRange.getLower(), mZoomRatioRange.getUpper());
    }
}
