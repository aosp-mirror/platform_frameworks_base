/*
 * Copyright 2016 The Android Open Source Project
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

package com.android.mediaframeworktest.helpers;

import junit.framework.Assert;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCharacteristics.Key;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.android.mediaframeworktest.helpers.AssertHelpers.assertArrayContainsAnyOf;

/**
 * Helpers to get common static info out of the camera.
 *
 * <p>Avoid boiler plate by putting repetitive get/set patterns in this class.</p>
 *
 * <p>Attempt to be durable against the camera device having bad or missing metadata
 * by providing reasonable defaults and logging warnings when that happens.</p>
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.helpers.StaticMetadata
 */
public class StaticMetadata {

    private static final String TAG = "StaticMetadata";
    private static final int IGNORE_SIZE_CHECK = -1;

    private static final long SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_AT_MOST = 100000L; // 100us
    private static final long SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_AT_LEAST = 100000000; // 100ms
    private static final int SENSOR_INFO_SENSITIVITY_RANGE_MIN_AT_MOST = 100;
    private static final int SENSOR_INFO_SENSITIVITY_RANGE_MAX_AT_LEAST = 800;
    private static final int STATISTICS_INFO_MAX_FACE_COUNT_MIN_AT_LEAST = 4;
    private static final int TONEMAP_MAX_CURVE_POINTS_AT_LEAST = 64;
    private static final int CONTROL_AE_COMPENSATION_RANGE_DEFAULT_MIN = -2;
    private static final int CONTROL_AE_COMPENSATION_RANGE_DEFAULT_MAX = 2;
    private static final Rational CONTROL_AE_COMPENSATION_STEP_DEFAULT = new Rational(1, 2);
    private static final byte REQUEST_PIPELINE_MAX_DEPTH_MAX = 8;
    private static final int MAX_REPROCESS_MAX_CAPTURE_STALL = 4;

    // TODO: Consider making this work across any metadata object, not just camera characteristics
    private final CameraCharacteristics mCharacteristics;
    private final CheckLevel mLevel;
    private final CameraErrorCollector mCollector;

    // Index with android.control.aeMode
    public static final String[] AE_MODE_NAMES = new String[] {
        "AE_MODE_OFF",
        "AE_MODE_ON",
        "AE_MODE_ON_AUTO_FLASH",
        "AE_MODE_ON_ALWAYS_FLASH",
        "AE_MODE_ON_AUTO_FLASH_REDEYE"
    };

    // Index with android.control.afMode
    public static final String[] AF_MODE_NAMES = new String[] {
        "AF_MODE_OFF",
        "AF_MODE_AUTO",
        "AF_MODE_MACRO",
        "AF_MODE_CONTINUOUS_VIDEO",
        "AF_MODE_CONTINUOUS_PICTURE",
        "AF_MODE_EDOF"
    };

    // Index with android.control.aeState
    public static final String[] AE_STATE_NAMES = new String[] {
        "AE_STATE_INACTIVE",
        "AE_STATE_SEARCHING",
        "AE_STATE_CONVERGED",
        "AE_STATE_LOCKED",
        "AE_STATE_FLASH_REQUIRED",
        "AE_STATE_PRECAPTURE"
    };

    // Index with android.control.afState
    public static final String[] AF_STATE_NAMES = new String[] {
        "AF_STATE_INACTIVE",
        "AF_STATE_PASSIVE_SCAN",
        "AF_STATE_PASSIVE_FOCUSED",
        "AF_STATE_ACTIVE_SCAN",
        "AF_STATE_FOCUSED_LOCKED",
        "AF_STATE_NOT_FOCUSED_LOCKED",
        "AF_STATE_PASSIVE_UNFOCUSED"
    };

    public enum CheckLevel {
        /** Only log warnings for metadata check failures. Execution continues. */
        WARN,
        /**
         * Use ErrorCollector to collect the metadata check failures, Execution
         * continues.
         */
        COLLECT,
        /** Assert the metadata check failures. Execution aborts. */
        ASSERT
    }

    /**
     * Construct a new StaticMetadata object.
     *
     *<p> Default constructor, only log warnings for the static metadata check failures</p>
     *
     * @param characteristics static info for a camera
     * @throws IllegalArgumentException if characteristics was null
     */
    public StaticMetadata(CameraCharacteristics characteristics) {
        this(characteristics, CheckLevel.WARN, /*collector*/null);
    }

    /**
     * Construct a new StaticMetadata object with {@link CameraErrorCollector}.
     * <p>
     * When level is not {@link CheckLevel.COLLECT}, the {@link CameraErrorCollector} will be
     * ignored, otherwise, it will be used to log the check failures.
     * </p>
     *
     * @param characteristics static info for a camera
     * @param collector The {@link CameraErrorCollector} used by this StaticMetadata
     * @throws IllegalArgumentException if characteristics or collector was null.
     */
    public StaticMetadata(CameraCharacteristics characteristics, CameraErrorCollector collector) {
        this(characteristics, CheckLevel.COLLECT, collector);
    }

    /**
     * Construct a new StaticMetadata object with {@link CheckLevel} and
     * {@link CameraErrorCollector}.
     * <p>
     * When level is not {@link CheckLevel.COLLECT}, the {@link CameraErrorCollector} will be
     * ignored, otherwise, it will be used to log the check failures.
     * </p>
     *
     * @param characteristics static info for a camera
     * @param level The {@link CheckLevel} of this StaticMetadata
     * @param collector The {@link CameraErrorCollector} used by this StaticMetadata
     * @throws IllegalArgumentException if characteristics was null or level was
     *         {@link CheckLevel.COLLECT} but collector was null.
     */
    public StaticMetadata(CameraCharacteristics characteristics, CheckLevel level,
            CameraErrorCollector collector) {
        if (characteristics == null) {
            throw new IllegalArgumentException("characteristics was null");
        }
        if (level == CheckLevel.COLLECT && collector == null) {
            throw new IllegalArgumentException("collector must valid when COLLECT level is set");
        }

        mCharacteristics = characteristics;
        mLevel = level;
        mCollector = collector;
    }

    /**
     * Get the CameraCharacteristics associated with this StaticMetadata.
     *
     * @return A non-null CameraCharacteristics object
     */
    public CameraCharacteristics getCharacteristics() {
        return mCharacteristics;
    }

    /**
     * Whether or not the hardware level reported by android.info.supportedHardwareLevel
     * is {@value CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_FULL}.
     *
     * <p>If the camera device is not reporting the hardwareLevel, this
     * will cause the test to fail.</p>
     *
     * @return {@code true} if the device is {@code FULL}, {@code false} otherwise.
     */
    public boolean isHardwareLevelFull() {
        return getHardwareLevelChecked() == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL;
    }

