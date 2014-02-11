/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.hardware.camera2.impl.CameraMetadataNative;

import java.util.Collections;
import java.util.List;

/**
 * <p>The properties describing a
 * {@link CameraDevice CameraDevice}.</p>
 *
 * <p>These properties are fixed for a given CameraDevice, and can be queried
 * through the {@link CameraManager CameraManager}
 * interface in addition to through the CameraDevice interface.</p>
 *
 * @see CameraDevice
 * @see CameraManager
 */
public final class CameraCharacteristics extends CameraMetadata {

    private final CameraMetadataNative mProperties;
    private List<Key<?>> mAvailableRequestKeys;
    private List<Key<?>> mAvailableResultKeys;

    /**
     * Takes ownership of the passed-in properties object
     * @hide
     */
    public CameraCharacteristics(CameraMetadataNative properties) {
        mProperties = properties;
    }

    @Override
    public <T> T get(Key<T> key) {
        return mProperties.get(key);
    }

    /**
     * Returns the list of keys supported by this {@link CameraDevice} for querying
     * with a {@link CaptureRequest}.
     *
     * <p>The list returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>Each key is only listed once in the list. The order of the keys is undefined.</p>
     *
     * <p>Note that there is no {@code getAvailableCameraCharacteristicsKeys()} -- use
     * {@link #getKeys()} instead.</p>
     *
     * @return List of keys supported by this CameraDevice for CaptureRequests.
     */
    public List<Key<?>> getAvailableCaptureRequestKeys() {
        if (mAvailableRequestKeys == null) {
            mAvailableRequestKeys = getAvailableKeyList(CaptureRequest.class);
        }
        return mAvailableRequestKeys;
    }

    /**
     * Returns the list of keys supported by this {@link CameraDevice} for querying
     * with a {@link CaptureResult}.
     *
     * <p>The list returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>Each key is only listed once in the list. The order of the keys is undefined.</p>
     *
     * <p>Note that there is no {@code getAvailableCameraCharacteristicsKeys()} -- use
     * {@link #getKeys()} instead.</p>
     *
     * @return List of keys supported by this CameraDevice for CaptureResults.
     */
    public List<Key<?>> getAvailableCaptureResultKeys() {
        if (mAvailableResultKeys == null) {
            mAvailableResultKeys = getAvailableKeyList(CaptureResult.class);
        }
        return mAvailableResultKeys;
    }

