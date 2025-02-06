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
import android.annotation.StringDef;
import android.media.tv.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The contract between the media quality service and applications. Contains definitions for the
 * commonly used parameter names.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public class MediaQualityContract {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "LEVEL_", value = {
            LEVEL_LOW,
            LEVEL_MEDIUM,
            LEVEL_HIGH,
            LEVEL_OFF
    })
    public @interface Level {}

    /**
     * Low level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is turned on with the low level
     * option.
     */
    public static final String LEVEL_LOW = "level_low";

    /**
     * Medium level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is turned on with the medium level
     * option.
     */
    public static final String LEVEL_MEDIUM = "level_medium";

    /**
     * High level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is turned on with the high level
     * option.
     */
    public static final String LEVEL_HIGH = "level_high";

    /**
     * Off level for parameters.
     *
     * <p>This level represents that the corresponding feature is turned off.
     */
    public static final String LEVEL_OFF = "level_off";


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
         * <p>Brightness value range are from 0.0 to 1.0 (inclusive), where 0.0 represents the
         * minimum brightness and 1.0 represents the maximum brightness. The content-unmodified
         * value is 0.5.
         *
         * <p>Type: FLOAT
         */
        public static final String PARAMETER_BRIGHTNESS = "brightness";

        /**
         * The contrast.
         *
         * <p>This value represents the image contrast on an arbitrary scale from 0 to 100,
         * where 0 represents the darkest black (black screen) and 100 represents the brightest
         * white (brighter).
         * The default/unmodified value for contrast is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_CONTRAST = "contrast";

        /**
         * The sharpness.
         *
         * <p>Sharpness value range are from 0 to 100 (inclusive), where 0 represents the minimum
         * sharpness that makes the image appear softer with less defined edges, 100 represents the
         * maximum sharpness that makes the image appear halos around objects due to excessive
         * edges.
         * The default/unmodified value for sharpness is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SHARPNESS = "sharpness";

        /**
         * The saturation.
         *
         * <p>Saturation value controls the intensity or purity of colors.
         * Saturation values are from 0 to 100, where 0 represents grayscale (no color) and 100
         * represents the most vivid colors.
         * The default/unmodified value for saturation is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SATURATION = "saturation";

        /**
         * The hue.
         *
         * <p>Hue affects the balance between red, green and blue primary colors on the screen.
         * Hue values are from -50 to 50, where -50 represents cooler and 50 represents warmer.
         * The default/unmodified value for hue is 0.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_HUE = "hue";

        /**
         * Adjust brightness in advance color engine. Similar to a "brightness" control on a TV
         * but acts at a lower level.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 represents the minimum brightness and
         * 100 represents the maximum brightness. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         * @see #PARAMETER_BRIGHTNESS
         */
        public static final String PARAMETER_COLOR_TUNER_BRIGHTNESS = "color_tuner_brightness";

        /**
         * Adjust saturation in advance color engine. Similar to a "saturation" control on a TV
         * but acts at a lower level.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 being completely desaturated/grayscale
         * and 100 being the most saturated. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         * @see #PARAMETER_SATURATION
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION = "color_tuner_saturation";

        /**
         * Adjust hue in advance color engine. Similar to a "hue" control on a TV but acts at a
         * lower level.
         *
         * <p>The range is from -50 to 50 (inclusive), where -50 represents cooler setting for a
         * specific color and 50 represents warmer setting for a specific color. The
         * default/unmodified value is 0.
         *
         * <p>Type: INTEGER
         * @see #PARAMETER_HUE
         */
        public static final String PARAMETER_COLOR_TUNER_HUE = "color_tuner_hue";

        /**
         * Advance setting for red offset. Adjust the black level of red color channels, it controls
         * the minimum intensity of each color, affecting the shadows and dark areas of the image.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes shadows darker and 100 makes
         * shadows brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_RED_OFFSET = "color_tuner_red_offset";

        /**
         * Advance setting for green offset. Adjust the black level of green color channels, it
         * controls the minimum intensity of each color, affecting the shadows and dark areas of the
         * image.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes shadows darker and 100 makes
         * shadows brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_GREEN_OFFSET = "color_tuner_green_offset";

        /**
         * Advance setting for blue offset. Adjust the black level of blue color channels, it
         * controls the minimum intensity of each color, affecting the shadows and dark areas of the
         * image.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes shadows darker and 100 makes
         * shadows brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_BLUE_OFFSET = "color_tuner_blue_offset";

        /**
         * Advance setting for red gain. Adjust the gain or amplification of the red color channels.
         * They control the overall intensity and white balance of red.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes the red dimmer and 100 makes the
         * red brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_RED_GAIN = "color_tuner_red_gain";

        /**
         * Advance setting for green gain. Adjust the gain or amplification of the green color
         * channels. They control the overall intensity and white balance of green.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes the green dimmer and 100 makes
         * the green brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_GREEN_GAIN = "color_tuner_green_gain";

        /**
         * Advance setting for blue gain. Adjust the gain or amplification of the blue color
         * channels. They control the overall intensity and white balance of blue.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes the blue dimmer and 100 makes
         * the blue brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_BLUE_GAIN = "color_tuner_blue_gain";

        /**
         * Noise reduction.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_NOISE_REDUCTION = "noise_reduction";

        /**
         * MPEG (moving picture experts group) noise reduction.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_MPEG_NOISE_REDUCTION = "mpeg_noise_reduction";

        /**
         * Refine the flesh colors in the pictures without affecting the other colors on the screen.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_FLESH_TONE = "flesh_tone";

        /**
         * Contour noise reduction.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DECONTOUR = "decontour";

        /**
         * Dynamically change picture luma to enhance contrast.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DYNAMIC_LUMA_CONTROL = "dynamic_luma_control";

        /**
         * Enable/disable film mode.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_FILM_MODE = "film_mode";

        /**
         * Enable/disable black color auto stretch
         *
         * @hide
         */
        public static final String PARAMETER_BLACK_STRETCH = "black_stretch";

        /**
         * Enable/disable blue color auto stretch
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_BLUE_STRETCH = "blue_stretch";

        /**
         * Enable/disable the overall color tuning feature.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_COLOR_TUNE = "color_tune";

        /**
         * Adjust color temperature type
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_COLOR_TEMPERATURE = "color_temperature";

        /**
         * Enable/disable globe dimming.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_GLOBAL_DIMMING = "global_dimming";

        /**
         * Enable/disable auto adjust picture parameter based on the TV content.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_AUTO_PICTURE_QUALITY_ENABLED =
                "auto_picture_quality_enabled";

        /**
         * Enable/disable auto upscaling the picture quality. It analyzes the lower-resolution
         * image and uses its knowledge to invent the missing pixel, make the image look sharper.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED =
                "auto_super_resolution_enabled";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_LEVEL_RANGE = "level_range";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_GAMUT_MAPPING = "gamut_mapping";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_PC_MODE = "pc_mode";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_LOW_LATENCY = "low_latency";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_VRR = "vrr";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_CVRR = "cvrr";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_HDMI_RGB_RANGE = "hdmi_rgb_range";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_SPACE = "color_space";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS =
                "panel_init_max_lumince_nits";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID =
                "panel_init_max_lumince_valid";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_GAMMA = "gamma";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TEMPERATURE_RED_GAIN =
                "color_temperature_red_gain";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TEMPERATURE_GREEN_GAIN =
                "color_temperature_green_gain";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TEMPERATURE_BLUE_GAIN =
                "color_temperature_blue_gain";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TEMPERATURE_RED_OFFSET =
                "color_temperature_red_offset";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET =
                "color_temperature_green_offset";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET =
                "color_temperature_blue_offset";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_ELEVEN_POINT_RED = "eleven_point_red";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_ELEVEN_POINT_GREEN = "eleven_point_green";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_ELEVEN_POINT_BLUE = "eleven_point_blue";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_LOW_BLUE_LIGHT = "low_blue_light";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_LD_MODE = "ld_mode";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_RED_GAIN = "osd_red_gain";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_GREEN_GAIN = "osd_green_gain";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_BLUE_GAIN = "osd_blue_gain";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_RED_OFFSET = "osd_red_offset";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_GREEN_OFFSET = "osd_green_offset";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_BLUE_OFFSET = "osd_blue_offset";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_HUE = "osd_hue";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_SATURATION = "osd_saturation";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_OSD_CONTRAST = "osd_contrast";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SWITCH = "color_tuner_switch";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_RED = "color_tuner_hue_red";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_GREEN = "color_tuner_hue_green";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_BLUE = "color_tuner_hue_blue";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_CYAN = "color_tuner_hue_cyan";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_MAGENTA = "color_tuner_hue_magenta";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_YELLOW = "color_tuner_hue_yellow";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_HUE_FLESH = "color_tuner_hue_flesh";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_RED =
                "color_tuner_saturation_red";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_GREEN =
                "color_tuner_saturation_green";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_BLUE =
                "color_tuner_saturation_blue";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_CYAN =
                "color_tuner_saturation_cyan";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_MAGENTA =
                "color_tuner_saturation_magenta";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_YELLOW =
                "color_tuner_saturation_yellow";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION_FLESH =
                "color_tuner_saturation_flesh";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_RED =
                "color_tuner_luminance_red";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_GREEN =
                "color_tuner_luminance_green";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_BLUE =
                "color_tuner_luminance_blue";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_CYAN =
                "color_tuner_luminance_cyan";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA =
                "color_tuner_luminance_magenta";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW =
                "color_tuner_luminance_yellow";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_FLESH =
                "color_tuner_luminance_flesh";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_PICTURE_QUALITY_EVENT_TYPE =
                "picture_quality_event_type";

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
         * <p>This parameter controls the balance between the left and right speakers.
         * The valid range is -50 to 50 (inclusive), where:
         *   - Negative values shift the balance towards the left speaker.
         *   - Positive values shift the balance towards the right speaker.
         *   - 0 represents a balanced output.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BALANCE = "balance";

        /**
         * The bass.
         *
         * <p>Bass controls the intensity of low-frequency sounds.
         * The valid range is 0 - 100 (inclusive).
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BASS = "bass";

        /**
         * The treble.
         *
         * <p>Treble controls the intensity of high-frequency sounds.
         * The valid range is 0 - 100 (inclusive).
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_TREBLE = "treble";

        /**
         * Enable/disable surround sound.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_SURROUND_SOUND = "surround_sound";

        /**
         * @hide
         */
        public static final String PARAMETER_EQUALIZER_DETAIL = "equalizer_detail";

        /**
         * Enable/disable speaker output.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_SPEAKERS = "speakers";

        /**
         * Speaker delay in milliseconds.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SPEAKERS_DELAY_MILLIS = "speakers_delay_millis";

        /**
         * Enable/disable enhanced audio return channel (eARC).
         *
         * <p>eARC allows for higher bandwidth audio transmission over HDMI.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_EARC = "earc";

        /**
         * Enable/disable auto volume control sound effect.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_AUTO_VOLUME_CONTROL = "auto_volume_control";

        /**
         * Downmix mode.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DOWN_MIX_MODE = "down_mix_mode";

        /**
         * Enable/disable dynamic range compression (DRC) of digital theater system (DTS).
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_DRC = "dts_drc";

        /**
         * @hide
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING = "dolby_audio_processing";

        /**
         * Sound mode for dolby audio processing.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_SOUND_MODE =
                "dolby_audio_processing_sound_mode";

        /**
         * Enable/disable Volume Leveler.
         *
         * <p>Volume Leveler helps to maintain a consistent volume level across different
         * types of content and even within the same program. It minimizes the jarring jumps
         * between loud commercials or action sequences and quiet dialogue.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_VOLUME_LEVELER =
                "dolby_audio_processing_volume_leveler";

        /**
         * Enable/disable Surround Virtualizer.
         *
         * <p>Surround Virtualizer creates a virtual surround sound experience from stereo
         * content, making it seem like the sound is coming from multiple speakers, even if
         * you only have your TV's built-in speakers. It expands the soundstage and adds
         * depth to the audio.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_SURROUND_VIRTUALIZER =
                "dolby_audio_processing_surround_virtualizer";

        /**
         * Enable/disable Dolby Atmos.
         *
         * <p>Dolby Atmos creates a more immersive and realistic sound experience by adding
         * a height dimension to surround sound. It allows sound to be placed and moved
         * precisely around you, including overhead.
         *
         * <p>Note: To experience Dolby Atmos, you need content that has been specifically
         * mixed in Dolby Atmos and a compatible sound system with upward-firing speakers
         * or a Dolby Atmos soundbar.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_DOLBY_ATMOS =
                "dolby_audio_processing_dolby_atmos";

        /**
         * Dialogue enhancer.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DIALOGUE_ENHANCER = "dialogue_enhancer";

        /**
         * @hide
         */
        public static final String PARAMETER_DTS_VIRTUAL_X = "dts_virtual_x";

        /**
         * Enable/disable Total Bass Harmonic Distortion (X).
         *
         * <p>TBHDX bass enhancement provides a richer low-frequency experience, simulating deeper
         * bass.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TBHDX = "dts_virtual_x_tbhdx";

        /**
         * Enable/disable audio limiter.
         *
         * <p>It prevents excessive volume peaks that could cause distortion or speaker damage.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_LIMITER = "dts_virtual_x_limiter";

        /**
         * Enable/disable the core DTS Virtual:X surround sound processing.
         *
         * <p>It creates an immersive, multi-channel audio experience from the speaker
         * configuration.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TRU_SURROUND_X =
                "dts_virtual_x_tru_surround_x";

        /**
         * Enable/disable DTS TruVolume HD.
         *
         * <p>It reduces the dynamic range of audio, minimizing loudness variations between content
         * and channels.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TRU_VOLUME_HD =
                "dts_virtual_x_tru_volume_hd";

        /**
         * Enable/disable dialog clarity.
         *
         * <p>It enhances the clarity and intelligibility of speech in audio content.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_DIALOG_CLARITY =
                "dts_virtual_x_dialog_clarity";

        /**
         * Enable/disable virtual X definition.
         *
         * <p>It applies audio processing to improve overall sound definition and clarity.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_DEFINITION = "dts_virtual_x_definition";

        /**
         * Enable/disable the processing of virtual height channels.
         *
         * <p>It creates a more immersive audio experience by simulating sounds from above.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_HEIGHT = "dts_virtual_x_height";

        /**
         * Digital output delay in milliseconds.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS =
                "digital_output_delay_millis";

        /**
         * Digital output mode.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DIGITAL_OUTPUT_MODE = "digital_output_mode";

        /**
         * @hide
         */
        public static final String PARAMETER_SOUND_STYLE = "sound_style";



        private SoundQuality() {
        }
    }

    private MediaQualityContract() {
    }
}
