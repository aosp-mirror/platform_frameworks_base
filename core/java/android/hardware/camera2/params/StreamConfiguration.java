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
import static android.hardware.camera2.params.StreamConfigurationMap.checkArgumentFormatInternal;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.graphics.PixelFormat;
import android.util.Size;

/**
 * Immutable class to store the available stream
 * {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS configurations} to set up
 * {@link android.view.Surface Surfaces} for creating a {@link CameraCaptureSession capture session}
 * with {@link CameraDevice#createCaptureSession}.
 * <!-- TODO: link to input stream configuration -->
 *
 * <p>This is the authoritative list for all input/output formats (and sizes respectively
 * for that format) that are supported by a camera device.</p>
 *
 * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS
 *
 * @hide
 */
public class StreamConfiguration {

    /**
     * Create a new {@link StreamConfiguration}.
     *
     * @param format image format
     * @param width image width, in pixels (positive)
     * @param height image height, in pixels (positive)
     * @param input true if this is an input configuration, false for output configurations
     *
     * @throws IllegalArgumentException
     *              if width/height were not positive
     *              or if the format was not user-defined in ImageFormat/PixelFormat
     *                  (IMPL_DEFINED is ok)
     *
     * @hide
     */
    public StreamConfiguration(
            final int format, final int width, final int height, final boolean input) {
        mFormat = checkArgumentFormatInternal(format);
        mWidth = checkArgumentPositive(width, "width must be positive");
        mHeight = checkArgumentPositive(height, "height must be positive");
        mInput = input;
    }

    /**
     * Get the internal image {@code format} in this stream configuration.
     *
     * @return an integer format
     *
     * @see ImageFormat
     * @see PixelFormat
     */
    public final int getFormat() {
        return mFormat;
    }


    /**
     * Return the width of the stream configuration.
     *
     * @return width > 0
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * Return the height of the stream configuration.
     *
     * @return height > 0
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Convenience method to return the size of this stream configuration.
     *
     * @return a Size with positive width and height
     */
    public Size getSize() {
        return new Size(mWidth, mHeight);
    }

    /**
     * Determines if this configuration is usable for input streams.
     *
     * <p>Input and output stream configurations are not interchangeable;
     * input stream configurations must be used when configuring inputs.</p>
     *
     * @return {@code true} if input configuration, {@code false} otherwise
     */
    public boolean isInput() {
        return mInput;
    }

    /**
     * Determines if this configuration is usable for output streams.
     *
     * <p>Input and output stream configurations are not interchangeable;
     * out stream configurations must be used when configuring outputs.</p>
     *
     * @return {@code true} if output configuration, {@code false} otherwise
     *
     * @see CameraDevice#createCaptureSession
     */
    public boolean isOutput() {
        return !mInput;
    }

    /**
     * Check if this {@link StreamConfiguration} is equal to another {@link StreamConfiguration}.
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
        if (obj instanceof StreamConfiguration) {
            final StreamConfiguration other = (StreamConfiguration) obj;
            return mFormat == other.mFormat &&
                    mWidth == other.mWidth &&
                    mHeight == other.mHeight &&
                    mInput == other.mInput;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mFormat, mWidth, mHeight, mInput ? 1 : 0);
    }

    protected int mFormat;
    protected int mWidth;
    protected int mHeight;
    protected boolean mInput;
}
