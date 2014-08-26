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

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.view.Surface;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

import static com.android.internal.util.Preconditions.*;

/**
 * Immutable class to store the available stream
 * {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP configurations} to set up
 * {@link android.view.Surface Surfaces} for creating a
 * {@link android.hardware.camera2.CameraCaptureSession capture session} with
 * {@link android.hardware.camera2.CameraDevice#createCaptureSession}.
 * <!-- TODO: link to input stream configuration -->
 *
 * <p>This is the authoritative list for all <!-- input/ -->output formats (and sizes respectively
 * for that format) that are supported by a camera device.</p>
 *
 * <p>This also contains the minimum frame durations and stall durations for each format/size
 * combination that can be used to calculate effective frame rate when submitting multiple captures.
 * </p>
 *
 * <p>An instance of this object is available from {@link CameraCharacteristics} using
 * the {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP} key and the
 * {@link CameraCharacteristics#get} method.</p>
 *
 * <pre><code>{@code
 * CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
 * StreamConfigurationMap configs = characteristics.get(
 *         CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
 * }</code></pre>
 *
 * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
 * @see CameraDevice#createCaptureSession
 */
public final class StreamConfigurationMap {

    private static final String TAG = "StreamConfigurationMap";

    /**
     * Create a new {@link StreamConfigurationMap}.
     *
     * <p>The array parameters ownership is passed to this object after creation; do not
     * write to them after this constructor is invoked.</p>
     *
     * @param configurations a non-{@code null} array of {@link StreamConfiguration}
     * @param minFrameDurations a non-{@code null} array of {@link StreamConfigurationDuration}
     * @param stallDurations a non-{@code null} array of {@link StreamConfigurationDuration}
     * @param highSpeedVideoConfigurations an array of {@link HighSpeedVideoConfiguration}, null if
     *        camera device does not support high speed video recording
     *
     * @throws NullPointerException if any of the arguments except highSpeedVideoConfigurations
     *         were {@code null} or any subelements were {@code null}
     *
     * @hide
     */
    public StreamConfigurationMap(
            StreamConfiguration[] configurations,
            StreamConfigurationDuration[] minFrameDurations,
            StreamConfigurationDuration[] stallDurations,
            HighSpeedVideoConfiguration[] highSpeedVideoConfigurations) {

        mConfigurations = checkArrayElementsNotNull(configurations, "configurations");
        mMinFrameDurations = checkArrayElementsNotNull(minFrameDurations, "minFrameDurations");
        mStallDurations = checkArrayElementsNotNull(stallDurations, "stallDurations");
        if (highSpeedVideoConfigurations == null) {
            mHighSpeedVideoConfigurations = new HighSpeedVideoConfiguration[0];
        } else {
            mHighSpeedVideoConfigurations = checkArrayElementsNotNull(
                    highSpeedVideoConfigurations, "highSpeedVideoConfigurations");
        }

        // For each format, track how many sizes there are available to configure
        for (StreamConfiguration config : configurations) {
            HashMap<Integer, Integer> map = config.isOutput() ? mOutputFormats : mInputFormats;

            Integer count = map.get(config.getFormat());

            if (count == null) {
                count = 0;
            }
            count = count + 1;

            map.put(config.getFormat(), count);
        }

        if (!mOutputFormats.containsKey(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED)) {
            throw new AssertionError(
                    "At least one stream configuration for IMPLEMENTATION_DEFINED must exist");
        }

        // For each Size/FPS range, track how many FPS range/Size there are available
        for (HighSpeedVideoConfiguration config : mHighSpeedVideoConfigurations) {
            Size size = config.getSize();
            Range<Integer> fpsRange = config.getFpsRange();
            Integer fpsRangeCount = mHighSpeedVideoSizeMap.get(size);
            if (fpsRangeCount == null) {
                fpsRangeCount = 0;
            }
            mHighSpeedVideoSizeMap.put(size, fpsRangeCount + 1);
            Integer sizeCount = mHighSpeedVideoFpsRangeMap.get(fpsRange);
            if (sizeCount == null) {
                sizeCount = 0;
            }
            mHighSpeedVideoFpsRangeMap.put(fpsRange, sizeCount + 1);
        }
    }

    /**
     * Get the image {@code format} output formats in this stream configuration.
     *
     * <p>All image formats returned by this function will be defined in either {@link ImageFormat}
     * or in {@link PixelFormat} (and there is no possibility of collision).</p>
     *
     * <p>Formats listed in this array are guaranteed to return true if queried with
     * {@link #isOutputSupportedFor(int)}.</p>
     *
     * @return an array of integer format
     *
     * @see ImageFormat
     * @see PixelFormat
     */
    public final int[] getOutputFormats() {
        return getPublicFormats(/*output*/true);
    }

