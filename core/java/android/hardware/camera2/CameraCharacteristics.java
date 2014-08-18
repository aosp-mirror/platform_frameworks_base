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
import android.hardware.camera2.impl.PublicKey;
import android.hardware.camera2.impl.SyntheticKey;
import android.hardware.camera2.utils.TypeReference;
import android.util.Rational;

import java.util.Collections;
import java.util.List;

/**
 * <p>The properties describing a
 * {@link CameraDevice CameraDevice}.</p>
 *
 * <p>These properties are fixed for a given CameraDevice, and can be queried
 * through the {@link CameraManager CameraManager}
 * interface with {@link CameraManager#getCameraCharacteristics}.</p>
 *
 * <p>{@link CameraCharacteristics} objects are immutable.</p>
 *
 * @see CameraDevice
 * @see CameraManager
 */
public final class CameraCharacteristics extends CameraMetadata<CameraCharacteristics.Key<?>> {

    /**
     * A {@code Key} is used to do camera characteristics field lookups with
     * {@link CameraCharacteristics#get}.
     *
     * <p>For example, to get the stream configuration map:
     * <code><pre>
     * StreamConfigurationMap map = cameraCharacteristics.get(
     *      CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
     * </pre></code>
     * </p>
     *
     * <p>To enumerate over all possible keys for {@link CameraCharacteristics}, see
     * {@link CameraCharacteristics#getKeys()}.</p>
     *
     * @see CameraCharacteristics#get
     * @see CameraCharacteristics#getKeys()
     */
    public static final class Key<T> {
        private final CameraMetadataNative.Key<T> mKey;

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        public Key(String name, Class<T> type) {
            mKey = new CameraMetadataNative.Key<T>(name,  type);
        }

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        public Key(String name, TypeReference<T> typeReference) {
            mKey = new CameraMetadataNative.Key<T>(name,  typeReference);
        }

        /**
         * Return a camelCase, period separated name formatted like:
         * {@code "root.section[.subsections].name"}.
         *
         * <p>Built-in keys exposed by the Android SDK are always prefixed with {@code "android."};
         * keys that are device/platform-specific are prefixed with {@code "com."}.</p>
         *
         * <p>For example, {@code CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP} would
         * have a name of {@code "android.scaler.streamConfigurationMap"}; whereas a device
         * specific key might look like {@code "com.google.nexus.data.private"}.</p>
         *
         * @return String representation of the key name
         */
        public String getName() {
            return mKey.getName();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public final int hashCode() {
            return mKey.hashCode();
        }

        /**
         * {@inheritDoc}
         */
        @SuppressWarnings("unchecked")
        @Override
        public final boolean equals(Object o) {
            return o instanceof Key && ((Key<T>)o).mKey.equals(mKey);
        }

        /**
         * Visible for CameraMetadataNative implementation only; do not use.
         *
         * TODO: Make this private or remove it altogether.
         *
         * @hide
         */
        public CameraMetadataNative.Key<T> getNativeKey() {
            return mKey;
        }

        @SuppressWarnings({
                "unused", "unchecked"
        })
        private Key(CameraMetadataNative.Key<?> nativeKey) {
            mKey = (CameraMetadataNative.Key<T>) nativeKey;
        }
    }

    private final CameraMetadataNative mProperties;
    private List<CameraCharacteristics.Key<?>> mKeys;
    private List<CaptureRequest.Key<?>> mAvailableRequestKeys;
    private List<CaptureResult.Key<?>> mAvailableResultKeys;

    /**
     * Takes ownership of the passed-in properties object
     * @hide
     */
    public CameraCharacteristics(CameraMetadataNative properties) {
        mProperties = CameraMetadataNative.move(properties);
    }

    /**
     * Returns a copy of the underlying {@link CameraMetadataNative}.
     * @hide
     */
    public CameraMetadataNative getNativeCopy() {
        return new CameraMetadataNative(mProperties);
    }

