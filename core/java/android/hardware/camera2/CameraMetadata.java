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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.ExtensionKey;
import android.hardware.camera2.impl.PublicKey;
import android.hardware.camera2.impl.SyntheticKey;
import android.util.Log;

import com.android.internal.camera.flags.Flags;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The base class for camera controls and information.
 *
 * <p>
 * This class defines the basic key/value map used for querying for camera
 * characteristics or capture results, and for setting camera request
 * parameters.
 * </p>
 *
 * <p>
 * All instances of CameraMetadata are immutable. Beginning with API level 32, the list of keys
 * returned by {@link #getKeys()} may change depending on the state of the device, as may the
 * values returned by any key with {@code #get} throughout the lifetime of the object. For
 * information on whether a specific value is fixed, see the documentation for its key.
 * </p>
 *
 * @see CameraDevice
 * @see CameraManager
 * @see CameraCharacteristics
 **/
public abstract class CameraMetadata<TKey> {

    private static final String TAG = "CameraMetadataAb";
    private static final boolean DEBUG = false;
    private CameraMetadataNative mNativeInstance = null;

    /**
     * Set a camera metadata field to a value. The field definitions can be
     * found in {@link CameraCharacteristics}, {@link CaptureResult}, and
     * {@link CaptureRequest}.
     *
     * @param key The metadata field to write.
     * @param value The value to set the field to, which must be of a matching
     * type to the key.
     *
     * @hide
     */
    protected CameraMetadata() {
    }

    /**
     * Get a camera metadata field value.
     *
     * <p>The field definitions can be
     * found in {@link CameraCharacteristics}, {@link CaptureResult}, and
     * {@link CaptureRequest}.</p>
     *
     * <p>Querying the value for the same key more than once will return a value
     * which is equal to the previous queried value.</p>
     *
     * @throws IllegalArgumentException if the key was not valid
     *
     * @param key The metadata field to read.
     * @return The value of that key, or {@code null} if the field is not set.
     *
     * @hide
     */
     protected abstract <T> T getProtected(TKey key);

    /**
     * @hide
     */
    protected void setNativeInstance(CameraMetadataNative nativeInstance) {
        mNativeInstance = nativeInstance;
    }

    /**
     * Retrieves the native std::shared_ptr<CameraMetadata*>* as a Java long.
     * Returns 0 if mNativeInstance is null.
     *
     * @hide
     */
    @UnsupportedAppUsage(publicAlternatives = "This method is exposed for native "
                        + "{@code ACameraMetadata_fromCameraMetadata} in {@code libcamera2ndk}.")
    public long getNativeMetadataPtr() {
        if (mNativeInstance == null) {
            return 0;
        } else {
            return mNativeInstance.getMetadataPtr();
        }
    }

    /**
     * Retrieves the CameraMetadataNative instance.
     *
     * @hide
     */
    public CameraMetadataNative getNativeMetadata() {
        return mNativeInstance;
    }

    /**
     * @hide
     */
    protected abstract Class<TKey> getKeyClass();

    /**
     * Returns a list of the keys contained in this map.
     *
     * <p>The list returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>All values retrieved by a key from this list with {@code #get} are guaranteed to be
     * non-{@code null}. Each key is only listed once in the list. The order of the keys
     * is undefined.</p>
     *
     * @return List of the keys contained in this map.
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public List<TKey> getKeys() {
        Class<CameraMetadata<TKey>> thisClass = (Class<CameraMetadata<TKey>>) getClass();
        return Collections.unmodifiableList(
                getKeys(thisClass, getKeyClass(), this, /*filterTags*/null,
                    /*includeSynthetic*/ true));
    }

    /**
     * Return a list of all the Key<?> that are declared as a field inside of the class
     * {@code type}.
     *
     * <p>
     * Optionally, if {@code instance} is not null, then filter out any keys with null values.
     * </p>
     *
     * <p>
     * Optionally, if {@code filterTags} is not {@code null}, then filter out any keys
     * whose native {@code tag} is not in {@code filterTags}. The {@code filterTags} array will be
     * sorted as a side effect.
     * {@code includeSynthetic} Includes public synthetic fields by default.
     * </p>
     */
     /*package*/ @SuppressWarnings("unchecked")
    <TKey> ArrayList<TKey> getKeys(
             Class<?> type, Class<TKey> keyClass,
             CameraMetadata<TKey> instance,
             int[] filterTags, boolean includeSynthetic) {

        if (DEBUG) Log.v(TAG, "getKeysStatic for " + type);

        // TotalCaptureResult does not have any of the keys on it, use CaptureResult instead
        if (type.equals(TotalCaptureResult.class)) {
            type = CaptureResult.class;
        }

        if (filterTags != null) {
            Arrays.sort(filterTags);
        }

        ArrayList<TKey> keyList = new ArrayList<TKey>();

        Field[] fields = type.getDeclaredFields();
        for (Field field : fields) {
            // Filter for Keys that are public
            if (field.getType().isAssignableFrom(keyClass) &&
                    (field.getModifiers() & Modifier.PUBLIC) != 0) {

                TKey key;
                try {
                    key = (TKey) field.get(instance);
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Can't get IllegalAccessException", e);
                } catch (IllegalArgumentException e) {
                    throw new AssertionError("Can't get IllegalArgumentException", e);
                }

                if (instance == null || instance.getProtected(key) != null) {
                    if (shouldKeyBeAdded(key, field, filterTags, includeSynthetic)) {
                        keyList.add(key);

                        if (DEBUG) {
                            Log.v(TAG, "getKeysStatic - key was added - " + key);
                        }
                    } else if (DEBUG) {
                        Log.v(TAG, "getKeysStatic - key was filtered - " + key);
                    }
                }
            }
        }

        if (null == mNativeInstance) {
            return keyList;
        }

        ArrayList<TKey> vendorKeys = mNativeInstance.getAllVendorKeys(keyClass);

        if (vendorKeys != null) {
            for (TKey k : vendorKeys) {
                String keyName;
                long vendorId;
                if (k instanceof CaptureRequest.Key<?>) {
                    keyName = ((CaptureRequest.Key<?>) k).getName();
                    vendorId = ((CaptureRequest.Key<?>) k).getVendorId();
                } else if (k instanceof CaptureResult.Key<?>) {
                    keyName = ((CaptureResult.Key<?>) k).getName();
                    vendorId = ((CaptureResult.Key<?>) k).getVendorId();
                } else if (k instanceof CameraCharacteristics.Key<?>) {
                    keyName = ((CameraCharacteristics.Key<?>) k).getName();
                    vendorId = ((CameraCharacteristics.Key<?>) k).getVendorId();
                } else {
                    continue;
                }


                if (filterTags != null && Arrays.binarySearch(filterTags,
                        CameraMetadataNative.getTag(keyName, vendorId)) < 0) {
                    // ignore vendor keys not in filterTags
                    continue;
                }
                if (instance == null || instance.getProtected(k) != null)  {
                    keyList.add(k);
                }

            }
        }

        return keyList;
    }

    @SuppressWarnings("rawtypes")
    private static <TKey> boolean shouldKeyBeAdded(TKey key, Field field, int[] filterTags,
            boolean includeSynthetic) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }

        CameraMetadataNative.Key nativeKey;

        /*
         * Get the native key from the public api key
         */
        if (key instanceof CameraCharacteristics.Key) {
            nativeKey = ((CameraCharacteristics.Key)key).getNativeKey();
        } else if (key instanceof CaptureResult.Key) {
            nativeKey = ((CaptureResult.Key)key).getNativeKey();
        } else if (key instanceof CaptureRequest.Key) {
            nativeKey = ((CaptureRequest.Key)key).getNativeKey();
        } else {
            // Reject fields that aren't a key
            throw new IllegalArgumentException("key type must be that of a metadata key");
        }

        if (field.getAnnotation(PublicKey.class) == null
                && field.getAnnotation(ExtensionKey.class) == null) {
            // Never expose @hide keys to the API user unless they are
            // marked as @ExtensionKey, as these keys are publicly accessible via
            // the extension key classes.
            return false;
        }

        // No filtering necessary
        if (filterTags == null) {
            return true;
        }

        if (field.getAnnotation(SyntheticKey.class) != null) {
            // This key is synthetic, so calling #getTag will throw IAE

            return includeSynthetic;
        }

        /*
         * Regular key: look up it's native tag and see if it's in filterTags
         */

        int keyTag = nativeKey.getTag();

        // non-negative result is returned iff the value is in the array
        return Arrays.binarySearch(filterTags, keyTag) >= 0;
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The enum values below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/

    //
    // Enumeration values for CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
    //

    /**
     * <p>The lens focus distance is not accurate, and the units used for
     * {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance} do not correspond to any physical units.</p>
     * <p>Setting the lens to the same focus distance on separate occasions may
     * result in a different real focus distance, depending on factors such
     * as the orientation of the device, the age of the focusing mechanism,
     * and the device temperature. The focus distance value will still be
     * in the range of <code>[0, {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance}]</code>, where 0
     * represents the farthest focus.</p>
     *
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     */
    public static final int LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED = 0;

    /**
     * <p>The lens focus distance is measured in diopters.</p>
     * <p>However, setting the lens to the same focus distance
     * on separate occasions may result in a different real
     * focus distance, depending on factors such as the
     * orientation of the device, the age of the focusing
     * mechanism, and the device temperature.</p>
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     */
    public static final int LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE = 1;

    /**
     * <p>The lens focus distance is measured in diopters, and
     * is calibrated.</p>
     * <p>The lens mechanism is calibrated so that setting the
     * same focus distance is repeatable on multiple
     * occasions with good accuracy, and the focus distance
     * corresponds to the real physical distance to the plane
     * of best focus.</p>
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     */
    public static final int LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED = 2;

    //
    // Enumeration values for CameraCharacteristics#LENS_FACING
    //

    /**
     * <p>The camera device faces the same direction as the device's screen.</p>
     * @see CameraCharacteristics#LENS_FACING
     */
    public static final int LENS_FACING_FRONT = 0;

    /**
     * <p>The camera device faces the opposite direction as the device's screen.</p>
     * @see CameraCharacteristics#LENS_FACING
     */
    public static final int LENS_FACING_BACK = 1;

    /**
     * <p>The camera device is an external camera, and has no fixed facing relative to the
     * device's screen.</p>
     * @see CameraCharacteristics#LENS_FACING
     */
    public static final int LENS_FACING_EXTERNAL = 2;

    //
    // Enumeration values for CameraCharacteristics#LENS_POSE_REFERENCE
    //

    /**
     * <p>The value of {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation} is relative to the optical center of
     * the largest camera device facing the same direction as this camera.</p>
     * <p>This is the default value for API levels before Android P.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     */
    public static final int LENS_POSE_REFERENCE_PRIMARY_CAMERA = 0;

    /**
     * <p>The value of {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation} is relative to the position of the
     * primary gyroscope of this Android device.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     */
    public static final int LENS_POSE_REFERENCE_GYROSCOPE = 1;

    /**
     * <p>The camera device cannot represent the values of {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}
     * and {@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} accurately enough. One such example is a camera device
     * on the cover of a foldable phone: in order to measure the pose translation and rotation,
     * some kind of hinge position sensor would be needed.</p>
     * <p>The value of {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation} must be all zeros, and
     * {@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} must be values matching its default facing.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     */
    public static final int LENS_POSE_REFERENCE_UNDEFINED = 2;

    /**
     * <p>The value of {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation} is relative to the origin of the
     * automotive sensor coordinate system, which is at the center of the rear axle.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     */
    public static final int LENS_POSE_REFERENCE_AUTOMOTIVE = 3;

    //
    // Enumeration values for CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
    //

    /**
     * <p>The minimal set of capabilities that every camera
     * device (regardless of {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel})
     * supports.</p>
     * <p>This capability is listed by all normal devices, and
     * indicates that the camera device has a feature set
     * that's comparable to the baseline requirements for the
     * older android.hardware.Camera API.</p>
     * <p>Devices with the DEPTH_OUTPUT capability might not list this
     * capability, indicating that they support only depth measurement,
     * not standard color output.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE = 0;

    /**
     * <p>The camera device can be manually controlled (3A algorithms such
     * as auto-exposure, and auto-focus can be bypassed).
     * The camera device supports basic manual control of the sensor image
     * acquisition related stages. This means the following controls are
     * guaranteed to be supported:</p>
     * <ul>
     * <li>Manual frame duration control<ul>
     * <li>{@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION android.sensor.info.maxFrameDuration}</li>
     * </ul>
     * </li>
     * <li>Manual exposure control<ul>
     * <li>{@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE android.sensor.info.exposureTimeRange}</li>
     * </ul>
     * </li>
     * <li>Manual sensitivity control<ul>
     * <li>{@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}</li>
     * </ul>
     * </li>
     * <li>Manual lens control (if the lens is adjustable)<ul>
     * <li>android.lens.*</li>
     * </ul>
     * </li>
     * <li>Manual flash control (if a flash unit is present)<ul>
     * <li>android.flash.*</li>
     * </ul>
     * </li>
     * <li>Manual black level locking<ul>
     * <li>{@link CaptureRequest#BLACK_LEVEL_LOCK android.blackLevel.lock}</li>
     * </ul>
     * </li>
     * <li>Auto exposure lock<ul>
     * <li>{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock}</li>
     * </ul>
     * </li>
     * </ul>
     * <p>If any of the above 3A algorithms are enabled, then the camera
     * device will accurately report the values applied by 3A in the
     * result.</p>
     * <p>A given camera device may also support additional manual sensor controls,
     * but this capability only covers the above list of controls.</p>
     * <p>If this is supported, {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap} will
     * additionally return a min frame duration that is greater than
     * zero for each supported size-format combination.</p>
     * <p>For camera devices with LOGICAL_MULTI_CAMERA capability, when the underlying active
     * physical camera switches, exposureTime, sensitivity, and lens properties may change
     * even if AE/AF is locked. However, the overall auto exposure and auto focus experience
     * for users will be consistent. Refer to LOGICAL_MULTI_CAMERA capability for details.</p>
     *
     * @see CaptureRequest#BLACK_LEVEL_LOCK
     * @see CaptureRequest#CONTROL_AE_LOCK
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE
     * @see CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION
     * @see CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR = 1;

    /**
     * <p>The camera device post-processing stages can be manually controlled.
     * The camera device supports basic manual control of the image post-processing
     * stages. This means the following controls are guaranteed to be supported:</p>
     * <ul>
     * <li>
     * <p>Manual tonemap control</p>
     * <ul>
     * <li>{@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}</li>
     * <li>{@link CaptureRequest#TONEMAP_MODE android.tonemap.mode}</li>
     * <li>{@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}</li>
     * <li>{@link CaptureRequest#TONEMAP_GAMMA android.tonemap.gamma}</li>
     * <li>{@link CaptureRequest#TONEMAP_PRESET_CURVE android.tonemap.presetCurve}</li>
     * </ul>
     * </li>
     * <li>
     * <p>Manual white balance control</p>
     * <ul>
     * <li>{@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}</li>
     * <li>{@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}</li>
     * </ul>
     * </li>
     * <li>Manual lens shading map control<ul>
     * <li>{@link CaptureRequest#SHADING_MODE android.shading.mode}</li>
     * <li>{@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode}</li>
     * <li>android.statistics.lensShadingMap</li>
     * <li>android.lens.info.shadingMapSize</li>
     * </ul>
     * </li>
     * <li>Manual aberration correction control (if aberration correction is supported)<ul>
     * <li>{@link CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE android.colorCorrection.aberrationMode}</li>
     * <li>{@link CameraCharacteristics#COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES android.colorCorrection.availableAberrationModes}</li>
     * </ul>
     * </li>
     * <li>Auto white balance lock<ul>
     * <li>{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock}</li>
     * </ul>
     * </li>
     * </ul>
     * <p>If auto white balance is enabled, then the camera device
     * will accurately report the values applied by AWB in the result.</p>
     * <p>A given camera device may also support additional post-processing
     * controls, but this capability only covers the above list of controls.</p>
     * <p>For camera devices with LOGICAL_MULTI_CAMERA capability, when underlying active
     * physical camera switches, tonemap, white balance, and shading map may change even if
     * awb is locked. However, the overall post-processing experience for users will be
     * consistent. Refer to LOGICAL_MULTI_CAMERA capability for details.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
     * @see CameraCharacteristics#COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_LOCK
     * @see CaptureRequest#SHADING_MODE
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     * @see CaptureRequest#TONEMAP_CURVE
     * @see CaptureRequest#TONEMAP_GAMMA
     * @see CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS
     * @see CaptureRequest#TONEMAP_MODE
     * @see CaptureRequest#TONEMAP_PRESET_CURVE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING = 2;

    /**
     * <p>The camera device supports outputting RAW buffers and
     * metadata for interpreting them.</p>
     * <p>Devices supporting the RAW capability allow both for
     * saving DNG files, and for direct application processing of
     * raw sensor images.</p>
     * <ul>
     * <li>RAW_SENSOR is supported as an output format.</li>
     * <li>The maximum available resolution for RAW_SENSOR streams
     *   will match either the value in
     *   {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize} or
     *   {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.</li>
     * <li>All DNG-related optional metadata entries are provided
     *   by the camera device.</li>
     * </ul>
     *
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_RAW = 3;

    /**
     * <p>The camera device supports the Zero Shutter Lag reprocessing use case.</p>
     * <ul>
     * <li>One input stream is supported, that is, <code>{@link CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS android.request.maxNumInputStreams} == 1</code>.</li>
     * <li>{@link android.graphics.ImageFormat#PRIVATE } is supported as an output/input format,
     *   that is, {@link android.graphics.ImageFormat#PRIVATE } is included in the lists of
     *   formats returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats } and {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputFormats }.</li>
     * <li>{@link android.hardware.camera2.params.StreamConfigurationMap#getValidOutputFormatsForInput }
     *   returns non-empty int[] for each supported input format returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats }.</li>
     * <li>Each size returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputSizes getInputSizes(ImageFormat.PRIVATE)} is also included in {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes getOutputSizes(ImageFormat.PRIVATE)}</li>
     * <li>Using {@link android.graphics.ImageFormat#PRIVATE } does not cause a frame rate drop
     *   relative to the sensor's maximum capture rate (at that resolution).</li>
     * <li>{@link android.graphics.ImageFormat#PRIVATE } will be reprocessable into both
     *   {@link android.graphics.ImageFormat#YUV_420_888 } and
     *   {@link android.graphics.ImageFormat#JPEG } formats.</li>
     * <li>For a MONOCHROME camera supporting Y8 format, {@link android.graphics.ImageFormat#PRIVATE } will be reprocessable into
     *   {@link android.graphics.ImageFormat#Y8 }.</li>
     * <li>The maximum available resolution for PRIVATE streams
     *   (both input/output) will match the maximum available
     *   resolution of JPEG streams.</li>
     * <li>Static metadata {@link CameraCharacteristics#REPROCESS_MAX_CAPTURE_STALL android.reprocess.maxCaptureStall}.</li>
     * <li>Only below controls are effective for reprocessing requests and
     *   will be present in capture results, other controls in reprocess
     *   requests will be ignored by the camera device.<ul>
     * <li>android.jpeg.*</li>
     * <li>{@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode}</li>
     * <li>{@link CaptureRequest#EDGE_MODE android.edge.mode}</li>
     * </ul>
     * </li>
     * <li>{@link CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES android.noiseReduction.availableNoiseReductionModes} and
     *   {@link CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES android.edge.availableEdgeModes} will both list ZERO_SHUTTER_LAG as a supported mode.</li>
     * </ul>
     *
     * @see CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES
     * @see CaptureRequest#EDGE_MODE
     * @see CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     * @see CameraCharacteristics#REPROCESS_MAX_CAPTURE_STALL
     * @see CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING = 4;

    /**
     * <p>The camera device supports accurately reporting the sensor settings for many of
     * the sensor controls while the built-in 3A algorithm is running.  This allows
     * reporting of sensor settings even when these settings cannot be manually changed.</p>
     * <p>The values reported for the following controls are guaranteed to be available
     * in the CaptureResult, including when 3A is enabled:</p>
     * <ul>
     * <li>Exposure control<ul>
     * <li>{@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime}</li>
     * </ul>
     * </li>
     * <li>Sensitivity control<ul>
     * <li>{@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}</li>
     * </ul>
     * </li>
     * <li>Lens controls (if the lens is adjustable)<ul>
     * <li>{@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance}</li>
     * <li>{@link CaptureRequest#LENS_APERTURE android.lens.aperture}</li>
     * </ul>
     * </li>
     * </ul>
     * <p>This capability is a subset of the MANUAL_SENSOR control capability, and will
     * always be included if the MANUAL_SENSOR capability is available.</p>
     *
     * @see CaptureRequest#LENS_APERTURE
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS = 5;

    /**
     * <p>The camera device supports capturing high-resolution images at &gt;= 20 frames per
     * second, in at least the uncompressed YUV format, when post-processing settings are
     * set to FAST. Additionally, all image resolutions less than 24 megapixels can be
     * captured at &gt;= 10 frames per second. Here, 'high resolution' means at least 8
     * megapixels, or the maximum resolution of the device, whichever is smaller.</p>
     * <p>More specifically, this means that a size matching the camera device's active array
     * size is listed as a supported size for the {@link android.graphics.ImageFormat#YUV_420_888 } format in either {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes } or {@link android.hardware.camera2.params.StreamConfigurationMap#getHighResolutionOutputSizes },
     * with a minimum frame duration for that format and size of either &lt;= 1/20 s, or
     * &lt;= 1/10 s if the image size is less than 24 megapixels, respectively; and
     * the {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES android.control.aeAvailableTargetFpsRanges} entry lists at least one FPS range
     * where the minimum FPS is &gt;= 1 / minimumFrameDuration for the maximum-size
     * YUV_420_888 format.  If that maximum size is listed in {@link android.hardware.camera2.params.StreamConfigurationMap#getHighResolutionOutputSizes },
     * then the list of resolutions for YUV_420_888 from {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes } contains at
     * least one resolution &gt;= 8 megapixels, with a minimum frame duration of &lt;= 1/20
     * s.</p>
     * <p>If the device supports the {@link android.graphics.ImageFormat#RAW10 }, {@link android.graphics.ImageFormat#RAW12 }, {@link android.graphics.ImageFormat#Y8 }, then those can also be
     * captured at the same rate as the maximum-size YUV_420_888 resolution is.</p>
     * <p>If the device supports the PRIVATE_REPROCESSING capability, then the same guarantees
     * as for the YUV_420_888 format also apply to the {@link android.graphics.ImageFormat#PRIVATE } format.</p>
     * <p>In addition, the {@link CameraCharacteristics#SYNC_MAX_LATENCY android.sync.maxLatency} field is guaranteed to have a value between 0
     * and 4, inclusive. {@link CameraCharacteristics#CONTROL_AE_LOCK_AVAILABLE android.control.aeLockAvailable} and {@link CameraCharacteristics#CONTROL_AWB_LOCK_AVAILABLE android.control.awbLockAvailable}
     * are also guaranteed to be <code>true</code> so burst capture with these two locks ON yields
     * consistent image output.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * @see CameraCharacteristics#CONTROL_AE_LOCK_AVAILABLE
     * @see CameraCharacteristics#CONTROL_AWB_LOCK_AVAILABLE
     * @see CameraCharacteristics#SYNC_MAX_LATENCY
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE = 6;

    /**
     * <p>The camera device supports the YUV_420_888 reprocessing use case, similar as
     * PRIVATE_REPROCESSING, This capability requires the camera device to support the
     * following:</p>
     * <ul>
     * <li>One input stream is supported, that is, <code>{@link CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS android.request.maxNumInputStreams} == 1</code>.</li>
     * <li>{@link android.graphics.ImageFormat#YUV_420_888 } is supported as an output/input
     *   format, that is, YUV_420_888 is included in the lists of formats returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats } and {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputFormats }.</li>
     * <li>{@link android.hardware.camera2.params.StreamConfigurationMap#getValidOutputFormatsForInput }
     *   returns non-empty int[] for each supported input format returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats }.</li>
     * <li>Each size returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputSizes getInputSizes(YUV_420_888)} is also included in {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes getOutputSizes(YUV_420_888)}</li>
     * <li>Using {@link android.graphics.ImageFormat#YUV_420_888 } does not cause a frame rate
     *   drop relative to the sensor's maximum capture rate (at that resolution).</li>
     * <li>{@link android.graphics.ImageFormat#YUV_420_888 } will be reprocessable into both
     *   {@link android.graphics.ImageFormat#YUV_420_888 } and {@link android.graphics.ImageFormat#JPEG } formats.</li>
     * <li>The maximum available resolution for {@link android.graphics.ImageFormat#YUV_420_888 } streams (both input/output) will match the
     *   maximum available resolution of {@link android.graphics.ImageFormat#JPEG } streams.</li>
     * <li>For a MONOCHROME camera with Y8 format support, all the requirements mentioned
     *   above for YUV_420_888 apply for Y8 format as well.</li>
     * <li>Static metadata {@link CameraCharacteristics#REPROCESS_MAX_CAPTURE_STALL android.reprocess.maxCaptureStall}.</li>
     * <li>Only the below controls are effective for reprocessing requests and will be present
     *   in capture results. The reprocess requests are from the original capture results
     *   that are associated with the intermediate {@link android.graphics.ImageFormat#YUV_420_888 } output buffers.  All other controls in the
     *   reprocess requests will be ignored by the camera device.<ul>
     * <li>android.jpeg.*</li>
     * <li>{@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode}</li>
     * <li>{@link CaptureRequest#EDGE_MODE android.edge.mode}</li>
     * <li>{@link CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR android.reprocess.effectiveExposureFactor}</li>
     * </ul>
     * </li>
     * <li>{@link CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES android.noiseReduction.availableNoiseReductionModes} and
     *   {@link CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES android.edge.availableEdgeModes} will both list ZERO_SHUTTER_LAG as a supported mode.</li>
     * </ul>
     *
     * @see CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES
     * @see CaptureRequest#EDGE_MODE
     * @see CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     * @see CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR
     * @see CameraCharacteristics#REPROCESS_MAX_CAPTURE_STALL
     * @see CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING = 7;

    /**
     * <p>The camera device can produce depth measurements from its field of view.</p>
     * <p>This capability requires the camera device to support the following:</p>
     * <ul>
     * <li>{@link android.graphics.ImageFormat#DEPTH16 } is supported as
     *   an output format.</li>
     * <li>{@link android.graphics.ImageFormat#DEPTH_POINT_CLOUD } is
     *   optionally supported as an output format.</li>
     * <li>This camera device, and all camera devices with the same {@link CameraCharacteristics#LENS_FACING android.lens.facing}, will
     *   list the following calibration metadata entries in both {@link android.hardware.camera2.CameraCharacteristics }
     *   and {@link android.hardware.camera2.CaptureResult }:<ul>
     * <li>{@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}</li>
     * <li>{@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation}</li>
     * <li>{@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration}</li>
     * <li>{@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion}</li>
     * </ul>
     * </li>
     * <li>The {@link CameraCharacteristics#DEPTH_DEPTH_IS_EXCLUSIVE android.depth.depthIsExclusive} entry is listed by this device.</li>
     * <li>As of Android P, the {@link CameraCharacteristics#LENS_POSE_REFERENCE android.lens.poseReference} entry is listed by this device.</li>
     * <li>A LIMITED camera with only the DEPTH_OUTPUT capability does not have to support
     *   normal YUV_420_888, Y8, JPEG, and PRIV-format outputs. It only has to support the
     *   DEPTH16 format.</li>
     * </ul>
     * <p>Generally, depth output operates at a slower frame rate than standard color capture,
     * so the DEPTH16 and DEPTH_POINT_CLOUD formats will commonly have a stall duration that
     * should be accounted for (see {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputStallDuration }).
     * On a device that supports both depth and color-based output, to enable smooth preview,
     * using a repeating burst is recommended, where a depth-output target is only included
     * once every N frames, where N is the ratio between preview output rate and depth output
     * rate, including depth stall time.</p>
     *
     * @see CameraCharacteristics#DEPTH_DEPTH_IS_EXCLUSIVE
     * @see CameraCharacteristics#LENS_DISTORTION
     * @see CameraCharacteristics#LENS_FACING
     * @see CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT = 8;

    /**
     * <p>The device supports constrained high speed video recording (frame rate &gt;=120fps) use
     * case. The camera device will support high speed capture session created by {@link android.hardware.camera2.CameraDevice#createConstrainedHighSpeedCaptureSession }, which
     * only accepts high speed request lists created by {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList }.</p>
     * <p>A camera device can still support high speed video streaming by advertising the high
     * speed FPS ranges in {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES android.control.aeAvailableTargetFpsRanges}. For this case, all
     * normal capture request per frame control and synchronization requirements will apply
     * to the high speed fps ranges, the same as all other fps ranges. This capability
     * describes the capability of a specialized operating mode with many limitations (see
     * below), which is only targeted at high speed video recording.</p>
     * <p>The supported high speed video sizes and fps ranges are specified in {@link android.hardware.camera2.params.StreamConfigurationMap#getHighSpeedVideoFpsRanges }.
     * To get desired output frame rates, the application is only allowed to select video
     * size and FPS range combinations provided by {@link android.hardware.camera2.params.StreamConfigurationMap#getHighSpeedVideoSizes }.  The
     * fps range can be controlled via {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange}.</p>
     * <p>In this capability, the camera device will override aeMode, awbMode, and afMode to
     * ON, AUTO, and CONTINUOUS_VIDEO, respectively. All post-processing block mode
     * controls will be overridden to be FAST. Therefore, no manual control of capture
     * and post-processing parameters is possible. All other controls operate the
     * same as when {@link CaptureRequest#CONTROL_MODE android.control.mode} == AUTO. This means that all other
     * android.control.* fields continue to work, such as</p>
     * <ul>
     * <li>{@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION android.control.aeExposureCompensation}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock}</li>
     * <li>{@link CaptureRequest#CONTROL_EFFECT_MODE android.control.effectMode}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}</li>
     * <li>{@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}</li>
     * </ul>
     * <p>Outside of android.control.*, the following controls will work:</p>
     * <ul>
     * <li>{@link CaptureRequest#FLASH_MODE android.flash.mode} (TORCH mode only, automatic flash for still capture will not
     * work since aeMode is ON)</li>
     * <li>{@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode} (if it is supported)</li>
     * <li>{@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}</li>
     * <li>{@link CaptureRequest#STATISTICS_FACE_DETECT_MODE android.statistics.faceDetectMode} (if it is supported)</li>
     * </ul>
     * <p>For high speed recording use case, the actual maximum supported frame rate may
     * be lower than what camera can output, depending on the destination Surfaces for
     * the image data. For example, if the destination surface is from video encoder,
     * the application need check if the video encoder is capable of supporting the
     * high frame rate for a given video size, or it will end up with lower recording
     * frame rate. If the destination surface is from preview window, the actual preview frame
     * rate will be bounded by the screen refresh rate.</p>
     * <p>The camera device will only support up to 2 high speed simultaneous output surfaces
     * (preview and recording surfaces) in this mode. Above controls will be effective only
     * if all of below conditions are true:</p>
     * <ul>
     * <li>The application creates a camera capture session with no more than 2 surfaces via
     * {@link android.hardware.camera2.CameraDevice#createConstrainedHighSpeedCaptureSession }. The
     * targeted surfaces must be preview surface (either from {@link android.view.SurfaceView } or {@link android.graphics.SurfaceTexture }) or recording
     * surface(either from {@link android.media.MediaRecorder#getSurface } or {@link android.media.MediaCodec#createInputSurface }).</li>
     * <li>The stream sizes are selected from the sizes reported by
     * {@link android.hardware.camera2.params.StreamConfigurationMap#getHighSpeedVideoSizes }.</li>
     * <li>The FPS ranges are selected from {@link android.hardware.camera2.params.StreamConfigurationMap#getHighSpeedVideoFpsRanges }.</li>
     * </ul>
     * <p>When above conditions are NOT satisfied,
     * {@link android.hardware.camera2.CameraDevice#createConstrainedHighSpeedCaptureSession }
     * will fail.</p>
     * <p>Switching to a FPS range that has different maximum FPS may trigger some camera device
     * reconfigurations, which may introduce extra latency. It is recommended that
     * the application avoids unnecessary maximum target FPS changes as much as possible
     * during high speed streaming.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * @see CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION
     * @see CaptureRequest#CONTROL_AE_LOCK
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureRequest#CONTROL_AWB_LOCK
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#FLASH_MODE
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO = 9;

    /**
     * <p>The camera device supports the MOTION_TRACKING value for
     * {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent}, which limits maximum exposure time to 20 ms.</p>
     * <p>This limits the motion blur of capture images, resulting in better image tracking
     * results for use cases such as image stabilization or augmented reality.</p>
     *
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING = 10;

    /**
     * <p>The camera device is a logical camera backed by two or more physical cameras.</p>
     * <p>In API level 28, the physical cameras must also be exposed to the application via
     * {@link android.hardware.camera2.CameraManager#getCameraIdList }.</p>
     * <p>Starting from API level 29:</p>
     * <ul>
     * <li>Some or all physical cameras may not be independently exposed to the application,
     * in which case the physical camera IDs will not be available in
     * {@link android.hardware.camera2.CameraManager#getCameraIdList }. But the
     * application can still query the physical cameras' characteristics by calling
     * {@link android.hardware.camera2.CameraManager#getCameraCharacteristics }.</li>
     * <li>If a physical camera is hidden from camera ID list, the mandatory stream
     * combinations for that physical camera must be supported through the logical camera
     * using physical streams. One exception is that in API level 30, a physical camera
     * may become unavailable via
     * {@link CameraManager.AvailabilityCallback#onPhysicalCameraUnavailable }
     * callback.</li>
     * </ul>
     * <p>Combinations of logical and physical streams, or physical streams from different
     * physical cameras are not guaranteed. However, if the camera device supports
     * {@link CameraDevice#isSessionConfigurationSupported },
     * application must be able to query whether a stream combination involving physical
     * streams is supported by calling
     * {@link CameraDevice#isSessionConfigurationSupported }.</p>
     * <p>Camera application shouldn't assume that there are at most 1 rear camera and 1 front
     * camera in the system. For an application that switches between front and back cameras,
     * the recommendation is to switch between the first rear camera and the first front
     * camera in the list of supported camera devices.</p>
     * <p>This capability requires the camera device to support the following:</p>
     * <ul>
     * <li>The IDs of underlying physical cameras are returned via
     *   {@link android.hardware.camera2.CameraCharacteristics#getPhysicalCameraIds }.</li>
     * <li>This camera device must list static metadata
     *   {@link CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE android.logicalMultiCamera.sensorSyncType} in
     *   {@link android.hardware.camera2.CameraCharacteristics }.</li>
     * <li>The underlying physical cameras' static metadata must list the following entries,
     *   so that the application can correlate pixels from the physical streams:<ul>
     * <li>{@link CameraCharacteristics#LENS_POSE_REFERENCE android.lens.poseReference}</li>
     * <li>{@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation}</li>
     * <li>{@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}</li>
     * <li>{@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration}</li>
     * <li>{@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion}</li>
     * </ul>
     * </li>
     * <li>The SENSOR_INFO_TIMESTAMP_SOURCE of the logical device and physical devices must be
     *   the same.</li>
     * <li>The logical camera must be LIMITED or higher device.</li>
     * </ul>
     * <p>A logical camera device's dynamic metadata may contain
     * {@link CaptureResult#LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID android.logicalMultiCamera.activePhysicalId} to notify the application of the current
     * active physical camera Id. An active physical camera is the physical camera from which
     * the logical camera's main image data outputs (YUV or RAW) and metadata come from.
     * In addition, this serves as an indication which physical camera is used to output to
     * a RAW stream, or in case only physical cameras support RAW, which physical RAW stream
     * the application should request.</p>
     * <p>Logical camera's static metadata tags below describe the default active physical
     * camera. An active physical camera is default if it's used when application directly
     * uses requests built from a template. All templates will default to the same active
     * physical camera.</p>
     * <ul>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT android.sensor.info.colorFilterArrangement}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE android.sensor.info.exposureTimeRange}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION android.sensor.info.maxFrameDuration}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE android.sensor.info.physicalSize}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL android.sensor.info.whiteLevel}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_LENS_SHADING_APPLIED android.sensor.info.lensShadingApplied}</li>
     * <li>{@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1}</li>
     * <li>{@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2 android.sensor.referenceIlluminant2}</li>
     * <li>{@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1 android.sensor.calibrationTransform1}</li>
     * <li>{@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM2 android.sensor.calibrationTransform2}</li>
     * <li>{@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM1 android.sensor.colorTransform1}</li>
     * <li>{@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM2 android.sensor.colorTransform2}</li>
     * <li>{@link CameraCharacteristics#SENSOR_FORWARD_MATRIX1 android.sensor.forwardMatrix1}</li>
     * <li>{@link CameraCharacteristics#SENSOR_FORWARD_MATRIX2 android.sensor.forwardMatrix2}</li>
     * <li>{@link CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN android.sensor.blackLevelPattern}</li>
     * <li>{@link CameraCharacteristics#SENSOR_MAX_ANALOG_SENSITIVITY android.sensor.maxAnalogSensitivity}</li>
     * <li>{@link CameraCharacteristics#SENSOR_OPTICAL_BLACK_REGIONS android.sensor.opticalBlackRegions}</li>
     * <li>{@link CameraCharacteristics#SENSOR_AVAILABLE_TEST_PATTERN_MODES android.sensor.availableTestPatternModes}</li>
     * <li>{@link CameraCharacteristics#LENS_INFO_HYPERFOCAL_DISTANCE android.lens.info.hyperfocalDistance}</li>
     * <li>{@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance}</li>
     * <li>{@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration}</li>
     * <li>{@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation}</li>
     * <li>{@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}</li>
     * <li>{@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration}</li>
     * <li>{@link CameraCharacteristics#LENS_POSE_REFERENCE android.lens.poseReference}</li>
     * <li>{@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion}</li>
     * </ul>
     * <p>The field of view of non-RAW physical streams must not be smaller than that of the
     * non-RAW logical streams, or the maximum field-of-view of the physical camera,
     * whichever is smaller. The application should check the physical capture result
     * metadata for how the physical streams are cropped or zoomed. More specifically, given
     * the physical camera result metadata, the effective horizontal field-of-view of the
     * physical camera is:</p>
     * <pre><code>fov = 2 * atan2(cropW * sensorW / (2 * zoomRatio * activeArrayW), focalLength)
     * </code></pre>
     * <p>where the equation parameters are the physical camera's crop region width, physical
     * sensor width, zoom ratio, active array width, and focal length respectively. Typically
     * the physical stream of active physical camera has the same field-of-view as the
     * logical streams. However, the same may not be true for physical streams from
     * non-active physical cameras. For example, if the logical camera has a wide-ultrawide
     * configuration where the wide lens is the default, when the crop region is set to the
     * logical camera's active array size, (and the zoom ratio set to 1.0 starting from
     * Android 11), a physical stream for the ultrawide camera may prefer outputting images
     * with larger field-of-view than that of the wide camera for better stereo matching
     * margin or more robust motion tracking. At the same time, the physical non-RAW streams'
     * field of view must not be smaller than the requested crop region and zoom ratio, as
     * long as it's within the physical lens' capability. For example, for a logical camera
     * with wide-tele lens configuration where the wide lens is the default, if the logical
     * camera's crop region is set to maximum size, and zoom ratio set to 1.0, the physical
     * stream for the tele lens will be configured to its maximum size crop region (no zoom).</p>
     * <p><em>Deprecated:</em> Prior to Android 11, the field of view of all non-RAW physical streams
     * cannot be larger than that of non-RAW logical streams. If the logical camera has a
     * wide-ultrawide lens configuration where the wide lens is the default, when the logical
     * camera's crop region is set to maximum size, the FOV of the physical streams for the
     * ultrawide lens will be the same as the logical stream, by making the crop region
     * smaller than its active array size to compensate for the smaller focal length.</p>
     * <p>For a logical camera, typically the underlying physical cameras have different RAW
     * capabilities (such as resolution or CFA pattern). There are two ways for the
     * application to capture RAW images from the logical camera:</p>
     * <ul>
     * <li>If the logical camera has RAW capability, the application can create and use RAW
     * streams in the same way as before. In case a RAW stream is configured, to maintain
     * backward compatibility, the camera device makes sure the default active physical
     * camera remains active and does not switch to other physical cameras. (One exception
     * is that, if the logical camera consists of identical image sensors and advertises
     * multiple focalLength due to different lenses, the camera device may generate RAW
     * images from different physical cameras based on the focalLength being set by the
     * application.) This backward-compatible approach usually results in loss of optical
     * zoom, to telephoto lens or to ultrawide lens.</li>
     * <li>Alternatively, if supported by the device,
     * {@link android.hardware.camera2.MultiResolutionImageReader }
     * can be used to capture RAW images from one of the underlying physical cameras (
     * depending on current zoom level). Because different physical cameras may have
     * different RAW characteristics, the application needs to use the characteristics
     * and result metadata of the active physical camera for the relevant RAW metadata.</li>
     * </ul>
     * <p>The capture request and result metadata tags required for backward compatible camera
     * functionalities will be solely based on the logical camera capability. On the other
     * hand, the use of manual capture controls (sensor or post-processing) with a
     * logical camera may result in unexpected behavior when the HAL decides to switch
     * between physical cameras with different characteristics under the hood. For example,
     * when the application manually sets exposure time and sensitivity while zooming in,
     * the brightness of the camera images may suddenly change because HAL switches from one
     * physical camera to the other.</p>
     *
     * @see CameraCharacteristics#LENS_DISTORTION
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     * @see CameraCharacteristics#LENS_INFO_HYPERFOCAL_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CaptureResult#LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID
     * @see CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE
     * @see CameraCharacteristics#SENSOR_AVAILABLE_TEST_PATTERN_MODES
     * @see CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN
     * @see CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1
     * @see CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM2
     * @see CameraCharacteristics#SENSOR_COLOR_TRANSFORM1
     * @see CameraCharacteristics#SENSOR_COLOR_TRANSFORM2
     * @see CameraCharacteristics#SENSOR_FORWARD_MATRIX1
     * @see CameraCharacteristics#SENSOR_FORWARD_MATRIX2
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     * @see CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE
     * @see CameraCharacteristics#SENSOR_INFO_LENS_SHADING_APPLIED
     * @see CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION
     * @see CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE
     * @see CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL
     * @see CameraCharacteristics#SENSOR_MAX_ANALOG_SENSITIVITY
     * @see CameraCharacteristics#SENSOR_OPTICAL_BLACK_REGIONS
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA = 11;

    /**
     * <p>The camera device is a monochrome camera that doesn't contain a color filter array,
     * and for YUV_420_888 stream, the pixel values on U and V planes are all 128.</p>
     * <p>A MONOCHROME camera must support the guaranteed stream combinations required for
     * its device level and capabilities. Additionally, if the monochrome camera device
     * supports Y8 format, all mandatory stream combination requirements related to {@link android.graphics.ImageFormat#YUV_420_888 YUV_420_888} apply
     * to {@link android.graphics.ImageFormat#Y8 Y8} as well. There are no
     * mandatory stream combination requirements with regard to
     * {@link android.graphics.ImageFormat#Y8 Y8} for Bayer camera devices.</p>
     * <p>Starting from Android Q, the SENSOR_INFO_COLOR_FILTER_ARRANGEMENT of a MONOCHROME
     * camera will be either MONO or NIR.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME = 12;

    /**
     * <p>The camera device is capable of writing image data into a region of memory
     * inaccessible to Android userspace or the Android kernel, and only accessible to
     * trusted execution environments (TEE).</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA = 13;

    /**
     * <p>The camera device is only accessible by Android's system components and privileged
     * applications. Processes need to have the android.permission.SYSTEM_CAMERA in
     * addition to android.permission.CAMERA in order to connect to this camera device.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA = 14;

    /**
     * <p>The camera device supports the OFFLINE_PROCESSING use case.</p>
     * <p>With OFFLINE_PROCESSING capability, the application can switch an ongoing
     * capture session to offline mode by calling the
     * CameraCaptureSession#switchToOffline method and specify streams to be kept in offline
     * mode. The camera will then stop currently active repeating requests, prepare for
     * some requests to go into offline mode, and return an offline session object. After
     * the switchToOffline call returns, the original capture session is in closed state as
     * if the CameraCaptureSession#close method has been called.
     * In the offline mode, all inflight requests will continue to be processed in the
     * background, and the application can immediately close the camera or create a new
     * capture session without losing those requests' output images and capture results.</p>
     * <p>While the camera device is processing offline requests, it
     * might not be able to support all stream configurations it can support
     * without offline requests. When that happens, the createCaptureSession
     * method call will fail. The following stream configurations are guaranteed to work
     * without hitting the resource busy exception:</p>
     * <ul>
     * <li>One ongoing offline session: target one output surface of YUV or
     * JPEG format, any resolution.</li>
     * <li>The active camera capture session:<ol>
     * <li>One preview surface (SurfaceView or SurfaceTexture) up to 1920 width</li>
     * <li>One YUV ImageReader surface up to 1920 width</li>
     * <li>One Jpeg ImageReader, any resolution: the camera device is
     *    allowed to slow down JPEG output speed by 50% if there is any ongoing offline
     *    session.</li>
     * <li>If the device supports PRIVATE_REPROCESSING, one pair of ImageWriter/ImageReader
     *    surfaces of private format, with the same resolution that is larger or equal to
     *    the JPEG ImageReader resolution above.</li>
     * </ol>
     * </li>
     * <li>Alternatively, the active camera session above can be replaced by an legacy
     * {@link android.hardware.Camera Camera} with the following parameter settings:<ol>
     * <li>Preview size up to 1920 width</li>
     * <li>Preview callback size up to 1920 width</li>
     * <li>Video size up to 1920 width</li>
     * <li>Picture size, any resolution: the camera device is
     *     allowed to slow down JPEG output speed by 50% if there is any ongoing offline
     *     session.</li>
     * </ol>
     * </li>
     * </ul>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING = 15;

    /**
     * <p>This camera device is capable of producing ultra high resolution images in
     * addition to the image sizes described in the
     * {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}.
     * It can operate in 'default' mode and 'max resolution' mode. It generally does this
     * by binning pixels in 'default' mode and not binning them in 'max resolution' mode.
     * <code>{@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}</code> describes the streams supported in 'default'
     * mode.
     * The stream configurations supported in 'max resolution' mode are described by
     * <code>{@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION android.scaler.streamConfigurationMapMaximumResolution}</code>.
     * The maximum resolution mode pixel array size of a camera device
     * (<code>{@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}</code>) with this capability,
     * will be at least 24 megapixels.</p>
     *
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR = 16;

    /**
     * <p>The device supports reprocessing from the <code>RAW_SENSOR</code> format with a bayer pattern
     * given by {@link CameraCharacteristics#SENSOR_INFO_BINNING_FACTOR android.sensor.info.binningFactor} (m x n group of pixels with the same
     * color filter) to a remosaiced regular bayer pattern.</p>
     * <p>This capability will only be present for devices with
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * capability. When
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR }
     * devices do not advertise this capability,
     * {@link android.graphics.ImageFormat#RAW_SENSOR } images will already have a
     * regular bayer pattern.</p>
     * <p>If a <code>RAW_SENSOR</code> stream is requested along with another non-RAW stream in a
     * {@link android.hardware.camera2.CaptureRequest } (if multiple streams are supported
     * when {@link CaptureRequest#SENSOR_PIXEL_MODE android.sensor.pixelMode} is set to
     * {@link android.hardware.camera2.CameraMetadata#SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION }),
     * the <code>RAW_SENSOR</code> stream will have a regular bayer pattern.</p>
     * <p>This capability requires the camera device to support the following :</p>
     * <ul>
     * <li>The {@link android.hardware.camera2.params.StreamConfigurationMap } mentioned below
     *   refers to the one, described by
     *   <code>{@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION android.scaler.streamConfigurationMapMaximumResolution}</code>.</li>
     * <li>One input stream is supported, that is, <code>{@link CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS android.request.maxNumInputStreams} == 1</code>.</li>
     * <li>{@link android.graphics.ImageFormat#RAW_SENSOR } is supported as an output/input
     *   format, that is, {@link android.graphics.ImageFormat#RAW_SENSOR } is included in the
     *   lists of formats returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats } and {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputFormats }.</li>
     * <li>{@link android.hardware.camera2.params.StreamConfigurationMap#getValidOutputFormatsForInput }
     *   returns non-empty int[] for each supported input format returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats }.</li>
     * <li>Each size returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputSizes getInputSizes(ImageFormat.RAW_SENSOR)} is also included in {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes getOutputSizes(ImageFormat.RAW_SENSOR)}</li>
     * <li>Using {@link android.graphics.ImageFormat#RAW_SENSOR } does not cause a frame rate
     *   drop relative to the sensor's maximum capture rate (at that resolution).</li>
     * <li>No CaptureRequest controls will be applicable when a request has an input target
     *   with {@link android.graphics.ImageFormat#RAW_SENSOR } format.</li>
     * </ul>
     *
     * @see CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
     * @see CameraCharacteristics#SENSOR_INFO_BINNING_FACTOR
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING = 17;

    /**
     * <p>The device supports one or more 10-bit camera outputs according to the dynamic range
     * profiles specified in
     * {@link android.hardware.camera2.params.DynamicRangeProfiles#getSupportedProfiles }.
     * They can be configured as part of the capture session initialization via
     * {@link android.hardware.camera2.params.OutputConfiguration#setDynamicRangeProfile }.
     * Cameras that enable this capability must also support the following:</p>
     * <ul>
     * <li>Profile {@link android.hardware.camera2.params.DynamicRangeProfiles#HLG10 }</li>
     * <li>All mandatory stream combinations for this specific capability as per
     *   <a href="https://developer.android.com/reference/android/hardware/camera2/CameraDevice#10-bit-output-additional-guaranteed-configurations">documentation</a></li>
     * <li>In case the device is not able to capture some combination of supported
     *   standard 8-bit and/or 10-bit dynamic range profiles within the same capture request,
     *   then those constraints must be listed in
     *   {@link android.hardware.camera2.params.DynamicRangeProfiles#getProfileCaptureRequestConstraints }</li>
     * <li>Recommended dynamic range profile listed in
     *   {@link android.hardware.camera2.CameraCharacteristics#REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE }.</li>
     * </ul>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT = 18;

    /**
     * <p>The camera device supports selecting a per-stream use case via
     * {@link android.hardware.camera2.params.OutputConfiguration#setStreamUseCase }
     * so that the device can optimize camera pipeline parameters such as tuning, sensor
     * mode, or ISP settings for a specific user scenario.
     * Some sample usages of this capability are:</p>
     * <ul>
     * <li>Distinguish high quality YUV captures from a regular YUV stream where
     *   the image quality may not be as good as the JPEG stream, or</li>
     * <li>Use one stream to serve multiple purposes: viewfinder, video recording and
     *   still capture. This is common with applications that wish to apply edits equally
     *   to preview, saved images, and saved videos.</li>
     * </ul>
     * <p>This capability requires the camera device to support the following
     * stream use cases:</p>
     * <ul>
     * <li>DEFAULT for backward compatibility where the application doesn't set
     *   a stream use case</li>
     * <li>PREVIEW for live viewfinder and in-app image analysis</li>
     * <li>STILL_CAPTURE for still photo capture</li>
     * <li>VIDEO_RECORD for recording video clips</li>
     * <li>PREVIEW_VIDEO_STILL for one single stream used for viewfinder, video
     *   recording, and still capture.</li>
     * <li>VIDEO_CALL for long running video calls</li>
     * </ul>
     * <p>{@link android.hardware.camera2.CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES }
     * lists all of the supported stream use cases.</p>
     * <p>Refer to the
     * <a href="https://developer.android.com/reference/android/hardware/camera2/CameraDevice#stream-use-case-capability-additional-guaranteed-configurations">guideline</a>
     * for the mandatory stream combinations involving stream use cases, which can also be
     * queried via {@link android.hardware.camera2.params.MandatoryStreamCombination }.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE = 19;

    /**
     * <p>The device supports querying the possible combinations of color spaces, image
     * formats, and dynamic range profiles supported by the camera and requesting a
     * particular color space for a session via
     * {@link android.hardware.camera2.params.SessionConfiguration#setColorSpace }.</p>
     * <p>Cameras that enable this capability may or may not also implement dynamic range
     * profiles. If they don't,
     * {@link android.hardware.camera2.params.ColorSpaceProfiles#getSupportedDynamicRangeProfiles }
     * will return only
     * {@link android.hardware.camera2.params.DynamicRangeProfiles#STANDARD } and
     * {@link android.hardware.camera2.params.ColorSpaceProfiles#getSupportedColorSpacesForDynamicRange }
     * will assume support of the
     * {@link android.hardware.camera2.params.DynamicRangeProfiles#STANDARD }
     * profile in all combinations of color spaces and image formats.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public static final int REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES = 20;

    //
    // Enumeration values for CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
    //

    /**
     * <p>8-bit SDR profile which is the default for all non 10-bit output capable devices.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_STANDARD = 0x1;

    /**
     * <p>10-bit pixel samples encoded using the Hybrid log-gamma transfer function.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_HLG10 = 0x2;

    /**
     * <p>10-bit pixel samples encoded using the SMPTE ST 2084 transfer function.
     * This profile utilizes internal static metadata to increase the quality
     * of the capture.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_HDR10 = 0x4;

    /**
     * <p>10-bit pixel samples encoded using the SMPTE ST 2084 transfer function.
     * In contrast to HDR10, this profile uses internal per-frame metadata
     * to further enhance the quality of the capture.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_HDR10_PLUS = 0x8;

    /**
     * <p>This is a camera mode for Dolby Vision capture optimized for a more scene
     * accurate capture. This would typically differ from what a specific device
     * might want to tune for a consumer optimized Dolby Vision general capture.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_REF = 0x10;

    /**
     * <p>This is the power optimized mode for 10-bit Dolby Vision HDR Reference Mode.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_REF_PO = 0x20;

    /**
     * <p>This is the camera mode for the default Dolby Vision capture mode for the
     * specific device. This would be tuned by each specific device for consumer
     * pleasing results that resonate with their particular audience. We expect
     * that each specific device would have a different look for their default
     * Dolby Vision capture.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_OEM = 0x40;

    /**
     * <p>This is the power optimized mode for 10-bit Dolby Vision HDR device specific
     * capture Mode.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_10B_HDR_OEM_PO = 0x80;

    /**
     * <p>This is the 8-bit version of the Dolby Vision reference capture mode optimized
     * for scene accuracy.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_REF = 0x100;

    /**
     * <p>This is the power optimized mode for 8-bit Dolby Vision HDR Reference Mode.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_REF_PO = 0x200;

    /**
     * <p>This is the 8-bit version of device specific tuned and optimized Dolby Vision
     * capture mode.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_OEM = 0x400;

    /**
     * <p>This is the power optimized mode for 8-bit Dolby Vision HDR device specific
     * capture Mode.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_DOLBY_VISION_8B_HDR_OEM_PO = 0x800;

    /**
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP_MAX = 0x1000;

    //
    // Enumeration values for CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP
    //

    /**
     * <p>Default value, when not explicitly specified. The Camera device will choose the color
     * space to employ.</p>
     * @see CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP
     * @hide
     */
    public static final int REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP_UNSPECIFIED = -1;

    //
    // Enumeration values for CameraCharacteristics#SCALER_CROPPING_TYPE
    //

    /**
     * <p>The camera device only supports centered crop regions.</p>
     * @see CameraCharacteristics#SCALER_CROPPING_TYPE
     */
    public static final int SCALER_CROPPING_TYPE_CENTER_ONLY = 0;

    /**
     * <p>The camera device supports arbitrarily chosen crop regions.</p>
     * @see CameraCharacteristics#SCALER_CROPPING_TYPE
     */
    public static final int SCALER_CROPPING_TYPE_FREEFORM = 1;

    //
    // Enumeration values for CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
    //

    /**
     * <p>Default stream use case.</p>
     * <p>This use case is the same as when the application doesn't set any use case for
     * the stream. The camera device uses the properties of the output target, such as
     * format, dataSpace, or surface class type, to optimize the image processing pipeline.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT = 0x0;

    /**
     * <p>Live stream shown to the user.</p>
     * <p>Optimized for performance and usability as a viewfinder, but not necessarily for
     * image quality. The output is not meant to be persisted as saved images or video.</p>
     * <p>No stall if android.control.* are set to FAST. There may be stall if
     * they are set to HIGH_QUALITY. This use case has the same behavior as the
     * default SurfaceView and SurfaceTexture targets. Additionally, this use case can be
     * used for in-app image analysis.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW = 0x1;

    /**
     * <p>Still photo capture.</p>
     * <p>Optimized for high-quality high-resolution capture, and not expected to maintain
     * preview-like frame rates.</p>
     * <p>The stream may have stalls regardless of whether android.control.* is HIGH_QUALITY.
     * This use case has the same behavior as the default JPEG and RAW related formats.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE = 0x2;

    /**
     * <p>Recording video clips.</p>
     * <p>Optimized for high-quality video capture, including high-quality image stabilization
     * if supported by the device and enabled by the application. As a result, may produce
     * output frames with a substantial lag from real time, to allow for highest-quality
     * stabilization or other processing. As such, such an output is not suitable for drawing
     * to screen directly, and is expected to be persisted to disk or similar for later
     * playback or processing. Only streams that set the VIDEO_RECORD use case are guaranteed
     * to have video stabilization applied when the video stabilization control is set
     * to ON, as opposed to PREVIEW_STABILIZATION.</p>
     * <p>This use case has the same behavior as the default MediaRecorder and MediaCodec
     * targets.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD = 0x3;

    /**
     * <p>One single stream used for combined purposes of preview, video, and still capture.</p>
     * <p>For such multi-purpose streams, the camera device aims to make the best tradeoff
     * between the individual use cases. For example, the STILL_CAPTURE use case by itself
     * may have stalls for achieving best image quality. But if combined with PREVIEW and
     * VIDEO_RECORD, the camera device needs to trade off the additional image processing
     * for speed so that preview and video recording aren't slowed down.</p>
     * <p>Similarly, VIDEO_RECORD may produce frames with a substantial lag, but
     * PREVIEW_VIDEO_STILL must have minimal output delay. This means that to enable video
     * stabilization with this use case, the device must support and the app must select the
     * PREVIEW_STABILIZATION mode for video stabilization.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL = 0x4;

    /**
     * <p>Long-running video call optimized for both power efficiency and video quality.</p>
     * <p>The camera sensor may run in a lower-resolution mode to reduce power consumption
     * at the cost of some image and digital zoom quality. Unlike VIDEO_RECORD, VIDEO_CALL
     * outputs are expected to work in dark conditions, so are usually accompanied with
     * variable frame rate settings to allow sufficient exposure time in low light.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL = 0x5;

    /**
     * <p>Cropped RAW stream when the client chooses to crop the field of view.</p>
     * <p>Certain types of image sensors can run in binned modes in order to improve signal to
     * noise ratio while capturing frames. However, at certain zoom levels and / or when
     * other scene conditions are deemed fit, the camera sub-system may choose to un-bin and
     * remosaic the sensor's output. This results in a RAW frame which is cropped in field
     * of view and yet has the same number of pixels as full field of view RAW, thereby
     * improving image detail.</p>
     * <p>The resultant field of view of the RAW stream will be greater than or equal to
     * croppable non-RAW streams. The effective crop region for this RAW stream will be
     * reflected in the CaptureResult key {@link CaptureResult#SCALER_RAW_CROP_REGION android.scaler.rawCropRegion}.</p>
     * <p>If this stream use case is set on a non-RAW stream, i.e. not one of :</p>
     * <ul>
     * <li>{@link android.graphics.ImageFormat#RAW_SENSOR RAW_SENSOR}</li>
     * <li>{@link android.graphics.ImageFormat#RAW10 RAW10}</li>
     * <li>{@link android.graphics.ImageFormat#RAW12 RAW12}</li>
     * </ul>
     * <p>session configuration is not guaranteed to succeed.</p>
     * <p>This stream use case may not be supported on some devices.</p>
     *
     * @see CaptureResult#SCALER_RAW_CROP_REGION
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW = 0x6;

    /**
     * <p>Vendor defined use cases. These depend on the vendor implementation.</p>
     * @see CameraCharacteristics#SCALER_AVAILABLE_STREAM_USE_CASES
     * @hide
     */
    public static final int SCALER_AVAILABLE_STREAM_USE_CASES_VENDOR_START = 0x10000;

    //
    // Enumeration values for CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
    //

    /**
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB = 0;

    /**
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG = 1;

    /**
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG = 2;

    /**
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR = 3;

    /**
     * <p>Sensor is not Bayer; output has 3 16-bit
     * values for each pixel, instead of just 1 16-bit value
     * per pixel.</p>
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB = 4;

    /**
     * <p>Sensor doesn't have any Bayer color filter.
     * Such sensor captures visible light in monochrome. The exact weighting and
     * wavelengths captured is not specified, but generally only includes the visible
     * frequencies. This value implies a MONOCHROME camera.</p>
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO = 5;

    /**
     * <p>Sensor has a near infrared filter capturing light with wavelength between
     * roughly 750nm and 1400nm, and the same filter covers the whole sensor array. This
     * value implies a MONOCHROME camera.</p>
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    public static final int SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR = 6;

    //
    // Enumeration values for CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE
    //

    /**
     * <p>Timestamps from {@link CaptureResult#SENSOR_TIMESTAMP android.sensor.timestamp} are in nanoseconds and monotonic, but can
     * not be compared to timestamps from other subsystems (e.g. accelerometer, gyro etc.),
     * or other instances of the same or different camera devices in the same system with
     * accuracy. However, the timestamps are roughly in the same timebase as
     * {@link android.os.SystemClock#uptimeMillis }.  The accuracy is sufficient for tasks
     * like A/V synchronization for video recording, at least, and the timestamps can be
     * directly used together with timestamps from the audio subsystem for that task.</p>
     * <p>Timestamps between streams and results for a single camera instance are comparable,
     * and the timestamps for all buffers and the result metadata generated by a single
     * capture are identical.</p>
     *
     * @see CaptureResult#SENSOR_TIMESTAMP
     * @see CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE
     */
    public static final int SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN = 0;

    /**
     * <p>Timestamps from {@link CaptureResult#SENSOR_TIMESTAMP android.sensor.timestamp} are in the same timebase as
     * {@link android.os.SystemClock#elapsedRealtimeNanos },
     * and they can be compared to other timestamps using that base.</p>
     * <p>When buffers from a REALTIME device are passed directly to a video encoder from the
     * camera, automatic compensation is done to account for differing timebases of the
     * audio and camera subsystems.  If the application is receiving buffers and then later
     * sending them to a video encoder or other application where they are compared with
     * audio subsystem timestamps or similar, this compensation is not present.  In those
     * cases, applications need to adjust the timestamps themselves.  Since {@link android.os.SystemClock#elapsedRealtimeNanos } and {@link android.os.SystemClock#uptimeMillis } only diverge while the device is asleep, an
     * offset between the two sources can be measured once per active session and applied
     * to timestamps for sufficient accuracy for A/V sync.</p>
     *
     * @see CaptureResult#SENSOR_TIMESTAMP
     * @see CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE
     */
    public static final int SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME = 1;

    //
    // Enumeration values for CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
    //

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT = 1;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT = 2;

    /**
     * <p>Incandescent light</p>
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN = 3;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_FLASH = 4;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER = 9;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER = 10;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_SHADE = 11;

    /**
     * <p>D 5700 - 7100K</p>
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT = 12;

    /**
     * <p>N 4600 - 5400K</p>
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT = 13;

    /**
     * <p>W 3900 - 4500K</p>
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT = 14;

    /**
     * <p>WW 3200 - 3700K</p>
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT = 15;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A = 17;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B = 18;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C = 19;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_D55 = 20;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_D65 = 21;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_D75 = 22;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_D50 = 23;

    /**
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    public static final int SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN = 24;

    //
    // Enumeration values for CameraCharacteristics#SENSOR_READOUT_TIMESTAMP
    //

    /**
     * <p>This camera device doesn't support readout timestamp and onReadoutStarted
     * callback.</p>
     * @see CameraCharacteristics#SENSOR_READOUT_TIMESTAMP
     */
    public static final int SENSOR_READOUT_TIMESTAMP_NOT_SUPPORTED = 0;

    /**
     * <p>This camera device supports the onReadoutStarted callback as well as outputting
     * readout timestamps. The readout timestamp is generated by the camera hardware and it
     * has the same accuracy and timing characteristics of the start-of-exposure time.</p>
     * @see CameraCharacteristics#SENSOR_READOUT_TIMESTAMP
     */
    public static final int SENSOR_READOUT_TIMESTAMP_HARDWARE = 1;

    //
    // Enumeration values for CameraCharacteristics#LED_AVAILABLE_LEDS
    //

    /**
     * <p>android.led.transmit control is used.</p>
     * @see CameraCharacteristics#LED_AVAILABLE_LEDS
     * @hide
     */
    public static final int LED_AVAILABLE_LEDS_TRANSMIT = 0;

    //
    // Enumeration values for CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
    //

    /**
     * <p>This camera device does not have enough capabilities to qualify as a <code>FULL</code> device or
     * better.</p>
     * <p>Only the stream configurations listed in the <code>LEGACY</code> and <code>LIMITED</code>
     * <a href="https://developer.android.com/reference/android/hardware/camera2/CameraDevice#limited-level-additional-guaranteed-configurations">tables</a>
     * in the documentation are guaranteed to be supported.</p>
     * <p>All <code>LIMITED</code> devices support the <code>BACKWARDS_COMPATIBLE</code> capability, indicating basic
     * support for color image capture. The only exception is that the device may
     * alternatively support only the <code>DEPTH_OUTPUT</code> capability, if it can only output depth
     * measurements and not color images.</p>
     * <p><code>LIMITED</code> devices and above require the use of {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}
     * to lock exposure metering (and calculate flash power, for cameras with flash) before
     * capturing a high-quality still image.</p>
     * <p>A <code>LIMITED</code> device that only lists the <code>BACKWARDS_COMPATIBLE</code> capability is only
     * required to support full-automatic operation and post-processing (<code>OFF</code> is not
     * supported for {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode}, {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}, or
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode})</p>
     * <p>Additional capabilities may optionally be supported by a <code>LIMITED</code>-level device, and
     * can be checked for in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED = 0;

    /**
     * <p>This camera device is capable of supporting advanced imaging applications.</p>
     * <p>The stream configurations listed in the <code>FULL</code>, <code>LEGACY</code> and <code>LIMITED</code>
     * <a href="https://developer.android.com/reference/android/hardware/camera2/CameraDevice#full-level-additional-guaranteed-configurations">tables</a>
     * in the documentation are guaranteed to be supported.</p>
     * <p>A <code>FULL</code> device will support below capabilities:</p>
     * <ul>
     * <li><code>BURST_CAPTURE</code> capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     *   <code>BURST_CAPTURE</code>)</li>
     * <li>Per frame control ({@link CameraCharacteristics#SYNC_MAX_LATENCY android.sync.maxLatency} <code>==</code> PER_FRAME_CONTROL)</li>
     * <li>Manual sensor control ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains <code>MANUAL_SENSOR</code>)</li>
     * <li>Manual post-processing control ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     *   <code>MANUAL_POST_PROCESSING</code>)</li>
     * <li>The required exposure time range defined in {@link CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE android.sensor.info.exposureTimeRange}</li>
     * <li>The required maxFrameDuration defined in {@link CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION android.sensor.info.maxFrameDuration}</li>
     * </ul>
     * <p>Note:
     * Pre-API level 23, FULL devices also supported arbitrary cropping region
     * ({@link CameraCharacteristics#SCALER_CROPPING_TYPE android.scaler.croppingType} <code>== FREEFORM</code>); this requirement was relaxed in API level
     * 23, and <code>FULL</code> devices may only support <code>CENTERED</code> cropping.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#SCALER_CROPPING_TYPE
     * @see CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE
     * @see CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION
     * @see CameraCharacteristics#SYNC_MAX_LATENCY
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_FULL = 1;

    /**
     * <p>This camera device is running in backward compatibility mode.</p>
     * <p>Only the stream configurations listed in the <code>LEGACY</code>
     * <a href="https://developer.android.com/reference/android/hardware/camera2/CameraDevice#legacy-level-guaranteed-configurations">table</a>
     * in the documentation are supported.</p>
     * <p>A <code>LEGACY</code> device does not support per-frame control, manual sensor control, manual
     * post-processing, arbitrary cropping regions, and has relaxed performance constraints.
     * No additional capabilities beyond <code>BACKWARD_COMPATIBLE</code> will ever be listed by a
     * <code>LEGACY</code> device in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     * <p>In addition, the {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger} is not functional on <code>LEGACY</code>
     * devices. Instead, every request that includes a JPEG-format output target is treated
     * as triggering a still capture, internally executing a precapture trigger.  This may
     * fire the flash for flash power metering during precapture, and then fire the flash
     * for the final capture, if a flash is available on the device and the AE mode is set to
     * enable the flash.</p>
     * <p>Devices that initially shipped with Android version {@link android.os.Build.VERSION_CODES#Q Q} or newer will not include any LEGACY-level devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY = 2;

    /**
     * <p>This camera device is capable of YUV reprocessing and RAW data capture, in addition to
     * FULL-level capabilities.</p>
     * <p>The stream configurations listed in the <code>LEVEL_3</code>, <code>RAW</code>, <code>FULL</code>, <code>LEGACY</code> and
     * <code>LIMITED</code>
     * <a href="https://developer.android.com/reference/android/hardware/camera2/CameraDevice#level-3-additional-guaranteed-configurations">tables</a>
     * in the documentation are guaranteed to be supported.</p>
     * <p>The following additional capabilities are guaranteed to be supported:</p>
     * <ul>
     * <li><code>YUV_REPROCESSING</code> capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     *   <code>YUV_REPROCESSING</code>)</li>
     * <li><code>RAW</code> capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains
     *   <code>RAW</code>)</li>
     * </ul>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_3 = 3;

    /**
     * <p>This camera device is backed by an external camera connected to this Android device.</p>
     * <p>The device has capability identical to a LIMITED level device, with the following
     * exceptions:</p>
     * <ul>
     * <li>The device may not report lens/sensor related information such as<ul>
     * <li>{@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength}</li>
     * <li>{@link CameraCharacteristics#LENS_INFO_HYPERFOCAL_DISTANCE android.lens.info.hyperfocalDistance}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE android.sensor.info.physicalSize}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL android.sensor.info.whiteLevel}</li>
     * <li>{@link CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN android.sensor.blackLevelPattern}</li>
     * <li>{@link CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT android.sensor.info.colorFilterArrangement}</li>
     * <li>{@link CaptureResult#SENSOR_ROLLING_SHUTTER_SKEW android.sensor.rollingShutterSkew}</li>
     * </ul>
     * </li>
     * <li>The device will report 0 for {@link CameraCharacteristics#SENSOR_ORIENTATION android.sensor.orientation}</li>
     * <li>The device has less guarantee on stable framerate, as the framerate partly depends
     *   on the external camera being used.</li>
     * </ul>
     *
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     * @see CameraCharacteristics#LENS_INFO_HYPERFOCAL_DISTANCE
     * @see CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     * @see CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL
     * @see CameraCharacteristics#SENSOR_ORIENTATION
     * @see CaptureResult#SENSOR_ROLLING_SHUTTER_SKEW
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL = 4;

    //
    // Enumeration values for CameraCharacteristics#SYNC_MAX_LATENCY
    //

    /**
     * <p>Every frame has the requests immediately applied.</p>
     * <p>Changing controls over multiple requests one after another will
     * produce results that have those controls applied atomically
     * each frame.</p>
     * <p>All FULL capability devices will have this as their maxLatency.</p>
     * @see CameraCharacteristics#SYNC_MAX_LATENCY
     */
    public static final int SYNC_MAX_LATENCY_PER_FRAME_CONTROL = 0;

    /**
     * <p>Each new frame has some subset (potentially the entire set)
     * of the past requests applied to the camera settings.</p>
     * <p>By submitting a series of identical requests, the camera device
     * will eventually have the camera settings applied, but it is
     * unknown when that exact point will be.</p>
     * <p>All LEGACY capability devices will have this as their maxLatency.</p>
     * @see CameraCharacteristics#SYNC_MAX_LATENCY
     */
    public static final int SYNC_MAX_LATENCY_UNKNOWN = -1;

    //
    // Enumeration values for CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE
    //

    /**
     * <p>A software mechanism is used to synchronize between the physical cameras. As a result,
     * the timestamp of an image from a physical stream is only an approximation of the
     * image sensor start-of-exposure time.</p>
     * @see CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE
     */
    public static final int LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE = 0;

    /**
     * <p>The camera device supports frame timestamp synchronization at the hardware level,
     * and the timestamp of a physical stream image accurately reflects its
     * start-of-exposure time.</p>
     * @see CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE
     */
    public static final int LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED = 1;

    //
    // Enumeration values for CameraCharacteristics#AUTOMOTIVE_LENS_FACING
    //

    /**
     * <p>The camera device faces the outside of the vehicle body frame but not exactly
     * one of the exterior sides defined by this enum.  Applications should determine
     * the exact facing direction from {@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} and
     * {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_EXTERIOR_OTHER = 0;

    /**
     * <p>The camera device faces the front of the vehicle body frame.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_EXTERIOR_FRONT = 1;

    /**
     * <p>The camera device faces the rear of the vehicle body frame.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_EXTERIOR_REAR = 2;

    /**
     * <p>The camera device faces the left side of the vehicle body frame.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_EXTERIOR_LEFT = 3;

    /**
     * <p>The camera device faces the right side of the vehicle body frame.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_EXTERIOR_RIGHT = 4;

    /**
     * <p>The camera device faces the inside of the vehicle body frame but not exactly
     * one of seats described by this enum.  Applications should determine the exact
     * facing direction from {@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} and {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_OTHER = 5;

    /**
     * <p>The camera device faces the left side seat of the first row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_1_LEFT = 6;

    /**
     * <p>The camera device faces the center seat of the first row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_1_CENTER = 7;

    /**
     * <p>The camera device faces the right seat of the first row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_1_RIGHT = 8;

    /**
     * <p>The camera device faces the left side seat of the second row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_2_LEFT = 9;

    /**
     * <p>The camera device faces the center seat of the second row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_2_CENTER = 10;

    /**
     * <p>The camera device faces the right side seat of the second row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_2_RIGHT = 11;

    /**
     * <p>The camera device faces the left side seat of the third row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_3_LEFT = 12;

    /**
     * <p>The camera device faces the center seat of the third row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_3_CENTER = 13;

    /**
     * <p>The camera device faces the right seat of the third row.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LENS_FACING
     */
    public static final int AUTOMOTIVE_LENS_FACING_INTERIOR_SEAT_ROW_3_RIGHT = 14;

    //
    // Enumeration values for CameraCharacteristics#AUTOMOTIVE_LOCATION
    //

    /**
     * <p>The camera device exists inside of the vehicle cabin.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_INTERIOR = 0;

    /**
     * <p>The camera exists outside of the vehicle body frame but not exactly on one of the
     * exterior locations this enum defines.  The applications should determine the exact
     * location from {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTERIOR_OTHER = 1;

    /**
     * <p>The camera device exists outside of the vehicle body frame and on its front side.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTERIOR_FRONT = 2;

    /**
     * <p>The camera device exists outside of the vehicle body frame and on its rear side.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTERIOR_REAR = 3;

    /**
     * <p>The camera device exists outside and on left side of the vehicle body frame.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTERIOR_LEFT = 4;

    /**
     * <p>The camera device exists outside and on right side of the vehicle body frame.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTERIOR_RIGHT = 5;

    /**
     * <p>The camera device exists on an extra vehicle, such as the trailer, but not exactly
     * on one of front, rear, left, or right side.  Applications should determine the exact
     * location from {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTRA_OTHER = 6;

    /**
     * <p>The camera device exists outside of the extra vehicle's body frame and on its front
     * side.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTRA_FRONT = 7;

    /**
     * <p>The camera device exists outside of the extra vehicle's body frame and on its rear
     * side.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTRA_REAR = 8;

    /**
     * <p>The camera device exists outside and on left side of the extra vehicle body.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTRA_LEFT = 9;

    /**
     * <p>The camera device exists outside and on right side of the extra vehicle body.</p>
     * @see CameraCharacteristics#AUTOMOTIVE_LOCATION
     */
    public static final int AUTOMOTIVE_LOCATION_EXTRA_RIGHT = 10;

    //
    // Enumeration values for CaptureRequest#COLOR_CORRECTION_MODE
    //

    /**
     * <p>Use the {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform} matrix
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} to do color conversion.</p>
     * <p>All advanced white balance adjustments (not specified
     * by our white balance pipeline) must be disabled.</p>
     * <p>If AWB is enabled with <code>{@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} != OFF</code>, then
     * TRANSFORM_MATRIX is ignored. The camera device will override
     * this value to either FAST or HIGH_QUALITY.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final int COLOR_CORRECTION_MODE_TRANSFORM_MATRIX = 0;

    /**
     * <p>Color correction processing must not slow down
     * capture rate relative to sensor raw output.</p>
     * <p>Advanced white balance adjustments above and beyond
     * the specified white balance pipeline may be applied.</p>
     * <p>If AWB is enabled with <code>{@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} != OFF</code>, then
     * the camera device uses the last frame's AWB values
     * (or defaults if AWB has never been run).</p>
     *
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final int COLOR_CORRECTION_MODE_FAST = 1;

    /**
     * <p>Color correction processing operates at improved
     * quality but the capture rate might be reduced (relative to sensor
     * raw output rate)</p>
     * <p>Advanced white balance adjustments above and beyond
     * the specified white balance pipeline may be applied.</p>
     * <p>If AWB is enabled with <code>{@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} != OFF</code>, then
     * the camera device uses the last frame's AWB values
     * (or defaults if AWB has never been run).</p>
     *
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final int COLOR_CORRECTION_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
    //

    /**
     * <p>No aberration correction is applied.</p>
     * @see CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
     */
    public static final int COLOR_CORRECTION_ABERRATION_MODE_OFF = 0;

    /**
     * <p>Aberration correction will not slow down capture rate
     * relative to sensor raw output.</p>
     * @see CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
     */
    public static final int COLOR_CORRECTION_ABERRATION_MODE_FAST = 1;

    /**
     * <p>Aberration correction operates at improved quality but the capture rate might be
     * reduced (relative to sensor raw output rate)</p>
     * @see CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
     */
    public static final int COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
    //

    /**
     * <p>The camera device will not adjust exposure duration to
     * avoid banding problems.</p>
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_OFF = 0;

    /**
     * <p>The camera device will adjust exposure duration to
     * avoid banding problems with 50Hz illumination sources.</p>
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_50HZ = 1;

    /**
     * <p>The camera device will adjust exposure duration to
     * avoid banding problems with 60Hz illumination
     * sources.</p>
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_60HZ = 2;

    /**
     * <p>The camera device will automatically adapt its
     * antibanding routine to the current illumination
     * condition. This is the default mode if AUTO is
     * available on given camera device.</p>
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    public static final int CONTROL_AE_ANTIBANDING_MODE_AUTO = 3;

    //
    // Enumeration values for CaptureRequest#CONTROL_AE_MODE
    //

    /**
     * <p>The camera device's autoexposure routine is disabled.</p>
     * <p>The application-selected {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime},
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity} and
     * {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} are used by the camera
     * device, along with android.flash.* fields, if there's
     * a flash unit for this camera device.</p>
     * <p>Note that auto-white balance (AWB) and auto-focus (AF)
     * behavior is device dependent when AE is in OFF mode.
     * To have consistent behavior across different devices,
     * it is recommended to either set AWB and AF to OFF mode
     * or lock AWB and AF before setting AE to OFF.
     * See {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}, {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode},
     * {@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock}, and {@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}
     * for more details.</p>
     * <p>LEGACY devices do not support the OFF mode and will
     * override attempts to use this value to ON.</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureRequest#CONTROL_AWB_LOCK
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_OFF = 0;

    /**
     * <p>The camera device's autoexposure routine is active,
     * with no flash control.</p>
     * <p>The application's values for
     * {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime},
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}, and
     * {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} are ignored. The
     * application has control over the various
     * android.flash.* fields.</p>
     *
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON = 1;

    /**
     * <p>Like ON, except that the camera device also controls
     * the camera's flash unit, firing it in low-light
     * conditions.</p>
     * <p>The flash may be fired during a precapture sequence
     * (triggered by {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}) and
     * may be fired for captures for which the
     * {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} field is set to
     * STILL_CAPTURE</p>
     *
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_AUTO_FLASH = 2;

    /**
     * <p>Like ON, except that the camera device also controls
     * the camera's flash unit, always firing it for still
     * captures.</p>
     * <p>The flash may be fired during a precapture sequence
     * (triggered by {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}) and
     * will always be fired for captures for which the
     * {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} field is set to
     * STILL_CAPTURE</p>
     *
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_ALWAYS_FLASH = 3;

    /**
     * <p>Like ON_AUTO_FLASH, but with automatic red eye
     * reduction.</p>
     * <p>If deemed necessary by the camera device, a red eye
     * reduction flash will fire during the precapture
     * sequence.</p>
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE = 4;

    /**
     * <p>An external flash has been turned on.</p>
     * <p>It informs the camera device that an external flash has been turned on, and that
     * metering (and continuous focus if active) should be quickly recalculated to account
     * for the external flash. Otherwise, this mode acts like ON.</p>
     * <p>When the external flash is turned off, AE mode should be changed to one of the
     * other available AE modes.</p>
     * <p>If the camera device supports AE external flash mode, {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} must
     * be FLASH_REQUIRED after the camera device finishes AE scan and it's too dark without
     * flash.</p>
     *
     * @see CaptureResult#CONTROL_AE_STATE
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    public static final int CONTROL_AE_MODE_ON_EXTERNAL_FLASH = 5;

    /**
     * <p>Like 'ON' but applies additional brightness boost in low light scenes.</p>
     * <p>When the scene lighting conditions are within the range defined by
     * {@link CameraCharacteristics#CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE android.control.lowLightBoostInfoLuminanceRange} this mode will apply additional
     * brightness boost.</p>
     * <p>This mode will automatically adjust the intensity of low light boost applied
     * according to the scene lighting conditions. A darker scene will receive more boost
     * while a brighter scene will receive less boost.</p>
     * <p>This mode can ignore the set target frame rate to allow more light to be captured
     * which can result in choppier motion. The frame rate can extend to lower than the
     * {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES android.control.aeAvailableTargetFpsRanges} but will not go below 10 FPS. This mode
     * can also increase the sensor sensitivity gain which can result in increased luma
     * and chroma noise. The sensor sensitivity gain can extend to higher values beyond
     * {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}. This mode may also apply additional
     * processing to recover details in dark and bright areas of the image,and noise
     * reduction at high sensitivity gain settings to manage the trade-off between light
     * sensitivity and capture noise.</p>
     * <p>This mode is restricted to two output surfaces. One output surface type can either
     * be SurfaceView or TextureView. Another output surface type can either be MediaCodec
     * or MediaRecorder. This mode cannot be used with a target FPS range higher than 30
     * FPS.</p>
     * <p>If the session configuration is not supported, the AE mode reported in the
     * CaptureResult will be 'ON' instead of 'ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY'.</p>
     * <p>When this AE mode is enabled, the CaptureResult field
     * {@link CaptureResult#CONTROL_LOW_LIGHT_BOOST_STATE android.control.lowLightBoostState} will indicate when low light boost is 'ACTIVE'
     * or 'INACTIVE'. By default {@link CaptureResult#CONTROL_LOW_LIGHT_BOOST_STATE android.control.lowLightBoostState} will be 'INACTIVE'.</p>
     * <p>The low light boost is 'ACTIVE' once the scene lighting condition is less than the
     * upper bound lux value defined by {@link CameraCharacteristics#CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE android.control.lowLightBoostInfoLuminanceRange}.
     * This mode will be 'INACTIVE' once the scene lighting condition is greater than the
     * upper bound lux value defined by {@link CameraCharacteristics#CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE android.control.lowLightBoostInfoLuminanceRange}.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * @see CameraCharacteristics#CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE
     * @see CaptureResult#CONTROL_LOW_LIGHT_BOOST_STATE
     * @see CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    @FlaggedApi(Flags.FLAG_CAMERA_AE_MODE_LOW_LIGHT_BOOST)
    public static final int CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY = 6;

    //
    // Enumeration values for CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
    //

    /**
     * <p>The trigger is idle.</p>
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     */
    public static final int CONTROL_AE_PRECAPTURE_TRIGGER_IDLE = 0;

    /**
     * <p>The precapture metering sequence will be started
     * by the camera device.</p>
     * <p>The exact effect of the precapture trigger depends on
     * the current AE mode and state.</p>
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     */
    public static final int CONTROL_AE_PRECAPTURE_TRIGGER_START = 1;

    /**
     * <p>The camera device will cancel any currently active or completed
     * precapture metering sequence, the auto-exposure routine will return to its
     * initial state.</p>
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     */
    public static final int CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_AF_MODE
    //

    /**
     * <p>The auto-focus routine does not control the lens;
     * {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance} is controlled by the
     * application.</p>
     *
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_OFF = 0;

    /**
     * <p>Basic automatic focus mode.</p>
     * <p>In this mode, the lens does not move unless
     * the autofocus trigger action is called. When that trigger
     * is activated, AF will transition to ACTIVE_SCAN, then to
     * the outcome of the scan (FOCUSED or NOT_FOCUSED).</p>
     * <p>Always supported if lens is not fixed focus.</p>
     * <p>Use {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} to determine if lens
     * is fixed-focus.</p>
     * <p>Triggering AF_CANCEL resets the lens position to default,
     * and sets the AF state to INACTIVE.</p>
     *
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_AUTO = 1;

    /**
     * <p>Close-up focusing mode.</p>
     * <p>In this mode, the lens does not move unless the
     * autofocus trigger action is called. When that trigger is
     * activated, AF will transition to ACTIVE_SCAN, then to
     * the outcome of the scan (FOCUSED or NOT_FOCUSED). This
     * mode is optimized for focusing on objects very close to
     * the camera.</p>
     * <p>When that trigger is activated, AF will transition to
     * ACTIVE_SCAN, then to the outcome of the scan (FOCUSED or
     * NOT_FOCUSED). Triggering cancel AF resets the lens
     * position to default, and sets the AF state to
     * INACTIVE.</p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_MACRO = 2;

    /**
     * <p>In this mode, the AF algorithm modifies the lens
     * position continually to attempt to provide a
     * constantly-in-focus image stream.</p>
     * <p>The focusing behavior should be suitable for good quality
     * video recording; typically this means slower focus
     * movement and no overshoots. When the AF trigger is not
     * involved, the AF algorithm should start in INACTIVE state,
     * and then transition into PASSIVE_SCAN and PASSIVE_FOCUSED
     * states as appropriate. When the AF trigger is activated,
     * the algorithm should immediately transition into
     * AF_FOCUSED or AF_NOT_FOCUSED as appropriate, and lock the
     * lens position until a cancel AF trigger is received.</p>
     * <p>Once cancel is received, the algorithm should transition
     * back to INACTIVE and resume passive scan. Note that this
     * behavior is not identical to CONTINUOUS_PICTURE, since an
     * ongoing PASSIVE_SCAN must immediately be
     * canceled.</p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_CONTINUOUS_VIDEO = 3;

    /**
     * <p>In this mode, the AF algorithm modifies the lens
     * position continually to attempt to provide a
     * constantly-in-focus image stream.</p>
     * <p>The focusing behavior should be suitable for still image
     * capture; typically this means focusing as fast as
     * possible. When the AF trigger is not involved, the AF
     * algorithm should start in INACTIVE state, and then
     * transition into PASSIVE_SCAN and PASSIVE_FOCUSED states as
     * appropriate as it attempts to maintain focus. When the AF
     * trigger is activated, the algorithm should finish its
     * PASSIVE_SCAN if active, and then transition into
     * AF_FOCUSED or AF_NOT_FOCUSED as appropriate, and lock the
     * lens position until a cancel AF trigger is received.</p>
     * <p>When the AF cancel trigger is activated, the algorithm
     * should transition back to INACTIVE and then act as if it
     * has just been started.</p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_CONTINUOUS_PICTURE = 4;

    /**
     * <p>Extended depth of field (digital focus) mode.</p>
     * <p>The camera device will produce images with an extended
     * depth of field automatically; no special focusing
     * operations need to be done before taking a picture.</p>
     * <p>AF triggers are ignored, and the AF state will always be
     * INACTIVE.</p>
     * @see CaptureRequest#CONTROL_AF_MODE
     */
    public static final int CONTROL_AF_MODE_EDOF = 5;

    //
    // Enumeration values for CaptureRequest#CONTROL_AF_TRIGGER
    //

    /**
     * <p>The trigger is idle.</p>
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     */
    public static final int CONTROL_AF_TRIGGER_IDLE = 0;

    /**
     * <p>Autofocus will trigger now.</p>
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     */
    public static final int CONTROL_AF_TRIGGER_START = 1;

    /**
     * <p>Autofocus will return to its initial
     * state, and cancel any currently active trigger.</p>
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     */
    public static final int CONTROL_AF_TRIGGER_CANCEL = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_AWB_MODE
    //

    /**
     * <p>The camera device's auto-white balance routine is disabled.</p>
     * <p>The application-selected color transform matrix
     * ({@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}) and gains
     * ({@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}) are used by the camera
     * device for manual white balance control.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_OFF = 0;

    /**
     * <p>The camera device's auto-white balance routine is active.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_AUTO = 1;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses incandescent light as the assumed scene
     * illumination for white balance.</p>
     * <p>While the exact white balance transforms are up to the
     * camera device, they will approximately match the CIE
     * standard illuminant A.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_INCANDESCENT = 2;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses fluorescent light as the assumed scene
     * illumination for white balance.</p>
     * <p>While the exact white balance transforms are up to the
     * camera device, they will approximately match the CIE
     * standard illuminant F2.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_FLUORESCENT = 3;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses warm fluorescent light as the assumed scene
     * illumination for white balance.</p>
     * <p>While the exact white balance transforms are up to the
     * camera device, they will approximately match the CIE
     * standard illuminant F4.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_WARM_FLUORESCENT = 4;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses daylight light as the assumed scene
     * illumination for white balance.</p>
     * <p>While the exact white balance transforms are up to the
     * camera device, they will approximately match the CIE
     * standard illuminant D65.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_DAYLIGHT = 5;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses cloudy daylight light as the assumed scene
     * illumination for white balance.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_CLOUDY_DAYLIGHT = 6;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses twilight light as the assumed scene
     * illumination for white balance.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_TWILIGHT = 7;

    /**
     * <p>The camera device's auto-white balance routine is disabled;
     * the camera device uses shade light as the assumed scene
     * illumination for white balance.</p>
     * <p>The application's values for {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}
     * and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} are ignored.
     * For devices that support the MANUAL_POST_PROCESSING capability, the
     * values used by the camera device for the transform and gains
     * will be available in the capture result for this request.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    public static final int CONTROL_AWB_MODE_SHADE = 8;

    //
    // Enumeration values for CaptureRequest#CONTROL_CAPTURE_INTENT
    //

    /**
     * <p>The goal of this request doesn't fall into the other
     * categories. The camera device will default to preview-like
     * behavior.</p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_CUSTOM = 0;

    /**
     * <p>This request is for a preview-like use case.</p>
     * <p>The precapture trigger may be used to start off a metering
     * w/flash sequence.</p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_PREVIEW = 1;

    /**
     * <p>This request is for a still capture-type
     * use case.</p>
     * <p>If the flash unit is under automatic control, it may fire as needed.</p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_STILL_CAPTURE = 2;

    /**
     * <p>This request is for a video recording
     * use case.</p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_VIDEO_RECORD = 3;

    /**
     * <p>This request is for a video snapshot (still
     * image while recording video) use case.</p>
     * <p>The camera device should take the highest-quality image
     * possible (given the other settings) without disrupting the
     * frame rate of video recording.  </p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT = 4;

    /**
     * <p>This request is for a ZSL usecase; the
     * application will stream full-resolution images and
     * reprocess one or several later for a final
     * capture.</p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG = 5;

    /**
     * <p>This request is for manual capture use case where
     * the applications want to directly control the capture parameters.</p>
     * <p>For example, the application may wish to manually control
     * {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime}, {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}, etc.</p>
     *
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_MANUAL = 6;

    /**
     * <p>This request is for a motion tracking use case, where
     * the application will use camera and inertial sensor data to
     * locate and track objects in the world.</p>
     * <p>The camera device auto-exposure routine will limit the exposure time
     * of the camera to no more than 20 milliseconds, to minimize motion blur.</p>
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     */
    public static final int CONTROL_CAPTURE_INTENT_MOTION_TRACKING = 7;

    //
    // Enumeration values for CaptureRequest#CONTROL_EFFECT_MODE
    //

    /**
     * <p>No color effect will be applied.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_OFF = 0;

    /**
     * <p>A "monocolor" effect where the image is mapped into
     * a single color.</p>
     * <p>This will typically be grayscale.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_MONO = 1;

    /**
     * <p>A "photo-negative" effect where the image's colors
     * are inverted.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_NEGATIVE = 2;

    /**
     * <p>A "solarisation" effect (Sabattier effect) where the
     * image is wholly or partially reversed in
     * tone.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_SOLARIZE = 3;

    /**
     * <p>A "sepia" effect where the image is mapped into warm
     * gray, red, and brown tones.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_SEPIA = 4;

    /**
     * <p>A "posterization" effect where the image uses
     * discrete regions of tone rather than a continuous
     * gradient of tones.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_POSTERIZE = 5;

    /**
     * <p>A "whiteboard" effect where the image is typically displayed
     * as regions of white, with black or grey details.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_WHITEBOARD = 6;

    /**
     * <p>A "blackboard" effect where the image is typically displayed
     * as regions of black, with white or grey details.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_BLACKBOARD = 7;

    /**
     * <p>An "aqua" effect where a blue hue is added to the image.</p>
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     */
    public static final int CONTROL_EFFECT_MODE_AQUA = 8;

    //
    // Enumeration values for CaptureRequest#CONTROL_MODE
    //

    /**
     * <p>Full application control of pipeline.</p>
     * <p>All control by the device's metering and focusing (3A)
     * routines is disabled, and no other settings in
     * android.control.* have any effect, except that
     * {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} may be used by the camera
     * device to select post-processing values for processing
     * blocks that do not allow for manual control, or are not
     * exposed by the camera API.</p>
     * <p>However, the camera device's 3A routines may continue to
     * collect statistics and update their internal state so that
     * when control is switched to AUTO mode, good control values
     * can be immediately applied.</p>
     *
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_OFF = 0;

    /**
     * <p>Use settings for each individual 3A routine.</p>
     * <p>Manual control of capture parameters is disabled. All
     * controls in android.control.* besides sceneMode take
     * effect.</p>
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_AUTO = 1;

    /**
     * <p>Use a specific scene mode.</p>
     * <p>Enabling this disables control.aeMode, control.awbMode and
     * control.afMode controls; the camera device will ignore
     * those settings while USE_SCENE_MODE is active (except for
     * FACE_PRIORITY scene mode). Other control entries are still active.
     * This setting can only be used if scene mode is supported (i.e.
     * {@link CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES android.control.availableSceneModes}
     * contain some modes other than DISABLED).</p>
     * <p>For extended scene modes such as BOKEH, please use USE_EXTENDED_SCENE_MODE instead.</p>
     *
     * @see CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_USE_SCENE_MODE = 2;

    /**
     * <p>Same as OFF mode, except that this capture will not be
     * used by camera device background auto-exposure, auto-white balance and
     * auto-focus algorithms (3A) to update their statistics.</p>
     * <p>Specifically, the 3A routines are locked to the last
     * values set from a request with AUTO, OFF, or
     * USE_SCENE_MODE, and any statistics or state updates
     * collected from manual captures with OFF_KEEP_STATE will be
     * discarded by the camera device.</p>
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_OFF_KEEP_STATE = 3;

    /**
     * <p>Use a specific extended scene mode.</p>
     * <p>When extended scene mode is on, the camera device may override certain control
     * parameters, such as targetFpsRange, AE, AWB, and AF modes, to achieve best power and
     * quality tradeoffs. Only the mandatory stream combinations of LIMITED hardware level
     * are guaranteed.</p>
     * <p>This setting can only be used if extended scene mode is supported (i.e.
     * android.control.availableExtendedSceneModes
     * contains some modes other than DISABLED).</p>
     * @see CaptureRequest#CONTROL_MODE
     */
    public static final int CONTROL_MODE_USE_EXTENDED_SCENE_MODE = 4;

    //
    // Enumeration values for CaptureRequest#CONTROL_SCENE_MODE
    //

    /**
     * <p>Indicates that no scene modes are set for a given capture request.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_DISABLED = 0;

    /**
     * <p>If face detection support exists, use face
     * detection data for auto-focus, auto-white balance, and
     * auto-exposure routines.</p>
     * <p>If face detection statistics are disabled
     * (i.e. {@link CaptureRequest#STATISTICS_FACE_DETECT_MODE android.statistics.faceDetectMode} is set to OFF),
     * this should still operate correctly (but will not return
     * face detection statistics to the framework).</p>
     * <p>Unlike the other scene modes, {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode},
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}, and {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}
     * remain active when FACE_PRIORITY is set.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_FACE_PRIORITY = 1;

    /**
     * <p>Optimized for photos of quickly moving objects.</p>
     * <p>Similar to SPORTS.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_ACTION = 2;

    /**
     * <p>Optimized for still photos of people.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_PORTRAIT = 3;

    /**
     * <p>Optimized for photos of distant macroscopic objects.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_LANDSCAPE = 4;

    /**
     * <p>Optimized for low-light settings.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_NIGHT = 5;

    /**
     * <p>Optimized for still photos of people in low-light
     * settings.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_NIGHT_PORTRAIT = 6;

    /**
     * <p>Optimized for dim, indoor settings where flash must
     * remain off.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_THEATRE = 7;

    /**
     * <p>Optimized for bright, outdoor beach settings.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_BEACH = 8;

    /**
     * <p>Optimized for bright, outdoor settings containing snow.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_SNOW = 9;

    /**
     * <p>Optimized for scenes of the setting sun.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_SUNSET = 10;

    /**
     * <p>Optimized to avoid blurry photos due to small amounts of
     * device motion (for example: due to hand shake).</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_STEADYPHOTO = 11;

    /**
     * <p>Optimized for nighttime photos of fireworks.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_FIREWORKS = 12;

    /**
     * <p>Optimized for photos of quickly moving people.</p>
     * <p>Similar to ACTION.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_SPORTS = 13;

    /**
     * <p>Optimized for dim, indoor settings with multiple moving
     * people.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_PARTY = 14;

    /**
     * <p>Optimized for dim settings where the main light source
     * is a candle.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_CANDLELIGHT = 15;

    /**
     * <p>Optimized for accurately capturing a photo of barcode
     * for use by camera applications that wish to read the
     * barcode value.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_BARCODE = 16;

    /**
     * <p>This is deprecated, please use {@link android.hardware.camera2.CameraDevice#createConstrainedHighSpeedCaptureSession }
     * and {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList }
     * for high speed video recording.</p>
     * <p>Optimized for high speed video recording (frame rate &gt;=60fps) use case.</p>
     * <p>The supported high speed video sizes and fps ranges are specified in
     * android.control.availableHighSpeedVideoConfigurations. To get desired
     * output frame rates, the application is only allowed to select video size
     * and fps range combinations listed in this static metadata. The fps range
     * can be control via {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange}.</p>
     * <p>In this mode, the camera device will override aeMode, awbMode, and afMode to
     * ON, ON, and CONTINUOUS_VIDEO, respectively. All post-processing block mode
     * controls will be overridden to be FAST. Therefore, no manual control of capture
     * and post-processing parameters is possible. All other controls operate the
     * same as when {@link CaptureRequest#CONTROL_MODE android.control.mode} == AUTO. This means that all other
     * android.control.* fields continue to work, such as</p>
     * <ul>
     * <li>{@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION android.control.aeExposureCompensation}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock}</li>
     * <li>{@link CaptureRequest#CONTROL_EFFECT_MODE android.control.effectMode}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}</li>
     * <li>{@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}</li>
     * </ul>
     * <p>Outside of android.control.*, the following controls will work:</p>
     * <ul>
     * <li>{@link CaptureRequest#FLASH_MODE android.flash.mode} (automatic flash for still capture will not work since aeMode is ON)</li>
     * <li>{@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode} (if it is supported)</li>
     * <li>{@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}</li>
     * <li>{@link CaptureRequest#STATISTICS_FACE_DETECT_MODE android.statistics.faceDetectMode}</li>
     * </ul>
     * <p>For high speed recording use case, the actual maximum supported frame rate may
     * be lower than what camera can output, depending on the destination Surfaces for
     * the image data. For example, if the destination surface is from video encoder,
     * the application need check if the video encoder is capable of supporting the
     * high frame rate for a given video size, or it will end up with lower recording
     * frame rate. If the destination surface is from preview window, the preview frame
     * rate will be bounded by the screen refresh rate.</p>
     * <p>The camera device will only support up to 2 output high speed streams
     * (processed non-stalling format defined in android.request.maxNumOutputStreams)
     * in this mode. This control will be effective only if all of below conditions are true:</p>
     * <ul>
     * <li>The application created no more than maxNumHighSpeedStreams processed non-stalling
     * format output streams, where maxNumHighSpeedStreams is calculated as
     * min(2, android.request.maxNumOutputStreams[Processed (but not-stalling)]).</li>
     * <li>The stream sizes are selected from the sizes reported by
     * android.control.availableHighSpeedVideoConfigurations.</li>
     * <li>No processed non-stalling or raw streams are configured.</li>
     * </ul>
     * <p>When above conditions are NOT satisfied, the controls of this mode and
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange} will be ignored by the camera device,
     * the camera device will fall back to {@link CaptureRequest#CONTROL_MODE android.control.mode} <code>==</code> AUTO,
     * and the returned capture result metadata will give the fps range chosen
     * by the camera device.</p>
     * <p>Switching into or out of this mode may trigger some camera ISP/sensor
     * reconfigurations, which may introduce extra latency. It is recommended that
     * the application avoids unnecessary scene mode switch as much as possible.</p>
     *
     * @see CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION
     * @see CaptureRequest#CONTROL_AE_LOCK
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureRequest#CONTROL_AWB_LOCK
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#FLASH_MODE
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @deprecated Please refer to this API documentation to find the alternatives
     */
    @Deprecated
    public static final int CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO = 17;

    /**
     * <p>Turn on a device-specific high dynamic range (HDR) mode.</p>
     * <p>In this scene mode, the camera device captures images
     * that keep a larger range of scene illumination levels
     * visible in the final image. For example, when taking a
     * picture of a object in front of a bright window, both
     * the object and the scene through the window may be
     * visible when using HDR mode, while in normal AUTO mode,
     * one or the other may be poorly exposed. As a tradeoff,
     * HDR mode generally takes much longer to capture a single
     * image, has no user control, and may have other artifacts
     * depending on the HDR method used.</p>
     * <p>Therefore, HDR captures operate at a much slower rate
     * than regular captures.</p>
     * <p>In this mode, on LIMITED or FULL devices, when a request
     * is made with a {@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} of
     * STILL_CAPTURE, the camera device will capture an image
     * using a high dynamic range capture technique.  On LEGACY
     * devices, captures that target a JPEG-format output will
     * be captured with HDR, and the capture intent is not
     * relevant.</p>
     * <p>The HDR capture may involve the device capturing a burst
     * of images internally and combining them into one, or it
     * may involve the device using specialized high dynamic
     * range capture hardware. In all cases, a single image is
     * produced in response to a capture request submitted
     * while in HDR mode.</p>
     * <p>Since substantial post-processing is generally needed to
     * produce an HDR image, only YUV, PRIVATE, and JPEG
     * outputs are supported for LIMITED/FULL device HDR
     * captures, and only JPEG outputs are supported for LEGACY
     * HDR captures. Using a RAW output for HDR capture is not
     * supported.</p>
     * <p>Some devices may also support always-on HDR, which
     * applies HDR processing at full frame rate.  For these
     * devices, intents other than STILL_CAPTURE will also
     * produce an HDR output with no frame rate impact compared
     * to normal operation, though the quality may be lower
     * than for STILL_CAPTURE intents.</p>
     * <p>If SCENE_MODE_HDR is used with unsupported output types
     * or capture intents, the images captured will be as if
     * the SCENE_MODE was not enabled at all.</p>
     *
     * @see CaptureRequest#CONTROL_CAPTURE_INTENT
     * @see CaptureRequest#CONTROL_SCENE_MODE
     */
    public static final int CONTROL_SCENE_MODE_HDR = 18;

    /**
     * <p>Same as FACE_PRIORITY scene mode, except that the camera
     * device will choose higher sensitivity values ({@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity})
     * under low light conditions.</p>
     * <p>The camera device may be tuned to expose the images in a reduced
     * sensitivity range to produce the best quality images. For example,
     * if the {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange} gives range of [100, 1600],
     * the camera device auto-exposure routine tuning process may limit the actual
     * exposure sensitivity range to [100, 1200] to ensure that the noise level isn't
     * excessive in order to preserve the image quality. Under this situation, the image under
     * low light may be under-exposed when the sensor max exposure time (bounded by the
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange} when {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is one of the
     * ON_* modes) and effective max sensitivity are reached. This scene mode allows the
     * camera device auto-exposure routine to increase the sensitivity up to the max
     * sensitivity specified by {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange} when the scene is too
     * dark and the max exposure time is reached. The captured images may be noisier
     * compared with the images captured in normal FACE_PRIORITY mode; therefore, it is
     * recommended that the application only use this scene mode when it is capable of
     * reducing the noise level of the captured images.</p>
     * <p>Unlike the other scene modes, {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode},
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}, and {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}
     * remain active when FACE_PRIORITY_LOW_LIGHT is set.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @hide
     */
    public static final int CONTROL_SCENE_MODE_FACE_PRIORITY_LOW_LIGHT = 19;

    /**
     * <p>Scene mode values within the range of
     * <code>[DEVICE_CUSTOM_START, DEVICE_CUSTOM_END]</code> are reserved for device specific
     * customized scene modes.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @hide
     */
    public static final int CONTROL_SCENE_MODE_DEVICE_CUSTOM_START = 100;

    /**
     * <p>Scene mode values within the range of
     * <code>[DEVICE_CUSTOM_START, DEVICE_CUSTOM_END]</code> are reserved for device specific
     * customized scene modes.</p>
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @hide
     */
    public static final int CONTROL_SCENE_MODE_DEVICE_CUSTOM_END = 127;

    //
    // Enumeration values for CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
    //

    /**
     * <p>Video stabilization is disabled.</p>
     * @see CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
     */
    public static final int CONTROL_VIDEO_STABILIZATION_MODE_OFF = 0;

    /**
     * <p>Video stabilization is enabled.</p>
     * @see CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
     */
    public static final int CONTROL_VIDEO_STABILIZATION_MODE_ON = 1;

    /**
     * <p>Preview stabilization, where the preview in addition to all other non-RAW streams are
     * stabilized with the same quality of stabilization, is enabled. This mode aims to give
     * clients a 'what you see is what you get' effect. In this mode, the FoV reduction will
     * be a maximum of 20 % both horizontally and vertically
     * (10% from left, right, top, bottom) for the given zoom ratio / crop region.
     * The resultant FoV will also be the same across all processed streams
     * (that have the same aspect ratio).</p>
     * @see CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
     */
    public static final int CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION = 2;

    //
    // Enumeration values for CaptureRequest#CONTROL_EXTENDED_SCENE_MODE
    //

    /**
     * <p>Extended scene mode is disabled.</p>
     * @see CaptureRequest#CONTROL_EXTENDED_SCENE_MODE
     */
    public static final int CONTROL_EXTENDED_SCENE_MODE_DISABLED = 0;

    /**
     * <p>High quality bokeh mode is enabled for all non-raw streams (including YUV,
     * JPEG, and IMPLEMENTATION_DEFINED) when capture intent is STILL_CAPTURE. Due to the
     * extra image processing, this mode may introduce additional stall to non-raw streams.
     * This mode should be used in high quality still capture use case.</p>
     * @see CaptureRequest#CONTROL_EXTENDED_SCENE_MODE
     */
    public static final int CONTROL_EXTENDED_SCENE_MODE_BOKEH_STILL_CAPTURE = 1;

    /**
     * <p>Bokeh effect must not slow down capture rate relative to sensor raw output,
     * and the effect is applied to all processed streams no larger than the maximum
     * streaming dimension. This mode should be used if performance and power are a
     * priority, such as video recording.</p>
     * @see CaptureRequest#CONTROL_EXTENDED_SCENE_MODE
     */
    public static final int CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS = 2;

    /**
     * <p>Vendor defined extended scene modes. These depend on vendor implementation.</p>
     * @see CaptureRequest#CONTROL_EXTENDED_SCENE_MODE
     * @hide
     */
    public static final int CONTROL_EXTENDED_SCENE_MODE_VENDOR_START = 0x40;

    //
    // Enumeration values for CaptureRequest#CONTROL_SETTINGS_OVERRIDE
    //

    /**
     * <p>No keys are applied sooner than the other keys when applying CaptureRequest
     * settings to the camera device. This is the default value.</p>
     * @see CaptureRequest#CONTROL_SETTINGS_OVERRIDE
     */
    public static final int CONTROL_SETTINGS_OVERRIDE_OFF = 0;

    /**
     * <p>Zoom related keys are applied sooner than the other keys in the CaptureRequest. The
     * zoom related keys are:</p>
     * <ul>
     * <li>{@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}</li>
     * <li>{@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}</li>
     * <li>{@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}</li>
     * <li>{@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}</li>
     * </ul>
     * <p>Even though {@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}, {@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions},
     * and {@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions} are not directly zoom related, applications
     * typically scale these regions together with {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} to have a
     * consistent mapping within the current field of view. In this aspect, they are
     * related to {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion} and {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CaptureRequest#CONTROL_SETTINGS_OVERRIDE
     */
    public static final int CONTROL_SETTINGS_OVERRIDE_ZOOM = 1;

    /**
     * <p>Vendor defined settingsOverride. These depend on vendor implementation.</p>
     * @see CaptureRequest#CONTROL_SETTINGS_OVERRIDE
     * @hide
     */
    public static final int CONTROL_SETTINGS_OVERRIDE_VENDOR_START = 0x4000;

    //
    // Enumeration values for CaptureRequest#CONTROL_AUTOFRAMING
    //

    /**
     * <p>Disable autoframing.</p>
     * @see CaptureRequest#CONTROL_AUTOFRAMING
     */
    public static final int CONTROL_AUTOFRAMING_OFF = 0;

    /**
     * <p>Enable autoframing to keep people in the frame's field of view.</p>
     * @see CaptureRequest#CONTROL_AUTOFRAMING
     */
    public static final int CONTROL_AUTOFRAMING_ON = 1;

    /**
     * <p>Automatically select ON or OFF based on the system level preferences.</p>
     * @see CaptureRequest#CONTROL_AUTOFRAMING
     * @hide
     */
    public static final int CONTROL_AUTOFRAMING_AUTO = 2;

    //
    // Enumeration values for CaptureRequest#EDGE_MODE
    //

    /**
     * <p>No edge enhancement is applied.</p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_OFF = 0;

    /**
     * <p>Apply edge enhancement at a quality level that does not slow down frame rate
     * relative to sensor output. It may be the same as OFF if edge enhancement will
     * slow down frame rate relative to sensor.</p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_FAST = 1;

    /**
     * <p>Apply high-quality edge enhancement, at a cost of possibly reduced output frame rate.</p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_HIGH_QUALITY = 2;

    /**
     * <p>Edge enhancement is applied at different
     * levels for different output streams, based on resolution. Streams at maximum recording
     * resolution (see {@link android.hardware.camera2.CameraDevice#createCaptureSession })
     * or below have edge enhancement applied, while higher-resolution streams have no edge
     * enhancement applied. The level of edge enhancement for low-resolution streams is tuned
     * so that frame rate is not impacted, and the quality is equal to or better than FAST
     * (since it is only applied to lower-resolution outputs, quality may improve from FAST).</p>
     * <p>This mode is intended to be used by applications operating in a zero-shutter-lag mode
     * with YUV or PRIVATE reprocessing, where the application continuously captures
     * high-resolution intermediate buffers into a circular buffer, from which a final image is
     * produced via reprocessing when a user takes a picture.  For such a use case, the
     * high-resolution buffers must not have edge enhancement applied to maximize efficiency of
     * preview and to avoid double-applying enhancement when reprocessed, while low-resolution
     * buffers (used for recording or preview, generally) need edge enhancement applied for
     * reasonable preview quality.</p>
     * <p>This mode is guaranteed to be supported by devices that support either the
     * YUV_REPROCESSING or PRIVATE_REPROCESSING capabilities
     * ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} lists either of those capabilities) and it will
     * be the default mode for CAMERA3_TEMPLATE_ZERO_SHUTTER_LAG template.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_ZERO_SHUTTER_LAG = 3;

    //
    // Enumeration values for CaptureRequest#FLASH_MODE
    //

    /**
     * <p>Do not fire the flash for this capture.</p>
     * @see CaptureRequest#FLASH_MODE
     */
    public static final int FLASH_MODE_OFF = 0;

    /**
     * <p>If the flash is available and charged, fire flash
     * for this capture.</p>
     * @see CaptureRequest#FLASH_MODE
     */
    public static final int FLASH_MODE_SINGLE = 1;

    /**
     * <p>Transition flash to continuously on.</p>
     * @see CaptureRequest#FLASH_MODE
     */
    public static final int FLASH_MODE_TORCH = 2;

    //
    // Enumeration values for CaptureRequest#HOT_PIXEL_MODE
    //

    /**
     * <p>No hot pixel correction is applied.</p>
     * <p>The frame rate must not be reduced relative to sensor raw output
     * for this option.</p>
     * <p>The hotpixel map may be returned in {@link CaptureResult#STATISTICS_HOT_PIXEL_MAP android.statistics.hotPixelMap}.</p>
     *
     * @see CaptureResult#STATISTICS_HOT_PIXEL_MAP
     * @see CaptureRequest#HOT_PIXEL_MODE
     */
    public static final int HOT_PIXEL_MODE_OFF = 0;

    /**
     * <p>Hot pixel correction is applied, without reducing frame
     * rate relative to sensor raw output.</p>
     * <p>The hotpixel map may be returned in {@link CaptureResult#STATISTICS_HOT_PIXEL_MAP android.statistics.hotPixelMap}.</p>
     *
     * @see CaptureResult#STATISTICS_HOT_PIXEL_MAP
     * @see CaptureRequest#HOT_PIXEL_MODE
     */
    public static final int HOT_PIXEL_MODE_FAST = 1;

    /**
     * <p>High-quality hot pixel correction is applied, at a cost
     * of possibly reduced frame rate relative to sensor raw output.</p>
     * <p>The hotpixel map may be returned in {@link CaptureResult#STATISTICS_HOT_PIXEL_MAP android.statistics.hotPixelMap}.</p>
     *
     * @see CaptureResult#STATISTICS_HOT_PIXEL_MAP
     * @see CaptureRequest#HOT_PIXEL_MODE
     */
    public static final int HOT_PIXEL_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
    //

    /**
     * <p>Optical stabilization is unavailable.</p>
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    public static final int LENS_OPTICAL_STABILIZATION_MODE_OFF = 0;

    /**
     * <p>Optical stabilization is enabled.</p>
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    public static final int LENS_OPTICAL_STABILIZATION_MODE_ON = 1;

    //
    // Enumeration values for CaptureRequest#NOISE_REDUCTION_MODE
    //

    /**
     * <p>No noise reduction is applied.</p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_OFF = 0;

    /**
     * <p>Noise reduction is applied without reducing frame rate relative to sensor
     * output. It may be the same as OFF if noise reduction will reduce frame rate
     * relative to sensor.</p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_FAST = 1;

    /**
     * <p>High-quality noise reduction is applied, at the cost of possibly reduced frame
     * rate relative to sensor output.</p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_HIGH_QUALITY = 2;

    /**
     * <p>MINIMAL noise reduction is applied without reducing frame rate relative to
     * sensor output. </p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_MINIMAL = 3;

    /**
     * <p>Noise reduction is applied at different levels for different output streams,
     * based on resolution. Streams at maximum recording resolution (see {@link android.hardware.camera2.CameraDevice#createCaptureSession })
     * or below have noise reduction applied, while higher-resolution streams have MINIMAL (if
     * supported) or no noise reduction applied (if MINIMAL is not supported.) The degree of
     * noise reduction for low-resolution streams is tuned so that frame rate is not impacted,
     * and the quality is equal to or better than FAST (since it is only applied to
     * lower-resolution outputs, quality may improve from FAST).</p>
     * <p>This mode is intended to be used by applications operating in a zero-shutter-lag mode
     * with YUV or PRIVATE reprocessing, where the application continuously captures
     * high-resolution intermediate buffers into a circular buffer, from which a final image is
     * produced via reprocessing when a user takes a picture.  For such a use case, the
     * high-resolution buffers must not have noise reduction applied to maximize efficiency of
     * preview and to avoid over-applying noise filtering when reprocessing, while
     * low-resolution buffers (used for recording or preview, generally) need noise reduction
     * applied for reasonable preview quality.</p>
     * <p>This mode is guaranteed to be supported by devices that support either the
     * YUV_REPROCESSING or PRIVATE_REPROCESSING capabilities
     * ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} lists either of those capabilities) and it will
     * be the default mode for CAMERA3_TEMPLATE_ZERO_SHUTTER_LAG template.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG = 4;

    //
    // Enumeration values for CaptureRequest#SCALER_ROTATE_AND_CROP
    //

    /**
     * <p>No rotate and crop is applied. Processed outputs are in the sensor orientation.</p>
     * @see CaptureRequest#SCALER_ROTATE_AND_CROP
     */
    public static final int SCALER_ROTATE_AND_CROP_NONE = 0;

    /**
     * <p>Processed images are rotated by 90 degrees clockwise, and then cropped
     * to the original aspect ratio.</p>
     * @see CaptureRequest#SCALER_ROTATE_AND_CROP
     */
    public static final int SCALER_ROTATE_AND_CROP_90 = 1;

    /**
     * <p>Processed images are rotated by 180 degrees.  Since the aspect ratio does not
     * change, no cropping is performed.</p>
     * @see CaptureRequest#SCALER_ROTATE_AND_CROP
     */
    public static final int SCALER_ROTATE_AND_CROP_180 = 2;

    /**
     * <p>Processed images are rotated by 270 degrees clockwise, and then cropped
     * to the original aspect ratio.</p>
     * @see CaptureRequest#SCALER_ROTATE_AND_CROP
     */
    public static final int SCALER_ROTATE_AND_CROP_270 = 3;

    /**
     * <p>The camera API automatically selects the best concrete value for
     * rotate-and-crop based on the application's support for resizability and the current
     * multi-window mode.</p>
     * <p>If the application does not support resizing but the display mode for its main
     * Activity is not in a typical orientation, the camera API will set <code>ROTATE_AND_CROP_90</code>
     * or some other supported rotation value, depending on device configuration,
     * to ensure preview and captured images are correctly shown to the user. Otherwise,
     * <code>ROTATE_AND_CROP_NONE</code> will be selected.</p>
     * <p>When a value other than NONE is selected, several metadata fields will also be parsed
     * differently to ensure that coordinates are correctly handled for features like drawing
     * face detection boxes or passing in tap-to-focus coordinates.  The camera API will
     * convert positions in the active array coordinate system to/from the cropped-and-rotated
     * coordinate system to make the operation transparent for applications.</p>
     * <p>No coordinate mapping will be done when the application selects a non-AUTO mode.</p>
     * @see CaptureRequest#SCALER_ROTATE_AND_CROP
     */
    public static final int SCALER_ROTATE_AND_CROP_AUTO = 4;

    //
    // Enumeration values for CaptureRequest#SENSOR_TEST_PATTERN_MODE
    //

    /**
     * <p>No test pattern mode is used, and the camera
     * device returns captures from the image sensor.</p>
     * <p>This is the default if the key is not set.</p>
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final int SENSOR_TEST_PATTERN_MODE_OFF = 0;

    /**
     * <p>Each pixel in <code>[R, G_even, G_odd, B]</code> is replaced by its
     * respective color channel provided in
     * {@link CaptureRequest#SENSOR_TEST_PATTERN_DATA android.sensor.testPatternData}.</p>
     * <p>For example:</p>
     * <pre><code>{@link CaptureRequest#SENSOR_TEST_PATTERN_DATA android.sensor.testPatternData} = [0, 0xFFFFFFFF, 0xFFFFFFFF, 0]
     * </code></pre>
     * <p>All green pixels are 100% green. All red/blue pixels are black.</p>
     * <pre><code>{@link CaptureRequest#SENSOR_TEST_PATTERN_DATA android.sensor.testPatternData} = [0xFFFFFFFF, 0, 0xFFFFFFFF, 0]
     * </code></pre>
     * <p>All red pixels are 100% red. Only the odd green pixels
     * are 100% green. All blue pixels are 100% black.</p>
     *
     * @see CaptureRequest#SENSOR_TEST_PATTERN_DATA
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final int SENSOR_TEST_PATTERN_MODE_SOLID_COLOR = 1;

    /**
     * <p>All pixel data is replaced with an 8-bar color pattern.</p>
     * <p>The vertical bars (left-to-right) are as follows:</p>
     * <ul>
     * <li>100% white</li>
     * <li>yellow</li>
     * <li>cyan</li>
     * <li>green</li>
     * <li>magenta</li>
     * <li>red</li>
     * <li>blue</li>
     * <li>black</li>
     * </ul>
     * <p>In general the image would look like the following:</p>
     * <pre><code>W Y C G M R B K
     * W Y C G M R B K
     * W Y C G M R B K
     * W Y C G M R B K
     * W Y C G M R B K
     * . . . . . . . .
     * . . . . . . . .
     * . . . . . . . .
     *
     * (B = Blue, K = Black)
     * </code></pre>
     * <p>Each bar should take up 1/8 of the sensor pixel array width.
     * When this is not possible, the bar size should be rounded
     * down to the nearest integer and the pattern can repeat
     * on the right side.</p>
     * <p>Each bar's height must always take up the full sensor
     * pixel array height.</p>
     * <p>Each pixel in this test pattern must be set to either
     * 0% intensity or 100% intensity.</p>
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final int SENSOR_TEST_PATTERN_MODE_COLOR_BARS = 2;

    /**
     * <p>The test pattern is similar to COLOR_BARS, except that
     * each bar should start at its specified color at the top,
     * and fade to gray at the bottom.</p>
     * <p>Furthermore each bar is further subdivided into a left and
     * right half. The left half should have a smooth gradient,
     * and the right half should have a quantized gradient.</p>
     * <p>In particular, the right half's should consist of blocks of the
     * same color for 1/16th active sensor pixel array width.</p>
     * <p>The least significant bits in the quantized gradient should
     * be copied from the most significant bits of the smooth gradient.</p>
     * <p>The height of each bar should always be a multiple of 128.
     * When this is not the case, the pattern should repeat at the bottom
     * of the image.</p>
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final int SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY = 3;

    /**
     * <p>All pixel data is replaced by a pseudo-random sequence
     * generated from a PN9 512-bit sequence (typically implemented
     * in hardware with a linear feedback shift register).</p>
     * <p>The generator should be reset at the beginning of each frame,
     * and thus each subsequent raw frame with this test pattern should
     * be exactly the same as the last.</p>
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final int SENSOR_TEST_PATTERN_MODE_PN9 = 4;

    /**
     * <p>All pixel data is replaced by 0% intensity (black) values.</p>
     * <p>This test pattern is identical to SOLID_COLOR with a value of <code>[0, 0, 0, 0]</code> for
     * {@link CaptureRequest#SENSOR_TEST_PATTERN_DATA android.sensor.testPatternData}.  It is recommended that devices implement full
     * SOLID_COLOR support instead, but BLACK can be used to provide minimal support for a
     * test pattern suitable for privacy use cases.</p>
     *
     * @see CaptureRequest#SENSOR_TEST_PATTERN_DATA
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     * @hide
     */
    @TestApi
    public static final int SENSOR_TEST_PATTERN_MODE_BLACK = 5;

    /**
     * <p>The first custom test pattern. All custom patterns that are
     * available only on this camera device are at least this numeric
     * value.</p>
     * <p>All of the custom test patterns will be static
     * (that is the raw image must not vary from frame to frame).</p>
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    public static final int SENSOR_TEST_PATTERN_MODE_CUSTOM1 = 256;

    //
    // Enumeration values for CaptureRequest#SENSOR_PIXEL_MODE
    //

    /**
     * <p>This is the default sensor pixel mode.</p>
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     */
    public static final int SENSOR_PIXEL_MODE_DEFAULT = 0;

    /**
     * <p>In this mode, sensors typically do not bin pixels, as a result can offer larger
     * image sizes.</p>
     * @see CaptureRequest#SENSOR_PIXEL_MODE
     */
    public static final int SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION = 1;

    //
    // Enumeration values for CaptureRequest#SHADING_MODE
    //

    /**
     * <p>No lens shading correction is applied.</p>
     * @see CaptureRequest#SHADING_MODE
     */
    public static final int SHADING_MODE_OFF = 0;

    /**
     * <p>Apply lens shading corrections, without slowing
     * frame rate relative to sensor raw output</p>
     * @see CaptureRequest#SHADING_MODE
     */
    public static final int SHADING_MODE_FAST = 1;

    /**
     * <p>Apply high-quality lens shading correction, at the
     * cost of possibly reduced frame rate.</p>
     * @see CaptureRequest#SHADING_MODE
     */
    public static final int SHADING_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureRequest#STATISTICS_FACE_DETECT_MODE
    //

    /**
     * <p>Do not include face detection statistics in capture
     * results.</p>
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    public static final int STATISTICS_FACE_DETECT_MODE_OFF = 0;

    /**
     * <p>Return face rectangle and confidence values only.</p>
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    public static final int STATISTICS_FACE_DETECT_MODE_SIMPLE = 1;

    /**
     * <p>Return all face
     * metadata.</p>
     * <p>In this mode, face rectangles, scores, landmarks, and face IDs are all valid.</p>
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    public static final int STATISTICS_FACE_DETECT_MODE_FULL = 2;

    //
    // Enumeration values for CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
    //

    /**
     * <p>Do not include a lens shading map in the capture result.</p>
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     */
    public static final int STATISTICS_LENS_SHADING_MAP_MODE_OFF = 0;

    /**
     * <p>Include a lens shading map in the capture result.</p>
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     */
    public static final int STATISTICS_LENS_SHADING_MAP_MODE_ON = 1;

    //
    // Enumeration values for CaptureRequest#STATISTICS_OIS_DATA_MODE
    //

    /**
     * <p>Do not include OIS data in the capture result.</p>
     * @see CaptureRequest#STATISTICS_OIS_DATA_MODE
     */
    public static final int STATISTICS_OIS_DATA_MODE_OFF = 0;

    /**
     * <p>Include OIS data in the capture result.</p>
     * <p>{@link CaptureResult#STATISTICS_OIS_SAMPLES android.statistics.oisSamples} provides OIS sample data in the
     * output result metadata.</p>
     *
     * @see CaptureResult#STATISTICS_OIS_SAMPLES
     * @see CaptureRequest#STATISTICS_OIS_DATA_MODE
     */
    public static final int STATISTICS_OIS_DATA_MODE_ON = 1;

    //
    // Enumeration values for CaptureRequest#TONEMAP_MODE
    //

    /**
     * <p>Use the tone mapping curve specified in
     * the {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}* entries.</p>
     * <p>All color enhancement and tonemapping must be disabled, except
     * for applying the tonemapping curve specified by
     * {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}.</p>
     * <p>Must not slow down frame rate relative to raw
     * sensor output.</p>
     *
     * @see CaptureRequest#TONEMAP_CURVE
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_CONTRAST_CURVE = 0;

    /**
     * <p>Advanced gamma mapping and color enhancement may be applied, without
     * reducing frame rate compared to raw sensor output.</p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_FAST = 1;

    /**
     * <p>High-quality gamma mapping and color enhancement will be applied, at
     * the cost of possibly reduced frame rate compared to raw sensor output.</p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_HIGH_QUALITY = 2;

    /**
     * <p>Use the gamma value specified in {@link CaptureRequest#TONEMAP_GAMMA android.tonemap.gamma} to perform
     * tonemapping.</p>
     * <p>All color enhancement and tonemapping must be disabled, except
     * for applying the tonemapping curve specified by {@link CaptureRequest#TONEMAP_GAMMA android.tonemap.gamma}.</p>
     * <p>Must not slow down frame rate relative to raw sensor output.</p>
     *
     * @see CaptureRequest#TONEMAP_GAMMA
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_GAMMA_VALUE = 3;

    /**
     * <p>Use the preset tonemapping curve specified in
     * {@link CaptureRequest#TONEMAP_PRESET_CURVE android.tonemap.presetCurve} to perform tonemapping.</p>
     * <p>All color enhancement and tonemapping must be disabled, except
     * for applying the tonemapping curve specified by
     * {@link CaptureRequest#TONEMAP_PRESET_CURVE android.tonemap.presetCurve}.</p>
     * <p>Must not slow down frame rate relative to raw sensor output.</p>
     *
     * @see CaptureRequest#TONEMAP_PRESET_CURVE
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_PRESET_CURVE = 4;

    //
    // Enumeration values for CaptureRequest#TONEMAP_PRESET_CURVE
    //

    /**
     * <p>Tonemapping curve is defined by sRGB</p>
     * @see CaptureRequest#TONEMAP_PRESET_CURVE
     */
    public static final int TONEMAP_PRESET_CURVE_SRGB = 0;

    /**
     * <p>Tonemapping curve is defined by ITU-R BT.709</p>
     * @see CaptureRequest#TONEMAP_PRESET_CURVE
     */
    public static final int TONEMAP_PRESET_CURVE_REC709 = 1;

    //
    // Enumeration values for CaptureRequest#DISTORTION_CORRECTION_MODE
    //

    /**
     * <p>No distortion correction is applied.</p>
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     */
    public static final int DISTORTION_CORRECTION_MODE_OFF = 0;

    /**
     * <p>Lens distortion correction is applied without reducing frame rate
     * relative to sensor output. It may be the same as OFF if distortion correction would
     * reduce frame rate relative to sensor.</p>
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     */
    public static final int DISTORTION_CORRECTION_MODE_FAST = 1;

    /**
     * <p>High-quality distortion correction is applied, at the cost of
     * possibly reduced frame rate relative to sensor output.</p>
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     */
    public static final int DISTORTION_CORRECTION_MODE_HIGH_QUALITY = 2;

    //
    // Enumeration values for CaptureResult#CONTROL_AE_STATE
    //

    /**
     * <p>AE is off or recently reset.</p>
     * <p>When a camera device is opened, it starts in
     * this state. This is a transient state, the camera device may skip reporting
     * this state in capture result.</p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_INACTIVE = 0;

    /**
     * <p>AE doesn't yet have a good set of control values
     * for the current scene.</p>
     * <p>This is a transient state, the camera device may skip
     * reporting this state in capture result.</p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_SEARCHING = 1;

    /**
     * <p>AE has a good set of control values for the
     * current scene.</p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_CONVERGED = 2;

    /**
     * <p>AE has been locked.</p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_LOCKED = 3;

    /**
     * <p>AE has a good set of control values, but flash
     * needs to be fired for good quality still
     * capture.</p>
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_FLASH_REQUIRED = 4;

    /**
     * <p>AE has been asked to do a precapture sequence
     * and is currently executing it.</p>
     * <p>Precapture can be triggered through setting
     * {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger} to START. Currently
     * active and completed (if it causes camera device internal AE lock) precapture
     * metering sequence can be canceled through setting
     * {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger} to CANCEL.</p>
     * <p>Once PRECAPTURE completes, AE will transition to CONVERGED
     * or FLASH_REQUIRED as appropriate. This is a transient
     * state, the camera device may skip reporting this state in
     * capture result.</p>
     *
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureResult#CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_PRECAPTURE = 5;

    //
    // Enumeration values for CaptureResult#CONTROL_AF_STATE
    //

    /**
     * <p>AF is off or has not yet tried to scan/been asked
     * to scan.</p>
     * <p>When a camera device is opened, it starts in this
     * state. This is a transient state, the camera device may
     * skip reporting this state in capture
     * result.</p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_INACTIVE = 0;

    /**
     * <p>AF is currently performing an AF scan initiated the
     * camera device in a continuous autofocus mode.</p>
     * <p>Only used by CONTINUOUS_* AF modes. This is a transient
     * state, the camera device may skip reporting this state in
     * capture result.</p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_PASSIVE_SCAN = 1;

    /**
     * <p>AF currently believes it is in focus, but may
     * restart scanning at any time.</p>
     * <p>Only used by CONTINUOUS_* AF modes. This is a transient
     * state, the camera device may skip reporting this state in
     * capture result.</p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_PASSIVE_FOCUSED = 2;

    /**
     * <p>AF is performing an AF scan because it was
     * triggered by AF trigger.</p>
     * <p>Only used by AUTO or MACRO AF modes. This is a transient
     * state, the camera device may skip reporting this state in
     * capture result.</p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_ACTIVE_SCAN = 3;

    /**
     * <p>AF believes it is focused correctly and has locked
     * focus.</p>
     * <p>This state is reached only after an explicit START AF trigger has been
     * sent ({@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}), when good focus has been obtained.</p>
     * <p>The lens will remain stationary until the AF mode ({@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}) is changed or
     * a new AF trigger is sent to the camera device ({@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}).</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_FOCUSED_LOCKED = 4;

    /**
     * <p>AF has failed to focus successfully and has locked
     * focus.</p>
     * <p>This state is reached only after an explicit START AF trigger has been
     * sent ({@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}), when good focus cannot be obtained.</p>
     * <p>The lens will remain stationary until the AF mode ({@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}) is changed or
     * a new AF trigger is sent to the camera device ({@link CaptureRequest#CONTROL_AF_TRIGGER android.control.afTrigger}).</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_AF_TRIGGER
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_NOT_FOCUSED_LOCKED = 5;

    /**
     * <p>AF finished a passive scan without finding focus,
     * and may restart scanning at any time.</p>
     * <p>Only used by CONTINUOUS_* AF modes. This is a transient state, the camera
     * device may skip reporting this state in capture result.</p>
     * <p>LEGACY camera devices do not support this state. When a passive
     * scan has finished, it will always go to PASSIVE_FOCUSED.</p>
     * @see CaptureResult#CONTROL_AF_STATE
     */
    public static final int CONTROL_AF_STATE_PASSIVE_UNFOCUSED = 6;

    //
    // Enumeration values for CaptureResult#CONTROL_AWB_STATE
    //

    /**
     * <p>AWB is not in auto mode, or has not yet started metering.</p>
     * <p>When a camera device is opened, it starts in this
     * state. This is a transient state, the camera device may
     * skip reporting this state in capture
     * result.</p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_INACTIVE = 0;

    /**
     * <p>AWB doesn't yet have a good set of control
     * values for the current scene.</p>
     * <p>This is a transient state, the camera device
     * may skip reporting this state in capture result.</p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_SEARCHING = 1;

    /**
     * <p>AWB has a good set of control values for the
     * current scene.</p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_CONVERGED = 2;

    /**
     * <p>AWB has been locked.</p>
     * @see CaptureResult#CONTROL_AWB_STATE
     */
    public static final int CONTROL_AWB_STATE_LOCKED = 3;

    //
    // Enumeration values for CaptureResult#CONTROL_AF_SCENE_CHANGE
    //

    /**
     * <p>Scene change is not detected within the AF region(s).</p>
     * @see CaptureResult#CONTROL_AF_SCENE_CHANGE
     */
    public static final int CONTROL_AF_SCENE_CHANGE_NOT_DETECTED = 0;

    /**
     * <p>Scene change is detected within the AF region(s).</p>
     * @see CaptureResult#CONTROL_AF_SCENE_CHANGE
     */
    public static final int CONTROL_AF_SCENE_CHANGE_DETECTED = 1;

    //
    // Enumeration values for CaptureResult#CONTROL_AUTOFRAMING_STATE
    //

    /**
     * <p>Auto-framing is inactive.</p>
     * @see CaptureResult#CONTROL_AUTOFRAMING_STATE
     */
    public static final int CONTROL_AUTOFRAMING_STATE_INACTIVE = 0;

    /**
     * <p>Auto-framing is in process - either zooming in, zooming out or pan is taking place.</p>
     * @see CaptureResult#CONTROL_AUTOFRAMING_STATE
     */
    public static final int CONTROL_AUTOFRAMING_STATE_FRAMING = 1;

    /**
     * <p>Auto-framing has reached a stable state (frame/fov is not being adjusted). The state
     * may transition back to FRAMING if the scene changes.</p>
     * @see CaptureResult#CONTROL_AUTOFRAMING_STATE
     */
    public static final int CONTROL_AUTOFRAMING_STATE_CONVERGED = 2;

    //
    // Enumeration values for CaptureResult#CONTROL_LOW_LIGHT_BOOST_STATE
    //

    /**
     * <p>The AE mode 'ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY' is enabled but not applied.</p>
     * @see CaptureResult#CONTROL_LOW_LIGHT_BOOST_STATE
     */
    @FlaggedApi(Flags.FLAG_CAMERA_AE_MODE_LOW_LIGHT_BOOST)
    public static final int CONTROL_LOW_LIGHT_BOOST_STATE_INACTIVE = 0;

    /**
     * <p>The AE mode 'ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY' is enabled and applied.</p>
     * @see CaptureResult#CONTROL_LOW_LIGHT_BOOST_STATE
     */
    @FlaggedApi(Flags.FLAG_CAMERA_AE_MODE_LOW_LIGHT_BOOST)
    public static final int CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE = 1;

    //
    // Enumeration values for CaptureResult#FLASH_STATE
    //

    /**
     * <p>No flash on camera.</p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_UNAVAILABLE = 0;

    /**
     * <p>Flash is charging and cannot be fired.</p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_CHARGING = 1;

    /**
     * <p>Flash is ready to fire.</p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_READY = 2;

    /**
     * <p>Flash fired for this capture.</p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_FIRED = 3;

    /**
     * <p>Flash partially illuminated this frame.</p>
     * <p>This is usually due to the next or previous frame having
     * the flash fire, and the flash spilling into this capture
     * due to hardware limitations.</p>
     * @see CaptureResult#FLASH_STATE
     */
    public static final int FLASH_STATE_PARTIAL = 4;

    //
    // Enumeration values for CaptureResult#LENS_STATE
    //

    /**
     * <p>The lens parameters ({@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength}, {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance},
     * {@link CaptureRequest#LENS_FILTER_DENSITY android.lens.filterDensity} and {@link CaptureRequest#LENS_APERTURE android.lens.aperture}) are not changing.</p>
     *
     * @see CaptureRequest#LENS_APERTURE
     * @see CaptureRequest#LENS_FILTER_DENSITY
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CaptureResult#LENS_STATE
     */
    public static final int LENS_STATE_STATIONARY = 0;

    /**
     * <p>One or several of the lens parameters
     * ({@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength}, {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance},
     * {@link CaptureRequest#LENS_FILTER_DENSITY android.lens.filterDensity} or {@link CaptureRequest#LENS_APERTURE android.lens.aperture}) is
     * currently changing.</p>
     *
     * @see CaptureRequest#LENS_APERTURE
     * @see CaptureRequest#LENS_FILTER_DENSITY
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CaptureResult#LENS_STATE
     */
    public static final int LENS_STATE_MOVING = 1;

    //
    // Enumeration values for CaptureResult#STATISTICS_SCENE_FLICKER
    //

    /**
     * <p>The camera device does not detect any flickering illumination
     * in the current scene.</p>
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     */
    public static final int STATISTICS_SCENE_FLICKER_NONE = 0;

    /**
     * <p>The camera device detects illumination flickering at 50Hz
     * in the current scene.</p>
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     */
    public static final int STATISTICS_SCENE_FLICKER_50HZ = 1;

    /**
     * <p>The camera device detects illumination flickering at 60Hz
     * in the current scene.</p>
     * @see CaptureResult#STATISTICS_SCENE_FLICKER
     */
    public static final int STATISTICS_SCENE_FLICKER_60HZ = 2;

    //
    // Enumeration values for CaptureResult#SYNC_FRAME_NUMBER
    //

    /**
     * <p>The current result is not yet fully synchronized to any request.</p>
     * <p>Synchronization is in progress, and reading metadata from this
     * result may include a mix of data that have taken effect since the
     * last synchronization time.</p>
     * <p>In some future result, within {@link CameraCharacteristics#SYNC_MAX_LATENCY android.sync.maxLatency} frames,
     * this value will update to the actual frame number frame number
     * the result is guaranteed to be synchronized to (as long as the
     * request settings remain constant).</p>
     *
     * @see CameraCharacteristics#SYNC_MAX_LATENCY
     * @see CaptureResult#SYNC_FRAME_NUMBER
     * @hide
     */
    public static final int SYNC_FRAME_NUMBER_CONVERGING = -1;

    /**
     * <p>The current result's synchronization status is unknown.</p>
     * <p>The result may have already converged, or it may be in
     * progress.  Reading from this result may include some mix
     * of settings from past requests.</p>
     * <p>After a settings change, the new settings will eventually all
     * take effect for the output buffers and results. However, this
     * value will not change when that happens. Altering settings
     * rapidly may provide outcomes using mixes of settings from recent
     * requests.</p>
     * <p>This value is intended primarily for backwards compatibility with
     * the older camera implementations (for android.hardware.Camera).</p>
     * @see CaptureResult#SYNC_FRAME_NUMBER
     * @hide
     */
    public static final int SYNC_FRAME_NUMBER_UNKNOWN = -2;

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/

}
