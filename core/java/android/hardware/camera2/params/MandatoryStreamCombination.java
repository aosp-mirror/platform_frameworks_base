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

import static android.hardware.camera2.params.StreamConfigurationMap.checkArgumentFormat;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.OutputConfiguration.StreamUseCase;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.media.CamcorderProfile;
import android.util.Log;
import android.util.Pair;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Immutable class to store the available mandatory stream combination.
 *
 * <p>A mandatory stream combination refers to a specific entry in the documented sets of
 * required stream {@link CameraDevice#createCaptureSession combinations}.
 * These combinations of streams are required to be supported by the camera device.
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
        private final boolean mIsUltraHighResolution;
        private final boolean mIsMaximumSize;
        private final boolean mIs10BitCapable;
        private final long mStreamUseCase;

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
         * @param availableSizes List of possible stream sizes.
         * @param format Image format.
         * @param isMaximumSize Whether this is a maximum size stream.
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, @Format int format,
                boolean isMaximumSize) {
            this(availableSizes, format, isMaximumSize, /*isInput*/false,
                    /*isUltraHighResolution*/false);
        }

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
         * @param availableSizes List of possible stream sizes.
         * @param format Image format.
         * @param isMaximumSize Whether this is a maximum size stream.
         * @param isInput Flag indicating whether this stream is input.
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, @Format int format,
                boolean isMaximumSize, boolean isInput) {
            this(availableSizes, format, isMaximumSize, isInput,
                    /*isUltraHighResolution*/ false);
        }

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
         * @param availableSizes List of possible stream sizes.
         * @param format Image format.
         * @param isMaximumSize Whether this is a maximum size stream.
         * @param isInput Flag indicating whether this stream is input.
         * @param isUltraHighResolution Flag indicating whether this is a ultra-high resolution
         *                              stream.
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, @Format int format,
                boolean isMaximumSize, boolean isInput, boolean isUltraHighResolution) {
            this(availableSizes, format, isMaximumSize, isInput, isUltraHighResolution,
                    /*is10bitCapable*/ false);
        }

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
         * @param availableSizes List of possible stream sizes.
         * @param format Image format.
         * @param isMaximumSize Whether this is a maximum size stream.
         * @param isInput Flag indicating whether this stream is input.
         * @param isUltraHighResolution Flag indicating whether this is a ultra-high resolution
         *                              stream.
         * @param is10BitCapable Flag indicating whether this stream is able to support 10-bit
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, @Format int format,
                boolean isMaximumSize, boolean isInput, boolean isUltraHighResolution,
                boolean is10BitCapable) {
            this(availableSizes, format, isMaximumSize, isInput, isUltraHighResolution,
                    is10BitCapable, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT);
        }

        /**
         * Create a new {@link MandatoryStreamInformation}.
         *
         * @param availableSizes List of possible stream sizes.
         * @param format Image format.
         * @param isMaximumSize Whether this is a maximum size stream.
         * @param isInput Flag indicating whether this stream is input.
         * @param isUltraHighResolution Flag indicating whether this is a ultra-high resolution
         *                              stream.
         * @param is10BitCapable Flag indicating whether this stream is able to support 10-bit
         * @param streamUseCase The stream use case.
         *
         * @throws IllegalArgumentException
         *              if sizes is empty or if the format was not user-defined in
         *              ImageFormat/PixelFormat.
         * @hide
         */
        public MandatoryStreamInformation(@NonNull List<Size> availableSizes, @Format int format,
                boolean isMaximumSize, boolean isInput, boolean isUltraHighResolution,
                boolean is10BitCapable, @StreamUseCase long streamUseCase) {
            if (availableSizes.isEmpty()) {
                throw new IllegalArgumentException("No available sizes");
            }
            mAvailableSizes.addAll(availableSizes);
            mFormat = checkArgumentFormat(format);
            mIsMaximumSize = isMaximumSize;
            mIsInput = isInput;
            mIsUltraHighResolution = isUltraHighResolution;
            mIs10BitCapable = is10BitCapable;
            mStreamUseCase = streamUseCase;
        }

        /**
         * Confirms whether or not this is an input stream.
         * @return true in case the stream is input, false otherwise.
         */
        public boolean isInput() {
            return mIsInput;
        }

        /**
         * Confirms whether or not this is an ultra high resolution stream.
         *
         * <p>An 'ultra high resolution' stream is one which has a configuration which appears in
         * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION},
         * Streams which are ultra high resolution must not be included with streams which are not
         * ultra high resolution in the same {@link android.hardware.camera2.CaptureRequest}.</p>
         *
         * @return true in case the stream is ultra high resolution, false otherwise.
        */
        public boolean isUltraHighResolution() {
            return mIsUltraHighResolution;
        }

        /**
         * Confirms whether or not this is a maximum size stream.
         *
         * <p>A stream with maximum size is one with the camera device's maximum resolution
         * for the stream's format as appears in {@link
         * android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP}. This
         * maximum size has the same meaning as the 'MAXIMUM' target size documented in the camera
         * capture session {@link CameraDevice#createCaptureSession guideline}.</p>
         *
         * <p>The application can use a
         * {@link android.hardware.camera2.MultiResolutionImageReader} for a maximum size
         * output stream if the camera device supports multi-resolution outputs for the stream's
         * format. See {@link
         * android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP}
         * for details.</p>
         *
         * <p>This is different from the ultra high resolution flag, which applies only to
         * ultra high resolution sensor camera devices and refers to a stream in
         * {@link
         * android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION}
         * instead.</p>
         *
         * @return true if the stream is a maximum size stream.
         */
        public boolean isMaximumSize() {
            return mIsMaximumSize;
        }

        /**
         * Indicates whether this stream is able to support 10-bit output.
         *
         * <p>10-bit capable streams can be configured to output 10-bit sample data via calls to
         * {@link android.hardware.camera2.params.OutputConfiguration#setDynamicRangeProfile} and
         * selecting the appropriate output Surface pixel format which can be queried via
         * {@link #get10BitFormat()} and will be either
         * {@link ImageFormat#PRIVATE} (the default for Surfaces initialized by
         * {@link android.view.SurfaceView}, {@link android.view.TextureView},
         * {@link android.media.MediaRecorder}, {@link android.media.MediaCodec} etc.) or
         * {@link ImageFormat#YCBCR_P010}.</p>
         *
         * @return true if stream is able to output 10-bit pixels
         *
         * @see android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
         * @see OutputConfiguration#setDynamicRangeProfile
         */
        public boolean is10BitCapable() {
            return mIs10BitCapable;
        }

        /**
         * Return the list of available sizes for this mandatory stream.
         *
         * <p>Per documented {@link CameraDevice#createCaptureSession guideline} the largest
         * resolution in the result will be tested and guaranteed to work. If clients want to use
         * smaller sizes, then the resulting
         * {@link android.hardware.camera2.params.SessionConfiguration session configuration} can
         * be tested either by calling {@link CameraDevice#createCaptureSession} or
         * {@link CameraDevice.CameraDeviceSetup#isSessionConfigurationSupported}.
         *
         * @return non-modifiable ascending list of available sizes.
         */
        public @NonNull List<Size> getAvailableSizes() {
            return Collections.unmodifiableList(mAvailableSizes);
        }

        /**
         * Retrieve the mandatory stream {@code format}.
         *
         * @return integer format.
         */
        public @Format int getFormat() {
            // P010 YUV streams must be supported along with SDR 8-bit YUV streams
            if ((mIs10BitCapable)  && (mFormat == ImageFormat.YCBCR_P010)) {
                return ImageFormat.YUV_420_888;
            }
            return mFormat;
        }

        /**
         * Retrieve the mandatory stream 10-bit {@code format} for 10-bit capable streams.
         *
         * <p>In case {@link #is10BitCapable()} returns {@code true}, then this method
         * will return the corresponding 10-bit output Surface pixel format. Depending on
         * the stream type it will be either {@link ImageFormat#PRIVATE} or
         * {@link ImageFormat#YCBCR_P010}.</p>
         *
         * @return integer format.
         * @throws UnsupportedOperationException in case the stream is not capable of 10-bit output
         * @see #is10BitCapable()
         */
        public @Format int get10BitFormat() {
            if (!mIs10BitCapable) {
                throw new UnsupportedOperationException("10-bit output is not supported!");
            }
            return mFormat;
        }

        /**
         * Retrieve the mandatory stream use case.
         *
         * <p>If this {@link MandatoryStreamInformation} is part of a mandatory stream
         * combination for stream use cases, the return value will be a non-DEFAULT value.
         * For {@link MandatoryStreamInformation} belonging to other mandatory stream
         * combinations, the return value will be DEFAULT. </p>
         *
         * @return the long integer stream use case.
         */
        public @StreamUseCase long getStreamUseCase() {
            return mStreamUseCase;
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
                        (mIsUltraHighResolution != other.mIsUltraHighResolution) ||
                        (mStreamUseCase != other.mStreamUseCase) ||
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
                    Boolean.hashCode(mIsUltraHighResolution), mAvailableSizes.hashCode(),
                    mStreamUseCase);
        }
    }

    private final String mDescription;
    private final boolean mIsReprocessable;
    private final ArrayList<MandatoryStreamInformation> mStreamsInformation =
            new ArrayList<MandatoryStreamInformation>();

    /**
     * Short hand for stream use cases
     */
    private static final long STREAM_USE_CASE_PREVIEW =
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW;
    private static final long STREAM_USE_CASE_STILL_CAPTURE =
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE;
    private static final long STREAM_USE_CASE_RECORD =
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD;
    private static final long STREAM_USE_CASE_PREVIEW_VIDEO_STILL =
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL;
    private static final long STREAM_USE_CASE_VIDEO_CALL =
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL;
    private static final long STREAM_USE_CASE_CROPPED_RAW =
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW;

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
            @NonNull String description, boolean isReprocessable) {
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
     * @return CharSequence with the mandatory combination description.
     */
    public @NonNull CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Indicates whether the mandatory stream combination is reprocessable. Reprocessable is defined
     * as a stream combination that contains one input stream
     * ({@link MandatoryStreamInformation#isInput} return true).
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
    public @NonNull List<MandatoryStreamInformation> getStreamsInformation() {
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

    private static enum SizeThreshold { VGA, PREVIEW, RECORD, MAXIMUM, s720p, s1440p, FULL_RES }
    private static enum ReprocessType { NONE, PRIVATE, YUV, REMOSAIC }
    private static final class StreamTemplate {
        public int mFormat;
        public SizeThreshold mSizeThreshold;
        public long mStreamUseCase;
        public StreamTemplate(int format, SizeThreshold sizeThreshold) {
            this(format, sizeThreshold, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT);
        }
        public StreamTemplate(@Format int format, @NonNull SizeThreshold sizeThreshold,
                @StreamUseCase long streamUseCase) {
            mFormat = format;
            mSizeThreshold = sizeThreshold;
            mStreamUseCase = streamUseCase;
        }
    }

    private static final class StreamCombinationTemplate {
        public StreamTemplate[] mStreamTemplates;
        public String mDescription;
        public ReprocessType mReprocessType;
        // Substitute MAXIMUM size YUV output stream with JPEG / RAW_SENSOR.
        public boolean mSubstituteYUV = false;

        public StreamCombinationTemplate(@NonNull StreamTemplate[] streamTemplates,
                @NonNull String description) {
            this(streamTemplates, description, /*reprocessType*/ReprocessType.NONE);
        }

        public StreamCombinationTemplate(@NonNull StreamTemplate[] streamTemplates,
                @NonNull String description, ReprocessType reprocessType) {
            this(streamTemplates, description, reprocessType, /*substituteYUV*/ false);
        }

        public StreamCombinationTemplate(@NonNull StreamTemplate[] streamTemplates,
                @NonNull String description, boolean substituteYUV) {
            this(streamTemplates, description, /*reprocessType*/ ReprocessType.NONE,
                    substituteYUV);
        }

        public StreamCombinationTemplate(@NonNull StreamTemplate[] streamTemplates,
                @NonNull String description, ReprocessType reprocessType, boolean substituteYUV) {
            mStreamTemplates = streamTemplates;
            mReprocessType = reprocessType;
            mDescription = description;
            mSubstituteYUV = substituteYUV;
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
                "Maximum-resolution two-input in-app processing"),
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

    private static StreamCombinationTemplate sConcurrentStreamCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p) },
                "In-app video / image processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p) },
                "preview / preview to GPU"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.s1440p) },
                "No view-finder still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s720p),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p)},
                "Two-input in app video / image processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s720p),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p)},
                "High resolution video recording with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s720p),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p)},
                "In-app video / image processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s720p),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p)},
                "In-app video / image processing with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s720p),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.s1440p)},
                "Standard still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s720p),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.s1440p)},
                "Standard still image capture"),
    };

    private static StreamCombinationTemplate sConcurrentDepthOnlyStreamCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.DEPTH16, SizeThreshold.VGA) },
                "Depth capture for mesh based object rendering"),
    };

    private static StreamCombinationTemplate sUltraHighResolutionStreamCombinations[] = {
        // UH res YUV / RAW / JPEG + PRIV preview size stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                 new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "Ultra high resolution YUV image capture with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "Ultra high resolution RAW_SENSOR image capture with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "Ultra high resolution JPEG image capture with preview"),

        // UH res YUV / RAW / JPEG + YUV preview size stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "No-viewfinder Ultra high resolution YUV image capture with image analysis"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "No-viewfinder Ultra high resolution RAW_SENSOR image capture with image analysis"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "No-viewfinder Ultra high resolution JPEG image capture with image analysis"),

        // UH res YUV / RAW / JPEG + PRIV preview + PRIV RECORD stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD)},
                "Ultra high resolution YUV image capture with preview + app-based image analysis"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD)},
                "Ultra high resolution RAW image capture with preview + app-based image analysis"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD)},
                "Ultra high resolution JPEG image capture with preview + app-based image analysis"),

        // UH res YUV / RAW / JPEG + PRIV preview + YUV RECORD stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD)},
                "Ultra high resolution YUV image capture with preview + app-based image analysis"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD)},
                "Ultra high resolution RAW image capture with preview + app-based image analysis"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD)},
                "Ultra high resolution JPEG image capture with preview + app-based image analysis"),

        // UH RES YUV / RAW / JPEG + PRIV preview + YUV / RAW / JPEG Maximum stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM)},
                "Ultra high resolution YUV image capture with preview + default",
                /*substituteYUV*/ true),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM)},
                "Ultra high resolution RAW image capture with preview + default",
                /*substituteYUV*/ true),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM)},
                "Ultra high resolution JPEG capture with preview + default",
                /*substituteYUV*/ true),
    };

    private static StreamCombinationTemplate sUltraHighResolutionReprocStreamCombinations[] = {
        // RAW_SENSOR -> RAW_SENSOR + preview size PRIV / YUV
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "In-app RAW remosaic reprocessing with separate preview",
                /*reprocessType*/ ReprocessType.REMOSAIC),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "In-app RAW remosaic reprocessing with in-app image analysis",
                /*reprocessType*/ ReprocessType.REMOSAIC),

        // RAW -> JPEG / YUV reprocessing + YUV / PRIV preview size stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "In-app RAW -> JPEG reprocessing with separate preview",
                /*reprocessType*/ ReprocessType.REMOSAIC),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "In-app RAW -> YUV reprocessing with separate preview",
                /*reprocessType*/ ReprocessType.REMOSAIC),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "In-app RAW -> JPEG reprocessing with in-app image analysis",
                /*reprocessType*/ ReprocessType.REMOSAIC),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "In-app RAW -> YUV reprocessing with in-app image analysis",
                /*reprocessType*/ ReprocessType.REMOSAIC),
    };

    private static StreamCombinationTemplate sUltraHighResolutionYUVReprocStreamCombinations[] = {
        // YUV -> JPEG reprocess + PRIV / YUV preview size stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "Ultra high resolution YUV -> JPEG reprocessing with separate preview",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "Ultra high resolution YUV -> JPEG reprocessing with in-app image analysis",
                /*reprocessType*/ ReprocessType.YUV),

        // YUV -> YUV reprocess + PRIV / YUV preview size stream
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "Ultra high resolution YUV -> YUV reprocessing with separate preview",
                /*reprocessType*/ ReprocessType.YUV),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "Ultra high resolution YUV -> YUV reprocessing with in-app image analysis",
                /*reprocessType*/ ReprocessType.YUV),
    };

    private static StreamCombinationTemplate sUltraHighResolutionPRIVReprocStreamCombinations[] = {
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "Ultra high resolution PRIVATE -> JPEG reprocessing with separate preview",
                /*reprocessType*/ ReprocessType.PRIVATE),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.FULL_RES),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "Ultra high resolution PRIVATE -> JPEG reprocessing with in-app image analysis",
                /*reprocessType*/ ReprocessType.PRIVATE),
    };

    private static StreamCombinationTemplate s10BitOutputStreamCombinations[] = {
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.MAXIMUM)},
                    "Simple preview, GPU video processing, or no-preview video recording"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.YCBCR_P010, SizeThreshold.MAXIMUM)},
                    "In-application video/image processing"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                    "Standard still imaging"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.YCBCR_P010, SizeThreshold.MAXIMUM),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                    "Maximum-resolution in-app processing with preview"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.YCBCR_P010, SizeThreshold.MAXIMUM),
                    new StreamTemplate(ImageFormat.YCBCR_P010, SizeThreshold.PREVIEW)},
                    "Maximum-resolution two-input in-app processing"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                    "High-resolution video recording with preview"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.YCBCR_P010, SizeThreshold.RECORD),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                    "High-resolution recording with in-app snapshot"),
            new StreamCombinationTemplate(new StreamTemplate [] {
                    new StreamTemplate(ImageFormat.JPEG, SizeThreshold.RECORD),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD),
                    new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                    "High-resolution recording with video snapshot"),
    };

    private static StreamCombinationTemplate sStreamUseCaseCombinations[] = {
        // Single stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW) },
                "Simple preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW) },
                "Simple in-application image processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD,
                        STREAM_USE_CASE_RECORD) },
                "Simple video recording"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD,
                        STREAM_USE_CASE_RECORD) },
                "Simple in-application video processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE) },
                "Simple JPEG still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE) },
                "Simple YUV still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p,
                        STREAM_USE_CASE_PREVIEW_VIDEO_STILL) },
                "Multi-purpose stream for preview, video and still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p,
                        STREAM_USE_CASE_PREVIEW_VIDEO_STILL) },
                "Multi-purpose YUV stream for preview, video and still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p,
                        STREAM_USE_CASE_VIDEO_CALL) },
                "Simple video call"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p,
                        STREAM_USE_CASE_VIDEO_CALL) },
                "Simple YUV video call"),

        // 2-stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Preview with JPEG still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Preview with YUV still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD,
                        STREAM_USE_CASE_RECORD)},
                "Preview with video recording"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD,
                        STREAM_USE_CASE_RECORD)},
                "Preview with in-application video processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW)},
                "Preview with in-application image processing"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p,
                        STREAM_USE_CASE_VIDEO_CALL)},
                "Preview with video call"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p,
                        STREAM_USE_CASE_VIDEO_CALL)},
                "Preview with YUV video call"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p,
                        STREAM_USE_CASE_PREVIEW_VIDEO_STILL),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Multi-purpose stream with JPEG still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p,
                        STREAM_USE_CASE_PREVIEW_VIDEO_STILL),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Multi-purpose stream with YUV still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p,
                        STREAM_USE_CASE_PREVIEW_VIDEO_STILL),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Multi-purpose YUV stream with JPEG still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p,
                        STREAM_USE_CASE_PREVIEW_VIDEO_STILL),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Multi-purpose YUV stream with YUV still capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_STILL_CAPTURE),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "YUV and JPEG concurrent still image capture (for testing)"),

        // 3-stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.RECORD,
                        STREAM_USE_CASE_RECORD),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.RECORD,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Preview, video record and JPEG video snapshot"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.RECORD,
                        STREAM_USE_CASE_RECORD),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.RECORD,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Preview, in-application video processing and JPEG video snapshot"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE)},
                "Preview, in-application image processing, and JPEG still image capture"),
    };

    private static StreamCombinationTemplate sCroppedRawStreamUseCaseCombinations[] = {
        // Single stream combination
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Cropped RAW still image capture without preview"),

        // 2 Stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Cropped RAW still image capture with preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "In-app image processing with cropped RAW still image capture"),

        // 3 stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Preview with YUV and RAW still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "In-app image processing with YUV and RAW still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Preview with JPEG and RAW still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_STILL_CAPTURE),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "In-app image processing with JPEG and RAW still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_RECORD),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Preview with video recording and RAW snapshot"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Preview with in-app image processing and RAW still image capture"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW,
                        STREAM_USE_CASE_PREVIEW),
                new StreamTemplate(ImageFormat.RAW_SENSOR, SizeThreshold.MAXIMUM,
                        STREAM_USE_CASE_CROPPED_RAW)},
                "Two input in-app processing and RAW still image capture"),
    };

    private static StreamCombinationTemplate sPreviewStabilizedStreamCombinations[] = {
        // 1 stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p)},
                "Stabilized preview, GPU video processing, or no-preview stabilized recording"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p)},
                "Stabilized preview, GPU video processing, or no-preview stabilized recording"),
        //2 stream combinations
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p)},
                "Standard JPEG still imaging with stabilized preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p)},
                "Standard YUV still imaging with stabilized preview"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p)},
                "Standard YUV still imaging with stabilized in-app image processing stream"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.JPEG, SizeThreshold.MAXIMUM),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p)},
                "Standard JPEG still imaging with stabilized in-app image processing stream"),

        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "High-resolution video recording with preview both streams stabilized"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.s1440p),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "High-resolution video recording with preview both streams stabilized"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p),
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.PREVIEW)},
                "High-resolution video recording with preview both streams stabilized"),
        new StreamCombinationTemplate(new StreamTemplate [] {
                new StreamTemplate(ImageFormat.YUV_420_888, SizeThreshold.s1440p),
                new StreamTemplate(ImageFormat.PRIVATE, SizeThreshold.PREVIEW)},
                "High-resolution video recording with preview both streams stabilized"),
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
        private StreamConfigurationMap mStreamConfigMapMaximumResolution;
        private boolean mIsHiddenPhysicalCamera;
        private boolean mIsPreviewStabilizationSupported = false;
        private boolean mIsCroppedRawSupported = false;

        private final Size kPreviewSizeBound = new Size(1920, 1088);

        /**
         * Helper class to be used to generate the available mandatory stream combinations.
         *
         * @param cameraId Current camera id.
         * @param hwLevel The camera HW level as reported by android.info.supportedHardwareLevel.
         * @param displaySize The device display size.
         * @param capabilities The camera device capabilities.
         * @param sm The camera device stream configuration map.
         * @param smMaxResolution The camera device stream configuration map when it runs in max
         *                        resolution mode.
         * @param previewStabilization The camera device supports preview stabilization.
         * @param croppedRaw The camera device supports the cropped raw stream use case.
         */
        public Builder(int cameraId, int hwLevel, @NonNull Size displaySize,
                @NonNull List<Integer> capabilities, @NonNull StreamConfigurationMap sm,
                StreamConfigurationMap smMaxResolution, boolean previewStabilization,
                boolean isCroppedRawSupported) {
            mCameraId = cameraId;
            mDisplaySize = displaySize;
            mCapabilities = capabilities;
            mStreamConfigMap = sm;
            mStreamConfigMapMaximumResolution = smMaxResolution;
            mHwLevel = hwLevel;
            mIsHiddenPhysicalCamera =
                    CameraManager.isHiddenPhysicalCamera(Integer.toString(mCameraId));
            mIsPreviewStabilizationSupported = previewStabilization;
            mIsCroppedRawSupported = isCroppedRawSupported;
        }

        private @Nullable List<MandatoryStreamCombination>
        getAvailableMandatoryStreamCombinationsInternal(
                StreamCombinationTemplate []chosenStreamCombinations, boolean s10Bit) {

            HashMap<Pair<SizeThreshold, Integer>, List<Size>> availableSizes =
                    enumerateAvailableSizes();
            if (availableSizes == null) {
                Log.e(TAG, "Available size enumeration failed!");
                return null;
            }

            ArrayList<MandatoryStreamCombination> availableStreamCombinations = new ArrayList<>();
            availableStreamCombinations.ensureCapacity(chosenStreamCombinations.length);
            for (StreamCombinationTemplate combTemplate : chosenStreamCombinations) {
                ArrayList<MandatoryStreamInformation> streamsInfo = new ArrayList<>();
                streamsInfo.ensureCapacity(combTemplate.mStreamTemplates.length);
                for (StreamTemplate template : combTemplate.mStreamTemplates) {
                    List<Size> sizes = null;
                    Pair<SizeThreshold, Integer> pair;
                    pair = new Pair<>(template.mSizeThreshold, new Integer(template.mFormat));
                    sizes = availableSizes.get(pair);
                    if (s10Bit && template.mFormat == ImageFormat.YCBCR_P010) {
                        // Make sure that exactly the same 10 and 8-bit YUV streams sizes are
                        // supported
                        pair = new Pair<>(template.mSizeThreshold,
                                new Integer(ImageFormat.YUV_420_888));
                        HashSet<Size> sdrYuvSizes = new HashSet<>(availableSizes.get(pair));
                        if (!sdrYuvSizes.equals(new HashSet<>(sizes))) {
                            Log.e(TAG, "The supported 10-bit YUV sizes are different from the"
                                    + " supported 8-bit YUV sizes!");
                            return null;
                        }
                    }

                    MandatoryStreamInformation streamInfo;
                    boolean isMaximumSize =
                            (template.mSizeThreshold == SizeThreshold.MAXIMUM);
                    try {
                        streamInfo = new MandatoryStreamInformation(sizes, template.mFormat,
                                isMaximumSize, /*isInput*/ false,
                                /*isUltraHighResolution*/ false,
                                /*is10BitCapable*/ s10Bit ? template.mFormat != ImageFormat.JPEG :
                                        false);
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
                            combTemplate.mDescription, /*isReprocessable*/ false);
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
         * Retrieve a list of all available mandatory stream combinations for devices supporting
         * preview stabilization.
         *
         * @return a non-modifiable list of supported mandatory stream combinations on which
         *         preview stabilization is supported.,
         *         null in case device is not 10-bit output capable.
         */
        public @Nullable List<MandatoryStreamCombination>
        getAvailableMandatoryPreviewStabilizedStreamCombinations() {
            // Since preview stabilization support is optional, we mandate these stream
            // combinations regardless of camera device capabilities.

            StreamCombinationTemplate []chosenStreamCombinations =
                    sPreviewStabilizedStreamCombinations;

            if (!mIsPreviewStabilizationSupported) {
                Log.v(TAG, "Device does not support preview stabilization");
                 return null;
             }

            return getAvailableMandatoryStreamCombinationsInternal(chosenStreamCombinations,
                    /*10bit*/false);
        }


        /**
         * Retrieve a list of all available mandatory 10-bit output capable stream combinations.
         *
         * @return a non-modifiable list of supported mandatory 10-bit capable stream combinations,
         *         null in case device is not 10-bit output capable.
         */
        public @Nullable List<MandatoryStreamCombination>
        getAvailableMandatory10BitStreamCombinations() {
            // Since 10-bit streaming support is optional, we mandate these stream
            // combinations regardless of camera device capabilities.

            StreamCombinationTemplate []chosenStreamCombinations = s10BitOutputStreamCombinations;
            if (!is10BitOutputSupported()) {
                Log.v(TAG, "Device is not able to output 10-bit!");
                return null;
            }
            return getAvailableMandatoryStreamCombinationsInternal(chosenStreamCombinations,
                    /*10bit*/true);
        }

        /**
          * Retrieve a list of all available mandatory stream combinations with stream use cases.
          * when the camera device has {@link
          * CameraMetdata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE} capability.
          *
          * @return a non-modifiable list of supported mandatory stream combinations with stream
          *         use cases. Null in case the device doesn't have {@link
          *         CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE}
          *         capability.
          */
        public @NonNull List<MandatoryStreamCombination>
                getAvailableMandatoryStreamUseCaseCombinations() {
            if (!isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE)) {
                return null;
            }

            HashMap<Pair<SizeThreshold, Integer>, List<Size>> availableSizes =
                    enumerateAvailableSizes();
            if (availableSizes == null) {
                Log.e(TAG, "Available size enumeration failed!");
                return null;
            }
            ArrayList<StreamCombinationTemplate> availableTemplates =
                    new ArrayList<StreamCombinationTemplate> ();
            availableTemplates.addAll(Arrays.asList(sStreamUseCaseCombinations));

            ArrayList<MandatoryStreamCombination> availableStreamCombinations = new ArrayList<>();
            int capacity = sStreamUseCaseCombinations.length;
            if (mIsCroppedRawSupported) {
                capacity += sCroppedRawStreamUseCaseCombinations.length;
                availableStreamCombinations.ensureCapacity(capacity);
                availableTemplates.addAll(Arrays.asList(sCroppedRawStreamUseCaseCombinations));
            }
             else {
                availableStreamCombinations.ensureCapacity(capacity);
             }


            for (StreamCombinationTemplate combTemplate : availableTemplates) {
                ArrayList<MandatoryStreamInformation> streamsInfo =
                        new ArrayList<MandatoryStreamInformation>();
                streamsInfo.ensureCapacity(combTemplate.mStreamTemplates.length);

                for (StreamTemplate template : combTemplate.mStreamTemplates) {
                    List<Size> sizes = null;
                    Pair<SizeThreshold, Integer> pair;
                    pair = new Pair<SizeThreshold, Integer>(template.mSizeThreshold,
                            new Integer(template.mFormat));
                    sizes = availableSizes.get(pair);

                    MandatoryStreamInformation streamInfo;
                    boolean isMaximumSize =
                            (template.mSizeThreshold == SizeThreshold.MAXIMUM);
                    try {
                        streamInfo = new MandatoryStreamInformation(sizes, template.mFormat,
                                isMaximumSize, /*isInput*/false, /*isUltraHighResolution*/false,
                                /*is10BitCapable*/ false, template.mStreamUseCase);
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
                            combTemplate.mDescription, /*isReprocessable*/ false);
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
          * Retrieve a list of all available mandatory concurrent stream combinations.
          * This method should only be called for devices which are listed in combinations returned
          * by CameraManager.getConcurrentCameraIds.
          *
          * @return a non-modifiable list of supported mandatory concurrent stream combinations.
          */
        public @NonNull List<MandatoryStreamCombination>
                getAvailableMandatoryConcurrentStreamCombinations() {
            // Since concurrent streaming support is optional, we mandate these stream
            // combinations regardless of camera device capabilities.

            StreamCombinationTemplate []chosenStreamCombinations = sConcurrentStreamCombinations;
            if (!isColorOutputSupported()) {
                Log.v(TAG, "Device is not backward compatible, depth streams are mandatory!");
                chosenStreamCombinations = sConcurrentDepthOnlyStreamCombinations;
            }
            Size sizeVGAp = new Size(640, 480);
            Size size720p = new Size(1280, 720);
            Size size1440p = new Size(1920, 1440);

            ArrayList<MandatoryStreamCombination> availableConcurrentStreamCombinations =
                    new ArrayList<MandatoryStreamCombination>();
            availableConcurrentStreamCombinations.ensureCapacity(
                    chosenStreamCombinations.length);
            for (StreamCombinationTemplate combTemplate : chosenStreamCombinations) {
                ArrayList<MandatoryStreamInformation> streamsInfo =
                        new ArrayList<MandatoryStreamInformation>();
                streamsInfo.ensureCapacity(combTemplate.mStreamTemplates.length);
                for (StreamTemplate template : combTemplate.mStreamTemplates) {
                    MandatoryStreamInformation streamInfo;
                    List<Size> sizes = new ArrayList<Size>();
                    Size formatSize = null;
                    switch (template.mSizeThreshold) {
                        case s1440p:
                            formatSize = size1440p;
                            break;
                        case VGA:
                            formatSize = sizeVGAp;
                            break;
                        default:
                            formatSize = size720p;
                    }
                    Size sizeChosen =
                            getMinSize(formatSize,
                                    getMaxSize(mStreamConfigMap.getOutputSizes(template.mFormat)));
                    sizes.add(sizeChosen);
                    try {
                        streamInfo = new MandatoryStreamInformation(sizes, template.mFormat,
                            /*isMaximumSize*/false);
                    } catch (IllegalArgumentException e) {
                        String cause = "No available sizes found for format: " + template.mFormat
                                + " size threshold: " + template.mSizeThreshold + " combination: "
                                + combTemplate.mDescription;
                        throw new RuntimeException(cause, e);
                    }
                    streamsInfo.add(streamInfo);
                }

                MandatoryStreamCombination streamCombination;
                try {
                    streamCombination = new MandatoryStreamCombination(streamsInfo,
                            combTemplate.mDescription, /*isReprocess*/false);
                } catch (IllegalArgumentException e) {
                    String cause =  "No stream information for mandatory combination: "
                            + combTemplate.mDescription;
                    throw new RuntimeException(cause, e);
                }
                availableConcurrentStreamCombinations.add(streamCombination);
            }
            return Collections.unmodifiableList(availableConcurrentStreamCombinations);
        }

        /**
         * Retrieve a list of all available mandatory stream combinations supported when
         * {@link CaptureRequest#ANDROID_SENSOR_PIXEL_MODE} is set to
         * {@link CameraMetadata#ANDROID_SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION}.
         *
         * @return a non-modifiable list of supported mandatory stream combinations or
         *         null in case device is not backward compatible or the method encounters
         *         an error.
         */
        public @NonNull List<MandatoryStreamCombination>
                getAvailableMandatoryMaximumResolutionStreamCombinations() {

            if (!isColorOutputSupported()) {
                Log.v(TAG, "Device is not backward compatible!, no mandatory maximum res streams");
                return null;
            }

            ArrayList<StreamCombinationTemplate> chosenStreamCombinationTemplates =
                    new ArrayList<StreamCombinationTemplate>();

            chosenStreamCombinationTemplates.addAll(
                    Arrays.asList(sUltraHighResolutionStreamCombinations));

            ArrayList<MandatoryStreamCombination> availableStreamCombinations =
                    new ArrayList<MandatoryStreamCombination>();
            boolean addRemosaicReprocessing = isRemosaicReprocessingSupported();

            int remosaicSize = 0;
            Size [] maxResYUVInputSizes =
                    mStreamConfigMapMaximumResolution.getInputSizes(ImageFormat.YUV_420_888);
            Size [] maxResPRIVInputSizes =
                    mStreamConfigMapMaximumResolution.getInputSizes(ImageFormat.PRIVATE);

            if (addRemosaicReprocessing) {
                remosaicSize += sUltraHighResolutionReprocStreamCombinations.length;
                chosenStreamCombinationTemplates.addAll(
                        Arrays.asList(sUltraHighResolutionReprocStreamCombinations));
            }

            if (maxResYUVInputSizes != null && maxResYUVInputSizes.length != 0) {
                remosaicSize += sUltraHighResolutionYUVReprocStreamCombinations.length;
                chosenStreamCombinationTemplates.addAll(
                        Arrays.asList(sUltraHighResolutionYUVReprocStreamCombinations));
            }

            if (maxResPRIVInputSizes != null && maxResPRIVInputSizes.length != 0) {
                remosaicSize += sUltraHighResolutionPRIVReprocStreamCombinations.length;
                chosenStreamCombinationTemplates.addAll(
                        Arrays.asList(sUltraHighResolutionPRIVReprocStreamCombinations));

            }
            availableStreamCombinations.ensureCapacity(
                    chosenStreamCombinationTemplates.size() + remosaicSize);
            fillUHMandatoryStreamCombinations(availableStreamCombinations,
                    chosenStreamCombinationTemplates);

            return Collections.unmodifiableList(availableStreamCombinations);
        }

        private MandatoryStreamCombination createUHSensorMandatoryStreamCombination(
                StreamCombinationTemplate combTemplate, int substitutedFormat) {
            ArrayList<MandatoryStreamInformation> streamsInfo =
                    new ArrayList<MandatoryStreamInformation>();
            streamsInfo.ensureCapacity(combTemplate.mStreamTemplates.length);
            boolean isReprocess = combTemplate.mReprocessType != ReprocessType.NONE;
            if (isReprocess) {
                int format = -1;
                ArrayList<Size> inputSize = new ArrayList<Size>();
                if (combTemplate.mReprocessType == ReprocessType.PRIVATE) {
                    inputSize.add(
                            getMaxSize(mStreamConfigMapMaximumResolution.getInputSizes(
                                    ImageFormat.PRIVATE)));
                    format = ImageFormat.PRIVATE;
                } else if (combTemplate.mReprocessType == ReprocessType.REMOSAIC) {
                    inputSize.add(
                            getMaxSize(mStreamConfigMapMaximumResolution.getInputSizes(
                                    ImageFormat.RAW_SENSOR)));
                    format = ImageFormat.RAW_SENSOR;
                } else {
                    inputSize.add(
                            getMaxSize(mStreamConfigMapMaximumResolution.getInputSizes(
                                    ImageFormat.YUV_420_888)));
                    format = ImageFormat.YUV_420_888;
                }
                streamsInfo.add(new MandatoryStreamInformation(inputSize, format,
                        /*isMaximumSize*/false, /*isInput*/true,
                        /*isUltraHighResolution*/ true));
                streamsInfo.add(new MandatoryStreamInformation(inputSize, format,
                        /*isMaximumSize*/false, /*isInput*/ false,
                        /*isUltraHighResolution*/true));
            }
            HashMap<Pair<SizeThreshold, Integer>, List<Size>> availableDefaultNonRawSizes =
                    enumerateAvailableSizes();
            if (availableDefaultNonRawSizes == null) {
                Log.e(TAG, "Available size enumeration failed");
                return null;
            }
            Size[] defaultRawSizes =
                    mStreamConfigMap.getOutputSizes(ImageFormat.RAW_SENSOR);
            ArrayList<Size> availableDefaultRawSizes = new ArrayList<>();
            if (defaultRawSizes != null) {
                availableDefaultRawSizes.ensureCapacity(defaultRawSizes.length);
                availableDefaultRawSizes.addAll(Arrays.asList(defaultRawSizes));
            }
            for (StreamTemplate template : combTemplate.mStreamTemplates) {
                MandatoryStreamInformation streamInfo;
                List<Size> sizes = new ArrayList<Size>();
                int formatChosen = template.mFormat;
                boolean isUltraHighResolution =
                        (template.mSizeThreshold == SizeThreshold.FULL_RES);
                StreamConfigurationMap sm =
                        isUltraHighResolution ?
                                mStreamConfigMapMaximumResolution : mStreamConfigMap;
                boolean isMaximumSize = (template.mSizeThreshold == SizeThreshold.MAXIMUM);

                if (substitutedFormat != ImageFormat.UNKNOWN && isMaximumSize) {
                    formatChosen = substitutedFormat;
                }

                if (isUltraHighResolution) {
                    Size [] outputSizes = sm.getOutputSizes(formatChosen);
                    Size [] highResolutionOutputSizes =
                            sm.getHighResolutionOutputSizes(formatChosen);
                    Size maxBurstSize = getMaxSizeOrNull(outputSizes);
                    Size maxHighResolutionSize = getMaxSizeOrNull(highResolutionOutputSizes);
                    Size chosenMaxSize =
                            maxBurstSize != null ? maxBurstSize : maxHighResolutionSize;
                    if (maxBurstSize != null && maxHighResolutionSize != null) {
                        chosenMaxSize = getMaxSize(maxBurstSize, maxHighResolutionSize);
                    }
                    sizes.add(chosenMaxSize);
                } else {
                    if (formatChosen == ImageFormat.RAW_SENSOR) {
                        // RAW_SENSOR always has MAXIMUM threshold.
                        sizes = availableDefaultRawSizes;
                    } else {
                        Pair<SizeThreshold, Integer> pair =
                            new Pair<SizeThreshold, Integer>(template.mSizeThreshold,
                                    new Integer(formatChosen));
                        sizes = availableDefaultNonRawSizes.get(pair);
                    }
                }

                try {
                    streamInfo = new MandatoryStreamInformation(sizes, formatChosen,
                            isMaximumSize, /*isInput*/ false, isUltraHighResolution);
                } catch (IllegalArgumentException e) {
                    String cause = "No available sizes found for format: " + template.mFormat
                            + " size threshold: " + template.mSizeThreshold + " combination: "
                            + combTemplate.mDescription;
                    throw new RuntimeException(cause, e);
                }
                streamsInfo.add(streamInfo);
            }

            String formatString = null;
            switch (substitutedFormat) {
                case ImageFormat.RAW_SENSOR :
                    formatString = "RAW_SENSOR";
                    break;
                case ImageFormat.JPEG :
                    formatString = "JPEG";
                    break;
                default:
                    formatString = "YUV";
            }

            MandatoryStreamCombination streamCombination;
            try {
                streamCombination = new MandatoryStreamCombination(streamsInfo,
                        combTemplate.mDescription + " " + formatString + " still-capture",
                        isReprocess);
            } catch (IllegalArgumentException e) {
                String cause =  "No stream information for mandatory combination: "
                        + combTemplate.mDescription;
                throw new RuntimeException(cause, e);
            }
            return streamCombination;
        }

        private void fillUHMandatoryStreamCombinations(
                ArrayList<MandatoryStreamCombination> availableStreamCombinations,
                ArrayList<StreamCombinationTemplate> chosenTemplates) {

            for (StreamCombinationTemplate combTemplate : chosenTemplates) {
                MandatoryStreamCombination streamCombination =
                        createUHSensorMandatoryStreamCombination(combTemplate,
                                  ImageFormat.UNKNOWN);
                availableStreamCombinations.add(streamCombination);
                if (combTemplate.mSubstituteYUV) {
                     streamCombination =
                            createUHSensorMandatoryStreamCombination(combTemplate,
                                    ImageFormat.RAW_SENSOR);
                    availableStreamCombinations.add(streamCombination);
                    streamCombination =
                            createUHSensorMandatoryStreamCombination(combTemplate,
                                    ImageFormat.JPEG);
                    availableStreamCombinations.add(streamCombination);
                }
            }
        }

        /**
         * Retrieve a list of all available mandatory stream combinations.
         *
         * @return a non-modifiable list of supported mandatory stream combinations or
         *         null in case device is not backward compatible or the method encounters
         *         an error.
         */
        public @Nullable List<MandatoryStreamCombination>
            getAvailableMandatoryStreamCombinations() {
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
        private @Nullable List<MandatoryStreamCombination> generateAvailableCombinations(
                @NonNull ArrayList<StreamCombinationTemplate> availableTemplates) {
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
                        // Default mandatory streams only have PRIVATE / YUV reprocessing.
                        inputSize.add(maxYUVInputSize);
                        format = ImageFormat.YUV_420_888;
                    }
                    streamsInfo.add(new MandatoryStreamInformation(inputSize, format,
                                /*isMaximumSize*/true, /*isInput*/true));
                    streamsInfo.add(new MandatoryStreamInformation(inputSize, format,
                            /*isMaximumSize*/true));
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
                    boolean isMaximumSize =
                            (template.mSizeThreshold == SizeThreshold.MAXIMUM);
                    try {
                        streamInfo = new MandatoryStreamInformation(sizes, template.mFormat,
                                isMaximumSize);
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
        private @Nullable HashMap<Pair<SizeThreshold, Integer>, List<Size>>
            enumerateAvailableSizes() {
            final int[] formats = {
                ImageFormat.RAW_SENSOR,
                ImageFormat.PRIVATE,
                ImageFormat.YUV_420_888,
                ImageFormat.JPEG,
                ImageFormat.YCBCR_P010
            };
            Size recordingMaxSize = new Size(0, 0);
            Size previewMaxSize = new Size(0, 0);
            Size vgaSize = new Size(640, 480);
            Size s720pSize = new Size(1280, 720);
            Size s1440pSize = new Size(1920, 1440);
            // For external camera, or hidden physical camera, CamcorderProfile may not be
            // available, so get maximum recording size using stream configuration map.
            if (isExternalCamera() || mIsHiddenPhysicalCamera) {
                recordingMaxSize = getMaxCameraRecordingSize();
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
                Size[] sizes = mStreamConfigMap.getOutputSizes(format);
                if (sizes == null) {
                    sizes = new Size[0];
                }
                allSizes.put(intFormat, sizes);
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

                pair = new Pair<SizeThreshold, Integer>(SizeThreshold.s720p, intFormat);
                availableSizes.put(pair, getSizesWithinBound(sizes, s720pSize));

                pair = new Pair<SizeThreshold, Integer>(SizeThreshold.s1440p, intFormat);
                availableSizes.put(pair, getSizesWithinBound(sizes, s1440pSize));
            }

            return availableSizes;
        }

        /**
         * Compile a list of sizes smaller than or equal to given bound.
         * Return an empty list if there is no size smaller than or equal to the bound.
         */
        private static @Nullable List<Size> getSizesWithinBound(@NonNull Size[] sizes,
                @NonNull Size bound) {
            ArrayList<Size> ret = new ArrayList<Size>();
            for (Size size : sizes) {
                if (size.getWidth() <= bound.getWidth() && size.getHeight() <= bound.getHeight()) {
                    ret.add(size);
                }
            }

            return ret;
        }

        /**
         * Return the lower size
         */
        public static @Nullable Size getMinSize(Size a, Size b) {
            if (a == null || b == null) {
                throw new IllegalArgumentException("sizes was empty");
            }
            if (a.getWidth() * a.getHeight() < b.getHeight() * b.getWidth()) {
                return a;
            }
            return b;
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
        public static @Nullable Size getMaxSize(@NonNull Size... sizes) {
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
         * Get the largest size by area.
         *
         * @param sizes an array of sizes
         *
         * @return Largest Size or null if sizes was null or had 0 elements
         */
        public static @Nullable Size getMaxSizeOrNull(Size... sizes) {
            if (sizes == null || sizes.length == 0) {
                return null;
            }

            return getMaxSize(sizes);
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
         * Check whether the current device supports 10-bit output.
         */
        private boolean is10BitOutputSupported() {
            return isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT);
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
         * Check whether the current device supports YUV reprocessing.
         */
        private boolean isRemosaicReprocessingSupported() {
            return isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING);
        }

        /**
         * Return the maximum supported video size using the camcorder profile information.
         *
         * @return Maximum supported video size.
         */
        private @Nullable Size getMaxRecordingSize() {
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
         * Return the maximum supported video size for cameras using data from
         * the stream configuration map.
         *
         * @return Maximum supported video size.
         */
        private @NonNull Size getMaxCameraRecordingSize() {
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
                if (minFrameDuration < (1e9 / 29.9)) {
                    Log.i(TAG, "External camera " + mCameraId + " has max video size:" + sz);
                    return sz;
                }
            }
            Log.w(TAG, "Camera " + mCameraId + " does not support any 30fps video output");
            return FULLHD; // doesn't matter what size is returned here
        }

        private @NonNull Size getMaxPreviewSize(List<Size> orderedPreviewSizes) {
            if (orderedPreviewSizes != null) {
                for (Size size : orderedPreviewSizes) {
                    if ((mDisplaySize.getWidth() >= size.getWidth()) &&
                            (mDisplaySize.getHeight() >= size.getHeight())) {
                        return size;
                    }
                }
            }

            Log.w(TAG,"Camera " + mCameraId + " maximum preview size search failed with "
                    + "display size " + mDisplaySize);
            return kPreviewSizeBound;
        }

        /**
         * Size comparator that compares the number of pixels it covers.
         *
         * <p>If two the areas of two sizes are same, compare the widths.</p>
         */
        public static class SizeComparator implements Comparator<Size> {
            @Override
            public int compare(@NonNull Size lhs, @NonNull Size rhs) {
                return StreamConfigurationMap.compareSizes(lhs.getWidth(), lhs.getHeight(),
                        rhs.getWidth(), rhs.getHeight());
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
        private static @NonNull List<Size> getAscendingOrderSizes(
                @NonNull final List<Size> sizeList, boolean ascending) {
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
