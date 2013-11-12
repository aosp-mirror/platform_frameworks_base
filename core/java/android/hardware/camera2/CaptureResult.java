/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.impl.CameraMetadataNative;

/**
 * <p>The results of a single image capture from the image sensor.</p>
 *
 * <p>Contains the final configuration for the capture hardware (sensor, lens,
 * flash), the processing pipeline, the control algorithms, and the output
 * buffers.</p>
 *
 * <p>CaptureResults are produced by a {@link CameraDevice} after processing a
 * {@link CaptureRequest}. All properties listed for capture requests can also
 * be queried on the capture result, to determine the final values used for
 * capture. The result also includes additional metadata about the state of the
 * camera device during the capture.</p>
 *
 */
public final class CaptureResult extends CameraMetadata {

    private final CameraMetadataNative mResults;
    private final CaptureRequest mRequest;
    private final int mSequenceId;

    /**
     * Takes ownership of the passed-in properties object
     * @hide
     */
    public CaptureResult(CameraMetadataNative results, CaptureRequest parent, int sequenceId) {
        if (results == null) {
            throw new IllegalArgumentException("results was null");
        }

        if (parent == null) {
            throw new IllegalArgumentException("parent was null");
        }

        mResults = results;
        mRequest = parent;
        mSequenceId = sequenceId;
    }

    @Override
    public <T> T get(Key<T> key) {
        return mResults.get(key);
    }

    /**
     * Get the request associated with this result.
     *
     * <p>Whenever a request is successfully captured, with
     * {@link CameraDevice.CaptureListener#onCaptureCompleted},
     * the {@code result}'s {@code getRequest()} will return that {@code request}.
     * </p>
     *
     * <p>In particular,
     * <code><pre>cameraDevice.capture(someRequest, new CaptureListener() {
     *     {@literal @}Override
     *     void onCaptureCompleted(CaptureRequest myRequest, CaptureResult myResult) {
     *         assert(myResult.getRequest.equals(myRequest) == true);
     *     }
     * };
     * </code></pre>
     * </p>
     *
     * @return The request associated with this result. Never {@code null}.
     */
    public CaptureRequest getRequest() {
        return mRequest;
    }

    /**
     * Get the frame number associated with this result.
     *
     * <p>Whenever a request has been processed, regardless of failure or success,
     * it gets a unique frame number assigned to its future result/failure.</p>
     *
     * <p>This value monotonically increments, starting with 0,
     * for every new result or failure; and the scope is the lifetime of the
     * {@link CameraDevice}.</p>
     *
     * @return int frame number
     */
    public int getFrameNumber() {
        return get(REQUEST_FRAME_COUNT);
    }

