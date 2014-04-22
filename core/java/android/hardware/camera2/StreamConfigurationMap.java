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

package android.hardware.camera2;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.view.Surface;
import android.util.Size;

import java.util.Arrays;

import static com.android.internal.util.Preconditions.*;

/**
 * Immutable class to store the available stream
 * {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS configurations} to be used
 * when configuring streams with {@link CameraDevice#configureOutputs}.
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
 * the {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS} key and the
 * {@link CameraCharacteristics#get} method.</p.
 *
 * <pre>{@code
 * CameraCharacteristics characteristics = ...;
 * StreamConfigurationMap configs = characteristics.get(
 *         CameraCharacteristics.SCALER_AVAILABLE_STREAM_CONFIGURATIONS);
 * }</pre>
 *
 * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS
 * @see CameraDevice#configureOutputs
 */
public final class StreamConfigurationMap {

    /**
     * Create a new {@link StreamConfigurationMap}.
     *
     * <p>The array parameters ownership is passed to this object after creation; do not
     * write to them after this constructor is invoked.</p>
     *
     * @param configurations a non-{@code null} array of {@link StreamConfiguration}
     * @param durations a non-{@code null} array of {@link StreamConfigurationDuration}
     *
     * @throws NullPointerException if any of the arguments or subelements were {@code null}
     *
     * @hide
     */
    public StreamConfigurationMap(
            StreamConfiguration[] configurations,
            StreamConfigurationDuration[] durations) {
        // TODO: format check against ImageFormat/PixelFormat ?

        mConfigurations = checkArrayElementsNotNull(configurations, "configurations");
        mDurations = checkArrayElementsNotNull(durations, "durations");

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Get the image {@code format} output formats in this stream configuration.
     *
     * <p>All image formats returned by this function will be defined in either {@link ImageFormat}
     * or in {@link PixelFormat} (and there is no possibility of collision).</p>
     *
     * <p>Formats listed in this array are guaranteed to return true if queried with
     * {@link #isOutputSupportedFor(int).</p>
     *
     * @return an array of integer format
     *
     * @see ImageFormat
     * @see PixelFormat
     */
    public final int[] getOutputFormats() {
        throw new UnsupportedOperationException("Not implemented yet");
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
        throw new UnsupportedOperationException("Not implemented yet");
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
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Determine whether or not output streams can be
     * {@link CameraDevice#configureOutputs configured} with a particular user-defined format.
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
     *          supported with {@link CameraDevice#configureOutputs}
     *
     * @throws IllegalArgumentException
     *          if the image format was not a defined named constant
     *          from either {@link ImageFormat} or {@link PixelFormat}
     *
     * @see ImageFormat
     * @see PixelFormat
     * @see CameraDevice#configureOutputs
     */
    public boolean isOutputSupportedFor(int format) {
        checkArgumentFormat(format);

        final int[] formats = getOutputFormats();
        for (int i = 0; i < formats.length; ++i) {
            if (format == formats[i]) {
                return true;
            }
        }

        return false;
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
     * {@link CameraDevice#configureOutputs}.</p>
     *
     * <p>Since not all of the above classes support output of all format and size combinations,
     * the particular combination should be queried with {@link #isOutputSupportedFor(Surface)}.</p>
     *
     * @param klass a non-{@code null} {@link Class} object reference
     * @return {@code true} if this class is supported as an output, {@code false} otherwise
     *
     * @throws NullPointerException if {@code klass} was {@code null}
     *
     * @see CameraDevice#configureOutputs
     * @see #isOutputSupportedFor(Surface)
     */
    public static <T> boolean isOutputSupportedFor(final Class<T> klass) {
        checkNotNull(klass, "klass must not be null");
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Determine whether or not the {@code surface} in its current state is suitable to be
     * {@link CameraDevice#configureOutputs configured} as an output.
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
     * @see CameraDevice#configureOutputs
     * @see #isOutputSupportedFor(Class)
     */
    public boolean isOutputSupportedFor(final Surface surface) {
        checkNotNull(surface, "surface must not be null");

        throw new UnsupportedOperationException("Not implemented yet");
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
    public <T> Size[] getOutputSizes(final Class<T> klass) {
        throw new UnsupportedOperationException("Not implemented yet");
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
    public Size[] getOutputSizes(final int format) {
        try {
            checkArgumentFormatSupported(format, /*output*/true);
        } catch (IllegalArgumentException e) {
            return null;
        }

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Get the minimum {@link CaptureRequest#SENSOR_FRAME_DURATION frame duration}
     * for the format/size combination (in nanoseconds).
     *
     * <p>{@code format} should be one of the ones returned by {@link #getOutputFormats()}.</p>
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code format} or {@code size} was not supported
     * @throws NullPointerException if {@code size} was {@code null}
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_MIN_FRAME_DURATIONS
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see ImageFormat
     * @see PixelFormat
     */
    public long getOutputMinFrameDuration(final int format, final Size size) {
        checkArgumentFormatSupported(format, /*output*/true);

        throw new UnsupportedOperationException("Not implemented yet");
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
     * @param klass
     *          a class which is supported by {@link #isOutputSupportedFor(Class)} and has a
     *          non-empty array returned by {@link #getOutputSizes(Class)}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code klass} or {@code size} was not supported
     * @throws NullPointerException if {@code size} or {@code klass} was {@code null}
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_MIN_FRAME_DURATIONS
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see ImageFormat
     * @see PixelFormat
     */
    public <T> long getOutputMinFrameDuration(final Class<T> klass, final Size size) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Get the {@link CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS stall duration}
     * for the format/size combination (in nanoseconds).
     *
     * <p>{@code format} should be one of the ones returned by {@link #getOutputFormats()}.</p>
     * <p>{@code size} should be one of the ones returned by
     * {@link #getOutputSizes(int)}.</p>
     *
     * @param format an image format from {@link ImageFormat} or {@link PixelFormat}
     * @param size an output-compatible size
     * @return a stall duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code format} or {@code size} was not supported
     * @throws NullPointerException if {@code size} was {@code null}
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS
     * @see ImageFormat
     * @see PixelFormat
     */
    public long getOutputStallDuration(final int format, final Size size) {
        checkArgumentFormatSupported(format, /*output*/true);
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Get the {@link CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS stall duration}
     * for the class/size combination (in nanoseconds).
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
     * @param klass
     *          a class which is supported by {@link #isOutputSupportedFor(Class)} and has a
     *          non-empty array returned by {@link #getOutputSizes(Class)}
     * @param size an output-compatible size
     * @return a minimum frame duration {@code >=} 0 in nanoseconds
     *
     * @throws IllegalArgumentException if {@code klass} or {@code size} was not supported
     * @throws NullPointerException if {@code size} or {@code klass} was {@code null}
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_MIN_FRAME_DURATIONS
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see ImageFormat
     * @see PixelFormat
     */
    public <T> long getOutputStallDuration(final Class<T> klass, final Size size) {
        throw new UnsupportedOperationException("Not implemented yet");
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
            // TODO: do we care about order?
            return Arrays.equals(mConfigurations, other.mConfigurations) &&
                    Arrays.equals(mDurations, other.mDurations);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // TODO: do we care about order?
        return HashCodeHelpers.hashCode(mConfigurations) ^ HashCodeHelpers.hashCode(mDurations);
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
     * <p>Any invalid/undefined formats will raise an exception.</p>
     *
     * @param format image format
     * @return the format
     *
     * @throws IllegalArgumentException if the format was invalid
     */
    static int checkArgumentFormatInternal(int format) {
        if (format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
            return format;
        }

        return checkArgumentFormat(format);
    }

    /**
     * Ensures that the format is user-defined in either ImageFormat or PixelFormat.
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
                    "format %x was not defined in either ImageFormat or PixelFormat", format));
        }

        return format;
    }

    private static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;

    private final StreamConfiguration[] mConfigurations;
    private final StreamConfigurationDuration[] mDurations;

}
