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

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.utils.ListUtils;
import android.hardware.camera2.utils.ParamsUtils;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.android.internal.util.Preconditions.*;
import static android.hardware.camera2.CaptureRequest.*;

/**
 * Provide legacy-specific implementations of camera2 CaptureRequest for legacy devices.
 */
@SuppressWarnings("deprecation")
public class LegacyRequestMapper {
    private static final String TAG = "LegacyRequestMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * Set the legacy parameters using the {@link LegacyRequest legacy request}.
     *
     * <p>The legacy request's parameters are changed as a side effect of calling this
     * method.</p>
     *
     * @param legacyRequest a non-{@code null} legacy request
     */
    public static void convertRequestMetadata(LegacyRequest legacyRequest) {
        CameraCharacteristics characteristics = legacyRequest.characteristics;
        CaptureRequest request = legacyRequest.captureRequest;
        Size previewSize = legacyRequest.previewSize;
        Camera.Parameters params = legacyRequest.parameters;

        Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        /*
         * scaler.cropRegion
         */
        ParameterUtils.ZoomData zoomData;
        {
            zoomData = ParameterUtils.convertScalerCropRegion(activeArray,
                    request.get(SCALER_CROP_REGION),
                    previewSize,
                    params);

            if (params.isZoomSupported()) {
                params.setZoom(zoomData.zoomIndex);
            } else if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - zoom is not supported");
            }
        }


        /*
         * control.ae*
         */
        // control.aeAntibandingMode
        {
        String legacyMode;
            Integer antiBandingMode = request.get(CONTROL_AE_ANTIBANDING_MODE);
            if (antiBandingMode != null) {
                legacyMode = convertAeAntiBandingModeToLegacy(antiBandingMode);
            } else {
                legacyMode = ListUtils.listSelectFirstFrom(params.getSupportedAntibanding(),
                        new String[] {
                            Parameters.ANTIBANDING_AUTO,
                            Parameters.ANTIBANDING_OFF,
                            Parameters.ANTIBANDING_50HZ,
                            Parameters.ANTIBANDING_60HZ,
                        });
            }

            if (legacyMode != null) {
                params.setAntibanding(legacyMode);
            }
        }

        /*
         * control.aeRegions, afRegions
         */
        {
            // aeRegions
            {
                // Use aeRegions if available, fall back to using awbRegions if present
                MeteringRectangle[] aeRegions = request.get(CONTROL_AE_REGIONS);
                if (request.get(CONTROL_AWB_REGIONS) != null) {
                    Log.w(TAG, "convertRequestMetadata - control.awbRegions setting is not " +
                            "supported, ignoring value");
                }
                int maxNumMeteringAreas = params.getMaxNumMeteringAreas();
                List<Camera.Area> meteringAreaList = convertMeteringRegionsToLegacy(
                        activeArray, zoomData, aeRegions, maxNumMeteringAreas,
                        /*regionName*/"AE");

                params.setMeteringAreas(meteringAreaList);
            }

            // afRegions
            {
                MeteringRectangle[] afRegions = request.get(CONTROL_AF_REGIONS);
                int maxNumFocusAreas = params.getMaxNumFocusAreas();
                List<Camera.Area> focusAreaList = convertMeteringRegionsToLegacy(
                        activeArray, zoomData, afRegions, maxNumFocusAreas,
                        /*regionName*/"AF");

                params.setFocusAreas(focusAreaList);
            }
        }