    /**
     * Returns the list of keys supported by this {@link CameraDevice} by metadataClass.
     *
     * <p>The list returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>Each key is only listed once in the list. The order of the keys is undefined.</p>
     *
     * @param metadataClass The subclass of CameraMetadata that you want to get the keys for.
     *
     * @return List of keys supported by this CameraDevice for metadataClass.
     *
     * @throws IllegalArgumentException if metadataClass is not a subclass of CameraMetadata
     */
    private <T extends CameraMetadata> List<Key<?>> getAvailableKeyList(Class<T> metadataClass) {

        if (metadataClass.equals(CameraMetadata.class)) {
            throw new AssertionError(
                    "metadataClass must be a strict subclass of CameraMetadata");
        } else if (!CameraMetadata.class.isAssignableFrom(metadataClass)) {
            throw new AssertionError(
                    "metadataClass must be a subclass of CameraMetadata");
        }

        return Collections.unmodifiableList(getKeysStatic(metadataClass, /*instance*/null));
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/


    /**
     * <p>The set of auto-exposure antibanding modes that are
     * supported by this camera device.</p>
     * <p>Not all of the auto-exposure anti-banding modes may be
     * supported by a given camera device. This field lists the
     * valid anti-banding modes that the application may request
     * for this camera device; they must include AUTO.</p>
     */
    public static final Key<byte[]> CONTROL_AE_AVAILABLE_ANTIBANDING_MODES =
            new Key<byte[]>("android.control.aeAvailableAntibandingModes", byte[].class);

    /**
     * <p>The set of auto-exposure modes that are supported by this
     * camera device.</p>
     * <p>Not all the auto-exposure modes may be supported by a
     * given camera device, especially if no flash unit is
     * available. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} for this camera device.</p>
     * <p>All camera devices support ON, and all camera devices with
     * flash units support ON_AUTO_FLASH and
     * ON_ALWAYS_FLASH.</p>
     * <p>Full-capability camera devices always support OFF mode,
     * which enables application control of camera exposure time,
     * sensitivity, and frame duration.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final Key<byte[]> CONTROL_AE_AVAILABLE_MODES =
            new Key<byte[]>("android.control.aeAvailableModes", byte[].class);

    /**
     * <p>List of frame rate ranges supported by the
     * AE algorithm/hardware</p>
     */
    public static final Key<int[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES =
            new Key<int[]>("android.control.aeAvailableTargetFpsRanges", int[].class);

    /**
     * <p>Maximum and minimum exposure compensation
     * setting, in counts of
     * {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep}.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP
     */
    public static final Key<int[]> CONTROL_AE_COMPENSATION_RANGE =
            new Key<int[]>("android.control.aeCompensationRange", int[].class);

    /**
     * <p>Smallest step by which exposure compensation
     * can be changed</p>
     */
    public static final Key<Rational> CONTROL_AE_COMPENSATION_STEP =
            new Key<Rational>("android.control.aeCompensationStep", Rational.class);

    /**
     * <p>List of AF modes that can be
     * selected with {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}.</p>
     * <p>Not all the auto-focus modes may be supported by a
     * given camera device. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} for this camera device.</p>
     * <p>All camera devices will support OFF mode, and all camera devices with
     * adjustable focuser units (<code>{@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} &gt; 0</code>)
     * will support AUTO mode.</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     */
    public static final Key<byte[]> CONTROL_AF_AVAILABLE_MODES =
            new Key<byte[]>("android.control.afAvailableModes", byte[].class);

    /**
     * <p>List containing the subset of color effects
     * specified in {@link CaptureRequest#CONTROL_EFFECT_MODE android.control.effectMode} that is supported by
     * this device.</p>
     * <p>This list contains the color effect modes that can be applied to
     * images produced by the camera device. Only modes that have
     * been fully implemented for the current device may be included here.
     * Implementations are not expected to be consistent across all devices.
     * If no color effect modes are available for a device, this should
     * simply be set to OFF.</p>
     * <p>A color effect will only be applied if
     * {@link CaptureRequest#CONTROL_MODE android.control.mode} != OFF.</p>
     *
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final Key<byte[]> CONTROL_AVAILABLE_EFFECTS =
            new Key<byte[]>("android.control.availableEffects", byte[].class);

    /**
     * <p>List containing a subset of scene modes
     * specified in {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode}.</p>
     * <p>This list contains scene modes that can be set for the camera device.
     * Only scene modes that have been fully implemented for the
     * camera device may be included here. Implementations are not expected
     * to be consistent across all devices. If no scene modes are supported
     * by the camera device, this will be set to <code>[DISABLED]</code>.</p>
     *
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final Key<byte[]> CONTROL_AVAILABLE_SCENE_MODES =
            new Key<byte[]>("android.control.availableSceneModes", byte[].class);

    /**
     * <p>List of video stabilization modes that can
     * be supported</p>
     */
    public static final Key<byte[]> CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES =
            new Key<byte[]>("android.control.availableVideoStabilizationModes", byte[].class);

    /**
     * <p>The set of auto-white-balance modes ({@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode})
     * that are supported by this camera device.</p>
     * <p>Not all the auto-white-balance modes may be supported by a
     * given camera device. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} for this camera device.</p>
     * <p>All camera devices will support ON mode.</p>
     * <p>Full-capability camera devices will always support OFF mode,
     * which enables application control of white balance, by using
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform} and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}({@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} must be set to TRANSFORM_MATRIX).</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final Key<byte[]> CONTROL_AWB_AVAILABLE_MODES =
            new Key<byte[]>("android.control.awbAvailableModes", byte[].class);

    /**
     * <p>List of the maximum number of regions that can be used for metering in
     * auto-exposure (AE), auto-white balance (AWB), and auto-focus (AF);
     * this corresponds to the the maximum number of elements in
     * {@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}, {@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions},
     * and {@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     */
    public static final Key<int[]> CONTROL_MAX_REGIONS =
            new Key<int[]>("android.control.maxRegions", int[].class);

    /**
     * <p>Whether this camera device has a
     * flash.</p>
     * <p>If no flash, none of the flash controls do
     * anything. All other metadata should return 0.</p>
     */
    public static final Key<Boolean> FLASH_INFO_AVAILABLE =
            new Key<Boolean>("android.flash.info.available", boolean.class);

    /**
     * <p>Supported resolutions for the JPEG thumbnail</p>
     * <p>Below condiditions will be satisfied for this size list:</p>
     * <ul>
     * <li>The sizes will be sorted by increasing pixel area (width x height).
     * If several resolutions have the same area, they will be sorted by increasing width.</li>
     * <li>The aspect ratio of the largest thumbnail size will be same as the
     * aspect ratio of largest JPEG output size in {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS android.scaler.availableStreamConfigurations}.
     * The largest size is defined as the size that has the largest pixel area
     * in a given size list.</li>
     * <li>Each output JPEG size in {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS android.scaler.availableStreamConfigurations} will have at least
     * one corresponding size that has the same aspect ratio in availableThumbnailSizes,
     * and vice versa.</li>
     * <li>All non (0, 0) sizes will have non-zero widths and heights.</li>
     * </ul>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS
     */
    public static final Key<android.hardware.camera2.Size[]> JPEG_AVAILABLE_THUMBNAIL_SIZES =
            new Key<android.hardware.camera2.Size[]>("android.jpeg.availableThumbnailSizes", android.hardware.camera2.Size[].class);

    /**
     * <p>List of supported aperture
     * values.</p>
     * <p>If the camera device doesn't support variable apertures,
     * listed value will be the fixed aperture.</p>
     * <p>If the camera device supports variable apertures, the aperture value
     * in this list will be sorted in ascending order.</p>
     */
    public static final Key<float[]> LENS_INFO_AVAILABLE_APERTURES =
            new Key<float[]>("android.lens.info.availableApertures", float[].class);

    /**
     * <p>List of supported neutral density filter values for
     * {@link CaptureRequest#LENS_FILTER_DENSITY android.lens.filterDensity}.</p>
     * <p>If changing {@link CaptureRequest#LENS_FILTER_DENSITY android.lens.filterDensity} is not supported,
     * availableFilterDensities must contain only 0. Otherwise, this
     * list contains only the exact filter density values available on
     * this camera device.</p>
     *
     * @see CaptureRequest#LENS_FILTER_DENSITY
     */
    public static final Key<float[]> LENS_INFO_AVAILABLE_FILTER_DENSITIES =
            new Key<float[]>("android.lens.info.availableFilterDensities", float[].class);

    /**
     * <p>The available focal lengths for this device for use with
     * {@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength}.</p>
     * <p>If optical zoom is not supported, this will only report
     * a single value corresponding to the static focal length of the
     * device. Otherwise, this will report every focal length supported
     * by the device.</p>
     *
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     */
    public static final Key<float[]> LENS_INFO_AVAILABLE_FOCAL_LENGTHS =
            new Key<float[]>("android.lens.info.availableFocalLengths", float[].class);

    /**
     * <p>List containing a subset of the optical image
     * stabilization (OIS) modes specified in
     * {@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode}.</p>
     * <p>If OIS is not implemented for a given camera device, this should
     * contain only OFF.</p>
     *
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    public static final Key<byte[]> LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION =
            new Key<byte[]>("android.lens.info.availableOpticalStabilization", byte[].class);

    /**
     * <p>Optional. Hyperfocal distance for this lens.</p>
     * <p>If the lens is fixed focus, the camera device will report 0.</p>
     * <p>If the lens is not fixed focus, the camera device will report this
     * field when {@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} is APPROXIMATE or CALIBRATED.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     */
    public static final Key<Float> LENS_INFO_HYPERFOCAL_DISTANCE =
            new Key<Float>("android.lens.info.hyperfocalDistance", float.class);

    /**
     * <p>Shortest distance from frontmost surface
     * of the lens that can be focused correctly.</p>
     * <p>If the lens is fixed-focus, this should be
     * 0.</p>
     */
    public static final Key<Float> LENS_INFO_MINIMUM_FOCUS_DISTANCE =
            new Key<Float>("android.lens.info.minimumFocusDistance", float.class);

    /**
     * <p>Dimensions of lens shading map.</p>
     * <p>The map should be on the order of 30-40 rows and columns, and
     * must be smaller than 64x64.</p>
     */
    public static final Key<android.hardware.camera2.Size> LENS_INFO_SHADING_MAP_SIZE =
            new Key<android.hardware.camera2.Size>("android.lens.info.shadingMapSize", android.hardware.camera2.Size.class);

    /**
     * <p>The lens focus distance calibration quality.</p>
     * <p>The lens focus distance calibration quality determines the reliability of
     * focus related metadata entries, i.e. {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance},
     * {@link CaptureResult#LENS_FOCUS_RANGE android.lens.focusRange}, {@link CameraCharacteristics#LENS_INFO_HYPERFOCAL_DISTANCE android.lens.info.hyperfocalDistance}, and
     * {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance}.</p>
     *
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CaptureResult#LENS_FOCUS_RANGE
     * @see CameraCharacteristics#LENS_INFO_HYPERFOCAL_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see #LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
     * @see #LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE
     * @see #LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
     */
    public static final Key<Integer> LENS_INFO_FOCUS_DISTANCE_CALIBRATION =
            new Key<Integer>("android.lens.info.focusDistanceCalibration", int.class);

    /**
     * <p>Direction the camera faces relative to
     * device screen</p>
     * @see #LENS_FACING_FRONT
     * @see #LENS_FACING_BACK
     */
    public static final Key<Integer> LENS_FACING =
            new Key<Integer>("android.lens.facing", int.class);

    /**
     * <p>If set to 1, the HAL will always split result
     * metadata for a single capture into multiple buffers,
     * returned using multiple process_capture_result calls.</p>
     * <p>Does not need to be listed in static
     * metadata. Support for partial results will be reworked in
     * future versions of camera service. This quirk will stop
     * working at that point; DO NOT USE without careful
     * consideration of future support.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @hide
     */
    public static final Key<Byte> QUIRKS_USE_PARTIAL_RESULT =
            new Key<Byte>("android.quirks.usePartialResult", byte.class);

    /**
     * <p>The maximum numbers of different types of output streams
     * that can be configured and used simultaneously by a camera device.</p>
     * <p>This is a 3 element tuple that contains the max number of output simultaneous
     * streams for raw sensor, processed (and uncompressed), and JPEG formats respectively.
     * For example, if max raw sensor format output stream number is 1, max YUV streams
     * number is 3, and max JPEG stream number is 2, then this tuple should be <code>(1, 3, 2)</code>.</p>
     * <p>This lists the upper bound of the number of output streams supported by
     * the camera device. Using more streams simultaneously may require more hardware and
     * CPU resources that will consume more power. The image format for a output stream can
     * be any supported format provided by {@link CameraCharacteristics#SCALER_AVAILABLE_FORMATS android.scaler.availableFormats}. The formats
     * defined in {@link CameraCharacteristics#SCALER_AVAILABLE_FORMATS android.scaler.availableFormats} can be catergorized into the 3 stream types
     * as below:</p>
     * <ul>
     * <li>JPEG-compressed format: BLOB.</li>
     * <li>Raw formats: RAW_SENSOR and RAW_OPAQUE.</li>
     * <li>processed, uncompressed formats: YCbCr_420_888, YCrCb_420_SP, YV12.</li>
     * </ul>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_FORMATS
     */
    public static final Key<int[]> REQUEST_MAX_NUM_OUTPUT_STREAMS =
            new Key<int[]>("android.request.maxNumOutputStreams", int[].class);

    /**
     * <p>The maximum numbers of any type of input streams
     * that can be configured and used simultaneously by a camera device.</p>
     * <p>When set to 0, it means no input stream is supported.</p>
     * <p>The image format for a input stream can be any supported
     * format provided by
     * {@link CameraCharacteristics#SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP android.scaler.availableInputOutputFormatsMap}. When using an
     * input stream, there must be at least one output stream
     * configured to to receive the reprocessed images.</p>
     * <p>For example, for Zero Shutter Lag (ZSL) still capture use case, the input
     * stream image format will be RAW_OPAQUE, the associated output stream image format
     * should be JPEG.</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP
     */
    public static final Key<Integer> REQUEST_MAX_NUM_INPUT_STREAMS =
            new Key<Integer>("android.request.maxNumInputStreams", int.class);

    /**
     * <p>Specifies the number of maximum pipeline stages a frame
     * has to go through from when it's exposed to when it's available
     * to the framework.</p>
     * <p>A typical minimum value for this is 2 (one stage to expose,
     * one stage to readout) from the sensor. The ISP then usually adds
     * its own stages to do custom HW processing. Further stages may be
     * added by SW processing.</p>
     * <p>Depending on what settings are used (e.g. YUV, JPEG) and what
     * processing is enabled (e.g. face detection), the actual pipeline
     * depth (specified by {@link CaptureResult#REQUEST_PIPELINE_DEPTH android.request.pipelineDepth}) may be less than
     * the max pipeline depth.</p>
     * <p>A pipeline depth of X stages is equivalent to a pipeline latency of
     * X frame intervals.</p>
     * <p>This value will be 8 or less.</p>
     *
     * @see CaptureResult#REQUEST_PIPELINE_DEPTH
     */
    public static final Key<Byte> REQUEST_PIPELINE_MAX_DEPTH =
            new Key<Byte>("android.request.pipelineMaxDepth", byte.class);

    /**
     * <p>Optional. Defaults to 1. Defines how many sub-components
     * a result will be composed of.</p>
     * <p>In order to combat the pipeline latency, partial results
     * may be delivered to the application layer from the camera device as
     * soon as they are available.</p>
     * <p>A value of 1 means that partial results are not supported.</p>
     * <p>A typical use case for this might be: after requesting an AF lock the
     * new AF state might be available 50% of the way through the pipeline.
     * The camera device could then immediately dispatch this state via a
     * partial result to the framework/application layer, and the rest of
     * the metadata via later partial results.</p>
     */
    public static final Key<Integer> REQUEST_PARTIAL_RESULT_COUNT =
            new Key<Integer>("android.request.partialResultCount", int.class);

    /**
     * <p>List of capabilities that the camera device
     * advertises as fully supporting.</p>
     * <p>A capability is a contract that the camera device makes in order
     * to be able to satisfy one or more use cases.</p>
     * <p>Listing a capability guarantees that the whole set of features
     * required to support a common use will all be available.</p>
     * <p>Using a subset of the functionality provided by an unsupported
     * capability may be possible on a specific camera device implementation;
     * to do this query each of android.request.availableRequestKeys,
     * android.request.availableResultKeys,
     * android.request.availableCharacteristicsKeys.</p>
     * <p>XX: Maybe these should go into {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel}
     * as a table instead?</p>
     * <p>The following capabilities are guaranteed to be available on
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} <code>==</code> FULL devices:</p>
     * <ul>
     * <li>MANUAL_SENSOR</li>
     * <li>ZSL</li>
     * </ul>
     * <p>Other capabilities may be available on either FULL or LIMITED
     * devices, but the app. should query this field to be sure.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see #REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
     * @see #REQUEST_AVAILABLE_CAPABILITIES_OPTIONAL
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
     * @see #REQUEST_AVAILABLE_CAPABILITIES_GCAM
     * @see #REQUEST_AVAILABLE_CAPABILITIES_ZSL
     * @see #REQUEST_AVAILABLE_CAPABILITIES_DNG
     */
    public static final Key<Integer> REQUEST_AVAILABLE_CAPABILITIES =
            new Key<Integer>("android.request.availableCapabilities", int.class);

    /**
     * <p>A list of all keys that the camera device has available
     * to use with CaptureRequest.</p>
     * <p>Attempting to set a key into a CaptureRequest that is not
     * listed here will result in an invalid request and will be rejected
     * by the camera device.</p>
     * <p>This field can be used to query the feature set of a camera device
     * at a more granular level than capabilities. This is especially
     * important for optional keys that are not listed under any capability
     * in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     * <p>TODO: This should be used by #getAvailableCaptureRequestKeys.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_REQUEST_KEYS =
            new Key<int[]>("android.request.availableRequestKeys", int[].class);

    /**
     * <p>A list of all keys that the camera device has available
     * to use with CaptureResult.</p>
     * <p>Attempting to get a key from a CaptureResult that is not
     * listed here will always return a <code>null</code> value. Getting a key from
     * a CaptureResult that is listed here must never return a <code>null</code>
     * value.</p>
     * <p>The following keys may return <code>null</code> unless they are enabled:</p>
     * <ul>
     * <li>{@link CaptureResult#STATISTICS_LENS_SHADING_MAP android.statistics.lensShadingMap} (non-null iff {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} == ON)</li>
     * </ul>
     * <p>(Those sometimes-null keys should nevertheless be listed here
     * if they are available.)</p>
     * <p>This field can be used to query the feature set of a camera device
     * at a more granular level than capabilities. This is especially
     * important for optional keys that are not listed under any capability
     * in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     * <p>TODO: This should be used by #getAvailableCaptureResultKeys.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureResult#STATISTICS_LENS_SHADING_MAP
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_RESULT_KEYS =
            new Key<int[]>("android.request.availableResultKeys", int[].class);

    /**
     * <p>A list of all keys that the camera device has available
     * to use with CameraCharacteristics.</p>
     * <p>This entry follows the same rules as
     * android.request.availableResultKeys (except that it applies for
     * CameraCharacteristics instead of CaptureResult). See above for more
     * details.</p>
     * <p>TODO: This should be used by CameraCharacteristics#getKeys.</p>
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_CHARACTERISTICS_KEYS =
            new Key<int[]>("android.request.availableCharacteristicsKeys", int[].class);

    /**
     * <p>The list of image formats that are supported by this
     * camera device for output streams.</p>
     * <p>All camera devices will support JPEG and YUV_420_888 formats.</p>
     * <p>When set to YUV_420_888, application can access the YUV420 data directly.</p>
     */
    public static final Key<int[]> SCALER_AVAILABLE_FORMATS =
            new Key<int[]>("android.scaler.availableFormats", int[].class);

    /**
     * <p>The minimum frame duration that is supported
     * for each resolution in {@link CameraCharacteristics#SCALER_AVAILABLE_JPEG_SIZES android.scaler.availableJpegSizes}.</p>
     * <p>This corresponds to the minimum steady-state frame duration when only
     * that JPEG stream is active and captured in a burst, with all
     * processing (typically in android.*.mode) set to FAST.</p>
     * <p>When multiple streams are configured, the minimum
     * frame duration will be &gt;= max(individual stream min
     * durations)</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_JPEG_SIZES
     */
    public static final Key<long[]> SCALER_AVAILABLE_JPEG_MIN_DURATIONS =
            new Key<long[]>("android.scaler.availableJpegMinDurations", long[].class);

    /**
     * <p>The JPEG resolutions that are supported by this camera device.</p>
     * <p>The resolutions are listed as <code>(width, height)</code> pairs. All camera devices will support
     * sensor maximum resolution (defined by {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}).</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     */
    public static final Key<android.hardware.camera2.Size[]> SCALER_AVAILABLE_JPEG_SIZES =
            new Key<android.hardware.camera2.Size[]>("android.scaler.availableJpegSizes", android.hardware.camera2.Size[].class);

    /**
     * <p>The maximum ratio between active area width
     * and crop region width, or between active area height and
     * crop region height, if the crop region height is larger
     * than width</p>
     */
    public static final Key<Float> SCALER_AVAILABLE_MAX_DIGITAL_ZOOM =
            new Key<Float>("android.scaler.availableMaxDigitalZoom", float.class);

    /**
     * <p>For each available processed output size (defined in
     * {@link CameraCharacteristics#SCALER_AVAILABLE_PROCESSED_SIZES android.scaler.availableProcessedSizes}), this property lists the
     * minimum supportable frame duration for that size.</p>
     * <p>This should correspond to the frame duration when only that processed
     * stream is active, with all processing (typically in android.*.mode)
     * set to FAST.</p>
     * <p>When multiple streams are configured, the minimum frame duration will
     * be &gt;= max(individual stream min durations).</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_PROCESSED_SIZES
     */
    public static final Key<long[]> SCALER_AVAILABLE_PROCESSED_MIN_DURATIONS =
            new Key<long[]>("android.scaler.availableProcessedMinDurations", long[].class);

    /**
     * <p>The resolutions available for use with
     * processed output streams, such as YV12, NV12, and
     * platform opaque YUV/RGB streams to the GPU or video
     * encoders.</p>
     * <p>The resolutions are listed as <code>(width, height)</code> pairs.</p>
     * <p>For a given use case, the actual maximum supported resolution
     * may be lower than what is listed here, depending on the destination
     * Surface for the image data. For example, for recording video,
     * the video encoder chosen may have a maximum size limit (e.g. 1080p)
     * smaller than what the camera (e.g. maximum resolution is 3264x2448)
     * can provide.</p>
     * <p>Please reference the documentation for the image data destination to
     * check if it limits the maximum size for image data.</p>
     */
    public static final Key<android.hardware.camera2.Size[]> SCALER_AVAILABLE_PROCESSED_SIZES =
            new Key<android.hardware.camera2.Size[]>("android.scaler.availableProcessedSizes", android.hardware.camera2.Size[].class);

    /**
     * <p>The mapping of image formats that are supported by this
     * camera device for input streams, to their corresponding output formats.</p>
     * <p>All camera devices with at least 1
     * {@link CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS android.request.maxNumInputStreams} will have at least one
     * available input format.</p>
     * <p>The camera device will support the following map of formats,
     * if its dependent capability is supported:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="left">Input Format</th>
     * <th align="left">Output Format</th>
     * <th align="left">Capability</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="left">RAW_OPAQUE</td>
     * <td align="left">JPEG</td>
     * <td align="left">ZSL</td>
     * </tr>
     * <tr>
     * <td align="left">RAW_OPAQUE</td>
     * <td align="left">YUV_420_888</td>
     * <td align="left">ZSL</td>
     * </tr>
     * <tr>
     * <td align="left">RAW_OPAQUE</td>
     * <td align="left">RAW16</td>
     * <td align="left">DNG</td>
     * </tr>
     * <tr>
     * <td align="left">RAW16</td>
     * <td align="left">YUV_420_888</td>
     * <td align="left">DNG</td>
     * </tr>
     * <tr>
     * <td align="left">RAW16</td>
     * <td align="left">JPEG</td>
     * <td align="left">DNG</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>For ZSL-capable camera devices, using the RAW_OPAQUE format
     * as either input or output will never hurt maximum frame rate (i.e.
     * {@link CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS android.scaler.availableStallDurations} will not have RAW_OPAQUE).</p>
     * <p>Attempting to configure an input stream with output streams not
     * listed as available in this map is not valid.</p>
     * <p>TODO: Add java type mapping for this property.</p>
     *
     * @see CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS
     * @see CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS
     */
    public static final Key<int[]> SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP =
            new Key<int[]>("android.scaler.availableInputOutputFormatsMap", int[].class);

    /**
     * <p>The available stream configurations that this
     * camera device supports
     * (i.e. format, width, height, output/input stream).</p>
     * <p>The configurations are listed as <code>(format, width, height, input?)</code>
     * tuples.</p>
     * <p>All camera devices will support sensor maximum resolution (defined by
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}) for the JPEG format.</p>
     * <p>For a given use case, the actual maximum supported resolution
     * may be lower than what is listed here, depending on the destination
     * Surface for the image data. For example, for recording video,
     * the video encoder chosen may have a maximum size limit (e.g. 1080p)
     * smaller than what the camera (e.g. maximum resolution is 3264x2448)
     * can provide.</p>
     * <p>Please reference the documentation for the image data destination to
     * check if it limits the maximum size for image data.</p>
     * <p>Not all output formats may be supported in a configuration with
     * an input stream of a particular format. For more details, see
     * {@link CameraCharacteristics#SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP android.scaler.availableInputOutputFormatsMap}.</p>
     * <p>The following table describes the minimum required output stream
     * configurations based on the hardware level
     * ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel}):</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">Format</th>
     * <th align="center">Size</th>
     * <th align="center">Hardware Level</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">JPEG</td>
     * <td align="center">{@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}</td>
     * <td align="center">Any</td>
     * <td align="center"></td>
     * </tr>
     * <tr>
     * <td align="center">JPEG</td>
     * <td align="center">1920x1080 (1080p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 1080p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">JPEG</td>
     * <td align="center">1280x720 (720)</td>
     * <td align="center">Any</td>
     * <td align="center">if 720p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">JPEG</td>
     * <td align="center">640x480 (480p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 480p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">JPEG</td>
     * <td align="center">320x240 (240p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 240p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">YUV_420_888</td>
     * <td align="center">all output sizes available for JPEG</td>
     * <td align="center">FULL</td>
     * <td align="center"></td>
     * </tr>
     * <tr>
     * <td align="center">YUV_420_888</td>
     * <td align="center">all output sizes available for JPEG, up to the maximum video size</td>
     * <td align="center">LIMITED</td>
     * <td align="center"></td>
     * </tr>
     * <tr>
     * <td align="center">IMPLEMENTATION_DEFINED</td>
     * <td align="center">same as YUV_420_888</td>
     * <td align="center">Any</td>
     * <td align="center"></td>
     * </tr>
     * </tbody>
     * </table>
     * <p>Refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} for additional
     * mandatory stream configurations on a per-capability basis.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see #SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT
     * @see #SCALER_AVAILABLE_STREAM_CONFIGURATIONS_INPUT
     */
    public static final Key<int[]> SCALER_AVAILABLE_STREAM_CONFIGURATIONS =
            new Key<int[]>("android.scaler.availableStreamConfigurations", int[].class);

    /**
     * <p>This lists the minimum frame duration for each
     * format/size combination.</p>
     * <p>This should correspond to the frame duration when only that
     * stream is active, with all processing (typically in android.*.mode)
     * set to either OFF or FAST.</p>
     * <p>When multiple streams are used in a request, the minimum frame
     * duration will be max(individual stream min durations).</p>
     * <p>The minimum frame duration of a stream (of a particular format, size)
     * is the same regardless of whether the stream is input or output.</p>
     * <p>See {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} and
     * {@link CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS android.scaler.availableStallDurations} for more details about
     * calculating the max frame rate.</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     */
    public static final Key<long[]> SCALER_AVAILABLE_MIN_FRAME_DURATIONS =
            new Key<long[]>("android.scaler.availableMinFrameDurations", long[].class);

    /**
     * <p>This lists the maximum stall duration for each
     * format/size combination.</p>
     * <p>A stall duration is how much extra time would get added
     * to the normal minimum frame duration for a repeating request
     * that has streams with non-zero stall.</p>
     * <p>For example, consider JPEG captures which have the following
     * characteristics:</p>
     * <ul>
     * <li>JPEG streams act like processed YUV streams in requests for which
     * they are not included; in requests in which they are directly
     * referenced, they act as JPEG streams. This is because supporting a
     * JPEG stream requires the underlying YUV data to always be ready for
     * use by a JPEG encoder, but the encoder will only be used (and impact
     * frame duration) on requests that actually reference a JPEG stream.</li>
     * <li>The JPEG processor can run concurrently to the rest of the camera
     * pipeline, but cannot process more than 1 capture at a time.</li>
     * </ul>
     * <p>In other words, using a repeating YUV request would result
     * in a steady frame rate (let's say it's 30 FPS). If a single
     * JPEG request is submitted periodically, the frame rate will stay
     * at 30 FPS (as long as we wait for the previous JPEG to return each
     * time). If we try to submit a repeating YUV + JPEG request, then
     * the frame rate will drop from 30 FPS.</p>
     * <p>In general, submitting a new request with a non-0 stall time
     * stream will <em>not</em> cause a frame rate drop unless there are still
     * outstanding buffers for that stream from previous requests.</p>
     * <p>Submitting a repeating request with streams (call this <code>S</code>)
     * is the same as setting the minimum frame duration from
     * the normal minimum frame duration corresponding to <code>S</code>, added with
     * the maximum stall duration for <code>S</code>.</p>
     * <p>If interleaving requests with and without a stall duration,
     * a request will stall by the maximum of the remaining times
     * for each can-stall stream with outstanding buffers.</p>
     * <p>This means that a stalling request will not have an exposure start
     * until the stall has completed.</p>
     * <p>This should correspond to the stall duration when only that stream is
     * active, with all processing (typically in android.*.mode) set to FAST
     * or OFF. Setting any of the processing modes to HIGH_QUALITY
     * effectively results in an indeterminate stall duration for all
     * streams in a request (the regular stall calculation rules are
     * ignored).</p>
     * <p>The following formats may always have a stall duration:</p>
     * <ul>
     * <li>JPEG</li>
     * <li>RAW16</li>
     * </ul>
     * <p>The following formats will never have a stall duration:</p>
     * <ul>
     * <li>YUV_420_888</li>
     * <li>IMPLEMENTATION_DEFINED</li>
     * </ul>
     * <p>All other formats may or may not have an allowed stall duration on
     * a per-capability basis; refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}
     * for more details.</p>
     * <p>See {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} for more information about
     * calculating the max frame rate (absent stalls).</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     */
    public static final Key<long[]> SCALER_AVAILABLE_STALL_DURATIONS =
            new Key<long[]>("android.scaler.availableStallDurations", long[].class);

    /**
     * <p>Area of raw data which corresponds to only
     * active pixels.</p>
     * <p>It is smaller or equal to
     * sensor full pixel array, which could include the black calibration pixels.</p>
     */
    public static final Key<android.graphics.Rect> SENSOR_INFO_ACTIVE_ARRAY_SIZE =
            new Key<android.graphics.Rect>("android.sensor.info.activeArraySize", android.graphics.Rect.class);

    /**
     * <p>Range of valid sensitivities</p>
     */
    public static final Key<int[]> SENSOR_INFO_SENSITIVITY_RANGE =
            new Key<int[]>("android.sensor.info.sensitivityRange", int[].class);

    /**
     * <p>Range of valid exposure
     * times used by {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime}.</p>
     *
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     */
    public static final Key<long[]> SENSOR_INFO_EXPOSURE_TIME_RANGE =
            new Key<long[]>("android.sensor.info.exposureTimeRange", long[].class);

    /**
     * <p>Maximum possible frame duration (minimum frame
     * rate).</p>
     * <p>The largest possible {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}
     * that will be accepted by the camera device. Attempting to use
     * frame durations beyond the maximum will result in the frame duration
     * being clipped to the maximum. See that control
     * for a full definition of frame durations.</p>
     * <p>Refer to
     * {@link CameraCharacteristics#SCALER_AVAILABLE_PROCESSED_MIN_DURATIONS android.scaler.availableProcessedMinDurations},
     * {@link CameraCharacteristics#SCALER_AVAILABLE_JPEG_MIN_DURATIONS android.scaler.availableJpegMinDurations}, and
     * android.scaler.availableRawMinDurations for the minimum
     * frame duration values.</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_JPEG_MIN_DURATIONS
     * @see CameraCharacteristics#SCALER_AVAILABLE_PROCESSED_MIN_DURATIONS
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     */
    public static final Key<Long> SENSOR_INFO_MAX_FRAME_DURATION =
            new Key<Long>("android.sensor.info.maxFrameDuration", long.class);

    /**
     * <p>The physical dimensions of the full pixel
     * array</p>
     * <p>Needed for FOV calculation for old API</p>
     */
    public static final Key<float[]> SENSOR_INFO_PHYSICAL_SIZE =
            new Key<float[]>("android.sensor.info.physicalSize", float[].class);

    /**
     * <p>Dimensions of full pixel array, possibly
     * including black calibration pixels.</p>
     * <p>Maximum output resolution for raw format must
     * match this in
     * {@link CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS android.scaler.availableStreamConfigurations}.</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_CONFIGURATIONS
     */
    public static final Key<android.hardware.camera2.Size> SENSOR_INFO_PIXEL_ARRAY_SIZE =
            new Key<android.hardware.camera2.Size>("android.sensor.info.pixelArraySize", android.hardware.camera2.Size.class);

    /**
     * <p>Gain factor from electrons to raw units when
     * ISO=100</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final Key<Rational> SENSOR_BASE_GAIN_FACTOR =
            new Key<Rational>("android.sensor.baseGainFactor", Rational.class);

    /**
     * <p>A fixed black level offset for each of the color filter arrangement
     * (CFA) mosaic channels.</p>
     * <p>This tag specifies the zero light value for each of the CFA mosaic
     * channels in the camera sensor.</p>
     * <p>The values are given in row-column scan order, with the first value
     * corresponding to the element of the CFA in row=0, column=0.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<int[]> SENSOR_BLACK_LEVEL_PATTERN =
            new Key<int[]>("android.sensor.blackLevelPattern", int[].class);

    /**
     * <p>Maximum sensitivity that is implemented
     * purely through analog gain.</p>
     * <p>For {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity} values less than or
     * equal to this, all applied gain must be analog. For
     * values above this, the gain applied can be a mix of analog and
     * digital.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    public static final Key<Integer> SENSOR_MAX_ANALOG_SENSITIVITY =
            new Key<Integer>("android.sensor.maxAnalogSensitivity", int.class);

    /**
     * <p>Clockwise angle through which the output
     * image needs to be rotated to be upright on the device
     * screen in its native orientation. Also defines the
     * direction of rolling shutter readout, which is from top
     * to bottom in the sensor's coordinate system</p>
     */
    public static final Key<Integer> SENSOR_ORIENTATION =
            new Key<Integer>("android.sensor.orientation", int.class);

    /**
     * <p>The number of input samples for each dimension of
     * {@link CaptureResult#SENSOR_PROFILE_HUE_SAT_MAP android.sensor.profileHueSatMap}.</p>
     * <p>The number of input samples for the hue, saturation, and value
     * dimension of {@link CaptureResult#SENSOR_PROFILE_HUE_SAT_MAP android.sensor.profileHueSatMap}. The order of the
     * dimensions given is hue, saturation, value; where hue is the 0th
     * element.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureResult#SENSOR_PROFILE_HUE_SAT_MAP
     */
    public static final Key<int[]> SENSOR_PROFILE_HUE_SAT_MAP_DIMENSIONS =
            new Key<int[]>("android.sensor.profileHueSatMapDimensions", int[].class);

    /**
     * <p>Optional. Defaults to [OFF]. Lists the supported test
     * pattern modes for {@link CaptureRequest#SENSOR_TEST_PATTERN_MODE android.sensor.testPatternMode}.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final Key<Byte> SENSOR_AVAILABLE_TEST_PATTERN_MODES =
            new Key<Byte>("android.sensor.availableTestPatternModes", byte.class);

    /**
     * <p>Which face detection modes are available,
     * if any</p>
     * <p>OFF means face detection is disabled, it must
     * be included in the list.</p>
     * <p>SIMPLE means the device supports the
     * android.statistics.faceRectangles and
     * android.statistics.faceScores outputs.</p>
     * <p>FULL means the device additionally supports the
     * android.statistics.faceIds and
     * android.statistics.faceLandmarks outputs.</p>
     */
    public static final Key<byte[]> STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES =
            new Key<byte[]>("android.statistics.info.availableFaceDetectModes", byte[].class);

    /**
     * <p>Maximum number of simultaneously detectable
     * faces</p>
     */
    public static final Key<Integer> STATISTICS_INFO_MAX_FACE_COUNT =
            new Key<Integer>("android.statistics.info.maxFaceCount", int.class);

    /**
     * <p>Maximum number of supported points in the
     * tonemap curve that can be used for {@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed}, or
     * {@link CaptureRequest#TONEMAP_CURVE_GREEN android.tonemap.curveGreen}, or {@link CaptureRequest#TONEMAP_CURVE_BLUE android.tonemap.curveBlue}.</p>
     * <p>If the actual number of points provided by the application (in
     * android.tonemap.curve*)  is less than max, the camera device will
     * resample the curve to its internal representation, using linear
     * interpolation.</p>
     * <p>The output curves in the result metadata may have a different number
     * of points than the input curves, and will represent the actual
     * hardware curves used as closely as possible when linearly interpolated.</p>
     *
     * @see CaptureRequest#TONEMAP_CURVE_BLUE
     * @see CaptureRequest#TONEMAP_CURVE_GREEN
     * @see CaptureRequest#TONEMAP_CURVE_RED
     */
    public static final Key<Integer> TONEMAP_MAX_CURVE_POINTS =
            new Key<Integer>("android.tonemap.maxCurvePoints", int.class);

    /**
     * <p>A list of camera LEDs that are available on this system.</p>
     * @see #LED_AVAILABLE_LEDS_TRANSMIT
     * @hide
     */
    public static final Key<int[]> LED_AVAILABLE_LEDS =
            new Key<int[]>("android.led.availableLeds", int[].class);

    /**
     * <p>Generally classifies the overall set of the camera device functionality.</p>
     * <p>Camera devices will come in two flavors: LIMITED and FULL.</p>
     * <p>A FULL device has the most support possible and will enable the
     * widest range of use cases such as:</p>
     * <ul>
     * <li>30 FPS at maximum resolution (== sensor resolution)</li>
     * <li>Per frame control</li>
     * <li>Manual sensor control</li>
     * <li>Zero Shutter Lag (ZSL)</li>
     * </ul>
     * <p>A LIMITED device may have some or none of the above characteristics.
     * To find out more refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_FULL
     */
    public static final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL =
            new Key<Integer>("android.info.supportedHardwareLevel", int.class);

    /**
     * <p>The maximum number of frames that can occur after a request
     * (different than the previous) has been submitted, and before the
     * result's state becomes synchronized (by setting
     * android.sync.frameNumber to a non-negative value).</p>
     * <p>This defines the maximum distance (in number of metadata results),
     * between android.sync.frameNumber and the equivalent
     * android.request.frameCount.</p>
     * <p>In other words this acts as an upper boundary for how many frames
     * must occur before the camera device knows for a fact that the new
     * submitted camera settings have been applied in outgoing frames.</p>
     * <p>For example if the distance was 2,</p>
     * <pre><code>initial request = X (repeating)
     * request1 = X
     * request2 = Y
     * request3 = Y
     * request4 = Y
     *
     * where requestN has frameNumber N, and the first of the repeating
     * initial request's has frameNumber F (and F &lt; 1).
     *
     * initial result = X' + { android.sync.frameNumber == F }
     * result1 = X' + { android.sync.frameNumber == F }
     * result2 = X' + { android.sync.frameNumber == CONVERGING }
     * result3 = X' + { android.sync.frameNumber == CONVERGING }
     * result4 = X' + { android.sync.frameNumber == 2 }
     *
     * where resultN has frameNumber N.
     * </code></pre>
     * <p>Since <code>result4</code> has a <code>frameNumber == 4</code> and
     * <code>android.sync.frameNumber == 2</code>, the distance is clearly
     * <code>4 - 2 = 2</code>.</p>
     * @see #SYNC_MAX_LATENCY_PER_FRAME_CONTROL
     * @see #SYNC_MAX_LATENCY_UNKNOWN
     */
    public static final Key<Integer> SYNC_MAX_LATENCY =
            new Key<Integer>("android.sync.maxLatency", int.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/
}
