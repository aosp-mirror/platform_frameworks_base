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

import static com.android.internal.R.string.hardware;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.util.ArraySet;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * Immutable class to store the recommended stream configurations to set up
 * {@link android.view.Surface Surfaces} for creating a
 * {@link android.hardware.camera2.CameraCaptureSession capture session} with
 * {@link android.hardware.camera2.CameraDevice#createCaptureSession}.
 *
 * <p>The recommended list does not replace or deprecate the exhaustive complete list found in
 * {@link StreamConfigurationMap}. It is a suggestion about available power and performance
 * efficient stream configurations for a specific use case. Per definition it is only a subset
 * of {@link StreamConfigurationMap} and can be considered by developers for optimization
 * purposes.</p>
 *
 * <p>This also duplicates the minimum frame durations and stall durations from the
 * {@link StreamConfigurationMap} for each format/size combination that can be used to calculate
 * effective frame rate when submitting multiple captures.
 * </p>
 *
 * <p>An instance of this object is available by invoking
 * {@link CameraCharacteristics#getRecommendedStreamConfigurationMap} and passing a respective
 * usecase id. For more information about supported use case constants see
 * {@link #USECASE_PREVIEW}.</p>
 *
 * <pre><code>{@code
 * CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
 * RecommendedStreamConfigurationMap configs = characteristics.getRecommendedStreamConfigurationMap(
 *         RecommendedStreamConfigurationMap.USECASE_PREVIEW);
 * }</code></pre>
 *
 * @see CameraCharacteristics#getRecommendedStreamConfigurationMap
 * @see CameraDevice#createCaptureSession
 */
public final class RecommendedStreamConfigurationMap {

    private static final String TAG = "RecommendedStreamConfigurationMap";
    private int mUsecase;
    private boolean mSupportsPrivate;
    private StreamConfigurationMap mRecommendedMap;

    /** @hide */
    public static final int MAX_USECASE_COUNT = 32;

    /**
     * The recommended stream configuration map for use case preview must contain a subset of
     * efficient, non-stalling configurations that must include both
     * {@link android.graphics.ImageFormat#PRIVATE} and
     * {@link android.graphics.ImageFormat#YUV_420_888} output formats. Even if available for the
     * camera device, high speed or input configurations will be absent.
     */
    public static final int USECASE_PREVIEW = 0x0;

    /**
     * The recommended stream configuration map for recording must contain a subset of efficient
     * video configurations that include {@link android.graphics.ImageFormat#PRIVATE}
     * output format for at least all supported {@link android.media.CamcorderProfile profiles}.
     * High speed configurations if supported will be available as well. Even if available for the
     * camera device, input configurations will be absent.
     */
    public static final int USECASE_RECORD = 0x1;

    /**
     * The recommended stream configuration map for use case video snapshot must only contain a
     * subset of efficient liveshot configurations that include
     * {@link android.graphics.ImageFormat#JPEG} output format. The sizes will match at least
     * the maximum resolution of usecase record and will not cause any preview glitches. Even
     * if available for the camera device, high speed or input configurations will be absent.
     */
    public static final int USECASE_VIDEO_SNAPSHOT = 0x2;

    /**
     * The recommended stream configuration map for use case snapshot must contain a subset of
     * efficient still capture configurations that must include
     * {@link android.graphics.ImageFormat#JPEG} output format and at least one configuration with
     * size approximately equal to the sensor pixel array size
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE}.
     * Even if available for the camera device, high speed or input configurations will be absent.
     */
    public static final int USECASE_SNAPSHOT = 0x3;

    /**
     * In case the device supports
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING} and/or
     * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING},
     * the recommended stream configuration map for use case ZSL must contain a subset of efficient
     * configurations that include the suggested input and output format mappings. Even if
     * available for the camera device, high speed configurations will be absent.
     */
    public static final int USECASE_ZSL = 0x4;

    /**
     * In case the device supports
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW}, the
     * recommended stream configuration map for use case RAW must contain a subset of efficient
     * configurations that include the {@link android.graphics.ImageFormat#RAW_SENSOR} and other
     * RAW output formats. Even if available for the camera device, high speed and input
     * configurations will be absent.
     */
    public static final int USECASE_RAW = 0x5;

    /**
     * The recommended stream configuration map for use case low latency snapshot must contain
     * subset of configurations with end-to-end latency that does not exceed 200 ms. under standard
     * operating conditions (reasonable light levels, not loaded system). The expected output format
     * will be primarily {@link android.graphics.ImageFormat#JPEG} however other image formats can
     * be present as well.  Even if available for the camera device, high speed and input
     * configurations will be absent. This suggested configuration map may be absent on some devices
     * that can not support any low latency requests.
     */
    public static final int USECASE_LOW_LATENCY_SNAPSHOT = 0x6;

    /**
     * If supported, the recommended 10-bit output stream configurations must include
     * a subset of the advertised {@link android.graphics.ImageFormat#YCBCR_P010} and
     * {@link android.graphics.ImageFormat#PRIVATE} outputs that are optimized for power
     * and performance when registered along with a supported 10-bit dynamic range profile.
     * {@see android.hardware.camera2.params.OutputConfiguration#setDynamicRangeProfile} for
     * details.
     */
     public static final int USECASE_10BIT_OUTPUT = 0x8;

    /**
     * Device specific use cases.
     * @hide
     */
    public static final int USECASE_VENDOR_START = 0x18;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"USECASE_"}, value =
        {USECASE_PREVIEW,
        USECASE_RECORD,
        USECASE_VIDEO_SNAPSHOT,
        USECASE_SNAPSHOT,
        USECASE_ZSL,
        USECASE_RAW,
        USECASE_LOW_LATENCY_SNAPSHOT,
        USECASE_10BIT_OUTPUT})
     public @interface RecommendedUsecase {};

    /**
     * Create a new {@link RecommendedStreamConfigurationMap}.
     *
     * @param recommendedMap stream configuration map that contains for the specific use case
     * @param usecase Recommended use case
     * @param supportsPrivate Flag indicating private format support.
     *
     * @hide
     */
    public RecommendedStreamConfigurationMap(StreamConfigurationMap recommendedMap, int usecase,
            boolean supportsPrivate) {
        mRecommendedMap = recommendedMap;
        mUsecase = usecase;
        mSupportsPrivate = supportsPrivate;
    }

    /**
     * Get the use case value for the recommended stream configurations.
     *
     * @return Use case id.
     */
    public @RecommendedUsecase int getRecommendedUseCase() {
        return mUsecase;
    }

    private Set<Integer> getUnmodifiableIntegerSet(int[] intArray) {
        if ((intArray != null) && (intArray.length > 0)) {
            ArraySet<Integer> integerSet = new ArraySet<Integer>();
            integerSet.ensureCapacity(intArray.length);
            for (int intEntry : intArray) {
                integerSet.add(intEntry);
            }

            return Collections.unmodifiableSet(integerSet);
        }

        return null;
    }

    /**
     * Get the image {@code format} output formats in this stream configuration.
     *
     * <p>
     * For more information refer to {@link StreamConfigurationMap#getOutputFormats}.
     * </p>
     *
     * @return a non-modifiable set of Integer formats
     */
    public @NonNull Set<Integer> getOutputFormats() {
        return getUnmodifiableIntegerSet(mRecommendedMap.getOutputFormats());
    }

    /**
     * Get the image {@code format} output formats for a reprocessing input format.
     *
     * <p>
     * For more information refer to {@link StreamConfigurationMap#getValidOutputFormatsForInput}.
     * </p>
     *
     * @return a non-modifiable set of Integer formats
     */
    public @Nullable Set<Integer> getValidOutputFormatsForInput(@Format int inputFormat) {
        return getUnmodifiableIntegerSet(mRecommendedMap.getValidOutputFormatsForInput(
                    inputFormat));
    }

    /**
     * Get the image {@code format} input formats in this stream configuration.
     *
     * <p>All image formats returned by this function will be defined in either {@link ImageFormat}
     * or in {@link PixelFormat} (and there is no possibility of collision).</p>
     *
     * @return a non-modifiable set of Integer formats
     */
    public @Nullable Set<Integer> getInputFormats() {
        return getUnmodifiableIntegerSet(mRecommendedMap.getInputFormats());
    }

    private Set<Size> getUnmodifiableSizeSet(Size[] sizeArray) {
        if ((sizeArray != null) && (sizeArray.length > 0)) {
            ArraySet<Size> sizeSet = new ArraySet<Size>();
            sizeSet.addAll(Arrays.asList(sizeArray));
            return Collections.unmodifiableSet(sizeSet);
        }

        return  null;
    }

    /**
     * Get the supported input sizes for this input format.
     *
     * <p>The format must have come from {@link #getInputFormats}; otherwise
     * {@code null} is returned.</p>
     *
     * @param format a format from {@link #getInputFormats}
     * @return a non-modifiable set of sizes, or {@code null} if the format was not available.
     */
    public @Nullable Set<Size> getInputSizes(@Format int format) {
        return getUnmodifiableSizeSet(mRecommendedMap.getInputSizes(format));
    }

    /**
     * Determine whether or not output surfaces with a particular user-defined format can be passed
     * {@link CameraDevice#createCaptureSession createCaptureSession}.
     *
     * <p>
     * For further information refer to {@link StreamConfigurationMap#isOutputSupportedFor}.
     * </p>
     *
     *
     * @param format an image format from either {@link ImageFormat} or {@link PixelFormat}
     * @return
     *          {@code true} if using a {@code surface} with this {@code format} will be
     *          supported with {@link CameraDevice#createCaptureSession}
     *
     * @throws IllegalArgumentException
     *          if the image format was not a defined named constant
     *          from either {@link ImageFormat} or {@link PixelFormat}
     */
    public boolean isOutputSupportedFor(@Format int format) {
        return mRecommendedMap.isOutputSupportedFor(format);
    }

    /**
     * Get a list of sizes compatible with the requested image {@code format}.
     *
     * <p>
     * For more information refer to {@link StreamConfigurationMap#getOutputSizes}.
     * </p>
     *
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @return  a non-modifiable set of supported sizes,
     *          or {@code null} if the {@code format} is not a supported output
     */
    public @Nullable Set<Size> getOutputSizes(@Format int format) {
        return getUnmodifiableSizeSet(mRecommendedMap.getOutputSizes(format));
    }

    /**
     * Get a list of supported high speed video recording sizes.
     * <p>
     * For more information refer to {@link StreamConfigurationMap#getHighSpeedVideoSizes}.
     * </p>
     *
     * @return a non-modifiable set of supported high speed video recording sizes
     */
    public @Nullable Set<Size> getHighSpeedVideoSizes() {
        return getUnmodifiableSizeSet(mRecommendedMap.getHighSpeedVideoSizes());
    }

    private Set<Range<Integer>> getUnmodifiableRangeSet(Range<Integer>[] rangeArray) {
        if ((rangeArray != null) && (rangeArray.length > 0)) {
            ArraySet<Range<Integer>> rangeSet = new ArraySet<Range<Integer>>();
            rangeSet.addAll(Arrays.asList(rangeArray));
            return Collections.unmodifiableSet(rangeSet);
        }

        return null;
    }

    /**
     * Get the frame per second ranges (fpsMin, fpsMax) for input high speed video size.
     *
     * <p>
     * For further information refer to
     * {@link StreamConfigurationMap#getHighSpeedVideoFpsRangesFor}.
     * </p>
     * @param size one of the sizes returned by {@link #getHighSpeedVideoSizes()}
     * @return a non-modifiable set of supported high speed video recording FPS ranges The upper
     *         bound of returned ranges is guaranteed to be greater than or equal to 120.
     * @throws IllegalArgumentException if input size does not exist in the return value of
     *             getHighSpeedVideoSizes
     */
    public @Nullable Set<Range<Integer>> getHighSpeedVideoFpsRangesFor(@NonNull Size size) {
        return getUnmodifiableRangeSet(mRecommendedMap.getHighSpeedVideoFpsRangesFor(size));
    }

    /**
     * Get a list of supported high speed video recording FPS ranges.
     * <p>
     * For further information refer to {@link StreamConfigurationMap#getHighSpeedVideoFpsRanges}.
     * </p>
     * @return a non-modifiable set of supported high speed video recording FPS ranges The upper
     *         bound of returned ranges is guaranteed to be larger or equal to 120.
     */
    public @Nullable Set<Range<Integer>> getHighSpeedVideoFpsRanges() {
        return getUnmodifiableRangeSet(mRecommendedMap.getHighSpeedVideoFpsRanges());
    }

    /**
     * Get the supported video sizes for an input high speed FPS range.
     *
     * <p>
     * For further information refer to {@link StreamConfigurationMap#getHighSpeedVideoSizesFor}.
     * </p>
     *
     * @param fpsRange one of the FPS ranges returned by {@link #getHighSpeedVideoFpsRanges()}
     * @return A non-modifiable set of video sizes to create high speed capture sessions for high
     *         speed streaming use cases.
     *
     * @throws IllegalArgumentException if input FPS range does not exist in the return value of
     *         getHighSpeedVideoFpsRanges
     */
    public @Nullable Set<Size> getHighSpeedVideoSizesFor(@NonNull Range<Integer> fpsRange) {
        return getUnmodifiableSizeSet(mRecommendedMap.getHighSpeedVideoSizesFor(fpsRange));
    }

    /**
     * Get a list of supported high resolution sizes, which cannot operate at full BURST_CAPTURE
     * rate.
     *
     * <p>
     * For further information refer to {@link StreamConfigurationMap#getHighResolutionOutputSizes}.
     * </p>
     *
     * @return a non-modifiable set of supported slower high-resolution sizes, or {@code null} if
     *         the BURST_CAPTURE capability is not supported
     */
    public @Nullable Set<Size> getHighResolutionOutputSizes(@Format int format) {
        return getUnmodifiableSizeSet(mRecommendedMap.getHighResolutionOutputSizes(format));
    }

    /**
     * Get the minimum
     * {@link android.hardware.camera2.CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the format/size combination (in nanoseconds).
     *
     * <p>
     * For further information refer to {@link StreamConfigurationMap#getOutputMinFrameDuration}.
     * </p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >} 0 in nanoseconds, or
     *          0 if the minimum frame duration is not available.
     *
     * @throws IllegalArgumentException if {@code format} or {@code size} was not supported
     */
    public @IntRange(from = 0) long getOutputMinFrameDuration(@Format int format,
            @NonNull Size size) {
        return mRecommendedMap.getOutputMinFrameDuration(format, size);
    }

    /**
     * Get the stall duration for the format/size combination (in nanoseconds).
     *
     * <p>
     * For further information refer to {@link StreamConfigurationMap#getOutputStallDuration}.
     * </p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @param size an output-compatible size
     * @return a stall duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code format} or {@code size} was not supported
     */
    public @IntRange(from = 0) long getOutputStallDuration(@Format int format, @NonNull Size size) {
        return mRecommendedMap.getOutputStallDuration(format, size);
    }

    /**
     * Get a list of sizes compatible with {@code klass} to use as an output.
     *
     * <p>For further information refer to {@link StreamConfigurationMap#getOutputSizes(Class)}.
     * </p>
     *
     * @param klass
     *          a {@link Class} object reference
     * @return
     *          a non-modifiable set of supported sizes for {@link ImageFormat#PRIVATE} format,
     *          or {@code null} if the {@code klass} is not a supported output.
     */
    public @Nullable <T> Set<Size> getOutputSizes(@NonNull Class<T> klass) {
        if (mSupportsPrivate) {
            return getUnmodifiableSizeSet(mRecommendedMap.getOutputSizes(klass));
        }

        return null;
    }

    /**
     * Get the minimum {@link CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the class/size combination (in nanoseconds).
     *
     * <p>For more information refer to
     * {@link StreamConfigurationMap#getOutputMinFrameDuration(Class, Size)}.</p>
     *
     * @param klass
     *          a class which has a non-empty array returned by {@link #getOutputSizes(Class)}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >} 0 in nanoseconds, or
     *          0 if the minimum frame duration is not available.
     *
     * @throws IllegalArgumentException if {@code klass} or {@code size} was not supported
     */
    public @IntRange(from = 0) <T> long getOutputMinFrameDuration(@NonNull final Class<T> klass,
            @NonNull final Size size) {
        if (mSupportsPrivate) {
            return mRecommendedMap.getOutputMinFrameDuration(klass, size);
        }

        return 0;
    }

    /**
     * Get the stall duration for the class/size combination (in nanoseconds).
     *
     * <p>For more information refer to
     * {@link StreamConfigurationMap#getOutputStallDuration(Class, Size)}.
     *
     * @param klass
     *          a class which has a non-empty array returned by {@link #getOutputSizes(Class)}.
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >} 0 in nanoseconds, or 0 if the stall duration is
     *         not available.
     *
     * @throws IllegalArgumentException if {@code klass} or {@code size} was not supported
     */
    public @IntRange(from = 0) <T> long getOutputStallDuration(@NonNull final Class<T> klass,
            @NonNull final Size size) {
        if (mSupportsPrivate) {
            return mRecommendedMap.getOutputStallDuration(klass, size);
        }

        return 0;
    }

    /**
     * Determine whether or not the {@code surface} in its current state is suitable to be included
     * in a {@link CameraDevice#createCaptureSession capture session} as an output.
     *
     * <p>For more information refer to {@link StreamConfigurationMap#isOutputSupportedFor}.
     * </p>
     *
     * @param surface a {@link Surface} object reference
     * @return {@code true} if this is supported, {@code false} otherwise
     *
     * @throws IllegalArgumentException if the Surface endpoint is no longer valid
     *
     */
    public boolean isOutputSupportedFor(@NonNull Surface surface) {
        return mRecommendedMap.isOutputSupportedFor(surface);
    }

}