    /**
     * The sequence ID for this failure that was returned by the
     * {@link CameraDevice#capture} family of functions.
     *
     * <p>The sequence ID is a unique monotonically increasing value starting from 0,
     * incremented every time a new group of requests is submitted to the CameraDevice.</p>
     *
     * @return int The ID for the sequence of requests that this capture result is a part of
     *
     * @see CameraDevice.CaptureListener#onCaptureSequenceCompleted
     */
    public int getSequenceId() {
        return mSequenceId;
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/

    /**
     * <p>
     * A color transform matrix to use to transform
     * from sensor RGB color space to output linear sRGB color space
     * </p>
     * <p>
     * This matrix is either set by HAL when the request
     * android.colorCorrection.mode is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * android.colorCorrection.mode is TRANSFORM_MATRIX.
     * </p><p>
     * In the latter case, the HAL may round the matrix to account
     * for precision issues; the final rounded matrix should be
     * reported back in this matrix result metadata.
     * </p>
     */
    public static final Key<Rational[]> COLOR_CORRECTION_TRANSFORM =
            new Key<Rational[]>("android.colorCorrection.transform", Rational[].class);

    /**
     * <p>
     * Gains applying to Bayer color channels for
     * white-balance
     * </p>
     * <p>
     * The 4-channel white-balance gains are defined in
     * the order of [R G_even G_odd B], where G_even is the gain
     * for green pixels on even rows of the output, and G_odd
     * is the gain for greenpixels on the odd rows. if a HAL
     * does not support a separate gain for even/odd green channels,
     * it should use the G_even value,and write G_odd equal to
     * G_even in the output result metadata.
     * </p><p>
     * This array is either set by HAL when the request
     * android.colorCorrection.mode is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * android.colorCorrection.mode is TRANSFORM_MATRIX.
     * </p><p>
     * The ouput should be the gains actually applied by the HAL to
     * the current frame.
     * </p>
     */
    public static final Key<float[]> COLOR_CORRECTION_GAINS =
            new Key<float[]>("android.colorCorrection.gains", float[].class);

    /**
     * <p>
     * The ID sent with the latest
     * CAMERA2_TRIGGER_PRECAPTURE_METERING call
     * </p>
     * <p>
     * Must be 0 if no
     * CAMERA2_TRIGGER_PRECAPTURE_METERING trigger received yet
     * by HAL. Always updated even if AE algorithm ignores the
     * trigger
     * </p>
     *
     * @hide
     */
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_ID =
            new Key<Integer>("android.control.aePrecaptureId", int.class);

    /**
     * <p>
     * List of areas to use for
     * metering
     * </p>
     * <p>
     * Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined inclusive of the
     * specified coordinates.
     * </p><p>
     * The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * (android.sensor.info.activeArraySize.width - 1,
     * android.sensor.info.activeArraySize.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.
     * </p><p>
     * If all regions have 0 weight, then no specific metering area
     * needs to be used by the HAL. If the metering region is
     * outside the current android.scaler.cropRegion, the HAL
     * should ignore the sections outside the region and output the
     * used sections in the frame metadata
     * </p>
     */
    public static final Key<int[]> CONTROL_AE_REGIONS =
            new Key<int[]>("android.control.aeRegions", int[].class);

    /**
     * <p>
     * Current state of AE algorithm
     * </p>
     * <p>
     * Whenever the AE algorithm state changes, a
     * MSG_AUTOEXPOSURE notification must be send if a
     * notification callback is registered.
     * </p>
     * @see #CONTROL_AE_STATE_INACTIVE
     * @see #CONTROL_AE_STATE_SEARCHING
     * @see #CONTROL_AE_STATE_CONVERGED
     * @see #CONTROL_AE_STATE_LOCKED
     * @see #CONTROL_AE_STATE_FLASH_REQUIRED
     * @see #CONTROL_AE_STATE_PRECAPTURE
     */
    public static final Key<Integer> CONTROL_AE_STATE =
            new Key<Integer>("android.control.aeState", int.class);

    /**
     * <p>
     * Whether AF is currently enabled, and what
     * mode it is set to
     * </p>
     * @see #CONTROL_AF_MODE_OFF
     * @see #CONTROL_AF_MODE_AUTO
     * @see #CONTROL_AF_MODE_MACRO
     * @see #CONTROL_AF_MODE_CONTINUOUS_VIDEO
     * @see #CONTROL_AF_MODE_CONTINUOUS_PICTURE
     * @see #CONTROL_AF_MODE_EDOF
     */
    public static final Key<Integer> CONTROL_AF_MODE =
            new Key<Integer>("android.control.afMode", int.class);

    /**
     * <p>
     * List of areas to use for focus
     * estimation
     * </p>
     * <p>
     * Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined inclusive of the
     * specified coordinates.
     * </p><p>
     * The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * (android.sensor.info.activeArraySize.width - 1,
     * android.sensor.info.activeArraySize.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.
     * </p><p>
     * If all regions have 0 weight, then no specific focus area
     * needs to be used by the HAL. If the focusing region is
     * outside the current android.scaler.cropRegion, the HAL
     * should ignore the sections outside the region and output the
     * used sections in the frame metadata
     * </p>
     */
    public static final Key<int[]> CONTROL_AF_REGIONS =
            new Key<int[]>("android.control.afRegions", int[].class);

    /**
     * <p>
     * Current state of AF algorithm
     * </p>
     * <p>
     * Whenever the AF algorithm state changes, a
     * MSG_AUTOFOCUS notification must be send if a notification
     * callback is registered.
     * </p>
     * @see #CONTROL_AF_STATE_INACTIVE
     * @see #CONTROL_AF_STATE_PASSIVE_SCAN
     * @see #CONTROL_AF_STATE_PASSIVE_FOCUSED
     * @see #CONTROL_AF_STATE_ACTIVE_SCAN
     * @see #CONTROL_AF_STATE_FOCUSED_LOCKED
     * @see #CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
     * @see #CONTROL_AF_STATE_PASSIVE_UNFOCUSED
     */
    public static final Key<Integer> CONTROL_AF_STATE =
            new Key<Integer>("android.control.afState", int.class);

    /**
     * <p>
     * The ID sent with the latest
     * CAMERA2_TRIGGER_AUTOFOCUS call
     * </p>
     * <p>
     * Must be 0 if no CAMERA2_TRIGGER_AUTOFOCUS trigger
     * received yet by HAL. Always updated even if AF algorithm
     * ignores the trigger
     * </p>
     *
     * @hide
     */
    public static final Key<Integer> CONTROL_AF_TRIGGER_ID =
            new Key<Integer>("android.control.afTriggerId", int.class);

    /**
     * <p>
     * Whether AWB is currently setting the color
     * transform fields, and what its illumination target
     * is
     * </p>
     * <p>
     * [BC - AWB lock,AWB modes]
     * </p>
     * @see #CONTROL_AWB_MODE_OFF
     * @see #CONTROL_AWB_MODE_AUTO
     * @see #CONTROL_AWB_MODE_INCANDESCENT
     * @see #CONTROL_AWB_MODE_FLUORESCENT
     * @see #CONTROL_AWB_MODE_WARM_FLUORESCENT
     * @see #CONTROL_AWB_MODE_DAYLIGHT
     * @see #CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
     * @see #CONTROL_AWB_MODE_TWILIGHT
     * @see #CONTROL_AWB_MODE_SHADE
     */
    public static final Key<Integer> CONTROL_AWB_MODE =
            new Key<Integer>("android.control.awbMode", int.class);

    /**
     * <p>
     * List of areas to use for illuminant
     * estimation
     * </p>
     * <p>
     * Only used in AUTO mode.
     * </p><p>
     * Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined inclusive of the
     * specified coordinates.
     * </p><p>
     * The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * (android.sensor.info.activeArraySize.width - 1,
     * android.sensor.info.activeArraySize.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.
     * </p><p>
     * If all regions have 0 weight, then no specific metering area
     * needs to be used by the HAL. If the metering region is
     * outside the current android.scaler.cropRegion, the HAL
     * should ignore the sections outside the region and output the
     * used sections in the frame metadata
     * </p>
     */
    public static final Key<int[]> CONTROL_AWB_REGIONS =
            new Key<int[]>("android.control.awbRegions", int[].class);

    /**
     * <p>
     * Current state of AWB algorithm
     * </p>
     * <p>
     * Whenever the AWB algorithm state changes, a
     * MSG_AUTOWHITEBALANCE notification must be send if a
     * notification callback is registered.
     * </p>
     * @see #CONTROL_AWB_STATE_INACTIVE
     * @see #CONTROL_AWB_STATE_SEARCHING
     * @see #CONTROL_AWB_STATE_CONVERGED
     * @see #CONTROL_AWB_STATE_LOCKED
     */
    public static final Key<Integer> CONTROL_AWB_STATE =
            new Key<Integer>("android.control.awbState", int.class);

    /**
     * <p>
     * Overall mode of 3A control
     * routines
     * </p>
     * @see #CONTROL_MODE_OFF
     * @see #CONTROL_MODE_AUTO
     * @see #CONTROL_MODE_USE_SCENE_MODE
     */
    public static final Key<Integer> CONTROL_MODE =
            new Key<Integer>("android.control.mode", int.class);

    /**
     * <p>
     * Operation mode for edge
     * enhancement
     * </p>
     * @see #EDGE_MODE_OFF
     * @see #EDGE_MODE_FAST
     * @see #EDGE_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> EDGE_MODE =
            new Key<Integer>("android.edge.mode", int.class);

    /**
     * <p>
     * Select flash operation mode
     * </p>
     * @see #FLASH_MODE_OFF
     * @see #FLASH_MODE_SINGLE
     * @see #FLASH_MODE_TORCH
     */
    public static final Key<Integer> FLASH_MODE =
            new Key<Integer>("android.flash.mode", int.class);

    /**
     * <p>
     * Current state of the flash
     * unit
     * </p>
     * @see #FLASH_STATE_UNAVAILABLE
     * @see #FLASH_STATE_CHARGING
     * @see #FLASH_STATE_READY
     * @see #FLASH_STATE_FIRED
     */
    public static final Key<Integer> FLASH_STATE =
            new Key<Integer>("android.flash.state", int.class);

    /**
     * <p>
     * GPS coordinates to include in output JPEG
     * EXIF
     * </p>
     */
    public static final Key<double[]> JPEG_GPS_COORDINATES =
            new Key<double[]>("android.jpeg.gpsCoordinates", double[].class);

    /**
     * <p>
     * 32 characters describing GPS algorithm to
     * include in EXIF
     * </p>
     */
    public static final Key<String> JPEG_GPS_PROCESSING_METHOD =
            new Key<String>("android.jpeg.gpsProcessingMethod", String.class);

    /**
     * <p>
     * Time GPS fix was made to include in
     * EXIF
     * </p>
     */
    public static final Key<Long> JPEG_GPS_TIMESTAMP =
            new Key<Long>("android.jpeg.gpsTimestamp", long.class);

    /**
     * <p>
     * Orientation of JPEG image to
     * write
     * </p>
     */
    public static final Key<Integer> JPEG_ORIENTATION =
            new Key<Integer>("android.jpeg.orientation", int.class);

    /**
     * <p>
     * Compression quality of the final JPEG
     * image
     * </p>
     * <p>
     * 85-95 is typical usage range
     * </p>
     */
    public static final Key<Byte> JPEG_QUALITY =
            new Key<Byte>("android.jpeg.quality", byte.class);

    /**
     * <p>
     * Compression quality of JPEG
     * thumbnail
     * </p>
     */
    public static final Key<Byte> JPEG_THUMBNAIL_QUALITY =
            new Key<Byte>("android.jpeg.thumbnailQuality", byte.class);

    /**
     * <p>
     * Resolution of embedded JPEG
     * thumbnail
     * </p>
     */
    public static final Key<android.hardware.camera2.Size> JPEG_THUMBNAIL_SIZE =
            new Key<android.hardware.camera2.Size>("android.jpeg.thumbnailSize", android.hardware.camera2.Size.class);

    /**
     * <p>
     * Size of the lens aperture
     * </p>
     * <p>
     * Will not be supported on most devices. Can only
     * pick from supported list
     * </p>
     */
    public static final Key<Float> LENS_APERTURE =
            new Key<Float>("android.lens.aperture", float.class);

    /**
     * <p>
     * State of lens neutral density
     * filter(s)
     * </p>
     * <p>
     * Will not be supported on most devices. Can only
     * pick from supported list
     * </p>
     */
    public static final Key<Float> LENS_FILTER_DENSITY =
            new Key<Float>("android.lens.filterDensity", float.class);

    /**
     * <p>
     * Lens optical zoom setting
     * </p>
     * <p>
     * Will not be supported on most devices.
     * </p>
     */
    public static final Key<Float> LENS_FOCAL_LENGTH =
            new Key<Float>("android.lens.focalLength", float.class);

    /**
     * <p>
     * Distance to plane of sharpest focus,
     * measured from frontmost surface of the lens
     * </p>
     * <p>
     * Should be zero for fixed-focus cameras
     * </p>
     */
    public static final Key<Float> LENS_FOCUS_DISTANCE =
            new Key<Float>("android.lens.focusDistance", float.class);

    /**
     * <p>
     * The range of scene distances that are in
     * sharp focus (depth of field)
     * </p>
     * <p>
     * If variable focus not supported, can still report
     * fixed depth of field range
     * </p>
     */
    public static final Key<float[]> LENS_FOCUS_RANGE =
            new Key<float[]>("android.lens.focusRange", float[].class);

    /**
     * <p>
     * Whether optical image stabilization is
     * enabled.
     * </p>
     * <p>
     * Will not be supported on most devices.
     * </p>
     * @see #LENS_OPTICAL_STABILIZATION_MODE_OFF
     * @see #LENS_OPTICAL_STABILIZATION_MODE_ON
     */
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE =
            new Key<Integer>("android.lens.opticalStabilizationMode", int.class);

    /**
     * <p>
     * Current lens status
     * </p>
     * @see #LENS_STATE_STATIONARY
     * @see #LENS_STATE_MOVING
     */
    public static final Key<Integer> LENS_STATE =
            new Key<Integer>("android.lens.state", int.class);

    /**
     * <p>
     * Mode of operation for the noise reduction
     * algorithm
     * </p>
     * @see #NOISE_REDUCTION_MODE_OFF
     * @see #NOISE_REDUCTION_MODE_FAST
     * @see #NOISE_REDUCTION_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> NOISE_REDUCTION_MODE =
            new Key<Integer>("android.noiseReduction.mode", int.class);

    /**
     * <p>
     * Whether a result given to the framework is the
     * final one for the capture, or only a partial that contains a
     * subset of the full set of dynamic metadata
     * values.
     * </p>
     * <p>
     * The entries in the result metadata buffers for a
     * single capture may not overlap, except for this entry. The
     * FINAL buffers must retain FIFO ordering relative to the
     * requests that generate them, so the FINAL buffer for frame 3 must
     * always be sent to the framework after the FINAL buffer for frame 2, and
     * before the FINAL buffer for frame 4. PARTIAL buffers may be returned
     * in any order relative to other frames, but all PARTIAL buffers for a given
     * capture must arrive before the FINAL buffer for that capture. This entry may
     * only be used by the HAL if quirks.usePartialResult is set to 1.
     * </p>
     *
     * <b>Optional</b> - This value may be null on some devices.
     *
     * @hide
     */
    public static final Key<Boolean> QUIRKS_PARTIAL_RESULT =
            new Key<Boolean>("android.quirks.partialResult", boolean.class);

    /**
     * <p>
     * A frame counter set by the framework. This value monotonically
     * increases with every new result (that is, each new result has a unique
     * frameCount value).
     * </p>
     * <p>
     * Reset on release()
     * </p>
     */
    public static final Key<Integer> REQUEST_FRAME_COUNT =
            new Key<Integer>("android.request.frameCount", int.class);

    /**
     * <p>
     * An application-specified ID for the current
     * request. Must be maintained unchanged in output
     * frame
     * </p>
     *
     * @hide
     */
    public static final Key<Integer> REQUEST_ID =
            new Key<Integer>("android.request.id", int.class);

    /**
     * <p>
     * (x, y, width, height).
     * </p><p>
     * A rectangle with the top-level corner of (x,y) and size
     * (width, height). The region of the sensor that is used for
     * output. Each stream must use this rectangle to produce its
     * output, cropping to a smaller region if necessary to
     * maintain the stream's aspect ratio.
     * </p><p>
     * HAL2.x uses only (x, y, width)
     * </p>
     * <p>
     * Any additional per-stream cropping must be done to
     * maximize the final pixel area of the stream.
     * </p><p>
     * For example, if the crop region is set to a 4:3 aspect
     * ratio, then 4:3 streams should use the exact crop
     * region. 16:9 streams should further crop vertically
     * (letterbox).
     * </p><p>
     * Conversely, if the crop region is set to a 16:9, then 4:3
     * outputs should crop horizontally (pillarbox), and 16:9
     * streams should match exactly. These additional crops must
     * be centered within the crop region.
     * </p><p>
     * The output streams must maintain square pixels at all
     * times, no matter what the relative aspect ratios of the
     * crop region and the stream are.  Negative values for
     * corner are allowed for raw output if full pixel array is
     * larger than active pixel array. Width and height may be
     * rounded to nearest larger supportable width, especially
     * for raw output, where only a few fixed scales may be
     * possible. The width and height of the crop region cannot
     * be set to be smaller than floor( activeArraySize.width /
     * android.scaler.maxDigitalZoom ) and floor(
     * activeArraySize.height / android.scaler.maxDigitalZoom),
     * respectively.
     * </p>
     */
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION =
            new Key<android.graphics.Rect>("android.scaler.cropRegion", android.graphics.Rect.class);

    /**
     * <p>
     * Duration each pixel is exposed to
     * light.
     * </p><p>
     * If the sensor can't expose this exact duration, it should shorten the
     * duration exposed to the nearest possible value (rather than expose longer).
     * </p>
     * <p>
     * 1/10000 - 30 sec range. No bulb mode
     * </p>
     */
    public static final Key<Long> SENSOR_EXPOSURE_TIME =
            new Key<Long>("android.sensor.exposureTime", long.class);

    /**
     * <p>
     * Duration from start of frame exposure to
     * start of next frame exposure
     * </p>
     * <p>
     * Exposure time has priority, so duration is set to
     * max(duration, exposure time + overhead)
     * </p>
     */
    public static final Key<Long> SENSOR_FRAME_DURATION =
            new Key<Long>("android.sensor.frameDuration", long.class);

    /**
     * <p>
     * Gain applied to image data. Must be
     * implemented through analog gain only if set to values
     * below 'maximum analog sensitivity'.
     * </p><p>
     * If the sensor can't apply this exact gain, it should lessen the
     * gain to the nearest possible value (rather than gain more).
     * </p>
     * <p>
     * ISO 12232:2006 REI method
     * </p>
     */
    public static final Key<Integer> SENSOR_SENSITIVITY =
            new Key<Integer>("android.sensor.sensitivity", int.class);

    /**
     * <p>
     * Time at start of exposure of first
     * row
     * </p>
     * <p>
     * Monotonic, should be synced to other timestamps in
     * system
     * </p>
     */
    public static final Key<Long> SENSOR_TIMESTAMP =
            new Key<Long>("android.sensor.timestamp", long.class);

    /**
     * <p>
     * The temperature of the sensor, sampled at the time
     * exposure began for this frame.
     * </p><p>
     * The thermal diode being queried should be inside the sensor PCB, or
     * somewhere close to it.
     * </p>
     *
     * <b>Optional</b> - This value may be null on some devices.
     *
     * <b>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL}</b> -
     * Present on all devices that report being FULL level hardware devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL HARDWARE_LEVEL} key.
     */
    public static final Key<Float> SENSOR_TEMPERATURE =
            new Key<Float>("android.sensor.temperature", float.class);

    /**
     * <p>
     * State of the face detector
     * unit
     * </p>
     * <p>
     * Whether face detection is enabled, and whether it
     * should output just the basic fields or the full set of
     * fields. Value must be one of the
     * android.statistics.info.availableFaceDetectModes.
     * </p>
     * @see #STATISTICS_FACE_DETECT_MODE_OFF
     * @see #STATISTICS_FACE_DETECT_MODE_SIMPLE
     * @see #STATISTICS_FACE_DETECT_MODE_FULL
     */
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE =
            new Key<Integer>("android.statistics.faceDetectMode", int.class);

    /**
     * <p>
     * List of unique IDs for detected
     * faces
     * </p>
     * <p>
     * Only available if faceDetectMode == FULL
     * </p>
     */
    public static final Key<int[]> STATISTICS_FACE_IDS =
            new Key<int[]>("android.statistics.faceIds", int[].class);

    /**
     * <p>
     * List of landmarks for detected
     * faces
     * </p>
     * <p>
     * Only available if faceDetectMode == FULL
     * </p>
     */
    public static final Key<int[]> STATISTICS_FACE_LANDMARKS =
            new Key<int[]>("android.statistics.faceLandmarks", int[].class);

    /**
     * <p>
     * List of the bounding rectangles for detected
     * faces
     * </p>
     * <p>
     * Only available if faceDetectMode != OFF
     * </p>
     */
    public static final Key<android.graphics.Rect[]> STATISTICS_FACE_RECTANGLES =
            new Key<android.graphics.Rect[]>("android.statistics.faceRectangles", android.graphics.Rect[].class);

    /**
     * <p>
     * List of the face confidence scores for
     * detected faces
     * </p>
     * <p>
     * Only available if faceDetectMode != OFF. The value should be
     * meaningful (for example, setting 100 at all times is illegal).
     * </p>
     */
    public static final Key<byte[]> STATISTICS_FACE_SCORES =
            new Key<byte[]>("android.statistics.faceScores", byte[].class);

    /**
     * <p>
     * A low-resolution map of lens shading, per
     * color channel
     * </p>
     * <p>
     * Assume bilinear interpolation of map. The least
     * shaded section of the image should have a gain factor
     * of 1; all other sections should have gains above 1.
     * the map should be on the order of 30-40 rows, and
     * must be smaller than 64x64.
     * </p><p>
     * When android.colorCorrection.mode = TRANSFORM_MATRIX, the map
     * must take into account the colorCorrection settings.
     * </p>
     */
    public static final Key<float[]> STATISTICS_LENS_SHADING_MAP =
            new Key<float[]>("android.statistics.lensShadingMap", float[].class);

    /**
     * <p>
     * The best-fit color channel gains calculated
     * by the HAL's statistics units for the current output frame
     * </p>
     * <p>
     * This may be different than the gains used for this frame,
     * since statistics processing on data from a new frame
     * typically completes after the transform has already been
     * applied to that frame.
     * </p><p>
     * The 4 channel gains are defined in Bayer domain,
     * see android.colorCorrection.gains for details.
     * </p><p>
     * This value should always be calculated by the AWB block,
     * regardless of the android.control.* current values.
     * </p>
     */
    public static final Key<float[]> STATISTICS_PREDICTED_COLOR_GAINS =
            new Key<float[]>("android.statistics.predictedColorGains", float[].class);

    /**
     * <p>
     * The best-fit color transform matrix estimate
     * calculated by the HAL's statistics units for the current
     * output frame
     * </p>
     * <p>
     * The HAL must provide the estimate from its
     * statistics unit on the white balance transforms to use
     * for the next frame. These are the values the HAL believes
     * are the best fit for the current output frame. This may
     * be different than the transform used for this frame, since
     * statistics processing on data from a new frame typically
     * completes after the transform has already been applied to
     * that frame.
     * </p><p>
     * These estimates must be provided for all frames, even if
     * capture settings and color transforms are set by the application.
     * </p><p>
     * This value should always be calculated by the AWB block,
     * regardless of the android.control.* current values.
     * </p>
     */
    public static final Key<Rational[]> STATISTICS_PREDICTED_COLOR_TRANSFORM =
            new Key<Rational[]>("android.statistics.predictedColorTransform", Rational[].class);

    /**
     * <p>
     * The HAL estimated scene illumination lighting
     * frequency
     * </p>
     * <p>
     * Report NONE if there doesn't appear to be flickering
     * illumination
     * </p>
     * @see #STATISTICS_SCENE_FLICKER_NONE
     * @see #STATISTICS_SCENE_FLICKER_50HZ
     * @see #STATISTICS_SCENE_FLICKER_60HZ
     */
    public static final Key<Integer> STATISTICS_SCENE_FLICKER =
            new Key<Integer>("android.statistics.sceneFlicker", int.class);

    /**
     * <p>
     * Table mapping blue input values to output
     * values
     * </p>
     * <p>
     * Tonemapping / contrast / gamma curve for the blue
     * channel, to use when android.tonemap.mode is CONTRAST_CURVE.
     * </p><p>
     * See android.tonemap.curveRed for more details.
     * </p>
     */
    public static final Key<float[]> TONEMAP_CURVE_BLUE =
            new Key<float[]>("android.tonemap.curveBlue", float[].class);

    /**
     * <p>
     * Table mapping green input values to output
     * values
     * </p>
     * <p>
     * Tonemapping / contrast / gamma curve for the green
     * channel, to use when android.tonemap.mode is CONTRAST_CURVE.
     * </p><p>
     * See android.tonemap.curveRed for more details.
     * </p>
     */
    public static final Key<float[]> TONEMAP_CURVE_GREEN =
            new Key<float[]>("android.tonemap.curveGreen", float[].class);

    /**
     * <p>
     * Table mapping red input values to output
     * values
     * </p>
     * <p>
     * Tonemapping / contrast / gamma curve for the red
     * channel, to use when android.tonemap.mode is CONTRAST_CURVE.
     * </p><p>
     * Since the input and output ranges may vary depending on
     * the camera pipeline, the input and output pixel values
     * are represented by normalized floating-point values
     * between 0 and 1, with 0 == black and 1 == white.
     * </p><p>
     * The curve should be linearly interpolated between the
     * defined points. The points will be listed in increasing
     * order of P_IN. For example, if the array is: [0.0, 0.0,
     * 0.3, 0.5, 1.0, 1.0], then the input->output mapping
     * for a few sample points would be: 0 -> 0, 0.15 ->
     * 0.25, 0.3 -> 0.5, 0.5 -> 0.64
     * </p>
     */
    public static final Key<float[]> TONEMAP_CURVE_RED =
            new Key<float[]>("android.tonemap.curveRed", float[].class);

    /**
     * @see #TONEMAP_MODE_CONTRAST_CURVE
     * @see #TONEMAP_MODE_FAST
     * @see #TONEMAP_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> TONEMAP_MODE =
            new Key<Integer>("android.tonemap.mode", int.class);

    /**
     * <p>
     * This LED is nominally used to indicate to the user
     * that the camera is powered on and may be streaming images back to the
     * Application Processor. In certain rare circumstances, the OS may
     * disable this when video is processed locally and not transmitted to
     * any untrusted applications.
     * </p><p>
     * In particular, the LED *must* always be on when the data could be
     * transmitted off the device. The LED *should* always be on whenever
     * data is stored locally on the device.
     * </p><p>
     * The LED *may* be off if a trusted application is using the data that
     * doesn't violate the above rules.
     * </p>
     *
     * @hide
     */
    public static final Key<Boolean> LED_TRANSMIT =
            new Key<Boolean>("android.led.transmit", boolean.class);

    /**
     * <p>
     * Whether black-level compensation is locked
     * to its current values, or is free to vary
     * </p>
     * <p>
     * When set to ON, the values used for black-level
     * compensation must not change until the lock is set to
     * OFF
     * </p><p>
     * Since changes to certain capture parameters (such as
     * exposure time) may require resetting of black level
     * compensation, the HAL must report whether setting the
     * black level lock was successful in the output result
     * metadata.
     * </p><p>
     * The black level locking must happen at the sensor, and not at the ISP.
     * If for some reason black level locking is no longer legal (for example,
     * the analog gain has changed, which forces black levels to be
     * recalculated), then the HAL is free to override this request (and it
     * must report 'OFF' when this does happen) until the next time locking
     * is legal again.
     * </p>
     */
    public static final Key<Boolean> BLACK_LEVEL_LOCK =
            new Key<Boolean>("android.blackLevel.lock", boolean.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/

    /**
     * <p>
     * List of the {@link Face Faces} detected through camera face detection
     * in this result.
     * </p>
     * <p>
     * Only available if {@link #STATISTICS_FACE_DETECT_MODE} {@code !=}
     * {@link CameraMetadata#STATISTICS_FACE_DETECT_MODE_OFF OFF}.
     * </p>
     *
     * @see Face
     */
    public static final Key<Face[]> STATISTICS_FACES =
            new Key<Face[]>("android.statistics.faces", Face[].class);
}
