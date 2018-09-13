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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.PublicKey;
import android.hardware.camera2.impl.SyntheticKey;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.ArrayUtils;
import android.hardware.camera2.utils.TypeReference;
import android.util.Rational;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        @UnsupportedAppUsage
        public Key(String name, Class<T> type, long vendorId) {
            mKey = new CameraMetadataNative.Key<T>(name,  type, vendorId);
        }

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        public Key(String name, String fallbackName, Class<T> type) {
            mKey = new CameraMetadataNative.Key<T>(name,  fallbackName, type);
        }

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public Key(String name, Class<T> type) {
            mKey = new CameraMetadataNative.Key<T>(name,  type);
        }

        /**
         * Visible for testing and vendor extensions only.
         *
         * @hide
         */
        @UnsupportedAppUsage
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
        @NonNull
        public String getName() {
            return mKey.getName();
        }

        /**
         * Return vendor tag id.
         *
         * @hide
         */
        public long getVendorId() {
            return mKey.getVendorId();
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
         * Return this {@link Key} as a string representation.
         *
         * <p>{@code "CameraCharacteristics.Key(%s)"}, where {@code %s} represents
         * the name of this key as returned by {@link #getName}.</p>
         *
         * @return string representation of {@link Key}
         */
        @NonNull
        @Override
        public String toString() {
            return String.format("CameraCharacteristics.Key(%s)", mKey.getName());
        }

        /**
         * Visible for CameraMetadataNative implementation only; do not use.
         *
         * TODO: Make this private or remove it altogether.
         *
         * @hide
         */
        @UnsupportedAppUsage
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

    @UnsupportedAppUsage
    private final CameraMetadataNative mProperties;
    private List<CameraCharacteristics.Key<?>> mKeys;
    private List<CaptureRequest.Key<?>> mAvailableRequestKeys;
    private List<CaptureRequest.Key<?>> mAvailableSessionKeys;
    private List<CaptureRequest.Key<?>> mAvailablePhysicalRequestKeys;
    private List<CaptureResult.Key<?>> mAvailableResultKeys;

    /**
     * Takes ownership of the passed-in properties object
     * @hide
     */
    public CameraCharacteristics(CameraMetadataNative properties) {
        mProperties = CameraMetadataNative.move(properties);
        setNativeInstance(mProperties);
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
    @Nullable
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
    @NonNull
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
                getKeys(getClass(), getKeyClass(), this, filterTags));
        return mKeys;
    }

    /**
     * <p>Returns a subset of {@link #getAvailableCaptureRequestKeys} keys that the
     * camera device can pass as part of the capture session initialization.</p>
     *
     * <p>This list includes keys that are difficult to apply per-frame and
     * can result in unexpected delays when modified during the capture session
     * lifetime. Typical examples include parameters that require a
     * time-consuming hardware re-configuration or internal camera pipeline
     * change. For performance reasons we suggest clients to pass their initial
     * values as part of {@link SessionConfiguration#setSessionParameters}. Once
     * the camera capture session is enabled it is also recommended to avoid
     * changing them from their initial values set in
     * {@link SessionConfiguration#setSessionParameters }.
     * Control over session parameters can still be exerted in capture requests
     * but clients should be aware and expect delays during their application.
     * An example usage scenario could look like this:</p>
     * <ul>
     * <li>The camera client starts by quering the session parameter key list via
     *   {@link android.hardware.camera2.CameraCharacteristics#getAvailableSessionKeys }.</li>
     * <li>Before triggering the capture session create sequence, a capture request
     *   must be built via {@link CameraDevice#createCaptureRequest } using an
     *   appropriate template matching the particular use case.</li>
     * <li>The client should go over the list of session parameters and check
     *   whether some of the keys listed matches with the parameters that
     *   they intend to modify as part of the first capture request.</li>
     * <li>If there is no such match, the capture request can be  passed
     *   unmodified to {@link SessionConfiguration#setSessionParameters }.</li>
     * <li>If matches do exist, the client should update the respective values
     *   and pass the request to {@link SessionConfiguration#setSessionParameters }.</li>
     * <li>After the capture session initialization completes the session parameter
     *   key list can continue to serve as reference when posting or updating
     *   further requests. As mentioned above further changes to session
     *   parameters should ideally be avoided, if updates are necessary
     *   however clients could expect a delay/glitch during the
     *   parameter switch.</li>
     * </ul>
     *
     * <p>The list returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>Each key is only listed once in the list. The order of the keys is undefined.</p>
     *
     * @return List of keys that can be passed during capture session initialization. In case the
     * camera device doesn't support such keys the list can be null.
     */
    @SuppressWarnings({"unchecked"})
    public List<CaptureRequest.Key<?>> getAvailableSessionKeys() {
        if (mAvailableSessionKeys == null) {
            Object crKey = CaptureRequest.Key.class;
            Class<CaptureRequest.Key<?>> crKeyTyped = (Class<CaptureRequest.Key<?>>)crKey;

            int[] filterTags = get(REQUEST_AVAILABLE_SESSION_KEYS);
            if (filterTags == null) {
                return null;
            }
            mAvailableSessionKeys =
                    getAvailableKeyList(CaptureRequest.class, crKeyTyped, filterTags);
        }
        return mAvailableSessionKeys;
    }

    /**
     * <p>Returns a subset of {@link #getAvailableCaptureRequestKeys} keys that can
     * be overridden for physical devices backing a logical multi-camera.</p>
     *
     * <p>This is a subset of android.request.availableRequestKeys which contains a list
     * of keys that can be overridden using {@link CaptureRequest.Builder#setPhysicalCameraKey }.
     * The respective value of such request key can be obtained by calling
     * {@link CaptureRequest.Builder#getPhysicalCameraKey }. Capture requests that contain
     * individual physical device requests must be built via
     * {@link android.hardware.camera2.CameraDevice#createCaptureRequest(int, Set)}.
     * Such extended capture requests can be passed only to
     * {@link CameraCaptureSession#capture } or {@link CameraCaptureSession#captureBurst } and
     * not to {@link CameraCaptureSession#setRepeatingRequest } or
     * {@link CameraCaptureSession#setRepeatingBurst }.</p>
     *
     * <p>The list returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * <p>Each key is only listed once in the list. The order of the keys is undefined.</p>
     *
     * @return List of keys that can be overridden in individual physical device requests.
     * In case the camera device doesn't support such keys the list can be null.
     */
    @SuppressWarnings({"unchecked"})
    public List<CaptureRequest.Key<?>> getAvailablePhysicalCameraRequestKeys() {
        if (mAvailablePhysicalRequestKeys == null) {
            Object crKey = CaptureRequest.Key.class;
            Class<CaptureRequest.Key<?>> crKeyTyped = (Class<CaptureRequest.Key<?>>)crKey;

            int[] filterTags = get(REQUEST_AVAILABLE_PHYSICAL_CAMERA_REQUEST_KEYS);
            if (filterTags == null) {
                return null;
            }
            mAvailablePhysicalRequestKeys =
                    getAvailableKeyList(CaptureRequest.class, crKeyTyped, filterTags);
        }
        return mAvailablePhysicalRequestKeys;
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
    @NonNull
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
    @NonNull
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

        List<TKey> staticKeyList = getKeys(
                metadataClass, keyClass, /*instance*/null, filterTags);
        return Collections.unmodifiableList(staticKeyList);
    }

    /**
     * Returns the set of physical camera ids that this logical {@link CameraDevice} is
     * made up of.
     *
     * <p>A camera device is a logical camera if it has
     * REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA capability. If the camera device
     * doesn't have the capability, the return value will be an empty set. </p>
     *
     * <p>The set returned is not modifiable, so any attempts to modify it will throw
     * a {@code UnsupportedOperationException}.</p>
     *
     * @return Set of physical camera ids for this logical camera device.
     */
    @NonNull
    public Set<String> getPhysicalCameraIds() {
        int[] availableCapabilities = get(REQUEST_AVAILABLE_CAPABILITIES);
        if (availableCapabilities == null) {
            throw new AssertionError("android.request.availableCapabilities must be non-null "
                        + "in the characteristics");
        }

        if (!ArrayUtils.contains(availableCapabilities,
                REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return Collections.emptySet();
        }
        byte[] physicalCamIds = get(LOGICAL_MULTI_CAMERA_PHYSICAL_IDS);

        String physicalCamIdString = null;
        try {
            physicalCamIdString = new String(physicalCamIds, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError("android.logicalCam.physicalIds must be UTF-8 string");
        }
        String[] physicalCameraIdArray = physicalCamIdString.split("\0");

        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(physicalCameraIdArray)));
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/

    /**
     * <p>List of aberration correction modes for {@link CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE android.colorCorrection.aberrationMode} that are
     * supported by this camera device.</p>
     * <p>This key lists the valid modes for {@link CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE android.colorCorrection.aberrationMode}.  If no
     * aberration correction modes are available for a device, this list will solely include
     * OFF mode. All camera devices will support either OFF or FAST mode.</p>
     * <p>Camera devices that support the MANUAL_POST_PROCESSING capability will always list
     * OFF mode. This includes all FULL level devices.</p>
     * <p>LEGACY devices will always only support FAST mode.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE android.colorCorrection.aberrationMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_ABERRATION_MODE
     */
    @PublicKey
    public static final Key<int[]> COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES =
            new Key<int[]>("android.colorCorrection.availableAberrationModes", int[].class);

    /**
     * <p>List of auto-exposure antibanding modes for {@link CaptureRequest#CONTROL_AE_ANTIBANDING_MODE android.control.aeAntibandingMode} that are
     * supported by this camera device.</p>
     * <p>Not all of the auto-exposure anti-banding modes may be
     * supported by a given camera device. This field lists the
     * valid anti-banding modes that the application may request
     * for this camera device with the
     * {@link CaptureRequest#CONTROL_AE_ANTIBANDING_MODE android.control.aeAntibandingMode} control.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_AE_ANTIBANDING_MODE android.control.aeAntibandingMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AE_AVAILABLE_ANTIBANDING_MODES =
            new Key<int[]>("android.control.aeAvailableAntibandingModes", int[].class);

    /**
     * <p>List of auto-exposure modes for {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} that are supported by this camera
     * device.</p>
     * <p>Not all the auto-exposure modes may be supported by a
     * given camera device, especially if no flash unit is
     * available. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} for this camera device.</p>
     * <p>All camera devices support ON, and all camera devices with flash
     * units support ON_AUTO_FLASH and ON_ALWAYS_FLASH.</p>
     * <p>FULL mode camera devices always support OFF mode,
     * which enables application control of camera exposure time,
     * sensitivity, and frame duration.</p>
     * <p>LEGACY mode camera devices never support OFF mode.
     * LIMITED mode devices support OFF if they support the MANUAL_SENSOR
     * capability.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AE_AVAILABLE_MODES =
            new Key<int[]>("android.control.aeAvailableModes", int[].class);

    /**
     * <p>List of frame rate ranges for {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE android.control.aeTargetFpsRange} supported by
     * this camera device.</p>
     * <p>For devices at the LEGACY level or above:</p>
     * <ul>
     * <li>
     * <p>For constant-framerate recording, for each normal
     * {@link android.media.CamcorderProfile CamcorderProfile}, that is, a
     * {@link android.media.CamcorderProfile CamcorderProfile} that has
     * {@link android.media.CamcorderProfile#quality quality} in
     * the range [{@link android.media.CamcorderProfile#QUALITY_LOW QUALITY_LOW},
     * {@link android.media.CamcorderProfile#QUALITY_2160P QUALITY_2160P}], if the profile is
     * supported by the device and has
     * {@link android.media.CamcorderProfile#videoFrameRate videoFrameRate} <code>x</code>, this list will
     * always include (<code>x</code>,<code>x</code>).</p>
     * </li>
     * <li>
     * <p>Also, a camera device must either not support any
     * {@link android.media.CamcorderProfile CamcorderProfile},
     * or support at least one
     * normal {@link android.media.CamcorderProfile CamcorderProfile} that has
     * {@link android.media.CamcorderProfile#videoFrameRate videoFrameRate} <code>x</code> &gt;= 24.</p>
     * </li>
     * </ul>
     * <p>For devices at the LIMITED level or above:</p>
     * <ul>
     * <li>For YUV_420_888 burst capture use case, this list will always include (<code>min</code>, <code>max</code>)
     * and (<code>max</code>, <code>max</code>) where <code>min</code> &lt;= 15 and <code>max</code> = the maximum output frame rate of the
     * maximum YUV_420_888 output size.</li>
     * </ul>
     * <p><b>Units</b>: Frames per second (FPS)</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES =
            new Key<android.util.Range<Integer>[]>("android.control.aeAvailableTargetFpsRanges", new TypeReference<android.util.Range<Integer>[]>() {{ }});

    /**
     * <p>Maximum and minimum exposure compensation values for
     * {@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION android.control.aeExposureCompensation}, in counts of {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep},
     * that are supported by this camera device.</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>Range [0,0] indicates that exposure compensation is not supported.</p>
     * <p>For LIMITED and FULL devices, range must follow below requirements if exposure
     * compensation is supported (<code>range != [0, 0]</code>):</p>
     * <p><code>Min.exposure compensation * {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep} &lt;= -2 EV</code></p>
     * <p><code>Max.exposure compensation * {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep} &gt;= 2 EV</code></p>
     * <p>LEGACY devices may support a smaller range than this.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP
     * @see CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>> CONTROL_AE_COMPENSATION_RANGE =
            new Key<android.util.Range<Integer>>("android.control.aeCompensationRange", new TypeReference<android.util.Range<Integer>>() {{ }});

    /**
     * <p>Smallest step by which the exposure compensation
     * can be changed.</p>
     * <p>This is the unit for {@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION android.control.aeExposureCompensation}. For example, if this key has
     * a value of <code>1/2</code>, then a setting of <code>-2</code> for {@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION android.control.aeExposureCompensation} means
     * that the target EV offset for the auto-exposure routine is -1 EV.</p>
     * <p>One unit of EV compensation changes the brightness of the captured image by a factor
     * of two. +1 EV doubles the image brightness, while -1 EV halves the image brightness.</p>
     * <p><b>Units</b>: Exposure Value (EV)</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION
     */
    @PublicKey
    public static final Key<Rational> CONTROL_AE_COMPENSATION_STEP =
            new Key<Rational>("android.control.aeCompensationStep", Rational.class);

    /**
     * <p>List of auto-focus (AF) modes for {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} that are
     * supported by this camera device.</p>
     * <p>Not all the auto-focus modes may be supported by a
     * given camera device. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} for this camera device.</p>
     * <p>All LIMITED and FULL mode camera devices will support OFF mode, and all
     * camera devices with adjustable focuser units
     * (<code>{@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} &gt; 0</code>) will support AUTO mode.</p>
     * <p>LEGACY devices will support OFF mode only if they support
     * focusing to infinity (by also setting {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance} to
     * <code>0.0f</code>).</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AF_AVAILABLE_MODES =
            new Key<int[]>("android.control.afAvailableModes", int[].class);

    /**
     * <p>List of color effects for {@link CaptureRequest#CONTROL_EFFECT_MODE android.control.effectMode} that are supported by this camera
     * device.</p>
     * <p>This list contains the color effect modes that can be applied to
     * images produced by the camera device.
     * Implementations are not expected to be consistent across all devices.
     * If no color effect modes are available for a device, this will only list
     * OFF.</p>
     * <p>A color effect will only be applied if
     * {@link CaptureRequest#CONTROL_MODE android.control.mode} != OFF.  OFF is always included in this list.</p>
     * <p>This control has no effect on the operation of other control routines such
     * as auto-exposure, white balance, or focus.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_EFFECT_MODE android.control.effectMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_EFFECT_MODE
     * @see CaptureRequest#CONTROL_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_EFFECTS =
            new Key<int[]>("android.control.availableEffects", int[].class);

    /**
     * <p>List of scene modes for {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode} that are supported by this camera
     * device.</p>
     * <p>This list contains scene modes that can be set for the camera device.
     * Only scene modes that have been fully implemented for the
     * camera device may be included here. Implementations are not expected
     * to be consistent across all devices.</p>
     * <p>If no scene modes are supported by the camera device, this
     * will be set to DISABLED. Otherwise DISABLED will not be listed.</p>
     * <p>FACE_PRIORITY is always listed if face detection is
     * supported (i.e.<code>{@link CameraCharacteristics#STATISTICS_INFO_MAX_FACE_COUNT android.statistics.info.maxFaceCount} &gt;
     * 0</code>).</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @see CameraCharacteristics#STATISTICS_INFO_MAX_FACE_COUNT
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_SCENE_MODES =
            new Key<int[]>("android.control.availableSceneModes", int[].class);

    /**
     * <p>List of video stabilization modes for {@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE android.control.videoStabilizationMode}
     * that are supported by this camera device.</p>
     * <p>OFF will always be listed.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE android.control.videoStabilizationMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES =
            new Key<int[]>("android.control.availableVideoStabilizationModes", int[].class);

    /**
     * <p>List of auto-white-balance modes for {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} that are supported by this
     * camera device.</p>
     * <p>Not all the auto-white-balance modes may be supported by a
     * given camera device. This entry lists the valid modes for
     * {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} for this camera device.</p>
     * <p>All camera devices will support ON mode.</p>
     * <p>Camera devices that support the MANUAL_POST_PROCESSING capability will always support OFF
     * mode, which enables application control of white balance, by using
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform} and {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}({@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} must be set to TRANSFORM_MATRIX). This includes all FULL
     * mode camera devices.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}</p>
     * <p>This key is available on all devices.</p>
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
     * <p><b>Range of valid values:</b><br></p>
     * <p>Value must be &gt;= 0 for each element. For full-capability devices
     * this value must be &gt;= 1 for AE and AF. The order of the elements is:
     * <code>(AE, AWB, AF)</code>.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     * @see CaptureRequest#CONTROL_AF_REGIONS
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     * @hide
     */
    public static final Key<int[]> CONTROL_MAX_REGIONS =
            new Key<int[]>("android.control.maxRegions", int[].class);

    /**
     * <p>The maximum number of metering regions that can be used by the auto-exposure (AE)
     * routine.</p>
     * <p>This corresponds to the the maximum allowed number of elements in
     * {@link CaptureRequest#CONTROL_AE_REGIONS android.control.aeRegions}.</p>
     * <p><b>Range of valid values:</b><br>
     * Value will be &gt;= 0. For FULL-capability devices, this
     * value will be &gt;= 1.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_REGIONS
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> CONTROL_MAX_REGIONS_AE =
            new Key<Integer>("android.control.maxRegionsAe", int.class);

    /**
     * <p>The maximum number of metering regions that can be used by the auto-white balance (AWB)
     * routine.</p>
     * <p>This corresponds to the the maximum allowed number of elements in
     * {@link CaptureRequest#CONTROL_AWB_REGIONS android.control.awbRegions}.</p>
     * <p><b>Range of valid values:</b><br>
     * Value will be &gt;= 0.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AWB_REGIONS
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> CONTROL_MAX_REGIONS_AWB =
            new Key<Integer>("android.control.maxRegionsAwb", int.class);

    /**
     * <p>The maximum number of metering regions that can be used by the auto-focus (AF) routine.</p>
     * <p>This corresponds to the the maximum allowed number of elements in
     * {@link CaptureRequest#CONTROL_AF_REGIONS android.control.afRegions}.</p>
     * <p><b>Range of valid values:</b><br>
     * Value will be &gt;= 0. For FULL-capability devices, this
     * value will be &gt;= 1.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AF_REGIONS
     */
    @PublicKey
    @SyntheticKey
    public static final Key<Integer> CONTROL_MAX_REGIONS_AF =
            new Key<Integer>("android.control.maxRegionsAf", int.class);

    /**
     * <p>List of available high speed video size, fps range and max batch size configurations
     * supported by the camera device, in the format of (width, height, fps_min, fps_max, batch_size_max).</p>
     * <p>When CONSTRAINED_HIGH_SPEED_VIDEO is supported in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities},
     * this metadata will list the supported high speed video size, fps range and max batch size
     * configurations. All the sizes listed in this configuration will be a subset of the sizes
     * reported by {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes }
     * for processed non-stalling formats.</p>
     * <p>For the high speed video use case, the application must
     * select the video size and fps range from this metadata to configure the recording and
     * preview streams and setup the recording requests. For example, if the application intends
     * to do high speed recording, it can select the maximum size reported by this metadata to
     * configure output streams. Once the size is selected, application can filter this metadata
     * by selected size and get the supported fps ranges, and use these fps ranges to setup the
     * recording requests. Note that for the use case of multiple output streams, application
     * must select one unique size from this metadata to use (e.g., preview and recording streams
     * must have the same size). Otherwise, the high speed capture session creation will fail.</p>
     * <p>The min and max fps will be multiple times of 30fps.</p>
     * <p>High speed video streaming extends significant performance pressue to camera hardware,
     * to achieve efficient high speed streaming, the camera device may have to aggregate
     * multiple frames together and send to camera device for processing where the request
     * controls are same for all the frames in this batch. Max batch size indicates
     * the max possible number of frames the camera device will group together for this high
     * speed stream configuration. This max batch size will be used to generate a high speed
     * recording request list by
     * {@link android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession#createHighSpeedRequestList }.
     * The max batch size for each configuration will satisfy below conditions:</p>
     * <ul>
     * <li>Each max batch size will be a divisor of its corresponding fps_max / 30. For example,
     * if max_fps is 300, max batch size will only be 1, 2, 5, or 10.</li>
     * <li>The camera device may choose smaller internal batch size for each configuration, but
     * the actual batch size will be a divisor of max batch size. For example, if the max batch
     * size is 8, the actual batch size used by camera device will only be 1, 2, 4, or 8.</li>
     * <li>The max batch size in each configuration entry must be no larger than 32.</li>
     * </ul>
     * <p>The camera device doesn't have to support batch mode to achieve high speed video recording,
     * in such case, batch_size_max will be reported as 1 in each configuration entry.</p>
     * <p>This fps ranges in this configuration list can only be used to create requests
     * that are submitted to a high speed camera capture session created by
     * {@link android.hardware.camera2.CameraDevice#createConstrainedHighSpeedCaptureSession }.
     * The fps ranges reported in this metadata must not be used to setup capture requests for
     * normal capture session, or it will cause request error.</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>For each configuration, the fps_max &gt;= 120fps.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @hide
     */
    public static final Key<android.hardware.camera2.params.HighSpeedVideoConfiguration[]> CONTROL_AVAILABLE_HIGH_SPEED_VIDEO_CONFIGURATIONS =
            new Key<android.hardware.camera2.params.HighSpeedVideoConfiguration[]>("android.control.availableHighSpeedVideoConfigurations", android.hardware.camera2.params.HighSpeedVideoConfiguration[].class);

    /**
     * <p>Whether the camera device supports {@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock}</p>
     * <p>Devices with MANUAL_SENSOR capability or BURST_CAPTURE capability will always
     * list <code>true</code>. This includes FULL devices.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AE_LOCK
     */
    @PublicKey
    public static final Key<Boolean> CONTROL_AE_LOCK_AVAILABLE =
            new Key<Boolean>("android.control.aeLockAvailable", boolean.class);

    /**
     * <p>Whether the camera device supports {@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock}</p>
     * <p>Devices with MANUAL_POST_PROCESSING capability or BURST_CAPTURE capability will
     * always list <code>true</code>. This includes FULL devices.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_AWB_LOCK
     */
    @PublicKey
    public static final Key<Boolean> CONTROL_AWB_LOCK_AVAILABLE =
            new Key<Boolean>("android.control.awbLockAvailable", boolean.class);

    /**
     * <p>List of control modes for {@link CaptureRequest#CONTROL_MODE android.control.mode} that are supported by this camera
     * device.</p>
     * <p>This list contains control modes that can be set for the camera device.
     * LEGACY mode devices will always support AUTO mode. LIMITED and FULL
     * devices will always support OFF, AUTO modes.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#CONTROL_MODE android.control.mode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#CONTROL_MODE
     */
    @PublicKey
    public static final Key<int[]> CONTROL_AVAILABLE_MODES =
            new Key<int[]>("android.control.availableModes", int[].class);

    /**
     * <p>Range of boosts for {@link CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST android.control.postRawSensitivityBoost} supported
     * by this camera device.</p>
     * <p>Devices support post RAW sensitivity boost  will advertise
     * {@link CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST android.control.postRawSensitivityBoost} key for controling
     * post RAW sensitivity boost.</p>
     * <p>This key will be <code>null</code> for devices that do not support any RAW format
     * outputs. For devices that do support RAW format outputs, this key will always
     * present, and if a device does not support post RAW sensitivity boost, it will
     * list <code>(100, 100)</code> in this key.</p>
     * <p><b>Units</b>: ISO arithmetic units, the same as {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_POST_RAW_SENSITIVITY_BOOST
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>> CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE =
            new Key<android.util.Range<Integer>>("android.control.postRawSensitivityBoostRange", new TypeReference<android.util.Range<Integer>>() {{ }});

    /**
     * <p>List of edge enhancement modes for {@link CaptureRequest#EDGE_MODE android.edge.mode} that are supported by this camera
     * device.</p>
     * <p>Full-capability camera devices must always support OFF; camera devices that support
     * YUV_REPROCESSING or PRIVATE_REPROCESSING will list ZERO_SHUTTER_LAG; all devices will
     * list FAST.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#EDGE_MODE android.edge.mode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CaptureRequest#EDGE_MODE
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
    public static final Key<int[]> EDGE_AVAILABLE_EDGE_MODES =
            new Key<int[]>("android.edge.availableEdgeModes", int[].class);

    /**
     * <p>Whether this camera device has a
     * flash unit.</p>
     * <p>Will be <code>false</code> if no flash is available.</p>
     * <p>If there is no flash unit, none of the flash controls do
     * anything.
     * This key is available on all devices.</p>
     */
    @PublicKey
    public static final Key<Boolean> FLASH_INFO_AVAILABLE =
            new Key<Boolean>("android.flash.info.available", boolean.class);

    /**
     * <p>List of hot pixel correction modes for {@link CaptureRequest#HOT_PIXEL_MODE android.hotPixel.mode} that are supported by this
     * camera device.</p>
     * <p>FULL mode camera devices will always support FAST.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#HOT_PIXEL_MODE android.hotPixel.mode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#HOT_PIXEL_MODE
     */
    @PublicKey
    public static final Key<int[]> HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES =
            new Key<int[]>("android.hotPixel.availableHotPixelModes", int[].class);

    /**
     * <p>List of JPEG thumbnail sizes for {@link CaptureRequest#JPEG_THUMBNAIL_SIZE android.jpeg.thumbnailSize} supported by this
     * camera device.</p>
     * <p>This list will include at least one non-zero resolution, plus <code>(0,0)</code> for indicating no
     * thumbnail should be generated.</p>
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
     * <li>All non-<code>(0, 0)</code> sizes will have non-zero widths and heights.
     * This key is available on all devices.</li>
     * </ul>
     *
     * @see CaptureRequest#JPEG_THUMBNAIL_SIZE
     */
    @PublicKey
    public static final Key<android.util.Size[]> JPEG_AVAILABLE_THUMBNAIL_SIZES =
            new Key<android.util.Size[]>("android.jpeg.availableThumbnailSizes", android.util.Size[].class);

    /**
     * <p>List of aperture size values for {@link CaptureRequest#LENS_APERTURE android.lens.aperture} that are
     * supported by this camera device.</p>
     * <p>If the camera device doesn't support a variable lens aperture,
     * this list will contain only one value, which is the fixed aperture size.</p>
     * <p>If the camera device supports a variable aperture, the aperture values
     * in this list will be sorted in ascending order.</p>
     * <p><b>Units</b>: The aperture f-number</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#LENS_APERTURE
     */
    @PublicKey
    public static final Key<float[]> LENS_INFO_AVAILABLE_APERTURES =
            new Key<float[]>("android.lens.info.availableApertures", float[].class);

    /**
     * <p>List of neutral density filter values for
     * {@link CaptureRequest#LENS_FILTER_DENSITY android.lens.filterDensity} that are supported by this camera device.</p>
     * <p>If a neutral density filter is not supported by this camera device,
     * this list will contain only 0. Otherwise, this list will include every
     * filter density supported by the camera device, in ascending order.</p>
     * <p><b>Units</b>: Exposure value (EV)</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>Values are &gt;= 0</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#LENS_FILTER_DENSITY
     */
    @PublicKey
    public static final Key<float[]> LENS_INFO_AVAILABLE_FILTER_DENSITIES =
            new Key<float[]>("android.lens.info.availableFilterDensities", float[].class);

    /**
     * <p>List of focal lengths for {@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength} that are supported by this camera
     * device.</p>
     * <p>If optical zoom is not supported, this list will only contain
     * a single value corresponding to the fixed focal length of the
     * device. Otherwise, this list will include every focal length supported
     * by the camera device, in ascending order.</p>
     * <p><b>Units</b>: Millimeters</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>Values are &gt; 0</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     */
    @PublicKey
    public static final Key<float[]> LENS_INFO_AVAILABLE_FOCAL_LENGTHS =
            new Key<float[]>("android.lens.info.availableFocalLengths", float[].class);

    /**
     * <p>List of optical image stabilization (OIS) modes for
     * {@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode} that are supported by this camera device.</p>
     * <p>If OIS is not supported by a given camera device, this list will
     * contain only OFF.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE android.lens.opticalStabilizationMode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#LENS_OPTICAL_STABILIZATION_MODE
     */
    @PublicKey
    public static final Key<int[]> LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION =
            new Key<int[]>("android.lens.info.availableOpticalStabilization", int[].class);

    /**
     * <p>Hyperfocal distance for this lens.</p>
     * <p>If the lens is not fixed focus, the camera device will report this
     * field when {@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} is APPROXIMATE or CALIBRATED.</p>
     * <p><b>Units</b>: See {@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} for details</p>
     * <p><b>Range of valid values:</b><br>
     * If lens is fixed focus, &gt;= 0. If lens has focuser unit, the value is
     * within <code>(0.0f, {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance}]</code></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     */
    @PublicKey
    public static final Key<Float> LENS_INFO_HYPERFOCAL_DISTANCE =
            new Key<Float>("android.lens.info.hyperfocalDistance", float.class);

    /**
     * <p>Shortest distance from frontmost surface
     * of the lens that can be brought into sharp focus.</p>
     * <p>If the lens is fixed-focus, this will be
     * 0.</p>
     * <p><b>Units</b>: See {@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} for details</p>
     * <p><b>Range of valid values:</b><br>
     * &gt;= 0</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     */
    @PublicKey
    public static final Key<Float> LENS_INFO_MINIMUM_FOCUS_DISTANCE =
            new Key<Float>("android.lens.info.minimumFocusDistance", float.class);

    /**
     * <p>Dimensions of lens shading map.</p>
     * <p>The map should be on the order of 30-40 rows and columns, and
     * must be smaller than 64x64.</p>
     * <p><b>Range of valid values:</b><br>
     * Both values &gt;= 1</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <p>APPROXIMATE and CALIBRATED devices report the focus metadata in
     * units of diopters (1/meter), so <code>0.0f</code> represents focusing at infinity,
     * and increasing positive numbers represent focusing closer and closer
     * to the camera device. The focus distance control also uses diopters
     * on these devices.</p>
     * <p>UNCALIBRATED devices do not use units that are directly comparable
     * to any real physical measurement, but <code>0.0f</code> still represents farthest
     * focus, and {@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} represents the
     * nearest focus the device can achieve.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED UNCALIBRATED}</li>
     *   <li>{@link #LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE APPROXIMATE}</li>
     *   <li>{@link #LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED CALIBRATED}</li>
     * </ul></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #LENS_FACING_FRONT FRONT}</li>
     *   <li>{@link #LENS_FACING_BACK BACK}</li>
     *   <li>{@link #LENS_FACING_EXTERNAL EXTERNAL}</li>
     * </ul></p>
     * <p>This key is available on all devices.</p>
     * @see #LENS_FACING_FRONT
     * @see #LENS_FACING_BACK
     * @see #LENS_FACING_EXTERNAL
     */
    @PublicKey
    public static final Key<Integer> LENS_FACING =
            new Key<Integer>("android.lens.facing", int.class);

    /**
     * <p>The orientation of the camera relative to the sensor
     * coordinate system.</p>
     * <p>The four coefficients that describe the quaternion
     * rotation from the Android sensor coordinate system to a
     * camera-aligned coordinate system where the X-axis is
     * aligned with the long side of the image sensor, the Y-axis
     * is aligned with the short side of the image sensor, and
     * the Z-axis is aligned with the optical axis of the sensor.</p>
     * <p>To convert from the quaternion coefficients <code>(x,y,z,w)</code>
     * to the axis of rotation <code>(a_x, a_y, a_z)</code> and rotation
     * amount <code>theta</code>, the following formulas can be used:</p>
     * <pre><code> theta = 2 * acos(w)
     * a_x = x / sin(theta/2)
     * a_y = y / sin(theta/2)
     * a_z = z / sin(theta/2)
     * </code></pre>
     * <p>To create a 3x3 rotation matrix that applies the rotation
     * defined by this quaternion, the following matrix can be
     * used:</p>
     * <pre><code>R = [ 1 - 2y^2 - 2z^2,       2xy - 2zw,       2xz + 2yw,
     *            2xy + 2zw, 1 - 2x^2 - 2z^2,       2yz - 2xw,
     *            2xz - 2yw,       2yz + 2xw, 1 - 2x^2 - 2y^2 ]
     * </code></pre>
     * <p>This matrix can then be used to apply the rotation to a
     *  column vector point with</p>
     * <p><code>p' = Rp</code></p>
     * <p>where <code>p</code> is in the device sensor coordinate system, and
     *  <code>p'</code> is in the camera-oriented coordinate system.</p>
     * <p><b>Units</b>:
     * Quaternion coefficients</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    @PublicKey
    public static final Key<float[]> LENS_POSE_ROTATION =
            new Key<float[]>("android.lens.poseRotation", float[].class);

    /**
     * <p>Position of the camera optical center.</p>
     * <p>The position of the camera device's lens optical center,
     * as a three-dimensional vector <code>(x,y,z)</code>.</p>
     * <p>Prior to Android P, or when {@link CameraCharacteristics#LENS_POSE_REFERENCE android.lens.poseReference} is PRIMARY_CAMERA, this position
     * is relative to the optical center of the largest camera device facing in the same
     * direction as this camera, in the {@link android.hardware.SensorEvent Android sensor
     * coordinate axes}. Note that only the axis definitions are shared with the sensor
     * coordinate system, but not the origin.</p>
     * <p>If this device is the largest or only camera device with a given facing, then this
     * position will be <code>(0, 0, 0)</code>; a camera device with a lens optical center located 3 cm
     * from the main sensor along the +X axis (to the right from the user's perspective) will
     * report <code>(0.03, 0, 0)</code>.  Note that this means that, for many computer vision
     * applications, the position needs to be negated to convert it to a translation from the
     * camera to the origin.</p>
     * <p>To transform a pixel coordinates between two cameras facing the same direction, first
     * the source camera {@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion} must be corrected for.  Then the source
     * camera {@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration} needs to be applied, followed by the
     * {@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} of the source camera, the translation of the source camera
     * relative to the destination camera, the {@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} of the destination
     * camera, and finally the inverse of {@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration} of the destination
     * camera. This obtains a radial-distortion-free coordinate in the destination camera pixel
     * coordinates.</p>
     * <p>To compare this against a real image from the destination camera, the destination camera
     * image then needs to be corrected for radial distortion before comparison or sampling.</p>
     * <p>When {@link CameraCharacteristics#LENS_POSE_REFERENCE android.lens.poseReference} is GYROSCOPE, then this position is relative to
     * the center of the primary gyroscope on the device. The axis definitions are the same as
     * with PRIMARY_CAMERA.</p>
     * <p><b>Units</b>: Meters</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_DISTORTION
     * @see CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
     * @see CameraCharacteristics#LENS_POSE_REFERENCE
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     */
    @PublicKey
    public static final Key<float[]> LENS_POSE_TRANSLATION =
            new Key<float[]>("android.lens.poseTranslation", float[].class);

    /**
     * <p>The parameters for this camera device's intrinsic
     * calibration.</p>
     * <p>The five calibration parameters that describe the
     * transform from camera-centric 3D coordinates to sensor
     * pixel coordinates:</p>
     * <pre><code>[f_x, f_y, c_x, c_y, s]
     * </code></pre>
     * <p>Where <code>f_x</code> and <code>f_y</code> are the horizontal and vertical
     * focal lengths, <code>[c_x, c_y]</code> is the position of the optical
     * axis, and <code>s</code> is a skew parameter for the sensor plane not
     * being aligned with the lens plane.</p>
     * <p>These are typically used within a transformation matrix K:</p>
     * <pre><code>K = [ f_x,   s, c_x,
     *        0, f_y, c_y,
     *        0    0,   1 ]
     * </code></pre>
     * <p>which can then be combined with the camera pose rotation
     * <code>R</code> and translation <code>t</code> ({@link CameraCharacteristics#LENS_POSE_ROTATION android.lens.poseRotation} and
     * {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}, respectively) to calculate the
     * complete transform from world coordinates to pixel
     * coordinates:</p>
     * <pre><code>P = [ K 0   * [ R -Rt
     *      0 1 ]      0 1 ]
     * </code></pre>
     * <p>(Note the negation of poseTranslation when mapping from camera
     * to world coordinates, and multiplication by the rotation).</p>
     * <p>With <code>p_w</code> being a point in the world coordinate system
     * and <code>p_s</code> being a point in the camera active pixel array
     * coordinate system, and with the mapping including the
     * homogeneous division by z:</p>
     * <pre><code> p_h = (x_h, y_h, z_h) = P p_w
     * p_s = p_h / z_h
     * </code></pre>
     * <p>so <code>[x_s, y_s]</code> is the pixel coordinates of the world
     * point, <code>z_s = 1</code>, and <code>w_s</code> is a measurement of disparity
     * (depth) in pixel coordinates.</p>
     * <p>Note that the coordinate system for this transform is the
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} system,
     * where <code>(0,0)</code> is the top-left of the
     * preCorrectionActiveArraySize rectangle. Once the pose and
     * intrinsic calibration transforms have been applied to a
     * world point, then the {@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion}
     * transform needs to be applied, and the result adjusted to
     * be in the {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} coordinate
     * system (where <code>(0, 0)</code> is the top-left of the
     * activeArraySize rectangle), to determine the final pixel
     * coordinate of the world point for processed (non-RAW)
     * output buffers.</p>
     * <p>For camera devices, the center of pixel <code>(x,y)</code> is located at
     * coordinate <code>(x + 0.5, y + 0.5)</code>.  So on a device with a
     * precorrection active array of size <code>(10,10)</code>, the valid pixel
     * indices go from <code>(0,0)-(9,9)</code>, and an perfectly-built camera would
     * have an optical center at the exact center of the pixel grid, at
     * coordinates <code>(5.0, 5.0)</code>, which is the top-left corner of pixel
     * <code>(5,5)</code>.</p>
     * <p><b>Units</b>:
     * Pixels in the
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}
     * coordinate system.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_DISTORTION
     * @see CameraCharacteristics#LENS_POSE_ROTATION
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     */
    @PublicKey
    public static final Key<float[]> LENS_INTRINSIC_CALIBRATION =
            new Key<float[]>("android.lens.intrinsicCalibration", float[].class);

    /**
     * <p>The correction coefficients to correct for this camera device's
     * radial and tangential lens distortion.</p>
     * <p>Four radial distortion coefficients <code>[kappa_0, kappa_1, kappa_2,
     * kappa_3]</code> and two tangential distortion coefficients
     * <code>[kappa_4, kappa_5]</code> that can be used to correct the
     * lens's geometric distortion with the mapping equations:</p>
     * <pre><code> x_c = x_i * ( kappa_0 + kappa_1 * r^2 + kappa_2 * r^4 + kappa_3 * r^6 ) +
     *        kappa_4 * (2 * x_i * y_i) + kappa_5 * ( r^2 + 2 * x_i^2 )
     *  y_c = y_i * ( kappa_0 + kappa_1 * r^2 + kappa_2 * r^4 + kappa_3 * r^6 ) +
     *        kappa_5 * (2 * x_i * y_i) + kappa_4 * ( r^2 + 2 * y_i^2 )
     * </code></pre>
     * <p>Here, <code>[x_c, y_c]</code> are the coordinates to sample in the
     * input image that correspond to the pixel values in the
     * corrected image at the coordinate <code>[x_i, y_i]</code>:</p>
     * <pre><code> correctedImage(x_i, y_i) = sample_at(x_c, y_c, inputImage)
     * </code></pre>
     * <p>The pixel coordinates are defined in a normalized
     * coordinate system related to the
     * {@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration} calibration fields.
     * Both <code>[x_i, y_i]</code> and <code>[x_c, y_c]</code> have <code>(0,0)</code> at the
     * lens optical center <code>[c_x, c_y]</code>. The maximum magnitudes
     * of both x and y coordinates are normalized to be 1 at the
     * edge further from the optical center, so the range
     * for both dimensions is <code>-1 &lt;= x &lt;= 1</code>.</p>
     * <p>Finally, <code>r</code> represents the radial distance from the
     * optical center, <code>r^2 = x_i^2 + y_i^2</code>, and its magnitude
     * is therefore no larger than <code>|r| &lt;= sqrt(2)</code>.</p>
     * <p>The distortion model used is the Brown-Conrady model.</p>
     * <p><b>Units</b>:
     * Unitless coefficients.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
     * @deprecated
     * <p>This field was inconsistently defined in terms of its
     * normalization. Use {@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion} instead.</p>
     *
     * @see CameraCharacteristics#LENS_DISTORTION

     */
    @Deprecated
    @PublicKey
    public static final Key<float[]> LENS_RADIAL_DISTORTION =
            new Key<float[]>("android.lens.radialDistortion", float[].class);

    /**
     * <p>The origin for {@link CameraCharacteristics#LENS_POSE_TRANSLATION android.lens.poseTranslation}.</p>
     * <p>Different calibration methods and use cases can produce better or worse results
     * depending on the selected coordinate origin.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #LENS_POSE_REFERENCE_PRIMARY_CAMERA PRIMARY_CAMERA}</li>
     *   <li>{@link #LENS_POSE_REFERENCE_GYROSCOPE GYROSCOPE}</li>
     * </ul></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_POSE_TRANSLATION
     * @see #LENS_POSE_REFERENCE_PRIMARY_CAMERA
     * @see #LENS_POSE_REFERENCE_GYROSCOPE
     */
    @PublicKey
    public static final Key<Integer> LENS_POSE_REFERENCE =
            new Key<Integer>("android.lens.poseReference", int.class);

    /**
     * <p>The correction coefficients to correct for this camera device's
     * radial and tangential lens distortion.</p>
     * <p>Replaces the deprecated {@link CameraCharacteristics#LENS_RADIAL_DISTORTION android.lens.radialDistortion} field, which was
     * inconsistently defined.</p>
     * <p>Three radial distortion coefficients <code>[kappa_1, kappa_2,
     * kappa_3]</code> and two tangential distortion coefficients
     * <code>[kappa_4, kappa_5]</code> that can be used to correct the
     * lens's geometric distortion with the mapping equations:</p>
     * <pre><code> x_c = x_i * ( 1 + kappa_1 * r^2 + kappa_2 * r^4 + kappa_3 * r^6 ) +
     *        kappa_4 * (2 * x_i * y_i) + kappa_5 * ( r^2 + 2 * x_i^2 )
     *  y_c = y_i * ( 1 + kappa_1 * r^2 + kappa_2 * r^4 + kappa_3 * r^6 ) +
     *        kappa_5 * (2 * x_i * y_i) + kappa_4 * ( r^2 + 2 * y_i^2 )
     * </code></pre>
     * <p>Here, <code>[x_c, y_c]</code> are the coordinates to sample in the
     * input image that correspond to the pixel values in the
     * corrected image at the coordinate <code>[x_i, y_i]</code>:</p>
     * <pre><code> correctedImage(x_i, y_i) = sample_at(x_c, y_c, inputImage)
     * </code></pre>
     * <p>The pixel coordinates are defined in a coordinate system
     * related to the {@link CameraCharacteristics#LENS_INTRINSIC_CALIBRATION android.lens.intrinsicCalibration}
     * calibration fields; see that entry for details of the mapping stages.
     * Both <code>[x_i, y_i]</code> and <code>[x_c, y_c]</code>
     * have <code>(0,0)</code> at the lens optical center <code>[c_x, c_y]</code>, and
     * the range of the coordinates depends on the focal length
     * terms of the intrinsic calibration.</p>
     * <p>Finally, <code>r</code> represents the radial distance from the
     * optical center, <code>r^2 = x_i^2 + y_i^2</code>.</p>
     * <p>The distortion model used is the Brown-Conrady model.</p>
     * <p><b>Units</b>:
     * Unitless coefficients.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
     * @see CameraCharacteristics#LENS_RADIAL_DISTORTION
     */
    @PublicKey
    public static final Key<float[]> LENS_DISTORTION =
            new Key<float[]>("android.lens.distortion", float[].class);

    /**
     * <p>List of noise reduction modes for {@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode} that are supported
     * by this camera device.</p>
     * <p>Full-capability camera devices will always support OFF and FAST.</p>
     * <p>Camera devices that support YUV_REPROCESSING or PRIVATE_REPROCESSING will support
     * ZERO_SHUTTER_LAG.</p>
     * <p>Legacy-capability camera devices will only support FAST mode.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <p>Not used in HALv3 or newer; replaced by better partials mechanism</p>

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
     *   Typically {@link android.graphics.ImageFormat#JPEG JPEG format}.</li>
     * <li>Raw formats: {@link android.graphics.ImageFormat#RAW_SENSOR RAW_SENSOR}, {@link android.graphics.ImageFormat#RAW10 RAW10}, or
     *   {@link android.graphics.ImageFormat#RAW12 RAW12}.</li>
     * <li>Processed (but not-stalling): any non-RAW format without a stall duration.  Typically
     *   {@link android.graphics.ImageFormat#YUV_420_888 YUV_420_888},
     *   {@link android.graphics.ImageFormat#NV21 NV21}, or {@link android.graphics.ImageFormat#YV12 YV12}.</li>
     * </ul>
     * <p><b>Range of valid values:</b><br></p>
     * <p>For processed (and stalling) format streams, &gt;= 1.</p>
     * <p>For Raw format (either stalling or non-stalling) streams, &gt;= 0.</p>
     * <p>For processed (but not stalling) format streams, &gt;= 3
     * for FULL mode devices (<code>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == FULL</code>);
     * &gt;= 2 for LIMITED mode devices (<code>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == LIMITED</code>).</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <li>{@link android.graphics.ImageFormat#RAW_SENSOR RAW_SENSOR}</li>
     * <li>{@link android.graphics.ImageFormat#RAW10 RAW10}</li>
     * <li>{@link android.graphics.ImageFormat#RAW12 RAW12}</li>
     * </ul>
     * <p>LEGACY mode devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} <code>==</code> LEGACY)
     * never support raw streams.</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>&gt;= 0</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <li>{@link android.graphics.ImageFormat#YUV_420_888 YUV_420_888}</li>
     * <li>{@link android.graphics.ImageFormat#NV21 NV21}</li>
     * <li>{@link android.graphics.ImageFormat#YV12 YV12}</li>
     * <li>Implementation-defined formats, i.e. {@link android.hardware.camera2.params.StreamConfigurationMap#isOutputSupportedFor(Class) }</li>
     * </ul>
     * <p>For full guarantees, query {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputStallDuration } with a
     * processed format -- it will return 0 for a non-stalling stream.</p>
     * <p>LEGACY devices will support at least 2 processing/non-stalling streams.</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>&gt;= 3
     * for FULL mode devices (<code>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == FULL</code>);
     * &gt;= 2 for LIMITED mode devices (<code>{@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == LIMITED</code>).</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <p>A processed and stalling format is defined as any non-RAW format with a stallDurations
     * &gt; 0.  Typically only the {@link android.graphics.ImageFormat#JPEG JPEG format} is a stalling format.</p>
     * <p>For full guarantees, query {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputStallDuration } with a
     * processed format -- it will return a non-0 value for a stalling stream.</p>
     * <p>LEGACY devices will support up to 1 processing/stalling stream.</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>&gt;= 1</p>
     * <p>This key is available on all devices.</p>
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
     * <p>The image format for a input stream can be any supported format returned by {@link android.hardware.camera2.params.StreamConfigurationMap#getInputFormats }. When using an
     * input stream, there must be at least one output stream configured to to receive the
     * reprocessed images.</p>
     * <p>When an input stream and some output streams are used in a reprocessing request,
     * only the input buffer will be used to produce these output stream buffers, and a
     * new sensor image will not be captured.</p>
     * <p>For example, for Zero Shutter Lag (ZSL) still capture use case, the input
     * stream image format will be PRIVATE, the associated output stream image format
     * should be JPEG.</p>
     * <p><b>Range of valid values:</b><br></p>
     * <p>0 or 1.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
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
     * <p>This value will normally be 8 or less, however, for high speed capture session,
     * the max pipeline depth will be up to 8 x size of high speed capture request list.</p>
     * <p>This key is available on all devices.</p>
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
     * <p><b>Range of valid values:</b><br>
     * &gt;= 1</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    @PublicKey
    public static final Key<Integer> REQUEST_PARTIAL_RESULT_COUNT =
            new Key<Integer>("android.request.partialResultCount", int.class);

    /**
     * <p>List of capabilities that this camera device
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
     * devices, but the application should query this key to be sure.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE BACKWARD_COMPATIBLE}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR MANUAL_SENSOR}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING MANUAL_POST_PROCESSING}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING PRIVATE_REPROCESSING}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS READ_SENSOR_SETTINGS}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE BURST_CAPTURE}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING YUV_REPROCESSING}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT DEPTH_OUTPUT}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO CONSTRAINED_HIGH_SPEED_VIDEO}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING MOTION_TRACKING}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA LOGICAL_MULTI_CAMERA}</li>
     *   <li>{@link #REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME MONOCHROME}</li>
     * </ul></p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see #REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
     * @see #REQUEST_AVAILABLE_CAPABILITIES_RAW
     * @see #REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING
     * @see #REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS
     * @see #REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
     * @see #REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING
     * @see #REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
     * @see #REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING
     * @see #REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
     * @see #REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME
     */
    @PublicKey
    public static final Key<int[]> REQUEST_AVAILABLE_CAPABILITIES =
            new Key<int[]>("android.request.availableCapabilities", int[].class);

    /**
     * <p>A list of all keys that the camera device has available
     * to use with {@link android.hardware.camera2.CaptureRequest }.</p>
     * <p>Attempting to set a key into a CaptureRequest that is not
     * listed here will result in an invalid request and will be rejected
     * by the camera device.</p>
     * <p>This field can be used to query the feature set of a camera device
     * at a more granular level than capabilities. This is especially
     * important for optional keys that are not listed under any capability
     * in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_REQUEST_KEYS =
            new Key<int[]>("android.request.availableRequestKeys", int[].class);

    /**
     * <p>A list of all keys that the camera device has available to use with {@link android.hardware.camera2.CaptureResult }.</p>
     * <p>Attempting to get a key from a CaptureResult that is not
     * listed here will always return a <code>null</code> value. Getting a key from
     * a CaptureResult that is listed here will generally never return a <code>null</code>
     * value.</p>
     * <p>The following keys may return <code>null</code> unless they are enabled:</p>
     * <ul>
     * <li>android.statistics.lensShadingMap (non-null iff {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} == ON)</li>
     * </ul>
     * <p>(Those sometimes-null keys will nevertheless be listed here
     * if they are available.)</p>
     * <p>This field can be used to query the feature set of a camera device
     * at a more granular level than capabilities. This is especially
     * important for optional keys that are not listed under any capability
     * in {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}.</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_RESULT_KEYS =
            new Key<int[]>("android.request.availableResultKeys", int[].class);

    /**
     * <p>A list of all keys that the camera device has available to use with {@link android.hardware.camera2.CameraCharacteristics }.</p>
     * <p>This entry follows the same rules as
     * android.request.availableResultKeys (except that it applies for
     * CameraCharacteristics instead of CaptureResult). See above for more
     * details.</p>
     * <p>This key is available on all devices.</p>
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_CHARACTERISTICS_KEYS =
            new Key<int[]>("android.request.availableCharacteristicsKeys", int[].class);

    /**
     * <p>A subset of the available request keys that the camera device
     * can pass as part of the capture session initialization.</p>
     * <p>This is a subset of android.request.availableRequestKeys which
     * contains a list of keys that are difficult to apply per-frame and
     * can result in unexpected delays when modified during the capture session
     * lifetime. Typical examples include parameters that require a
     * time-consuming hardware re-configuration or internal camera pipeline
     * change. For performance reasons we advise clients to pass their initial
     * values as part of
     * {@link SessionConfiguration#setSessionParameters }.
     * Once the camera capture session is enabled it is also recommended to avoid
     * changing them from their initial values set in
     * {@link SessionConfiguration#setSessionParameters }.
     * Control over session parameters can still be exerted in capture requests
     * but clients should be aware and expect delays during their application.
     * An example usage scenario could look like this:</p>
     * <ul>
     * <li>The camera client starts by quering the session parameter key list via
     *   {@link android.hardware.camera2.CameraCharacteristics#getAvailableSessionKeys }.</li>
     * <li>Before triggering the capture session create sequence, a capture request
     *   must be built via
     *   {@link CameraDevice#createCaptureRequest }
     *   using an appropriate template matching the particular use case.</li>
     * <li>The client should go over the list of session parameters and check
     *   whether some of the keys listed matches with the parameters that
     *   they intend to modify as part of the first capture request.</li>
     * <li>If there is no such match, the capture request can be  passed
     *   unmodified to
     *   {@link SessionConfiguration#setSessionParameters }.</li>
     * <li>If matches do exist, the client should update the respective values
     *   and pass the request to
     *   {@link SessionConfiguration#setSessionParameters }.</li>
     * <li>After the capture session initialization completes the session parameter
     *   key list can continue to serve as reference when posting or updating
     *   further requests. As mentioned above further changes to session
     *   parameters should ideally be avoided, if updates are necessary
     *   however clients could expect a delay/glitch during the
     *   parameter switch.</li>
     * </ul>
     * <p>This key is available on all devices.</p>
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_SESSION_KEYS =
            new Key<int[]>("android.request.availableSessionKeys", int[].class);

    /**
     * <p>A subset of the available request keys that can be overridden for
     * physical devices backing a logical multi-camera.</p>
     * <p>This is a subset of android.request.availableRequestKeys which contains a list
     * of keys that can be overridden using {@link CaptureRequest.Builder#setPhysicalCameraKey }.
     * The respective value of such request key can be obtained by calling
     * {@link CaptureRequest.Builder#getPhysicalCameraKey }. Capture requests that contain
     * individual physical device requests must be built via
     * {@link android.hardware.camera2.CameraDevice#createCaptureRequest(int, Set)}.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @hide
     */
    public static final Key<int[]> REQUEST_AVAILABLE_PHYSICAL_CAMERA_REQUEST_KEYS =
            new Key<int[]>("android.request.availablePhysicalCameraRequestKeys", int[].class);

    /**
     * <p>The list of image formats that are supported by this
     * camera device for output streams.</p>
     * <p>All camera devices will support JPEG and YUV_420_888 formats.</p>
     * <p>When set to YUV_420_888, application can access the YUV420 data directly.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @deprecated
     * <p>Not used in HALv3 or newer</p>

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
     * <p><b>Units</b>: Nanoseconds</p>
     * <p><b>Range of valid values:</b><br>
     * TODO: Remove property.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @deprecated
     * <p>Not used in HALv3 or newer</p>

     * @hide
     */
    @Deprecated
    public static final Key<long[]> SCALER_AVAILABLE_JPEG_MIN_DURATIONS =
            new Key<long[]>("android.scaler.availableJpegMinDurations", long[].class);

    /**
     * <p>The JPEG resolutions that are supported by this camera device.</p>
     * <p>The resolutions are listed as <code>(width, height)</code> pairs. All camera devices will support
     * sensor maximum resolution (defined by {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}).</p>
     * <p><b>Range of valid values:</b><br>
     * TODO: Remove property.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @deprecated
     * <p>Not used in HALv3 or newer</p>

     * @hide
     */
    @Deprecated
    public static final Key<android.util.Size[]> SCALER_AVAILABLE_JPEG_SIZES =
            new Key<android.util.Size[]>("android.scaler.availableJpegSizes", android.util.Size[].class);

    /**
     * <p>The maximum ratio between both active area width
     * and crop region width, and active area height and
     * crop region height, for {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}.</p>
     * <p>This represents the maximum amount of zooming possible by
     * the camera device, or equivalently, the minimum cropping
     * window size.</p>
     * <p>Crop regions that have a width or height that is smaller
     * than this ratio allows will be rounded up to the minimum
     * allowed size by the camera device.</p>
     * <p><b>Units</b>: Zoom scale factor</p>
     * <p><b>Range of valid values:</b><br>
     * &gt;=1</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#SCALER_CROP_REGION
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
     * <p><b>Units</b>: Nanoseconds</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @deprecated
     * <p>Not used in HALv3 or newer</p>

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
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @deprecated
     * <p>Not used in HALv3 or newer</p>

     * @hide
     */
    @Deprecated
    public static final Key<android.util.Size[]> SCALER_AVAILABLE_PROCESSED_SIZES =
            new Key<android.util.Size[]>("android.scaler.availableProcessedSizes", android.util.Size[].class);

    /**
     * <p>The mapping of image formats that are supported by this
     * camera device for input streams, to their corresponding output formats.</p>
     * <p>All camera devices with at least 1
     * {@link CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS android.request.maxNumInputStreams} will have at least one
     * available input format.</p>
     * <p>The camera device will support the following map of formats,
     * if its dependent capability ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}) is supported:</p>
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
     * <td align="left">{@link android.graphics.ImageFormat#PRIVATE }</td>
     * <td align="left">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="left">PRIVATE_REPROCESSING</td>
     * </tr>
     * <tr>
     * <td align="left">{@link android.graphics.ImageFormat#PRIVATE }</td>
     * <td align="left">{@link android.graphics.ImageFormat#YUV_420_888 }</td>
     * <td align="left">PRIVATE_REPROCESSING</td>
     * </tr>
     * <tr>
     * <td align="left">{@link android.graphics.ImageFormat#YUV_420_888 }</td>
     * <td align="left">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="left">YUV_REPROCESSING</td>
     * </tr>
     * <tr>
     * <td align="left">{@link android.graphics.ImageFormat#YUV_420_888 }</td>
     * <td align="left">{@link android.graphics.ImageFormat#YUV_420_888 }</td>
     * <td align="left">YUV_REPROCESSING</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>PRIVATE refers to a device-internal format that is not directly application-visible.  A
     * PRIVATE input surface can be acquired by {@link android.media.ImageReader#newInstance }
     * with {@link android.graphics.ImageFormat#PRIVATE } as the format.</p>
     * <p>For a PRIVATE_REPROCESSING-capable camera device, using the PRIVATE format as either input
     * or output will never hurt maximum frame rate (i.e.  {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputStallDuration getOutputStallDuration(ImageFormat.PRIVATE, size)} is always 0),</p>
     * <p>Attempting to configure an input stream with output streams not
     * listed as available in this map is not valid.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#REQUEST_MAX_NUM_INPUT_STREAMS
     * @hide
     */
    public static final Key<android.hardware.camera2.params.ReprocessFormatsMap> SCALER_AVAILABLE_INPUT_OUTPUT_FORMATS_MAP =
            new Key<android.hardware.camera2.params.ReprocessFormatsMap>("android.scaler.availableInputOutputFormatsMap", android.hardware.camera2.params.ReprocessFormatsMap.class);

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
     * <p>This key is available on all devices.</p>
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
     * <p><b>Units</b>: (format, width, height, ns) x n</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfigurationDuration[]> SCALER_AVAILABLE_MIN_FRAME_DURATIONS =
            new Key<android.hardware.camera2.params.StreamConfigurationDuration[]>("android.scaler.availableMinFrameDurations", android.hardware.camera2.params.StreamConfigurationDuration[].class);

    /**
     * <p>This lists the maximum stall duration for each
     * output format/size combination.</p>
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
     * <li>{@link android.graphics.ImageFormat#JPEG }</li>
     * <li>{@link android.graphics.ImageFormat#RAW_SENSOR }</li>
     * </ul>
     * <p>The following formats will never have a stall duration:</p>
     * <ul>
     * <li>{@link android.graphics.ImageFormat#YUV_420_888 }</li>
     * <li>{@link android.graphics.ImageFormat#RAW10 }</li>
     * <li>{@link android.graphics.ImageFormat#RAW12 }</li>
     * </ul>
     * <p>All other formats may or may not have an allowed stall duration on
     * a per-capability basis; refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities}
     * for more details.</p>
     * <p>See {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} for more information about
     * calculating the max frame rate (absent stalls).</p>
     * <p><b>Units</b>: (format, width, height, ns) x n</p>
     * <p>This key is available on all devices.</p>
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
     * <td align="center">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="center">{@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} (*1)</td>
     * <td align="center">Any</td>
     * <td align="center"></td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="center">1920x1080 (1080p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 1080p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="center">1280x720 (720p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 720p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="center">640x480 (480p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 480p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#JPEG }</td>
     * <td align="center">320x240 (240p)</td>
     * <td align="center">Any</td>
     * <td align="center">if 240p &lt;= activeArraySize</td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#YUV_420_888 }</td>
     * <td align="center">all output sizes available for JPEG</td>
     * <td align="center">FULL</td>
     * <td align="center"></td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#YUV_420_888 }</td>
     * <td align="center">all output sizes available for JPEG, up to the maximum video size</td>
     * <td align="center">LIMITED</td>
     * <td align="center"></td>
     * </tr>
     * <tr>
     * <td align="center">{@link android.graphics.ImageFormat#PRIVATE }</td>
     * <td align="center">same as YUV_420_888</td>
     * <td align="center">Any</td>
     * <td align="center"></td>
     * </tr>
     * </tbody>
     * </table>
     * <p>Refer to {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} and {@link android.hardware.camera2.CameraDevice#createCaptureSession } for additional mandatory
     * stream configurations on a per-capability basis.</p>
     * <p>*1: For JPEG format, the sizes may be restricted by below conditions:</p>
     * <ul>
     * <li>The HAL may choose the aspect ratio of each Jpeg size to be one of well known ones
     * (e.g. 4:3, 16:9, 3:2 etc.). If the sensor maximum resolution
     * (defined by {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}) has an aspect ratio other than these,
     * it does not have to be included in the supported JPEG sizes.</li>
     * <li>Some hardware JPEG encoders may have pixel boundary alignment requirements, such as
     * the dimensions being a multiple of 16.
     * Therefore, the maximum JPEG size may be smaller than sensor maximum resolution.
     * However, the largest JPEG size will be as close as possible to the sensor maximum
     * resolution given above constraints. It is required that after aspect ratio adjustments,
     * additional size reduction due to other issues must be less than 3% in area. For example,
     * if the sensor maximum resolution is 3280x2464, if the maximum JPEG size has aspect
     * ratio 4:3, and the JPEG encoder alignment requirement is 16, the maximum JPEG size will be
     * 3264x2448.</li>
     * </ul>
     * <p>This key is available on all devices.</p>
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
     * <p>LEGACY capability devices will only support CENTER_ONLY cropping.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #SCALER_CROPPING_TYPE_CENTER_ONLY CENTER_ONLY}</li>
     *   <li>{@link #SCALER_CROPPING_TYPE_FREEFORM FREEFORM}</li>
     * </ul></p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see #SCALER_CROPPING_TYPE_CENTER_ONLY
     * @see #SCALER_CROPPING_TYPE_FREEFORM
     */
    @PublicKey
    public static final Key<Integer> SCALER_CROPPING_TYPE =
            new Key<Integer>("android.scaler.croppingType", int.class);

    /**
     * <p>The area of the image sensor which corresponds to active pixels after any geometric
     * distortion correction has been applied.</p>
     * <p>This is the rectangle representing the size of the active region of the sensor (i.e.
     * the region that actually receives light from the scene) after any geometric correction
     * has been applied, and should be treated as the maximum size in pixels of any of the
     * image output formats aside from the raw formats.</p>
     * <p>This rectangle is defined relative to the full pixel array; (0,0) is the top-left of
     * the full pixel array, and the size of the full pixel array is given by
     * {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}.</p>
     * <p>The coordinate system for most other keys that list pixel coordinates, including
     * {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}, is defined relative to the active array rectangle given in
     * this field, with <code>(0, 0)</code> being the top-left of this rectangle.</p>
     * <p>The active array may be smaller than the full pixel array, since the full array may
     * include black calibration pixels or other inactive regions.</p>
     * <p>For devices that do not support {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the active
     * array must be the same as {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.</p>
     * <p>For devices that support {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} control, the active array must
     * be enclosed by {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}. The difference between
     * pre-correction active array and active array accounts for scaling or cropping caused
     * by lens geometric distortion correction.</p>
     * <p>In general, application should always refer to active array size for controls like
     * metering regions or crop region. Two exceptions are when the application is dealing with
     * RAW image buffers (RAW_SENSOR, RAW10, RAW12 etc), or when application explicitly set
     * {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} to OFF. In these cases, application should refer
     * to {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}.</p>
     * <p><b>Units</b>: Pixel coordinates on the image sensor</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     */
    @PublicKey
    public static final Key<android.graphics.Rect> SENSOR_INFO_ACTIVE_ARRAY_SIZE =
            new Key<android.graphics.Rect>("android.sensor.info.activeArraySize", android.graphics.Rect.class);

    /**
     * <p>Range of sensitivities for {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity} supported by this
     * camera device.</p>
     * <p>The values are the standard ISO sensitivity values,
     * as defined in ISO 12232:2006.</p>
     * <p><b>Range of valid values:</b><br>
     * Min &lt;= 100, Max &gt;= 800</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    @PublicKey
    public static final Key<android.util.Range<Integer>> SENSOR_INFO_SENSITIVITY_RANGE =
            new Key<android.util.Range<Integer>>("android.sensor.info.sensitivityRange", new TypeReference<android.util.Range<Integer>>() {{ }});

    /**
     * <p>The arrangement of color filters on sensor;
     * represents the colors in the top-left 2x2 section of
     * the sensor, in reading order.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB RGGB}</li>
     *   <li>{@link #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG GRBG}</li>
     *   <li>{@link #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG GBRG}</li>
     *   <li>{@link #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR BGGR}</li>
     *   <li>{@link #SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB RGB}</li>
     * </ul></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
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
     * <p>The range of image exposure times for {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime} supported
     * by this camera device.</p>
     * <p><b>Units</b>: Nanoseconds</p>
     * <p><b>Range of valid values:</b><br>
     * The minimum exposure time will be less than 100 us. For FULL
     * capability devices ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == FULL),
     * the maximum exposure time will be greater than 100ms.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     */
    @PublicKey
    public static final Key<android.util.Range<Long>> SENSOR_INFO_EXPOSURE_TIME_RANGE =
            new Key<android.util.Range<Long>>("android.sensor.info.exposureTimeRange", new TypeReference<android.util.Range<Long>>() {{ }});

    /**
     * <p>The maximum possible frame duration (minimum frame rate) for
     * {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration} that is supported this camera device.</p>
     * <p>Attempting to use frame durations beyond the maximum will result in the frame
     * duration being clipped to the maximum. See that control for a full definition of frame
     * durations.</p>
     * <p>Refer to {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputMinFrameDuration }
     * for the minimum frame duration values.</p>
     * <p><b>Units</b>: Nanoseconds</p>
     * <p><b>Range of valid values:</b><br>
     * For FULL capability devices
     * ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} == FULL), at least 100ms.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
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
     * <p><b>Units</b>: Millimeters</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     */
    @PublicKey
    public static final Key<android.util.SizeF> SENSOR_INFO_PHYSICAL_SIZE =
            new Key<android.util.SizeF>("android.sensor.info.physicalSize", android.util.SizeF.class);

    /**
     * <p>Dimensions of the full pixel array, possibly
     * including black calibration pixels.</p>
     * <p>The pixel count of the full pixel array of the image sensor, which covers
     * {@link CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE android.sensor.info.physicalSize} area.  This represents the full pixel dimensions of
     * the raw buffers produced by this sensor.</p>
     * <p>If a camera device supports raw sensor formats, either this or
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} is the maximum dimensions for the raw
     * output formats listed in {@link android.hardware.camera2.params.StreamConfigurationMap }
     * (this depends on whether or not the image sensor returns buffers containing pixels that
     * are not part of the active array region for blacklevel calibration or other purposes).</p>
     * <p>Some parts of the full pixel array may not receive light from the scene,
     * or be otherwise inactive.  The {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} key
     * defines the rectangle of active pixels that will be included in processed image
     * formats.</p>
     * <p><b>Units</b>: Pixels</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_PHYSICAL_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
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
     * {@link CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN android.sensor.blackLevelPattern} key.</p>
     * <p>The white level is typically determined either by sensor bit depth
     * (8-14 bits is expected), or by the point where the sensor response
     * becomes too non-linear to be useful.  The default value for this is
     * maximum representable value for a 16-bit raw sample (2^16 - 1).</p>
     * <p>The white level values of captured images may vary for different
     * capture settings (e.g., {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}). This key
     * represents a coarse approximation for such case. It is recommended
     * to use {@link CaptureResult#SENSOR_DYNAMIC_WHITE_LEVEL android.sensor.dynamicWhiteLevel} for captures when supported
     * by the camera device, which provides more accurate white level values.</p>
     * <p><b>Range of valid values:</b><br>
     * &gt; 255 (8-bit output)</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN
     * @see CaptureResult#SENSOR_DYNAMIC_WHITE_LEVEL
     * @see CaptureRequest#SENSOR_SENSITIVITY
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
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN UNKNOWN}</li>
     *   <li>{@link #SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME REALTIME}</li>
     * </ul></p>
     * <p>This key is available on all devices.</p>
     * @see #SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
     * @see #SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
     */
    @PublicKey
    public static final Key<Integer> SENSOR_INFO_TIMESTAMP_SOURCE =
            new Key<Integer>("android.sensor.info.timestampSource", int.class);

    /**
     * <p>Whether the RAW images output from this camera device are subject to
     * lens shading correction.</p>
     * <p>If TRUE, all images produced by the camera device in the RAW image formats will
     * have lens shading correction already applied to it. If FALSE, the images will
     * not be adjusted for lens shading correction.
     * See {@link CameraCharacteristics#REQUEST_MAX_NUM_OUTPUT_RAW android.request.maxNumOutputRaw} for a list of RAW image formats.</p>
     * <p>This key will be <code>null</code> for all devices do not report this information.
     * Devices with RAW capability will always report this information in this key.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#REQUEST_MAX_NUM_OUTPUT_RAW
     */
    @PublicKey
    public static final Key<Boolean> SENSOR_INFO_LENS_SHADING_APPLIED =
            new Key<Boolean>("android.sensor.info.lensShadingApplied", boolean.class);

    /**
     * <p>The area of the image sensor which corresponds to active pixels prior to the
     * application of any geometric distortion correction.</p>
     * <p>This is the rectangle representing the size of the active region of the sensor (i.e.
     * the region that actually receives light from the scene) before any geometric correction
     * has been applied, and should be treated as the active region rectangle for any of the
     * raw formats.  All metadata associated with raw processing (e.g. the lens shading
     * correction map, and radial distortion fields) treats the top, left of this rectangle as
     * the origin, (0,0).</p>
     * <p>The size of this region determines the maximum field of view and the maximum number of
     * pixels that an image from this sensor can contain, prior to the application of
     * geometric distortion correction. The effective maximum pixel dimensions of a
     * post-distortion-corrected image is given by the {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}
     * field, and the effective maximum field of view for a post-distortion-corrected image
     * can be calculated by applying the geometric distortion correction fields to this
     * rectangle, and cropping to the rectangle given in {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.</p>
     * <p>E.g. to calculate position of a pixel, (x,y), in a processed YUV output image with the
     * dimensions in {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} given the position of a pixel,
     * (x', y'), in the raw pixel array with dimensions give in
     * {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}:</p>
     * <ol>
     * <li>Choose a pixel (x', y') within the active array region of the raw buffer given in
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize}, otherwise this pixel is considered
     * to be outside of the FOV, and will not be shown in the processed output image.</li>
     * <li>Apply geometric distortion correction to get the post-distortion pixel coordinate,
     * (x_i, y_i). When applying geometric correction metadata, note that metadata for raw
     * buffers is defined relative to the top, left of the
     * {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} rectangle.</li>
     * <li>If the resulting corrected pixel coordinate is within the region given in
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}, then the position of this pixel in the
     * processed output image buffer is <code>(x_i - activeArray.left, y_i - activeArray.top)</code>,
     * when the top, left coordinate of that buffer is treated as (0, 0).</li>
     * </ol>
     * <p>Thus, for pixel x',y' = (25, 25) on a sensor where {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}
     * is (100,100), {@link CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE android.sensor.info.preCorrectionActiveArraySize} is (10, 10, 100, 100),
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize} is (20, 20, 80, 80), and the geometric distortion
     * correction doesn't change the pixel coordinate, the resulting pixel selected in
     * pixel coordinates would be x,y = (25, 25) relative to the top,left of the raw buffer
     * with dimensions given in {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}, and would be (5, 5)
     * relative to the top,left of post-processed YUV output buffer with dimensions given in
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.</p>
     * <p>The currently supported fields that correct for geometric distortion are:</p>
     * <ol>
     * <li>{@link CameraCharacteristics#LENS_DISTORTION android.lens.distortion}.</li>
     * </ol>
     * <p>If the camera device doesn't support geometric distortion correction, or all of the
     * geometric distortion fields are no-ops, this rectangle will be the same as the
     * post-distortion-corrected rectangle given in {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.</p>
     * <p>This rectangle is defined relative to the full pixel array; (0,0) is the top-left of
     * the full pixel array, and the size of the full pixel array is given by
     * {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}.</p>
     * <p>The pre-correction active array may be smaller than the full pixel array, since the
     * full array may include black calibration pixels or other inactive regions.</p>
     * <p><b>Units</b>: Pixel coordinates on the image sensor</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#LENS_DISTORTION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
     */
    @PublicKey
    public static final Key<android.graphics.Rect> SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE =
            new Key<android.graphics.Rect>("android.sensor.info.preCorrectionActiveArraySize", android.graphics.Rect.class);

    /**
     * <p>The standard reference illuminant used as the scene light source when
     * calculating the {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM1 android.sensor.colorTransform1},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1 android.sensor.calibrationTransform1}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX1 android.sensor.forwardMatrix1} matrices.</p>
     * <p>The values in this key correspond to the values defined for the
     * EXIF LightSource tag. These illuminants are standard light sources
     * that are often used calibrating camera devices.</p>
     * <p>If this key is present, then {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM1 android.sensor.colorTransform1},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM1 android.sensor.calibrationTransform1}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX1 android.sensor.forwardMatrix1} will also be present.</p>
     * <p>Some devices may choose to provide a second set of calibration
     * information for improved quality, including
     * {@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT2 android.sensor.referenceIlluminant2} and its corresponding matrices.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT DAYLIGHT}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT FLUORESCENT}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN TUNGSTEN}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_FLASH FLASH}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER FINE_WEATHER}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER CLOUDY_WEATHER}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_SHADE SHADE}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT DAYLIGHT_FLUORESCENT}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT DAY_WHITE_FLUORESCENT}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT COOL_WHITE_FLUORESCENT}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT WHITE_FLUORESCENT}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A STANDARD_A}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B STANDARD_B}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C STANDARD_C}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_D55 D55}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_D65 D65}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_D75 D75}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_D50 D50}</li>
     *   <li>{@link #SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN ISO_STUDIO_TUNGSTEN}</li>
     * </ul></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
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
     * <p>See {@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1} for more details.</p>
     * <p>If this key is present, then {@link CameraCharacteristics#SENSOR_COLOR_TRANSFORM2 android.sensor.colorTransform2},
     * {@link CameraCharacteristics#SENSOR_CALIBRATION_TRANSFORM2 android.sensor.calibrationTransform2}, and
     * {@link CameraCharacteristics#SENSOR_FORWARD_MATRIX2 android.sensor.forwardMatrix2} will also be present.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CameraCharacteristics#SENSOR_REFERENCE_ILLUMINANT1 android.sensor.referenceIlluminant1}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
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
     * <p>This key specifies the zero light value for each of the CFA mosaic
     * channels in the camera sensor.  The maximal value output by the
     * sensor is represented by the value in {@link CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL android.sensor.info.whiteLevel}.</p>
     * <p>The values are given in the same order as channels listed for the CFA
     * layout key (see {@link CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT android.sensor.info.colorFilterArrangement}), i.e. the
     * nth value given corresponds to the black level offset for the nth
     * color channel listed in the CFA.</p>
     * <p>The black level values of captured images may vary for different
     * capture settings (e.g., {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}). This key
     * represents a coarse approximation for such case. It is recommended to
     * use {@link CaptureResult#SENSOR_DYNAMIC_BLACK_LEVEL android.sensor.dynamicBlackLevel} or use pixels from
     * {@link CameraCharacteristics#SENSOR_OPTICAL_BLACK_REGIONS android.sensor.opticalBlackRegions} directly for captures when
     * supported by the camera device, which provides more accurate black
     * level values. For raw capture in particular, it is recommended to use
     * pixels from {@link CameraCharacteristics#SENSOR_OPTICAL_BLACK_REGIONS android.sensor.opticalBlackRegions} to calculate black
     * level values for each frame.</p>
     * <p><b>Range of valid values:</b><br>
     * &gt;= 0 for each.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureResult#SENSOR_DYNAMIC_BLACK_LEVEL
     * @see CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     * @see CameraCharacteristics#SENSOR_INFO_WHITE_LEVEL
     * @see CameraCharacteristics#SENSOR_OPTICAL_BLACK_REGIONS
     * @see CaptureRequest#SENSOR_SENSITIVITY
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
     * <p>Clockwise angle through which the output image needs to be rotated to be
     * upright on the device screen in its native orientation.</p>
     * <p>Also defines the direction of rolling shutter readout, which is from top to bottom in
     * the sensor's coordinate system.</p>
     * <p><b>Units</b>: Degrees of clockwise rotation; always a multiple of
     * 90</p>
     * <p><b>Range of valid values:</b><br>
     * 0, 90, 180, 270</p>
     * <p>This key is available on all devices.</p>
     */
    @PublicKey
    public static final Key<Integer> SENSOR_ORIENTATION =
            new Key<Integer>("android.sensor.orientation", int.class);

    /**
     * <p>List of sensor test pattern modes for {@link CaptureRequest#SENSOR_TEST_PATTERN_MODE android.sensor.testPatternMode}
     * supported by this camera device.</p>
     * <p>Defaults to OFF, and always includes OFF if defined.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#SENSOR_TEST_PATTERN_MODE android.sensor.testPatternMode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#SENSOR_TEST_PATTERN_MODE
     */
    @PublicKey
    public static final Key<int[]> SENSOR_AVAILABLE_TEST_PATTERN_MODES =
            new Key<int[]>("android.sensor.availableTestPatternModes", int[].class);

    /**
     * <p>List of disjoint rectangles indicating the sensor
     * optically shielded black pixel regions.</p>
     * <p>In most camera sensors, the active array is surrounded by some
     * optically shielded pixel areas. By blocking light, these pixels
     * provides a reliable black reference for black level compensation
     * in active array region.</p>
     * <p>This key provides a list of disjoint rectangles specifying the
     * regions of optically shielded (with metal shield) black pixel
     * regions if the camera device is capable of reading out these black
     * pixels in the output raw images. In comparison to the fixed black
     * level values reported by {@link CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN android.sensor.blackLevelPattern}, this key
     * may provide a more accurate way for the application to calculate
     * black level of each captured raw images.</p>
     * <p>When this key is reported, the {@link CaptureResult#SENSOR_DYNAMIC_BLACK_LEVEL android.sensor.dynamicBlackLevel} and
     * {@link CaptureResult#SENSOR_DYNAMIC_WHITE_LEVEL android.sensor.dynamicWhiteLevel} will also be reported.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_BLACK_LEVEL_PATTERN
     * @see CaptureResult#SENSOR_DYNAMIC_BLACK_LEVEL
     * @see CaptureResult#SENSOR_DYNAMIC_WHITE_LEVEL
     */
    @PublicKey
    public static final Key<android.graphics.Rect[]> SENSOR_OPTICAL_BLACK_REGIONS =
            new Key<android.graphics.Rect[]>("android.sensor.opticalBlackRegions", android.graphics.Rect[].class);

    /**
     * <p>List of lens shading modes for {@link CaptureRequest#SHADING_MODE android.shading.mode} that are supported by this camera device.</p>
     * <p>This list contains lens shading modes that can be set for the camera device.
     * Camera devices that support the MANUAL_POST_PROCESSING capability will always
     * list OFF and FAST mode. This includes all FULL level devices.
     * LEGACY devices will always only support FAST mode.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#SHADING_MODE android.shading.mode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#SHADING_MODE
     */
    @PublicKey
    public static final Key<int[]> SHADING_AVAILABLE_MODES =
            new Key<int[]>("android.shading.availableModes", int[].class);

    /**
     * <p>List of face detection modes for {@link CaptureRequest#STATISTICS_FACE_DETECT_MODE android.statistics.faceDetectMode} that are
     * supported by this camera device.</p>
     * <p>OFF is always supported.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#STATISTICS_FACE_DETECT_MODE android.statistics.faceDetectMode}</p>
     * <p>This key is available on all devices.</p>
     *
     * @see CaptureRequest#STATISTICS_FACE_DETECT_MODE
     */
    @PublicKey
    public static final Key<int[]> STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES =
            new Key<int[]>("android.statistics.info.availableFaceDetectModes", int[].class);

    /**
     * <p>The maximum number of simultaneously detectable
     * faces.</p>
     * <p><b>Range of valid values:</b><br>
     * 0 for cameras without available face detection; otherwise:
     * <code>&gt;=4</code> for LIMITED or FULL hwlevel devices or
     * <code>&gt;0</code> for LEGACY devices.</p>
     * <p>This key is available on all devices.</p>
     */
    @PublicKey
    public static final Key<Integer> STATISTICS_INFO_MAX_FACE_COUNT =
            new Key<Integer>("android.statistics.info.maxFaceCount", int.class);

    /**
     * <p>List of hot pixel map output modes for {@link CaptureRequest#STATISTICS_HOT_PIXEL_MAP_MODE android.statistics.hotPixelMapMode} that are
     * supported by this camera device.</p>
     * <p>If no hotpixel map output is available for this camera device, this will contain only
     * <code>false</code>.</p>
     * <p>ON is always supported on devices with the RAW capability.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#STATISTICS_HOT_PIXEL_MAP_MODE android.statistics.hotPixelMapMode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#STATISTICS_HOT_PIXEL_MAP_MODE
     */
    @PublicKey
    public static final Key<boolean[]> STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES =
            new Key<boolean[]>("android.statistics.info.availableHotPixelMapModes", boolean[].class);

    /**
     * <p>List of lens shading map output modes for {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} that
     * are supported by this camera device.</p>
     * <p>If no lens shading map output is available for this camera device, this key will
     * contain only OFF.</p>
     * <p>ON is always supported on devices with the RAW capability.
     * LEGACY mode devices will always only support OFF.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     */
    @PublicKey
    public static final Key<int[]> STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES =
            new Key<int[]>("android.statistics.info.availableLensShadingMapModes", int[].class);

    /**
     * <p>List of OIS data output modes for {@link CaptureRequest#STATISTICS_OIS_DATA_MODE android.statistics.oisDataMode} that
     * are supported by this camera device.</p>
     * <p>If no OIS data output is available for this camera device, this key will
     * contain only OFF.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#STATISTICS_OIS_DATA_MODE android.statistics.oisDataMode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#STATISTICS_OIS_DATA_MODE
     */
    @PublicKey
    public static final Key<int[]> STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES =
            new Key<int[]>("android.statistics.info.availableOisDataModes", int[].class);

    /**
     * <p>Maximum number of supported points in the
     * tonemap curve that can be used for {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}.</p>
     * <p>If the actual number of points provided by the application (in {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}*) is
     * less than this maximum, the camera device will resample the curve to its internal
     * representation, using linear interpolation.</p>
     * <p>The output curves in the result metadata may have a different number
     * of points than the input curves, and will represent the actual
     * hardware curves used as closely as possible when linearly interpolated.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#TONEMAP_CURVE
     */
    @PublicKey
    public static final Key<Integer> TONEMAP_MAX_CURVE_POINTS =
            new Key<Integer>("android.tonemap.maxCurvePoints", int.class);

    /**
     * <p>List of tonemapping modes for {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} that are supported by this camera
     * device.</p>
     * <p>Camera devices that support the MANUAL_POST_PROCESSING capability will always contain
     * at least one of below mode combinations:</p>
     * <ul>
     * <li>CONTRAST_CURVE, FAST and HIGH_QUALITY</li>
     * <li>GAMMA_VALUE, PRESET_CURVE, FAST and HIGH_QUALITY</li>
     * </ul>
     * <p>This includes all FULL level devices.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#TONEMAP_MODE
     */
    @PublicKey
    public static final Key<int[]> TONEMAP_AVAILABLE_TONE_MAP_MODES =
            new Key<int[]>("android.tonemap.availableToneMapModes", int[].class);

    /**
     * <p>A list of camera LEDs that are available on this system.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #LED_AVAILABLE_LEDS_TRANSMIT TRANSMIT}</li>
     * </ul></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @see #LED_AVAILABLE_LEDS_TRANSMIT
     * @hide
     */
    public static final Key<int[]> LED_AVAILABLE_LEDS =
            new Key<int[]>("android.led.availableLeds", int[].class);

    /**
     * <p>Generally classifies the overall set of the camera device functionality.</p>
     * <p>The supported hardware level is a high-level description of the camera device's
     * capabilities, summarizing several capabilities into one field.  Each level adds additional
     * features to the previous one, and is always a strict superset of the previous level.
     * The ordering is <code>LEGACY &lt; LIMITED &lt; FULL &lt; LEVEL_3</code>.</p>
     * <p>Starting from <code>LEVEL_3</code>, the level enumerations are guaranteed to be in increasing
     * numerical value as well. To check if a given device is at least at a given hardware level,
     * the following code snippet can be used:</p>
     * <pre><code>// Returns true if the device supports the required hardware level, or better.
     * boolean isHardwareLevelSupported(CameraCharacteristics c, int requiredLevel) {
     *     final int[] sortedHwLevels = {
     *         CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
     *         CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
     *         CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
     *         CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
     *         CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
     *     };
     *     int deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
     *     if (requiredLevel == deviceLevel) {
     *         return true;
     *     }
     *
     *     for (int sortedlevel : sortedHwLevels) {
     *         if (sortedlevel == requiredLevel) {
     *             return true;
     *         } else if (sortedlevel == deviceLevel) {
     *             return false;
     *         }
     *     }
     *     return false; // Should never reach here
     * }
     * </code></pre>
     * <p>At a high level, the levels are:</p>
     * <ul>
     * <li><code>LEGACY</code> devices operate in a backwards-compatibility mode for older
     *   Android devices, and have very limited capabilities.</li>
     * <li><code>LIMITED</code> devices represent the
     *   baseline feature set, and may also include additional capabilities that are
     *   subsets of <code>FULL</code>.</li>
     * <li><code>FULL</code> devices additionally support per-frame manual control of sensor, flash, lens and
     *   post-processing settings, and image capture at a high rate.</li>
     * <li><code>LEVEL_3</code> devices additionally support YUV reprocessing and RAW image capture, along
     *   with additional output stream configurations.</li>
     * <li><code>EXTERNAL</code> devices are similar to <code>LIMITED</code> devices with exceptions like some sensor or
     *   lens information not reorted or less stable framerates.</li>
     * </ul>
     * <p>See the individual level enums for full descriptions of the supported capabilities.  The
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} entry describes the device's capabilities at a
     * finer-grain level, if needed. In addition, many controls have their available settings or
     * ranges defined in individual entries from {@link android.hardware.camera2.CameraCharacteristics }.</p>
     * <p>Some features are not part of any particular hardware level or capability and must be
     * queried separately. These include:</p>
     * <ul>
     * <li>Calibrated timestamps ({@link CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE android.sensor.info.timestampSource} <code>==</code> REALTIME)</li>
     * <li>Precision lens control ({@link CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION android.lens.info.focusDistanceCalibration} <code>==</code> CALIBRATED)</li>
     * <li>Face detection ({@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES android.statistics.info.availableFaceDetectModes})</li>
     * <li>Optical or electrical image stabilization
     *   ({@link CameraCharacteristics#LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION android.lens.info.availableOpticalStabilization},
     *    {@link CameraCharacteristics#CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES android.control.availableVideoStabilizationModes})</li>
     * </ul>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED LIMITED}</li>
     *   <li>{@link #INFO_SUPPORTED_HARDWARE_LEVEL_FULL FULL}</li>
     *   <li>{@link #INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY LEGACY}</li>
     *   <li>{@link #INFO_SUPPORTED_HARDWARE_LEVEL_3 3}</li>
     *   <li>{@link #INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL EXTERNAL}</li>
     * </ul></p>
     * <p>This key is available on all devices.</p>
     *
     * @see CameraCharacteristics#CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
     * @see CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_FULL
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_3
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
     */
    @PublicKey
    public static final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL =
            new Key<Integer>("android.info.supportedHardwareLevel", int.class);

    /**
     * <p>A short string for manufacturer version information about the camera device, such as
     * ISP hardware, sensors, etc.</p>
     * <p>This can be used in {@link android.media.ExifInterface#TAG_IMAGE_DESCRIPTION TAG_IMAGE_DESCRIPTION}
     * in jpeg EXIF. This key may be absent if no version information is available on the
     * device.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    @PublicKey
    public static final Key<String> INFO_VERSION =
            new Key<String>("android.info.version", String.class);

    /**
     * <p>The maximum number of frames that can occur after a request
     * (different than the previous) has been submitted, and before the
     * result's state becomes synchronized.</p>
     * <p>This defines the maximum distance (in number of metadata results),
     * between the frame number of the request that has new controls to apply
     * and the frame number of the result that has all the controls applied.</p>
     * <p>In other words this acts as an upper boundary for how many frames
     * must occur before the camera device knows for a fact that the new
     * submitted camera settings have been applied in outgoing frames.</p>
     * <p><b>Units</b>: Frame counts</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #SYNC_MAX_LATENCY_PER_FRAME_CONTROL PER_FRAME_CONTROL}</li>
     *   <li>{@link #SYNC_MAX_LATENCY_UNKNOWN UNKNOWN}</li>
     * </ul></p>
     * <p><b>Available values for this device:</b><br>
     * A positive value, PER_FRAME_CONTROL, or UNKNOWN.</p>
     * <p>This key is available on all devices.</p>
     * @see #SYNC_MAX_LATENCY_PER_FRAME_CONTROL
     * @see #SYNC_MAX_LATENCY_UNKNOWN
     */
    @PublicKey
    public static final Key<Integer> SYNC_MAX_LATENCY =
            new Key<Integer>("android.sync.maxLatency", int.class);

    /**
     * <p>The maximal camera capture pipeline stall (in unit of frame count) introduced by a
     * reprocess capture request.</p>
     * <p>The key describes the maximal interference that one reprocess (input) request
     * can introduce to the camera simultaneous streaming of regular (output) capture
     * requests, including repeating requests.</p>
     * <p>When a reprocessing capture request is submitted while a camera output repeating request
     * (e.g. preview) is being served by the camera device, it may preempt the camera capture
     * pipeline for at least one frame duration so that the camera device is unable to process
     * the following capture request in time for the next sensor start of exposure boundary.
     * When this happens, the application may observe a capture time gap (longer than one frame
     * duration) between adjacent capture output frames, which usually exhibits as preview
     * glitch if the repeating request output targets include a preview surface. This key gives
     * the worst-case number of frame stall introduced by one reprocess request with any kind of
     * formats/sizes combination.</p>
     * <p>If this key reports 0, it means a reprocess request doesn't introduce any glitch to the
     * ongoing camera repeating request outputs, as if this reprocess request is never issued.</p>
     * <p>This key is supported if the camera device supports PRIVATE or YUV reprocessing (
     * i.e. {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} contains PRIVATE_REPROCESSING or
     * YUV_REPROCESSING).</p>
     * <p><b>Units</b>: Number of frames.</p>
     * <p><b>Range of valid values:</b><br>
     * &lt;= 4</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    @PublicKey
    public static final Key<Integer> REPROCESS_MAX_CAPTURE_STALL =
            new Key<Integer>("android.reprocess.maxCaptureStall", int.class);

    /**
     * <p>The available depth dataspace stream
     * configurations that this camera device supports
     * (i.e. format, width, height, output/input stream).</p>
     * <p>These are output stream configurations for use with
     * dataSpace HAL_DATASPACE_DEPTH. The configurations are
     * listed as <code>(format, width, height, input?)</code> tuples.</p>
     * <p>Only devices that support depth output for at least
     * the HAL_PIXEL_FORMAT_Y16 dense depth map may include
     * this entry.</p>
     * <p>A device that also supports the HAL_PIXEL_FORMAT_BLOB
     * sparse depth point cloud must report a single entry for
     * the format in this list as <code>(HAL_PIXEL_FORMAT_BLOB,
     * android.depth.maxDepthSamples, 1, OUTPUT)</code> in addition to
     * the entries for HAL_PIXEL_FORMAT_Y16.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfiguration[]> DEPTH_AVAILABLE_DEPTH_STREAM_CONFIGURATIONS =
            new Key<android.hardware.camera2.params.StreamConfiguration[]>("android.depth.availableDepthStreamConfigurations", android.hardware.camera2.params.StreamConfiguration[].class);

    /**
     * <p>This lists the minimum frame duration for each
     * format/size combination for depth output formats.</p>
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
     * <p><b>Units</b>: (format, width, height, ns) x n</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfigurationDuration[]> DEPTH_AVAILABLE_DEPTH_MIN_FRAME_DURATIONS =
            new Key<android.hardware.camera2.params.StreamConfigurationDuration[]>("android.depth.availableDepthMinFrameDurations", android.hardware.camera2.params.StreamConfigurationDuration[].class);

    /**
     * <p>This lists the maximum stall duration for each
     * output format/size combination for depth streams.</p>
     * <p>A stall duration is how much extra time would get added
     * to the normal minimum frame duration for a repeating request
     * that has streams with non-zero stall.</p>
     * <p>This functions similarly to
     * android.scaler.availableStallDurations for depth
     * streams.</p>
     * <p>All depth output stream formats may have a nonzero stall
     * duration.</p>
     * <p><b>Units</b>: (format, width, height, ns) x n</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @hide
     */
    public static final Key<android.hardware.camera2.params.StreamConfigurationDuration[]> DEPTH_AVAILABLE_DEPTH_STALL_DURATIONS =
            new Key<android.hardware.camera2.params.StreamConfigurationDuration[]>("android.depth.availableDepthStallDurations", android.hardware.camera2.params.StreamConfigurationDuration[].class);

    /**
     * <p>Indicates whether a capture request may target both a
     * DEPTH16 / DEPTH_POINT_CLOUD output, and normal color outputs (such as
     * YUV_420_888, JPEG, or RAW) simultaneously.</p>
     * <p>If TRUE, including both depth and color outputs in a single
     * capture request is not supported. An application must interleave color
     * and depth requests.  If FALSE, a single request can target both types
     * of output.</p>
     * <p>Typically, this restriction exists on camera devices that
     * need to emit a specific pattern or wavelength of light to
     * measure depth values, which causes the color image to be
     * corrupted during depth measurement.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    @PublicKey
    public static final Key<Boolean> DEPTH_DEPTH_IS_EXCLUSIVE =
            new Key<Boolean>("android.depth.depthIsExclusive", boolean.class);

    /**
     * <p>String containing the ids of the underlying physical cameras.</p>
     * <p>For a logical camera, this is concatenation of all underlying physical camera ids.
     * The null terminator for physical camera id must be preserved so that the whole string
     * can be tokenized using '\0' to generate list of physical camera ids.</p>
     * <p>For example, if the physical camera ids of the logical camera are "2" and "3", the
     * value of this tag will be ['2', '\0', '3', '\0'].</p>
     * <p>The number of physical camera ids must be no less than 2.</p>
     * <p><b>Units</b>: UTF-8 null-terminated string</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @hide
     */
    public static final Key<byte[]> LOGICAL_MULTI_CAMERA_PHYSICAL_IDS =
            new Key<byte[]>("android.logicalMultiCamera.physicalIds", byte[].class);

    /**
     * <p>The accuracy of frame timestamp synchronization between physical cameras</p>
     * <p>The accuracy of the frame timestamp synchronization determines the physical cameras'
     * ability to start exposure at the same time. If the sensorSyncType is CALIBRATED,
     * the physical camera sensors usually run in master-slave mode so that their shutter
     * time is synchronized. For APPROXIMATE sensorSyncType, the camera sensors usually run in
     * master-master mode, and there could be offset between their start of exposure.</p>
     * <p>In both cases, all images generated for a particular capture request still carry the same
     * timestamps, so that they can be used to look up the matching frame number and
     * onCaptureStarted callback.</p>
     * <p><b>Possible values:</b>
     * <ul>
     *   <li>{@link #LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE APPROXIMATE}</li>
     *   <li>{@link #LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED CALIBRATED}</li>
     * </ul></p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Limited capability</b> -
     * Present on all camera devices that report being at least {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED HARDWARE_LEVEL_LIMITED} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     * @see #LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE
     * @see #LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED
     */
    @PublicKey
    public static final Key<Integer> LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE =
            new Key<Integer>("android.logicalMultiCamera.sensorSyncType", int.class);

    /**
     * <p>List of distortion correction modes for {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode} that are
     * supported by this camera device.</p>
     * <p>No device is required to support this API; such devices will always list only 'OFF'.
     * All devices that support this API will list both FAST and HIGH_QUALITY.</p>
     * <p><b>Range of valid values:</b><br>
     * Any value listed in {@link CaptureRequest#DISTORTION_CORRECTION_MODE android.distortionCorrection.mode}</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#DISTORTION_CORRECTION_MODE
     */
    @PublicKey
    public static final Key<int[]> DISTORTION_CORRECTION_AVAILABLE_MODES =
            new Key<int[]>("android.distortionCorrection.availableModes", int[].class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/



}
