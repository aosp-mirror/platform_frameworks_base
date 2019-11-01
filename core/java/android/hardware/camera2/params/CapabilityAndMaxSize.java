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

import static com.android.internal.util.Preconditions.checkArgumentInRange;
import static com.android.internal.util.Preconditions.checkArgumentNonnegative;

import android.annotation.NonNull;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.util.Size;

/**
 * Immutable class to store the available camera capability and its
 * corresponding maximum streaming dimensions.
 *
 * @see CameraCharacteristics#CONTROL_AVAILABLE_BOKEH_CAPABILITIES
 */

public final class CapabilityAndMaxSize {
    /**
     * @hide
     */
    public static final int COUNT = 3;

    private final int mMode;
    private final int mMaxStreamingWidth;
    private final int mMaxStreamingHeight;

    /**
     * Create a new CapabilityAndMaxSize object.
     *
     * @param mode supported mode for a camera capability.
     * @param maxStreamingWidth width >= 0
     * @param maxStreamingHeight height >= 0
     *
     * @hide
     */
    public CapabilityAndMaxSize(int mode, int maxStreamingWidth, int maxStreamingHeight) {
        mMode = mode;
        mMaxStreamingWidth = checkArgumentNonnegative(maxStreamingWidth,
                "maxStreamingWidth must be nonnegative");
        mMaxStreamingHeight = checkArgumentNonnegative(maxStreamingHeight,
                "maxStreamingHeight must be nonnegative");
    }

    /**
     * Return the supported mode for this capability.
     *
     * @return One of supported modes for the capability. For example, for available bokeh modes,
     * this will be one of {@link CameraMetadata#CONTROL_BOKEH_MODE_OFF},
     * {@link CameraMetadata#CONTROL_BOKEH_MODE_STILL_CAPTURE}, and
     * {@link CameraMetadata#CONTROL_BOKEH_MODE_CONTINUOUS}.
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
     * Compare two CapabilityAndMaxSize objects to see if they are equal.
     *
     * @param obj Another CapabilityAndMaxSize object
     *
     * @return {@code true} if the mode and max size are equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof CapabilityAndMaxSize) {
            final CapabilityAndMaxSize other = (CapabilityAndMaxSize) obj;
            return (mMode == other.mMode
                    && mMaxStreamingWidth == other.mMaxStreamingWidth
                    && mMaxStreamingHeight == other.mMaxStreamingHeight);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mMode, mMaxStreamingWidth, mMaxStreamingHeight);
    }

    /**
     * Return the CapabilityAndMaxSize as a string representation
     * {@code "(mode:%d, maxStreamingSize:%d x %d)"}.
     *
     * @return string representation of the capability and max streaming size.
     */
    @Override
    public String toString() {
        return String.format("(mode:%d, maxStreamingSize:%d x %d)",
                mMode, mMaxStreamingWidth, mMaxStreamingHeight);
    }
}