    /**
     * Get the image {@code format} input formats in this stream configuration.
     *
     * <p>All image formats returned by this function will be defined in either {@link ImageFormat}
     * or in {@link PixelFormat} (and there is no possibility of collision).</p>
     *
     * @return an array of integer format
     *
     * @see ImageFormat
     * @see PixelFormat
     *
     * @hide
     */
    public final int[] getInputFormats() {
        return getPublicFormats(/*output*/false);
    }

    /**
     * Get the supported input sizes for this input format.
     *
     * <p>The format must have come from {@link #getInputFormats}; otherwise
     * {@code null} is returned.</p>
     *
     * @param format a format from {@link #getInputFormats}
     * @return a non-empty array of sizes, or {@code null} if the format was not available.
     *
     * @hide
     */
    public Size[] getInputSizes(final int format) {
        return getPublicFormatSizes(format, /*output*/false);
    }

    /**
     * Determine whether or not output surfaces with a particular user-defined format can be passed
     * {@link CameraDevice#createCaptureSession createCaptureSession}.
     *
     * <p>This method determines that the output {@code format} is supported by the camera device;
     * each output {@code surface} target may or may not itself support that {@code format}.
     * Refer to the class which provides the surface for additional documentation.</p>
     *
     * <p>Formats for which this returns {@code true} are guaranteed to exist in the result
     * returned by {@link #getOutputSizes}.</p>
     *
     * @param format an image format from either {@link ImageFormat} or {@link PixelFormat}
     * @return
     *          {@code true} iff using a {@code surface} with this {@code format} will be
     *          supported with {@link CameraDevice#createCaptureSession}
     *
     * @throws IllegalArgumentException
     *          if the image format was not a defined named constant
     *          from either {@link ImageFormat} or {@link PixelFormat}
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see CameraDevice#createCaptureSession
     */
    public boolean isOutputSupportedFor(int format) {
        checkArgumentFormat(format);

        format = imageFormatToInternal(format);
        return getFormatsMap(/*output*/true).containsKey(format);
    }

    /**
     * Determine whether or not output streams can be configured with a particular class
     * as a consumer.
     *
     * <p>The following list is generally usable for outputs:
     * <ul>
     * <li>{@link android.media.ImageReader} -
     * Recommended for image processing or streaming to external resources (such as a file or
     * network)
     * <li>{@link android.media.MediaRecorder} -
     * Recommended for recording video (simple to use)
     * <li>{@link android.media.MediaCodec} -
     * Recommended for recording video (more complicated to use, with more flexibility)
     * <li>{@link android.renderscript.Allocation} -
     * Recommended for image processing with {@link android.renderscript RenderScript}
     * <li>{@link android.view.SurfaceHolder} -
     * Recommended for low-power camera preview with {@link android.view.SurfaceView}
     * <li>{@link android.graphics.SurfaceTexture} -
     * Recommended for OpenGL-accelerated preview processing or compositing with
     * {@link android.view.TextureView}
     * </ul>
     * </p>
     *
     * <p>Generally speaking this means that creating a {@link Surface} from that class <i>may</i>
     * provide a producer endpoint that is suitable to be used with
     * {@link CameraDevice#createCaptureSession}.</p>
     *
     * <p>Since not all of the above classes support output of all format and size combinations,
     * the particular combination should be queried with {@link #isOutputSupportedFor(Surface)}.</p>
     *
     * @param klass a non-{@code null} {@link Class} object reference
     * @return {@code true} if this class is supported as an output, {@code false} otherwise
     *
     * @throws NullPointerException if {@code klass} was {@code null}
     *
     * @see CameraDevice#createCaptureSession
     * @see #isOutputSupportedFor(Surface)
     */
    public static <T> boolean isOutputSupportedFor(Class<T> klass) {
        checkNotNull(klass, "klass must not be null");

        if (klass == android.media.ImageReader.class) {
            return true;
        } else if (klass == android.media.MediaRecorder.class) {
            return true;
        } else if (klass == android.media.MediaCodec.class) {
            return true;
        } else if (klass == android.renderscript.Allocation.class) {
            return true;
        } else if (klass == android.view.SurfaceHolder.class) {
            return true;
        } else if (klass == android.graphics.SurfaceTexture.class) {
            return true;
        }

        return false;
    }