        // control.aeTargetFpsRange
        Range<Integer> aeFpsRange = request.get(CONTROL_AE_TARGET_FPS_RANGE);
        if (aeFpsRange != null) {
            int[] legacyFps = convertAeFpsRangeToLegacy(aeFpsRange);

            // TODO - Should we enforce that all HAL1 devices must include (30, 30) FPS range?
            boolean supported = false;
            for(int[] range : params.getSupportedPreviewFpsRange()) {
                if (legacyFps[0] == range[0] && legacyFps[1] == range[1]) {
                    supported = true;
                    break;
                }
            }
            if (supported) {
                params.setPreviewFpsRange(legacyFps[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                        legacyFps[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
            } else {
                Log.w(TAG, "Unsupported FPS range set [" + legacyFps[0] + "," + legacyFps[1] + "]");
            }
        }

        /*
         * control
         */

        // control.aeExposureCompensation
        {
            Range<Integer> compensationRange =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            int compensation = ParamsUtils.getOrDefault(request,
                    CONTROL_AE_EXPOSURE_COMPENSATION,
                    /*defaultValue*/0);

            if (!compensationRange.contains(compensation)) {
                Log.w(TAG,
                        "convertRequestMetadata - control.aeExposureCompensation " +
                        "is out of range, ignoring value");
                compensation = 0;
            }

            params.setExposureCompensation(compensation);
        }

        // control.aeLock
        {
            Boolean aeLock = getIfSupported(request, CONTROL_AE_LOCK, /*defaultValue*/false,
                    params.isAutoExposureLockSupported(),
                    /*allowedValue*/false);

            if (aeLock != null) {
                params.setAutoExposureLock(aeLock);
            }

            if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - control.aeLock set to " + aeLock);
            }

            // TODO: Don't add control.aeLock to availableRequestKeys if it's not supported
        }

        // control.aeMode, flash.mode
        mapAeAndFlashMode(request, /*out*/params);

        // control.afMode
        {
            int afMode = ParamsUtils.getOrDefault(request, CONTROL_AF_MODE,
                    /*defaultValue*/CONTROL_AF_MODE_OFF);
            String focusMode = LegacyMetadataMapper.convertAfModeToLegacy(afMode,
                    params.getSupportedFocusModes());

            if (focusMode != null) {
                params.setFocusMode(focusMode);
            }

            if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - control.afMode "
                        + afMode + " mapped to " + focusMode);
            }
        }

        // control.awbMode
        {
            Integer awbMode = getIfSupported(request, CONTROL_AWB_MODE,
                    /*defaultValue*/CONTROL_AWB_MODE_AUTO,
                    params.getSupportedWhiteBalance() != null,
                    /*allowedValue*/CONTROL_AWB_MODE_AUTO);

            String whiteBalanceMode = null;
            if (awbMode != null) { // null iff AWB is not supported by camera1 api
                whiteBalanceMode = convertAwbModeToLegacy(awbMode);
                params.setWhiteBalance(whiteBalanceMode);
            }

            if (VERBOSE) {
                Log.v(TAG, "convertRequestToMetadata - control.awbMode "
                        + awbMode + " mapped to " + whiteBalanceMode);
            }
        }

        // control.awbLock
        {
            Boolean awbLock = getIfSupported(request, CONTROL_AWB_LOCK, /*defaultValue*/false,
                    params.isAutoWhiteBalanceLockSupported(),
                    /*allowedValue*/false);

            if (awbLock != null) {
                params.setAutoWhiteBalanceLock(awbLock);
            }

         // TODO: Don't add control.awbLock to availableRequestKeys if it's not supported
        }

        // control.captureIntent
        {
            int captureIntent = ParamsUtils.getOrDefault(request,
                    CONTROL_CAPTURE_INTENT,
                    /*defaultValue*/CONTROL_CAPTURE_INTENT_PREVIEW);

            captureIntent = filterSupportedCaptureIntent(captureIntent);

            params.setRecordingHint(
                    captureIntent == CONTROL_CAPTURE_INTENT_VIDEO_RECORD ||
                    captureIntent == CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT);
        }

        // control.videoStabilizationMode
        {
            Integer stabMode = getIfSupported(request, CONTROL_VIDEO_STABILIZATION_MODE,
                    /*defaultValue*/CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                    params.isVideoStabilizationSupported(),
                    /*allowedValue*/CONTROL_VIDEO_STABILIZATION_MODE_OFF);

            if (stabMode != null) {
                params.setVideoStabilization(stabMode == CONTROL_VIDEO_STABILIZATION_MODE_ON);
            }
        }