    /**
     * Get a camera characteristics field value.
     *
     * <p>The field definitions can be
     * found in {@link CameraCharacteristics}.</p>
     *
     * <p>Querying the value for the same key more than once will return a value
     * which is equal to the previous queried value.</p>
     *
     * @throws IllegalArgumentException if the key was not valid
     *
     * @param key The characteristics field to read.
     * @return The value of that key, or {@code null} if the field is not set.
     */
    public <T> T get(Key<T> key) {
        return mProperties.get(key);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> T getProtected(Key<?> key) {
        return (T) mProperties.get(key);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SuppressWarnings("unchecked")
    @Override
    protected Class<Key<?>> getKeyClass() {
        Object thisClass = Key.class;
        return (Class<Key<?>>)thisClass;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Key<?>> getKeys() {
        // List of keys is immutable; cache the results after we calculate them
        if (mKeys != null) {
            return mKeys;
        }

        int[] filterTags = get(REQUEST_AVAILABLE_CHARACTERISTICS_KEYS);
        if (filterTags == null) {
            throw new AssertionError("android.request.availableCharacteristicsKeys must be non-null"
                    + " in the characteristics");
        }

        mKeys = Collections.unmodifiableList(
                getKeysStatic(getClass(), getKeyClass(), this, filterTags));
        return mKeys;
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
    @SuppressWarnings({"unchecked"})
    public List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys() {
        if (mAvailableRequestKeys == null) {
            Object crKey = CaptureRequest.Key.class;
            Class<CaptureRequest.Key<?>> crKeyTyped = (Class<CaptureRequest.Key<?>>)crKey;

            int[] filterTags = get(REQUEST_AVAILABLE_REQUEST_KEYS);
            if (filterTags == null) {
                throw new AssertionError("android.request.availableRequestKeys must be non-null "
                        + "in the characteristics");
            }
            mAvailableRequestKeys =
                    getAvailableKeyList(CaptureRequest.class, crKeyTyped, filterTags);
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
    @SuppressWarnings({"unchecked"})
    public List<CaptureResult.Key<?>> getAvailableCaptureResultKeys() {
        if (mAvailableResultKeys == null) {
            Object crKey = CaptureResult.Key.class;
            Class<CaptureResult.Key<?>> crKeyTyped = (Class<CaptureResult.Key<?>>)crKey;

            int[] filterTags = get(REQUEST_AVAILABLE_RESULT_KEYS);
            if (filterTags == null) {
                throw new AssertionError("android.request.availableResultKeys must be non-null "
                        + "in the characteristics");
            }
            mAvailableResultKeys = getAvailableKeyList(CaptureResult.class, crKeyTyped, filterTags);
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
     * @param keyClass The class of the metadata key, e.g. CaptureRequest.Key.class
     *
     * @return List of keys supported by this CameraDevice for metadataClass.
     *
     * @throws IllegalArgumentException if metadataClass is not a subclass of CameraMetadata
     */
    private <TKey> List<TKey>
    getAvailableKeyList(Class<?> metadataClass, Class<TKey> keyClass, int[] filterTags) {

        if (metadataClass.equals(CameraMetadata.class)) {
            throw new AssertionError(
                    "metadataClass must be a strict subclass of CameraMetadata");
        } else if (!CameraMetadata.class.isAssignableFrom(metadataClass)) {
            throw new AssertionError(
                    "metadataClass must be a subclass of CameraMetadata");
        }

        List<TKey> staticKeyList = CameraCharacteristics.<TKey>getKeysStatic(
                metadataClass, keyClass, /*instance*/null, filterTags);
        return Collections.unmodifiableList(staticKeyList);
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/


    /**
     * <p>The set of aberration correction modes supported by this camera device.</p>
     * <p>This metadata lists the valid modes for {@link CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE android.colorCorrection.aberrationMode}.
     * If no aberration correction modes are available for a device, this list will solely include
     * OFF mode.</p>
     * <p>For FULL capability device ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} <code>==</code> FULL), OFF must be
     * included.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
    public static final Key<int[]> COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES =
            new Key<int[]>("android.colorCorrection.availableAberrationModes", int[].class);

    /**
     * <p>The set of auto-exposure antibanding modes that are
     * supported by this camera device.</p>
     * <p>Not all of the auto-exposure anti-banding modes may be
     * supported by a given camera device. This field lists the
     * valid anti-banding modes that the application may request
     * for this camera device; they must include AUTO.</p>
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AE_AVAILABLE_ANTIBANDING_MODES =
            new Key<int[]>("android.control.aeAvailableAntibandingModes", int[].class);

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
     * <p>FULL mode camera devices always support OFF mode,
     * which enables application control of camera exposure time,
     * sensitivity, and frame duration.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AE_AVAILABLE_MODES =
            new Key<int[]>("android.control.aeAvailableModes", int[].class);

    /**
     * <p>List of frame rate ranges supported by the
     * auto-exposure (AE) algorithm/hardware</p>
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES =
            new Key<android.util.Range<Integer>[]>("android.control.aeAvailableTargetFpsRanges", new TypeReference<android.util.Range<Integer>[]>() {{ }});

    /**
     * <p>Maximum and minimum exposure compensation
     * setting, in counts of
     * {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep}.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>> CONTROL_AE_COMPENSATION_RANGE =
            new Key<android.util.Range<Integer>>("android.control.aeCompensationRange", new TypeReference<android.util.Range<Integer>>() {{ }});

    /**
     * <p>Smallest step by which exposure compensation
     * can be changed</p>
     */
    @PublicKey
    public static final Key<Rational> CONTROL_AE_COMPENSATION_STEP =
            new Key<Rational>("android.control.aeCompensationStep", Rational.class);

    /**
     * <p>List of auto-focus (AF) modes that can be
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
    @PublicKey
    public static final Key<int[]> CONTROL_AF_AVAILABLE_MODES =
            new Key<int[]>("android.control.afAvailableModes", int[].class);

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
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_EFFECTS =
            new Key<int[]>("android.control.availableEffects", int[].class);

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
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_SCENE_MODES =
            new Key<int[]>("android.control.availableSceneModes", int[].class);

    /**
     * <p>List of video stabilization modes that can
     * be supported</p>
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES =
            new Key<int[]>("android.control.availableVideoStabilizationModes", int[].class);

    /**
     * <p>The set of auto-white-balance modes ({@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode})
     * that are supported by this camera device.</p>
     * <p>Not all the auto-white-balance modes may be supported by a
     * given camera device. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} for this camera device.</p>
     * <p>All camera devices will support ON mode.</p>
     * <p>FULL mode camera devices will always support OFF mode,
     * which enables application control of white balance, by using
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform} and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}({@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} must be set to TRANSFORM_MATRIX).</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_AWB_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AWB_AVAILABLE_MODES =
            new Key<int[]>("android.control.awbAvailableModes", int[].class);

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
     * @hide
     */
    public static final Key<int[]> CONTROL_MAX_REGIONS =
            new Key<int[]>("android.control.maxRegions", int[].class);

    /**
     * <p>List of the maximum number of regions that can be used for metering in
     * auto-exposure (AE);
     * this corresponds to the the maximum number of elements in
     * {@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> CONTROL_MAX_REGIONS_AE =
            new Key<Integer>("android.control.maxRegionsAe", int.class);

    /**
     * <p>List of the maximum number of regions that can be used for metering in
     * auto-white balance (AWB);
     * this corresponds to the the maximum number of elements in
     * {@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}.</p>
     *
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> CONTROL_MAX_REGIONS_AWB =
            new Key<Integer>("android.control.maxRegionsAwb", int.class);

    /**
     * <p>List of the maximum number of regions that can be used for metering in
     * auto-focus (AF);
     * this corresponds to the the maximum number of elements in
     * {@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}.</p>
     *
     * @see CaptureRequest#CONTROL_AF_REGIONS
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> CONTROL_MAX_REGIONS_AF =
            new Key<Integer>("android.control.maxRegionsAf", int.class);

    /**
     * <p>List of available high speed video size and fps range configurations
     * supported by the camera device, in the format of (width, height, fps_min, fps_max).</p>
     * <p>When HIGH_SPEED_VIDEO is supported in {@link CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES android.control.availableSceneModes},
     * this metadata will list the supported high speed video size and fps range
     * configurations. All the sizes listed in this configuration will be a subset
     * of the sizes reported by StreamConfigurationMap#getOutputSizes for processed
     * non-stalling formats.</p>
     * <p>For the high speed video use case, where the application will set
     * {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode} to HIGH_SPEED_VIDEO in capture requests, the application must
     * select the video size and fps range from this metadata to configure the recording and
     * preview streams and setup the recording requests. For example, if the application intends
     * to do high speed recording, it can select the maximum size reported by this metadata to
     * configure output streams. Once the size is selected, application can filter this metadata
     * by selected size and get the supported fps ranges, and use these fps ranges to setup the
     * recording requests. Note that for the use case of multiple output streams, application
     * must select one unique size from this metadata to use. Otherwise a request error might
     * occur.</p>
     * <p>For normal video recording use case, where some application will NOT set
     * {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode} to HIGH_SPEED_VIDEO in capture requests, the fps ranges
     * reported in this metadata must not be used to setup capture requests, or it will cause
     * request error.</p>
     *
     * @see CameraCharacteristics#CONTROL_AVAILABLE_SCENE_MODES
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @hide
     */
    public static final Key<android.hardware.camera2.params.HighSpeedVideoConfiguration[]> CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS =
            new Key<android.hardware.camera2.params.HighSpeedVideoConfiguration[]>("android.control.availableHighSpeedVideoConfigurations", android.hardware.camera2.params.HighSpeedVideoConfiguration[].class);

    /**
     * <p>The set of edge enhancement modes supported by this camera device.</p>
     * <p>This tag lists the valid modes for {@link CaptureRequest#EDGE_MODE android.edge.mode}.</p>
     * <p>Full-capability camera devices must always support OFF and FAST.</p>
     *
     * @see CaptureRequest#EDGE_MODE
     */
    @PublicKey
    public static final Key<int[]> EDGE_AVAILABLE_EDGE_MODES =
            new Key<int[]>("android.edge.availableEdgeModes", int[].class);

    /**
     * <p>Whether this camera device has a
     * flash.</p>
     * <p>If no flash, none of the flash controls do
     * anything. All other metadata should return 0.</p>
     */
    @PublicKey
    public static final Key<Boolean> FLASH_INFO_AVAILABLE =
            new Key<Boolean>("android.flash.info.available", boolean.class);

    /**
     * <p>The set of hot pixel correction modes that are supported by this
     * camera device.</p>
     * <p>This tag lists valid modes for {@link CaptureRequest#HOT_PIXEL_MODE android.hotPixel.mode}.</p>
     * <p>FULL mode camera devices will always support FAST.</p>
     *
     * @see CaptureRequest#HOT_PIXEL_MODE
     */
    @PublicKey
    public static final Key<int[]> HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES =
            new Key<int[]>("android.hotPixel.availableHotPixelModes", int[].class);

    /**
     * <p>Supported resolutions for the JPEG thumbnail.</p>
     * <p>Below condiditions will be satisfied for this size list:</p>
     * <ul>
     * <li>The sizes will be sorted by increasing pixel area (width x height).
     * If several resolutions have the same area, they will be sorted by increasing width.</li>
     * <li>The aspect ratio of the largest thumbnail size will be same as the
     * aspect ratio of largest JPEG output size in android.scaler.availableStreamConfigurations.
     * The largest size is defined as the size that has the largest pixel area
     * in a given size list.</li>
     * <li>Each output JPEG size in android.scaler.availableStreamConfigurations will have at least
     * one corresponding size that has the same aspect ratio in availableThumbnailSizes,
     * and vice versa.</li>
     * <li>All non (0, 0) sizes will have non-zero widths and heights.</li>
     * </ul>
     */
    @PublicKey
    public static final Key<android.util.Size[]> JPEG_AVAILABLE_THUMBNAIL_SIZES =
            new Key<android.util.Size[]>("android.jpeg.availableThumbnailSizes", android.util.Size[].class);

    /**
     * <p>List of supported aperture
     * values.</p>
     * <p>If the camera device doesn't support variable apertures,
     * listed value will be the fixed aperture.</p>
     * <p>If the camera device supports variable apertures, the aperture value
     * in this list will be sorted in ascending order.</p>
     */
    @PublicKey
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
    @PublicKey
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
    @PublicKey
    public static final Key<float[]> LENS_INFO_AVAILABLE_FOCAL_LENGTHS =
            new Key<float[]>("android.lens.info.availableFocalLengths", float[].class);

    /**
     * <p>List containing a subset of the optical image
     * stabilization (OIS) modes specified in
     * {@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode}.</p>
     * <p>If OIS is not implemented for a given camera device, this will
     * contain only OFF.</p>
     *
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    @PublicKey
    public static final Key<int[]> LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION =
            new Key<int[]>("android.lens.info.availableOpticalStabilization", int[].class);

    /**
     * <p>Optional. Hyperfocal distance for this lens.</p>
     * <p>If the lens is not fixed focus, the camera device will report this
     * field when {@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} is APPROXIMATE or CALIBRATED.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     */
    @PublicKey
    public static final Key<Float> LENS_INFO_HYPERFOCAL_DISTANCE =
            new Key<Float>("android.lens.info.hyperfocalDistance", float.class);

    /**
     * <p>Shortest distance from frontmost surface
     * of the lens that can be focused correctly.</p>
     * <p>If the lens is fixed-focus, this should be
     * 0.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    @PublicKey
    public static final Key<Float> LENS_INFO_MINIMUM_FOCUS_DISTANCE =
            new Key<Float>("android.lens.info.minimumFocusDistance", float.class);

    /**
     * <p>Dimensions of lens shading map.</p>
     * <p>The map should be on the order of 30-40 rows and columns, and
     * must be smaller than 64x64.</p>
     * @hide
     */
    public static final Key<android.util.Size> LENS_INFO_SHADING_MAP_SIZE =
            new Key<android.util.Size>("android.lens.info.shadingMapSize", android.util.Size.class);

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
    @PublicKey
    public static final Key<Integer> LENS_INFO_FOCUS_DISTANCE_CALIBRATION =
            new Key<Integer>("android.lens.info.focusDistanceCalibration", int.class);

    /**
     * <p>Direction the camera faces relative to
     * device screen.</p>
     * @see #LENS_FACING_FRONT
     * @see #LENS_FACING_BACK
     */
    @PublicKey
    public static final Key<Integer> LENS_FACING =
            new Key<Integer>("android.lens.facing", int.class);

    /**
     * <p>The set of noise reduction modes supported by this camera device.</p>
     * <p>This tag lists the valid modes for {@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode}.</p>
     * <p>Full-capability camera devices must always support OFF and FAST.</p>
     *
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    @PublicKey
    public static final Key<int[]> NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES =
            new Key<int[]>("android.noiseReduction.availableNoiseReductionModes", int[].class);

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
     * @deprecated
     * @hide
     */
    @Deprecated
    public static final Key<Byte> QUIRKS_USE_PARTIAL_RESULT =
            new Key<Byte>("android.quirks.usePartialResult", byte.class);

    /**
     * <p>The maximum numbers of different types of output streams
     * that can be configured and used simultaneously by a camera device.</p>
     * <p>This is a 3 element tuple that contains the max number of output simultaneous
     * streams for raw sensor, processed (but not stalling), and processed (and stalling)
     * formats respectively. For example, assuming that JPEG is typically a processed and
     * stalling stream, if max raw sensor format output stream number is 1, max YUV streams
     * number is 3, and max JPEG stream number is 2, then this tuple should be <code>(1, 3, 2)</code>.</p>
     * <p>This lists the upper bound of the number of output streams supported by
     * the camera device. Using more streams simultaneously may require more hardware and
     * CPU resources that will consume more power. The image format for an output stream can
     * be any supported format provided by android.scaler.availableStreamConfigurations.
     * The formats defined in android.scaler.availableStreamConfigurations can be catergorized
     * into the 3 stream types as below:</p>
     * <ul>
     * <li>Processed (but stalling): any non-RAW format with a stallDurations &gt; 0.
     * Typically JPEG format (ImageFormat#JPEG).</li>
     * <li>Raw formats: ImageFormat#RAW_SENSOR, ImageFormat#RAW10 and ImageFormat#RAW_OPAQUE.</li>
     * <li>Processed (but not-stalling): any non-RAW format without a stall duration.
     * Typically ImageFormat#YUV_420_888, ImageFormat#NV21, ImageFormat#YV12.</li>
     * </ul>
     * @hide
     */
    public static final Key<int[]> REQUEST_MAX_NUM_OUTPUT_STREAMS =
            new Key<int[]>("android.request.maxNumOutputStreams", int[].class);

    /**
     * <p>The maximum numbers of different types of output streams
     * that can be configured and used simultaneously by a camera device
     * for any <code>RAW</code> formats.</p>
     * <p>This value contains the max number of output simultaneous
     * streams from the raw sensor.</p>
     * <p>This lists the upper bound of the number of output streams supported by
     * the camera device. Using more streams simultaneously may require more hardware and
     * CPU resources that will consume more power. The image format for this kind of an output stream can
     * be any <code>RAW</code> and supported format provided by {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}.</p>
     * <p>In particular, a <code>RAW</code> format is typically one of:</p>
     * <ul>
     * <li>ImageFormat#RAW_SENSOR</li>
     * <li>ImageFormat#RAW10</li>
     * <li>Opaque <code>RAW</code></li>
     * </ul>
     *
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> REQUEST_MAX_NUM_OUTPUT_RAW =
            new Key<Integer>("android.request.maxNumOutputRaw", int.class);

    /**
     * <p>The maximum numbers of different types of output streams
     * that can be configured and used simultaneously by a camera device
     * for any processed (but not-stalling) formats.</p>
     * <p>This value contains the max number of output simultaneous
     * streams for any processed (but not-stalling) formats.</p>
     * <p>This lists the upper bound of the number of output streams supported by
     * the camera device. Using more streams simultaneously may require more hardware and
     * CPU resources that will consume more power. The image format for this kind of an output stream can
     * be any non-<code>RAW</code> and supported format provided by {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}.</p>
     * <p>Processed (but not-stalling) is defined as any non-RAW format without a stall duration.
     * Typically:</p>
     * <ul>
     * <li>ImageFormat#YUV_420_888</li>
     * <li>ImageFormat#NV21</li>
     * <li>ImageFormat#YV12</li>
     * <li>Implementation-defined formats, i.e. StreamConfiguration#isOutputSupportedFor(Class)</li>
     * </ul>
     * <p>For full guarantees, query StreamConfigurationMap#getOutputStallDuration with
     * a processed format -- it will return 0 for a non-stalling stream.</p>
     *
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> REQUEST_MAX_NUM_OUTPUT_PROC =
            new Key<Integer>("android.request.maxNumOutputProc", int.class);

    /**
     * <p>The maximum numbers of different types of output streams
     * that can be configured and used simultaneously by a camera device
     * for any processed (and stalling) formats.</p>
     * <p>This value contains the max number of output simultaneous
     * streams for any processed (but not-stalling) formats.</p>
     * <p>This lists the upper bound of the number of output streams supported by
     * the camera device. Using more streams simultaneously may require more hardware and
     * CPU resources that will consume more power. The image format for this kind of an output stream can
     * be any non-<code>RAW</code> and supported format provided by {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}.</p>
     * <p>A processed and stalling format is defined as any non-RAW format with a stallDurations &gt; 0.
     * Typically only the <code>JPEG</code> format (ImageFormat#JPEG)</p>
     * <p>For full guarantees, query StreamConfigurationMap#getOutputStallDuration with
     * a processed format -- it will return a non-0 value for a stalling stream.</p>
     *
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> REQUEST_MAX_NUM_OUTPUT_PROC_STALLING =
            new Key<Integer>("android.request.maxNumOutputProcStalling", int.class);

    /**
     * <p>The maximum numbers of any type of input streams
     * that can be configured and used simultaneously by a camera device.</p>
     * <p>When set to 0, it means no input stream is supported.</p>
     * <p>The image format for a input stream can be any supported
     * format provided by
     * android.scaler.availableInputOutputFormatsMap. When using an
     * input stream, there must be at least one output stream
     * configured to to receive the reprocessed images.</p>
     * <p>For example, for Zero Shutter Lag (ZSL) still capture use case, the input
     * stream image format will be RAW_OPAQUE, the associated output stream image format
     * should be JPEG.</p>
     * @hide
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
    @PublicKey
    public static final Key<Byte> REQUEST_PIPELINE_MAX_DEPTH =
            new Key<Byte>("android.request.pipelineMaxDepth", byte.class);

    /**
     * <p>Defines how many sub-components
     * a result will be composed of.</p>
     * <p>In order to combat the pipeline latency, partial results
     * may be delivered to the application layer from the camera device as
     * soon as they are available.</p>
     * <p>Optional; defaults to 1. A value of 1 means that partial
     * results are not supported, and only the final TotalCaptureResult will
     * be produced by the camera device.</p>
     * <p>A typical use case for this might be: after requesting an
     * auto-focus (AF) lock the new AF state might be available 50%
     * of the way through the pipeline.  The camera device could
     * then immediately dispatch this state via a partial result to
     * the application, and the rest of the metadata via later
     * partial results.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    @PublicKey
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
     * <p>The following capabilities are guaranteed to be available on
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} <code>==</code> FULL devices:</p>
     * <ul>
     * <li>MANUAL_SENSOR</li>
     * <li>MANUAL_POST_PROCESSING</li>
     * </ul>
     * <p>Other capabilities may be available on either FULL or LIMITED
     * devices, but the application should query this field to be sure.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see #REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
     * @see #REQUEST_AVAILABLE_CAPABILITIES_RAW
     */
    @PublicKey
    public static final Key<int[]> REQUEST_AVAILABLE_CAPABILITIES =
            new Key<int[]>("android.request.availableCapabilities", int[].class);

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
     * <li>android.statistics.lensShadingMap (non-null iff {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} == ON)</li>
     * </ul>
     * <p>(Those sometimes-null keys should nevertheless be listed here
     * if they are available.)</p>
     * <p>This field can be used to query the feature set of a camera device
     * at a more granular level than capabilities. This is especially
     * important for optional keys that are not listed under any capability
     * in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
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
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_CHARACTERISTICS_KEYS =
            new Key<int[]>("android.request.availableCharacteristicsKeys", int[].class);

    /**
     * <p>The list of image formats that are supported by this
     * camera device for output streams.</p>
     * <p>All camera devices will support JPEG and YUV_420_888 formats.</p>
     * <p>When set to YUV_420_888, application can access the YUV420 data directly.</p>
     * @deprecated
     * @hide
     */
    @Deprecated
    public static final Key<int[]> SCALER_AVAILABLE_FORMATS =
            new Key<int[]>("android.scaler.availableFormats", int[].class);

    /**
     * <p>The minimum frame duration that is supported
     * for each resolution in android.scaler.availableJpegSizes.</p>
     * <p>This corresponds to the minimum steady-state frame duration when only
     * that JPEG stream is active and captured in a burst, with all
     * processing (typically in android.*.mode) set to FAST.</p>
     * <p>When multiple streams are configured, the minimum
     * frame duration will be &gt;= max(individual stream min
     * durations)</p>
     * @deprecated
     * @hide
     */
    @Deprecated
    public static final Key<long[]> SCALER_AVAILABLE_JPEG_MIN_DURATIONS =
            new Key<long[]>("android.scaler.availableJpegMinDurations", long[].class);

    /**
     * <p>The JPEG resolutions that are supported by this camera device.</p>
     * <p>The resolutions are listed as <code>(width, height)</code> pairs. All camera devices will support
     * sensor maximum resolution (defined by {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}).</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @deprecated
     * @hide
     */
    @Deprecated
    public static final Key<android.util.Size[]> SCALER_AVAILABLE_JPEG_SIZES =
            new Key<android.util.Size[]>("android.scaler.availableJpegSizes", android.util.Size[].class);

    /**
     * <p>The maximum ratio between both active area width
     * and crop region width, and active area height and
     * crop region height.</p>
     * <p>This represents the maximum amount of zooming possible by
     * the camera device, or equivalently, the minimum cropping
     * window size.</p>
     * <p>Crop regions that have a width or height that is smaller
     * than this ratio allows will be rounded up to the minimum
     * allowed size by the camera device.</p>
     */
    @PublicKey
    public static final Key<Float> SCALER_AVAILABLE_MAX_DIGITAL_ZOOM =
            new Key<Float>("android.scaler.availableMaxDigitalZoom", float.class);

    /**
     * <p>For each available processed output size (defined in
     * android.scaler.availableProcessedSizes), this property lists the
     * minimum supportable frame duration for that size.</p>
     * <p>This should correspond to the frame duration when only that processed
     * stream is active, with all processing (typically in android.*.mode)
     * set to FAST.</p>
     * <p>When multiple streams are configured, the minimum frame duration will
     * be &gt;= max(individual stream min durations).</p>
     * @deprecated
     * @hide
     */
    @Deprecated
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
     * @deprecated
     * @hide
     */
    @Deprecated
    public static final Key<android.util.Size[]> SCALER_AVAILABLE_PROCESSED_SIZES =
            new Key<android.util.Size[]>("android.scaler.availableProcessedSizes", android.util.Size[].class);

    /**
     * <p>The mapping of image formats that are supported by this
     * camera device for input streams, to their corresponding output formats.</p>
     * <p>All camera devices with at least 1
     * android.request.maxNumInputStreams will have at least one
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
     * <td align="left">RAW</td>
     * </tr>
     * <tr>
     * <td align="left">RAW16</td>
     * <td align="left">YUV_420_888</td>
     * <td align="left">RAW</td>
     * </tr>
     * <tr>
     * <td align="left">RAW16</td>
     * <td align="left">JPEG</td>
     * <td align="left">RAW</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>For ZSL-capable camera devices, using the RAW_OPAQUE format
     * as either input or output will never hurt maximum frame rate (i.e.
     * StreamConfigurationMap#getOutputStallDuration(int,Size)
     * for a <code>format =</code> RAW_OPAQUE is always 0).</p>
     * <p>Attempting to configure an input stream with output streams not
     * listed as available in this map is not valid.</p>
     * <p>TODO: typedef to ReprocessFormatMap</p>
     * @hide
     */
    public static final Key<int[]> SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP =
            new Key<int[]>("android.scaler.availableInputOutputFormatsMap", int[].class);

    /**
     * <p>The available stream configurations that this
     * camera device supports
     * (i.e. format, width, height, output/input stream).</p>
     * <p>The configurations are listed as <code>(format, width, height, input?)</code>
     * tuples.</p>
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
     * android.scaler.availableInputOutputFormatsMap.</p>
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
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfiguration[]> SCALER_AVAILABLE_STREAM_CONFIGURATIONS =
            new Key<android.hardware.camera2.params.StreamConfiguration[]>("android.scaler.availableStreamConfigurations", android.hardware.camera2.params.StreamConfiguration[].class);

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
     * android.scaler.availableStallDurations for more details about
     * calculating the max frame rate.</p>
     * <p>(Keep in sync with
     * StreamConfigurationMap#getOutputMinFrameDuration)</p>
     *
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfigurationDuration[]> SCALER_AVAILABLE_MIN_FRAME_DURATIONS =
            new Key<android.hardware.camera2.params.StreamConfigurationDuration[]>("android.scaler.availableMinFrameDurations", android.hardware.camera2.params.StreamConfigurationDuration[].class);

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
     * <li>ImageFormat#JPEG</li>
     * <li>ImageFormat#RAW_SENSOR</li>
     * </ul>
     * <p>The following formats will never have a stall duration:</p>
     * <ul>
     * <li>ImageFormat#YUV_420_888</li>
     * </ul>
     * <p>All other formats may or may not have an allowed stall duration on
     * a per-capability basis; refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}
     * for more details.</p>
     * <p>See {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} for more information about
     * calculating the max frame rate (absent stalls).</p>
     * <p>(Keep up to date with
     * StreamConfigurationMap#getOutputStallDuration(int, Size) )</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfigurationDuration[]> SCALER_AVAILABLE_STALL_DURATIONS =
            new Key<android.hardware.camera2.params.StreamConfigurationDuration[]>("android.scaler.availableStallDurations", android.hardware.camera2.params.StreamConfigurationDuration[].class);

    /**
     * <p>The available stream configurations that this
     * camera device supports; also includes the minimum frame durations
     * and the stall durations for each format/size combination.</p>
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
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     */
    @PublicKey
    @SyntheticKey
    public static final Key<android.hardware.camera2.params.StreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP =
            new Key<android.hardware.camera2.params.StreamConfigurationMap>("android.scaler.streamConfigurationMap", android.hardware.camera2.params.StreamConfigurationMap.class);

    /**
     * <p>The crop type that this camera device supports.</p>
     * <p>When passing a non-centered crop region ({@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}) to a camera
     * device that only supports CENTER_ONLY cropping, the camera device will move the
     * crop region to the center of the sensor active array ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize})
     * and keep the crop region width and height unchanged. The camera device will return the
     * final used crop region in metadata result {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}.</p>
     * <p>Camera devices that support FREEFORM cropping will support any crop region that
     * is inside of the active array. The camera device will apply the same crop region and
     * return the final used crop region in capture result metadata {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}.</p>
     * <p>FULL capability devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} <code>==</code> FULL) will support
     * FREEFORM cropping.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see #SCALER_CROPPING_TYPE_CENTER_ONLY
     * @see #SCALER_CROPPING_TYPE_FREEFORM
     */
    @PublicKey
    public static final Key<Integer> SCALER_CROPPING_TYPE =
            new Key<Integer>("android.scaler.croppingType", int.class);

    /**
     * <p>Area of raw data which corresponds to only
     * active pixels.</p>
     * <p>It is smaller or equal to
     * sensor full pixel array, which could include the black calibration pixels.</p>
     */
    @PublicKey
    public static final Key<android.graphics.Rect> SENSOR_INFO_ACTIVE_ARRAY_SIZE =
            new Key<android.graphics.Rect>("android.sensor.info.activeArraySize", android.graphics.Rect.class);

    /**
     * <p>Range of valid sensitivities.</p>
     * <p>The minimum and maximum valid values for the
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity} control.</p>
     * <p>The values are the standard ISO sensitivity values,
     * as defined in ISO 12232:2006.</p>
     *
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>> SENSOR_INFO_SENSITIVITY_RANGE =
            new Key<android.util.Range<Integer>>("android.sensor.info.sensitivityRange", new TypeReference<android.util.Range<Integer>>() {{ }});

    /**
     * <p>The arrangement of color filters on sensor;
     * represents the colors in the top-left 2x2 section of
     * the sensor, in reading order.</p>
     * @see #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
     * @see #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG
     * @see #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG
     * @see #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR
     * @see #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB
     */
    @PublicKey
    public static final Key<Integer> SENSOR_INFO_COLOR_FILTER_ARRANGEMENT =
            new Key<Integer>("android.sensor.info.colorFilterArrangement", int.class);

    /**
     * <p>Range of valid exposure
     * times used by {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime}.</p>
     * <p>The min value will be &lt;= 100e3 (100 us). For FULL
     * capability devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == FULL),
     * max will be &gt;= 100e6 (100ms)</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     */
    @PublicKey
    public static final Key<android.util.Range<Long>> SENSOR_INFO_EXPOSURE_TIME_RANGE =
            new Key<android.util.Range<Long>>("android.sensor.info.exposureTimeRange", new TypeReference<android.util.Range<Long>>() {{ }});

    /**
     * <p>Maximum possible frame duration (minimum frame
     * rate).</p>
     * <p>The largest possible {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}
     * that will be accepted by the camera device. Attempting to use
     * frame durations beyond the maximum will result in the frame duration
     * being clipped to the maximum. See that control
     * for a full definition of frame durations.</p>
     * <p>Refer to
     * StreamConfigurationMap#getOutputMinFrameDuration(int,Size)
     * for the minimum frame duration values.</p>
     * <p>For FULL capability devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == FULL),
     * max will be &gt;= 100e6 (100ms).</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     */
    @PublicKey
    public static final Key<Long> SENSOR_INFO_MAX_FRAME_DURATION =
            new Key<Long>("android.sensor.info.maxFrameDuration", long.class);

    /**
     * <p>The physical dimensions of the full pixel
     * array.</p>
     * <p>This is the physical size of the sensor pixel
     * array defined by {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}.</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     */
    @PublicKey
    public static final Key<android.util.SizeF> SENSOR_INFO_PHYSICAL_SIZE =
            new Key<android.util.SizeF>("android.sensor.info.physicalSize", android.util.SizeF.class);

    /**
     * <p>Dimensions of full pixel array, possibly
     * including black calibration pixels.</p>
     * <p>The pixel count of the full pixel array,
     * which covers {@link CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE android.sensor.info.physicalSize} area.</p>
     * <p>If a camera device supports raw sensor formats, either this
     * or {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} is the maximum output
     * raw size listed in {@link CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP android.scaler.streamConfigurationMap}.
     * If a size corresponding to pixelArraySize is listed, the resulting
     * raw sensor image will include black pixels.</p>
     *
     * @see CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE
     */
    @PublicKey
    public static final Key<android.util.Size> SENSOR_INFO_PIXEL_ARRAY_SIZE =
            new Key<android.util.Size>("android.sensor.info.pixelArraySize", android.util.Size.class);

    /**
     * <p>Maximum raw value output by sensor.</p>
     * <p>This specifies the fully-saturated encoding level for the raw
     * sample values from the sensor.  This is typically caused by the
     * sensor becoming highly non-linear or clipping. The minimum for
     * each channel is specified by the offset in the
     * {@link CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN android.sensor.blackLevelPattern} tag.</p>
     * <p>The white level is typically determined either by sensor bit depth
     * (8-14 bits is expected), or by the point where the sensor response
     * becomes too non-linear to be useful.  The default value for this is
     * maximum representable value for a 16-bit raw sample (2^16 - 1).</p>
     *
     * @see CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN
     */
    @PublicKey
    public static final Key<Integer> SENSOR_INFO_WHITE_LEVEL =
            new Key<Integer>("android.sensor.info.whiteLevel", int.class);

    /**
     * <p>The time base source for sensor capture start timestamps.</p>
     * <p>The timestamps provided for captures are always in nanoseconds and monotonic, but
     * may not based on a time source that can be compared to other system time sources.</p>
     * <p>This characteristic defines the source for the timestamps, and therefore whether they
     * can be compared against other system time sources/timestamps.</p>
     * @see #SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
     * @see #SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
     */
    @PublicKey
    public static final Key<Integer> SENSOR_INFO_TIMESTAMP_SOURCE =
            new Key<Integer>("android.sensor.info.timestampSource", int.class);

    /**
     * <p>The standard reference illuminant used as the scene light source when
     * calculating the {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM1 android.sensor.colorTransform1},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1 android.sensor.calibrationTransform1}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX1 android.sensor.forwardMatrix1} matrices.</p>
     * <p>The values in this tag correspond to the values defined for the
     * EXIF LightSource tag. These illuminants are standard light sources
     * that are often used calibrating camera devices.</p>
     * <p>If this tag is present, then {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM1 android.sensor.colorTransform1},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1 android.sensor.calibrationTransform1}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX1 android.sensor.forwardMatrix1} will also be present.</p>
     * <p>Some devices may choose to provide a second set of calibration
     * information for improved quality, including
     * {@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2 android.sensor.referenceIlluminant2} and its corresponding matrices.</p>
     *
     * @see CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1
     * @see CameraCharacteristics#SENSOR_COLOR_TRANSFORM1
     * @see CameraCharacteristics#SENSOR_FORWARD_MATRIX1
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2
     * @see #SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT
     * @see #SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN
     * @see #SENSOR_REFERENCE_ILLUMINANT1_FLASH
     * @see #SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER
     * @see #SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER
     * @see #SENSOR_REFERENCE_ILLUMINANT1_SHADE
     * @see #SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A
     * @see #SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B
     * @see #SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C
     * @see #SENSOR_REFERENCE_ILLUMINANT1_D55
     * @see #SENSOR_REFERENCE_ILLUMINANT1_D65
     * @see #SENSOR_REFERENCE_ILLUMINANT1_D75
     * @see #SENSOR_REFERENCE_ILLUMINANT1_D50
     * @see #SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN
     */
    @PublicKey
    public static final Key<Integer> SENSOR_REFERENCE_ILLUMINANT1 =
            new Key<Integer>("android.sensor.referenceIlluminant1", int.class);

    /**
     * <p>The standard reference illuminant used as the scene light source when
     * calculating the {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM2 android.sensor.colorTransform2},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM2 android.sensor.calibrationTransform2}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX2 android.sensor.forwardMatrix2} matrices.</p>
     * <p>See {@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1} for more details.
     * Valid values for this are the same as those given for the first
     * reference illuminant.</p>
     * <p>If this tag is present, then {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM2 android.sensor.colorTransform2},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM2 android.sensor.calibrationTransform2}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX2 android.sensor.forwardMatrix2} will also be present.</p>
     *
     * @see CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM2
     * @see CameraCharacteristics#SENSOR_COLOR_TRANSFORM2
     * @see CameraCharacteristics#SENSOR_FORWARD_MATRIX2
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    @PublicKey
    public static final Key<Byte> SENSOR_REFERENCE_ILLUMINANT2 =
            new Key<Byte>("android.sensor.referenceIlluminant2", byte.class);

    /**
     * <p>A per-device calibration transform matrix that maps from the
     * reference sensor colorspace to the actual device sensor colorspace.</p>
     * <p>This matrix is used to correct for per-device variations in the
     * sensor colorspace, and is used for processing raw buffer data.</p>
     * <p>The matrix is expressed as a 3x3 matrix in row-major-order, and
     * contains a per-device calibration transform that maps colors
     * from reference sensor color space (i.e. the "golden module"
     * colorspace) into this camera device's native sensor color
     * space under the first reference illuminant
     * ({@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1}).</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> SENSOR_CALIBRATION_TRANSFORM1 =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.sensor.calibrationTransform1", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>A per-device calibration transform matrix that maps from the
     * reference sensor colorspace to the actual device sensor colorspace
     * (this is the colorspace of the raw buffer data).</p>
     * <p>This matrix is used to correct for per-device variations in the
     * sensor colorspace, and is used for processing raw buffer data.</p>
     * <p>The matrix is expressed as a 3x3 matrix in row-major-order, and
     * contains a per-device calibration transform that maps colors
     * from reference sensor color space (i.e. the "golden module"
     * colorspace) into this camera device's native sensor color
     * space under the second reference illuminant
     * ({@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2 android.sensor.referenceIlluminant2}).</p>
     * <p>This matrix will only be present if the second reference
     * illuminant is present.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> SENSOR_CALIBRATION_TRANSFORM2 =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.sensor.calibrationTransform2", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>A matrix that transforms color values from CIE XYZ color space to
     * reference sensor color space.</p>
     * <p>This matrix is used to convert from the standard CIE XYZ color
     * space to the reference sensor colorspace, and is used when processing
     * raw buffer data.</p>
     * <p>The matrix is expressed as a 3x3 matrix in row-major-order, and
     * contains a color transform matrix that maps colors from the CIE
     * XYZ color space to the reference sensor color space (i.e. the
     * "golden module" colorspace) under the first reference illuminant
     * ({@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1}).</p>
     * <p>The white points chosen in both the reference sensor color space
     * and the CIE XYZ colorspace when calculating this transform will
     * match the standard white point for the first reference illuminant
     * (i.e. no chromatic adaptation will be applied by this transform).</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> SENSOR_COLOR_TRANSFORM1 =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.sensor.colorTransform1", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>A matrix that transforms color values from CIE XYZ color space to
     * reference sensor color space.</p>
     * <p>This matrix is used to convert from the standard CIE XYZ color
     * space to the reference sensor colorspace, and is used when processing
     * raw buffer data.</p>
     * <p>The matrix is expressed as a 3x3 matrix in row-major-order, and
     * contains a color transform matrix that maps colors from the CIE
     * XYZ color space to the reference sensor color space (i.e. the
     * "golden module" colorspace) under the second reference illuminant
     * ({@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2 android.sensor.referenceIlluminant2}).</p>
     * <p>The white points chosen in both the reference sensor color space
     * and the CIE XYZ colorspace when calculating this transform will
     * match the standard white point for the second reference illuminant
     * (i.e. no chromatic adaptation will be applied by this transform).</p>
     * <p>This matrix will only be present if the second reference
     * illuminant is present.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> SENSOR_COLOR_TRANSFORM2 =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.sensor.colorTransform2", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>A matrix that transforms white balanced camera colors from the reference
     * sensor colorspace to the CIE XYZ colorspace with a D50 whitepoint.</p>
     * <p>This matrix is used to convert to the standard CIE XYZ colorspace, and
     * is used when processing raw buffer data.</p>
     * <p>This matrix is expressed as a 3x3 matrix in row-major-order, and contains
     * a color transform matrix that maps white balanced colors from the
     * reference sensor color space to the CIE XYZ color space with a D50 white
     * point.</p>
     * <p>Under the first reference illuminant ({@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1})
     * this matrix is chosen so that the standard white point for this reference
     * illuminant in the reference sensor colorspace is mapped to D50 in the
     * CIE XYZ colorspace.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> SENSOR_FORWARD_MATRIX1 =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.sensor.forwardMatrix1", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>A matrix that transforms white balanced camera colors from the reference
     * sensor colorspace to the CIE XYZ colorspace with a D50 whitepoint.</p>
     * <p>This matrix is used to convert to the standard CIE XYZ colorspace, and
     * is used when processing raw buffer data.</p>
     * <p>This matrix is expressed as a 3x3 matrix in row-major-order, and contains
     * a color transform matrix that maps white balanced colors from the
     * reference sensor color space to the CIE XYZ color space with a D50 white
     * point.</p>
     * <p>Under the second reference illuminant ({@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2 android.sensor.referenceIlluminant2})
     * this matrix is chosen so that the standard white point for this reference
     * illuminant in the reference sensor colorspace is mapped to D50 in the
     * CIE XYZ colorspace.</p>
     * <p>This matrix will only be present if the second reference
     * illuminant is present.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.ColorSpaceTransform> SENSOR_FORWARD_MATRIX2 =
            new Key<android.hardware.camera2.params.ColorSpaceTransform>("android.sensor.forwardMatrix2", android.hardware.camera2.params.ColorSpaceTransform.class);

    /**
     * <p>A fixed black level offset for each of the color filter arrangement
     * (CFA) mosaic channels.</p>
     * <p>This tag specifies the zero light value for each of the CFA mosaic
     * channels in the camera sensor.  The maximal value output by the
     * sensor is represented by the value in {@link CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL android.sensor.info.whiteLevel}.</p>
     * <p>The values are given in the same order as channels listed for the CFA
     * layout tag (see {@link CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT android.sensor.info.colorFilterArrangement}), i.e. the
     * nth value given corresponds to the black level offset for the nth
     * color channel listed in the CFA.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     * @see CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL
     */
    @PublicKey
    public static final Key<android.hardware.camera2.params.BlackLevelPattern> SENSOR_BLACK_LEVEL_PATTERN =
            new Key<android.hardware.camera2.params.BlackLevelPattern>("android.sensor.blackLevelPattern", android.hardware.camera2.params.BlackLevelPattern.class);

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
    @PublicKey
    public static final Key<Integer> SENSOR_MAX_ANALOG_SENSITIVITY =
            new Key<Integer>("android.sensor.maxAnalogSensitivity", int.class);

    /**
     * <p>Clockwise angle through which the output
     * image needs to be rotated to be upright on the device
     * screen in its native orientation. Also defines the
     * direction of rolling shutter readout, which is from top
     * to bottom in the sensor's coordinate system</p>
     */
    @PublicKey
    public static final Key<Integer> SENSOR_ORIENTATION =
            new Key<Integer>("android.sensor.orientation", int.class);

    /**
     * <p>Lists the supported sensor test pattern modes for {@link CaptureRequest#SENSOR_TEST_PATTERN_MODE android.sensor.testPatternMode}.</p>
     * <p>Optional. Defaults to [OFF].</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    @PublicKey
    public static final Key<int[]> SENSOR_AVAILABLE_TEST_PATTERN_MODES =
            new Key<int[]>("android.sensor.availableTestPatternModes", int[].class);

    /**
     * <p>The face detection modes that are available
     * for this camera device.</p>
     * <p>OFF is always supported.</p>
     * <p>SIMPLE means the device supports the
     * android.statistics.faceRectangles and
     * android.statistics.faceScores outputs.</p>
     * <p>FULL means the device additionally supports the
     * android.statistics.faceIds and
     * android.statistics.faceLandmarks outputs.</p>
     */
    @PublicKey
    public static final Key<int[]> STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES =
            new Key<int[]>("android.statistics.info.availableFaceDetectModes", int[].class);

    /**
     * <p>The maximum number of simultaneously detectable
     * faces.</p>
     */
    @PublicKey
    public static final Key<Integer> STATISTICS_INFO_MAX_FACE_COUNT =
            new Key<Integer>("android.statistics.info.maxFaceCount", int.class);

    /**
     * <p>The set of hot pixel map output modes supported by this camera device.</p>
     * <p>This tag lists valid output modes for {@link CaptureRequest#STATISTICS_HOT_PIXEL_MAP_MODE android.statistics.hotPixelMapMode}.</p>
     * <p>If no hotpixel map is available for this camera device, this will contain
     * only OFF.  If the hotpixel map is available, this will include both
     * the ON and OFF options.</p>
     *
     * @see CaptureRequest#STATISTICS_HOT_PIXEL_MAP_MODE
     */
    @PublicKey
    public static final Key<boolean[]> STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES =
            new Key<boolean[]>("android.statistics.info.availableHotPixelMapModes", boolean[].class);

    /**
     * <p>Maximum number of supported points in the
     * tonemap curve that can be used for {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}.</p>
     * <p>If the actual number of points provided by the application (in
     * {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}*)  is less than max, the camera device will
     * resample the curve to its internal representation, using linear
     * interpolation.</p>
     * <p>The output curves in the result metadata may have a different number
     * of points than the input curves, and will represent the actual
     * hardware curves used as closely as possible when linearly interpolated.</p>
     *
     * @see CaptureRequest#TONEMAP_CURVE
     */
    @PublicKey
    public static final Key<Integer> TONEMAP_MAX_CURVE_POINTS =
            new Key<Integer>("android.tonemap.maxCurvePoints", int.class);

    /**
     * <p>The set of tonemapping modes supported by this camera device.</p>
     * <p>This tag lists the valid modes for {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode}.</p>
     * <p>Full-capability camera devices must always support CONTRAST_CURVE and
     * FAST.</p>
     *
     * @see CaptureRequest#TONEMAP_MODE
     */
    @PublicKey
    public static final Key<int[]> TONEMAP_AVAILABLE_TONE_MAP_MODES =
            new Key<int[]>("android.tonemap.availableToneMapModes", int[].class);

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
     * <p>A FULL device has the most support possible and will support below capabilities:</p>
     * <ul>
     * <li>30fps at maximum resolution (== sensor resolution) is preferred, more than 20fps is required.</li>
     * <li>Per frame control ({@link CameraCharacteristics#SYNC_MAX_LATENCY android.sync.maxLatency} <code>==</code> PER_FRAME_CONTROL)</li>
     * <li>Manual sensor control ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains MANUAL_SENSOR)</li>
     * <li>Manual post-processing control ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains MANUAL_POST_PROCESSING)</li>
     * <li>Arbitrary cropping region ({@link CameraCharacteristics#SCALER_CROPPING_TYPE android.scaler.croppingType} <code>==</code> FREEFORM)</li>
     * <li>At least 3 processed (but not stalling) format output streams ({@link CameraCharacteristics#REQUEST_MAX_NUM_OUTPUT_PROC android.request.maxNumOutputProc} <code>&gt;=</code> 3)</li>
     * <li>The required stream configuration defined in android.scaler.availableStreamConfigurations</li>
     * <li>The required exposure time range defined in {@link CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE android.sensor.info.exposureTimeRange}</li>
     * <li>The required maxFrameDuration defined in {@link CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION android.sensor.info.maxFrameDuration}</li>
     * </ul>
     * <p>A LIMITED device may have some or none of the above characteristics.
     * To find out more refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     * <p>A LEGACY device does not support per-frame control, manual sensor control, manual
     * post-processing, arbitrary cropping regions, and has relaxed performance constraints.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#REQUEST_MAX_NUM_OUTPUT_PROC
     * @see CameraCharacteristics#SCALER_CROPPING_TYPE
     * @see CameraCharacteristics#SENSOR_INFO_EXPOSURE_TIME_RANGE
     * @see CameraCharacteristics#SENSOR_INFO_MAX_FRAME_DURATION
     * @see CameraCharacteristics#SYNC_MAX_LATENCY
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_FULL
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
     */
    @PublicKey
    public static final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL =
            new Key<Integer>("android.info.supportedHardwareLevel", int.class);

    /**
     * <p>The maximum number of frames that can occur after a request
     * (different than the previous) has been submitted, and before the
     * result's state becomes synchronized (by setting
     * android.sync.frameNumber to a non-negative value).</p>
     * <p>This defines the maximum distance (in number of metadata results),
     * between android.sync.frameNumber and the equivalent
     * frame number for that result.</p>
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
    @PublicKey
    public static final Key<Integer> SYNC_MAX_LATENCY =
            new Key<Integer>("android.sync.maxLatency", int.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/

}