    /**
     * Determine whether or not the {@code surface} in its current state is suitable to be included
     * in a {@link CameraDevice#createCaptureSession capture session} as an output.
     *
     * <p>Not all surfaces are usable with the {@link CameraDevice}, and not all configurations
     * of that {@code surface} are compatible. Some classes that provide the {@code surface} are
     * compatible with the {@link CameraDevice} in general
     * (see {@link #isOutputSupportedFor(Class)}, but it is the caller's responsibility to put the
     * {@code surface} into a state that will be compatible with the {@link CameraDevice}.</p>
     *
     * <p>Reasons for a {@code surface} being specifically incompatible might be:
     * <ul>
     * <li>Using a format that's not listed by {@link #getOutputFormats}
     * <li>Using a format/size combination that's not listed by {@link #getOutputSizes}
     * <li>The {@code surface} itself is not in a state where it can service a new producer.</p>
     * </li>
     * </ul>
     *
     * This is not an exhaustive list; see the particular class's documentation for further
     * possible reasons of incompatibility.</p>
     *
     * @param surface a non-{@code null} {@link Surface} object reference
     * @return {@code true} if this is supported, {@code false} otherwise
     *
     * @throws NullPointerException if {@code surface} was {@code null}
     *
     * @see CameraDevice#createCaptureSession
     * @see #isOutputSupportedFor(Class)
     */
    public boolean isOutputSupportedFor(Surface surface) {
        checkNotNull(surface, "surface must not be null");

        throw new UnsupportedOperationException("Not implemented yet");

        // TODO: JNI function that checks the Surface's IGraphicBufferProducer state
    }

    /**
     * Get a list of sizes compatible with {@code klass} to use as an output.
     *
     * <p>Since some of the supported classes may support additional formats beyond
     * an opaque/implementation-defined (under-the-hood) format; this function only returns
     * sizes for the implementation-defined format.</p>
     *
     * <p>Some classes such as {@link android.media.ImageReader} may only support user-defined
     * formats; in particular {@link #isOutputSupportedFor(Class)} will return {@code true} for
     * that class and this method will return an empty array (but not {@code null}).</p>
     *
     * <p>If a well-defined format such as {@code NV21} is required, use
     * {@link #getOutputSizes(int)} instead.</p>
     *
     * <p>The {@code klass} should be a supported output, that querying
     * {@code #isOutputSupportedFor(Class)} should return {@code true}.</p>
     *
     * @param klass
     *          a non-{@code null} {@link Class} object reference
     * @return
     *          an array of supported sizes for implementation-defined formats,
     *          or {@code null} iff the {@code klass} is not a supported output
     *
     * @throws NullPointerException if {@code klass} was {@code null}
     *
     * @see #isOutputSupportedFor(Class)
     */
    public <T> Size[] getOutputSizes(Class<T> klass) {
        // Image reader is "supported", but never for implementation-defined formats; return empty
        if (android.media.ImageReader.class.isAssignableFrom(klass)) {
            return new Size[0];
        }

        if (isOutputSupportedFor(klass) == false) {
            return null;
        }

        return getInternalFormatSizes(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED, /*output*/true);
    }

    /**
     * Get a list of sizes compatible with the requested image {@code format}.
     *
     * <p>The {@code format} should be a supported format (one of the formats returned by
     * {@link #getOutputFormats}).</p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @return
     *          an array of supported sizes,
     *          or {@code null} if the {@code format} is not a supported output
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see #getOutputFormats
     */
    public Size[] getOutputSizes(int format) {
        return getPublicFormatSizes(format, /*output*/true);
    }

    /**
     * Get a list of supported high speed video recording sizes.
     *
     * <p> When HIGH_SPEED_VIDEO is supported in
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES available scene modes}, this
     * method will list the supported high speed video size configurations. All the sizes listed
     * will be a subset of the sizes reported by {@link #getOutputSizes} for processed non-stalling
     * formats (typically ImageFormat#YUV_420_888, ImageFormat#NV21, ImageFormat#YV12)</p>
     *
     * <p> To enable high speed video recording, application must set
     * {@link CaptureRequest#CONTROL_SCENE_MODE} to
     * {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO} in capture
     * requests and select the video size from this method and
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS range} from
     * {@link #getHighSpeedVideoFpsRangesFor} to configure the recording and preview streams and
     * setup the recording requests. For example, if the application intends to do high speed
     * recording, it can select the maximum size reported by this method to configure output
     * streams. Note that for the use case of multiple output streams, application must select one
     * unique size from this method to use. Otherwise a request error might occur. Once the size is
     * selected, application can get the supported FPS ranges by
     * {@link #getHighSpeedVideoFpsRangesFor}, and use these FPS ranges to setup the recording
     * requests.</p>
     *
     * @return
     *          an array of supported high speed video recording sizes
     *
     * @see #getHighSpeedVideoFpsRangesFor(Size)
     */
    public Size[] getHighSpeedVideoSizes() {
        Set<Size> keySet = mHighSpeedVideoSizeMap.keySet();
        return keySet.toArray(new Size[keySet.size()]);
    }

