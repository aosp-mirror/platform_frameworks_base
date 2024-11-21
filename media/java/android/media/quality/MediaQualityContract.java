/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.quality;


import android.annotation.FlaggedApi;
import android.media.tv.flags.Flags;

/**
 * The contract between the media quality service and applications. Contains definitions for the
 * commonly used parameter names.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public class MediaQualityContract {

    /**
     * @hide
     */
    public interface BaseParameters {
        String PARAMETER_ID = "_id";
        String PARAMETER_TYPE = "_type";
        String PARAMETER_NAME = "_name";
        String PARAMETER_PACKAGE = "_package";
        String PARAMETER_INPUT_ID = "_input_id";

    }

    /**
     * Parameters picture quality.
     */
    public static final class PictureQuality {
        /**
         * The brightness.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BRIGHTNESS = "brightness";

        /**
         * The contrast.
         *
         * <p>The ratio between the luminance of the brightest white and the darkest black.
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_CONTRAST = "contrast";

        /**
         * The sharpness.
         *
         * <p>Sharpness indicates the clarity of detail.
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SHARPNESS = "sharpness";

        /**
         * The saturation.
         *
         * <p>Saturation indicates the intensity of the color.
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SATURATION = "saturation";

        private PictureQuality() {
        }
    }

    /**
     * Parameters for sound quality.
     */
    public static final class SoundQuality {
        /**
         * The audio volume balance.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BALANCE = "balance";

        /**
         * The bass.
         *
         * <p>Bass setting adjust the low sound frequencies.
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BASS = "bass";

        /**
         * The treble.
         *
         * <p>Treble setting adjust the high sound frequencies.
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_TREBLE = "treble";

        private SoundQuality() {
        }
    }

    private MediaQualityContract() {
    }
}
