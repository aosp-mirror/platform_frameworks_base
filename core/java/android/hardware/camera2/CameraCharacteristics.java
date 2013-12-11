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
     * <p>
     * Which set of antibanding modes are
     * supported
     * </p>
     */
    public static final Key<byte[]> CONTROL_AE_AVAILABLE_ANTIBANDING_MODES =
            new Key<byte[]>("android.control.aeAvailableAntibandingModes", byte[].class);

    /**
     * <p>
     * List of frame rate ranges supported by the
     * AE algorithm/hardware
     * </p>
     */
    public static final Key<int[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES =
            new Key<int[]>("android.control.aeAvailableTargetFpsRanges", int[].class);

    /**
     * <p>
     * Maximum and minimum exposure compensation
     * setting, in counts of
     * android.control.aeCompensationStepSize
     * </p>
     */
    public static final Key<int[]> CONTROL_AE_COMPENSATION_RANGE =
            new Key<int[]>("android.control.aeCompensationRange", int[].class);

    /**
     * <p>
     * Smallest step by which exposure compensation
     * can be changed
     * </p>
     */
    public static final Key<Rational> CONTROL_AE_COMPENSATION_STEP =
            new Key<Rational>("android.control.aeCompensationStep", Rational.class);

    /**
     * <p>
     * List of AF modes that can be
     * selected
     * </p>
     */
    public static final Key<byte[]> CONTROL_AF_AVAILABLE_MODES =
            new Key<byte[]>("android.control.afAvailableModes", byte[].class);

    /**
     * <p>
     * what subset of the full color effect enum
     * list is supported
     * </p>
     */
    public static final Key<byte[]> CONTROL_AVAILABLE_EFFECTS =
            new Key<byte[]>("android.control.availableEffects", byte[].class);

    /**
     * <p>
     * what subset of the scene mode enum list is
     * supported.
     * </p>
     */
    public static final Key<byte[]> CONTROL_AVAILABLE_SCENE_MODES =
            new Key<byte[]>("android.control.availableSceneModes", byte[].class);

    /**
     * <p>
     * List of video stabilization modes that can
     * be supported
     * </p>
     */
    public static final Key<byte[]> CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES =
            new Key<byte[]>("android.control.availableVideoStabilizationModes", byte[].class);

    /**
     */
    public static final Key<byte[]> CONTROL_AWB_AVAILABLE_MODES =
            new Key<byte[]>("android.control.awbAvailableModes", byte[].class);

    /**
     * <p>
     * For AE, AWB, and AF, how many individual
     * regions can be listed for metering?
     * </p>
     */
    public static final Key<Integer> CONTROL_MAX_REGIONS =
            new Key<Integer>("android.control.maxRegions", int.class);

    /**
     * <p>
     * Whether this camera has a
     * flash
     * </p>
     * <p>
     * If no flash, none of the flash controls do
     * anything. All other metadata should return 0
     * </p>
     */
    public static final Key<Byte> FLASH_INFO_AVAILABLE =
            new Key<Byte>("android.flash.info.available", byte.class);

    /**
     * <p>
     * Supported resolutions for the JPEG
     * thumbnail
     * </p>
     */
    public static final Key<android.hardware.camera2.Size[]> JPEG_AVAILABLE_THUMBNAIL_SIZES =
            new Key<android.hardware.camera2.Size[]>("android.jpeg.availableThumbnailSizes", android.hardware.camera2.Size[].class);

    /**
     * <p>
     * List of supported aperture
     * values
     * </p>
     * <p>
     * If variable aperture not available, only setting
     * should be for the fixed aperture
     * </p>
     */
    public static final Key<float[]> LENS_INFO_AVAILABLE_APERTURES =
            new Key<float[]>("android.lens.info.availableApertures", float[].class);

    /**
     * <p>
     * List of supported ND filter
     * values
     * </p>
     * <p>
     * If not available, only setting is 0. Otherwise,
     * lists the available exposure index values for dimming
     * (2 would mean the filter is set to reduce incoming
     * light by two stops)
     * </p>
     */
    public static final Key<float[]> LENS_INFO_AVAILABLE_FILTER_DENSITIES =
            new Key<float[]>("android.lens.info.availableFilterDensities", float[].class);

    /**
     * <p>
     * If fitted with optical zoom, what focal
     * lengths are available. If not, the static focal
     * length
     * </p>
     * <p>
     * If optical zoom not supported, only one value
     * should be reported
     * </p>
     */
    public static final Key<float[]> LENS_INFO_AVAILABLE_FOCAL_LENGTHS =
            new Key<float[]>("android.lens.info.availableFocalLengths", float[].class);

    /**
     * <p>
     * List of supported optical image
     * stabilization modes
     * </p>
     */
    public static final Key<byte[]> LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION =
            new Key<byte[]>("android.lens.info.availableOpticalStabilization", byte[].class);

    /**
     * <p>
     * Hyperfocal distance for this lens; set to
     * 0 if fixed focus
     * </p>
     * <p>
     * The hyperfocal distance is used for the old
     * API's 'fixed' setting
     * </p>
     */
    public static final Key<Float> LENS_INFO_HYPERFOCAL_DISTANCE =
            new Key<Float>("android.lens.info.hyperfocalDistance", float.class);

    /**
     * <p>
     * Shortest distance from frontmost surface
     * of the lens that can be focused correctly
     * </p>
     * <p>
     * If the lens is fixed-focus, this should be
     * 0
     * </p>
     */
    public static final Key<Float> LENS_INFO_MINIMUM_FOCUS_DISTANCE =
            new Key<Float>("android.lens.info.minimumFocusDistance", float.class);

    /**
     * <p>
     * Dimensions of lens shading
     * map
     * </p>
     */
    public static final Key<android.hardware.camera2.Size> LENS_INFO_SHADING_MAP_SIZE =
            new Key<android.hardware.camera2.Size>("android.lens.info.shadingMapSize", android.hardware.camera2.Size.class);

    /**
     * <p>
     * Direction the camera faces relative to
     * device screen
     * </p>
     * @see #LENS_FACING_FRONT
     * @see #LENS_FACING_BACK
     */
    public static final Key<Integer> LENS_FACING =
            new Key<Integer>("android.lens.facing", int.class);

    /**
     * <p>
     * If set to 1, the HAL will always split result
     * metadata for a single capture into multiple buffers,
     * returned using multiple process_capture_result calls.
     * </p>
     * <p>
     * Does not need to be listed in static
     * metadata. Support for partial results will be reworked in
     * future versions of camera service. This quirk will stop
     * working at that point; DO NOT USE without careful
     * consideration of future support.
     * </p>
     *
     * <b>Optional</b> - This value may be null on some devices.
     *
     * @hide
     */
    public static final Key<Byte> QUIRKS_USE_PARTIAL_RESULT =
            new Key<Byte>("android.quirks.usePartialResult", byte.class);

    /**
     * <p>
     * How many output streams can be allocated at
     * the same time for each type of stream
     * </p>
     * <p>
     * Video snapshot with preview callbacks requires 3
     * processed streams (preview, record, app callbacks) and
     * one JPEG stream (snapshot)
     * </p>
     */
    public static final Key<int[]> REQUEST_MAX_NUM_OUTPUT_STREAMS =
            new Key<int[]>("android.request.maxNumOutputStreams", int[].class);

    /**
     * <p>
     * List of app-visible formats
     * </p>
     */
    public static final Key<int[]> SCALER_AVAILABLE_FORMATS =
            new Key<int[]>("android.scaler.availableFormats", int[].class);

    /**
     * <p>
     * The minimum frame duration that is supported
     * for each resolution in availableJpegSizes. Should
     * correspond to the frame duration when only that JPEG
     * stream is active and captured in a burst, with all
     * processing set to FAST
     * </p>
     * <p>
     * When multiple streams are configured, the minimum
     * frame duration will be >= max(individual stream min
     * durations)
     * </p>
     */
    public static final Key<long[]> SCALER_AVAILABLE_JPEG_MIN_DURATIONS =
            new Key<long[]>("android.scaler.availableJpegMinDurations", long[].class);

    /**
     * <p>
     * The resolutions available for output from
     * the JPEG block. Listed as width x height
     * </p>
     */
    public static final Key<android.hardware.camera2.Size[]> SCALER_AVAILABLE_JPEG_SIZES =
            new Key<android.hardware.camera2.Size[]>("android.scaler.availableJpegSizes", android.hardware.camera2.Size[].class);

    /**
     * <p>
     * The maximum ratio between active area width
     * and crop region width, or between active area height and
     * crop region height, if the crop region height is larger
     * than width
     * </p>
     */
    public static final Key<Float> SCALER_AVAILABLE_MAX_DIGITAL_ZOOM =
            new Key<Float>("android.scaler.availableMaxDigitalZoom", float.class);

    /**
     * <p>
     * The minimum frame duration that is supported
     * for each resolution in availableProcessedSizes. Should
     * correspond to the frame duration when only that processed
     * stream is active, with all processing set to
     * FAST
     * </p>
     * <p>
     * When multiple streams are configured, the minimum
     * frame duration will be >= max(individual stream min
     * durations)
     * </p>
     */
    public static final Key<long[]> SCALER_AVAILABLE_PROCESSED_MIN_DURATIONS =
            new Key<long[]>("android.scaler.availableProcessedMinDurations", long[].class);

    /**
     * <p>
     * The resolutions available for use with
     * processed output streams, such as YV12, NV12, and
     * platform opaque YUV/RGB streams to the GPU or video
     * encoders. Listed as width, height
     * </p>
     * <p>
     * The actual supported resolution list may be limited by
     * consumer end points for different use cases. For example, for
     * recording use case, the largest supported resolution may be
     * limited by max supported size from encoder, for preview use
     * case, the largest supported resolution may be limited by max
     * resolution SurfaceTexture/SurfaceView can support.
     * </p>
     */
    public static final Key<android.hardware.camera2.Size[]> SCALER_AVAILABLE_PROCESSED_SIZES =
            new Key<android.hardware.camera2.Size[]>("android.scaler.availableProcessedSizes", android.hardware.camera2.Size[].class);

    /**
     * <p>
     * Area of raw data which corresponds to only
     * active pixels; smaller or equal to
     * pixelArraySize.
     * </p>
     */
    public static final Key<android.graphics.Rect> SENSOR_INFO_ACTIVE_ARRAY_SIZE =
            new Key<android.graphics.Rect>("android.sensor.info.activeArraySize", android.graphics.Rect.class);

    /**
     * <p>
     * Range of valid sensitivities
     * </p>
     */
    public static final Key<int[]> SENSOR_INFO_SENSITIVITY_RANGE =
            new Key<int[]>("android.sensor.info.sensitivityRange", int[].class);

    /**
     * <p>
     * Range of valid exposure
     * times
     * </p>
     */
    public static final Key<long[]> SENSOR_INFO_EXPOSURE_TIME_RANGE =
            new Key<long[]>("android.sensor.info.exposureTimeRange", long[].class);

    /**
     * <p>
     * Maximum possible frame duration (minimum frame
     * rate)
     * </p>
     * <p>
     * Minimum duration is a function of resolution,
     * processing settings. See
     * android.scaler.availableProcessedMinDurations
     * android.scaler.availableJpegMinDurations
     * android.scaler.availableRawMinDurations
     * </p>
     */
    public static final Key<Long> SENSOR_INFO_MAX_FRAME_DURATION =
            new Key<Long>("android.sensor.info.maxFrameDuration", long.class);

    /**
     * <p>
     * The physical dimensions of the full pixel
     * array
     * </p>
     * <p>
     * Needed for FOV calculation for old API
     * </p>
     */
    public static final Key<float[]> SENSOR_INFO_PHYSICAL_SIZE =
            new Key<float[]>("android.sensor.info.physicalSize", float[].class);

    /**
     * <p>
     * Gain factor from electrons to raw units when
     * ISO=100
     * </p>
     *
     * <b>Optional</b> - This value may be null on some devices.
     *
     * <b>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL}</b> -
     * Present on all devices that report being FULL level hardware devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL HARDWARE_LEVEL} key.
     */
    public static final Key<Rational> SENSOR_BASE_GAIN_FACTOR =
            new Key<Rational>("android.sensor.baseGainFactor", Rational.class);

    /**
     * <p>
     * Maximum sensitivity that is implemented
     * purely through analog gain
     * </p>
     * <p>
     * For android.sensor.sensitivity values less than or
     * equal to this, all applied gain must be analog. For
     * values above this, it can be a mix of analog and
     * digital
     * </p>
     *
     * <b>Optional</b> - This value may be null on some devices.
     *
     * <b>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL}</b> -
     * Present on all devices that report being FULL level hardware devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL HARDWARE_LEVEL} key.
     */
    public static final Key<Integer> SENSOR_MAX_ANALOG_SENSITIVITY =
            new Key<Integer>("android.sensor.maxAnalogSensitivity", int.class);

    /**
     * <p>
     * Clockwise angle through which the output
     * image needs to be rotated to be upright on the device
     * screen in its native orientation. Also defines the
     * direction of rolling shutter readout, which is from top
     * to bottom in the sensor's coordinate system
     * </p>
     */
    public static final Key<Integer> SENSOR_ORIENTATION =
            new Key<Integer>("android.sensor.orientation", int.class);

    /**
     * <p>
     * Which face detection modes are available,
     * if any
     * </p>
     * <p>
     * OFF means face detection is disabled, it must
     * be included in the list.
     * </p><p>
     * SIMPLE means the device supports the
     * android.statistics.faceRectangles and
     * android.statistics.faceScores outputs.
     * </p><p>
     * FULL means the device additionally supports the
     * android.statistics.faceIds and
     * android.statistics.faceLandmarks outputs.
     * </p>
     */
    public static final Key<byte[]> STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES =
            new Key<byte[]>("android.statistics.info.availableFaceDetectModes", byte[].class);

    /**
     * <p>
     * Maximum number of simultaneously detectable
     * faces
     * </p>
     */
    public static final Key<Integer> STATISTICS_INFO_MAX_FACE_COUNT =
            new Key<Integer>("android.statistics.info.maxFaceCount", int.class);

    /**
     * <p>
     * Maximum number of supported points in the
     * tonemap curve
     * </p>
     */
    public static final Key<Integer> TONEMAP_MAX_CURVE_POINTS =
            new Key<Integer>("android.tonemap.maxCurvePoints", int.class);

    /**
     * <p>
     * A list of camera LEDs that are available on this system.
     * </p>
     * @see #LED_AVAILABLE_LEDS_TRANSMIT
     *
     * @hide
     */
    public static final Key<int[]> LED_AVAILABLE_LEDS =
            new Key<int[]>("android.led.availableLeds", int[].class);

    /**
     * <p>
     * The camera 3 HAL device can implement one of two possible
     * operational modes; limited and full. Full support is
     * expected from new higher-end devices. Limited mode has
     * hardware requirements roughly in line with those for a
     * camera HAL device v1 implementation, and is expected from
     * older or inexpensive devices. Full is a strict superset of
     * limited, and they share the same essential operational flow.
     * </p><p>
     * For full details refer to "S3. Operational Modes" in camera3.h
     * </p>
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_FULL
     */
    public static final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL =
            new Key<Integer>("android.info.supportedHardwareLevel", int.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/
}