    /**
     * Get the frame per second ranges (fpsMin, fpsMax) for input high speed video size.
     *
     * <p> See {@link #getHighSpeedVideoSizes} for how to enable high speed recording.</p>
     *
     * <p> For normal video recording use case, where some application will NOT set
     * {@link CaptureRequest#CONTROL_SCENE_MODE} to
     * {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO} in capture
     * requests, the {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS ranges} reported in
     * this method must not be used to setup capture requests, or it will cause request error.</p>
     *
     * @param size one of the sizes returned by {@link #getHighSpeedVideoSizes()}
     * @return
     *          An array of FPS range to use with
     *          {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE TARGET_FPS_RANGE} when using
     *          {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO} scene
     *          mode.
     *          The upper bound of returned ranges is guaranteed to be larger or equal to 60.
     *
     * @throws IllegalArgumentException if input size does not exist in the return value of
     *         getHighSpeedVideoSizes
     * @see #getHighSpeedVideoSizes()
     */
    public Range<Integer>[] getHighSpeedVideoFpsRangesFor(Size size) {
        Integer fpsRangeCount = mHighSpeedVideoSizeMap.get(size);
        if (fpsRangeCount == null || fpsRangeCount == 0) {
            throw new IllegalArgumentException(String.format(
                    "Size %s does not support high speed video recording", size));
        }

        @SuppressWarnings("unchecked")
        Range<Integer>[] fpsRanges = new Range[fpsRangeCount];
        int i = 0;
        for (HighSpeedVideoConfiguration config : mHighSpeedVideoConfigurations) {
            if (size.equals(config.getSize())) {
                fpsRanges[i++] = config.getFpsRange();
            }
        }
        return fpsRanges;
    }

    /**
     * Get a list of supported high speed video recording FPS ranges.
     *
     * <p> When HIGH_SPEED_VIDEO is supported in
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES available scene modes}, this
     * method will list the supported high speed video FPS range configurations. Application can
     * then use {@link #getHighSpeedVideoSizesFor} to query available sizes for one of returned
     * FPS range.</p>
     *
     * <p> To enable high speed video recording, application must set
     * {@link CaptureRequest#CONTROL_SCENE_MODE} to
     * {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO} in capture
     * requests and select the video size from {@link #getHighSpeedVideoSizesFor} and
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS range} from
     * this method to configure the recording and preview streams and setup the recording requests.
     * For example, if the application intends to do high speed recording, it can select one FPS
     * range reported by this method, query the video sizes corresponding to this FPS range  by
     * {@link #getHighSpeedVideoSizesFor} and select one of reported sizes to configure output
     * streams. Note that for the use case of multiple output streams, application must select one
     * unique size from {@link #getHighSpeedVideoSizesFor}, and use it for all output streams.
     * Otherwise a request error might occur when attempting to enable
     * {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO}.
     * Once the stream is configured, application can set the FPS range in the recording requests.
     * </p>
     *
     * @return
     *          an array of supported high speed video recording FPS ranges
     *          The upper bound of returned ranges is guaranteed to be larger or equal to 60.
     *
     * @see #getHighSpeedVideoSizesFor
     */
    @SuppressWarnings("unchecked")
    public Range<Integer>[] getHighSpeedVideoFpsRanges() {
        Set<Range<Integer>> keySet = mHighSpeedVideoFpsRangeMap.keySet();
        return keySet.toArray(new Range[keySet.size()]);
    }

    /**
     * Get the supported video sizes for input FPS range.
     *
     * <p> See {@link #getHighSpeedVideoFpsRanges} for how to enable high speed recording.</p>
     *
     * <p> For normal video recording use case, where the application will NOT set
     * {@link CaptureRequest#CONTROL_SCENE_MODE} to
     * {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO} in capture
     * requests, the {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS ranges} reported in
     * this method must not be used to setup capture requests, or it will cause request error.</p>
     *
     * @param fpsRange one of the FPS range returned by {@link #getHighSpeedVideoFpsRanges()}
     * @return
     *          An array of video sizes to configure output stream when using
     *          {@link CaptureRequest#CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO HIGH_SPEED_VIDEO} scene
     *          mode.
     *
     * @throws IllegalArgumentException if input FPS range does not exist in the return value of
     *         getHighSpeedVideoFpsRanges
     * @see #getHighSpeedVideoFpsRanges()
     */
    public Size[] getHighSpeedVideoSizesFor(Range<Integer> fpsRange) {
        Integer sizeCount = mHighSpeedVideoFpsRangeMap.get(fpsRange);
        if (sizeCount == null || sizeCount == 0) {
            throw new IllegalArgumentException(String.format(
                    "FpsRange %s does not support high speed video recording", fpsRange));
        }

        Size[] sizes = new Size[sizeCount];
        int i = 0;
        for (HighSpeedVideoConfiguration config : mHighSpeedVideoConfigurations) {
            if (fpsRange.equals(config.getFpsRange())) {
                sizes[i++] = config.getSize();
            }
        }
        return sizes;
    }

