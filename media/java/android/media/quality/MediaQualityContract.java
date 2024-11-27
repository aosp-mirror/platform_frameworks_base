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

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR = "color";
        /**
         * @hide
         */
        public static final String PARAMETER_HUE = "hue";

        /**
         * @hide
         */
        public static final String PARAMETER_BACKLIGHT = "backlight";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_BRIGHTNESS = "color_tuner_brightness";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION = "color_tuner_saturation";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_HUE = "color_tuner_hue";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_REDO_FFSET = "color_tuner_red_offset";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_GREEN_OFFSET = "color_tuner_green_offset";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_BLUE_OFFSET = "color_tuner_blue_offset";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_RED_GAIN = "color_tuner_red_gain";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_GREEN_GAIN = "color_tuner_green_gain";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNER_BLUE_GAIN = "color_tuner_blue_gain";

        /**
         * @hide
         */
        public static final String PARAMETER_AI_PQ = "ai_pq";

        /**
         * @hide
         */
        public static final String PARAMETER_AI_SUPER_RESOLUTION = "ai_super_resolution";

        /**
         * @hide
         */
        public static final String PARAMETER_NOISE_REDUCTION = "noise_reduction";

        /**
         * @hide
         */
        public static final String PARAMETER_MPEG_NOISE_REDUCTION = "mpeg_noise_reduction";

        /**
         * @hide
         */
        public static final String PARAMETER_FLESH_TONE = "flesh_tone";

        /**
         * @hide
         */
        public static final String PARAMETER_DECONTOUR = "decontour";

        /**
         * @hide
         */
        public static final String PARAMETER_DYNAMIC_LUMA_CONTROL = "dynamic_luma_control";

        /**
         * @hide
         */
        public static final String PARAMETER_FILM_MODE = "film_mode";

        /**
         * @hide
         */
        public static final String PARAMETER_BLACK_STRETCH = "black_stretch";

        /**
         * @hide
         */
        public static final String PARAMETER_BLUE_STRETCH = "blue_stretch";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TUNE = "color_tune";

        /**
         * @hide
         */
        public static final String PARAMETER_COLOR_TEMPERATURE = "color_temperature";

        /**
         * @hide
         */
        public static final String PARAMETER_GLOBAL_DIMMING = "global_dimming";

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

        /**
         * @hide
         */
        public static final String PARAMETER_SOUND_MODE = "sound_mode";

        /**
         * @hide
         */
        public static final String PARAMETER_SURROUND_SOUND = "surround_sound";

        /**
         * @hide
         */
        public static final String PARAMETER_EQUALIZER_DETAIL = "equalizer_detail";

        /**
         * @hide
         */
        public static final String PARAMETER_SPEAKERS = "speakers";

        /**
         * @hide
         */
        public static final String PARAMETER_SPEAKERS_DELAY = "speakers_delay";

        /**
         * @hide
         */
        public static final String PARAMETER_EARC = "earc";

        /**
         * @hide
         */
        public static final String PARAMETER_AUTO_VOLUME_CONTROL = "auto_volume_control";

        /**
         * @hide
         */
        public static final String PARAMETER_DOWN_MIX_MODE = "down_mix_mode";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_DRC = "dts_drc";

        /**
         * @hide
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING = "dolby_audio_processing";

        /**
         * @hide
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_SOUND_MODE =
                "dolby_audio_processing_sound_mode";

        /**
         * @hide
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_VOLUME_LEVELER =
                "dolby_audio_processing_volume_leveler";

        /**
         * @hide
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_SURROUND_VIRTUALIZER =
                "dolby_audio_processing_surround_virtualizer";

        /**
         * @hide
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_DOLBY_ATMOS =
                "dolby_audio_processing_dolby_atmos";

        /**
         * @hide
         */
        public static final String PARAMETER_DIALOGUE_ENHANCER = "dialogue_enhancer";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X = "dts_virtual_x";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TBHDX = "dts_virtual_x_tbhdx";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_LIMITER = "dts_virtual_x_limiter";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TRU_SURROUND_X =
                "dts_virtual_x_tru_surround_x";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TRU_VOLUME_HD =
                "dts_virtual_x_tru_volume_hd";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_DIALOG_CLARITY =
                "dts_virtual_x_dialog_clarity";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_DEFINITION = "dts_virtual_x_definition";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_HEIGHT = "dts_virtual_x_height";


        private SoundQuality() {
        }
    }

    private MediaQualityContract() {
    }
}
