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

package android.hardware.camera2.legacy;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.utils.ArrayUtils;
import android.hardware.camera2.utils.ListUtils;
import android.hardware.camera2.utils.ParamsUtils;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.android.internal.util.Preconditions.*;
import static android.hardware.camera2.CameraCharacteristics.*;
import static android.hardware.camera2.legacy.ParameterUtils.*;

/**
 * Provide legacy-specific implementations of camera2 metadata for legacy devices, such as the
 * camera characteristics.
 */
@SuppressWarnings("deprecation")
public class LegacyMetadataMapper {
    private static final String TAG = "LegacyMetadataMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final long NS_PER_MS = 1000000;

    // from graphics.h
    public static final int HAL_PIXEL_FORMAT_RGBA_8888 = PixelFormat.RGBA_8888;
    public static final int HAL_PIXEL_FORMAT_BGRA_8888 = 0x5;
    public static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;
    public static final int HAL_PIXEL_FORMAT_BLOB = 0x21;

    // for metadata
    private static final float LENS_INFO_MINIMUM_FOCUS_DISTANCE_FIXED_FOCUS = 0.0f;

    private static final int REQUEST_MAX_NUM_OUTPUT_STREAMS_COUNT_RAW = 0; // no raw support
    private static final int REQUEST_MAX_NUM_OUTPUT_STREAMS_COUNT_PROC = 3; // preview, video, cb
    private static final int REQUEST_MAX_NUM_OUTPUT_STREAMS_COUNT_PROC_STALL = 1; // 1 jpeg only
    private static final int REQUEST_MAX_NUM_INPUT_STREAMS_COUNT = 0; // no reprocessing

    /** Assume 3 HAL1 stages: Exposure, Read-out, Post-Processing */
    private static final int REQUEST_PIPELINE_MAX_DEPTH_HAL1 = 3;
    /** Assume 3 shim stages: Preview input, Split output, Format conversion for output */
    private static final int REQUEST_PIPELINE_MAX_DEPTH_OURS = 3;
    /* TODO: Update above maxDepth values once we do more performance measurements */

    // For approximating JPEG stall durations
    private static final long APPROXIMATE_CAPTURE_DELAY_MS = 200; // 200 milliseconds
    private static final long APPROXIMATE_SENSOR_AREA_PX = (1 << 23); // 8 megapixels
    private static final long APPROXIMATE_JPEG_ENCODE_TIME_MS = 600; // 600 milliseconds

    static final int UNKNOWN_MODE = -1;

    // Maximum difference between a preview size aspect ratio and a jpeg size aspect ratio
    private static final float PREVIEW_ASPECT_RATIO_TOLERANCE = 0.01f;

    /*
     * Development hijinks: Lie about not supporting certain capabilities
     *
     * - Unblock some CTS tests from running whose main intent is not the metadata itself
     *
     * TODO: Remove these constants and strip out any code that previously relied on them
     * being set to true.
     */
    static final boolean LIE_ABOUT_AE_STATE = false;
    static final boolean LIE_ABOUT_AE_MAX_REGIONS = false;
    static final boolean LIE_ABOUT_AF = false;
    static final boolean LIE_ABOUT_AF_MAX_REGIONS = false;
    static final boolean LIE_ABOUT_AWB_STATE = false;
    static final boolean LIE_ABOUT_AWB = false;


    /**
     * Create characteristics for a legacy device by mapping the {@code parameters}
     * and {@code info}
     *
     * @param parameters A non-{@code null} parameters set
     * @param info Camera info with camera facing direction and angle of orientation
     *
     * @return static camera characteristics for a camera device
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public static CameraCharacteristics createCharacteristics(Camera.Parameters parameters,
            CameraInfo info) {
        checkNotNull(parameters, "parameters must not be null");
        checkNotNull(info, "info must not be null");

        String paramStr = parameters.flatten();
        android.hardware.CameraInfo outerInfo = new android.hardware.CameraInfo();
        outerInfo.info = info;

        return createCharacteristics(paramStr, outerInfo);
    }

    /**
     * Create characteristics for a legacy device by mapping the {@code parameters}
     * and {@code info}
     *
     * @param parameters A string parseable by {@link Camera.Parameters#unflatten}
     * @param info Camera info with camera facing direction and angle of orientation
     * @return static camera characteristics for a camera device
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public static CameraCharacteristics createCharacteristics(String parameters,
            android.hardware.CameraInfo info) {
        checkNotNull(parameters, "parameters must not be null");
        checkNotNull(info, "info must not be null");
        checkNotNull(info.info, "info.info must not be null");

        CameraMetadataNative m = new CameraMetadataNative();

        mapCharacteristicsFromInfo(m, info.info);

        Camera.Parameters params = Camera.getEmptyParameters();
        params.unflatten(parameters);
        mapCharacteristicsFromParameters(m, params);

        if (VERBOSE) {
            Log.v(TAG, "createCharacteristics metadata:");
            Log.v(TAG, "--------------------------------------------------- (start)");
            m.dumpToLog();
            Log.v(TAG, "--------------------------------------------------- (end)");
        }

        return new CameraCharacteristics(m);
    }

    private static void mapCharacteristicsFromInfo(CameraMetadataNative m, CameraInfo i) {
        m.set(LENS_FACING, i.facing == CameraInfo.CAMERA_FACING_BACK ?
                LENS_FACING_BACK : LENS_FACING_FRONT);
        m.set(SENSOR_ORIENTATION, i.orientation);
    }

    private static void mapCharacteristicsFromParameters(CameraMetadataNative m,
            Camera.Parameters p) {

        /*
         * colorCorrection.*
         */
        m.set(COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                new int[] { COLOR_CORRECTION_ABERRATION_MODE_FAST });
        /*
         * control.ae*
         */
        mapControlAe(m, p);
        /*
         * control.af*
         */
        mapControlAf(m, p);
        /*
         * control.awb*
         */
        mapControlAwb(m, p);
        /*
         * control.*
         * - Anything that doesn't have a set of related fields
         */
        mapControlOther(m, p);
        /*
         * lens.*
         */
        mapLens(m, p);
        /*
         * flash.*
         */
        mapFlash(m, p);
        /*
         * jpeg.*
         */
        mapJpeg(m, p);

        /*
         * noiseReduction.*
         */
        m.set(NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                new int[] { NOISE_REDUCTION_MODE_FAST });

        /*
         * scaler.*
         */
        mapScaler(m, p);

        /*
         * sensor.*
         */
        mapSensor(m, p);

        /*
         * statistics.*
         */
        mapStatistics(m, p);

