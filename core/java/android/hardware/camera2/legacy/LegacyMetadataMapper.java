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
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.util.Log;

import java.util.ArrayList;
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
        mapStreamConfigs(m, p);

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
        for (Camera.Size s : jpegSizes) {
            jpegStalls[i++] = new StreamConfigurationDuration(HAL_PIXEL_FORMAT_BLOB, s.width,
                    s.height, calculateJpegStallDuration(s));
        }
        // Set stall durations for jpeg, other formats use default stall duration
        m.set(SCALER_AVAILABLE_STALL_DURATIONS, jpegStalls);
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
}
