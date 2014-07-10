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
                MeteringRectangle[] aeRegions = request.get(CONTROL_AE_REGIONS);
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
                params.setRecordingHint(false);
            } else {
                Log.w(TAG, "Unsupported FPS range set [" + legacyFps[0] + "," + legacyFps[1] + "]");
                params.setRecordingHint(true);
            }
        }

        /*
         * control
         */

        // control.aeExposureCompensation
        {
            Range<Integer> compensationRange =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            int compensation = getOrDefault(request,
                    CONTROL_AE_EXPOSURE_COMPENSATION,
                    /*defaultValue*/0);

            if (!compensationRange.inRange(compensation)) {
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

            // TODO: Don't add control.aeLock to availableRequestKeys if it's not supported
        }

        // control.aeMode, flash.mode
        mapAeAndFlashMode(request, /*out*/params);

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
        int flashMode = getOrDefault(r, FLASH_MODE, FLASH_MODE_OFF);
        int aeMode = getOrDefault(r, CONTROL_AE_MODE, CONTROL_AE_MODE_ON);

        List<String> supportedFlashModes = p.getSupportedFlashModes();

        /*
         * Map all of the control.aeMode* enums, but ignore AE_MODE_OFF since we never support it
         */

        // Ignore flash.mode controls unless aeMode == ON
        if (aeMode == CONTROL_AE_MODE_ON) {
            // Flash is OFF by default
            p.setFlashMode(Parameters.FLASH_MODE_OFF);

            if (flashMode == FLASH_MODE_TORCH &&
                    ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_TORCH)) {
                p.setFlashMode(Parameters.FLASH_MODE_TORCH);
            } else if (flashMode == FLASH_MODE_SINGLE &&
                    ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_ON)) {
                p.setFlashMode(Parameters.FLASH_MODE_ON);
            }
        } else if (aeMode == CONTROL_AE_MODE_ON_ALWAYS_FLASH &&
                ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_ON)) {
            p.setFlashMode(Parameters.FLASH_MODE_ON);
        } else if (aeMode == CONTROL_AE_MODE_ON_AUTO_FLASH &&
                ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_AUTO)) {
            p.setFlashMode(Parameters.FLASH_MODE_AUTO);
        } else if (aeMode == CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE &&
                ListUtils.listContains(supportedFlashModes, Parameters.FLASH_MODE_RED_EYE)) {
            p.setFlashMode(Parameters.FLASH_MODE_RED_EYE);
        } else {
            // Default to aeMode == ON, flash = OFF
            p.setFlashMode(Parameters.FLASH_MODE_OFF);
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

    /** Return the value set by the key, or the {@code defaultValue} if no value was set. */
    private static <T> T getOrDefault(CaptureRequest r, CaptureRequest.Key<T> key, T defaultValue) {
        checkNotNull(r, "r must not be null");
        checkNotNull(key, "key must not be null");
        checkNotNull(defaultValue, "defaultValue must not be null");

        T value = r.get(key);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
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
        T val = getOrDefault(r, key, defaultValue);

        if (!isSupported) {
            if (!Objects.equals(val, allowedValue)) {
                Log.w(TAG, key.getName() + " is not supported; ignoring requested value " + val);
            }
            return null;
        }

        return val;
    }
}
