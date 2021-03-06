/*
 * Copyright (C) 2021 The Android Open Source Project
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

import java.util.Objects;

/**
 * A utility class describing the properties of one stream of fixed-size image buffers
 * backing a multi-resolution image stream.
 *
 * <p>A group of {@link MultiResolutionStreamInfo} are used to describe the properties of a
 * multi-resolution image stream for a particular format. The
 * {@link android.hardware.camera2.MultiResolutionImageReader} class represents a
 * multi-resolution output stream, and is constructed using a group of
 * {@link MultiResolutionStreamInfo}. A group of {@link MultiResolutionStreamInfo} can also be used
 * to create a multi-resolution reprocessable camera capture session. See
 * {@link android.hardware.camera2.params.InputConfiguration} for details.</p>
 *
 * @see InputConfiguration
 * @see android.hardware.camera2.MultiResolutionImageReader
 */
public class MultiResolutionStreamInfo {
    private int mStreamWidth;
    private int mStreamHeight;
    private String mPhysicalCameraId;

    /**
     * Create a new {@link MultiResolutionStreamInfo}.
     *
     * <p>This class creates a {@link MultiResolutionStreamInfo} using image width, image height,
     * and the physical camera Id images originate from.</p>
     *
     * <p>Normally applications do not need to create these directly. Use {@link
     * MultiResolutionStreamConfigurationMap#getOutputInfo} or {@link
     * MultiResolutionStreamConfigurationMap#getInputInfo} to obtain them for a particular format
     * instead.</p>
     */
    public MultiResolutionStreamInfo(int streamWidth, int streamHeight,
            @NonNull String physicalCameraId) {
        mStreamWidth = streamWidth;
        mStreamHeight = streamHeight;
        mPhysicalCameraId = physicalCameraId;
    }

    /**
     * The width of this particular image buffer stream in pixels.
     */
    public int getWidth() {
        return mStreamWidth;
    }

    /**
     * The height of this particular image buffer stream in pixels.
     */
    public int getHeight() {
        return mStreamHeight;
    }

    /**
     * The physical camera Id of this particular image buffer stream.
     */
    public @NonNull String getPhysicalCameraId() {
        return mPhysicalCameraId;
    }

    /**
     * Check if this {@link MultiResolutionStreamInfo} is equal to another
     * {@link MultiResolutionStreamInfo}.
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
        if (obj instanceof MultiResolutionStreamInfo) {
            final MultiResolutionStreamInfo other = (MultiResolutionStreamInfo) obj;
            return mStreamWidth == other.mStreamWidth &&
                    mStreamHeight == other.mStreamHeight &&
                    mPhysicalCameraId.equals(other.mPhysicalCameraId);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                mStreamWidth, mStreamHeight, mPhysicalCameraId);
    }
}