        // lens.focusDistance
        {
            boolean infinityFocusSupported =
                    ListUtils.listContains(params.getSupportedFocusModes(),
                            Parameters.FOCUS_MODE_INFINITY);
            Float focusDistance = getIfSupported(request, LENS_FOCUS_DISTANCE,
                    /*defaultValue*/0f, infinityFocusSupported, /*allowedValue*/0f);

            if (focusDistance == null || focusDistance != 0f) {
                Log.w(TAG,
                        "convertRequestToMetadata - Ignoring android.lens.focusDistance "
                                + infinityFocusSupported + ", only 0.0f is supported");
            }
        }

        // control.sceneMode, control.mode
        {
            // TODO: Map FACE_PRIORITY scene mode to face detection.

            if (params.getSupportedSceneModes() != null) {
                int controlMode = ParamsUtils.getOrDefault(request, CONTROL_MODE,
                    /*defaultValue*/CONTROL_MODE_AUTO);
                String modeToSet;
                switch (controlMode) {
                    case CONTROL_MODE_USE_SCENE_MODE: {
                        int sceneMode = ParamsUtils.getOrDefault(request, CONTROL_SCENE_MODE,
                                /*defaultValue*/CONTROL_SCENE_MODE_DISABLED);
                        String legacySceneMode = LegacyMetadataMapper.
                                convertSceneModeToLegacy(sceneMode);
                        if (legacySceneMode != null) {
                            modeToSet = legacySceneMode;
                        } else {
                            modeToSet = Parameters.SCENE_MODE_AUTO;
                            Log.w(TAG, "Skipping unknown requested scene mode: " + sceneMode);
                        }
                        break;
                    }
                    case CONTROL_MODE_AUTO: {
                        modeToSet = Parameters.SCENE_MODE_AUTO;
                        break;
                    }
                    default: {
                        Log.w(TAG, "Control mode " + controlMode +
                                " is unsupported, defaulting to AUTO");
                        modeToSet = Parameters.SCENE_MODE_AUTO;
                    }
                }
                params.setSceneMode(modeToSet);
            }
        }

        // control.effectMode
        {
            if (params.getSupportedColorEffects() != null) {
                int effectMode = ParamsUtils.getOrDefault(request, CONTROL_EFFECT_MODE,
                    /*defaultValue*/CONTROL_EFFECT_MODE_OFF);
                String legacyEffectMode = LegacyMetadataMapper.convertEffectModeToLegacy(effectMode);
                if (legacyEffectMode != null) {
                    params.setColorEffect(legacyEffectMode);
                } else {
                    params.setColorEffect(Parameters.EFFECT_NONE);
                    Log.w(TAG, "Skipping unknown requested effect mode: " + effectMode);
                }
            }
        }

        /*
         * sensor
         */