    /**
     * Get the minimum {@link CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the format/size combination (in nanoseconds).
     *
     * <p>{@code format} should be one of the ones returned by {@link #getOutputFormats()}.</p>
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * <p>This should correspond to the frame duration when only that stream is active, with all
     * processing (typically in {@code android.*.mode}) set to either {@code OFF} or {@code FAST}.
     * </p>
     *
     * <p>When multiple streams are used in a request, the minimum frame duration will be
     * {@code max(individual stream min durations)}.</p>
     *
     * <p>For devices that do not support manual sensor control
     * ({@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR}),
     * this function may return 0.</p>
     *
     * <!--
     * TODO: uncomment after adding input stream support
     * <p>The minimum frame duration of a stream (of a particular format, size) is the same
     * regardless of whether the stream is input or output.</p>
     * -->
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >} 0 in nanoseconds, or
     *          0 if the minimum frame duration is not available.
     *
     * @throws IllegalArgumentException if {@code format} or {@code size} was not supported
     * @throws NullPointerException if {@code size} was {@code null}
     *
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see #getOutputStallDuration(int, Size)
     * @see ImageFormat
     * @see PixelFormat
     */
    public long getOutputMinFrameDuration(int format, Size size) {
        checkNotNull(size, "size must not be null");
        checkArgumentFormatSupported(format, /*output*/true);

        return getInternalFormatDuration(imageFormatToInternal(format), size, DURATION_MIN_FRAME);
    }

    /**
     * Get the minimum {@link CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the class/size combination (in nanoseconds).
     *
     * <p>This assumes a the {@code klass} is set up to use an implementation-defined format.
     * For user-defined formats, use {@link #getOutputMinFrameDuration(int, Size)}.</p>
     *
     * <p>{@code klass} should be one of the ones which is supported by
     * {@link #isOutputSupportedFor(Class)}.</p>
     *
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * <p>This should correspond to the frame duration when only that stream is active, with all
     * processing (typically in {@code android.*.mode}) set to either {@code OFF} or {@code FAST}.
     * </p>
     *
     * <p>When multiple streams are used in a request, the minimum frame duration will be
     * {@code max(individual stream min durations)}.</p>
     *
     * <p>For devices that do not support manual sensor control
     * ({@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR}),
     * this function may return 0.</p>
     *
     * <!--
     * TODO: uncomment after adding input stream support
     * <p>The minimum frame duration of a stream (of a particular format, size) is the same
     * regardless of whether the stream is input or output.</p>
     * -->
     *
     * @param klass
     *          a class which is supported by {@link #isOutputSupportedFor(Class)} and has a
     *          non-empty array returned by {@link #getOutputSizes(Class)}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >} 0 in nanoseconds, or
     *          0 if the minimum frame duration is not available.
     *
     * @throws IllegalArgumentException if {@code klass} or {@code size} was not supported
     * @throws NullPointerException if {@code size} or {@code klass} was {@code null}
     *
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see ImageFormat
     * @see PixelFormat
     */
    public <T> long getOutputMinFrameDuration(final Class<T> klass, final Size size) {
        if (!isOutputSupportedFor(klass)) {
            throw new IllegalArgumentException("klass was not supported");
        }

        return getInternalFormatDuration(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED,
                size, DURATION_MIN_FRAME);
    }

    /**
     * Get the stall duration for the format/size combination (in nanoseconds).
     *
     * <p>{@code format} should be one of the ones returned by {@link #getOutputFormats()}.</p>
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * <p>
     * A stall duration is how much extra time would get added to the normal minimum frame duration
     * for a repeating request that has streams with non-zero stall.
     *
     * <p>For example, consider JPEG captures which have the following characteristics:
     *
     * <ul>
     * <li>JPEG streams act like processed YUV streams in requests for which they are not included;
     * in requests in which they are directly referenced, they act as JPEG streams.
     * This is because supporting a JPEG stream requires the underlying YUV data to always be ready
     * for use by a JPEG encoder, but the encoder will only be used (and impact frame duration) on
     * requests that actually reference a JPEG stream.
     * <li>The JPEG processor can run concurrently to the rest of the camera pipeline, but cannot
     * process more than 1 capture at a time.
     * </ul>
     *
     * <p>In other words, using a repeating YUV request would result in a steady frame rate
     * (let's say it's 30 FPS). If a single JPEG request is submitted periodically,
     * the frame rate will stay at 30 FPS (as long as we wait for the previous JPEG to return each
     * time). If we try to submit a repeating YUV + JPEG request, then the frame rate will drop from
     * 30 FPS.</p>
     *
     * <p>In general, submitting a new request with a non-0 stall time stream will <em>not</em> cause a
     * frame rate drop unless there are still outstanding buffers for that stream from previous
     * requests.</p>
     *
     * <p>Submitting a repeating request with streams (call this {@code S}) is the same as setting
     * the minimum frame duration from the normal minimum frame duration corresponding to {@code S},
     * added with the maximum stall duration for {@code S}.</p>
     *
     * <p>If interleaving requests with and without a stall duration, a request will stall by the
     * maximum of the remaining times for each can-stall stream with outstanding buffers.</p>
     *
     * <p>This means that a stalling request will not have an exposure start until the stall has
     * completed.</p>
     *
     * <p>This should correspond to the stall duration when only that stream is active, with all
     * processing (typically in {@code android.*.mode}) set to {@code FAST} or {@code OFF}.
     * Setting any of the processing modes to {@code HIGH_QUALITY} effectively results in an
     * indeterminate stall duration for all streams in a request (the regular stall calculation
     * rules are ignored).</p>
     *
     * <p>The following formats may always have a stall duration:
     * <ul>
     * <li>{@link ImageFormat#JPEG JPEG}
     * <li>{@link ImageFormat#RAW_SENSOR RAW16}
     * </ul>
     * </p>
     *
     * <p>The following formats will never have a stall duration:
     * <ul>
     * <li>{@link ImageFormat#YUV_420_888 YUV_420_888}
     * <li>{@link #isOutputSupportedFor(Class) Implementation-Defined}
     * </ul></p>
     *
     * <p>
     * All other formats may or may not have an allowed stall duration on a per-capability basis;
     * refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * android.request.availableCapabilities} for more details.</p>
     * </p>
     *
     * <p>See {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}
     * for more information about calculating the max frame rate (absent stalls).</p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @param size an output-compatible size
     * @return a stall duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code format} or {@code size} was not supported
     * @throws NullPointerException if {@code size} was {@code null}
     *
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see ImageFormat
     * @see PixelFormat
     */
    public long getOutputStallDuration(int format, Size size) {
        checkArgumentFormatSupported(format, /*output*/true);

        return getInternalFormatDuration(imageFormatToInternal(format),
                size, DURATION_STALL);
    }

