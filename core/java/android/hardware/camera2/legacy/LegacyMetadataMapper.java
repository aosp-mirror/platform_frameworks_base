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
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Size;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.util.Log;
import android.util.Range;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.internal.util.Preconditions.*;
import static android.hardware.camera2.CameraCharacteristics.*;

/**
 * Provide legacy-specific implementations of camera2 metadata for legacy devices, such as the
 * camera characteristics.
 */
public class LegacyMetadataMapper {
    private static final String TAG = "LegacyMetadataMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    // from graphics.h
    private static final int HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED = 0x22;
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;

    // for metadata
    private static final float LENS_INFO_MINIMUM_FOCUS_DISTANCE_FIXED_FOCUS = 0.0f;

    private static final long APPROXIMATE_CAPTURE_DELAY_MS = 200; // ms
    private static final long APPROXIMATE_SENSOR_AREA = (1 << 20); // 8mp
    private static final long APPROXIMATE_JPEG_ENCODE_TIME = 600; // ms
    private static final long NS_PER_MS = 1000000;

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

        mapCameraInfo(m, info.info);

        Camera.Parameters params = Camera.getEmptyParameters();
        params.unflatten(parameters);
        mapCameraParameters(m, params);

        if (VERBOSE) {
            Log.v(TAG, "createCharacteristics metadata:");
            Log.v(TAG, "--------------------------------------------------- (start)");
            m.dumpToLog();
            Log.v(TAG, "--------------------------------------------------- (end)");
        }

        return new CameraCharacteristics(m);
    }

    private static void mapCameraInfo(CameraMetadataNative m, CameraInfo i) {
        m.set(LENS_FACING, i.facing == CameraInfo.CAMERA_FACING_BACK ?
                LENS_FACING_BACK : LENS_FACING_FRONT);
        m.set(SENSOR_ORIENTATION, i.orientation);
    }

    private static void mapCameraParameters(CameraMetadataNative m, Camera.Parameters p) {
        m.set(INFO_SUPPORTED_HARDWARE_LEVEL, INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
        mapStreamConfigs(m, p);
        mapAeConfig(m, p);
        mapCapabilities(m, p);
        mapLens(m, p);
        mapFlash(m, p);
        // TODO: map other fields
    }

    private static void mapStreamConfigs(CameraMetadataNative m, Camera.Parameters p) {

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
        List<Size> previewSizes = p.getSupportedPreviewSizes();
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
        m.set(SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                availableStreamConfigs.toArray(new StreamConfiguration[0]));

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
        // Set stall durations for jpeg, other formats use default stall duration
        m.set(SCALER_AVAILABLE_STALL_DURATIONS, jpegStalls);

        m.set(SENSOR_INFO_MAX_FRAME_DURATION, longestStallDuration);
    }

    @SuppressWarnings({"unchecked"})
    private static void mapAeConfig(CameraMetadataNative m, Camera.Parameters p) {

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

        List<String> antiBandingModes = p.getSupportedAntibanding();
        int antiBandingModesSize = antiBandingModes.size();
        if (antiBandingModesSize > 0) {
            int[] modes = new int[antiBandingModesSize];
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
        }
    }

    private static void mapCapabilities(CameraMetadataNative m, Camera.Parameters p) {
        int[] capabilities = { REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE };
        m.set(REQUEST_AVAILABLE_CAPABILITIES, capabilities);
    }

    private static void mapLens(CameraMetadataNative m, Camera.Parameters p) {
        /*
         *  We can tell if the lens is fixed focus;
         *  but if it's not, we can't tell the minimum focus distance, so leave it null then.
         */
        if (p.getFocusMode() == Camera.Parameters.FOCUS_MODE_FIXED) {
            m.set(LENS_INFO_MINIMUM_FOCUS_DISTANCE, LENS_INFO_MINIMUM_FOCUS_DISTANCE_FIXED_FOCUS);
        }
    }

    private static void mapFlash(CameraMetadataNative m, Camera.Parameters p) {
        boolean flashAvailable = false;
        List<String> supportedFlashModes = p.getSupportedFlashModes();
        if (supportedFlashModes != null) {
            // If only 'OFF' is available, we don't really have flash support
            if (!(supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_OFF) &&
                    supportedFlashModes.size() == 1)) {
                flashAvailable = true;
            }
        }

        m.set(FLASH_INFO_AVAILABLE, flashAvailable);
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
     * Returns -1 if the anti-banding mode string is null, or not supported.
     */
    private static int convertAntiBandingMode(final String mode) {
        if (mode == null) {
            return -1;
        }
        switch(mode) {
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
                return -1;
            }
        }
    }

    /**
     * Returns null if the anti-banding mode enum is not supported.
     */
    private static String convertAntiBandingModeToLegacy(int mode) {
        switch(mode) {
            case CONTROL_AE_ANTIBANDING_MODE_OFF: {
                return Camera.Parameters.ANTIBANDING_OFF;
            }
            case CONTROL_AE_ANTIBANDING_MODE_50HZ: {
                return Camera.Parameters.ANTIBANDING_50HZ;
            }
            case CONTROL_AE_ANTIBANDING_MODE_60HZ: {
                return Camera.Parameters.ANTIBANDING_60HZ;
            }
            case CONTROL_AE_ANTIBANDING_MODE_AUTO: {
                return Camera.Parameters.ANTIBANDING_AUTO;
            }
            default: {
                return null;
            }
        }
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
        long stallPerArea = APPROXIMATE_JPEG_ENCODE_TIME * NS_PER_MS /
                APPROXIMATE_SENSOR_AREA; // 600ms stall for 8mp
        return baseDuration + area * stallPerArea;
    }

    /**
     * Generate capture result metadata from legacy camera parameters.
     *
     * @param params a {@link Camera.Parameters} object to generate metadata from.
     * @param request the {@link CaptureRequest} used for this result.
     * @param timestamp the timestamp to use for this result in nanoseconds.
     * @return a {@link CameraMetadataNative} object containing result metadata.
     */
    public static CameraMetadataNative convertResultMetadata(Camera.Parameters params,
                                                      CaptureRequest request,
                                                      long timestamp) {
        CameraMetadataNative result = new CameraMetadataNative();
        result.set(CaptureResult.LENS_FOCAL_LENGTH, params.getFocalLength());
        result.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);

        // TODO: Remaining result metadata tags conversions.
        return result;
    }

    /**
     * Set the legacy parameters using the request metadata.
     *
     * @param request a {@link CaptureRequest} object to generate parameters from.
     * @param params the a {@link Camera.Parameters} to set parameters in.
     */
    public static void convertRequestMetadata(CaptureRequest request,
            /*out*/Camera.Parameters params) {
        Integer antiBandingMode = request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE);
        if (antiBandingMode != null) {
            String legacyMode = convertAntiBandingModeToLegacy(antiBandingMode);
            if (legacyMode != null) params.setAntibanding(legacyMode);
        }

        Range<Integer> aeFpsRange = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        if (aeFpsRange != null) {
            int[] legacyFps = convertAeFpsRangeToLegacy(aeFpsRange);
            params.setPreviewFpsRange(legacyFps[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                    legacyFps[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        }
    }
}