    /**
     * Whether or not the hardware level reported by android.info.supportedHardwareLevel
     * Return the supported hardware level of the device, or fail if no value is reported.
     *
     * @return the supported hardware level as a constant defined for
     *      {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL}.
     */
    public int getHardwareLevelChecked() {
        Integer hwLevel = getValueFromKeyNonNull(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (hwLevel == null) {
            Assert.fail("No supported hardware level reported.");
        }
        return hwLevel;
    }

    /**
     * Whether or not the hardware level reported by android.info.supportedHardwareLevel
     * is {@value CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY}.
     *
     * <p>If the camera device is not reporting the hardwareLevel, this
     * will cause the test to fail.</p>
     *
     * @return {@code true} if the device is {@code LEGACY}, {@code false} otherwise.
     */
    public boolean isHardwareLevelLegacy() {
        return getHardwareLevelChecked() == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
    }

    /**
     * Whether or not the per frame control is supported by the camera device.
     *
     * @return {@code true} if per frame control is supported, {@code false} otherwise.
     */
    public boolean isPerFrameControlSupported() {
        return getSyncMaxLatency() == CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL;
    }

    /**
     * Get the maximum number of frames to wait for a request settings being applied
     *
     * @return CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN for unknown latency
     *         CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL for per frame control
     *         a positive int otherwise
     */
    public int getSyncMaxLatency() {
        Integer value = getValueFromKeyNonNull(CameraCharacteristics.SYNC_MAX_LATENCY);
        if (value == null) {
            return CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN;
        }
        return value;
    }

    /**
     * Whether or not the hardware level reported by android.info.supportedHardwareLevel
     * is {@value CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED}.
     *
     * <p>If the camera device is incorrectly reporting the hardwareLevel, this
     * will always return {@code true}.</p>
     *
     * @return {@code true} if the device is {@code LIMITED}, {@code false} otherwise.
     */
    public boolean isHardwareLevelLimited() {
        return getHardwareLevelChecked() == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED;
    }

    /**
     * Whether or not the hardware level reported by {@code android.info.supportedHardwareLevel}
     * is at least {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED}.
     *
     * <p>If the camera device is incorrectly reporting the hardwareLevel, this
     * will always return {@code false}.</p>
     *
     * @return
     *          {@code true} if the device is {@code LIMITED} or {@code FULL},
     *          {@code false} otherwise (i.e. LEGACY).
     */
    public boolean isHardwareLevelLimitedOrBetter() {
        Integer hwLevel = getValueFromKeyNonNull(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

        if (hwLevel == null) {
            return false;
        }

        // Normal. Device could be limited.
        int hwLevelInt = hwLevel;
        return hwLevelInt == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                hwLevelInt == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED;
    }

    /**
     * Get the maximum number of partial result a request can expect
     *
     * @return 1 if partial result is not supported.
     *         a integer value larger than 1 if partial result is supported.
     */
    public int getPartialResultCount() {
        Integer value = mCharacteristics.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT);
        if (value == null) {
            // Optional key. Default value is 1 if key is missing.
            return 1;
        }
        return value;
    }

    /**
     * Get the exposure time value and clamp to the range if needed.
     *
     * @param exposure Input exposure time value to check.
     * @return Exposure value in the legal range.
     */
    public long getExposureClampToRange(long exposure) {
        long minExposure = getExposureMinimumOrDefault(Long.MAX_VALUE);
        long maxExposure = getExposureMaximumOrDefault(Long.MIN_VALUE);
        if (minExposure > SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_AT_MOST) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                    String.format(
                    "Min value %d is too large, set to maximal legal value %d",
                    minExposure, SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_AT_MOST));
            minExposure = SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_AT_MOST;
        }
        if (maxExposure < SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_AT_LEAST) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                    String.format(
                    "Max value %d is too small, set to minimal legal value %d",
                    maxExposure, SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_AT_LEAST));
            maxExposure = SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_AT_LEAST;
        }

        return Math.max(minExposure, Math.min(maxExposure, exposure));
    }

    /**
     * Check if the camera device support focuser.
     *
     * @return true if camera device support focuser, false otherwise.
     */
    public boolean hasFocuser() {
        if (areKeysAvailable(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)) {
            // LEGACY devices don't have lens.info.minimumFocusDistance, so guard this query
            return (getMinimumFocusDistanceChecked() > 0);
        } else {
            // Check available AF modes
            int[] availableAfModes = mCharacteristics.get(
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

            if (availableAfModes == null) {
                return false;
            }

            // Assume that if we have an AF mode which doesn't ignore AF trigger, we have a focuser
            boolean hasFocuser = false;
            loop: for (int mode : availableAfModes) {
                switch (mode) {
                    case CameraMetadata.CONTROL_AF_MODE_AUTO:
                    case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                    case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                    case CameraMetadata.CONTROL_AF_MODE_MACRO:
                        hasFocuser = true;
                        break loop;
                }
            }

            return hasFocuser;
        }
    }

    /**
     * Check if the camera device has flash unit.
     * @return true if flash unit is available, false otherwise.
     */
    public boolean hasFlash() {
        return getFlashInfoChecked();
    }

    /**
     * Get minimum focus distance.
     *
     * @return minimum focus distance, 0 if minimum focus distance is invalid.
     */
    public float getMinimumFocusDistanceChecked() {
        Key<Float> key = CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE;
        Float minFocusDistance;

        /**
         * android.lens.info.minimumFocusDistance - required for FULL and MANUAL_SENSOR-capable
         *   devices; optional for all other devices.
         */
        if (isHardwareLevelFull() || isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            minFocusDistance = getValueFromKeyNonNull(key);
        } else {
            minFocusDistance = mCharacteristics.get(key);
        }

        if (minFocusDistance == null) {
            return 0.0f;
        }

        checkTrueForKey(key, " minFocusDistance value shouldn't be negative",
                minFocusDistance >= 0);
        if (minFocusDistance < 0) {
            minFocusDistance = 0.0f;
        }

        return minFocusDistance;
    }

    /**
     * Get focusDistanceCalibration.
     *
     * @return focusDistanceCalibration, UNCALIBRATED if value is invalid.
     */
    public int getFocusDistanceCalibrationChecked() {
        Key<Integer> key = CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION;
        Integer calibration = getValueFromKeyNonNull(key);

        if (calibration == null) {
            return CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED;
        }

        checkTrueForKey(key, " value is out of range" ,
                calibration >= CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED &&
                calibration <= CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED);

        return calibration;
    }

    /**
     * Get max AE regions and do sanity check.
     *
     * @return AE max regions supported by the camera device
     */
    public int getAeMaxRegionsChecked() {
        Integer regionCount = mCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (regionCount == null) {
            return 0;
        }
        return regionCount;
    }

    /**
     * Get max AWB regions and do sanity check.
     *
     * @return AWB max regions supported by the camera device
     */
    public int getAwbMaxRegionsChecked() {
        Integer regionCount = mCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB);
        if (regionCount == null) {
            return 0;
        }
        return regionCount;
    }

    /**
     * Get max AF regions and do sanity check.
     *
     * @return AF max regions supported by the camera device
     */
    public int getAfMaxRegionsChecked() {
        Integer regionCount = mCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        if (regionCount == null) {
            return 0;
        }
        return regionCount;
    }
    /**
     * Get the available anti-banding modes.
     *
     * @return The array contains available anti-banding modes.
     */
    public int[] getAeAvailableAntiBandingModesChecked() {
        Key<int[]> key = CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        boolean foundAuto = false;
        boolean found50Hz = false;
        boolean found60Hz = false;
        for (int mode : modes) {
            checkTrueForKey(key, "mode value " + mode + " is out if range",
                    mode >= CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF ||
                    mode <= CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO);
            if (mode == CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO) {
                foundAuto = true;
            } else if (mode == CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ) {
                found50Hz = true;
            } else if (mode == CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ) {
                found60Hz = true;
            }
        }
        // Must contain AUTO mode or one of 50/60Hz mode.
        checkTrueForKey(key, "Either AUTO mode or both 50HZ/60HZ mode should present",
                foundAuto || (found50Hz && found60Hz));

        return modes;
    }

    /**
     * Check if the antibanding OFF mode is supported.
     *
     * @return true if antibanding OFF mode is supported, false otherwise.
     */
    public boolean isAntiBandingOffModeSupported() {
        List<Integer> antiBandingModes =
                Arrays.asList(CameraTestUtils.toObject(getAeAvailableAntiBandingModesChecked()));

        return antiBandingModes.contains(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF);
    }

    public Boolean getFlashInfoChecked() {
        Key<Boolean> key = CameraCharacteristics.FLASH_INFO_AVAILABLE;
        Boolean hasFlash = getValueFromKeyNonNull(key);

        // In case the failOnKey only gives warning.
        if (hasFlash == null) {
            return false;
        }

        return hasFlash;
    }

    public int[] getAvailableTestPatternModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        int expectValue = CameraCharacteristics.SENSOR_TEST_PATTERN_MODE_OFF;
        Integer[] boxedModes = CameraTestUtils.toObject(modes);
        checkTrueForKey(key, " value must contain OFF mode",
                Arrays.asList(boxedModes).contains(expectValue));

        return modes;
    }

    /**
     * Get available thumbnail sizes and do the sanity check.
     *
     * @return The array of available thumbnail sizes
     */
    public Size[] getAvailableThumbnailSizesChecked() {
        Key<Size[]> key = CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES;
        Size[] sizes = getValueFromKeyNonNull(key);
        final List<Size> sizeList = Arrays.asList(sizes);

        // Size must contain (0, 0).
        checkTrueForKey(key, "size should contain (0, 0)", sizeList.contains(new Size(0, 0)));

        // Each size must be distinct.
        checkElementDistinct(key, sizeList);

        // Must be sorted in ascending order by area, by width if areas are same.
        List<Size> orderedSizes =
                CameraTestUtils.getAscendingOrderSizes(sizeList, /*ascending*/true);
        checkTrueForKey(key, "Sizes should be in ascending order: Original " + sizeList.toString()
                + ", Expected " + orderedSizes.toString(), orderedSizes.equals(sizeList));

        // TODO: Aspect ratio match, need wait for android.scaler.availableStreamConfigurations
        // implementation see b/12958122.

        return sizes;
    }

    /**
     * Get available focal lengths and do the sanity check.
     *
     * @return The array of available focal lengths
     */
    public float[] getAvailableFocalLengthsChecked() {
        Key<float[]> key = CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS;
        float[] focalLengths = getValueFromKeyNonNull(key);

        checkTrueForKey(key, "Array should contain at least one element", focalLengths.length >= 1);

        for (int i = 0; i < focalLengths.length; i++) {
            checkTrueForKey(key,
                    String.format("focalLength[%d] %f should be positive.", i, focalLengths[i]),
                    focalLengths[i] > 0);
        }
        checkElementDistinct(key, Arrays.asList(CameraTestUtils.toObject(focalLengths)));

        return focalLengths;
    }

    /**
     * Get available apertures and do the sanity check.
     *
     * @return The non-null array of available apertures
     */
    public float[] getAvailableAperturesChecked() {
        Key<float[]> key = CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES;
        float[] apertures = getValueFromKeyNonNull(key);

        checkTrueForKey(key, "Array should contain at least one element", apertures.length >= 1);

        for (int i = 0; i < apertures.length; i++) {
            checkTrueForKey(key,
                    String.format("apertures[%d] %f should be positive.", i, apertures[i]),
                    apertures[i] > 0);
        }
        checkElementDistinct(key, Arrays.asList(CameraTestUtils.toObject(apertures)));

        return apertures;
    }

    /**
     * Get and check the available hot pixel map modes.
     *
     * @return the available hot pixel map modes
     */
    public int[] getAvailableHotPixelModesChecked() {
        Key<int[]> key = CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        if (isHardwareLevelFull()) {
            checkTrueForKey(key, "Full-capability camera devices must support FAST mode",
                    modeList.contains(CameraMetadata.HOT_PIXEL_MODE_FAST));
        }

        if (isHardwareLevelLimitedOrBetter()) {
            // FAST and HIGH_QUALITY mode must be both present or both not present
            List<Integer> coupledModes = Arrays.asList(new Integer[] {
                    CameraMetadata.HOT_PIXEL_MODE_FAST,
                    CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
            });
            checkTrueForKey(
                    key, " FAST and HIGH_QUALITY mode must both present or both not present",
                    containsAllOrNone(modeList, coupledModes));
        }
        checkElementDistinct(key, modeList);
        checkArrayValuesInRange(key, modes, CameraMetadata.HOT_PIXEL_MODE_OFF,
                CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY);

        return modes;
    }

    /**
     * Get and check available face detection modes.
     *
     * @return The non-null array of available face detection modes
     */
    public int[] getAvailableFaceDetectModesChecked() {
        Key<int[]> key = CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        checkTrueForKey(key, "Array should contain OFF mode",
                modeList.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF));
        checkElementDistinct(key, modeList);
        checkArrayValuesInRange(key, modes, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL);

        return modes;
    }

    /**
     * Get and check max face detected count.
     *
     * @return max number of faces that can be detected
     */
    public int getMaxFaceCountChecked() {
        Key<Integer> key = CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT;
        Integer count = getValueFromKeyNonNull(key);

        if (count == null) {
            return 0;
        }

        List<Integer> faceDetectModes =
                Arrays.asList(CameraTestUtils.toObject(getAvailableFaceDetectModesChecked()));
        if (faceDetectModes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) &&
                faceDetectModes.size() == 1) {
            checkTrueForKey(key, " value must be 0 if only OFF mode is supported in "
                    + "availableFaceDetectionModes", count == 0);
        } else {
            int maxFaceCountAtLeast = STATISTICS_INFO_MAX_FACE_COUNT_MIN_AT_LEAST;

            // Legacy mode may support fewer than STATISTICS_INFO_MAX_FACE_COUNT_MIN_AT_LEAST faces.
            if (isHardwareLevelLegacy()) {
                maxFaceCountAtLeast = 1;
            }
            checkTrueForKey(key, " value must be no less than " + maxFaceCountAtLeast + " if SIMPLE"
                    + "or FULL is also supported in availableFaceDetectionModes",
                    count >= maxFaceCountAtLeast);
        }

        return count;
    }

    /**
     * Get and check the available tone map modes.
     *
     * @return the available tone map modes
     */
    public int[] getAvailableToneMapModesChecked() {
        Key<int[]> key = CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        checkTrueForKey(key, " Camera devices must always support FAST mode",
                modeList.contains(CameraMetadata.TONEMAP_MODE_FAST));
        // Qualification check for MANUAL_POSTPROCESSING capability is in
        // StaticMetadataTest#testCapabilities

        if (isHardwareLevelLimitedOrBetter()) {
            // FAST and HIGH_QUALITY mode must be both present or both not present
            List<Integer> coupledModes = Arrays.asList(new Integer[] {
                    CameraMetadata.TONEMAP_MODE_FAST,
                    CameraMetadata.TONEMAP_MODE_HIGH_QUALITY
            });
            checkTrueForKey(
                    key, " FAST and HIGH_QUALITY mode must both present or both not present",
                    containsAllOrNone(modeList, coupledModes));
        }
        checkElementDistinct(key, modeList);
        checkArrayValuesInRange(key, modes, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE,
                CameraMetadata.TONEMAP_MODE_PRESET_CURVE);

        return modes;
    }

    /**
     * Get and check max tonemap curve point.
     *
     * @return Max tonemap curve points.
     */
    public int getMaxTonemapCurvePointChecked() {
        Key<Integer> key = CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS;
        Integer count = getValueFromKeyNonNull(key);
        List<Integer> modeList =
                Arrays.asList(CameraTestUtils.toObject(getAvailableToneMapModesChecked()));
        boolean tonemapCurveOutputSupported =
                modeList.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) ||
                modeList.contains(CameraMetadata.TONEMAP_MODE_GAMMA_VALUE) ||
                modeList.contains(CameraMetadata.TONEMAP_MODE_PRESET_CURVE);

        if (count == null) {
            if (tonemapCurveOutputSupported) {
                Assert.fail("Tonemap curve output is supported but MAX_CURVE_POINTS is null");
            }
            return 0;
        }

        if (tonemapCurveOutputSupported) {
            checkTrueForKey(key, "Tonemap curve output supported camera device must support "
                    + "maxCurvePoints >= " + TONEMAP_MAX_CURVE_POINTS_AT_LEAST,
                    count >= TONEMAP_MAX_CURVE_POINTS_AT_LEAST);
        }

        return count;
    }

    /**
     * Get and check pixel array size.
     */
    public Size getPixelArraySizeChecked() {
        Key<Size> key = CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE;
        Size pixelArray = getValueFromKeyNonNull(key);
        if (pixelArray == null) {
            return new Size(0, 0);
        }

        return pixelArray;
    }

    /**
     * Get and check pre-correction active array size.
     */
    public Rect getPreCorrectedActiveArraySizeChecked() {
        Key<Rect> key = CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE;
        Rect activeArray = getValueFromKeyNonNull(key);

        if (activeArray == null) {
            return new Rect(0, 0, 0, 0);
        }

        Size pixelArraySize = getPixelArraySizeChecked();
        checkTrueForKey(key, "values left/top are invalid", activeArray.left >= 0 && activeArray.top >= 0);
        checkTrueForKey(key, "values width/height are invalid",
                activeArray.width() <= pixelArraySize.getWidth() &&
                activeArray.height() <= pixelArraySize.getHeight());

        return activeArray;
    }

    /**
     * Get and check active array size.
     */
    public Rect getActiveArraySizeChecked() {
        Key<Rect> key = CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE;
        Rect activeArray = getValueFromKeyNonNull(key);

        if (activeArray == null) {
            return new Rect(0, 0, 0, 0);
        }

        Size pixelArraySize = getPixelArraySizeChecked();
        checkTrueForKey(key, "values left/top are invalid", activeArray.left >= 0 && activeArray.top >= 0);
        checkTrueForKey(key, "values width/height are invalid",
                activeArray.width() <= pixelArraySize.getWidth() &&
                activeArray.height() <= pixelArraySize.getHeight());

        return activeArray;
    }

    /**
     * Get the dimensions to use for RAW16 buffers.
     */
    public Size getRawDimensChecked() throws Exception {
        Size[] targetCaptureSizes = getAvailableSizesForFormatChecked(ImageFormat.RAW_SENSOR,
                        StaticMetadata.StreamDirection.Output);
        Assert.assertTrue("No capture sizes available for RAW format!",
                targetCaptureSizes.length != 0);
        Rect activeArray = getPreCorrectedActiveArraySizeChecked();
        Size preCorrectionActiveArraySize =
                new Size(activeArray.width(), activeArray.height());
        Size pixelArraySize = getPixelArraySizeChecked();
        Assert.assertTrue("Missing pre-correction active array size", activeArray.width() > 0 &&
                activeArray.height() > 0);
        Assert.assertTrue("Missing pixel array size", pixelArraySize.getWidth() > 0 &&
                pixelArraySize.getHeight() > 0);
        Size[] allowedArraySizes = new Size[] { preCorrectionActiveArraySize,
                pixelArraySize };
        return assertArrayContainsAnyOf("Available sizes for RAW format" +
                " must include either the pre-corrected active array size, or the full " +
                "pixel array size", targetCaptureSizes, allowedArraySizes);
    }

    /**
     * Get the sensitivity value and clamp to the range if needed.
     *
     * @param sensitivity Input sensitivity value to check.
     * @return Sensitivity value in legal range.
     */
    public int getSensitivityClampToRange(int sensitivity) {
        int minSensitivity = getSensitivityMinimumOrDefault(Integer.MAX_VALUE);
        int maxSensitivity = getSensitivityMaximumOrDefault(Integer.MIN_VALUE);
        if (minSensitivity > SENSOR_INFO_SENSITIVITY_RANGE_MIN_AT_MOST) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                    String.format(
                    "Min value %d is too large, set to maximal legal value %d",
                    minSensitivity, SENSOR_INFO_SENSITIVITY_RANGE_MIN_AT_MOST));
            minSensitivity = SENSOR_INFO_SENSITIVITY_RANGE_MIN_AT_MOST;
        }
        if (maxSensitivity < SENSOR_INFO_SENSITIVITY_RANGE_MAX_AT_LEAST) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                    String.format(
                    "Max value %d is too small, set to minimal legal value %d",
                    maxSensitivity, SENSOR_INFO_SENSITIVITY_RANGE_MAX_AT_LEAST));
            maxSensitivity = SENSOR_INFO_SENSITIVITY_RANGE_MAX_AT_LEAST;
        }

        return Math.max(minSensitivity, Math.min(maxSensitivity, sensitivity));
    }

    /**
     * Get maxAnalogSensitivity for a camera device.
     * <p>
     * This is only available for FULL capability device, return 0 if it is unavailable.
     * </p>
     *
     * @return maxAnalogSensitivity, 0 if it is not available.
     */
    public int getMaxAnalogSensitivityChecked() {

        Key<Integer> key = CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY;
        Integer maxAnalogsensitivity = mCharacteristics.get(key);
        if (maxAnalogsensitivity == null) {
            if (isHardwareLevelFull()) {
                Assert.fail("Full device should report max analog sensitivity");
            }
            return 0;
        }

        int minSensitivity = getSensitivityMinimumOrDefault();
        int maxSensitivity = getSensitivityMaximumOrDefault();
        checkTrueForKey(key, " Max analog sensitivity " + maxAnalogsensitivity
                + " should be no larger than max sensitivity " + maxSensitivity,
                maxAnalogsensitivity <= maxSensitivity);
        checkTrueForKey(key, " Max analog sensitivity " + maxAnalogsensitivity
                + " should be larger than min sensitivity " + maxSensitivity,
                maxAnalogsensitivity > minSensitivity);

        return maxAnalogsensitivity;
    }

    /**
     * Get hyperfocalDistance and do the sanity check.
     * <p>
     * Note that, this tag is optional, will return -1 if this tag is not
     * available.
     * </p>
     *
     * @return hyperfocalDistance of this device, -1 if this tag is not available.
     */
    public float getHyperfocalDistanceChecked() {
        Key<Float> key = CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE;
        Float hyperfocalDistance = getValueFromKeyNonNull(key);
        if (hyperfocalDistance == null) {
            return -1;
        }

        if (hasFocuser()) {
            float minFocusDistance = getMinimumFocusDistanceChecked();
            checkTrueForKey(key, String.format(" hyperfocal distance %f should be in the range of"
                    + " should be in the range of (%f, %f]", hyperfocalDistance, 0.0f,
                    minFocusDistance),
                    hyperfocalDistance > 0 && hyperfocalDistance <= minFocusDistance);
        }

        return hyperfocalDistance;
    }

    /**
     * Get the minimum value for a sensitivity range from android.sensor.info.sensitivityRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead, which is the largest minimum value required to be supported
     * by all camera devices.</p>
     *
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public int getSensitivityMinimumOrDefault() {
        return getSensitivityMinimumOrDefault(SENSOR_INFO_SENSITIVITY_RANGE_MIN_AT_MOST);
    }

    /**
     * Get the minimum value for a sensitivity range from android.sensor.info.sensitivityRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead.</p>
     *
     * @param defaultValue Value to return if no legal value is available
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public int getSensitivityMinimumOrDefault(int defaultValue) {
        Range<Integer> range = getValueFromKeyNonNull(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (range == null) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                    "had no valid minimum value; using default of " + defaultValue);
            return defaultValue;
        }
        return range.getLower();
    }

    /**
     * Get the maximum value for a sensitivity range from android.sensor.info.sensitivityRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead, which is the smallest maximum value required to be supported
     * by all camera devices.</p>
     *
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public int getSensitivityMaximumOrDefault() {
        return getSensitivityMaximumOrDefault(SENSOR_INFO_SENSITIVITY_RANGE_MAX_AT_LEAST);
    }

    /**
     * Get the maximum value for a sensitivity range from android.sensor.info.sensitivityRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead.</p>
     *
     * @param defaultValue Value to return if no legal value is available
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public int getSensitivityMaximumOrDefault(int defaultValue) {
        Range<Integer> range = getValueFromKeyNonNull(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (range == null) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                    "had no valid maximum value; using default of " + defaultValue);
            return defaultValue;
        }
        return range.getUpper();
    }

    /**
     * Get the minimum value for an exposure range from android.sensor.info.exposureTimeRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead.</p>
     *
     * @param defaultValue Value to return if no legal value is available
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public long getExposureMinimumOrDefault(long defaultValue) {
        Range<Long> range = getValueFromKeyNonNull(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (range == null) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                    "had no valid minimum value; using default of " + defaultValue);
            return defaultValue;
        }
        return range.getLower();
    }

    /**
     * Get the minimum value for an exposure range from android.sensor.info.exposureTimeRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead, which is the largest minimum value required to be supported
     * by all camera devices.</p>
     *
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public long getExposureMinimumOrDefault() {
        return getExposureMinimumOrDefault(SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_AT_MOST);
    }

    /**
     * Get the maximum value for an exposure range from android.sensor.info.exposureTimeRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead.</p>
     *
     * @param defaultValue Value to return if no legal value is available
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public long getExposureMaximumOrDefault(long defaultValue) {
        Range<Long> range = getValueFromKeyNonNull(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (range == null) {
            failKeyCheck(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                    "had no valid maximum value; using default of " + defaultValue);
            return defaultValue;
        }
        return range.getUpper();
    }

    /**
     * Get the maximum value for an exposure range from android.sensor.info.exposureTimeRange.
     *
     * <p>If the camera is incorrectly reporting values, log a warning and return
     * the default value instead, which is the smallest maximum value required to be supported
     * by all camera devices.</p>
     *
     * @return The value reported by the camera device or the defaultValue otherwise.
     */
    public long getExposureMaximumOrDefault() {
        return getExposureMaximumOrDefault(SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_AT_LEAST);
    }

    /**
     * get android.control.availableModes and do the sanity check.
     *
     * @return available control modes.
     */
    public int[] getAvailableControlModesChecked() {
        Key<int[]> modesKey = CameraCharacteristics.CONTROL_AVAILABLE_MODES;
        int[] modes = getValueFromKeyNonNull(modesKey);
        if (modes == null) {
            modes = new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        checkTrueForKey(modesKey, "value is empty", !modeList.isEmpty());

        // All camera device must support AUTO
        checkTrueForKey(modesKey, "values " + modeList.toString() + " must contain AUTO mode",
                modeList.contains(CameraMetadata.CONTROL_MODE_AUTO));

        boolean isAeOffSupported =  Arrays.asList(
                CameraTestUtils.toObject(getAeAvailableModesChecked())).contains(
                        CameraMetadata.CONTROL_AE_MODE_OFF);
        boolean isAfOffSupported =  Arrays.asList(
                CameraTestUtils.toObject(getAfAvailableModesChecked())).contains(
                        CameraMetadata.CONTROL_AF_MODE_OFF);
        boolean isAwbOffSupported =  Arrays.asList(
                CameraTestUtils.toObject(getAwbAvailableModesChecked())).contains(
                        CameraMetadata.CONTROL_AWB_MODE_OFF);
        if (isAeOffSupported && isAfOffSupported && isAwbOffSupported) {
            // 3A OFF controls are supported, OFF mode must be supported here.
            checkTrueForKey(modesKey, "values " + modeList.toString() + " must contain OFF mode",
                    modeList.contains(CameraMetadata.CONTROL_MODE_OFF));
        }

        if (isSceneModeSupported()) {
            checkTrueForKey(modesKey, "values " + modeList.toString() + " must contain"
                    + " USE_SCENE_MODE",
                    modeList.contains(CameraMetadata.CONTROL_MODE_USE_SCENE_MODE));
        }

        return modes;
    }

    public boolean isSceneModeSupported() {
        List<Integer> availableSceneModes = Arrays.asList(
                CameraTestUtils.toObject(getAvailableSceneModesChecked()));

        if (availableSceneModes.isEmpty()) {
            return false;
        }

        // If sceneMode is not supported, camera device will contain single entry: DISABLED.
        return availableSceneModes.size() > 1 ||
                !availableSceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_DISABLED);
    }

    /**
     * Get aeAvailableModes and do the sanity check.
     *
     * <p>Depending on the check level this class has, for WAR or COLLECT levels,
     * If the aeMode list is invalid, return an empty mode array. The the caller doesn't
     * have to abort the execution even the aeMode list is invalid.</p>
     * @return AE available modes
     */
    public int[] getAeAvailableModesChecked() {
        Key<int[]> modesKey = CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES;
        int[] modes = getValueFromKeyNonNull(modesKey);
        if (modes == null) {
            modes = new int[0];
        }
        List<Integer> modeList = new ArrayList<Integer>();
        for (int mode : modes) {
            // Skip vendor-added modes
            if (mode <= CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE) {
                modeList.add(mode);
            }
        }
        checkTrueForKey(modesKey, "value is empty", !modeList.isEmpty());
        modes = new int[modeList.size()];
        for (int i = 0; i < modeList.size(); i++) {
            modes[i] = modeList.get(i);
        }

        checkTrueForKey(modesKey, "value is empty", !modeList.isEmpty());

        // All camera device must support ON
        checkTrueForKey(modesKey, "values " + modeList.toString() + " must contain ON mode",
                modeList.contains(CameraMetadata.CONTROL_AE_MODE_ON));

        // All camera devices with flash units support ON_AUTO_FLASH and ON_ALWAYS_FLASH
        Key<Boolean> flashKey= CameraCharacteristics.FLASH_INFO_AVAILABLE;
        Boolean hasFlash = getValueFromKeyNonNull(flashKey);
        if (hasFlash == null) {
            hasFlash = false;
        }
        if (hasFlash) {
            boolean flashModeConsistentWithFlash =
                    modeList.contains(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH) &&
                    modeList.contains(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            checkTrueForKey(modesKey,
                    "value must contain ON_AUTO_FLASH and ON_ALWAYS_FLASH and  when flash is" +
                    "available", flashModeConsistentWithFlash);
        } else {
            boolean flashModeConsistentWithoutFlash =
                    !(modeList.contains(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH) ||
                    modeList.contains(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH) ||
                    modeList.contains(CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE));
            checkTrueForKey(modesKey,
                    "value must not contain ON_AUTO_FLASH, ON_ALWAYS_FLASH and" +
                    "ON_AUTO_FLASH_REDEYE when flash is unavailable",
                    flashModeConsistentWithoutFlash);
        }

        // FULL mode camera devices always support OFF mode.
        boolean condition =
                !isHardwareLevelFull() || modeList.contains(CameraMetadata.CONTROL_AE_MODE_OFF);
        checkTrueForKey(modesKey, "Full capability device must have OFF mode", condition);

        // Boundary check.
        for (int mode : modes) {
            checkTrueForKey(modesKey, "Value " + mode + " is out of bound",
                    mode >= CameraMetadata.CONTROL_AE_MODE_OFF
                    && mode <= CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
        }

        return modes;
    }

    /**
     * Get available AWB modes and do the sanity check.
     *
     * @return array that contains available AWB modes, empty array if awbAvailableModes is
     * unavailable.
     */
    public int[] getAwbAvailableModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES;
        int[] awbModes = getValueFromKeyNonNull(key);

        if (awbModes == null) {
            return new int[0];
        }

        List<Integer> modesList = Arrays.asList(CameraTestUtils.toObject(awbModes));
        checkTrueForKey(key, " All camera devices must support AUTO mode",
                modesList.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO));
        if (isHardwareLevelFull()) {
            checkTrueForKey(key, " Full capability camera devices must support OFF mode",
                    modesList.contains(CameraMetadata.CONTROL_AWB_MODE_OFF));
        }

        return awbModes;
    }

    /**
     * Get available AF modes and do the sanity check.
     *
     * @return array that contains available AF modes, empty array if afAvailableModes is
     * unavailable.
     */
    public int[] getAfAvailableModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES;
        int[] afModes = getValueFromKeyNonNull(key);

        if (afModes == null) {
            return new int[0];
        }

        List<Integer> modesList = new ArrayList<Integer>();
        for (int afMode : afModes) {
            // Skip vendor-added AF modes
            if (afMode > CameraCharacteristics.CONTROL_AF_MODE_EDOF) continue;
            modesList.add(afMode);
        }
        afModes = new int[modesList.size()];
        for (int i = 0; i < modesList.size(); i++) {
            afModes[i] = modesList.get(i);
        }

        if (isHardwareLevelLimitedOrBetter()) {
            // Some LEGACY mode devices do not support AF OFF
            checkTrueForKey(key, " All camera devices must support OFF mode",
                    modesList.contains(CameraMetadata.CONTROL_AF_MODE_OFF));
        }
        if (hasFocuser()) {
            checkTrueForKey(key, " Camera devices that have focuser units must support AUTO mode",
                    modesList.contains(CameraMetadata.CONTROL_AF_MODE_AUTO));
        }

        return afModes;
    }

    /**
     * Get supported raw output sizes and do the check.
     *
     * @return Empty size array if raw output is not supported
     */
    public Size[] getRawOutputSizesChecked() {
        return getAvailableSizesForFormatChecked(ImageFormat.RAW_SENSOR,
                StreamDirection.Output);
    }

    /**
     * Get supported jpeg output sizes and do the check.
     *
     * @return Empty size array if jpeg output is not supported
     */
    public Size[] getJpegOutputSizesChecked() {
        return getAvailableSizesForFormatChecked(ImageFormat.JPEG,
                StreamDirection.Output);
    }

    /**
     * Used to determine the stream direction for various helpers that look up
     * format or size information.
     */
    public enum StreamDirection {
        /** Stream is used with {@link android.hardware.camera2.CameraDevice#configureOutputs} */
        Output,
        /** Stream is used with {@code CameraDevice#configureInputs} -- NOT YET PUBLIC */
        Input
    }

    /**
     * Get available formats for a given direction.
     *
     * @param direction The stream direction, input or output.
     * @return The formats of the given direction, empty array if no available format is found.
     */
    public int[] getAvailableFormats(StreamDirection direction) {
        Key<StreamConfigurationMap> key =
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
        StreamConfigurationMap config = getValueFromKeyNonNull(key);

        if (config == null) {
            return new int[0];
        }

        switch (direction) {
            case Output:
                return config.getOutputFormats();
            case Input:
                return config.getInputFormats();
            default:
                throw new IllegalArgumentException("direction must be output or input");
        }
    }

    /**
     * Get valid output formats for a given input format.
     *
     * @param inputFormat The input format used to produce the output images.
     * @return The output formats for the given input format, empty array if
     *         no available format is found.
     */
    public int[] getValidOutputFormatsForInput(int inputFormat) {
        Key<StreamConfigurationMap> key =
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
        StreamConfigurationMap config = getValueFromKeyNonNull(key);

        if (config == null) {
            return new int[0];
        }

        return config.getValidOutputFormatsForInput(inputFormat);
    }

    /**
     * Get available sizes for given format and direction.
     *
     * @param format The format for the requested size array.
     * @param direction The stream direction, input or output.
     * @return The sizes of the given format, empty array if no available size is found.
     */
    public Size[] getAvailableSizesForFormatChecked(int format, StreamDirection direction) {
        return getAvailableSizesForFormatChecked(format, direction,
                /*fastSizes*/true, /*slowSizes*/true);
    }

    /**
     * Get available sizes for given format and direction, and whether to limit to slow or fast
     * resolutions.
     *
     * @param format The format for the requested size array.
     * @param direction The stream direction, input or output.
     * @param fastSizes whether to include getOutputSizes() sizes (generally faster)
     * @param slowSizes whether to include getHighResolutionOutputSizes() sizes (generally slower)
     * @return The sizes of the given format, empty array if no available size is found.
     */
    public Size[] getAvailableSizesForFormatChecked(int format, StreamDirection direction,
            boolean fastSizes, boolean slowSizes) {
        Key<StreamConfigurationMap> key =
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
        StreamConfigurationMap config = getValueFromKeyNonNull(key);

        if (config == null) {
            return new Size[0];
        }

        Size[] sizes = null;

        switch (direction) {
            case Output:
                Size[] fastSizeList = null;
                Size[] slowSizeList = null;
                if (fastSizes) {
                    fastSizeList = config.getOutputSizes(format);
                }
                if (slowSizes) {
                    slowSizeList = config.getHighResolutionOutputSizes(format);
                }
                if (fastSizeList != null && slowSizeList != null) {
                    sizes = new Size[slowSizeList.length + fastSizeList.length];
                    System.arraycopy(fastSizeList, 0, sizes, 0, fastSizeList.length);
                    System.arraycopy(slowSizeList, 0, sizes, fastSizeList.length, slowSizeList.length);
                } else if (fastSizeList != null) {
                    sizes = fastSizeList;
                } else if (slowSizeList != null) {
                    sizes = slowSizeList;
                }
                break;
            case Input:
                sizes = config.getInputSizes(format);
                break;
            default:
                throw new IllegalArgumentException("direction must be output or input");
        }

        if (sizes == null) {
            sizes = new Size[0];
        }

        return sizes;
    }

    /**
     * Get available AE target fps ranges.
     *
     * @return Empty int array if aeAvailableTargetFpsRanges is invalid.
     */
    @SuppressWarnings("raw")
    public Range<Integer>[] getAeAvailableTargetFpsRangesChecked() {
        Key<Range<Integer>[]> key =
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES;
        Range<Integer>[] fpsRanges = getValueFromKeyNonNull(key);

        if (fpsRanges == null) {
            return new Range[0];
        }

        // Round down to 2 boundary if it is not integer times of 2, to avoid array out of bound
        // in case the above check fails.
        int fpsRangeLength = fpsRanges.length;
        int minFps, maxFps;
        long maxFrameDuration = getMaxFrameDurationChecked();
        for (int i = 0; i < fpsRangeLength; i += 1) {
            minFps = fpsRanges[i].getLower();
            maxFps = fpsRanges[i].getUpper();
            checkTrueForKey(key, " min fps must be no larger than max fps!",
                    minFps > 0 && maxFps >= minFps);
            long maxDuration = (long) (1e9 / minFps);
            checkTrueForKey(key, String.format(
                    " the frame duration %d for min fps %d must smaller than maxFrameDuration %d",
                    maxDuration, minFps, maxFrameDuration), maxDuration <= maxFrameDuration);
        }
        return fpsRanges;
    }

    public static String getAeModeName(int aeMode) {
        return (aeMode >= AE_MODE_NAMES.length) ? String.format("VENDOR_AE_MODE_%d", aeMode) :
            AE_MODE_NAMES[aeMode];
    }

    public static String getAfModeName(int afMode) {
        return (afMode >= AF_MODE_NAMES.length) ? String.format("VENDOR_AF_MODE_%d", afMode) :
            AF_MODE_NAMES[afMode];
    }

    /**
     * Get the highest supported target FPS range.
     * Prioritizes maximizing the min FPS, then the max FPS without lowering min FPS.
     */
    public Range<Integer> getAeMaxTargetFpsRange() {
        Range<Integer>[] fpsRanges = getAeAvailableTargetFpsRangesChecked();

        Range<Integer> targetRange = fpsRanges[0];
        // Assume unsorted list of target FPS ranges, so use two passes, first maximize min FPS
        for (Range<Integer> candidateRange : fpsRanges) {
            if (candidateRange.getLower() > targetRange.getLower()) {
                targetRange = candidateRange;
            }
        }
        // Then maximize max FPS while not lowering min FPS
        for (Range<Integer> candidateRange : fpsRanges) {
            if (candidateRange.getLower() >= targetRange.getLower() &&
                    candidateRange.getUpper() > targetRange.getUpper()) {
                targetRange = candidateRange;
            }
        }
        return targetRange;
    }

    /**
     * Get max frame duration.
     *
     * @return 0 if maxFrameDuration is null
     */
    public long getMaxFrameDurationChecked() {
        Key<Long> key =
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION;
        Long maxDuration = getValueFromKeyNonNull(key);

        if (maxDuration == null) {
            return 0;
        }

        return maxDuration;
    }

    /**
     * Get available minimal frame durations for a given format.
     *
     * @param format One of the format from {@link ImageFormat}.
     * @return HashMap of minimal frame durations for different sizes, empty HashMap
     *         if availableMinFrameDurations is null.
     */
    public HashMap<Size, Long> getAvailableMinFrameDurationsForFormatChecked(int format) {

        HashMap<Size, Long> minDurationMap = new HashMap<Size, Long>();

        Key<StreamConfigurationMap> key =
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
        StreamConfigurationMap config = getValueFromKeyNonNull(key);

        if (config == null) {
            return minDurationMap;
        }

        for (Size size : getAvailableSizesForFormatChecked(format,
                StreamDirection.Output)) {
            long minFrameDuration = config.getOutputMinFrameDuration(format, size);

            if (minFrameDuration != 0) {
                minDurationMap.put(new Size(size.getWidth(), size.getHeight()), minFrameDuration);
            }
        }

        return minDurationMap;
    }

    public int[] getAvailableEdgeModesChecked() {
        Key<int[]> key = CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES;
        int[] edgeModes = getValueFromKeyNonNull(key);

        if (edgeModes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(edgeModes));
        // Full device should always include OFF and FAST
        if (isHardwareLevelFull()) {
            checkTrueForKey(key, "Full device must contain OFF and FAST edge modes",
                    modeList.contains(CameraMetadata.EDGE_MODE_OFF) &&
                    modeList.contains(CameraMetadata.EDGE_MODE_FAST));
        }

        if (isHardwareLevelLimitedOrBetter()) {
            // FAST and HIGH_QUALITY mode must be both present or both not present
            List<Integer> coupledModes = Arrays.asList(new Integer[] {
                    CameraMetadata.EDGE_MODE_FAST,
                    CameraMetadata.EDGE_MODE_HIGH_QUALITY
            });
            checkTrueForKey(
                    key, " FAST and HIGH_QUALITY mode must both present or both not present",
                    containsAllOrNone(modeList, coupledModes));
        }

        return edgeModes;
    }

    public int[] getAvailableNoiseReductionModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES;
        int[] noiseReductionModes = getValueFromKeyNonNull(key);

        if (noiseReductionModes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(noiseReductionModes));
        // Full device should always include OFF and FAST
        if (isHardwareLevelFull()) {

            checkTrueForKey(key, "Full device must contain OFF and FAST noise reduction modes",
                    modeList.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) &&
                    modeList.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST));
        }

        if (isHardwareLevelLimitedOrBetter()) {
            // FAST and HIGH_QUALITY mode must be both present or both not present
            List<Integer> coupledModes = Arrays.asList(new Integer[] {
                    CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            });
            checkTrueForKey(
                    key, " FAST and HIGH_QUALITY mode must both present or both not present",
                    containsAllOrNone(modeList, coupledModes));
        }
        return noiseReductionModes;
    }

    /**
     * Get value of key android.control.aeCompensationStep and do the sanity check.
     *
     * @return default value if the value is null.
     */
    public Rational getAeCompensationStepChecked() {
        Key<Rational> key =
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP;
        Rational compensationStep = getValueFromKeyNonNull(key);

        if (compensationStep == null) {
            // Return default step.
            return CONTROL_AE_COMPENSATION_STEP_DEFAULT;
        }

        // Legacy devices don't have a minimum step requirement
        if (isHardwareLevelLimitedOrBetter()) {
            float compensationStepF =
                    (float) compensationStep.getNumerator() / compensationStep.getDenominator();
            checkTrueForKey(key, " value must be no more than 1/2", compensationStepF <= 0.5f);
        }

        return compensationStep;
    }

    /**
     * Get value of key android.control.aeCompensationRange and do the sanity check.
     *
     * @return default value if the value is null or malformed.
     */
    public Range<Integer> getAeCompensationRangeChecked() {
        Key<Range<Integer>> key =
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE;
        Range<Integer> compensationRange = getValueFromKeyNonNull(key);
        Rational compensationStep = getAeCompensationStepChecked();
        float compensationStepF = compensationStep.floatValue();
        final Range<Integer> DEFAULT_RANGE = Range.create(
                (int)(CONTROL_AE_COMPENSATION_RANGE_DEFAULT_MIN / compensationStepF),
                (int)(CONTROL_AE_COMPENSATION_RANGE_DEFAULT_MAX / compensationStepF));
        final Range<Integer> ZERO_RANGE = Range.create(0, 0);
        if (compensationRange == null) {
            return ZERO_RANGE;
        }

        // Legacy devices don't have a minimum range requirement
        if (isHardwareLevelLimitedOrBetter() && !compensationRange.equals(ZERO_RANGE)) {
            checkTrueForKey(key, " range value must be at least " + DEFAULT_RANGE
                    + ", actual " + compensationRange + ", compensation step " + compensationStep,
                   compensationRange.getLower() <= DEFAULT_RANGE.getLower() &&
                   compensationRange.getUpper() >= DEFAULT_RANGE.getUpper());
        }

        return compensationRange;
    }

    /**
     * Get availableVideoStabilizationModes and do the sanity check.
     *
     * @return available video stabilization modes, empty array if it is unavailable.
     */
    public int[] getAvailableVideoStabilizationModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        checkTrueForKey(key, " All device should support OFF mode",
                modeList.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF));
        checkArrayValuesInRange(key, modes,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);

        return modes;
    }

    public boolean isVideoStabilizationSupported() {
        Integer[] videoStabModes =
                CameraTestUtils.toObject(getAvailableVideoStabilizationModesChecked());
        return Arrays.asList(videoStabModes).contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
    }

    /**
     * Get availableOpticalStabilization and do the sanity check.
     *
     * @return available optical stabilization modes, empty array if it is unavailable.
     */
    public int[] getAvailableOpticalStabilizationChecked() {
        Key<int[]> key =
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        checkArrayValuesInRange(key, modes,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);

        return modes;
    }

    /**
     * Get the scaler's max digital zoom ({@code >= 1.0f}) ratio between crop and active array
     * @return the max zoom ratio, or {@code 1.0f} if the value is unavailable
     */
    public float getAvailableMaxDigitalZoomChecked() {
        Key<Float> key =
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM;

        Float maxZoom = getValueFromKeyNonNull(key);
        if (maxZoom == null) {
            return 1.0f;
        }

        checkTrueForKey(key, " max digital zoom should be no less than 1",
                maxZoom >= 1.0f && !Float.isNaN(maxZoom) && !Float.isInfinite(maxZoom));

        return maxZoom;
    }

    public int[] getAvailableSceneModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        // FACE_PRIORITY must be included if face detection is supported.
        if (areKeysAvailable(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) &&
                getMaxFaceCountChecked() > 0) {
            checkTrueForKey(key, " FACE_PRIORITY must be included if face detection is supported",
                    modeList.contains(CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY));
        }

        return modes;
    }

    public int[] getAvailableEffectModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        // OFF must be included.
        checkTrueForKey(key, " OFF must be included",
                modeList.contains(CameraMetadata.CONTROL_EFFECT_MODE_OFF));

        return modes;
    }

    /**
     * Get and check the available color aberration modes
     *
     * @return the available color aberration modes
     */
    public int[] getAvailableColorAberrationModesChecked() {
        Key<int[]> key =
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES;
        int[] modes = getValueFromKeyNonNull(key);

        if (modes == null) {
            return new int[0];
        }

        List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
        checkTrueForKey(key, " Camera devices must always support either OFF or FAST mode",
                modeList.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF) ||
                modeList.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST));

        if (isHardwareLevelLimitedOrBetter()) {
            // FAST and HIGH_QUALITY mode must be both present or both not present
            List<Integer> coupledModes = Arrays.asList(new Integer[] {
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            });
            checkTrueForKey(
                    key, " FAST and HIGH_QUALITY mode must both present or both not present",
                    containsAllOrNone(modeList, coupledModes));
        }
        checkElementDistinct(key, modeList);
        checkArrayValuesInRange(key, modes,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);

        return modes;
    }

    /**
     * Get max pipeline depth and do the sanity check.
     *
     * @return max pipeline depth, default value if it is not available.
     */
    public byte getPipelineMaxDepthChecked() {
        Key<Byte> key =
                CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH;
        Byte maxDepth = getValueFromKeyNonNull(key);

        if (maxDepth == null) {
            return REQUEST_PIPELINE_MAX_DEPTH_MAX;
        }

        checkTrueForKey(key, " max pipeline depth should be no larger than "
                + REQUEST_PIPELINE_MAX_DEPTH_MAX, maxDepth <= REQUEST_PIPELINE_MAX_DEPTH_MAX);

        return maxDepth;
    }

    /**
     * Get available lens shading modes.
     */
     public int[] getAvailableLensShadingModesChecked() {
         Key<int[]> key =
                 CameraCharacteristics.SHADING_AVAILABLE_MODES;
         int[] modes = getValueFromKeyNonNull(key);
         if (modes == null) {
             return new int[0];
         }

         List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));
         // FAST must be included.
         checkTrueForKey(key, " FAST must be included",
                 modeList.contains(CameraMetadata.SHADING_MODE_FAST));

         if (isCapabilitySupported(
                 CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
             checkTrueForKey(key, " OFF must be included for MANUAL_POST_PROCESSING devices",
                     modeList.contains(CameraMetadata.SHADING_MODE_OFF));
         }
         return modes;
     }

     /**
      * Get available lens shading map modes.
      */
      public int[] getAvailableLensShadingMapModesChecked() {
          Key<int[]> key =
                  CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES;
          int[] modes = getValueFromKeyNonNull(key);
          if (modes == null) {
              return new int[0];
          }

          List<Integer> modeList = Arrays.asList(CameraTestUtils.toObject(modes));

          if (isCapabilitySupported(
                  CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
              checkTrueForKey(key, " ON must be included for RAW capability devices",
                      modeList.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON));
          }
          return modes;
      }


    /**
     * Get available capabilities and do the sanity check.
     *
     * @return reported available capabilities list, empty list if the value is unavailable.
     */
    public List<Integer> getAvailableCapabilitiesChecked() {
        Key<int[]> key =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES;
        int[] availableCaps = getValueFromKeyNonNull(key);
        List<Integer> capList;

        if (availableCaps == null) {
            return new ArrayList<Integer>();
        }

        capList = Arrays.asList(CameraTestUtils.toObject(availableCaps));
        return capList;
    }

    /**
     * Determine whether the current device supports a capability or not.
     *
     * @param capability (non-negative)
     *
     * @return {@code true} if the capability is supported, {@code false} otherwise.
     *
     * @throws IllegalArgumentException if {@code capability} was negative
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     */
    public boolean isCapabilitySupported(int capability) {
        if (capability < 0) {
            throw new IllegalArgumentException("capability must be non-negative");
        }

        List<Integer> availableCapabilities = getAvailableCapabilitiesChecked();

        return availableCapabilities.contains(capability);
    }

    /**
     * Determine whether or not all the {@code keys} are available characteristics keys
     * (as in {@link CameraCharacteristics#getKeys}.
     *
     * <p>If this returns {@code true}, then querying for this key from a characteristics
     * object will always return a non-{@code null} value.</p>
     *
     * @param keys collection of camera characteristics keys
     * @return whether or not all characteristics keys are available
     */
    public final boolean areCharacteristicsKeysAvailable(
            Collection<Key<?>> keys) {
        return mCharacteristics.getKeys().containsAll(keys);
    }

    /**
     * Determine whether or not all the {@code keys} are available result keys
     * (as in {@link CameraCharacteristics#getAvailableCaptureResultKeys}.
     *
     * <p>If this returns {@code true}, then querying for this key from a result
     * object will almost always return a non-{@code null} value.</p>
     *
     * <p>In some cases (e.g. lens shading map), the request must have additional settings
     * configured in order for the key to correspond to a value.</p>
     *
     * @param keys collection of capture result keys
     * @return whether or not all result keys are available
     */
    public final boolean areResultKeysAvailable(Collection<CaptureResult.Key<?>> keys) {
        return mCharacteristics.getAvailableCaptureResultKeys().containsAll(keys);
    }

    /**
     * Determine whether or not all the {@code keys} are available request keys
     * (as in {@link CameraCharacteristics#getAvailableCaptureRequestKeys}.
     *
     * <p>If this returns {@code true}, then setting this key in the request builder
     * may have some effect (and if it's {@code false}, then the camera device will
     * definitely ignore it).</p>
     *
     * <p>In some cases (e.g. manual control of exposure), other keys must be also be set
     * in order for a key to take effect (e.g. control.mode set to OFF).</p>
     *
     * @param keys collection of capture request keys
     * @return whether or not all result keys are available
     */
    public final boolean areRequestKeysAvailable(Collection<CaptureRequest.Key<?>> keys) {
        return mCharacteristics.getAvailableCaptureRequestKeys().containsAll(keys);
    }

    /**
     * Determine whether or not all the {@code keys} are available characteristics keys
     * (as in {@link CameraCharacteristics#getKeys}.
     *
     * <p>If this returns {@code true}, then querying for this key from a characteristics
     * object will always return a non-{@code null} value.</p>
     *
     * @param keys one or more camera characteristic keys
     * @return whether or not all characteristics keys are available
     */
    @SafeVarargs
    public final boolean areKeysAvailable(Key<?>... keys) {
        return areCharacteristicsKeysAvailable(Arrays.asList(keys));
    }

    /**
     * Determine whether or not all the {@code keys} are available result keys
     * (as in {@link CameraCharacteristics#getAvailableCaptureResultKeys}.
     *
     * <p>If this returns {@code true}, then querying for this key from a result
     * object will almost always return a non-{@code null} value.</p>
     *
     * <p>In some cases (e.g. lens shading map), the request must have additional settings
     * configured in order for the key to correspond to a value.</p>
     *
     * @param keys one or more capture result keys
     * @return whether or not all result keys are available
     */
    @SafeVarargs
    public final boolean areKeysAvailable(CaptureResult.Key<?>... keys) {
        return areResultKeysAvailable(Arrays.asList(keys));
    }

    /**
     * Determine whether or not all the {@code keys} are available request keys
     * (as in {@link CameraCharacteristics#getAvailableCaptureRequestKeys}.
     *
     * <p>If this returns {@code true}, then setting this key in the request builder
     * may have some effect (and if it's {@code false}, then the camera device will
     * definitely ignore it).</p>
     *
     * <p>In some cases (e.g. manual control of exposure), other keys must be also be set
     * in order for a key to take effect (e.g. control.mode set to OFF).</p>
     *
     * @param keys one or more capture request keys
     * @return whether or not all result keys are available
     */
    @SafeVarargs
    public final boolean areKeysAvailable(CaptureRequest.Key<?>... keys) {
        return areRequestKeysAvailable(Arrays.asList(keys));
    }

    /*
     * Determine if camera device support AE lock control
     *
     * @return {@code true} if AE lock control is supported
     */
    public boolean isAeLockSupported() {
        return getValueFromKeyNonNull(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
    }

    /*
     * Determine if camera device support AWB lock control
     *
     * @return {@code true} if AWB lock control is supported
     */
    public boolean isAwbLockSupported() {
        return getValueFromKeyNonNull(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
    }


    /*
     * Determine if camera device support manual lens shading map control
     *
     * @return {@code true} if manual lens shading map control is supported
     */
    public boolean isManualLensShadingMapSupported() {
        return areKeysAvailable(CaptureRequest.SHADING_MODE);
    }

    /**
     * Determine if camera device support manual color correction control
     *
     * @return {@code true} if manual color correction control is supported
     */
    public boolean isColorCorrectionSupported() {
        return areKeysAvailable(CaptureRequest.COLOR_CORRECTION_MODE);
    }

    /**
     * Determine if camera device support manual tone mapping control
     *
     * @return {@code true} if manual tone mapping control is supported
     */
    public boolean isManualToneMapSupported() {
        return areKeysAvailable(CaptureRequest.TONEMAP_MODE);
    }

    /**
     * Determine if camera device support manual color aberration control
     *
     * @return {@code true} if manual color aberration control is supported
     */
    public boolean isManualColorAberrationControlSupported() {
        return areKeysAvailable(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE);
    }

    /**
     * Determine if camera device support edge mode control
     *
     * @return {@code true} if edge mode control is supported
     */
    public boolean isEdgeModeControlSupported() {
        return areKeysAvailable(CaptureRequest.EDGE_MODE);
    }

    /**
     * Determine if camera device support hot pixel mode control
     *
     * @return {@code true} if hot pixel mode control is supported
     */
    public boolean isHotPixelMapModeControlSupported() {
        return areKeysAvailable(CaptureRequest.HOT_PIXEL_MODE);
    }

    /**
     * Determine if camera device support noise reduction mode control
     *
     * @return {@code true} if noise reduction mode control is supported
     */
    public boolean isNoiseReductionModeControlSupported() {
        return areKeysAvailable(CaptureRequest.NOISE_REDUCTION_MODE);
    }

    /**
     * Get max number of output raw streams and do the basic sanity check.
     *
     * @return reported max number of raw output stream
     */
    public int getMaxNumOutputStreamsRawChecked() {
        Integer maxNumStreams =
                getValueFromKeyNonNull(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW);
        if (maxNumStreams == null)
            return 0;
        return maxNumStreams;
    }

    /**
     * Get max number of output processed streams and do the basic sanity check.
     *
     * @return reported max number of processed output stream
     */
    public int getMaxNumOutputStreamsProcessedChecked() {
        Integer maxNumStreams =
                getValueFromKeyNonNull(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC);
        if (maxNumStreams == null)
            return 0;
        return maxNumStreams;
    }

    /**
     * Get max number of output stalling processed streams and do the basic sanity check.
     *
     * @return reported max number of stalling processed output stream
     */
    public int getMaxNumOutputStreamsProcessedStallChecked() {
        Integer maxNumStreams =
                getValueFromKeyNonNull(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING);
        if (maxNumStreams == null)
            return 0;
        return maxNumStreams;
    }

    /**
     * Get lens facing and do the sanity check
     * @return lens facing, return default value (BACK) if value is unavailable.
     */
    public int getLensFacingChecked() {
        Key<Integer> key =
                CameraCharacteristics.LENS_FACING;
        Integer facing = getValueFromKeyNonNull(key);

        if (facing == null) {
            return CameraCharacteristics.LENS_FACING_BACK;
        }

        checkTrueForKey(key, " value is out of range ",
                facing >= CameraCharacteristics.LENS_FACING_FRONT &&
                facing <= CameraCharacteristics.LENS_FACING_BACK);
        return facing;
    }

    /**
     * Get maxCaptureStall frames or default value (if value doesn't exist)
     * @return maxCaptureStall frames or default value.
     */
    public int getMaxCaptureStallOrDefault() {
        Key<Integer> key =
                CameraCharacteristics.REPROCESS_MAX_CAPTURE_STALL;
        Integer value = getValueFromKeyNonNull(key);

        if (value == null) {
            return MAX_REPROCESS_MAX_CAPTURE_STALL;
        }

        checkTrueForKey(key, " value is out of range ",
                value >= 0 &&
                value <= MAX_REPROCESS_MAX_CAPTURE_STALL);

        return value;
    }

    /**
     * Get the scaler's cropping type (center only or freeform)
     * @return cropping type, return default value (CENTER_ONLY) if value is unavailable
     */
    public int getScalerCroppingTypeChecked() {
        Key<Integer> key =
                CameraCharacteristics.SCALER_CROPPING_TYPE;
        Integer value = getValueFromKeyNonNull(key);

        if (value == null) {
            return CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY;
        }

        checkTrueForKey(key, " value is out of range ",
                value >= CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY &&
                value <= CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM);

        return value;
    }

    /**
     * Check if the constrained high speed video is supported by the camera device.
     * The high speed FPS ranges and sizes are sanitized in
     * ExtendedCameraCharacteristicsTest#testConstrainedHighSpeedCapability.
     *
     * @return true if the constrained high speed video is supported, false otherwise.
     */
    public boolean isConstrainedHighSpeedVideoSupported() {
        List<Integer> availableCapabilities = getAvailableCapabilitiesChecked();
        return (availableCapabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO));
    }

    /**
     * Check if high speed video is supported (HIGH_SPEED_VIDEO scene mode is
     * supported, supported high speed fps ranges and sizes are valid).
     *
     * @return true if high speed video is supported.
     */
    public boolean isHighSpeedVideoSupported() {
        List<Integer> sceneModes =
                Arrays.asList(CameraTestUtils.toObject(getAvailableSceneModesChecked()));
        if (sceneModes.contains(CameraCharacteristics.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)) {
            StreamConfigurationMap config =
                    getValueFromKeyNonNull(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (config == null) {
                return false;
            }
            Size[] availableSizes = config.getHighSpeedVideoSizes();
            if (availableSizes.length == 0) {
                return false;
            }

            for (Size size : availableSizes) {
                Range<Integer>[] availableFpsRanges = config.getHighSpeedVideoFpsRangesFor(size);
                if (availableFpsRanges.length == 0) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if depth output is supported, based on the depth capability
     */
    public boolean isDepthOutputSupported() {
        return isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT);
    }

    /**
     * Check if standard outputs (PRIVATE, YUV, JPEG) outputs are supported, based on the
     * backwards-compatible capability
     */
    public boolean isColorOutputSupported() {
        return isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE);
    }

    /**
     * Check if optical black regions key is supported.
     */
    public boolean isOpticalBlackRegionSupported() {
        return areKeysAvailable(CameraCharacteristics.SENSOR_OPTICAL_BLACK_REGIONS);
    }

    /**
     * Check if the dynamic black level is supported.
     *
     * <p>
     * Note that: This also indicates if the white level is supported, as dynamic black and white
     * level must be all supported or none of them is supported.
     * </p>
     */
    public boolean isDynamicBlackLevelSupported() {
        return areKeysAvailable(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
    }

    /**
     * Get the value in index for a fixed-size array from a given key.
     *
     * <p>If the camera device is incorrectly reporting values, log a warning and return
     * the default value instead.</p>
     *
     * @param key Key to fetch
     * @param defaultValue Default value to return if camera device uses invalid values
     * @param name Human-readable name for the array index (logging only)
     * @param index Array index of the subelement
     * @param size Expected fixed size of the array
     *
     * @return The value reported by the camera device, or the defaultValue otherwise.
     */
    private <T> T getArrayElementOrDefault(Key<?> key, T defaultValue, String name, int index,
            int size) {
        T elementValue = getArrayElementCheckRangeNonNull(
                key,
                index,
                size);

        if (elementValue == null) {
            failKeyCheck(key,
                    "had no valid " + name + " value; using default of " + defaultValue);
            elementValue = defaultValue;
        }

        return elementValue;
    }

    /**
     * Fetch an array sub-element from an array value given by a key.
     *
     * <p>
     * Prints a warning if the sub-element was null.
     * </p>
     *
     * <p>Use for variable-size arrays since this does not check the array size.</p>
     *
     * @param key Metadata key to look up
     * @param element A non-negative index value.
     * @return The array sub-element, or null if the checking failed.
     */
    private <T> T getArrayElementNonNull(Key<?> key, int element) {
        return getArrayElementCheckRangeNonNull(key, element, IGNORE_SIZE_CHECK);
    }

    /**
     * Fetch an array sub-element from an array value given by a key.
     *
     * <p>
     * Prints a warning if the array size does not match the size, or if the sub-element was null.
     * </p>
     *
     * @param key Metadata key to look up
     * @param element The index in [0,size)
     * @param size A positive size value or otherwise {@value #IGNORE_SIZE_CHECK}
     * @return The array sub-element, or null if the checking failed.
     */
    private <T> T getArrayElementCheckRangeNonNull(Key<?> key, int element, int size) {
        Object array = getValueFromKeyNonNull(key);

        if (array == null) {
            // Warning already printed
            return null;
        }

        if (size != IGNORE_SIZE_CHECK) {
            int actualLength = Array.getLength(array);
            if (actualLength != size) {
                failKeyCheck(key,
                        String.format("had the wrong number of elements (%d), expected (%d)",
                                actualLength, size));
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        T val = (T) Array.get(array, element);

        if (val == null) {
            failKeyCheck(key, "had a null element at index" + element);
            return null;
        }

        return val;
    }

    /**
     * Gets the key, logging warnings for null values.
     */
    public <T> T getValueFromKeyNonNull(Key<T> key) {
        if (key == null) {
            throw new IllegalArgumentException("key was null");
        }

        T value = mCharacteristics.get(key);

        if (value == null) {
            failKeyCheck(key, "was null");
        }

        return value;
    }

    private void checkArrayValuesInRange(Key<int[]> key, int[] array, int min, int max) {
        for (int value : array) {
            checkTrueForKey(key, String.format(" value is out of range [%d, %d]", min, max),
                    value <= max && value >= min);
        }
    }

    private void checkArrayValuesInRange(Key<byte[]> key, byte[] array, byte min, byte max) {
        for (byte value : array) {
            checkTrueForKey(key, String.format(" value is out of range [%d, %d]", min, max),
                    value <= max && value >= min);
        }
    }

    /**
     * Check the uniqueness of the values in a list.
     *
     * @param key The key to be checked
     * @param list The list contains the value of the key
     */
    private <U, T> void checkElementDistinct(Key<U> key, List<T> list) {
        // Each size must be distinct.
        Set<T> sizeSet = new HashSet<T>(list);
        checkTrueForKey(key, "Each size must be distinct", sizeSet.size() == list.size());
    }

    private <T> void checkTrueForKey(Key<T> key, String message, boolean condition) {
        if (!condition) {
            failKeyCheck(key, message);
        }
    }

    /* Helper function to check if the coupled modes are either all present or all non-present */
    private <T> boolean containsAllOrNone(Collection<T> observedModes, Collection<T> coupledModes) {
        if (observedModes.containsAll(coupledModes)) {
            return true;
        }
        for (T mode : coupledModes) {
            if (observedModes.contains(mode)) {
                return false;
            }
        }
        return true;
    }

    private <T> void failKeyCheck(Key<T> key, String message) {
        // TODO: Consider only warning once per key/message combination if it's too spammy.
        // TODO: Consider offering other options such as throwing an assertion exception
        String failureCause = String.format("The static info key '%s' %s", key.getName(), message);
        switch (mLevel) {
            case WARN:
                Log.w(TAG, failureCause);
                break;
            case COLLECT:
                mCollector.addMessage(failureCause);
                break;
            case ASSERT:
                Assert.fail(failureCause);
            default:
                throw new UnsupportedOperationException("Unhandled level " + mLevel);
        }
    }
}