    /**
     * Get the stall duration for the class/size combination (in nanoseconds).
     *
     * <p>This assumes a the {@code klass} is set up to use an implementation-defined format.
     * For user-defined formats, use {@link #getOutputMinFrameDuration(int, Size)}.</p>
     *
     * <p>{@code klass} should be one of the ones with a non-empty array returned by
     * {@link #getOutputSizes(Class)}.</p>
     *
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(Class)}.</p>
     *
     * <p>See {@link #getOutputStallDuration(int, Size)} for a definition of a
     * <em>stall duration</em>.</p>
     *
     * @param klass
     *          a class which is supported by {@link #isOutputSupportedFor(Class)} and has a
     *          non-empty array returned by {@link #getOutputSizes(Class)}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code klass} or {@code size} was not supported
     * @throws NullPointerException if {@code size} or {@code klass} was {@code null}
     *
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see ImageFormat
     * @see PixelFormat
     */
    public <T> long getOutputStallDuration(final Class<T> klass, final Size size) {
        if (!isOutputSupportedFor(klass)) {
            throw new IllegalArgumentException("klass was not supported");
        }

        return getInternalFormatDuration(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED,
                size, DURATION_STALL);
    }

    /**
     * Check if this {@link StreamConfigurationMap} is equal to another
     * {@link StreamConfigurationMap}.
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
        if (obj instanceof StreamConfigurationMap) {
            final StreamConfigurationMap other = (StreamConfigurationMap) obj;
            // XX: do we care about order?
            return Arrays.equals(mConfigurations, other.mConfigurations) &&
                    Arrays.equals(mMinFrameDurations, other.mMinFrameDurations) &&
                    Arrays.equals(mStallDurations, other.mStallDurations) &&
                    Arrays.equals(mHighSpeedVideoConfigurations,
                            other.mHighSpeedVideoConfigurations);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // XX: do we care about order?
        return HashCodeHelpers.hashCode(
                mConfigurations, mMinFrameDurations,
                mStallDurations, mHighSpeedVideoConfigurations);
    }

    // Check that the argument is supported by #getOutputFormats or #getInputFormats
    private int checkArgumentFormatSupported(int format, boolean output) {
        checkArgumentFormat(format);

        int[] formats = output ? getOutputFormats() : getInputFormats();
        for (int i = 0; i < formats.length; ++i) {
            if (format == formats[i]) {
                return format;
            }
        }

        throw new IllegalArgumentException(String.format(
                "format %x is not supported by this stream configuration map", format));
    }

    /**
     * Ensures that the format is either user-defined or implementation defined.
     *
     * <p>If a format has a different internal representation than the public representation,
     * passing in the public representation here will fail.</p>
     *
     * <p>For example if trying to use {@link ImageFormat#JPEG}:
     * it has a different public representation than the internal representation
     * {@code HAL_PIXEL_FORMAT_BLOB}, this check will fail.</p>
     *
     * <p>Any invalid/undefined formats will raise an exception.</p>
     *
     * @param format image format
     * @return the format
     *
     * @throws IllegalArgumentException if the format was invalid
     */
    static int checkArgumentFormatInternal(int format) {
        switch (format) {
            case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
            case HAL_PIXEL_FORMAT_BLOB:
            case HAL_PIXEL_FORMAT_RAW_OPAQUE:
                return format;
            case ImageFormat.JPEG:
                throw new IllegalArgumentException(
                        "ImageFormat.JPEG is an unknown internal format");
            default:
                return checkArgumentFormat(format);
        }
    }

