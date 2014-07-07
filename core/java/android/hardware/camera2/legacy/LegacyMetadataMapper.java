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
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.utils.ArrayUtils;
import android.hardware.camera2.utils.ListUtils;
import android.hardware.camera2.utils.ParamsUtils;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.internal.util.Preconditions.*;
import static android.hardware.camera2.CameraCharacteristics.*;
import static android.hardware.camera2.legacy.ParameterUtils.*;

/**
 * Provide legacy-specific implementations of camera2 metadata for legacy devices, such as the
 * camera characteristics.
 */
public class LegacyMetadataMapper {
    private static final String TAG = "LegacyMetadataMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final long NS_PER_MS = 1000000;

    // from graphics.h
    private static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;

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

    /*
     * Development hijinks: Lie about not supporting certain capabilities
     *
     * - Unblock some CTS tests from running whose main intent is not the metadata itself
     *
     * TODO: Remove these constants and strip out any code that previously relied on them
     * being set to true.
     */
    static final boolean LIE_ABOUT_AE_STATE = true;
    static final boolean LIE_ABOUT_AE_MAX_REGIONS = true;
    static final boolean LIE_ABOUT_AF = true;
    static final boolean LIE_ABOUT_AF_MAX_REGIONS = true;
    static final boolean LIE_ABOUT_AWB = true;

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
         * info.supportedHardwareLevel
         */
        m.set(INFO_SUPPORTED_HARDWARE_LEVEL, INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);

        /*
         * scaler.availableStream*, scaler.available*Durations, sensor.info.maxFrameDuration
         */
        mapScalerStreamConfigs(m, p);

        /*
         * control.ae*
         */
        mapControlAe(m, p);
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
         * request.*
         */
        mapRequest(m, p);
        // TODO: map other fields

        /*
         * scaler.*
         */
        mapScaler(m, p);

        /*
         * sensor.*
         */
        mapSensor(m, p);

        /*
         * sync.*
         */
        mapSync(m, p);
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
        appendStreamConfig(availableStreamConfigs,
                HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED, previewSizes);
        appendStreamConfig(availableStreamConfigs,
                ImageFormat.YUV_420_888, previewSizes);
        for (int format : p.getSupportedPreviewFormats()) {
            if (ImageFormat.isPublicFormat(format)) {
                appendStreamConfig(availableStreamConfigs, format, previewSizes);
            } else {
                /*
                 *  Do not add any formats unknown to us
                 * (since it would fail runtime checks in StreamConfigurationMap)
                 */
                Log.w(TAG,
                        String.format("mapStreamConfigs - Skipping non-public format %x", format));
            }
        }

        List<Camera.Size> jpegSizes = p.getSupportedPictureSizes();
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
                if (convertedMode == -1) {
                    Log.w(TAG, "Antibanding mode " + ((mode == null) ? "NULL" : mode) +
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
                    Camera.Parameters.FLASH_MODE_AUTO,
                    Camera.Parameters.FLASH_MODE_ON,
                    Camera.Parameters.FLASH_MODE_RED_EYE,
                    // Map these manually
                    Camera.Parameters.FLASH_MODE_TORCH,
                    Camera.Parameters.FLASH_MODE_OFF,
            };
            int[] flashModeInts = new int[] {
                    CONTROL_AE_MODE_ON,
                    CONTROL_AE_MODE_ON_AUTO_FLASH,
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
    }

    private static void mapControlAwb(CameraMetadataNative m, Camera.Parameters p) {
        if (!LIE_ABOUT_AWB) {
            throw new AssertionError("Not implemented yet");
        }
    }

    private static void mapControlOther(CameraMetadataNative m, Camera.Parameters p) {
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

        // TODO rest of control fields
    }

    private static void mapLens(CameraMetadataNative m, Camera.Parameters p) {
        /*
         *  We can tell if the lens is fixed focus;
         *  but if it's not, we can't tell the minimum focus distance, so leave it null then.
         */
        if (p.getFocusMode() == Camera.Parameters.FOCUS_MODE_FIXED) {
            /*
             * lens.info.minimumFocusDistance
             */
            m.set(LENS_INFO_MINIMUM_FOCUS_DISTANCE, LENS_INFO_MINIMUM_FOCUS_DISTANCE_FIXED_FOCUS);
        }
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

    private static void mapRequest(CameraMetadataNative m, Parameters p) {
        /*
         * request.availableCapabilities
         */
        int[] capabilities = { REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE };
        m.set(REQUEST_AVAILABLE_CAPABILITIES, capabilities);

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
         * sensor.info.pixelArraySize
         */
        m.set(SENSOR_INFO_PIXEL_ARRAY_SIZE, largestJpegSize);
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
        if (templateId < 0 || templateId > CameraDevice.TEMPLATE_MANUAL) {
            throw new IllegalArgumentException("templateId out of range");
        }

        CameraMetadataNative m = new CameraMetadataNative();

        /*
         * NOTE: If adding new code here and it needs to query the static info,
         * query the camera characteristics, so we can reuse this for api2 code later
         * to create our own templates in the framework
         */

        if (LIE_ABOUT_AWB) {
            m.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        } else {
            throw new AssertionError("Valid control.awbMode not implemented yet");
        }

        // control.aeMode
        m.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        // AE is always unconditionally available in API1 devices

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
            }

            m.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        }

        // TODO: map other request template values
        return m;
    }
}
