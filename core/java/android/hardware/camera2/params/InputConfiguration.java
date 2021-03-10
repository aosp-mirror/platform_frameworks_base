/*
 * Copyright 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.ImageFormat.Format;
import android.hardware.camera2.params.MultiResolutionStreamInfo;
import android.hardware.camera2.utils.HashCodeHelpers;

import java.util.Collection;
import java.util.List;

import static com.android.internal.util.Preconditions.*;

/**
 * Immutable class to store an input configuration that is used to create a reprocessable capture
 * session.
 *
 * @see android.hardware.camera2.CameraDevice#createReprocessableCaptureSession
 * @see android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
 */
public final class InputConfiguration {

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final boolean mIsMultiResolution;

    /**
     * Create an input configration with the width, height, and user-defined format.
     *
     * <p>Images of a user-defined format are accessible by applications. Use
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP}
     * to query supported input formats</p>
     *
     * @param width Width of the input buffers.
     * @param height Height of the input buffers.
     * @param format Format of the input buffers. One of ImageFormat or PixelFormat constants.
     *
     * @see android.graphics.ImageFormat
     * @see android.graphics.PixelFormat
     * @see android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     */
    public InputConfiguration(int width, int height, int format) {
        mWidth = width;
        mHeight = height;
        mFormat = format;
        mIsMultiResolution = false;
    }

    /**
     * Create an input configration with the format and a list of multi-resolution input stream
     * info.
     *
     * <p>Use {@link
     * android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP}
     * to query supported multi-resolution input formats.</p>
     *
     * <p>To do reprocessing with variable resolution input, the application calls
     * {@link android.media.ImageWriter#queueInputImage ImageWriter.queueInputImage}
     * using an image from an {@link android.media.ImageReader ImageReader} or {@link
     * android.hardware.camera2.MultiResolutionImageReader MultiResolutionImageReader}. See
     * {@link android.hardware.camera2.CameraDevice#createReprocessCaptureRequest} for more
     * details on camera reprocessing.
     * </p>
     *
     * @param multiResolutionInputs A group of multi-resolution input info for the specified format.
     * @param format Format of the input buffers. One of ImageFormat or PixelFormat constants.
     *
     * @see android.graphics.ImageFormat
     * @see android.graphics.PixelFormat
     * @see
     * android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP
     */
    public InputConfiguration(@NonNull Collection<MultiResolutionStreamInfo> multiResolutionInputs,
            @Format int format) {
        checkCollectionNotEmpty(multiResolutionInputs, "Input multi-resolution stream info");
        //TODO: Pick the default mode stream info for ultra-high resolution sensor camera
        MultiResolutionStreamInfo info = multiResolutionInputs.iterator().next();
        mWidth = info.getWidth();
        mHeight = info.getHeight();
        mFormat = format;
        mIsMultiResolution = true;
    }

    /**
     * @hide
     */
    public InputConfiguration(int width, int height, int format, boolean isMultiResolution) {
        mWidth = width;
        mHeight = height;
        mFormat = format;
        mIsMultiResolution = isMultiResolution;
    }

    /**
     * Get the width of this input configration.
     *
     * @return width of this input configuration.
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * Get the height of this input configration.
     *
     * @return height of this input configuration.
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Get the format of this input configration.
     *
     * @return format of this input configuration.
     */
    public int getFormat() {
        return mFormat;
    }

    /**
     * Whether this input configuration is of multi-resolution.
     *
     * <p>An multi-resolution InputConfiguration means that the reprocessing session created from it
     * allows input images of different sizes.</p>
     *
     * @return  this input configuration is multi-resolution or not.
     */
    public boolean isMultiResolution() {
        return mIsMultiResolution;
    }

    /**
     * Check if this InputConfiguration is equal to another InputConfiguration.
     *
     * <p>Two input configurations are equal if and only if they have the same widths, heights, and
     * formats.</p>
     *
     * @param obj the object to compare this instance with.
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise.
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof InputConfiguration)) {
            return false;
        }

        InputConfiguration otherInputConfig = (InputConfiguration) obj;

        if (otherInputConfig.getWidth() == mWidth &&
                otherInputConfig.getHeight() == mHeight &&
                otherInputConfig.getFormat() == mFormat &&
                otherInputConfig.isMultiResolution() == mIsMultiResolution) {
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mWidth, mHeight, mFormat, mIsMultiResolution ? 1 : 0);
    }

    /**
     * Return this {@link InputConfiguration} as a string representation.
     *
     * <p> {@code "InputConfiguration(w:%d, h:%d, format:%d, isMultiResolution:%d)"},
     * where {@code %d} represents the width, height, format, and multi-resolution flag
     * respectively.</p>
     *
     * @return string representation of {@link InputConfiguration}
     */
    @Override
    public String toString() {
        return String.format("InputConfiguration(w:%d, h:%d, format:%d, isMultiResolution %b)",
                mWidth, mHeight, mFormat, mIsMultiResolution);
    }
}
