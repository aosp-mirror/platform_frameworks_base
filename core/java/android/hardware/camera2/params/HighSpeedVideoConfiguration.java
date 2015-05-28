/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.*;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.util.Range;
import android.util.Size;

/**
 * Immutable class to store the available
 * {@link CameraCharacteristics#CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS high speed video
 *  configurations}
 *
 * @see CameraCharacteristics#CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS
 *
 * @hide
 */
public final class HighSpeedVideoConfiguration {
    static final private int HIGH_SPEED_MAX_MINIMAL_FPS = 120;

    /**
     * Create a new {@link HighSpeedVideoConfiguration}.
     *
     * @param width image width, in pixels (positive)
     * @param height image height, in pixels (positive)
     * @param fpsMin minimum frames per second for the configuration (positive)
     * @param fpsMax maximum frames per second for the configuration (larger or equal to 60)
     *
     * @throws IllegalArgumentException
     *              if width/height/fpsMin were not positive or fpsMax less than 60
     *
     * @hide
     */
    public HighSpeedVideoConfiguration(
            final int width, final int height, final int fpsMin, final int fpsMax,
            final int batchSizeMax) {
        if (fpsMax < HIGH_SPEED_MAX_MINIMAL_FPS) {
            throw new IllegalArgumentException("fpsMax must be at least " +
                    HIGH_SPEED_MAX_MINIMAL_FPS);
        }
        mFpsMax = fpsMax;
        mWidth = checkArgumentPositive(width, "width must be positive");
        mHeight = checkArgumentPositive(height, "height must be positive");
        mFpsMin = checkArgumentPositive(fpsMin, "fpsMin must be positive");
        mSize = new Size(mWidth, mHeight);
        mBatchSizeMax = checkArgumentPositive(batchSizeMax, "batchSizeMax must be positive");
        mFpsRange = new Range<Integer>(mFpsMin, mFpsMax);
    }

    /**
     * Return the width of the high speed video configuration.
     *
     * @return width > 0
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * Return the height of the high speed video configuration.
     *
     * @return height > 0
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Return the minimum frame per second of the high speed video configuration.
     *
     * @return fpsMin > 0
     */
    public int getFpsMin() {
        return mFpsMin;
    }

    /**
     * Return the maximum frame per second of the high speed video configuration.
     *
     * @return fpsMax >= 60
     */
    public int getFpsMax() {
        return mFpsMax;
    }

    /**
     * Convenience method to return the size of this high speed video configuration.
     *
     * @return a Size with positive width and height
     */
    public Size getSize() {
        return mSize;
    }

    /**
     * Convenience method to return the max batch size of this high speed video configuration.
     *
     * @return the maximal batch size for this high speed video configuration
     */
    public int getBatchSizeMax() {
        return mBatchSizeMax;
    }

    /**
     * Convenience method to return the FPS range of this high speed video configuration.
     *
     * @return a Range with high bound >= {@value #HIGH_SPEED_MAX_MINIMAL_FPS}
     */
    public Range<Integer> getFpsRange() {
        return mFpsRange;
    }

    /**
     * Check if this {@link HighSpeedVideoConfiguration} is equal to another
     * {@link HighSpeedVideoConfiguration}.
     *
     * <p>Two configurations are equal if and only if each of the respective elements is equal.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof HighSpeedVideoConfiguration) {
            final HighSpeedVideoConfiguration other = (HighSpeedVideoConfiguration) obj;
            return mWidth == other.mWidth &&
                    mHeight == other.mHeight &&
                    mFpsMin == other.mFpsMin &&
                    mFpsMax == other.mFpsMax &&
                    mBatchSizeMax == other.mBatchSizeMax;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mWidth, mHeight, mFpsMin, mFpsMax);
    }

    private final int mWidth;
    private final int mHeight;
    private final int mFpsMin;
    private final int mFpsMax;
    private final int mBatchSizeMax;
    private final Size mSize;
    private final Range<Integer> mFpsRange;
}
