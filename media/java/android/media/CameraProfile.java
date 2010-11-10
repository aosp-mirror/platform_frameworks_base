/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media;

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;

import java.util.Arrays;
import java.util.HashMap;

/**
 * The CameraProfile class is used to retrieve the pre-defined still image
 * capture (jpeg) quality levels (0-100) used for low, medium, and high
 * quality settings in the Camera application.
 *
 */
public class CameraProfile
{
    /**
     * Define three quality levels for JPEG image encoding.
     */
    /*
     * Don't change the values for these constants unless getImageEncodingQualityLevels()
     * method is also changed accordingly.
     */
    public static final int QUALITY_LOW    = 0;
    public static final int QUALITY_MEDIUM = 1;
    public static final int QUALITY_HIGH   = 2;

    /*
     * Cache the Jpeg encoding quality parameters
     */
    private static final HashMap<Integer, int[]> sCache = new HashMap<Integer, int[]>();

    /**
     * Returns a pre-defined still image capture (jpeg) quality level
     * used for the given quality level in the Camera application for
     * the first back-facing camera on the device. If the device has no
     * back-facing camera, this returns 0.
     *
     * @param quality The target quality level
     */
    public static int getJpegEncodingQualityParameter(int quality) {
        int numberOfCameras = Camera.getNumberOfCameras();
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                return getJpegEncodingQualityParameter(i, quality);
            }
        }
        return 0;
    }

    /**
     * Returns a pre-defined still image capture (jpeg) quality level
     * used for the given quality level in the Camera application for
     * the specified camera.
     *
     * @param cameraId The id of the camera
     * @param quality The target quality level
     */
    public static int getJpegEncodingQualityParameter(int cameraId, int quality) {
        if (quality < QUALITY_LOW || quality > QUALITY_HIGH) {
            throw new IllegalArgumentException("Unsupported quality level: " + quality);
        }
        synchronized (sCache) {
            int[] levels = sCache.get(cameraId);
            if (levels == null) {
                levels = getImageEncodingQualityLevels(cameraId);
                sCache.put(cameraId, levels);
            }
            return levels[quality];
        }
    }

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private static int[] getImageEncodingQualityLevels(int cameraId) {
        int nLevels = native_get_num_image_encoding_quality_levels(cameraId);
        if (nLevels != QUALITY_HIGH + 1) {
            throw new RuntimeException("Unexpected Jpeg encoding quality levels " + nLevels);
        }

        int[] levels = new int[nLevels];
        for (int i = 0; i < nLevels; ++i) {
            levels[i] = native_get_image_encoding_quality_level(cameraId, i);
        }
        Arrays.sort(levels);  // Lower quality level ALWAYS comes before higher one
        return levels;
    }

    // Methods implemented by JNI
    private static native final void native_init();
    private static native final int native_get_num_image_encoding_quality_levels(int cameraId);
    private static native final int native_get_image_encoding_quality_level(int cameraId, int index);
}
