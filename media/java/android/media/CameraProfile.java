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

/**
 * The CameraProfile class is used to retrieve the pre-defined still image
 * capture (jpeg) quality levels (0-100) used for low, medium, and high
 * quality settings in the Camera application.
 *
 */
public class CameraProfile
{
    /**
     * Returns a list of the pre-defined still image capture (jpeg) quality levels
     * used for low, medium and high quality settings in the Camera application.
     */
    public static int[] getImageEncodingQualityLevels() {
        int nLevels = native_get_num_image_encoding_quality_levels();
        if (nLevels == 0) return null;

        int[] levels = new int[nLevels];
        for (int i = 0; i < nLevels; ++i) {
            levels[i] = native_get_image_encoding_quality_level(i);
        }
        return levels;
    }

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    // Methods implemented by JNI
    private static native final void native_init();
    private static native final int native_get_num_image_encoding_quality_levels();
    private static native final int native_get_image_encoding_quality_level(int index);
}