        // sensor.testPattern
        {
            int testPatternMode = ParamsUtils.getOrDefault(request, SENSOR_TEST_PATTERN_MODE,
                    /*defaultValue*/SENSOR_TEST_PATTERN_MODE_OFF);
            if (testPatternMode != SENSOR_TEST_PATTERN_MODE_OFF) {
                Log.w(TAG, "convertRequestToMetadata - ignoring sensor.testPatternMode "
                        + testPatternMode + "; only OFF is supported");
            }
        }
    }

    static int filterSupportedCaptureIntent(int captureIntent) {
        switch (captureIntent) {
            case CONTROL_CAPTURE_INTENT_CUSTOM:
            case CONTROL_CAPTURE_INTENT_PREVIEW:
            case CONTROL_CAPTURE_INTENT_STILL_CAPTURE:
            case CONTROL_CAPTURE_INTENT_VIDEO_RECORD:
            case CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT:
                break;
            case CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG:
            case CONTROL_CAPTURE_INTENT_MANUAL:
                captureIntent = CONTROL_CAPTURE_INTENT_PREVIEW;
                Log.w(TAG, "Unsupported control.captureIntent value " + captureIntent
                        + "; default to PREVIEW");
            default:
                captureIntent = CONTROL_CAPTURE_INTENT_PREVIEW;
                Log.w(TAG, "Unknown control.captureIntent value " + captureIntent
                        + "; default to PREVIEW");
        }

        return captureIntent;
    }

    private static List<Camera.Area> convertMeteringRegionsToLegacy(
            Rect activeArray, ParameterUtils.ZoomData zoomData,
            MeteringRectangle[] meteringRegions, int maxNumMeteringAreas, String regionName) {
        if (meteringRegions == null || maxNumMeteringAreas <= 0) {
            if (maxNumMeteringAreas > 0) {
                return Arrays.asList(ParameterUtils.CAMERA_AREA_DEFAULT);
            } else {
                return null;
            }
        }

        // Add all non-zero weight regions to the list
        List<MeteringRectangle> meteringRectangleList = new ArrayList<>();
        for (MeteringRectangle rect : meteringRegions) {
            if (rect.getMeteringWeight() != MeteringRectangle.METERING_WEIGHT_DONT_CARE) {
                meteringRectangleList.add(rect);
            }
        }

        // Ignore any regions beyond our maximum supported count
        int countMeteringAreas =
                Math.min(maxNumMeteringAreas, meteringRectangleList.size());
        List<Camera.Area> meteringAreaList = new ArrayList<>(countMeteringAreas);

        for (int i = 0; i < countMeteringAreas; ++i) {
            MeteringRectangle rect = meteringRectangleList.get(i);

            ParameterUtils.MeteringData meteringData =
                    ParameterUtils.convertMeteringRectangleToLegacy(activeArray, rect, zoomData);
            meteringAreaList.add(meteringData.meteringArea);
        }

        if (maxNumMeteringAreas < meteringRectangleList.size()) {
            Log.w(TAG,
                    "convertMeteringRegionsToLegacy - Too many requested " + regionName +
                            " regions, ignoring all beyond the first " + maxNumMeteringAreas);
        }

        if (VERBOSE) {
            Log.v(TAG, "convertMeteringRegionsToLegacy - " + regionName + " areas = "
                    + ParameterUtils.stringFromAreaList(meteringAreaList));
        }

        return meteringAreaList;
    }

    private static void mapAeAndFlashMode(CaptureRequest r, /*out*/Parameters p) {
        int flashMode = ParamsUtils.getOrDefault(r, FLASH_MODE, FLASH_MODE_OFF);
        int aeMode = ParamsUtils.getOrDefault(r, CONTROL_AE_MODE, CONTROL_AE_MODE_ON);

        List<String> supportedFlashModes = p.getSupportedFlashModes();

        String flashModeSetting = null;

        // Flash is OFF by default, on cameras that support flash
        if (ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_OFF)) {
            flashModeSetting = Parameters.FLASH_MODE_OFF;
        }

        /*
         * Map all of the control.aeMode* enums, but ignore AE_MODE_OFF since we never support it
         */

        // Ignore flash.mode controls unless aeMode == ON
        if (aeMode == CONTROL_AE_MODE_ON) {
            if (flashMode == FLASH_MODE_TORCH) {
                    if (ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_TORCH)) {
                        flashModeSetting = Parameters.FLASH_MODE_TORCH;
                    } else {
                        Log.w(TAG, "mapAeAndFlashMode - Ignore flash.mode == TORCH;" +
                                "camera does not support it");
                    }
            } else if (flashMode == FLASH_MODE_SINGLE) {
                if (ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_ON)) {
                    flashModeSetting = Parameters.FLASH_MODE_ON;
                } else {
                    Log.w(TAG, "mapAeAndFlashMode - Ignore flash.mode == SINGLE;" +
                            "camera does not support it");
                }
            } else {
                // Use the default FLASH_MODE_OFF
            }
        } else if (aeMode == CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                if (ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_ON)) {
                    flashModeSetting = Parameters.FLASH_MODE_ON;
                } else {
                    Log.w(TAG, "mapAeAndFlashMode - Ignore control.aeMode == ON_ALWAYS_FLASH;" +
                            "camera does not support it");
                }
        } else if (aeMode == CONTROL_AE_MODE_ON_AUTO_FLASH) {
            if (ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_AUTO)) {
                flashModeSetting = Parameters.FLASH_MODE_AUTO;
            } else {
                Log.w(TAG, "mapAeAndFlashMode - Ignore control.aeMode == ON_AUTO_FLASH;" +
                        "camera does not support it");
            }
        } else if (aeMode == CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE) {
                if (ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_RED_EYE)) {
                    flashModeSetting = Parameters.FLASH_MODE_RED_EYE;
                } else {
                    Log.w(TAG, "mapAeAndFlashMode - Ignore control.aeMode == ON_AUTO_FLASH_REDEYE;"
                            + "camera does not support it");
                }
        } else {
            // Default to aeMode == ON, flash = OFF
        }

        if (flashModeSetting != null) {
            p.setFlashMode(flashModeSetting);
        }

        if (VERBOSE) {
                Log.v(TAG,
                        "mapAeAndFlashMode - set flash.mode (api1) to " + flashModeSetting
                        + ", requested (api2) " + flashMode
                        + ", supported (api1) " + ListUtils.listToString(supportedFlashModes));
        }
    }

    /**
     * Returns null if the anti-banding mode enum is not supported.
     */
    private static String convertAeAntiBandingModeToLegacy(int mode) {
        switch (mode) {
            case CONTROL_AE_ANTIBANDING_MODE_OFF: {
                return Parameters.ANTIBANDING_OFF;
            }
            case CONTROL_AE_ANTIBANDING_MODE_50HZ: {
                return Parameters.ANTIBANDING_50HZ;
            }
            case CONTROL_AE_ANTIBANDING_MODE_60HZ: {
                return Parameters.ANTIBANDING_60HZ;
            }
            case CONTROL_AE_ANTIBANDING_MODE_AUTO: {
                return Parameters.ANTIBANDING_AUTO;
            }
            default: {
                return null;
            }
        }
    }

    private static int[] convertAeFpsRangeToLegacy(Range<Integer> fpsRange) {
        int[] legacyFps = new int[2];
        legacyFps[Parameters.PREVIEW_FPS_MIN_INDEX] = fpsRange.getLower();
        legacyFps[Parameters.PREVIEW_FPS_MAX_INDEX] = fpsRange.getUpper();
        return legacyFps;
    }

    private static String convertAwbModeToLegacy(int mode) {
        switch (mode) {
            case CONTROL_AWB_MODE_AUTO:
                return Camera.Parameters.WHITE_BALANCE_AUTO;
            case CONTROL_AWB_MODE_INCANDESCENT:
                return Camera.Parameters.WHITE_BALANCE_INCANDESCENT;
            case CONTROL_AWB_MODE_FLUORESCENT:
                return Camera.Parameters.WHITE_BALANCE_FLUORESCENT;
            case CONTROL_AWB_MODE_WARM_FLUORESCENT:
                return Camera.Parameters.WHITE_BALANCE_WARM_FLUORESCENT;
            case CONTROL_AWB_MODE_DAYLIGHT:
                return Camera.Parameters.WHITE_BALANCE_DAYLIGHT;
            case CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                return Camera.Parameters.WHITE_BALANCE_CLOUDY_DAYLIGHT;
            case CONTROL_AWB_MODE_TWILIGHT:
                return Camera.Parameters.WHITE_BALANCE_TWILIGHT;
            default:
                Log.w(TAG, "convertAwbModeToLegacy - unrecognized control.awbMode" + mode);
                return Camera.Parameters.WHITE_BALANCE_AUTO;
        }
    }


    /**
     * Return {@code null} if the value is not supported, otherwise return the retrieved key's
     * value from the request (or the default value if it wasn't set).
     *
     * <p>If the fetched value in the request is equivalent to {@code allowedValue},
     * then omit the warning (e.g. turning off AF lock on a camera
     * that always has the AF lock turned off is a silent no-op), but still return {@code null}.</p>
     *
     * <p>Logs a warning to logcat if the key is not supported by api1 camera device.</p.
     */
    private static <T> T getIfSupported(
            CaptureRequest r, CaptureRequest.Key<T> key, T defaultValue, boolean isSupported,
            T allowedValue) {
        T val = ParamsUtils.getOrDefault(r, key, defaultValue);

        if (!isSupported) {
            if (!Objects.equals(val, allowedValue)) {
                Log.w(TAG, key.getName() + " is not supported; ignoring requested value " + val);
            }
            return null;
        }

        return val;
    }
}