    /**
     * Ensures that the format is publicly user-defined in either ImageFormat or PixelFormat.
     *
     * <p>If a format has a different public representation than the internal representation,
     * passing in the internal representation here will fail.</p>
     *
     * <p>For example if trying to use {@code HAL_PIXEL_FORMAT_BLOB}:
     * it has a different internal representation than the public representation
     * {@link ImageFormat#JPEG}, this check will fail.</p>
     *
     * <p>Any invalid/undefined formats will raise an exception, including implementation-defined.
     * </p>
     *
     * <p>Note that {@code @hide} and deprecated formats will not pass this check.</p>
     *
     * @param format image format
     * @return the format
     *
     * @throws IllegalArgumentException if the format was not user-defined
     */
    static int checkArgumentFormat(int format) {
        if (!ImageFormat.isPublicFormat(format) && !PixelFormat.isPublicFormat(format)) {
            throw new IllegalArgumentException(String.format(
                    "format 0x%x was not defined in either ImageFormat or PixelFormat", format));
        }

        return format;
    }

    /**
     * Convert a public-visible {@code ImageFormat} into an internal format
     * compatible with {@code graphics.h}.
     *
     * <p>In particular these formats are converted:
     * <ul>
     * <li>HAL_PIXEL_FORMAT_BLOB => ImageFormat.JPEG
     * </ul>
     * </p>
     *
     * <p>Passing in an implementation-defined format which has no public equivalent will fail;
     * as will passing in a public format which has a different internal format equivalent.
     * See {@link #checkArgumentFormat} for more details about a legal public format.</p>
     *
     * <p>All other formats are returned as-is, no further invalid check is performed.</p>
     *
     * <p>This function is the dual of {@link #imageFormatToInternal}.</p>
     *
     * @param format image format from {@link ImageFormat} or {@link PixelFormat}
     * @return the converted image formats
     *
     * @throws IllegalArgumentException
     *          if {@code format} is {@code HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED} or
     *          {@link ImageFormat#JPEG}
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see #checkArgumentFormat
     */
    static int imageFormatToPublic(int format) {
        switch (format) {
            case HAL_PIXEL_FORMAT_BLOB:
                return ImageFormat.JPEG;
            case ImageFormat.JPEG:
                throw new IllegalArgumentException(
                        "ImageFormat.JPEG is an unknown internal format");
            case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
                throw new IllegalArgumentException(
                        "IMPLEMENTATION_DEFINED must not leak to public API");
            default:
                return format;
        }
    }

    /**
     * Convert image formats from internal to public formats (in-place).
     *
     * @param formats an array of image formats
     * @return {@code formats}
     *
     * @see #imageFormatToPublic
     */
    static int[] imageFormatToPublic(int[] formats) {
        if (formats == null) {
            return null;
        }

        for (int i = 0; i < formats.length; ++i) {
            formats[i] = imageFormatToPublic(formats[i]);
        }

        return formats;
    }

    /**
     * Convert a public format compatible with {@code ImageFormat} to an internal format
     * from {@code graphics.h}.
     *
     * <p>In particular these formats are converted:
     * <ul>
     * <li>ImageFormat.JPEG => HAL_PIXEL_FORMAT_BLOB
     * </ul>
     * </p>
     *
     * <p>Passing in an implementation-defined format here will fail (it's not a public format);
     * as will passing in an internal format which has a different public format equivalent.
     * See {@link #checkArgumentFormat} for more details about a legal public format.</p>
     *
     * <p>All other formats are returned as-is, no invalid check is performed.</p>
     *
     * <p>This function is the dual of {@link #imageFormatToPublic}.</p>
     *
     * @param format public image format from {@link ImageFormat} or {@link PixelFormat}
     * @return the converted image formats
     *
     * @see ImageFormat
     * @see PixelFormat
     *
     * @throws IllegalArgumentException
     *              if {@code format} was {@code HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED}
     */
    static int imageFormatToInternal(int format) {
        switch (format) {
            case ImageFormat.JPEG:
                return HAL_PIXEL_FORMAT_BLOB;
            case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
                throw new IllegalArgumentException(
                        "IMPLEMENTATION_DEFINED is not allowed via public API");
            default:
                return format;
        }
    }

    /**
     * Convert image formats from public to internal formats (in-place).
     *
     * @param formats an array of image formats
     * @return {@code formats}
     *
     * @see #imageFormatToInternal
     *
     * @hide
     */
    public static int[] imageFormatToInternal(int[] formats) {
        if (formats == null) {
            return null;
        }

        for (int i = 0; i < formats.length; ++i) {
            formats[i] = imageFormatToInternal(formats[i]);
        }

        return formats;
    }