        /*
         * sync.*
         */
        mapSync(m, p);

        /*
         * info.supportedHardwareLevel
         */
        m.set(INFO_SUPPORTED_HARDWARE_LEVEL, INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);

        /*
         * scaler.availableStream*, scaler.available*Durations, sensor.info.maxFrameDuration
         */
        mapScalerStreamConfigs(m, p);

        // Order matters below: Put this last so that we can read the metadata set previously

        /*
         * request.*
         */
        mapRequest(m, p);

    }

    private static void mapScalerStreamConfigs(CameraMetadataNative m, Camera.Parameters p) {

        ArrayList<StreamConfiguration> availableStreamConfigs = new ArrayList<>();
        /*
         * Implementation-defined (preview, recording, etc) -> use camera1 preview sizes
         * YUV_420_888 cpu callbacks -> use camera1 preview sizes
         * Other preview callbacks (CPU) -> use camera1 preview sizes
         * JPEG still capture -> use camera1 still capture sizes
         *
         * Use platform-internal format constants here, since StreamConfigurationMap does the
         * remapping to public format constants.
         */
        List<Camera.Size> previewSizes = p.getSupportedPreviewSizes();
        List<Camera.Size> jpegSizes = p.getSupportedPictureSizes();
        /*
         * Work-around for b/17589233:
         * - Some HALs's largest preview size aspect ratio does not match the largest JPEG size AR
         * - This causes a large amount of problems with focus/metering because it's relative to
         *   preview, making the difference between the JPEG and preview viewport inaccessible
         * - This boils down to metering or focusing areas being "arbitrarily" cropped
         *   in the capture result.
         * - Work-around the HAL limitations by removing all of the largest preview sizes
         *   until we get one with the same aspect ratio as the jpeg size.
         */
        {
            SizeAreaComparator areaComparator = new SizeAreaComparator();

            // Sort preview to min->max
            Collections.sort(previewSizes, areaComparator);

            Camera.Size maxJpegSize = SizeAreaComparator.findLargestByArea(jpegSizes);
            float jpegAspectRatio = maxJpegSize.width * 1.0f / maxJpegSize.height;

            if (VERBOSE) {
                Log.v(TAG, String.format("mapScalerStreamConfigs - largest JPEG area %dx%d, AR=%f",
                        maxJpegSize.width, maxJpegSize.height, jpegAspectRatio));
            }

            // Now remove preview sizes from the end (largest->smallest) until aspect ratio matches
            while (!previewSizes.isEmpty()) {
                int index = previewSizes.size() - 1; // max is always at the end
                Camera.Size size = previewSizes.get(index);

                float previewAspectRatio = size.width * 1.0f / size.height;

                if (Math.abs(jpegAspectRatio - previewAspectRatio) >=
                        PREVIEW_ASPECT_RATIO_TOLERANCE) {
                    previewSizes.remove(index); // Assume removing from end is O(1)

                    if (VERBOSE) {
                        Log.v(TAG, String.format(
                                "mapScalerStreamConfigs - removed preview size %dx%d, AR=%f "
                                        + "was not the same",
                                size.width, size.height, previewAspectRatio));
                    }
                } else {
                    break;
                }
            }

            if (previewSizes.isEmpty()) {
                // Fall-back to the original faulty behavior, but at least work
                Log.w(TAG, "mapScalerStreamConfigs - failed to find any preview size matching " +
                        "JPEG aspect ratio " + jpegAspectRatio);
                previewSizes = p.getSupportedPreviewSizes();
            }

            // Sort again, this time in descending order max->min
            Collections.sort(previewSizes, Collections.reverseOrder(areaComparator));
        }

        appendStreamConfig(availableStreamConfigs,
                HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED, previewSizes);
        appendStreamConfig(availableStreamConfigs,
                ImageFormat.YUV_420_888, previewSizes);
        for (int format : p.getSupportedPreviewFormats()) {
            if (ImageFormat.isPublicFormat(format) && format != ImageFormat.NV21) {
                appendStreamConfig(availableStreamConfigs, format, previewSizes);
            } else if (VERBOSE) {
                /*
                 *  Do not add any formats unknown to us
                 * (since it would fail runtime checks in StreamConfigurationMap)
                 */
                Log.v(TAG,
                        String.format("mapStreamConfigs - Skipping format %x", format));
            }
        }

        appendStreamConfig(availableStreamConfigs,
                HAL_PIXEL_FORMAT_BLOB, p.getSupportedPictureSizes());
        /*
         * scaler.availableStreamConfigurations
         */
        m.set(SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                availableStreamConfigs.toArray(new StreamConfiguration[0]));

        /*
         * scaler.availableMinFrameDurations
         */
        // No frame durations available
        m.set(SCALER_AVAILABLE_MIN_FRAME_DURATIONS, new StreamConfigurationDuration[0]);

        StreamConfigurationDuration[] jpegStalls =
                new StreamConfigurationDuration[jpegSizes.size()];
        int i = 0;
        long longestStallDuration = -1;
        for (Camera.Size s : jpegSizes) {
            long stallDuration =  calculateJpegStallDuration(s);
            jpegStalls[i++] = new StreamConfigurationDuration(HAL_PIXEL_FORMAT_BLOB, s.width,
                    s.height, stallDuration);
            if (longestStallDuration < stallDuration) {
                longestStallDuration = stallDuration;
            }
        }
        /*
         * scaler.availableStallDurations
         */
        // Set stall durations for jpeg, other formats use default stall duration
        m.set(SCALER_AVAILABLE_STALL_DURATIONS, jpegStalls);

        /*
         * sensor.info.maxFrameDuration
         */
        m.set(SENSOR_INFO_MAX_FRAME_DURATION, longestStallDuration);
    }

    @SuppressWarnings({"unchecked"})
    private static void mapControlAe(CameraMetadataNative m, Camera.Parameters p) {
        /*
         * control.aeAvailableAntiBandingModes
         */
        List<String> antiBandingModes = p.getSupportedAntibanding();
        if (antiBandingModes != null && antiBandingModes.size() > 0) { // antibanding is optional
            int[] modes = new int[antiBandingModes.size()];
            int j = 0;
            for (String mode : antiBandingModes) {
                int convertedMode = convertAntiBandingMode(mode);
                if (VERBOSE && convertedMode == -1) {
                    Log.v(TAG, "Antibanding mode " + ((mode == null) ? "NULL" : mode) +
                            " not supported, skipping...");
                } else {
                    modes[j++] = convertedMode;
                }
            }
            m.set(CONTROL_AE_AVAILABLE_ANTIBANDING_MODES, Arrays.copyOf(modes, j));
        } else {
            m.set(CONTROL_AE_AVAILABLE_ANTIBANDING_MODES, new int[0]);
        }

        /*
         * control.aeAvailableTargetFpsRanges
         */
        {
            List<int[]> fpsRanges = p.getSupportedPreviewFpsRange();
            if (fpsRanges == null) {
                throw new AssertionError("Supported FPS ranges cannot be null.");
            }
            int rangesSize = fpsRanges.size();
            if (rangesSize <= 0) {
                throw new AssertionError("At least one FPS range must be supported.");
            }
            Range<Integer>[] ranges = new Range[rangesSize];
            int i = 0;
            for (int[] r : fpsRanges) {
                ranges[i++] = Range.create(r[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                        r[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
            }
            m.set(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, ranges);
        }

        /*
         * control.aeAvailableModes
         */
        {
            List<String> flashModes = p.getSupportedFlashModes();

            String[] flashModeStrings = new String[] {
                    Camera.Parameters.FLASH_MODE_OFF,
                    Camera.Parameters.FLASH_MODE_AUTO,
                    Camera.Parameters.FLASH_MODE_ON,
                    Camera.Parameters.FLASH_MODE_RED_EYE,
                    // Map these manually
                    Camera.Parameters.FLASH_MODE_TORCH,
            };
            int[] flashModeInts = new int[] {
                    CONTROL_AE_MODE_ON,
                    CONTROL_AE_MODE_ON_AUTO_FLASH,
                    CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                    CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
            };
            int[] aeAvail = ArrayUtils.convertStringListToIntArray(
                    flashModes, flashModeStrings, flashModeInts);

            // No flash control -> AE is always on
            if (aeAvail == null || aeAvail.length == 0) {
                aeAvail = new int[] {
                        CONTROL_AE_MODE_ON
                };
            }

            // Note that AE_MODE_OFF is never available.
            m.set(CONTROL_AE_AVAILABLE_MODES, aeAvail);
        }

        /*
         * control.aeCompensationRanges
         */
        {
            int min = p.getMinExposureCompensation();
            int max = p.getMaxExposureCompensation();

            m.set(CONTROL_AE_COMPENSATION_RANGE, Range.create(min, max));
        }

        /*
         * control.aeCompensationStep
         */
        {
            float step = p.getExposureCompensationStep();

            m.set(CONTROL_AE_COMPENSATION_STEP, ParamsUtils.createRational(step));
        }
    }


    @SuppressWarnings({"unchecked"})
    private static void mapControlAf(CameraMetadataNative m, Camera.Parameters p) {
        /*
         * control.afAvailableModes
         */
        {
            List<String> focusModes = p.getSupportedFocusModes();

            String[] focusModeStrings = new String[] {
                    Camera.Parameters.FOCUS_MODE_AUTO,
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
                    Camera.Parameters.FOCUS_MODE_EDOF,
                    Camera.Parameters.FOCUS_MODE_INFINITY,
                    Camera.Parameters.FOCUS_MODE_MACRO,
                    Camera.Parameters.FOCUS_MODE_FIXED,
            };

            int[] focusModeInts = new int[] {
                    CONTROL_AF_MODE_AUTO,
                    CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CONTROL_AF_MODE_EDOF,
                    CONTROL_AF_MODE_OFF,
                    CONTROL_AF_MODE_MACRO,
                    CONTROL_AF_MODE_OFF
            };

            List<Integer> afAvail = ArrayUtils.convertStringListToIntList(
                    focusModes, focusModeStrings, focusModeInts);

            // No AF modes supported? That's unpossible!
            if (afAvail == null || afAvail.size() == 0) {
                Log.w(TAG, "No AF modes supported (HAL bug); defaulting to AF_MODE_OFF only");
                afAvail = new ArrayList<Integer>(/*capacity*/1);
                afAvail.add(CONTROL_AF_MODE_OFF);
            }

            m.set(CONTROL_AF_AVAILABLE_MODES, ArrayUtils.toIntArray(afAvail));

            if (VERBOSE) {
                Log.v(TAG, "mapControlAf - control.afAvailableModes set to " +
                        ListUtils.listToString(afAvail));
            }
        }
    }

    private static void mapControlAwb(CameraMetadataNative m, Camera.Parameters p) {
        /*
         * control.awbAvailableModes
         */

        {
            List<String> wbModes = p.getSupportedWhiteBalance();

            String[] wbModeStrings = new String[] {
                    Camera.Parameters.WHITE_BALANCE_AUTO                    ,
                    Camera.Parameters.WHITE_BALANCE_INCANDESCENT            ,
                    Camera.Parameters.WHITE_BALANCE_FLUORESCENT             ,
                    Camera.Parameters.WHITE_BALANCE_WARM_FLUORESCENT        ,
                    Camera.Parameters.WHITE_BALANCE_DAYLIGHT                ,
                    Camera.Parameters.WHITE_BALANCE_CLOUDY_DAYLIGHT         ,
                    Camera.Parameters.WHITE_BALANCE_TWILIGHT                ,
                    Camera.Parameters.WHITE_BALANCE_SHADE                   ,
            };

            int[] wbModeInts = new int[] {
                    CONTROL_AWB_MODE_AUTO,
                    CONTROL_AWB_MODE_INCANDESCENT            ,
                    CONTROL_AWB_MODE_FLUORESCENT             ,
                    CONTROL_AWB_MODE_WARM_FLUORESCENT        ,
                    CONTROL_AWB_MODE_DAYLIGHT                ,
                    CONTROL_AWB_MODE_CLOUDY_DAYLIGHT         ,
                    CONTROL_AWB_MODE_TWILIGHT                ,
                    CONTROL_AWB_MODE_SHADE                   ,
                    // Note that CONTROL_AWB_MODE_OFF is unsupported
            };

            List<Integer> awbAvail = ArrayUtils.convertStringListToIntList(
                        wbModes, wbModeStrings, wbModeInts);

            // No AWB modes supported? That's unpossible!
            if (awbAvail == null || awbAvail.size() == 0) {
                Log.w(TAG, "No AWB modes supported (HAL bug); defaulting to AWB_MODE_AUTO only");
                awbAvail = new ArrayList<Integer>(/*capacity*/1);
                awbAvail.add(CONTROL_AWB_MODE_AUTO);
            }

            m.set(CONTROL_AWB_AVAILABLE_MODES, ArrayUtils.toIntArray(awbAvail));

            if (VERBOSE) {
                Log.v(TAG, "mapControlAwb - control.awbAvailableModes set to " +
                        ListUtils.listToString(awbAvail));
            }
        }
    }

    private static void mapControlOther(CameraMetadataNative m, Camera.Parameters p) {
        /*
         * android.control.availableVideoStabilizationModes
         */
        {
            int stabModes[] = p.isVideoStabilizationSupported() ?
                    new int[] { CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                                CONTROL_VIDEO_STABILIZATION_MODE_ON } :
                    new int[] { CONTROL_VIDEO_STABILIZATION_MODE_OFF };

            m.set(CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, stabModes);
        }

        /*
         * android.control.maxRegions
         */
        final int AE = 0, AWB = 1, AF = 2;

        int[] maxRegions = new int[3];
        maxRegions[AE] = p.getMaxNumMeteringAreas();
        maxRegions[AWB] = 0; // AWB regions not supported in API1
        maxRegions[AF] = p.getMaxNumFocusAreas();

        if (LIE_ABOUT_AE_MAX_REGIONS) {
            maxRegions[AE] = 0;
        }
        if (LIE_ABOUT_AF_MAX_REGIONS) {
            maxRegions[AF] = 0;
        }

        m.set(CONTROL_MAX_REGIONS, maxRegions);

        /*
         * android.control.availableEffects
         */
        List<String> effectModes = p.getSupportedColorEffects();
        int[] supportedEffectModes = (effectModes == null) ? new int[0] :
                ArrayUtils.convertStringListToIntArray(effectModes, sLegacyEffectMode,
                        sEffectModes);
        m.set(CONTROL_AVAILABLE_EFFECTS, supportedEffectModes);

        /*
         * android.control.availableSceneModes
         */
        List<String> sceneModes = p.getSupportedSceneModes();
        List<Integer> supportedSceneModes =
                ArrayUtils.convertStringListToIntList(sceneModes, sLegacySceneModes, sSceneModes);
        if (supportedSceneModes == null) { // camera1 doesn't support scene mode settings
            supportedSceneModes = new ArrayList<Integer>();
            supportedSceneModes.add(CONTROL_SCENE_MODE_DISABLED); // disabled is always available
        }
        if (p.getMaxNumDetectedFaces() > 0) { // always supports FACE_PRIORITY when face detecting
            supportedSceneModes.add(CONTROL_SCENE_MODE_FACE_PRIORITY);
        }
        m.set(CONTROL_AVAILABLE_SCENE_MODES, ArrayUtils.toIntArray(supportedSceneModes));
    }

    private static void mapLens(CameraMetadataNative m, Camera.Parameters p) {
        /*
         *  We can tell if the lens is fixed focus;
         *  but if it's not, we can't tell the minimum focus distance, so leave it null then.
         */
        if (VERBOSE) {
            Log.v(TAG, "mapLens - focus-mode='" + p.getFocusMode() + "'");
        }

        if (Camera.Parameters.FOCUS_MODE_FIXED.equals(p.getFocusMode())) {
            /*
             * lens.info.minimumFocusDistance
             */
            m.set(LENS_INFO_MINIMUM_FOCUS_DISTANCE, LENS_INFO_MINIMUM_FOCUS_DISTANCE_FIXED_FOCUS);

            if (VERBOSE) {
                Log.v(TAG, "mapLens - lens.info.minimumFocusDistance = 0");
            }
        } else {
            if (VERBOSE) {
                Log.v(TAG, "mapLens - lens.info.minimumFocusDistance is unknown");
            }
        }

        float[] focalLengths = new float[] { p.getFocalLength() };
        m.set(LENS_INFO_AVAILABLE_FOCAL_LENGTHS, focalLengths);
    }

    private static void mapFlash(CameraMetadataNative m, Camera.Parameters p) {
        boolean flashAvailable = false;
        List<String> supportedFlashModes = p.getSupportedFlashModes();

        if (supportedFlashModes != null) {
            // If only 'OFF' is available, we don't really have flash support
            flashAvailable = !ListUtils.listElementsEqualTo(
                    supportedFlashModes, Camera.Parameters.FLASH_MODE_OFF);
        }

        /*
         * flash.info.available
         */
        m.set(FLASH_INFO_AVAILABLE, flashAvailable);
    }

    private static void mapJpeg(CameraMetadataNative m, Camera.Parameters p) {
        List<Camera.Size> thumbnailSizes = p.getSupportedJpegThumbnailSizes();

        if (thumbnailSizes != null) {
            Size[] sizes = convertSizeListToArray(thumbnailSizes);
            Arrays.sort(sizes, new android.hardware.camera2.utils.SizeAreaComparator());
            m.set(JPEG_AVAILABLE_THUMBNAIL_SIZES, sizes);
        }
    }

    private static void mapRequest(CameraMetadataNative m, Parameters p) {
        /*
         * request.availableCapabilities
         */
        int[] capabilities = { REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE };
        m.set(REQUEST_AVAILABLE_CAPABILITIES, capabilities);

        /*
         * request.availableCharacteristicsKeys
         */
        {
            // TODO: check if the underlying key is supported before listing a key as available

            // Note: We only list public keys. Native HALs should list ALL keys regardless of visibility.

            Key<?> availableKeys[] = new Key<?>[] {
                    CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES     ,
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES          ,
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES                      ,
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES          ,
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE                   ,
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP                    ,
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES                      ,
                    CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS                       ,
                    CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES                   ,
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES     ,
                    CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES                     ,
                    CameraCharacteristics.CONTROL_MAX_REGIONS                             ,
                    CameraCharacteristics.FLASH_INFO_AVAILABLE                            ,
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL                   ,
                    CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES                  ,
                    CameraCharacteristics.LENS_FACING                                     ,
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS               ,
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES ,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES                  ,
                    CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_STREAMS                  ,
                    CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT                    ,
                    CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH                      ,
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM               ,
//                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP                 ,
                    CameraCharacteristics.SCALER_CROPPING_TYPE                            ,
                    CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES             ,
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE                   ,
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE                       ,
                    CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE                    ,
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE                    ,
                    CameraCharacteristics.SENSOR_ORIENTATION                              ,
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES     ,
                    CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT                  ,
                    CameraCharacteristics.SYNC_MAX_LATENCY                                ,
            };
            List<Key<?>> characteristicsKeys = new ArrayList<>(Arrays.asList(availableKeys));

            /*
             * Add the conditional keys
             */
            if (m.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE) != null) {
                characteristicsKeys.add(LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            }

            m.set(REQUEST_AVAILABLE_CHARACTERISTICS_KEYS,
                    getTagsForKeys(characteristicsKeys.toArray(new Key<?>[0])));
        }

        /*
         * request.availableRequestKeys
         */
        {
            CaptureRequest.Key<?> defaultAvailableKeys[] = new CaptureRequest.Key<?>[] {
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    CaptureRequest.CONTROL_AE_LOCK,
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AWB_LOCK,
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_EFFECT_MODE,
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.FLASH_MODE,
                    CaptureRequest.JPEG_GPS_COORDINATES,
                    CaptureRequest.JPEG_GPS_PROCESSING_METHOD,
                    CaptureRequest.JPEG_GPS_TIMESTAMP,
                    CaptureRequest.JPEG_ORIENTATION,
                    CaptureRequest.JPEG_QUALITY,
                    CaptureRequest.JPEG_THUMBNAIL_QUALITY,
                    CaptureRequest.JPEG_THUMBNAIL_SIZE,
                    CaptureRequest.LENS_FOCAL_LENGTH,
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.SCALER_CROP_REGION,
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
            };
            ArrayList<CaptureRequest.Key<?>> availableKeys =
                    new ArrayList<CaptureRequest.Key<?>>(Arrays.asList(defaultAvailableKeys));

            if (p.getMaxNumMeteringAreas() > 0) {
                availableKeys.add(CaptureRequest.CONTROL_AE_REGIONS);
            }
            if (p.getMaxNumFocusAreas() > 0) {
                availableKeys.add(CaptureRequest.CONTROL_AF_REGIONS);
            }

            CaptureRequest.Key<?> availableRequestKeys[] =
                    new CaptureRequest.Key<?>[availableKeys.size()];
            availableKeys.toArray(availableRequestKeys);
            m.set(REQUEST_AVAILABLE_REQUEST_KEYS, getTagsForKeys(availableRequestKeys));
        }

        /*
         * request.availableResultKeys
         */
        {
            CaptureResult.Key<?> defaultAvailableKeys[] = new CaptureResult.Key<?>[] {
                    CaptureResult.COLOR_CORRECTION_ABERRATION_MODE                 ,
                    CaptureResult.CONTROL_AE_ANTIBANDING_MODE                      ,
                    CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION                 ,
                    CaptureResult.CONTROL_AE_LOCK                                  ,
                    CaptureResult.CONTROL_AE_MODE                                  ,
                    CaptureResult.CONTROL_AF_MODE                                  ,
                    CaptureResult.CONTROL_AF_STATE                                 ,
                    CaptureResult.CONTROL_AWB_MODE                                 ,
                    CaptureResult.CONTROL_AWB_LOCK                                 ,
                    CaptureResult.CONTROL_MODE                                     ,
                    CaptureResult.FLASH_MODE                                       ,
                    CaptureResult.JPEG_GPS_COORDINATES                             ,
                    CaptureResult.JPEG_GPS_PROCESSING_METHOD                       ,
                    CaptureResult.JPEG_GPS_TIMESTAMP                               ,
                    CaptureResult.JPEG_ORIENTATION                                 ,
                    CaptureResult.JPEG_QUALITY                                     ,
                    CaptureResult.JPEG_THUMBNAIL_QUALITY                           ,
                    CaptureResult.LENS_FOCAL_LENGTH                                ,
                    CaptureResult.NOISE_REDUCTION_MODE                             ,
                    CaptureResult.REQUEST_PIPELINE_DEPTH                           ,
                    CaptureResult.SCALER_CROP_REGION                               ,
                    CaptureResult.SENSOR_TIMESTAMP                                 ,
                    CaptureResult.STATISTICS_FACE_DETECT_MODE                      ,
//                    CaptureResult.STATISTICS_FACES                                 ,
            };
            List<CaptureResult.Key<?>> availableKeys =
                    new ArrayList<CaptureResult.Key<?>>(Arrays.asList(defaultAvailableKeys));

            if (p.getMaxNumMeteringAreas() > 0) {
                availableKeys.add(CaptureResult.CONTROL_AE_REGIONS);
            }
            if (p.getMaxNumFocusAreas() > 0) {
                availableKeys.add(CaptureResult.CONTROL_AF_REGIONS);
            }

            CaptureResult.Key<?> availableResultKeys[] =
                    new CaptureResult.Key<?>[availableKeys.size()];
            availableKeys.toArray(availableResultKeys);
            m.set(REQUEST_AVAILABLE_RESULT_KEYS, getTagsForKeys(availableResultKeys));
        }

        /*
         * request.maxNumOutputStreams
         */
        int[] outputStreams = {
                /* RAW */
                REQUEST_MAX_NUM_OUTPUT_STREAMS_COUNT_RAW,
                /* Processed & Not-Stalling */
                REQUEST_MAX_NUM_OUTPUT_STREAMS_COUNT_PROC,
                /* Processed & Stalling */
                REQUEST_MAX_NUM_OUTPUT_STREAMS_COUNT_PROC_STALL,
        };
        m.set(REQUEST_MAX_NUM_OUTPUT_STREAMS, outputStreams);

        /*
         * request.maxNumInputStreams
         */
        m.set(REQUEST_MAX_NUM_INPUT_STREAMS, REQUEST_MAX_NUM_INPUT_STREAMS_COUNT);

        /*
         * request.partialResultCount
         */
        m.set(REQUEST_PARTIAL_RESULT_COUNT, 1); // No partial results supported

        /*
         * request.pipelineMaxDepth
         */
        m.set(REQUEST_PIPELINE_MAX_DEPTH,
                (byte)(REQUEST_PIPELINE_MAX_DEPTH_HAL1 + REQUEST_PIPELINE_MAX_DEPTH_OURS));
    }

    private static void mapScaler(CameraMetadataNative m, Parameters p) {
        /*
         * scaler.availableMaxDigitalZoom
         */
        m.set(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, ParameterUtils.getMaxZoomRatio(p));

        /*
         * scaler.croppingType = CENTER_ONLY
         */
        m.set(SCALER_CROPPING_TYPE, SCALER_CROPPING_TYPE_CENTER_ONLY);
    }

    private static void mapSensor(CameraMetadataNative m, Parameters p) {
        // Use the largest jpeg size (by area) for both active array and pixel array
        Size largestJpegSize = getLargestSupportedJpegSizeByArea(p);
        /*
         * sensor.info.activeArraySize
         */
        {
            Rect activeArrayRect = ParamsUtils.createRect(largestJpegSize);
            m.set(SENSOR_INFO_ACTIVE_ARRAY_SIZE, activeArrayRect);
        }

        /*
         * sensor.availableTestPatternModes
         */
        {
            // Only "OFF" test pattern mode is available
            m.set(SENSOR_AVAILABLE_TEST_PATTERN_MODES, new int[] { SENSOR_TEST_PATTERN_MODE_OFF });
        }

        /*
         * sensor.info.pixelArraySize
         */
        m.set(SENSOR_INFO_PIXEL_ARRAY_SIZE, largestJpegSize);

        /*
         * sensor.info.physicalSize
         */
        {
            /*
             * Assume focal length is at infinity focus and that the lens is rectilinear.
             */
            float focalLength = p.getFocalLength(); // in mm
            double angleHor = p.getHorizontalViewAngle() * Math.PI / 180; // to radians
            double angleVer = p.getVerticalViewAngle() * Math.PI / 180; // to radians

            float height = (float)Math.abs(2 * focalLength * Math.tan(angleVer / 2));
            float width = (float)Math.abs(2 * focalLength * Math.tan(angleHor / 2));

            m.set(SENSOR_INFO_PHYSICAL_SIZE, new SizeF(width, height)); // in mm
        }

        /*
         * sensor.info.timestampSource
         */
        {
            m.set(SENSOR_INFO_TIMESTAMP_SOURCE, SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN);
        }
    }

    private static void mapStatistics(CameraMetadataNative m, Parameters p) {
        /*
         * statistics.info.availableFaceDetectModes
         */
        int[] fdModes;

        if (p.getMaxNumDetectedFaces() > 0) {
            fdModes = new int[] {
                STATISTICS_FACE_DETECT_MODE_OFF,
                STATISTICS_FACE_DETECT_MODE_SIMPLE
                // FULL is never-listed, since we have no way to query it statically
            };
        } else {
            fdModes = new int[] {
                STATISTICS_FACE_DETECT_MODE_OFF
            };
        }
        m.set(STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, fdModes);

        /*
         * statistics.info.maxFaceCount
         */
        m.set(STATISTICS_INFO_MAX_FACE_COUNT, p.getMaxNumDetectedFaces());
    }

    private static void mapSync(CameraMetadataNative m, Parameters p) {
        /*
         * sync.maxLatency
         */
        m.set(SYNC_MAX_LATENCY, SYNC_MAX_LATENCY_UNKNOWN);
    }

    private static void appendStreamConfig(
            ArrayList<StreamConfiguration> configs, int format, List<Camera.Size> sizes) {
        for (Camera.Size size : sizes) {
            StreamConfiguration config =
                    new StreamConfiguration(format, size.width, size.height, /*input*/false);
            configs.add(config);
        }
    }

    private final static String[] sLegacySceneModes = {
        Parameters.SCENE_MODE_AUTO,
        Parameters.SCENE_MODE_ACTION,
        Parameters.SCENE_MODE_PORTRAIT,
        Parameters.SCENE_MODE_LANDSCAPE,
        Parameters.SCENE_MODE_NIGHT,
        Parameters.SCENE_MODE_NIGHT_PORTRAIT,
        Parameters.SCENE_MODE_THEATRE,
        Parameters.SCENE_MODE_BEACH,
        Parameters.SCENE_MODE_SNOW,
        Parameters.SCENE_MODE_SUNSET,
        Parameters.SCENE_MODE_STEADYPHOTO,
        Parameters.SCENE_MODE_FIREWORKS,
        Parameters.SCENE_MODE_SPORTS,
        Parameters.SCENE_MODE_PARTY,
        Parameters.SCENE_MODE_CANDLELIGHT,
        Parameters.SCENE_MODE_BARCODE,
        Parameters.SCENE_MODE_HDR,
    };

    private final static int[] sSceneModes = {
        CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED,
        CameraCharacteristics.CONTROL_SCENE_MODE_ACTION,
        CameraCharacteristics.CONTROL_SCENE_MODE_PORTRAIT,
        CameraCharacteristics.CONTROL_SCENE_MODE_LANDSCAPE,
        CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT,
        CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT_PORTRAIT,
        CameraCharacteristics.CONTROL_SCENE_MODE_THEATRE,
        CameraCharacteristics.CONTROL_SCENE_MODE_BEACH,
        CameraCharacteristics.CONTROL_SCENE_MODE_SNOW,
        CameraCharacteristics.CONTROL_SCENE_MODE_SUNSET,
        CameraCharacteristics.CONTROL_SCENE_MODE_STEADYPHOTO,
        CameraCharacteristics.CONTROL_SCENE_MODE_FIREWORKS,
        CameraCharacteristics.CONTROL_SCENE_MODE_SPORTS,
        CameraCharacteristics.CONTROL_SCENE_MODE_PARTY,
        CameraCharacteristics.CONTROL_SCENE_MODE_CANDLELIGHT,
        CameraCharacteristics.CONTROL_SCENE_MODE_BARCODE,
        CameraCharacteristics.CONTROL_SCENE_MODE_HDR,
    };

    static int convertSceneModeFromLegacy(String mode) {
        if (mode == null) {
            return CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED;
        }
        int index = ArrayUtils.getArrayIndex(sLegacySceneModes, mode);
        if (index < 0) {
            return UNKNOWN_MODE;
        }
        return sSceneModes[index];
    }

    static String convertSceneModeToLegacy(int mode) {
        if (mode == CONTROL_SCENE_MODE_FACE_PRIORITY) {
            // OK: Let LegacyFaceDetectMapper handle turning face detection on/off
            return Parameters.SCENE_MODE_AUTO;
        }

        int index = ArrayUtils.getArrayIndex(sSceneModes, mode);
        if (index < 0) {
            return null;
        }
        return sLegacySceneModes[index];
    }

    private final static String[] sLegacyEffectMode = {
        Parameters.EFFECT_NONE,
        Parameters.EFFECT_MONO,
        Parameters.EFFECT_NEGATIVE,
        Parameters.EFFECT_SOLARIZE,
        Parameters.EFFECT_SEPIA,
        Parameters.EFFECT_POSTERIZE,
        Parameters.EFFECT_WHITEBOARD,
        Parameters.EFFECT_BLACKBOARD,
        Parameters.EFFECT_AQUA,
    };

    private final static int[] sEffectModes = {
        CameraCharacteristics.CONTROL_EFFECT_MODE_OFF,
        CameraCharacteristics.CONTROL_EFFECT_MODE_MONO,
        CameraCharacteristics.CONTROL_EFFECT_MODE_NEGATIVE,
        CameraCharacteristics.CONTROL_EFFECT_MODE_SOLARIZE,
        CameraCharacteristics.CONTROL_EFFECT_MODE_SEPIA,
        CameraCharacteristics.CONTROL_EFFECT_MODE_POSTERIZE,
        CameraCharacteristics.CONTROL_EFFECT_MODE_WHITEBOARD,
        CameraCharacteristics.CONTROL_EFFECT_MODE_BLACKBOARD,
        CameraCharacteristics.CONTROL_EFFECT_MODE_AQUA,
    };

    static int convertEffectModeFromLegacy(String mode) {
        if (mode == null) {
            return CameraCharacteristics.CONTROL_EFFECT_MODE_OFF;
        }
        int index = ArrayUtils.getArrayIndex(sLegacyEffectMode, mode);
        if (index < 0) {
            return UNKNOWN_MODE;
        }
        return sEffectModes[index];
    }

    static String convertEffectModeToLegacy(int mode) {
        int index = ArrayUtils.getArrayIndex(sEffectModes, mode);
        if (index < 0) {
            return null;
        }
        return sLegacyEffectMode[index];
    }

    /**
     * Convert the ae antibanding mode from api1 into api2.
     *
     * @param mode the api1 mode, {@code null} is allowed and will return {@code -1}.
     *
     * @return The api2 value, or {@code -1} by default if conversion failed
     */
    private static int convertAntiBandingMode(String mode) {
        if (mode == null) {
            return -1;
        }

        switch (mode) {
            case Camera.Parameters.ANTIBANDING_OFF: {
                return CONTROL_AE_ANTIBANDING_MODE_OFF;
            }
            case Camera.Parameters.ANTIBANDING_50HZ: {
                return CONTROL_AE_ANTIBANDING_MODE_50HZ;
            }
            case Camera.Parameters.ANTIBANDING_60HZ: {
                return CONTROL_AE_ANTIBANDING_MODE_60HZ;
            }
            case Camera.Parameters.ANTIBANDING_AUTO: {
                return CONTROL_AE_ANTIBANDING_MODE_AUTO;
            }
            default: {
                Log.w(TAG, "convertAntiBandingMode - Unknown antibanding mode " + mode);
                return -1;
            }
        }
    }

    /**
     * Convert the ae antibanding mode from api1 into api2.
     *
     * @param mode the api1 mode, {@code null} is allowed and will return {@code MODE_OFF}.
     *
     * @return The api2 value, or {@code MODE_OFF} by default if conversion failed
     */
    static int convertAntiBandingModeOrDefault(String mode) {
        int antiBandingMode = convertAntiBandingMode(mode);
        if (antiBandingMode == -1) {
            return CONTROL_AE_ANTIBANDING_MODE_OFF;
        }

        return antiBandingMode;
    }

    private static int[] convertAeFpsRangeToLegacy(Range<Integer> fpsRange) {
        int[] legacyFps = new int[2];
        legacyFps[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] = fpsRange.getLower();
        legacyFps[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] = fpsRange.getUpper();
        return legacyFps;
    }

    /**
     * Return the stall duration for a given output jpeg size in nanoseconds.
     *
     * <p>An 8mp image is chosen to have a stall duration of 0.8 seconds.</p>
     */
    private static long calculateJpegStallDuration(Camera.Size size) {
        long baseDuration = APPROXIMATE_CAPTURE_DELAY_MS * NS_PER_MS; // 200ms for capture
        long area = size.width * (long) size.height;
        long stallPerArea = APPROXIMATE_JPEG_ENCODE_TIME_MS * NS_PER_MS /
                APPROXIMATE_SENSOR_AREA_PX; // 600ms stall for 8mp
        return baseDuration + area * stallPerArea;
    }

    /**
     * Set the legacy parameters using the {@link LegacyRequest legacy request}.
     *
     * <p>The legacy request's parameters are changed as a side effect of calling this
     * method.</p>
     *
     * @param request a non-{@code null} legacy request
     */
    public static void convertRequestMetadata(LegacyRequest request) {
        LegacyRequestMapper.convertRequestMetadata(request);
    }

    private static final int[] sAllowedTemplates = {
            CameraDevice.TEMPLATE_PREVIEW,
            CameraDevice.TEMPLATE_STILL_CAPTURE,
            CameraDevice.TEMPLATE_RECORD,
            // Disallowed templates in legacy mode:
            // CameraDevice.TEMPLATE_VIDEO_SNAPSHOT,
            // CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG,
            // CameraDevice.TEMPLATE_MANUAL
    };

    /**
     * Create a request template
     *
     * @param c a non-{@code null} camera characteristics for this camera
     * @param templateId a non-negative template ID
     *
     * @return a non-{@code null} request template
     *
     * @throws IllegalArgumentException if {@code templateId} was invalid
     *
     * @see android.hardware.camera2.CameraDevice#TEMPLATE_MANUAL
     */
    public static CameraMetadataNative createRequestTemplate(
            CameraCharacteristics c, int templateId) {
        if (!ArrayUtils.contains(sAllowedTemplates, templateId)) {
            throw new IllegalArgumentException("templateId out of range");
        }

        CameraMetadataNative m = new CameraMetadataNative();

        /*
         * NOTE: If adding new code here and it needs to query the static info,
         * query the camera characteristics, so we can reuse this for api2 code later
         * to create our own templates in the framework
         */

        /*
         * control.*
         */

        // control.awbMode
        m.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        // AWB is always unconditionally available in API1 devices

        // control.aeAntibandingMode
        m.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CONTROL_AE_ANTIBANDING_MODE_AUTO);

        // control.aeExposureCompensation
        m.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);

        // control.aeLock
        m.set(CaptureRequest.CONTROL_AE_LOCK, false);

        // control.aePrecaptureTrigger
        m.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);

        // control.afTrigger
        m.set(CaptureRequest.CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_IDLE);

        // control.awbMode
        m.set(CaptureRequest.CONTROL_AWB_MODE, CONTROL_AWB_MODE_AUTO);

        // control.awbLock
        m.set(CaptureRequest.CONTROL_AWB_LOCK, false);

        // control.aeRegions, control.awbRegions, control.afRegions
        {
            Rect activeArray = c.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            MeteringRectangle[] activeRegions =  new MeteringRectangle[] {
                    new MeteringRectangle(/*x*/0, /*y*/0, /*width*/activeArray.width() - 1,
                    /*height*/activeArray.height() - 1,/*weight*/0)};
            m.set(CaptureRequest.CONTROL_AE_REGIONS, activeRegions);
            m.set(CaptureRequest.CONTROL_AWB_REGIONS, activeRegions);
            m.set(CaptureRequest.CONTROL_AF_REGIONS, activeRegions);
        }

        // control.captureIntent
        {
            int captureIntent;
            switch (templateId) {
                case CameraDevice.TEMPLATE_PREVIEW:
                    captureIntent = CONTROL_CAPTURE_INTENT_PREVIEW;
                    break;
                case CameraDevice.TEMPLATE_STILL_CAPTURE:
                    captureIntent = CONTROL_CAPTURE_INTENT_STILL_CAPTURE;
                    break;
                case CameraDevice.TEMPLATE_RECORD:
                    captureIntent = CONTROL_CAPTURE_INTENT_VIDEO_RECORD;
                    break;
                default:
                    // Can't get anything else since it's guarded by the IAE check
                    throw new AssertionError("Impossible; keep in sync with sAllowedTemplates");
            }
            m.set(CaptureRequest.CONTROL_CAPTURE_INTENT, captureIntent);
        }

        // control.aeMode
        m.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        // AE is always unconditionally available in API1 devices

        // control.mode
        m.set(CaptureRequest.CONTROL_MODE, CONTROL_MODE_AUTO);

        // control.afMode
        {
            Float minimumFocusDistance = c.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE);

            int afMode;
            if (minimumFocusDistance != null &&
                    minimumFocusDistance == LENS_INFO_MINIMUM_FOCUS_DISTANCE_FIXED_FOCUS) {
                // Cannot control auto-focus with fixed-focus cameras
                afMode = CameraMetadata.CONTROL_AF_MODE_OFF;
            } else {
                // If a minimum focus distance is reported; the camera must have AF
                afMode = CameraMetadata.CONTROL_AF_MODE_AUTO;

                if (templateId == CameraDevice.TEMPLATE_RECORD ||
                        templateId == CameraDevice.TEMPLATE_VIDEO_SNAPSHOT) {
                    if (ArrayUtils.contains(c.get(CONTROL_AF_AVAILABLE_MODES),
                            CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                        afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
                    }
                } else if (templateId == CameraDevice.TEMPLATE_PREVIEW ||
                        templateId == CameraDevice.TEMPLATE_STILL_CAPTURE) {
                    if (ArrayUtils.contains(c.get(CONTROL_AF_AVAILABLE_MODES),
                            CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                        afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
                    }
                }
            }

            if (VERBOSE) {
                Log.v(TAG, "createRequestTemplate (templateId=" + templateId + ")," +
                        " afMode=" + afMode + ", minimumFocusDistance=" + minimumFocusDistance);
            }

            m.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        }

        {
            // control.aeTargetFpsRange
            Range<Integer>[] availableFpsRange = c.
                    get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            // Pick FPS range with highest max value, tiebreak on higher min value
            Range<Integer> bestRange = availableFpsRange[0];
            for (Range<Integer> r : availableFpsRange) {
                if (bestRange.getUpper() < r.getUpper()) {
                    bestRange = r;
                } else if (bestRange.getUpper() == r.getUpper() &&
                        bestRange.getLower() < r.getLower()) {
                    bestRange = r;
                }
            }
            m.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange);
        }

        // control.sceneMode -- DISABLED is always available
        m.set(CaptureRequest.CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_DISABLED);

        /*
         * statistics.*
         */

        // statistics.faceDetectMode
        m.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, STATISTICS_FACE_DETECT_MODE_OFF);

        /*
         * flash.*
         */

        // flash.mode
        m.set(CaptureRequest.FLASH_MODE, FLASH_MODE_OFF);

        /*
         * noiseReduction.*
         */
        m.set(CaptureRequest.NOISE_REDUCTION_MODE, NOISE_REDUCTION_MODE_FAST);

        /*
         * lens.*
         */

        // lens.focalLength
        m.set(CaptureRequest.LENS_FOCAL_LENGTH,
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0]);

        /*
         * jpeg.*
         */

        // jpeg.thumbnailSize - set smallest non-zero size if possible
        Size[] sizes = c.get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES);
        m.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, (sizes.length > 1) ? sizes[1] : sizes[0]);

        // TODO: map other request template values
        return m;
    }

    private static int[] getTagsForKeys(Key<?>[] keys) {
        int[] tags = new int[keys.length];

        for (int i = 0; i < keys.length; ++i) {
            tags[i] = keys[i].getNativeKey().getTag();
        }

        return tags;
    }

    private static int[] getTagsForKeys(CaptureRequest.Key<?>[] keys) {
        int[] tags = new int[keys.length];

        for (int i = 0; i < keys.length; ++i) {
            tags[i] = keys[i].getNativeKey().getTag();
        }

        return tags;
    }

    private static int[] getTagsForKeys(CaptureResult.Key<?>[] keys) {
        int[] tags = new int[keys.length];

        for (int i = 0; i < keys.length; ++i) {
            tags[i] = keys[i].getNativeKey().getTag();
        }

        return tags;
    }

    /**
     * Convert the requested AF mode into its equivalent supported parameter.
     *
     * @param mode {@code CONTROL_AF_MODE}
     * @param supportedFocusModes list of camera1's supported focus modes
     * @return the stringified af mode, or {@code null} if its not supported
     */
    static String convertAfModeToLegacy(int mode, List<String> supportedFocusModes) {
        if (supportedFocusModes == null || supportedFocusModes.isEmpty()) {
            Log.w(TAG, "No focus modes supported; API1 bug");
            return null;
        }

        String param = null;
        switch (mode) {
            case CONTROL_AF_MODE_AUTO:
                param = Parameters.FOCUS_MODE_AUTO;
                break;
            case CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                param = Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
                break;
            case CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                param = Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                break;
            case CONTROL_AF_MODE_EDOF:
                param = Parameters.FOCUS_MODE_EDOF;
                break;
            case CONTROL_AF_MODE_MACRO:
                param = Parameters.FOCUS_MODE_MACRO;
                break;
            case CONTROL_AF_MODE_OFF:
                if (supportedFocusModes.contains(Parameters.FOCUS_MODE_FIXED)) {
                    param = Parameters.FOCUS_MODE_FIXED;
                } else {
                    param = Parameters.FOCUS_MODE_INFINITY;
                }
        }

        if (!supportedFocusModes.contains(param)) {
            // Weed out bad user input by setting to the first arbitrary focus mode
            String defaultMode = supportedFocusModes.get(0);
            Log.w(TAG,
                    String.format(
                            "convertAfModeToLegacy - ignoring unsupported mode %d, " +
                            "defaulting to %s", mode, defaultMode));
            param = defaultMode;
        }

        return param;
    }
}
