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

import static com.android.internal.util.Preconditions.*;
import static android.hardware.camera2.params.StreamConfigurationMap.checkArgumentFormat;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCharacteristics.Key;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.graphics.PixelFormat;
import android.media.CamcorderProfile;
import android.util.Size;
import android.util.Log;
import android.util.Pair;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Immutable class to store the available mandatory stream combination.
 *
 * <p>The individual stream combinations are generated according to the guidelines
 * at {@link CameraDevice#createCaptureSession}.</p>
 *
 * <p>The list of stream combinations is available by invoking
 * {@link CameraCharacteristics#get} and passing key
 * {@link android.hardware.camera2.CameraCharacteristics#SCALER_MANDATORY_STREAM_COMBINATIONS}.</p>
 */
public final class MandatoryStreamCombination {
    private static final String TAG = "MandatoryStreamCombination";
    /**
     * Immutable class to store available mandatory stream information.
     */
    public static final class MandatoryStreamInformation {
        private final int mFormat;
        private final ArrayList<Size> mAvailableSizes = new ArrayList<Size> ();
        private final boolean mIsInput;

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
           @param availableSizes List of possible stream sizes.
         * @param format Image format.
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, int format) {
            this(availableSizes, format, /*isInput*/false);
        }

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
           @param availableSizes List of possible stream sizes.
         * @param format Image format.
         * @param isInput Flag indicating whether this stream is input.
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, int format,
                boolean isInput) {
            if (availableSizes.isEmpty()) {
                throw new IllegalArgumentException("No available sizes");
            }
            mAvailableSizes.addAll(availableSizes);
            mFormat = checkArgumentFormat(format);
            mIsInput = isInput;
        }

        /**
         * Confirms whether or not this is an input stream.
         * @return true in case the stream is input, false otherwise.
         */
        public boolean isInput() {
            return mIsInput;
        }

        /**
         * Return the list of available sizes for this mandatory stream.
         *
         * <p>Per documented {@link CameraDevice#createCaptureSession guideline} the largest
         * resolution in the result will be tested and guaranteed to work. If clients want to use
         * smaller sizes, then the resulting
         * {@link android.hardware.camera2.params.SessionConfiguration session configuration} can
         * be tested either by calling {@link CameraDevice#createCaptureSession} or
         * {@link CameraDevice#isSessionConfigurationSupported}.
         *
         * @return non-modifiable ascending list of available sizes.
         */
        public List<Size> getAvailableSizes() {
            return Collections.unmodifiableList(mAvailableSizes);
        }

        /**
         * Retrieve the mandatory stream {@code format}.
         *
         * @return integer format.
         */
        public int getFormat() {
            return mFormat;
        }

        /**
         * Check if this {@link MandatoryStreamInformation} is equal to another
         * {@link MandatoryStreamInformation}.
         *
         * <p>Two vectors are only equal if and only if each of the respective elements is
         * equal.</p>
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
            if (obj instanceof MandatoryStreamInformation) {
                final MandatoryStreamInformation other = (MandatoryStreamInformation) obj;
                if ((mFormat != other.mFormat) || (mIsInput != other.mIsInput) ||
                        (mAvailableSizes.size() != other.mAvailableSizes.size())) {
                    return false;
                }

                return mAvailableSizes.equals(other.mAvailableSizes);
            }

            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return HashCodeHelpers.hashCode(mFormat, Boolean.hashCode(mIsInput),
                    mAvailableSizes.hashCode());
        }
    }

    private final String mDescription;
    private final boolean mIsReprocessable;
    private final ArrayList<MandatoryStreamInformation> mStreamsInformation =
            new ArrayList<MandatoryStreamInformation>();
    /**
     * Create a new {@link MandatoryStreamCombination}.
     *
     * @param streamsInformation list of available streams in the stream combination.
     * @param description Summary of the stream combination use case.
     * @param isReprocessable Flag whether the mandatory stream combination is reprocessable.
     *
     * @throws IllegalArgumentException
     *              if stream information is empty
     * @hide
     */
    public MandatoryStreamCombination(@NonNull List<MandatoryStreamInformation> streamsInformation,
            String description, boolean isReprocessable) {
        if (streamsInformation.isEmpty()) {
            throw new IllegalArgumentException("Empty stream information");
        }
        mStreamsInformation.addAll(streamsInformation);
        mDescription = description;
        mIsReprocessable = isReprocessable;
    }

    /**
     * Get the mandatory stream combination description.
     *
     * @return String with the mandatory combination description.
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * Indicates whether the mandatory stream combination is reprocessable.
     *
     * @return {@code true} in case the mandatory stream combination contains an input,
     *         {@code false} otherwise.
     */
    public boolean isReprocessable() {
        return mIsReprocessable;
    }

    /**
     * Get information about each stream in the mandatory combination.
     *
     * @return Non-modifiable list of stream information.
     *
     */
    public List<MandatoryStreamInformation> getStreamsInformation() {
        return Collections.unmodifiableList(mStreamsInformation);
    }

    /**
     * Check if this {@link MandatoryStreamCombination} is equal to another
     * {@link MandatoryStreamCombination}.
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
        if (obj instanceof MandatoryStreamCombination) {
            final MandatoryStreamCombination other = (MandatoryStreamCombination) obj;
            if ((mDescription != other.mDescription) ||
                    (mIsReprocessable != other.mIsReprocessable) ||
                    (mStreamsInformation.size() != other.mStreamsInformation.size())) {
                return false;
            }

            return mStreamsInformation.equals(other.mStreamsInformation);
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(Boolean.hashCode(mIsReprocessable), mDescription.hashCode(),
                mStreamsInformation.hashCode());
    }

    private static enum SizeThreshold { VGA, PREVIEW, RECORD, MAXIMUM }
    private static enum ReprocessType { NONE, PRIVATE, YUV }
    private static final class StreamTemplate {
        public int mFormat;
        public SizeThreshold mSizeThreshold;
        public boolean mIsInput;
        public StreamTemplate(int format, SizeThreshold sizeThreshold) {
            this(format, sizeThreshold, /*isInput*/false);
        }

        public StreamTemplate(int format, SizeThreshold sizeThreshold, boolean isInput) {
            mFormat = format;
            mSizeThreshold = sizeThreshold;
            mIsInput = isInput;
        }
    }

    private static final class StreamCombinationTemplate {
        public StreamTemplate[] mStreamTemplates;
        public String mDescription;
        public ReprocessType mReprocessType;

        public StreamCombinationTemplate(StreamTemplate[] streamTemplates, String description) {
            this(streamTemplates, description, /*reprocessType*/ReprocessType.NONE);
        }

        public StreamCombinationTemplate(StreamTemplate[] streamTemplates, String description,
                ReprocessType reprocessType) {
            mStreamTemplates = streamTemplates;
            mReprocessType = reprocessType;
            mDescription = description;
        }
    }

    private static StreamCombinationTemplate sLegacyCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.MAXIMUM) },
                "Simple preview, GPU video processing, or no-preview video recording"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "No-viewfinder still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "In-application video/image processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "Standard still imaging"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "In-app processing plus still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW) },
                "Standard recording"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW) },
                "Preview plus in-app processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "Still capture plus in-app processing")
    };

    private static StreamCombinationTemplate sLimitedCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD)},
                "High-resolution video recording with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD)},
                "High-resolution in-app video processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD) },
                "Two-input in-app video processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.RECORD) },
                "High-resolution recording with video snapshot"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.RECORD) },
                "High-resolution in-app processing with video snapshot"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "Two-input in-app processing with still capture")
    };

    private static StreamCombinationTemplate sBurstCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.MAXIMUM) },
                "Maximum-resolution GPU processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Maximum-resolution in-app processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Maximum-resolution two-input in-app processsing")
    };

    private static StreamCombinationTemplate sFullCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.MAXIMUM) },
                "Maximum-resolution GPU processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Maximum-resolution in-app processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Maximum-resolution two-input in-app processsing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "Video recording with maximum-size video snapshot"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Standard video recording plus maximum-resolution in-app processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Preview plus two-input maximum-resolution in-app processing")
    };

    private static StreamCombinationTemplate sRawCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR,  SizeThreshold.MAXIMUM) },
                "No-preview DNG capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Standard DNG capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "In-app processing plus DNG capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Video recording with DNG capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Preview with in-app processing and DNG capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Two-input in-app processing plus DNG capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Still capture with simultaneous JPEG and DNG"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "In-app processing with simultaneous JPEG and DNG")
    };

    private static StreamCombinationTemplate sLevel3Combinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "In-app viewfinder analysis with dynamic selection of output format"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "In-app viewfinder analysis with dynamic selection of output format")
    };

    private static StreamCombinationTemplate sLimitedPrivateReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "No-viewfinder still image reprocessing",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL(Zero-Shutter-Lag) still imaging",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL still and in-app processing imaging",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL in-app processing with still capture",
                /*reprocessType*/ ReprocessType.PRIVATE),
    };

    private static StreamCombinationTemplate sLimitedYUVReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "No-viewfinder still image reprocessing",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL(Zero-Shutter-Lag) still imaging",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL still and in-app processing imaging",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL in-app processing with still capture",
                /*reprocessType*/ ReprocessType.YUV),
    };

    private static StreamCombinationTemplate sFullPrivateReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD) },
                "High-resolution ZSL in-app video processing with regular preview",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Maximum-resolution ZSL in-app processing with regular preview",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM) },
                "Maximum-resolution two-input ZSL in-app processing",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL still capture and in-app processing",
                /*reprocessType*/ ReprocessType.PRIVATE),
    };

    private static StreamCombinationTemplate sFullYUVReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW) },
                "Maximum-resolution multi-frame image fusion in-app processing with regular "
                + "preview",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW) },
                "Maximum-resolution multi-frame image fusion two-input in-app processing",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD) },
                "High-resolution ZSL in-app video processing with regular preview",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "ZSL still capture and in-app processing",
                /*reprocessType*/ ReprocessType.YUV),
    };

    private static StreamCombinationTemplate sRAWPrivateReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL in-app processing and DNG capture",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL in-app processing and preview with DNG capture",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL two-input in-app processing and DNG capture",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL still capture and preview with DNG capture",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL in-app processing with still capture and DNG capture",
                /*reprocessType*/ ReprocessType.PRIVATE),
    };

    private static StreamCombinationTemplate sRAWYUVReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL in-app processing and DNG capture",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL in-app processing and preview with DNG capture",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL two-input in-app processing and DNG capture",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL still capture and preview with DNG capture",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "Mutually exclusive ZSL in-app processing with still capture and DNG capture",
                /*reprocessType*/ ReprocessType.YUV),
    };

    private static StreamCombinationTemplate sLevel3PrivateReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "In-app viewfinder analysis with ZSL, RAW, and JPEG reprocessing output",
                /*reprocessType*/ ReprocessType.PRIVATE),
    };

    private static StreamCombinationTemplate sLevel3YUVReprocCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM) },
                "In-app viewfinder analysis with ZSL and RAW",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.VGA),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM) },
                "In-app viewfinder analysis with ZSL, RAW, and JPEG reprocessing output",
                /*reprocessType*/ ReprocessType.YUV),
    };

    /**
     * Helper builder class to generate a list of available mandatory stream combinations.
     * @hide
     */
    public static final class Builder {
        private Size mDisplaySize;
        private List<Integer> mCapabilities;
        private int mHwLevel, mCameraId;
        private StreamConfigurationMap mStreamConfigMap;

        private final Size kPreviewSizeBound = new Size(1920, 1088);

        /**
         * Helper class to be used to generate the available mandatory stream combinations.
         *
         * @param cameraId Current camera id.
         * @param hwLevel The camera HW level as reported by android.info.supportedHardwareLevel.
         * @param displaySize The device display size.
         * @param capabilities The camera device capabilities.
         * @param sm The camera device stream configuration map.
         */
        public Builder(int cameraId, int hwLevel, @NonNull Size displaySize,
                @NonNull List<Integer> capabilities, @NonNull StreamConfigurationMap sm) {
            mCameraId = cameraId;
            mDisplaySize = displaySize;
            mCapabilities = capabilities;
            mStreamConfigMap = sm;
            mHwLevel = hwLevel;
        }

        /**
         * Retrieve a list of all available mandatory stream combinations.
         *
         * @return a non-modifiable list of supported mandatory stream combinations or
         *         null in case device is not backward compatible or the method encounters
         *         an error.
         */
        public List<MandatoryStreamCombination> getAvailableMandatoryStreamCombinations() {
            if (!isColorOutputSupported()) {
                Log.v(TAG, "Device is not backward compatible!");
                return null;
            }

            if ((mCameraId < 0) && !isExternalCamera()) {
                Log.i(TAG, "Invalid camera id");
                return null;
            }

            ArrayList<StreamCombinationTemplate> availableTemplates =
                    new ArrayList<StreamCombinationTemplate> ();
            if (isHardwareLevelAtLeastLegacy()) {
                availableTemplates.addAll(Arrays.asList(sLegacyCombinations));
            }

            // External devices are identical to limited devices w.r.t. stream combinations.
            if (isHardwareLevelAtLeastLimited() || isExternalCamera()) {
                availableTemplates.addAll(Arrays.asList(sLimitedCombinations));

                if (isPrivateReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sLimitedPrivateReprocCombinations));
                }

                if (isYUVReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sLimitedYUVReprocCombinations));
                }

            }

            if (isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)) {
                availableTemplates.addAll(Arrays.asList(sBurstCombinations));
            }

            if (isHardwareLevelAtLeastFull()) {
                availableTemplates.addAll(Arrays.asList(sFullCombinations));

                if (isPrivateReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sFullPrivateReprocCombinations));
                }

                if (isYUVReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sFullYUVReprocCombinations));
                }

            }

            if (isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                availableTemplates.addAll(Arrays.asList(sRawCombinations));

                if (isPrivateReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sRAWPrivateReprocCombinations));
                }

                if (isYUVReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sRAWYUVReprocCombinations));
                }

            }

            if (isHardwareLevelAtLeastLevel3()) {
                availableTemplates.addAll(Arrays.asList(sLevel3Combinations));

                if (isPrivateReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sLevel3PrivateReprocCombinations));
                }

                if (isYUVReprocessingSupported()) {
                    availableTemplates.addAll(Arrays.asList(sLevel3YUVReprocCombinations));
                }

            }

            return generateAvailableCombinations(availableTemplates);
        }

        /**
         * Helper method to generate the available stream combinations given the
         * list of available combination templates.
         *
         * @param availableTemplates a list of templates supported by the camera device.
         * @return a non-modifiable list of supported mandatory stream combinations or
         *         null in case of errors.
         */
        private List<MandatoryStreamCombination> generateAvailableCombinations(
                ArrayList<StreamCombinationTemplate> availableTemplates) {
            if (availableTemplates.isEmpty()) {
                Log.e(TAG, "No available stream templates!");
                return null;
            }

            HashMap<Pair<SizeThreshold, Integer>, List<Size>> availableSizes =
                enumerateAvailableSizes();
            if (availableSizes == null) {
                Log.e(TAG, "Available size enumeration failed!");
                return null;
            }

            // RAW only uses MAXIMUM size threshold
            Size[] rawSizes = mStreamConfigMap.getOutputSizes(ImageFormat.RAW_SENSOR);
            ArrayList<Size> availableRawSizes = new ArrayList<Size>();
            if (rawSizes != null) {
                availableRawSizes.ensureCapacity(rawSizes.length);
                availableRawSizes.addAll(Arrays.asList(rawSizes));
            }

            Size maxPrivateInputSize = new Size(0, 0);
            if (isPrivateReprocessingSupported()) {
                maxPrivateInputSize = getMaxSize(mStreamConfigMap.getInputSizes(
                            ImageFormat.PRIVATE));
            }

            Size maxYUVInputSize = new Size(0, 0);
            if (isYUVReprocessingSupported()) {
                maxYUVInputSize = getMaxSize(mStreamConfigMap.getInputSizes(
                            ImageFormat.YUV_420_888));
            }

            // Generate the available mandatory stream combinations given the supported templates
            // and size ranges.
            ArrayList<MandatoryStreamCombination> availableStreamCombinations =
                    new ArrayList<MandatoryStreamCombination>();
            availableStreamCombinations.ensureCapacity(availableTemplates.size());
            for (StreamCombinationTemplate combTemplate : availableTemplates) {
                ArrayList<MandatoryStreamInformation> streamsInfo =
                        new ArrayList<MandatoryStreamInformation>();
                streamsInfo.ensureCapacity(combTemplate.mStreamTemplates.length);
                boolean isReprocessable = combTemplate.mReprocessType != ReprocessType.NONE;
                if (isReprocessable) {
                    // The first and second streams in a reprocessable combination have the
                    // same size and format. The first is the input and the second is the output
                    // used for generating the subsequent input buffers.
                    ArrayList<Size> inputSize = new ArrayList<Size>();
                    int format;
                    if (combTemplate.mReprocessType == ReprocessType.PRIVATE) {
                        inputSize.add(maxPrivateInputSize);
                        format = ImageFormat.PRIVATE;
                    } else {
                        inputSize.add(maxYUVInputSize);
                        format = ImageFormat.YUV_420_888;
                    }

                    streamsInfo.add(new MandatoryStreamInformation(inputSize, format,
                                /*isInput*/true));
                    streamsInfo.add(new MandatoryStreamInformation(inputSize, format));
                }

                for (StreamTemplate template : combTemplate.mStreamTemplates) {
                    List<Size> sizes = null;
                    if (template.mFormat == ImageFormat.RAW_SENSOR) {
                        sizes = availableRawSizes;
                    } else {
                        Pair<SizeThreshold, Integer> pair;
                        pair = new Pair<SizeThreshold, Integer>(template.mSizeThreshold,
                                new Integer(template.mFormat));
                        sizes = availableSizes.get(pair);
                    }

                    MandatoryStreamInformation streamInfo;
                    try {
                        streamInfo = new MandatoryStreamInformation(sizes, template.mFormat);
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "No available sizes found for format: " + template.mFormat +
                                " size threshold: " + template.mSizeThreshold + " combination: " +
                                combTemplate.mDescription);
                        return null;
                    }

                    streamsInfo.add(streamInfo);
                }

                MandatoryStreamCombination streamCombination;
                try {
                    streamCombination = new MandatoryStreamCombination(streamsInfo,
                            combTemplate.mDescription, isReprocessable);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "No stream information for mandatory combination: "
                            + combTemplate.mDescription);
                    return null;
                }

                availableStreamCombinations.add(streamCombination);
            }

            return Collections.unmodifiableList(availableStreamCombinations);
        }

        /**
         * Helper method to enumerate all available sizes according to size threshold and format.
         */
        private HashMap<Pair<SizeThreshold, Integer>, List<Size>> enumerateAvailableSizes() {
            final int[] formats = {
                ImageFormat.PRIVATE,
                ImageFormat.YUV_420_888,
                ImageFormat.JPEG
            };
            Size recordingMaxSize = new Size(0, 0);
            Size previewMaxSize = new Size(0, 0);
            Size vgaSize = new Size(640, 480);
            if (isExternalCamera()) {
                recordingMaxSize = getMaxExternalRecordingSize();
            } else {
                recordingMaxSize = getMaxRecordingSize();
            }
            if (recordingMaxSize == null) {
                Log.e(TAG, "Failed to find maximum recording size!");
                return null;
            }

            HashMap<Integer, Size[]> allSizes = new HashMap<Integer, Size[]>();
            for (int format : formats) {
                Integer intFormat = new Integer(format);
                allSizes.put(intFormat, mStreamConfigMap.getOutputSizes(format));
            }

            List<Size> previewSizes = getSizesWithinBound(
                    allSizes.get(new Integer(ImageFormat.PRIVATE)), kPreviewSizeBound);
            if ((previewSizes == null) || (previewSizes.isEmpty())) {
                Log.e(TAG, "No preview sizes within preview size bound!");
                return null;
            }
            List<Size> orderedPreviewSizes = getAscendingOrderSizes(previewSizes,
                    /*ascending*/false);
            previewMaxSize = getMaxPreviewSize(orderedPreviewSizes);

            HashMap<Pair<SizeThreshold, Integer>, List<Size>> availableSizes =
                    new HashMap<Pair<SizeThreshold, Integer>, List<Size>>();

            for (int format : formats) {
                Integer intFormat = new Integer(format);
                Size[] sizes = allSizes.get(intFormat);
                Pair<SizeThreshold, Integer> pair = new Pair<SizeThreshold, Integer>(
                        SizeThreshold.VGA, intFormat);
                availableSizes.put(pair, getSizesWithinBound(sizes, vgaSize));

                pair = new Pair<SizeThreshold, Integer>(SizeThreshold.PREVIEW, intFormat);
                availableSizes.put(pair, getSizesWithinBound(sizes, previewMaxSize));

                pair = new Pair<SizeThreshold, Integer>(SizeThreshold.RECORD, intFormat);
                availableSizes.put(pair, getSizesWithinBound(sizes, recordingMaxSize));

                pair = new Pair<SizeThreshold, Integer>(SizeThreshold.MAXIMUM, intFormat);
                availableSizes.put(pair, Arrays.asList(sizes));
            }

            return availableSizes;
        }

        /**
         * Compile a list of sizes smaller than or equal to given bound.
         * Return an empty list if there is no size smaller than or equal to the bound.
         */
        private static List<Size> getSizesWithinBound(Size[] sizes, Size bound) {
            if (sizes == null || sizes.length == 0) {
                Log.e(TAG, "Empty or invalid size array!");
                return null;
            }

            ArrayList<Size> ret = new ArrayList<Size>();
            for (Size size : sizes) {
                if (size.getWidth() <= bound.getWidth() && size.getHeight() <= bound.getHeight()) {
                    ret.add(size);
                }
            }

            return ret;
        }

        /**
         * Get the largest size by area.
         *
         * @param sizes an array of sizes, must have at least 1 element
         *
         * @return Largest Size
         *
         * @throws IllegalArgumentException if sizes was null or had 0 elements
         */
        public static Size getMaxSize(Size... sizes) {
            if (sizes == null || sizes.length == 0) {
                throw new IllegalArgumentException("sizes was empty");
            }

            Size sz = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() * size.getHeight() > sz.getWidth() * sz.getHeight()) {
                    sz = size;
                }
            }

            return sz;
        }

        /**
         * Whether or not the hardware level reported by android.info.supportedHardwareLevel is
         * at least the desired one (but could be higher)
         */
        private boolean isHardwareLevelAtLeast(int level) {
            final int[] sortedHwLevels = {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
            };
            if (level == mHwLevel) {
                return true;
            }

            for (int sortedlevel : sortedHwLevels) {
                if (sortedlevel == level) {
                    return true;
                } else if (sortedlevel == mHwLevel) {
                    return false;
                }
            }

            return false;
        }

        /**
         * Whether or not the camera is an external camera.
         *
         * @return {@code true} if the device is external, {@code false} otherwise.
         */
        private boolean isExternalCamera() {
            return mHwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL;
        }

        /**
         * Whether or not the hardware level is at least legacy.
         *
         * @return {@code true} if the device is {@code LEGACY}, {@code false} otherwise.
         */
        private boolean isHardwareLevelAtLeastLegacy() {
            return isHardwareLevelAtLeast(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
        }

        /**
         * Whether or not the hardware level is at least limited.
         *
         * @return {@code true} if the device is {@code LIMITED} or {@code FULL},
         *         {@code false} otherwise (i.e. LEGACY).
         */
        private boolean isHardwareLevelAtLeastLimited() {
            return isHardwareLevelAtLeast(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
        }

        /**
         * Whether or not the hardware level is at least full.
         *
         * @return {@code true} if the device is {@code FULL}, {@code false} otherwise.
         */
        private boolean isHardwareLevelAtLeastFull() {
            return isHardwareLevelAtLeast(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        }

        /**
         * Whether or not the hardware level is at least Level 3.
         *
         * @return {@code true} if the device is {@code LEVEL3}, {@code false} otherwise.
         */
        private boolean isHardwareLevelAtLeastLevel3() {
            return isHardwareLevelAtLeast(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3);
        }

        /**
         * Determine whether the current device supports a capability or not.
         *
         * @param capability (non-negative)
         *
         * @return {@code true} if the capability is supported, {@code false} otherwise.
         *
         */
        private boolean isCapabilitySupported(int capability) {
            return mCapabilities.contains(capability);
        }

        /**
         * Check whether the current device is backward compatible.
         */
        private boolean isColorOutputSupported() {
            return isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE);
        }

        /**
         * Check whether the current device supports private reprocessing.
         */
        private boolean isPrivateReprocessingSupported() {
            return isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING);
        }

        /**
         * Check whether the current device supports YUV reprocessing.
         */
        private boolean isYUVReprocessingSupported() {
            return isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING);
        }

        /**
         * Return the maximum supported video size using the camcorder profile information.
         *
         * @return Maximum supported video size.
         */
        private Size getMaxRecordingSize() {
            int quality =
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_2160P) ?
                        CamcorderProfile.QUALITY_2160P :
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_1080P) ?
                        CamcorderProfile.QUALITY_1080P :
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_720P) ?
                        CamcorderProfile.QUALITY_720P :
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_480P) ?
                        CamcorderProfile.QUALITY_480P :
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_QVGA) ?
                        CamcorderProfile.QUALITY_QVGA :
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_CIF) ?
                        CamcorderProfile.QUALITY_CIF :
                    CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_QCIF) ?
                        CamcorderProfile.QUALITY_QCIF :
                        -1;

            if (quality < 0) {
                return null;
            }

            CamcorderProfile maxProfile = CamcorderProfile.get(mCameraId, quality);
            return new Size(maxProfile.videoFrameWidth, maxProfile.videoFrameHeight);
        }

        /**
         * Return the maximum supported video size for external cameras using data from
         * the stream configuration map.
         *
         * @return Maximum supported video size.
         */
        private Size getMaxExternalRecordingSize() {
            final Size FULLHD = new Size(1920, 1080);

            Size[] videoSizeArr = mStreamConfigMap.getOutputSizes(
                    android.media.MediaRecorder.class);
            List<Size> sizes = new ArrayList<Size>();
            for (Size sz: videoSizeArr) {
                if (sz.getWidth() <= FULLHD.getWidth() && sz.getHeight() <= FULLHD.getHeight()) {
                    sizes.add(sz);
                }
            }
            List<Size> videoSizes = getAscendingOrderSizes(sizes, /*ascending*/false);
            for (Size sz : videoSizes) {
                long minFrameDuration = mStreamConfigMap.getOutputMinFrameDuration(
                        android.media.MediaRecorder.class, sz);
                // Give some margin for rounding error
                if (minFrameDuration > (1e9 / 30.1)) {
                    Log.i(TAG, "External camera " + mCameraId + " has max video size:" + sz);
                    return sz;
                }
            }
            Log.w(TAG, "Camera " + mCameraId + " does not support any 30fps video output");
            return FULLHD; // doesn't matter what size is returned here
        }

        private Size getMaxPreviewSize(List<Size> orderedPreviewSizes) {
            if (orderedPreviewSizes != null) {
                for (Size size : orderedPreviewSizes) {
                    if ((mDisplaySize.getWidth() >= size.getWidth()) &&
                            (mDisplaySize.getWidth() >= size.getHeight())) {
                        return size;
                    }
                }
            }

            Log.w(TAG,"Camera " + mCameraId + " maximum preview size search failed with "
                    + "display size " + mDisplaySize);
            return kPreviewSizeBound;
        }

        /**
         * Size comparison method used by size comparators.
         */
        private static int compareSizes(int widthA, int heightA, int widthB, int heightB) {
            long left = widthA * (long) heightA;
            long right = widthB * (long) heightB;
            if (left == right) {
                left = widthA;
                right = widthB;
            }
            return (left < right) ? -1 : (left > right ? 1 : 0);
        }

        /**
         * Size comparator that compares the number of pixels it covers.
         *
         * <p>If two the areas of two sizes are same, compare the widths.</p>
         */
        public static class SizeComparator implements Comparator<Size> {
            @Override
            public int compare(Size lhs, Size rhs) {
                return compareSizes(lhs.getWidth(), lhs.getHeight(), rhs.getWidth(),
                        rhs.getHeight());
            }
        }

        /**
         * Get a sorted list of sizes from a given size list.
         *
         * <p>
         * The size is compare by area it covers, if the areas are same, then
         * compare the widths.
         * </p>
         *
         * @param sizeList The input size list to be sorted
         * @param ascending True if the order is ascending, otherwise descending order
         * @return The ordered list of sizes
         */
        private static List<Size> getAscendingOrderSizes(final List<Size> sizeList,
                boolean ascending) {
            if (sizeList == null) {
                return null;
            }

            Comparator<Size> comparator = new SizeComparator();
            List<Size> sortedSizes = new ArrayList<Size>();
            sortedSizes.addAll(sizeList);
            Collections.sort(sortedSizes, comparator);
            if (!ascending) {
                Collections.reverse(sortedSizes);
            }

            return sortedSizes;
        }
    }
}