    private Size[] getPublicFormatSizes(int format, boolean output) {
        try {
            checkArgumentFormatSupported(format, output);
        } catch (IllegalArgumentException e) {
            return null;
        }

        format = imageFormatToInternal(format);

        return getInternalFormatSizes(format, output);
    }

    private Size[] getInternalFormatSizes(int format, boolean output) {
        HashMap<Integer, Integer> formatsMap = getFormatsMap(output);

        Integer sizesCount = formatsMap.get(format);
        if (sizesCount == null) {
            throw new IllegalArgumentException("format not available");
        }

        int len = sizesCount;
        Size[] sizes = new Size[len];
        int sizeIndex = 0;

        for (StreamConfiguration config : mConfigurations) {
            if (config.getFormat() == format && config.isOutput() == output) {
                sizes[sizeIndex++] = config.getSize();
            }
        }

        if (sizeIndex != len) {
            throw new AssertionError(
                    "Too few sizes (expected " + len + ", actual " + sizeIndex + ")");
        }

        return sizes;
    }

    /** Get the list of publically visible output formats; does not include IMPL_DEFINED */
    private int[] getPublicFormats(boolean output) {
        int[] formats = new int[getPublicFormatCount(output)];

        int i = 0;

        for (int format : getFormatsMap(output).keySet()) {
            if (format != HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
                formats[i++] = format;
            }
        }

        if (formats.length != i) {
            throw new AssertionError("Too few formats " + i + ", expected " + formats.length);
        }

        return imageFormatToPublic(formats);
    }

    /** Get the format -> size count map for either output or input formats */
    private HashMap<Integer, Integer> getFormatsMap(boolean output) {
        return output ? mOutputFormats : mInputFormats;
    }

    private long getInternalFormatDuration(int format, Size size, int duration) {
        // assume format is already checked, since its internal

        if (!arrayContains(getInternalFormatSizes(format, /*output*/true), size)) {
            throw new IllegalArgumentException("size was not supported");
        }

        StreamConfigurationDuration[] durations = getDurations(duration);

        for (StreamConfigurationDuration configurationDuration : durations) {
            if (configurationDuration.getFormat() == format &&
                    configurationDuration.getWidth() == size.getWidth() &&
                    configurationDuration.getHeight() == size.getHeight()) {
                return configurationDuration.getDuration();
            }
        }
        // Default duration is '0' (unsupported/no extra stall)
        return 0;
    }

    /**
     * Get the durations array for the kind of duration
     *
     * @see #DURATION_MIN_FRAME
     * @see #DURATION_STALL
     * */
    private StreamConfigurationDuration[] getDurations(int duration) {
        switch (duration) {
            case DURATION_MIN_FRAME:
                return mMinFrameDurations;
            case DURATION_STALL:
                return mStallDurations;
            default:
                throw new IllegalArgumentException("duration was invalid");
        }
    }

    /** Count the number of publicly-visible output formats */
    private int getPublicFormatCount(boolean output) {
        HashMap<Integer, Integer> formatsMap = getFormatsMap(output);

        int size = formatsMap.size();
        if (formatsMap.containsKey(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED)) {
            size -= 1;
        }
        return size;
    }

    private static <T> boolean arrayContains(T[] array, T element) {
        if (array == null) {
            return false;
        }

        for (T el : array) {
            if (Objects.equals(el, element)) {
                return true;
            }
        }

        return false;
    }

    // from system/core/include/system/graphics.h
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;
    private static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;
    private static final int HAL_PIXEL_FORMAT_RAW_OPAQUE = 0x24;

    /**
     * @see #getDurations(int)
     * @see #getDurationDefault(int)
     */
    private static final int DURATION_MIN_FRAME = 0;
    private static final int DURATION_STALL = 1;

    private final StreamConfiguration[] mConfigurations;
    private final StreamConfigurationDuration[] mMinFrameDurations;
    private final StreamConfigurationDuration[] mStallDurations;
    private final HighSpeedVideoConfiguration[] mHighSpeedVideoConfigurations;

    /** ImageFormat -> num output sizes mapping */
    private final HashMap</*ImageFormat*/Integer, /*Count*/Integer> mOutputFormats =
            new HashMap<Integer, Integer>();
    /** ImageFormat -> num input sizes mapping */
    private final HashMap</*ImageFormat*/Integer, /*Count*/Integer> mInputFormats =
            new HashMap<Integer, Integer>();
    /** High speed video Size -> FPS range count mapping*/
    private final HashMap</*HighSpeedVideoSize*/Size, /*Count*/Integer> mHighSpeedVideoSizeMap =
            new HashMap<Size, Integer>();
    /** High speed video FPS range -> Size count mapping*/
    private final HashMap</*HighSpeedVideoFpsRange*/Range<Integer>, /*Count*/Integer>
            mHighSpeedVideoFpsRangeMap = new HashMap<Range<Integer>, Integer>();

}
