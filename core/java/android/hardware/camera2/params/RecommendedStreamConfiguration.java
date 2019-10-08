/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.utils.HashCodeHelpers;

/**
 * Immutable class to store the recommended stream configurations to set up
 * {@link android.view.Surface Surfaces} for creating a {@link CameraCaptureSession capture session}
 * with {@link CameraDevice#createCaptureSession}.
 *
 * @see CameraCharacteristics#getRecommendedStreamConfigurationMap
 *
 * @hide
 */
public final class RecommendedStreamConfiguration extends StreamConfiguration{

    /**
     * Create a new {@link RecommendedStreamConfiguration}.
     *
     * @param format image format
     * @param width image width, in pixels (positive)
     * @param height image height, in pixels (positive)
     * @param input true if this is an input configuration, false for output configurations
     * @param usecaseBitmap Use case bitmap
     *
     * @throws IllegalArgumentException
     *              if width/height were not positive
     *              or if the format was not user-defined in ImageFormat/PixelFormat
     *                  (IMPL_DEFINED is ok)
     *
     * @hide
     */
    public RecommendedStreamConfiguration(
            final int format, final int width, final int height, final boolean input, final int
            usecaseBitmap) {
        super(format, width, height, input);
        mUsecaseBitmap = usecaseBitmap;
    }

    /**
     * Return usecase bitmap.
     *
     * @return usecase bitmap
     */
    public int getUsecaseBitmap() {
        return mUsecaseBitmap;
    }

    /**
     * Check if this {@link RecommendedStreamConfiguration} is equal to another
     * {@link RecommendedStreamConfiguration}.
     *
     * <p>Two vectors are only equal if and only if each of the respective elements is equal.</p>
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
        if (obj instanceof RecommendedStreamConfiguration) {
            final RecommendedStreamConfiguration other = (RecommendedStreamConfiguration) obj;
            return mFormat == other.mFormat &&
                    mWidth == other.mWidth &&
                    mHeight == other.mHeight &&
                    mUsecaseBitmap == other.mUsecaseBitmap &&
                    mInput == other.mInput;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mFormat, mWidth, mHeight, mInput ? 1 : 0, mUsecaseBitmap);
    }

    private final int mUsecaseBitmap;
}
