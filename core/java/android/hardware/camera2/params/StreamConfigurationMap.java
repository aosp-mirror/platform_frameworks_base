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

import static com.android.internal.util.Preconditions.checkArrayElementsNotNull;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.legacy.LegacyCameraDevice;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.hardware.camera2.utils.SurfaceUtils;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

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
     * @param depthConfigurations a non-{@code null} array of depth {@link StreamConfiguration}
     * @param depthMinFrameDurations a non-{@code null} array of depth
     *        {@link StreamConfigurationDuration}
     * @param depthStallDurations a non-{@code null} array of depth
     *        {@link StreamConfigurationDuration}
     * @param dynamicDepthConfigurations a non-{@code null} array of dynamic depth
     *        {@link StreamConfiguration}
     * @param dynamicDepthMinFrameDurations a non-{@code null} array of dynamic depth
     *        {@link StreamConfigurationDuration}
     * @param dynamicDepthStallDurations a non-{@code null} array of dynamic depth
     *        {@link StreamConfigurationDuration}
     * @param heicConfigurations a non-{@code null} array of heic {@link StreamConfiguration}
     * @param heicMinFrameDurations a non-{@code null} array of heic
     *        {@link StreamConfigurationDuration}
     * @param heicStallDurations a non-{@code null} array of heic
     *        {@link StreamConfigurationDuration}
     * @param highSpeedVideoConfigurations an array of {@link HighSpeedVideoConfiguration}, null if
     *        camera device does not support high speed video recording
     * @param listHighResolution a flag indicating whether the device supports BURST_CAPTURE
     *        and thus needs a separate list of slow high-resolution output sizes
     * @throws NullPointerException if any of the arguments except highSpeedVideoConfigurations
     *         were {@code null} or any subelements were {@code null}
     *
     * @hide
     */
    public StreamConfigurationMap(
            StreamConfiguration[] configurations,
            StreamConfigurationDuration[] minFrameDurations,
            StreamConfigurationDuration[] stallDurations,
            StreamConfiguration[] depthConfigurations,
            StreamConfigurationDuration[] depthMinFrameDurations,
            StreamConfigurationDuration[] depthStallDurations,
            StreamConfiguration[] dynamicDepthConfigurations,
            StreamConfigurationDuration[] dynamicDepthMinFrameDurations,
            StreamConfigurationDuration[] dynamicDepthStallDurations,
            StreamConfiguration[] heicConfigurations,
            StreamConfigurationDuration[] heicMinFrameDurations,
            StreamConfigurationDuration[] heicStallDurations,
            HighSpeedVideoConfiguration[] highSpeedVideoConfigurations,
            ReprocessFormatsMap inputOutputFormatsMap,
            boolean listHighResolution) {
        this(configurations, minFrameDurations, stallDurations,
                    depthConfigurations, depthMinFrameDurations, depthStallDurations,
                    dynamicDepthConfigurations, dynamicDepthMinFrameDurations,
                    dynamicDepthStallDurations,
                    heicConfigurations, heicMinFrameDurations, heicStallDurations,
                    highSpeedVideoConfigurations, inputOutputFormatsMap, listHighResolution,
                    /*enforceImplementationDefined*/ true);
    }

    /**
     * Create a new {@link StreamConfigurationMap}.
     *
     * <p>The array parameters ownership is passed to this object after creation; do not
     * write to them after this constructor is invoked.</p>
     *
     * @param configurations a non-{@code null} array of {@link StreamConfiguration}
     * @param minFrameDurations a non-{@code null} array of {@link StreamConfigurationDuration}
     * @param stallDurations a non-{@code null} array of {@link StreamConfigurationDuration}
     * @param depthConfigurations a non-{@code null} array of depth {@link StreamConfiguration}
     * @param depthMinFrameDurations a non-{@code null} array of depth
     *        {@link StreamConfigurationDuration}
     * @param depthStallDurations a non-{@code null} array of depth
     *        {@link StreamConfigurationDuration}
     * @param dynamicDepthConfigurations a non-{@code null} array of dynamic depth
     *        {@link StreamConfiguration}
     * @param dynamicDepthMinFrameDurations a non-{@code null} array of dynamic depth
     *        {@link StreamConfigurationDuration}
     * @param dynamicDepthStallDurations a non-{@code null} array of dynamic depth
     *        {@link StreamConfigurationDuration}
     * @param heicConfigurations a non-{@code null} array of heic {@link StreamConfiguration}
     * @param heicMinFrameDurations a non-{@code null} array of heic
     *        {@link StreamConfigurationDuration}
     * @param heicStallDurations a non-{@code null} array of heic
     *        {@link StreamConfigurationDuration}
     * @param highSpeedVideoConfigurations an array of {@link HighSpeedVideoConfiguration}, null if
     *        camera device does not support high speed video recording
     * @param listHighResolution a flag indicating whether the device supports BURST_CAPTURE
     *        and thus needs a separate list of slow high-resolution output sizes
     * @param enforceImplementationDefined a flag indicating whether
     *        IMPLEMENTATION_DEFINED format configuration must be present
     * @throws NullPointerException if any of the arguments except highSpeedVideoConfigurations
     *         were {@code null} or any subelements were {@code null}
     *
     * @hide
     */
    public StreamConfigurationMap(
            StreamConfiguration[] configurations,
            StreamConfigurationDuration[] minFrameDurations,
            StreamConfigurationDuration[] stallDurations,
            StreamConfiguration[] depthConfigurations,
            StreamConfigurationDuration[] depthMinFrameDurations,
            StreamConfigurationDuration[] depthStallDurations,
            StreamConfiguration[] dynamicDepthConfigurations,
            StreamConfigurationDuration[] dynamicDepthMinFrameDurations,
            StreamConfigurationDuration[] dynamicDepthStallDurations,
            StreamConfiguration[] heicConfigurations,
            StreamConfigurationDuration[] heicMinFrameDurations,
            StreamConfigurationDuration[] heicStallDurations,
            HighSpeedVideoConfiguration[] highSpeedVideoConfigurations,
            ReprocessFormatsMap inputOutputFormatsMap,
            boolean listHighResolution,
            boolean enforceImplementationDefined) {

        if (configurations == null &&
                depthConfigurations == null &&
                heicConfigurations == null) {
            throw new NullPointerException("At least one of color/depth/heic configurations " +
                    "must not be null");
        }

        if (configurations == null) {
            // If no color configurations exist, ensure depth ones do
            mConfigurations = new StreamConfiguration[0];
            mMinFrameDurations = new StreamConfigurationDuration[0];
            mStallDurations = new StreamConfigurationDuration[0];
        } else {
            mConfigurations = checkArrayElementsNotNull(configurations, "configurations");
            mMinFrameDurations = checkArrayElementsNotNull(minFrameDurations, "minFrameDurations");
            mStallDurations = checkArrayElementsNotNull(stallDurations, "stallDurations");
        }

        mListHighResolution = listHighResolution;

        if (depthConfigurations == null) {
            mDepthConfigurations = new StreamConfiguration[0];
            mDepthMinFrameDurations = new StreamConfigurationDuration[0];
            mDepthStallDurations = new StreamConfigurationDuration[0];
        } else {
            mDepthConfigurations = checkArrayElementsNotNull(depthConfigurations,
                    "depthConfigurations");
            mDepthMinFrameDurations = checkArrayElementsNotNull(depthMinFrameDurations,
                    "depthMinFrameDurations");
            mDepthStallDurations = checkArrayElementsNotNull(depthStallDurations,
                    "depthStallDurations");
        }

        if (dynamicDepthConfigurations == null) {
            mDynamicDepthConfigurations = new StreamConfiguration[0];
            mDynamicDepthMinFrameDurations = new StreamConfigurationDuration[0];
            mDynamicDepthStallDurations = new StreamConfigurationDuration[0];
        } else {
            mDynamicDepthConfigurations = checkArrayElementsNotNull(dynamicDepthConfigurations,
                    "dynamicDepthConfigurations");
            mDynamicDepthMinFrameDurations = checkArrayElementsNotNull(
                    dynamicDepthMinFrameDurations, "dynamicDepthMinFrameDurations");
            mDynamicDepthStallDurations = checkArrayElementsNotNull(dynamicDepthStallDurations,
                    "dynamicDepthStallDurations");
        }

        if (heicConfigurations == null) {
            mHeicConfigurations = new StreamConfiguration[0];
            mHeicMinFrameDurations = new StreamConfigurationDuration[0];
            mHeicStallDurations = new StreamConfigurationDuration[0];
        } else {
            mHeicConfigurations = checkArrayElementsNotNull(heicConfigurations,
                    "heicConfigurations");
            mHeicMinFrameDurations = checkArrayElementsNotNull(heicMinFrameDurations,
                    "heicMinFrameDurations");
            mHeicStallDurations = checkArrayElementsNotNull(heicStallDurations,
                    "heicStallDurations");
        }

        if (highSpeedVideoConfigurations == null) {
            mHighSpeedVideoConfigurations = new HighSpeedVideoConfiguration[0];
        } else {
            mHighSpeedVideoConfigurations = checkArrayElementsNotNull(
                    highSpeedVideoConfigurations, "highSpeedVideoConfigurations");
        }

        // For each format, track how many sizes there are available to configure
        for (StreamConfiguration config : mConfigurations) {
            int fmt = config.getFormat();
            SparseIntArray map = null;
            if (config.isOutput()) {
                mAllOutputFormats.put(fmt, mAllOutputFormats.get(fmt) + 1);
                long duration = 0;
                if (mListHighResolution) {
                    for (StreamConfigurationDuration configurationDuration : mMinFrameDurations) {
                        if (configurationDuration.getFormat() == fmt &&
                                configurationDuration.getWidth() == config.getSize().getWidth() &&
                                configurationDuration.getHeight() == config.getSize().getHeight()) {
                            duration = configurationDuration.getDuration();
                            break;
                        }
                    }
                }
                map = duration <= DURATION_20FPS_NS ?
                        mOutputFormats : mHighResOutputFormats;
            } else {
                map = mInputFormats;
            }
            map.put(fmt, map.get(fmt) + 1);
        }

        // For each depth format, track how many sizes there are available to configure
        for (StreamConfiguration config : mDepthConfigurations) {
            if (!config.isOutput()) {
                // Ignoring input depth configs
                continue;
            }

            mDepthOutputFormats.put(config.getFormat(),
                    mDepthOutputFormats.get(config.getFormat()) + 1);
        }
        for (StreamConfiguration config : mDynamicDepthConfigurations) {
            if (!config.isOutput()) {
                // Ignoring input configs
                continue;
            }

            mDynamicDepthOutputFormats.put(config.getFormat(),
                    mDynamicDepthOutputFormats.get(config.getFormat()) + 1);
        }

        // For each heic format, track how many sizes there are available to configure
        for (StreamConfiguration config : mHeicConfigurations) {
            if (!config.isOutput()) {
                // Ignoring input depth configs
                continue;
            }

            mHeicOutputFormats.put(config.getFormat(),
                    mHeicOutputFormats.get(config.getFormat()) + 1);
        }

        if (configurations != null && enforceImplementationDefined &&
                mOutputFormats.indexOfKey(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) < 0) {
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

        mInputOutputFormatsMap = inputOutputFormatsMap;
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
    public int[] getOutputFormats() {
        return getPublicFormats(/*output*/true);
    }

    /**
     * Get the image {@code format} output formats for a reprocessing input format.
     *
     * <p>When submitting a {@link CaptureRequest} with an input Surface of a given format,
     * the only allowed target outputs of the {@link CaptureRequest} are the ones with a format
     * listed in the return value of this method. Including any other output Surface as a target
     * will throw an IllegalArgumentException. If no output format is supported given the input
     * format, an empty int[] will be returned.</p>
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
    public int[] getValidOutputFormatsForInput(int inputFormat) {
        if (mInputOutputFormatsMap == null) {
            return new int[0];
        }

        int[] outputs = mInputOutputFormatsMap.getOutputs(inputFormat);
        if (mHeicOutputFormats.size() > 0) {
            // All reprocessing formats map contain JPEG.
            int[] outputsWithHeic = Arrays.copyOf(outputs, outputs.length+1);
            outputsWithHeic[outputs.length] = ImageFormat.HEIC;
            return outputsWithHeic;
        } else {
            return outputs;
        }
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
     */
    public int[] getInputFormats() {
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
     */
    public Size[] getInputSizes(final int format) {
        return getPublicFormatSizes(format, /*output*/false, /*highRes*/false);
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

        int internalFormat = imageFormatToInternal(format);
        int dataspace = imageFormatToDataspace(format);
        if (dataspace == HAL_DATASPACE_DEPTH) {
            return mDepthOutputFormats.indexOfKey(internalFormat) >= 0;
        } else if (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH) {
            return mDynamicDepthOutputFormats.indexOfKey(internalFormat) >= 0;
        } else if (dataspace == HAL_DATASPACE_HEIF) {
            return mHeicOutputFormats.indexOfKey(internalFormat) >= 0;
        } else {
            return getFormatsMap(/*output*/true).indexOfKey(internalFormat) >= 0;
        }
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
        Objects.requireNonNull(klass, "klass must not be null");

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
     * <p>Surfaces from flexible sources will return true even if the exact size of the Surface does
     * not match a camera-supported size, as long as the format (or class) is supported and the
     * camera device supports a size that is equal to or less than 1080p in that format. If such as
     * Surface is used to create a capture session, it will have its size rounded to the nearest
     * supported size, below or equal to 1080p. Flexible sources include SurfaceView, SurfaceTexture,
     * and ImageReader.</p>
     *
     * <p>This is not an exhaustive list; see the particular class's documentation for further
     * possible reasons of incompatibility.</p>
     *
     * @param surface a non-{@code null} {@link Surface} object reference
     * @return {@code true} if this is supported, {@code false} otherwise
     *
     * @throws NullPointerException if {@code surface} was {@code null}
     * @throws IllegalArgumentException if the Surface endpoint is no longer valid
     *
     * @see CameraDevice#createCaptureSession
     * @see #isOutputSupportedFor(Class)
     */
    public boolean isOutputSupportedFor(Surface surface) {
        Objects.requireNonNull(surface, "surface must not be null");

        Size surfaceSize = SurfaceUtils.getSurfaceSize(surface);
        int surfaceFormat = SurfaceUtils.getSurfaceFormat(surface);
        int surfaceDataspace = SurfaceUtils.getSurfaceDataspace(surface);

        // See if consumer is flexible.
        boolean isFlexible = SurfaceUtils.isFlexibleConsumer(surface);

        StreamConfiguration[] configs =
                surfaceDataspace == HAL_DATASPACE_DEPTH ? mDepthConfigurations :
                surfaceDataspace == HAL_DATASPACE_DYNAMIC_DEPTH ? mDynamicDepthConfigurations :
                surfaceDataspace == HAL_DATASPACE_HEIF ? mHeicConfigurations :
                mConfigurations;
        for (StreamConfiguration config : configs) {
            if (config.getFormat() == surfaceFormat && config.isOutput()) {
                // Matching format, either need exact size match, or a flexible consumer
                // and a size no bigger than MAX_DIMEN_FOR_ROUNDING
                if (config.getSize().equals(surfaceSize)) {
                    return true;
                } else if (isFlexible &&
                        (config.getSize().getWidth() <= LegacyCameraDevice.MAX_DIMEN_FOR_ROUNDING)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determine whether or not the particular stream configuration is suitable to be included
     * in a {@link CameraDevice#createCaptureSession capture session} as an output.
     *
     * @param size stream configuration size
     * @param format stream configuration format
     * @return {@code true} if this is supported, {@code false} otherwise
     *
     * @see CameraDevice#createCaptureSession
     * @see #isOutputSupportedFor(Class)
     * @hide
     */
    public boolean isOutputSupportedFor(Size size, int format) {
        int internalFormat = imageFormatToInternal(format);
        int dataspace = imageFormatToDataspace(format);

        StreamConfiguration[] configs =
                dataspace == HAL_DATASPACE_DEPTH ? mDepthConfigurations :
                dataspace == HAL_DATASPACE_DYNAMIC_DEPTH ? mDynamicDepthConfigurations :
                dataspace == HAL_DATASPACE_HEIF ? mHeicConfigurations :
                mConfigurations;
        for (StreamConfiguration config : configs) {
            if ((config.getFormat() == internalFormat) && config.isOutput() &&
                    config.getSize().equals(size)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get a list of sizes compatible with {@code klass} to use as an output.
     *
     * <p>Some of the supported classes may support additional formats beyond
     * {@link ImageFormat#PRIVATE}; this function only returns
     * sizes for {@link ImageFormat#PRIVATE}. For example, {@link android.media.ImageReader}
     * supports {@link ImageFormat#YUV_420_888} and {@link ImageFormat#PRIVATE}, this method will
     * only return the sizes for {@link ImageFormat#PRIVATE} for {@link android.media.ImageReader}
     * class.</p>
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
     *          an array of supported sizes for {@link ImageFormat#PRIVATE} format,
     *          or {@code null} iff the {@code klass} is not a supported output.
     *
     *
     * @throws NullPointerException if {@code klass} was {@code null}
     *
     * @see #isOutputSupportedFor(Class)
     */
    public <T> Size[] getOutputSizes(Class<T> klass) {
        if (isOutputSupportedFor(klass) == false) {
            return null;
        }

        return getInternalFormatSizes(HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED,
                HAL_DATASPACE_UNKNOWN,/*output*/true, /*highRes*/false);
    }

    /**
     * Get a list of sizes compatible with the requested image {@code format}.
     *
     * <p>The {@code format} should be a supported format (one of the formats returned by
     * {@link #getOutputFormats}).</p>
     *
     * As of API level 23, the {@link #getHighResolutionOutputSizes} method can be used on devices
     * that support the
     * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE BURST_CAPTURE}
     * capability to get a list of high-resolution output sizes that cannot operate at the preferred
     * 20fps rate. This means that for some supported formats, this method will return an empty
     * list, if all the supported resolutions operate at below 20fps.  For devices that do not
     * support the BURST_CAPTURE capability, all output resolutions are listed through this method.
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
        return getPublicFormatSizes(format, /*output*/true, /*highRes*/ false);
    }

    /**
     * Get a list of supported high speed video recording sizes.
     * <p>
     * When {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO} is
     * supported in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES}, this method will
     * list the supported high speed video size configurations. All the sizes listed will be a
     * subset of the sizes reported by {@link #getOutputSizes} for processed non-stalling formats
     * (typically {@link ImageFormat#PRIVATE} {@link ImageFormat#YUV_420_888}, etc.)
     * </p>
     * <p>
     * To enable high speed video recording, application must create a constrained create high speed
     * capture session via {@link CameraDevice#createConstrainedHighSpeedCaptureSession}, and submit
     * a CaptureRequest list created by
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}
     * to this session. The application must select the video size from this method and
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS range} from
     * {@link #getHighSpeedVideoFpsRangesFor} to configure the constrained high speed session and
     * generate the high speed request list. For example, if the application intends to do high
     * speed recording, it can select the maximum size reported by this method to create high speed
     * capture session. Note that for the use case of multiple output streams, application must
     * select one unique size from this method to use (e.g., preview and recording streams must have
     * the same size). Otherwise, the high speed session creation will fail. Once the size is
     * selected, application can get the supported FPS ranges by
     * {@link #getHighSpeedVideoFpsRangesFor}, and use these FPS ranges to setup the recording
     * request lists via
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}.
     * </p>
     *
     * @return an array of supported high speed video recording sizes
     * @see #getHighSpeedVideoFpsRangesFor(Size)
     * @see CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
     * @see CameraDevice#createConstrainedHighSpeedCaptureSession
     * @see android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList
     */
    public Size[] getHighSpeedVideoSizes() {
        Set<Size> keySet = mHighSpeedVideoSizeMap.keySet();
        return keySet.toArray(new Size[keySet.size()]);
    }

    /**
     * Get the frame per second ranges (fpsMin, fpsMax) for input high speed video size.
     * <p>
     * See {@link #getHighSpeedVideoFpsRanges} for how to enable high speed recording.
     * </p>
     * <p>
     * The {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS ranges} reported in this method
     * must not be used to setup capture requests that are submitted to unconstrained capture
     * sessions, or it will result in {@link IllegalArgumentException IllegalArgumentExceptions}.
     * </p>
     * <p>
     * See {@link #getHighSpeedVideoFpsRanges} for the characteristics of the returned FPS ranges.
     * </p>
     *
     * @param size one of the sizes returned by {@link #getHighSpeedVideoSizes()}
     * @return an array of supported high speed video recording FPS ranges The upper bound of
     *         returned ranges is guaranteed to be greater than or equal to 120.
     * @throws IllegalArgumentException if input size does not exist in the return value of
     *             getHighSpeedVideoSizes
     * @see #getHighSpeedVideoSizes()
     * @see #getHighSpeedVideoFpsRanges()
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
     * <p>
     * When {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO} is
     * supported in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES}, this method will
     * list the supported high speed video FPS range configurations. Application can then use
     * {@link #getHighSpeedVideoSizesFor} to query available sizes for one of returned FPS range.
     * </p>
     * <p>
     * To enable high speed video recording, application must create a constrained create high speed
     * capture session via {@link CameraDevice#createConstrainedHighSpeedCaptureSession}, and submit
     * a CaptureRequest list created by
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}
     * to this session. The application must select the video size from this method and
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS range} from
     * {@link #getHighSpeedVideoFpsRangesFor} to configure the constrained high speed session and
     * generate the high speed request list. For example, if the application intends to do high
     * speed recording, it can select one FPS range reported by this method, query the video sizes
     * corresponding to this FPS range by {@link #getHighSpeedVideoSizesFor} and use one of reported
     * sizes to create a high speed capture session. Note that for the use case of multiple output
     * streams, application must select one unique size from this method to use (e.g., preview and
     * recording streams must have the same size). Otherwise, the high speed session creation will
     * fail. Once the high speed capture session is created, the application can set the FPS range
     * in the recording request lists via
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList}.
     * </p>
     * <p>
     * The FPS ranges reported by this method will have below characteristics:
     * <li>The fpsMin and fpsMax will be a multiple 30fps.</li>
     * <li>The fpsMin will be no less than 30fps, the fpsMax will be no less than 120fps.</li>
     * <li>At least one range will be a fixed FPS range where fpsMin == fpsMax.</li>
     * <li>For each fixed FPS range, there will be one corresponding variable FPS range [30,
     * fps_max]. These kinds of FPS ranges are suitable for preview-only use cases where the
     * application doesn't want the camera device always produce higher frame rate than the display
     * refresh rate.</li>
     * </p>
     *
     * @return an array of supported high speed video recording FPS ranges The upper bound of
     *         returned ranges is guaranteed to be larger or equal to 120.
     * @see #getHighSpeedVideoSizesFor
     * @see CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
     * @see CameraDevice#createConstrainedHighSpeedCaptureSession
     * @see android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList
     */
    @SuppressWarnings("unchecked")
    public Range<Integer>[] getHighSpeedVideoFpsRanges() {
        Set<Range<Integer>> keySet = mHighSpeedVideoFpsRangeMap.keySet();
        return keySet.toArray(new Range[keySet.size()]);
    }

    /**
     * Get the supported video sizes for an input high speed FPS range.
     *
     * <p> See {@link #getHighSpeedVideoSizes} for how to enable high speed recording.</p>
     *
     * @param fpsRange one of the FPS range returned by {@link #getHighSpeedVideoFpsRanges()}
     * @return An array of video sizes to create high speed capture sessions for high speed streaming
     *         use cases.
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
     * Get a list of supported high resolution sizes, which cannot operate at full BURST_CAPTURE
     * rate.
     *
     * <p>This includes all output sizes that cannot meet the 20 fps frame rate requirements for the
     * {@link android.hardware.camera2.CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE BURST_CAPTURE}
     * capability.  This does not include the stall duration, so for example, a JPEG or RAW16 output
     * resolution with a large stall duration but a minimum frame duration that's above 20 fps will
     * still be listed in the regular {@link #getOutputSizes} list. All the sizes on this list that
     * are less than 24 megapixels are still guaranteed to operate at a rate of at least 10 fps,
     * not including stall duration. Sizes on this list that are at least 24 megapixels are allowed
     * to operate at less than 10 fps.</p>
     *
     * <p>For a device that does not support the BURST_CAPTURE capability, this list will be
     * {@code null}, since resolutions in the {@link #getOutputSizes} list are already not
     * guaranteed to meet &gt;= 20 fps rate requirements. For a device that does support the
     * BURST_CAPTURE capability, this list may be empty, if all supported resolutions meet the 20
     * fps requirement.</p>
     *
     * @return an array of supported slower high-resolution sizes, or {@code null} if the
     *         BURST_CAPTURE capability is not supported
     */
    public Size[] getHighResolutionOutputSizes(int format) {
        if (!mListHighResolution) return null;

        return getPublicFormatSizes(format, /*output*/true, /*highRes*/ true);
    }

    /**
     * Get the minimum {@link CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the format/size combination (in nanoseconds).
     *
     * <p>{@code format} should be one of the ones returned by {@link #getOutputFormats()}.</p>
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * <p>This corresponds to the minimum frame duration (maximum frame rate) possible when only
     * that stream is configured in a session, with all processing (typically in
     * {@code android.*.mode}) set to either {@code OFF} or {@code FAST}.  </p>
     *
     * <p>When multiple streams are used in a session, the minimum frame duration will be
     * {@code max(individual stream min durations)}.  See {@link #getOutputStallDuration} for
     * details of timing for formats that may cause frame rate slowdown when they are targeted by a
     * capture request.</p>
     *
     * <p>For devices that do not support manual sensor control
     * ({@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR}),
     * this function may return 0.</p>
     *
     * <p>The minimum frame duration of a stream (of a particular format, size) is the same
     * regardless of whether the stream is input or output.</p>
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
        Objects.requireNonNull(size, "size must not be null");
        checkArgumentFormatSupported(format, /*output*/true);

        return getInternalFormatDuration(imageFormatToInternal(format),
                imageFormatToDataspace(format),
                size,
                DURATION_MIN_FRAME);
    }

    /**
     * Get the minimum {@link CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the class/size combination (in nanoseconds).
     *
     * <p>This assumes that the {@code klass} is set up to use {@link ImageFormat#PRIVATE}.
     * For user-defined formats, use {@link #getOutputMinFrameDuration(int, Size)}.</p>
     *
     * <p>{@code klass} should be one of the ones which is supported by
     * {@link #isOutputSupportedFor(Class)}.</p>
     *
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * <p>This corresponds to the minimum frame duration (maximum frame rate) possible when only
     * that stream is configured in a session, with all processing (typically in
     * {@code android.*.mode}) set to either {@code OFF} or {@code FAST}.  </p>
     *
     * <p>When multiple streams are used in a session, the minimum frame duration will be
     * {@code max(individual stream min durations)}.  See {@link #getOutputStallDuration} for
     * details of timing for formats that may cause frame rate slowdown when they are targeted by a
     * capture request.</p>
     *
     * <p>For devices that do not support manual sensor control
     * ({@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR}),
     * this function may return 0.</p>
     *
     * <p>The minimum frame duration of a stream (of a particular format, size) is the same
     * regardless of whether the stream is input or output.</p>
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
                HAL_DATASPACE_UNKNOWN,
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
     * <li>{@link ImageFormat#RAW_PRIVATE RAW_PRIVATE}
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
                imageFormatToDataspace(format),
                size,
                DURATION_STALL);
    }

    /**
     * Get the stall duration for the class/size combination (in nanoseconds).
     *
     * <p>This assumes that the {@code klass} is set up to use {@link ImageFormat#PRIVATE}.
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
                HAL_DATASPACE_UNKNOWN, size, DURATION_STALL);
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
                    Arrays.equals(mDepthConfigurations, other.mDepthConfigurations) &&
                    Arrays.equals(mDepthMinFrameDurations, other.mDepthMinFrameDurations) &&
                    Arrays.equals(mDepthStallDurations, other.mDepthStallDurations) &&
                    Arrays.equals(mDynamicDepthConfigurations, other.mDynamicDepthConfigurations) &&
                    Arrays.equals(mDynamicDepthMinFrameDurations,
                            other.mDynamicDepthMinFrameDurations) &&
                    Arrays.equals(mDynamicDepthStallDurations, other.mDynamicDepthStallDurations) &&
                    Arrays.equals(mHeicConfigurations, other.mHeicConfigurations) &&
                    Arrays.equals(mHeicMinFrameDurations, other.mHeicMinFrameDurations) &&
                    Arrays.equals(mHeicStallDurations, other.mHeicStallDurations) &&
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
        return HashCodeHelpers.hashCodeGeneric(
                mConfigurations, mMinFrameDurations, mStallDurations,
                mDepthConfigurations, mDepthMinFrameDurations, mDepthStallDurations,
                mDynamicDepthConfigurations, mDynamicDepthMinFrameDurations,
                mDynamicDepthStallDurations, mHeicConfigurations,
                mHeicMinFrameDurations, mHeicStallDurations,
                mHighSpeedVideoConfigurations);
    }

    // Check that the argument is supported by #getOutputFormats or #getInputFormats
    private int checkArgumentFormatSupported(int format, boolean output) {
        checkArgumentFormat(format);

        int internalFormat = imageFormatToInternal(format);
        int internalDataspace = imageFormatToDataspace(format);

        if (output) {
            if (internalDataspace == HAL_DATASPACE_DEPTH) {
                if (mDepthOutputFormats.indexOfKey(internalFormat) >= 0) {
                    return format;
                }
            } else if (internalDataspace == HAL_DATASPACE_DYNAMIC_DEPTH) {
                if (mDynamicDepthOutputFormats.indexOfKey(internalFormat) >= 0) {
                    return format;
                }
            } else if (internalDataspace == HAL_DATASPACE_HEIF) {
                if (mHeicOutputFormats.indexOfKey(internalFormat) >= 0) {
                    return format;
                }
            } else {
                if (mAllOutputFormats.indexOfKey(internalFormat) >= 0) {
                    return format;
                }
            }
        } else {
            if (mInputFormats.indexOfKey(internalFormat) >= 0) {
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
            case HAL_PIXEL_FORMAT_Y16:
                return format;
            case ImageFormat.JPEG:
            case ImageFormat.HEIC:
                throw new IllegalArgumentException(
                        "An unknown internal format: " + format);
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
     * Convert an internal format compatible with {@code graphics.h} into public-visible
     * {@code ImageFormat}. This assumes the dataspace of the format is not HAL_DATASPACE_DEPTH.
     *
     * <p>In particular these formats are converted:
     * <ul>
     * <li>HAL_PIXEL_FORMAT_BLOB => ImageFormat.JPEG</li>
     * </ul>
     * </p>
     *
     * <p>Passing in a format which has no public equivalent will fail;
     * as will passing in a public format which has a different internal format equivalent.
     * See {@link #checkArgumentFormat} for more details about a legal public format.</p>
     *
     * <p>All other formats are returned as-is, no further invalid check is performed.</p>
     *
     * <p>This function is the dual of {@link #imageFormatToInternal} for dataspaces other than
     * HAL_DATASPACE_DEPTH.</p>
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
     * @hide
     */
    public static int imageFormatToPublic(int format) {
        switch (format) {
            case HAL_PIXEL_FORMAT_BLOB:
                return ImageFormat.JPEG;
            case ImageFormat.JPEG:
                throw new IllegalArgumentException(
                        "ImageFormat.JPEG is an unknown internal format");
            default:
                return format;
        }
    }

    /**
     * Convert an internal format compatible with {@code graphics.h} into public-visible
     * {@code ImageFormat}. This assumes the dataspace of the format is HAL_DATASPACE_DEPTH.
     *
     * <p>In particular these formats are converted:
     * <ul>
     * <li>HAL_PIXEL_FORMAT_BLOB => ImageFormat.DEPTH_POINT_CLOUD
     * <li>HAL_PIXEL_FORMAT_Y16 => ImageFormat.DEPTH16
     * </ul>
     * </p>
     *
     * <p>Passing in an implementation-defined format which has no public equivalent will fail;
     * as will passing in a public format which has a different internal format equivalent.
     * See {@link #checkArgumentFormat} for more details about a legal public format.</p>
     *
     * <p>All other formats are returned as-is, no further invalid check is performed.</p>
     *
     * <p>This function is the dual of {@link #imageFormatToInternal} for formats associated with
     * HAL_DATASPACE_DEPTH.</p>
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
     * @hide
     */
    public static int depthFormatToPublic(int format) {
        switch (format) {
            case HAL_PIXEL_FORMAT_BLOB:
                return ImageFormat.DEPTH_POINT_CLOUD;
            case HAL_PIXEL_FORMAT_Y16:
                return ImageFormat.DEPTH16;
            case HAL_PIXEL_FORMAT_RAW16:
                return ImageFormat.RAW_DEPTH;
            case ImageFormat.JPEG:
                throw new IllegalArgumentException(
                        "ImageFormat.JPEG is an unknown internal format");
            case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
                throw new IllegalArgumentException(
                        "IMPLEMENTATION_DEFINED must not leak to public API");
            default:
                throw new IllegalArgumentException(
                        "Unknown DATASPACE_DEPTH format " + format);
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
     * <li>ImageFormat.DEPTH_POINT_CLOUD => HAL_PIXEL_FORMAT_BLOB
     * <li>ImageFormat.DEPTH_JPEG => HAL_PIXEL_FORMAT_BLOB
     * <li>ImageFormat.HEIC => HAL_PIXEL_FORMAT_BLOB
     * <li>ImageFormat.DEPTH16 => HAL_PIXEL_FORMAT_Y16
     * </ul>
     * </p>
     *
     * <p>Passing in an internal format which has a different public format equivalent will fail.
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
            case ImageFormat.DEPTH_POINT_CLOUD:
            case ImageFormat.DEPTH_JPEG:
            case ImageFormat.HEIC:
                return HAL_PIXEL_FORMAT_BLOB;
            case ImageFormat.DEPTH16:
                return HAL_PIXEL_FORMAT_Y16;
            case ImageFormat.RAW_DEPTH:
                return HAL_PIXEL_FORMAT_RAW16;
            default:
                return format;
        }
    }

    /**
     * Convert a public format compatible with {@code ImageFormat} to an internal dataspace
     * from {@code graphics.h}.
     *
     * <p>In particular these formats are converted:
     * <ul>
     * <li>ImageFormat.JPEG => HAL_DATASPACE_V0_JFIF
     * <li>ImageFormat.DEPTH_POINT_CLOUD => HAL_DATASPACE_DEPTH
     * <li>ImageFormat.DEPTH16 => HAL_DATASPACE_DEPTH
     * <li>ImageFormat.DEPTH_JPEG => HAL_DATASPACE_DYNAMIC_DEPTH
     * <li>ImageFormat.HEIC => HAL_DATASPACE_HEIF
     * <li>others => HAL_DATASPACE_UNKNOWN
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
    static int imageFormatToDataspace(int format) {
        switch (format) {
            case ImageFormat.JPEG:
                return HAL_DATASPACE_V0_JFIF;
            case ImageFormat.DEPTH_POINT_CLOUD:
            case ImageFormat.DEPTH16:
            case ImageFormat.RAW_DEPTH:
                return HAL_DATASPACE_DEPTH;
            case ImageFormat.DEPTH_JPEG:
                return HAL_DATASPACE_DYNAMIC_DEPTH;
            case ImageFormat.HEIC:
                return HAL_DATASPACE_HEIF;
            default:
                return HAL_DATASPACE_UNKNOWN;
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

    private Size[] getPublicFormatSizes(int format, boolean output, boolean highRes) {
        try {
            checkArgumentFormatSupported(format, output);
        } catch (IllegalArgumentException e) {
            return null;
        }

        int internalFormat = imageFormatToInternal(format);
        int dataspace = imageFormatToDataspace(format);

        return getInternalFormatSizes(internalFormat, dataspace, output, highRes);
    }

    private Size[] getInternalFormatSizes(int format, int dataspace,
            boolean output, boolean highRes) {
        // All depth formats are non-high-res.
        if (dataspace == HAL_DATASPACE_DEPTH && highRes) {
            return new Size[0];
        }

        SparseIntArray formatsMap =
                !output ? mInputFormats :
                dataspace == HAL_DATASPACE_DEPTH ? mDepthOutputFormats :
                dataspace == HAL_DATASPACE_DYNAMIC_DEPTH ? mDynamicDepthOutputFormats :
                dataspace == HAL_DATASPACE_HEIF ? mHeicOutputFormats :
                highRes ? mHighResOutputFormats :
                mOutputFormats;

        int sizesCount = formatsMap.get(format);
        if ( ((!output || (dataspace == HAL_DATASPACE_DEPTH ||
                            dataspace == HAL_DATASPACE_DYNAMIC_DEPTH ||
                            dataspace == HAL_DATASPACE_HEIF)) && sizesCount == 0) ||
                (output && (dataspace != HAL_DATASPACE_DEPTH &&
                            dataspace != HAL_DATASPACE_DYNAMIC_DEPTH &&
                            dataspace != HAL_DATASPACE_HEIF) &&
                 mAllOutputFormats.get(format) == 0)) {
            return null;
        }

        Size[] sizes = new Size[sizesCount];
        int sizeIndex = 0;

        StreamConfiguration[] configurations =
                (dataspace == HAL_DATASPACE_DEPTH) ? mDepthConfigurations :
                (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH) ? mDynamicDepthConfigurations :
                (dataspace == HAL_DATASPACE_HEIF) ? mHeicConfigurations :
                mConfigurations;
        StreamConfigurationDuration[] minFrameDurations =
                (dataspace == HAL_DATASPACE_DEPTH) ? mDepthMinFrameDurations :
                (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH) ? mDynamicDepthMinFrameDurations :
                (dataspace == HAL_DATASPACE_HEIF) ? mHeicMinFrameDurations :
                mMinFrameDurations;

        for (StreamConfiguration config : configurations) {
            int fmt = config.getFormat();
            if (fmt == format && config.isOutput() == output) {
                if (output && mListHighResolution) {
                    // Filter slow high-res output formats; include for
                    // highRes, remove for !highRes
                    long duration = 0;
                    for (int i = 0; i < minFrameDurations.length; i++) {
                        StreamConfigurationDuration d = minFrameDurations[i];
                        if (d.getFormat() == fmt &&
                                d.getWidth() == config.getSize().getWidth() &&
                                d.getHeight() == config.getSize().getHeight()) {
                            duration = d.getDuration();
                            break;
                        }
                    }
                    if (dataspace != HAL_DATASPACE_DEPTH &&
                            highRes != (duration > DURATION_20FPS_NS)) {
                        continue;
                    }
                }
                sizes[sizeIndex++] = config.getSize();
            }
        }

        // Dynamic depth streams can have both fast and also high res modes.
        if ((sizeIndex != sizesCount) && (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH ||
                dataspace == HAL_DATASPACE_HEIF)) {

            if (sizeIndex > sizesCount) {
                throw new AssertionError(
                        "Too many dynamic depth sizes (expected " + sizesCount + ", actual " +
                        sizeIndex + ")");
            }

            if (sizeIndex <= 0) {
                sizes = new Size[0];
            } else {
                sizes = Arrays.copyOf(sizes, sizeIndex);
            }
        } else if (sizeIndex != sizesCount) {
            throw new AssertionError(
                    "Too few sizes (expected " + sizesCount + ", actual " + sizeIndex + ")");
        }

        return sizes;
    }

    /** Get the list of publically visible output formats; does not include IMPL_DEFINED */
    private int[] getPublicFormats(boolean output) {
        int[] formats = new int[getPublicFormatCount(output)];

        int i = 0;

        SparseIntArray map = getFormatsMap(output);
        for (int j = 0; j < map.size(); j++) {
            int format = map.keyAt(j);
            formats[i++] = imageFormatToPublic(format);
        }
        if (output) {
            for (int j = 0; j < mDepthOutputFormats.size(); j++) {
                formats[i++] = depthFormatToPublic(mDepthOutputFormats.keyAt(j));
            }
            if (mDynamicDepthOutputFormats.size() > 0) {
                // Only one publicly dynamic depth format is available.
                formats[i++] = ImageFormat.DEPTH_JPEG;
            }
            if (mHeicOutputFormats.size() > 0) {
                formats[i++] = ImageFormat.HEIC;
            }
        }
        if (formats.length != i) {
            throw new AssertionError("Too few formats " + i + ", expected " + formats.length);
        }

        return formats;
    }

    /** Get the format -> size count map for either output or input formats */
    private SparseIntArray getFormatsMap(boolean output) {
        return output ? mAllOutputFormats : mInputFormats;
    }

    private long getInternalFormatDuration(int format, int dataspace, Size size, int duration) {
        // assume format is already checked, since its internal

        if (!isSupportedInternalConfiguration(format, dataspace, size)) {
            throw new IllegalArgumentException("size was not supported");
        }

        StreamConfigurationDuration[] durations = getDurations(duration, dataspace);

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
    private StreamConfigurationDuration[] getDurations(int duration, int dataspace) {
        switch (duration) {
            case DURATION_MIN_FRAME:
                return (dataspace == HAL_DATASPACE_DEPTH) ? mDepthMinFrameDurations :
                        (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH) ?
                        mDynamicDepthMinFrameDurations :
                        (dataspace == HAL_DATASPACE_HEIF) ? mHeicMinFrameDurations :
                        mMinFrameDurations;

            case DURATION_STALL:
                return (dataspace == HAL_DATASPACE_DEPTH) ? mDepthStallDurations :
                        (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH) ? mDynamicDepthStallDurations :
                        (dataspace == HAL_DATASPACE_HEIF) ? mHeicStallDurations :
                        mStallDurations;
            default:
                throw new IllegalArgumentException("duration was invalid");
        }
    }

    /** Count the number of publicly-visible output formats */
    private int getPublicFormatCount(boolean output) {
        SparseIntArray formatsMap = getFormatsMap(output);
        int size = formatsMap.size();
        if (output) {
            size += mDepthOutputFormats.size();
            size += mDynamicDepthOutputFormats.size();
            size += mHeicOutputFormats.size();
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

    private boolean isSupportedInternalConfiguration(int format, int dataspace, Size size) {
        StreamConfiguration[] configurations =
                (dataspace == HAL_DATASPACE_DEPTH) ? mDepthConfigurations :
                (dataspace == HAL_DATASPACE_DYNAMIC_DEPTH) ? mDynamicDepthConfigurations :
                (dataspace == HAL_DATASPACE_HEIF) ? mHeicConfigurations :
                mConfigurations;

        for (int i = 0; i < configurations.length; i++) {
            if (configurations[i].getFormat() == format &&
                    configurations[i].getSize().equals(size)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Return this {@link StreamConfigurationMap} as a string representation.
     *
     * <p>{@code "StreamConfigurationMap(Outputs([w:%d, h:%d, format:%s(%d), min_duration:%d,
     * stall:%d], ... [w:%d, h:%d, format:%s(%d), min_duration:%d, stall:%d]), Inputs([w:%d, h:%d,
     * format:%s(%d)], ... [w:%d, h:%d, format:%s(%d)]), ValidOutputFormatsForInput(
     * [in:%d, out:%d, ... %d], ... [in:%d, out:%d, ... %d]), HighSpeedVideoConfigurations(
     * [w:%d, h:%d, min_fps:%d, max_fps:%d], ... [w:%d, h:%d, min_fps:%d, max_fps:%d]))"}.</p>
     *
     * <p>{@code Outputs([w:%d, h:%d, format:%s(%d), min_duration:%d, stall:%d], ...
     * [w:%d, h:%d, format:%s(%d), min_duration:%d, stall:%d])}, where
     * {@code [w:%d, h:%d, format:%s(%d), min_duration:%d, stall:%d]} represents an output
     * configuration's width, height, format, minimal frame duration in nanoseconds, and stall
     * duration in nanoseconds.</p>
     *
     * <p>{@code Inputs([w:%d, h:%d, format:%s(%d)], ... [w:%d, h:%d, format:%s(%d)])}, where
     * {@code [w:%d, h:%d, format:%s(%d)]} represents an input configuration's width, height, and
     * format.</p>
     *
     * <p>{@code ValidOutputFormatsForInput([in:%s(%d), out:%s(%d), ... %s(%d)],
     * ... [in:%s(%d), out:%s(%d), ... %s(%d)])}, where {@code [in:%s(%d), out:%s(%d), ... %s(%d)]}
     * represents an input fomat and its valid output formats.</p>
     *
     * <p>{@code HighSpeedVideoConfigurations([w:%d, h:%d, min_fps:%d, max_fps:%d],
     * ... [w:%d, h:%d, min_fps:%d, max_fps:%d])}, where
     * {@code [w:%d, h:%d, min_fps:%d, max_fps:%d]} represents a high speed video output
     * configuration's width, height, minimal frame rate, and maximal frame rate.</p>
     *
     * @return string representation of {@link StreamConfigurationMap}
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("StreamConfiguration(");
        appendOutputsString(sb);
        sb.append(", ");
        appendHighResOutputsString(sb);
        sb.append(", ");
        appendInputsString(sb);
        sb.append(", ");
        appendValidOutputFormatsForInputString(sb);
        sb.append(", ");
        appendHighSpeedVideoConfigurationsString(sb);
        sb.append(")");

        return sb.toString();
    }

    private void appendOutputsString(StringBuilder sb) {
        sb.append("Outputs(");
        int[] formats = getOutputFormats();
        for (int format : formats) {
            Size[] sizes = getOutputSizes(format);
            for (Size size : sizes) {
                long minFrameDuration = getOutputMinFrameDuration(format, size);
                long stallDuration = getOutputStallDuration(format, size);
                sb.append(String.format("[w:%d, h:%d, format:%s(%d), min_duration:%d, " +
                        "stall:%d], ", size.getWidth(), size.getHeight(), formatToString(format),
                        format, minFrameDuration, stallDuration));
            }
        }
        // Remove the pending ", "
        if (sb.charAt(sb.length() - 1) == ' ') {
            sb.delete(sb.length() - 2, sb.length());
        }
        sb.append(")");
    }

    private void appendHighResOutputsString(StringBuilder sb) {
        sb.append("HighResolutionOutputs(");
        int[] formats = getOutputFormats();
        for (int format : formats) {
            Size[] sizes = getHighResolutionOutputSizes(format);
            if (sizes == null) continue;
            for (Size size : sizes) {
                long minFrameDuration = getOutputMinFrameDuration(format, size);
                long stallDuration = getOutputStallDuration(format, size);
                sb.append(String.format("[w:%d, h:%d, format:%s(%d), min_duration:%d, " +
                        "stall:%d], ", size.getWidth(), size.getHeight(), formatToString(format),
                        format, minFrameDuration, stallDuration));
            }
        }
        // Remove the pending ", "
        if (sb.charAt(sb.length() - 1) == ' ') {
            sb.delete(sb.length() - 2, sb.length());
        }
        sb.append(")");
    }

    private void appendInputsString(StringBuilder sb) {
        sb.append("Inputs(");
        int[] formats = getInputFormats();
        for (int format : formats) {
            Size[] sizes = getInputSizes(format);
            for (Size size : sizes) {
                sb.append(String.format("[w:%d, h:%d, format:%s(%d)], ", size.getWidth(),
                        size.getHeight(), formatToString(format), format));
            }
        }
        // Remove the pending ", "
        if (sb.charAt(sb.length() - 1) == ' ') {
            sb.delete(sb.length() - 2, sb.length());
        }
        sb.append(")");
    }

    private void appendValidOutputFormatsForInputString(StringBuilder sb) {
        sb.append("ValidOutputFormatsForInput(");
        int[] inputFormats = getInputFormats();
        for (int inputFormat : inputFormats) {
            sb.append(String.format("[in:%s(%d), out:", formatToString(inputFormat), inputFormat));
            int[] outputFormats = getValidOutputFormatsForInput(inputFormat);
            for (int i = 0; i < outputFormats.length; i++) {
                sb.append(String.format("%s(%d)", formatToString(outputFormats[i]),
                        outputFormats[i]));
                if (i < outputFormats.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("], ");
        }
        // Remove the pending ", "
        if (sb.charAt(sb.length() - 1) == ' ') {
            sb.delete(sb.length() - 2, sb.length());
        }
        sb.append(")");
    }

    private void appendHighSpeedVideoConfigurationsString(StringBuilder sb) {
        sb.append("HighSpeedVideoConfigurations(");
        Size[] sizes = getHighSpeedVideoSizes();
        for (Size size : sizes) {
            Range<Integer>[] ranges = getHighSpeedVideoFpsRangesFor(size);
            for (Range<Integer> range : ranges) {
                sb.append(String.format("[w:%d, h:%d, min_fps:%d, max_fps:%d], ", size.getWidth(),
                        size.getHeight(), range.getLower(), range.getUpper()));
            }
        }
        // Remove the pending ", "
        if (sb.charAt(sb.length() - 1) == ' ') {
            sb.delete(sb.length() - 2, sb.length());
        }
        sb.append(")");
    }

    private String formatToString(int format) {
        switch (format) {
            case ImageFormat.YV12:
                return "YV12";
            case ImageFormat.YUV_420_888:
                return "YUV_420_888";
            case ImageFormat.NV21:
                return "NV21";
            case ImageFormat.NV16:
                return "NV16";
            case PixelFormat.RGB_565:
                return "RGB_565";
            case PixelFormat.RGBA_8888:
                return "RGBA_8888";
            case PixelFormat.RGBX_8888:
                return "RGBX_8888";
            case PixelFormat.RGB_888:
                return "RGB_888";
            case ImageFormat.JPEG:
                return "JPEG";
            case ImageFormat.YUY2:
                return "YUY2";
            case ImageFormat.Y8:
                return "Y8";
            case ImageFormat.Y16:
                return "Y16";
            case ImageFormat.RAW_SENSOR:
                return "RAW_SENSOR";
            case ImageFormat.RAW_PRIVATE:
                return "RAW_PRIVATE";
            case ImageFormat.RAW10:
                return "RAW10";
            case ImageFormat.DEPTH16:
                return "DEPTH16";
            case ImageFormat.DEPTH_POINT_CLOUD:
                return "DEPTH_POINT_CLOUD";
            case ImageFormat.DEPTH_JPEG:
                return "DEPTH_JPEG";
            case ImageFormat.RAW_DEPTH:
                return "RAW_DEPTH";
            case ImageFormat.PRIVATE:
                return "PRIVATE";
            case ImageFormat.HEIC:
                return "HEIC";
            default:
                return "UNKNOWN";
        }
    }

    // from system/core/include/system/graphics.h
    private static final int HAL_PIXEL_FORMAT_RAW16 = 0x20;
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;
    private static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;
    private static final int HAL_PIXEL_FORMAT_YCbCr_420_888 = 0x23;
    private static final int HAL_PIXEL_FORMAT_RAW_OPAQUE = 0x24;
    private static final int HAL_PIXEL_FORMAT_RAW10 = 0x25;
    private static final int HAL_PIXEL_FORMAT_RAW12 = 0x26;
    private static final int HAL_PIXEL_FORMAT_Y16 = 0x20363159;


    private static final int HAL_DATASPACE_STANDARD_SHIFT = 16;
    private static final int HAL_DATASPACE_TRANSFER_SHIFT = 22;
    private static final int HAL_DATASPACE_RANGE_SHIFT = 27;

    private static final int HAL_DATASPACE_UNKNOWN = 0x0;
    private static final int HAL_DATASPACE_V0_JFIF =
            (2 << HAL_DATASPACE_STANDARD_SHIFT) |
            (3 << HAL_DATASPACE_TRANSFER_SHIFT) |
            (1 << HAL_DATASPACE_RANGE_SHIFT);

    private static final int HAL_DATASPACE_DEPTH = 0x1000;
    private static final int HAL_DATASPACE_DYNAMIC_DEPTH = 0x1002;
    private static final int HAL_DATASPACE_HEIF = 0x1003;
    private static final long DURATION_20FPS_NS = 50000000L;
    /**
     * @see #getDurations(int, int)
     */
    private static final int DURATION_MIN_FRAME = 0;
    private static final int DURATION_STALL = 1;

    private final StreamConfiguration[] mConfigurations;
    private final StreamConfigurationDuration[] mMinFrameDurations;
    private final StreamConfigurationDuration[] mStallDurations;

    private final StreamConfiguration[] mDepthConfigurations;
    private final StreamConfigurationDuration[] mDepthMinFrameDurations;
    private final StreamConfigurationDuration[] mDepthStallDurations;

    private final StreamConfiguration[] mDynamicDepthConfigurations;
    private final StreamConfigurationDuration[] mDynamicDepthMinFrameDurations;
    private final StreamConfigurationDuration[] mDynamicDepthStallDurations;

    private final StreamConfiguration[] mHeicConfigurations;
    private final StreamConfigurationDuration[] mHeicMinFrameDurations;
    private final StreamConfigurationDuration[] mHeicStallDurations;

    private final HighSpeedVideoConfiguration[] mHighSpeedVideoConfigurations;
    private final ReprocessFormatsMap mInputOutputFormatsMap;

    private final boolean mListHighResolution;

    /** internal format -> num output sizes mapping, not including slow high-res sizes, for
     * non-depth dataspaces */
    private final SparseIntArray mOutputFormats = new SparseIntArray();
    /** internal format -> num output sizes mapping for slow high-res sizes, for non-depth
     * dataspaces */
    private final SparseIntArray mHighResOutputFormats = new SparseIntArray();
    /** internal format -> num output sizes mapping for all non-depth dataspaces */
    private final SparseIntArray mAllOutputFormats = new SparseIntArray();
    /** internal format -> num input sizes mapping, for input reprocessing formats */
    private final SparseIntArray mInputFormats = new SparseIntArray();
    /** internal format -> num depth output sizes mapping, for HAL_DATASPACE_DEPTH */
    private final SparseIntArray mDepthOutputFormats = new SparseIntArray();
    /** internal format -> num dynamic depth output sizes mapping, for HAL_DATASPACE_DYNAMIC_DEPTH */
    private final SparseIntArray mDynamicDepthOutputFormats = new SparseIntArray();
    /** internal format -> num heic output sizes mapping, for HAL_DATASPACE_HEIF */
    private final SparseIntArray mHeicOutputFormats = new SparseIntArray();

    /** High speed video Size -> FPS range count mapping*/
    private final HashMap</*HighSpeedVideoSize*/Size, /*Count*/Integer> mHighSpeedVideoSizeMap =
            new HashMap<Size, Integer>();
    /** High speed video FPS range -> Size count mapping*/
    private final HashMap</*HighSpeedVideoFpsRange*/Range<Integer>, /*Count*/Integer>
            mHighSpeedVideoFpsRangeMap = new HashMap<Range<Integer>, Integer>();

}
