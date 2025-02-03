/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.media.quality;

import android.content.ContentValues;
import android.database.Cursor;
import android.hardware.tv.mediaquality.DolbyAudioProcessing;
import android.hardware.tv.mediaquality.DtsVirtualX;
import android.hardware.tv.mediaquality.ParameterName;
import android.hardware.tv.mediaquality.PictureParameter;
import android.hardware.tv.mediaquality.SoundParameter;
import android.media.quality.MediaQualityContract.BaseParameters;
import android.media.quality.MediaQualityContract.PictureQuality;
import android.media.quality.MediaQualityContract.SoundQuality;
import android.media.quality.PictureProfile;
import android.media.quality.PictureProfileHandle;
import android.media.quality.SoundProfile;
import android.media.quality.SoundProfileHandle;
import android.os.PersistableBundle;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Utility class for media quality framework.
 *
 * @hide
 */
public final class MediaQualityUtils {

    private static final int MAX_UUID_GENERATION_ATTEMPTS = 10;
    private static final String TAG = "MediaQualityUtils";
    public static final String SETTINGS = "settings";

    /**
     * Convert PictureParameter List to PersistableBundle.
     */
    public static PersistableBundle convertPictureParameterListToPersistableBundle(
            PictureParameter[] parameters) {
        PersistableBundle bundle = new PersistableBundle();
        for (PictureParameter pp : parameters) {
            if (pp.getBrightness() > -1) {
                bundle.putLong(PictureQuality.PARAMETER_BRIGHTNESS, (long) pp.getBrightness());
            }
            if (pp.getContrast() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_CONTRAST, pp.getContrast());
            }
            if (pp.getSharpness() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_SHARPNESS, pp.getSharpness());
            }
            if (pp.getSaturation() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_SATURATION, pp.getSaturation());
            }
            if (pp.getHue() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_HUE, pp.getHue());
            }
            if (pp.getColorTunerBrightness() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS,
                        pp.getColorTunerBrightness());
            }
            if (pp.getColorTunerSaturation() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION,
                        pp.getColorTunerSaturation());
            }
            if (pp.getColorTunerHue() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE, pp.getColorTunerHue());
            }
            if (pp.getColorTunerRedOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET,
                        pp.getColorTunerRedOffset());
            }
            if (pp.getColorTunerGreenOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET,
                        pp.getColorTunerGreenOffset());
            }
            if (pp.getColorTunerBlueOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET,
                        pp.getColorTunerBlueOffset());
            }
            if (pp.getColorTunerRedGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN,
                        pp.getColorTunerRedGain());
            }
            if (pp.getColorTunerGreenGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN,
                        pp.getColorTunerGreenGain());
            }
            if (pp.getColorTunerBlueGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN,
                        pp.getColorTunerBlueGain());
            }
            if (pp.getNoiseReduction() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_NOISE_REDUCTION,
                        pp.getNoiseReduction());
            }
            if (pp.getMpegNoiseReduction() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION,
                        pp.getMpegNoiseReduction());
            }
            if (pp.getFleshTone() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_FLESH_TONE, pp.getFleshTone());
            }
            if (pp.getDeContour() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_DECONTOUR, pp.getDeContour());
            }
            if (pp.getDynamicLumaControl() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL,
                        pp.getDynamicLumaControl());
            }
            if (pp.getColorTemperature() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TEMPERATURE,
                        pp.getColorTemperature());
            }
            if (pp.getColorTemperatureRedGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN,
                        pp.getColorTemperatureRedGain());
            }
            if (pp.getColorTemperatureGreenGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN,
                        pp.getColorTemperatureGreenGain());
            }
            if (pp.getColorTemperatureBlueGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN,
                        pp.getColorTemperatureBlueGain());
            }
            if (pp.getLevelRange() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_LEVEL_RANGE, pp.getLevelRange());
            }
            if (pp.getHdmiRgbRange() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_HDMI_RGB_RANGE, pp.getHdmiRgbRange());
            }
            if (pp.getColorSpace() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_SPACE, pp.getColorSpace());
            }
            if (pp.getPanelInitMaxLuminceNits() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS,
                        pp.getPanelInitMaxLuminceNits());
            }
            if (pp.getGamma() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_GAMMA, pp.getGamma());
            }
            if (pp.getColorTemperatureRedOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET,
                        pp.getColorTemperatureRedOffset());
            }
            if (pp.getColorTemperatureGreenOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET,
                        pp.getColorTemperatureGreenOffset());
            }
            if (pp.getColorTemperatureBlueOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET,
                        pp.getColorTemperatureBlueOffset());
            }
            if (pp.getLowBlueLight() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_LOW_BLUE_LIGHT, pp.getLowBlueLight());
            }
            if (pp.getLdMode() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_LD_MODE, pp.getLdMode());
            }
            if (pp.getOsdRedGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_RED_GAIN, pp.getOsdRedGain());
            }
            if (pp.getOsdGreenGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_GREEN_GAIN, pp.getOsdGreenGain());
            }
            if (pp.getOsdBlueGain() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_BLUE_GAIN, pp.getOsdBlueGain());
            }
            if (pp.getOsdRedOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_RED_OFFSET, pp.getOsdRedOffset());
            }
            if (pp.getOsdGreenOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_GREEN_OFFSET,
                        pp.getOsdGreenOffset());
            }
            if (pp.getOsdBlueOffset() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_BLUE_OFFSET, pp.getOsdBlueOffset());
            }
            if (pp.getOsdHue() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_HUE, pp.getOsdHue());
            }
            if (pp.getOsdSaturation() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_SATURATION, pp.getOsdSaturation());
            }
            if (pp.getOsdContrast() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_OSD_CONTRAST, pp.getOsdContrast());
            }
            if (pp.getColorTunerHueRed() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED,
                        pp.getColorTunerHueRed());
            }
            if (pp.getColorTunerHueGreen() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN,
                        pp.getColorTunerHueGreen());
            }
            if (pp.getColorTunerHueBlue() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE,
                        pp.getColorTunerHueBlue());
            }
            if (pp.getColorTunerHueCyan() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN,
                        pp.getColorTunerHueCyan());
            }
            if (pp.getColorTunerHueMagenta() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA,
                        pp.getColorTunerHueMagenta());
            }
            if (pp.getColorTunerHueYellow() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW,
                        pp.getColorTunerHueYellow());
            }
            if (pp.getColorTunerHueFlesh() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH,
                        pp.getColorTunerHueFlesh());
            }
            if (pp.getColorTunerSaturationRed() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED,
                        pp.getColorTunerSaturationRed());
            }
            if (pp.getColorTunerSaturationGreen() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN,
                        pp.getColorTunerSaturationGreen());
            }
            if (pp.getColorTunerSaturationBlue() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE,
                        pp.getColorTunerSaturationBlue());
            }
            if (pp.getColorTunerSaturationCyan() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN,
                        pp.getColorTunerSaturationCyan());
            }
            if (pp.getColorTunerSaturationMagenta() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA,
                        pp.getColorTunerSaturationMagenta());
            }
            if (pp.getColorTunerSaturationYellow() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW,
                        pp.getColorTunerSaturationYellow());
            }
            if (pp.getColorTunerSaturationFlesh() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH,
                        pp.getColorTunerSaturationFlesh());
            }
            if (pp.getColorTunerLuminanceRed() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED,
                        pp.getColorTunerLuminanceRed());
            }
            if (pp.getColorTunerLuminanceGreen() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN,
                        pp.getColorTunerLuminanceGreen());
            }
            if (pp.getColorTunerLuminanceBlue() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE,
                        pp.getColorTunerLuminanceBlue());
            }
            if (pp.getColorTunerLuminanceCyan() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN,
                        pp.getColorTunerLuminanceCyan());
            }
            if (pp.getColorTunerLuminanceMagenta() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA,
                        pp.getColorTunerLuminanceMagenta());
            }
            if (pp.getColorTunerLuminanceYellow() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW,
                        pp.getColorTunerLuminanceYellow());
            }
            if (pp.getColorTunerLuminanceFlesh() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH,
                        pp.getColorTunerLuminanceFlesh());
            }
            if (pp.getPictureQualityEventType() > -1) {
                bundle.putInt(PictureQuality.PARAMETER_PICTURE_QUALITY_EVENT_TYPE,
                        pp.getPictureQualityEventType());
            }
            if (pp.getFilmMode()) {
                bundle.putBoolean(PictureQuality.PARAMETER_FILM_MODE, true);
            }
            if (pp.getBlueStretch()) {
                bundle.putBoolean(PictureQuality.PARAMETER_BLUE_STRETCH, true);
            }
            if (pp.getColorTune()) {
                bundle.putBoolean(PictureQuality.PARAMETER_COLOR_TUNE, true);
            }
            if (pp.getGlobeDimming()) {
                bundle.putBoolean(PictureQuality.PARAMETER_GLOBAL_DIMMING, true);
            }
            if (pp.getAutoPictureQualityEnabled()) {
                bundle.putBoolean(PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED, true);
            }
            if (pp.getAutoSuperResolutionEnabled()) {
                bundle.putBoolean(PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED, true);
            }
            if (pp.getGamutMapping()) {
                bundle.putBoolean(PictureQuality.PARAMETER_GAMUT_MAPPING, true);
            }
            if (pp.getPcMode()) {
                bundle.putBoolean(PictureQuality.PARAMETER_PC_MODE, true);
            }
            if (pp.getLowLatency()) {
                bundle.putBoolean(PictureQuality.PARAMETER_LOW_LATENCY, true);
            }
            if (pp.getVrr()) {
                bundle.putBoolean(PictureQuality.PARAMETER_VRR, true);
            }
            if (pp.getCvrr()) {
                bundle.putBoolean(PictureQuality.PARAMETER_CVRR, true);
            }
            if (pp.getPanelInitMaxLuminceValid()) {
                bundle.putBoolean(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID, true);
            }
            if (pp.getColorTunerSwitch()) {
                bundle.putBoolean(PictureQuality.PARAMETER_COLOR_TUNER_SWITCH, true);
            }
            if (pp.getElevenPointRed() != null) {
                bundle.putIntArray(PictureQuality.PARAMETER_ELEVEN_POINT_RED,
                        pp.getElevenPointRed());
            }
            if (pp.getElevenPointBlue() != null) {
                bundle.putIntArray(PictureQuality.PARAMETER_ELEVEN_POINT_RED,
                        pp.getElevenPointBlue());
            }
            if (pp.getElevenPointGreen() != null) {
                bundle.putIntArray(PictureQuality.PARAMETER_ELEVEN_POINT_RED,
                        pp.getElevenPointGreen());
            }
        }
        return bundle;
    }

    /**
     * Convert PersistableBundle to PictureParameter List.
     */
    public static PictureParameter[] convertPersistableBundleToPictureParameterList(
            PersistableBundle params) {
        if (params == null) {
            return null;
        }
        List<PictureParameter> pictureParams = new ArrayList<>();
        if (params.containsKey(PictureQuality.PARAMETER_BRIGHTNESS)) {
            pictureParams.add(PictureParameter.brightness(params.getLong(
                    PictureQuality.PARAMETER_BRIGHTNESS)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_CONTRAST)) {
            pictureParams.add(PictureParameter.contrast(params.getInt(
                    PictureQuality.PARAMETER_CONTRAST)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_SHARPNESS)) {
            pictureParams.add(PictureParameter.sharpness(params.getInt(
                    PictureQuality.PARAMETER_SHARPNESS)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_SATURATION)) {
            pictureParams.add(PictureParameter.saturation(params.getInt(
                    PictureQuality.PARAMETER_SATURATION)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_HUE)) {
            pictureParams.add(PictureParameter.hue(params.getInt(
                    PictureQuality.PARAMETER_HUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS)) {
            pictureParams.add(PictureParameter.colorTunerBrightness(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION)) {
            pictureParams.add(PictureParameter.colorTunerSaturation(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE)) {
            pictureParams.add(PictureParameter.colorTunerHue(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET)) {
            pictureParams.add(PictureParameter.colorTunerRedOffset(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET)) {
            pictureParams.add(PictureParameter.colorTunerGreenOffset(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET)) {
            pictureParams.add(PictureParameter.colorTunerBlueOffset(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)) {
            pictureParams.add(PictureParameter.colorTunerRedGain(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)) {
            pictureParams.add(PictureParameter.colorTunerGreenGain(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)) {
            pictureParams.add(PictureParameter.colorTunerBlueGain(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_NOISE_REDUCTION)) {
            pictureParams.add(PictureParameter.noiseReduction(
                    (byte) params.getInt(PictureQuality.PARAMETER_NOISE_REDUCTION)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION)) {
            pictureParams.add(PictureParameter.mpegNoiseReduction(
                    (byte) params.getInt(PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_FLESH_TONE)) {
            pictureParams.add(PictureParameter.fleshTone(
                    (byte) params.getInt(PictureQuality.PARAMETER_FLESH_TONE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_DECONTOUR)) {
            pictureParams.add(PictureParameter.deContour(
                    (byte) params.getInt(PictureQuality.PARAMETER_DECONTOUR)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL)) {
            pictureParams.add(PictureParameter.dynamicLumaControl(
                    (byte) params.getInt(PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_FILM_MODE)) {
            pictureParams.add(PictureParameter.filmMode(params.getBoolean(
                    PictureQuality.PARAMETER_FILM_MODE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_BLUE_STRETCH)) {
            pictureParams.add(PictureParameter.blueStretch(params.getBoolean(
                    PictureQuality.PARAMETER_BLUE_STRETCH)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNE)) {
            pictureParams.add(PictureParameter.colorTune(params.getBoolean(
                    PictureQuality.PARAMETER_COLOR_TUNE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE)) {
            pictureParams.add(PictureParameter.colorTemperature(
                    (byte) params.getInt(
                            PictureQuality.PARAMETER_COLOR_TEMPERATURE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_GLOBAL_DIMMING)) {
            pictureParams.add(PictureParameter.globeDimming(params.getBoolean(
                    PictureQuality.PARAMETER_GLOBAL_DIMMING)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED)) {
            pictureParams.add(PictureParameter.autoPictureQualityEnabled(params.getBoolean(
                    PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED)) {
            pictureParams.add(PictureParameter.autoSuperResolutionEnabled(params.getBoolean(
                    PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)) {
            pictureParams.add(PictureParameter.colorTemperatureRedGain(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)) {
            pictureParams.add(PictureParameter.colorTemperatureGreenGain(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)) {
            pictureParams.add(PictureParameter.colorTemperatureBlueGain(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_LEVEL_RANGE)) {
            pictureParams.add(PictureParameter.levelRange(
                    (byte) params.getInt(PictureQuality.PARAMETER_LEVEL_RANGE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_GAMUT_MAPPING)) {
            pictureParams.add(PictureParameter.gamutMapping(params.getBoolean(
                    PictureQuality.PARAMETER_GAMUT_MAPPING)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_PC_MODE)) {
            pictureParams.add(PictureParameter.pcMode(params.getBoolean(
                    PictureQuality.PARAMETER_PC_MODE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_LOW_LATENCY)) {
            pictureParams.add(PictureParameter.lowLatency(params.getBoolean(
                    PictureQuality.PARAMETER_LOW_LATENCY)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_VRR)) {
            pictureParams.add(PictureParameter.vrr(params.getBoolean(
                    PictureQuality.PARAMETER_VRR)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_CVRR)) {
            pictureParams.add(PictureParameter.cvrr(params.getBoolean(
                    PictureQuality.PARAMETER_CVRR)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_HDMI_RGB_RANGE)) {
            pictureParams.add(PictureParameter.hdmiRgbRange(
                    (byte) params.getInt(PictureQuality.PARAMETER_HDMI_RGB_RANGE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_SPACE)) {
            pictureParams.add(PictureParameter.colorSpace(
                    (byte) params.getInt(PictureQuality.PARAMETER_COLOR_SPACE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS)) {
            pictureParams.add(PictureParameter.panelInitMaxLuminceNits(
                    params.getInt(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID)) {
            pictureParams.add(PictureParameter.panelInitMaxLuminceValid(
                    params.getBoolean(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_GAMMA)) {
            pictureParams.add(PictureParameter.gamma(
                    (byte) params.getInt(PictureQuality.PARAMETER_GAMMA)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET)) {
            pictureParams.add(PictureParameter.colorTemperatureRedOffset(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET)) {
            pictureParams.add(PictureParameter.colorTemperatureGreenOffset(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET)) {
            pictureParams.add(PictureParameter.colorTemperatureBlueOffset(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_ELEVEN_POINT_RED)) {
            pictureParams.add(PictureParameter.elevenPointRed(params.getIntArray(
                    PictureQuality.PARAMETER_ELEVEN_POINT_RED)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_ELEVEN_POINT_GREEN)) {
            pictureParams.add(PictureParameter.elevenPointGreen(params.getIntArray(
                    PictureQuality.PARAMETER_ELEVEN_POINT_GREEN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_ELEVEN_POINT_BLUE)) {
            pictureParams.add(PictureParameter.elevenPointBlue(params.getIntArray(
                    PictureQuality.PARAMETER_ELEVEN_POINT_BLUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_LOW_BLUE_LIGHT)) {
            pictureParams.add(PictureParameter.lowBlueLight(
                    (byte) params.getInt(PictureQuality.PARAMETER_LOW_BLUE_LIGHT)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_LD_MODE)) {
            pictureParams.add(PictureParameter.LdMode(
                    (byte) params.getInt(PictureQuality.PARAMETER_LD_MODE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_RED_GAIN)) {
            pictureParams.add(PictureParameter.osdRedGain(params.getInt(
                    PictureQuality.PARAMETER_OSD_RED_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_GREEN_GAIN)) {
            pictureParams.add(PictureParameter.osdGreenGain(params.getInt(
                    PictureQuality.PARAMETER_OSD_GREEN_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_BLUE_GAIN)) {
            pictureParams.add(PictureParameter.osdBlueGain(params.getInt(
                    PictureQuality.PARAMETER_OSD_BLUE_GAIN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_RED_OFFSET)) {
            pictureParams.add(PictureParameter.osdRedOffset(params.getInt(
                    PictureQuality.PARAMETER_OSD_RED_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_GREEN_OFFSET)) {
            pictureParams.add(PictureParameter.osdGreenOffset(params.getInt(
                    PictureQuality.PARAMETER_OSD_GREEN_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_BLUE_OFFSET)) {
            pictureParams.add(PictureParameter.osdBlueOffset(params.getInt(
                    PictureQuality.PARAMETER_OSD_BLUE_OFFSET)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_HUE)) {
            pictureParams.add(PictureParameter.osdHue(params.getInt(
                    PictureQuality.PARAMETER_OSD_HUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_SATURATION)) {
            pictureParams.add(PictureParameter.osdSaturation(params.getInt(
                    PictureQuality.PARAMETER_OSD_SATURATION)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_OSD_CONTRAST)) {
            pictureParams.add(PictureParameter.osdContrast(params.getInt(
                    PictureQuality.PARAMETER_OSD_CONTRAST)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SWITCH)) {
            pictureParams.add(PictureParameter.colorTunerSwitch(params.getBoolean(
                    PictureQuality.PARAMETER_COLOR_TUNER_SWITCH)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED)) {
            pictureParams.add(PictureParameter.colorTunerHueRed(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN)) {
            pictureParams.add(PictureParameter.colorTunerHueGreen(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE)) {
            pictureParams.add(PictureParameter.colorTunerHueBlue(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN)) {
            pictureParams.add(PictureParameter.colorTunerHueCyan(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA)) {
            pictureParams.add(PictureParameter.colorTunerHueMagenta(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW)) {
            pictureParams.add(PictureParameter.colorTunerHueYellow(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH)) {
            pictureParams.add(PictureParameter.colorTunerHueFlesh(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED)) {
            pictureParams.add(PictureParameter.colorTunerSaturationRed(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN)) {
            pictureParams.add(PictureParameter.colorTunerSaturationGreen(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE)) {
            pictureParams.add(PictureParameter.colorTunerSaturationBlue(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN)) {
            pictureParams.add(PictureParameter.colorTunerSaturationCyan(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA)) {
            pictureParams.add(PictureParameter.colorTunerSaturationMagenta(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW)) {
            pictureParams.add(PictureParameter.colorTunerSaturationYellow(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH)) {
            pictureParams.add(PictureParameter.colorTunerSaturationFlesh(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceRed(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceGreen(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceBlue(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceCyan(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceMagenta(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceYellow(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH)) {
            pictureParams.add(PictureParameter.colorTunerLuminanceFlesh(params.getInt(
                    PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH)));
        }
        if (params.containsKey(PictureQuality.PARAMETER_PICTURE_QUALITY_EVENT_TYPE)) {
            pictureParams.add(PictureParameter.pictureQualityEventType(
                    (byte) params.getInt(PictureQuality.PARAMETER_PICTURE_QUALITY_EVENT_TYPE)));
        }
        return  (PictureParameter[]) pictureParams.toArray();
    }

    /**
     * Convert SoundParameter List to PersistableBundle.
     */
    public static PersistableBundle convertSoundParameterListToPersistableBundle(
            SoundParameter[] parameters) {
        if (parameters == null) {
            return null;
        }

        PersistableBundle bundle = new PersistableBundle();
        for (SoundParameter sp: parameters) {
            if (sp.getSurroundSoundEnabled()) {
                bundle.putBoolean(SoundQuality.PARAMETER_SURROUND_SOUND, true);
            }
            if (sp.getSpeakersEnabled()) {
                bundle.putBoolean(SoundQuality.PARAMETER_SPEAKERS, true);
            }
            if (sp.getAutoVolumeControl()) {
                bundle.putBoolean(SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL, true);
            }
            if (sp.getDtsDrc()) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_DRC, true);
            }
            if (sp.getSurroundSoundEnabled()) {
                bundle.putBoolean(SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS, true);
            }
            if (sp.getEnhancedAudioReturnChannelEnabled()) {
                bundle.putBoolean(SoundQuality.PARAMETER_EARC, true);
            }
            if (sp.getBalance() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_BALANCE, sp.getBalance());
            }
            if (sp.getBass() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_BASS, sp.getBass());
            }
            if (sp.getTreble() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_TREBLE, sp.getTreble());
            }
            if (sp.getSpeakersDelayMs() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS,
                        sp.getSpeakersDelayMs());
            }
            if (sp.getDownmixMode() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_DOWN_MIX_MODE, sp.getDownmixMode());
            }
            if (sp.getSoundStyle() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_SOUND_STYLE, sp.getSoundStyle());
            }
            if (sp.getDigitalOutput() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE,
                        sp.getDigitalOutput());
            }
            if (sp.getDolbyDialogueEnhancer() > -1) {
                bundle.putInt(SoundQuality.PARAMETER_DIALOGUE_ENHANCER,
                        sp.getDolbyDialogueEnhancer());
            }
            if (sp.getDtsVirtualX().tbHdx) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TBHDX, true);
            }
            if (sp.getDtsVirtualX().limiter) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_LIMITER, true);
            }
            if (sp.getDtsVirtualX().truSurroundX) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TRU_SURROUND_X, true);
            }
            if (sp.getDtsVirtualX().truVolumeHd) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TRU_VOLUME_HD, true);
            }
            if (sp.getDtsVirtualX().dialogClarity) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_DIALOG_CLARITY, true);
            }
            if (sp.getDtsVirtualX().definition) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_DEFINITION, true);
            }
            if (sp.getDtsVirtualX().height) {
                bundle.putBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_HEIGHT, true);
            }
            if (sp.getDolbyAudioProcessing().soundMode > -1) {
                bundle.putInt(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_SOUND_MODE,
                        sp.getDolbyAudioProcessing().soundMode);
            }
            if (sp.getDolbyAudioProcessing().volumeLeveler) {
                bundle.putBoolean(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_VOLUME_LEVELER,
                        true);
            }
            if (sp.getDolbyAudioProcessing().surroundVirtualizer) {
                bundle.putBoolean(
                        SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_SURROUND_VIRTUALIZER,
                        true);
            }
            if (sp.getDolbyAudioProcessing().dolbyAtmos) {
                bundle.putBoolean(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_DOLBY_ATMOS,
                        true);
            }
        }
        return bundle;
    }
    /**
     * Convert PersistableBundle to SoundParameter List.
     */
    public static SoundParameter[] convertPersistableBundleToSoundParameterList(
            PersistableBundle params) {
        if (params == null) {
            return null;
        }
        //TODO: set EqualizerDetail
        List<SoundParameter> soundParams = new ArrayList<>();
        if (params.containsKey(SoundQuality.PARAMETER_BALANCE)) {
            soundParams.add(SoundParameter.balance(params.getInt(
                    SoundQuality.PARAMETER_BALANCE)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_BASS)) {
            soundParams.add(SoundParameter.bass(params.getInt(SoundQuality.PARAMETER_BASS)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_TREBLE)) {
            soundParams.add(SoundParameter.treble(params.getInt(
                    SoundQuality.PARAMETER_TREBLE)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_SURROUND_SOUND)) {
            soundParams.add(SoundParameter.surroundSoundEnabled(params.getBoolean(
                    SoundQuality.PARAMETER_SURROUND_SOUND)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_SPEAKERS)) {
            soundParams.add(SoundParameter.speakersEnabled(params.getBoolean(
                    SoundQuality.PARAMETER_SPEAKERS)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS)) {
            soundParams.add(SoundParameter.speakersDelayMs(params.getInt(
                    SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL)) {
            soundParams.add(SoundParameter.autoVolumeControl(params.getBoolean(
                    SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_DTS_DRC)) {
            soundParams.add(SoundParameter.dtsDrc(params.getBoolean(
                    SoundQuality.PARAMETER_DTS_DRC)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS)) {
            soundParams.add(SoundParameter.surroundSoundEnabled(params.getBoolean(
                    SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_EARC)) {
            soundParams.add(SoundParameter.enhancedAudioReturnChannelEnabled(params.getBoolean(
                    SoundQuality.PARAMETER_EARC)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_DOWN_MIX_MODE)) {
            soundParams.add(SoundParameter.downmixMode((byte) params.getInt(
                    SoundQuality.PARAMETER_DOWN_MIX_MODE)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_SOUND_STYLE)) {
            soundParams.add(SoundParameter.soundStyle((byte) params.getInt(
                    SoundQuality.PARAMETER_SOUND_STYLE)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE)) {
            soundParams.add(SoundParameter.digitalOutput((byte) params.getInt(
                    SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE)));
        }
        if (params.containsKey(SoundQuality.PARAMETER_DIALOGUE_ENHANCER)) {
            soundParams.add(SoundParameter.dolbyDialogueEnhancer((byte) params.getInt(
                    SoundQuality.PARAMETER_DIALOGUE_ENHANCER)));
        }

        DolbyAudioProcessing dab = new DolbyAudioProcessing();
        dab.soundMode =
                (byte) params.getInt(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_SOUND_MODE);
        dab.volumeLeveler =
                params.getBoolean(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_VOLUME_LEVELER);
        dab.surroundVirtualizer = params.getBoolean(
                SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_SURROUND_VIRTUALIZER);
        dab.dolbyAtmos =
                params.getBoolean(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING_DOLBY_ATMOS);
        soundParams.add(SoundParameter.dolbyAudioProcessing(dab));

        DtsVirtualX dts = new DtsVirtualX();
        dts.tbHdx = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TBHDX);
        dts.limiter = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_LIMITER);
        dts.truSurroundX = params.getBoolean(
                SoundQuality.PARAMETER_DTS_VIRTUAL_X_TRU_SURROUND_X);
        dts.truVolumeHd = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_TRU_VOLUME_HD);
        dts.dialogClarity = params.getBoolean(
                SoundQuality.PARAMETER_DTS_VIRTUAL_X_DIALOG_CLARITY);
        dts.definition = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_DEFINITION);
        dts.height = params.getBoolean(SoundQuality.PARAMETER_DTS_VIRTUAL_X_HEIGHT);
        soundParams.add(SoundParameter.dtsVirtualX(dts));

        return  (SoundParameter[]) soundParams.toArray();
    }

    private static String persistableBundleToJson(PersistableBundle bundle) {
        JSONObject json = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            try {
                if (value instanceof String) {
                    json.put(key, bundle.getString(key));
                } else if (value instanceof Integer) {
                    json.put(key, bundle.getInt(key));
                } else if (value instanceof Long) {
                    json.put(key, bundle.getLong(key));
                } else if (value instanceof Boolean) {
                    json.put(key, bundle.getBoolean(key));
                } else if (value instanceof Double) {
                    json.put(key, bundle.getDouble(key));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Unable to serialize ", e);
            }
        }
        return json.toString();
    }

    private static PersistableBundle jsonToPersistableBundle(String jsonString) {
        PersistableBundle bundle = new PersistableBundle();
        if (jsonString != null) {
            JSONObject jsonObject = null;
            try {
                jsonObject = new JSONObject(jsonString);

                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = jsonObject.get(key);

                    if (value instanceof String) {
                        bundle.putString(key, (String) value);
                    } else if (value instanceof Integer) {
                        bundle.putInt(key, (Integer) value);
                    } else if (value instanceof Boolean) {
                        bundle.putBoolean(key, (Boolean) value);
                    } else if (value instanceof Double) {
                        bundle.putDouble(key, (Double) value);
                    } else if (value instanceof Long) {
                        bundle.putLong(key, (Long) value);
                    }
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return bundle;
    }

    /**
     * Populates the given map with the ID and generated UUID.
     */
    public static void populateTempIdMap(BiMap<Long, String> map, Long id) {
        if (id != null && map.getValue(id) == null) {
            String uuid;
            int attempts = 0;
            while (attempts < MAX_UUID_GENERATION_ATTEMPTS) {
                uuid = UUID.randomUUID().toString();
                if (map.getKey(uuid) == null) {
                    map.put(id, uuid);
                    return;
                }
                attempts++;
            }
        }
    }

    /**
     * Get Content Values.
     */
    public static ContentValues getContentValues(Long dbId, Integer profileType, String name,
            String packageName, String inputId, PersistableBundle params) {
        ContentValues values = new ContentValues();
        if (dbId != null) {
            values.put(BaseParameters.PARAMETER_ID, dbId);
        }
        if (profileType != null) {
            values.put(BaseParameters.PARAMETER_TYPE, profileType);
        }
        if (name != null) {
            values.put(BaseParameters.PARAMETER_NAME, name);
        }
        if (packageName != null) {
            values.put(BaseParameters.PARAMETER_PACKAGE, packageName);
        }
        if (inputId != null) {
            values.put(BaseParameters.PARAMETER_INPUT_ID, inputId);
        }
        if (params != null) {
            values.put(SETTINGS, persistableBundleToJson(params));
        }
        return values;
    }

    /**
     * Get Media Profile Columns.
     */
    public static String[] getMediaProfileColumns(boolean includeParams) {
        ArrayList<String> columns = new ArrayList<>(Arrays.asList(
                BaseParameters.PARAMETER_ID,
                BaseParameters.PARAMETER_TYPE,
                BaseParameters.PARAMETER_NAME,
                BaseParameters.PARAMETER_INPUT_ID,
                BaseParameters.PARAMETER_PACKAGE)
        );
        if (includeParams) {
            columns.add(SETTINGS);
        }
        return columns.toArray(new String[0]);
    }

    /**
     * Convert cursor to Picture Profile with temporary UUID.
     */
    public static PictureProfile convertCursorToPictureProfileWithTempId(Cursor cursor,
            BiMap<Long, String> map) {
        return new PictureProfile(
                getTempId(map, cursor),
                getType(cursor),
                getName(cursor),
                getInputId(cursor),
                getPackageName(cursor),
                jsonToPersistableBundle(getSettingsString(cursor)),
                PictureProfileHandle.NONE
        );
    }

    /**
     * Convert cursor to Sound Profile with temporary UUID.
     */
    public static SoundProfile convertCursorToSoundProfileWithTempId(Cursor cursor, BiMap<Long,
            String> map) {
        return new SoundProfile(
                getTempId(map, cursor),
                getType(cursor),
                getName(cursor),
                getInputId(cursor),
                getPackageName(cursor),
                jsonToPersistableBundle(getSettingsString(cursor)),
                SoundProfileHandle.NONE
        );
    }

    /**
     * Convert parameter to byte array.
     */
    public static byte[] convertParameterToByteArray(List<String> names) {
        /**
         * TODO Add following to ParameterName & add conversion here.
         * - PICTURE_QUALITY_EVENT_TYPE
         * - PANEL_INIT_MAX_LUMINCE_NITS
         */

        HashSet<String> nameMap = new HashSet<>(names);

        List<Byte> bytes = new ArrayList<>();
        // Picture Quality parameters
        if (nameMap.contains(PictureQuality.PARAMETER_BRIGHTNESS)) {
            bytes.add(ParameterName.BRIGHTNESS);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_BRIGHTNESS)) {
            bytes.add(ParameterName.BRIGHTNESS);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_CONTRAST)) {
            bytes.add(ParameterName.CONTRAST);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_SHARPNESS)) {
            bytes.add(ParameterName.SHARPNESS);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_SATURATION)) {
            bytes.add(ParameterName.SATURATION);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_HUE)) {
            bytes.add(ParameterName.HUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_BRIGHTNESS)) {
            bytes.add(ParameterName.BRIGHTNESS);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS)) {
            bytes.add(ParameterName.COLOR_TUNER_BRIGHTNESS);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_SATURATION)) {
            bytes.add(ParameterName.SATURATION);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_HUE)) {
            bytes.add(ParameterName.HUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET)) {
            bytes.add(ParameterName.COLOR_TUNER_RED_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET)) {
            bytes.add(ParameterName.COLOR_TUNER_GREEN_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET)) {
            bytes.add(ParameterName.COLOR_TUNER_BLUE_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN)) {
            bytes.add(ParameterName.COLOR_TUNER_RED_GAIN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN)) {
            bytes.add(ParameterName.COLOR_TUNER_GREEN_GAIN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN)) {
            bytes.add(ParameterName.COLOR_TUNER_BLUE_GAIN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_NOISE_REDUCTION)) {
            bytes.add(ParameterName.NOISE_REDUCTION);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION)) {
            bytes.add(ParameterName.MPEG_NOISE_REDUCTION);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_FLESH_TONE)) {
            bytes.add(ParameterName.FLASH_TONE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_DECONTOUR)) {
            bytes.add(ParameterName.DE_CONTOUR);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL)) {
            bytes.add(ParameterName.DYNAMIC_LUMA_CONTROL);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_FILM_MODE)) {
            bytes.add(ParameterName.FILM_MODE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_BLUE_STRETCH)) {
            bytes.add(ParameterName.BLUE_STRETCH);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNE)) {
            bytes.add(ParameterName.COLOR_TUNE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TEMPERATURE)) {
            bytes.add(ParameterName.COLOR_TEMPERATURE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_GLOBAL_DIMMING)) {
            bytes.add(ParameterName.GLOBE_DIMMING);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED)) {
            bytes.add(ParameterName.AUTO_PICTUREQUALITY_ENABLED);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED)) {
            bytes.add(ParameterName.AUTO_SUPER_RESOLUTION_ENABLED);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_LEVEL_RANGE)) {
            bytes.add(ParameterName.LEVEL_RANGE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_GAMUT_MAPPING)) {
            bytes.add(ParameterName.GAMUT_MAPPING);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_PC_MODE)) {
            bytes.add(ParameterName.PC_MODE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_LOW_LATENCY)) {
            bytes.add(ParameterName.LOW_LATENCY);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_VRR)) {
            bytes.add(ParameterName.VRR);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_CVRR)) {
            bytes.add(ParameterName.CVRR);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_HDMI_RGB_RANGE)) {
            bytes.add(ParameterName.HDMI_RGB_RANGE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_SPACE)) {
            bytes.add(ParameterName.COLOR_SPACE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID)) {
            bytes.add(ParameterName.PANEL_INIT_MAX_LUMINCE_VALID);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_GAMMA)) {
            bytes.add(ParameterName.GAMMA);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET)) {
            bytes.add(ParameterName.COLOR_TEMPERATURE_RED_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET)) {
            bytes.add(ParameterName.COLOR_TEMPERATURE_GREEN_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET)) {
            bytes.add(ParameterName.COLOR_TEMPERATURE_BLUE_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_ELEVEN_POINT_RED)) {
            bytes.add(ParameterName.ELEVEN_POINT_RED);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_ELEVEN_POINT_GREEN)) {
            bytes.add(ParameterName.ELEVEN_POINT_GREEN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_ELEVEN_POINT_BLUE)) {
            bytes.add(ParameterName.ELEVEN_POINT_BLUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_LOW_BLUE_LIGHT)) {
            bytes.add(ParameterName.LOW_BLUE_LIGHT);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_LD_MODE)) {
            bytes.add(ParameterName.LD_MODE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_RED_GAIN)) {
            bytes.add(ParameterName.OSD_RED_GAIN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_GREEN_GAIN)) {
            bytes.add(ParameterName.OSD_GREEN_GAIN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_BLUE_GAIN)) {
            bytes.add(ParameterName.OSD_BLUE_GAIN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_RED_OFFSET)) {
            bytes.add(ParameterName.OSD_RED_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_GREEN_OFFSET)) {
            bytes.add(ParameterName.OSD_GREEN_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_BLUE_OFFSET)) {
            bytes.add(ParameterName.OSD_BLUE_OFFSET);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_HUE)) {
            bytes.add(ParameterName.OSD_HUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_SATURATION)) {
            bytes.add(ParameterName.OSD_SATURATION);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_OSD_CONTRAST)) {
            bytes.add(ParameterName.OSD_CONTRAST);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SWITCH)) {
            bytes.add(ParameterName.COLOR_TUNER_SWITCH);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_RED);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_GREEN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_BLUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_CYAN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_MAGENTA);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_YELLOW);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH)) {
            bytes.add(ParameterName.COLOR_TUNER_HUE_FLESH);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_RED);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_GREEN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_BLUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_CYAN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_MAGENTA);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_YELLOW);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH)) {
            bytes.add(ParameterName.COLOR_TUNER_SATURATION_FLESH);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_RED);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_GREEN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_BLUE);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_CYAN);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_MAGENTA);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_YELLOW);
        }
        if (nameMap.contains(PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH)) {
            bytes.add(ParameterName.COLOR_TUNER_LUMINANCE_FLESH);
        }

        // Sound Quality parameters
        if (nameMap.contains(SoundQuality.PARAMETER_BALANCE)) {
            bytes.add(ParameterName.BALANCE);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_BASS)) {
            bytes.add(ParameterName.BASS);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_TREBLE)) {
            bytes.add(ParameterName.TREBLE);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_SURROUND_SOUND)) {
            bytes.add(ParameterName.SURROUND_SOUND_ENABLED);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_EQUALIZER_DETAIL)) {
            bytes.add(ParameterName.EQUALIZER_DETAIL);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_SPEAKERS)) {
            bytes.add(ParameterName.SPEAKERS_ENABLED);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS)) {
            bytes.add(ParameterName.SPEAKERS_DELAY_MS);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_EARC)) {
            bytes.add(ParameterName.ENHANCED_AUDIO_RETURN_CHANNEL_ENABLED);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL)) {
            bytes.add(ParameterName.AUTO_VOLUME_CONTROL);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DOWN_MIX_MODE)) {
            bytes.add(ParameterName.DOWNMIX_MODE);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DTS_DRC)) {
            bytes.add(ParameterName.DTS_DRC);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING)) {
            bytes.add(ParameterName.DOLBY_AUDIO_PROCESSING);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DIALOGUE_ENHANCER)) {
            bytes.add(ParameterName.DOLBY_DIALOGUE_ENHANCER);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DTS_VIRTUAL_X)) {
            bytes.add(ParameterName.DTS_VIRTUAL_X);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS)) {
            bytes.add(ParameterName.DIGITAL_OUTPUT_DELAY_MS);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE)) {
            bytes.add(ParameterName.DIGITAL_OUTPUT);
        }
        if (nameMap.contains(SoundQuality.PARAMETER_SOUND_STYLE)) {
            bytes.add(ParameterName.SOUND_STYLE);
        }

        byte[] byteArray = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            byteArray[i] = bytes.get(i);
        }
        return byteArray;
    }

    /**
     * Get Parameter Name based on byte.
     */
    public static String getParameterName(byte pn) {
        Map<Byte, String> parameterNameMap = new HashMap<>();
        parameterNameMap.put(ParameterName.BRIGHTNESS, PictureQuality.PARAMETER_BRIGHTNESS);
        parameterNameMap.put(ParameterName.CONTRAST, PictureQuality.PARAMETER_CONTRAST);
        parameterNameMap.put(ParameterName.SHARPNESS, PictureQuality.PARAMETER_SHARPNESS);
        parameterNameMap.put(ParameterName.SATURATION, PictureQuality.PARAMETER_SATURATION);
        parameterNameMap.put(ParameterName.HUE, PictureQuality.PARAMETER_HUE);
        parameterNameMap.put(ParameterName.COLOR_TUNER_BRIGHTNESS,
                PictureQuality.PARAMETER_COLOR_TUNER_BRIGHTNESS);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE);
        parameterNameMap.put(ParameterName.COLOR_TUNER_RED_OFFSET,
                PictureQuality.PARAMETER_COLOR_TUNER_RED_OFFSET);
        parameterNameMap.put(ParameterName.COLOR_TUNER_GREEN_OFFSET,
                PictureQuality.PARAMETER_COLOR_TUNER_GREEN_OFFSET);
        parameterNameMap.put(ParameterName.COLOR_TUNER_BLUE_OFFSET,
                PictureQuality.PARAMETER_COLOR_TUNER_BLUE_OFFSET);
        parameterNameMap.put(ParameterName.COLOR_TUNER_RED_GAIN,
                PictureQuality.PARAMETER_COLOR_TUNER_RED_GAIN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_GREEN_GAIN,
                PictureQuality.PARAMETER_COLOR_TUNER_GREEN_GAIN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_BLUE_GAIN,
                PictureQuality.PARAMETER_COLOR_TUNER_BLUE_GAIN);
        parameterNameMap.put(ParameterName.NOISE_REDUCTION,
                PictureQuality.PARAMETER_NOISE_REDUCTION);
        parameterNameMap.put(ParameterName.MPEG_NOISE_REDUCTION,
                PictureQuality.PARAMETER_MPEG_NOISE_REDUCTION);
        parameterNameMap.put(ParameterName.FLASH_TONE, PictureQuality.PARAMETER_FLESH_TONE);
        parameterNameMap.put(ParameterName.DE_CONTOUR, PictureQuality.PARAMETER_DECONTOUR);
        parameterNameMap.put(ParameterName.DYNAMIC_LUMA_CONTROL,
                PictureQuality.PARAMETER_DYNAMIC_LUMA_CONTROL);
        parameterNameMap.put(ParameterName.FILM_MODE,
                PictureQuality.PARAMETER_FILM_MODE);
        parameterNameMap.put(ParameterName.BLACK_STRETCH,
                PictureQuality.PARAMETER_BLACK_STRETCH);
        parameterNameMap.put(ParameterName.BLUE_STRETCH,
                PictureQuality.PARAMETER_BLUE_STRETCH);
        parameterNameMap.put(ParameterName.COLOR_TUNE,
                PictureQuality.PARAMETER_COLOR_TUNE);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE);
        parameterNameMap.put(ParameterName.GLOBE_DIMMING,
                PictureQuality.PARAMETER_GLOBAL_DIMMING);
        parameterNameMap.put(ParameterName.AUTO_PICTUREQUALITY_ENABLED,
                PictureQuality.PARAMETER_AUTO_PICTURE_QUALITY_ENABLED);
        parameterNameMap.put(ParameterName.AUTO_SUPER_RESOLUTION_ENABLED,
                PictureQuality.PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED);
        parameterNameMap.put(ParameterName.LEVEL_RANGE, PictureQuality.PARAMETER_LEVEL_RANGE);
        parameterNameMap.put(ParameterName.GAMUT_MAPPING,
                PictureQuality.PARAMETER_GAMUT_MAPPING);
        parameterNameMap.put(ParameterName.PC_MODE, PictureQuality.PARAMETER_PC_MODE);
        parameterNameMap.put(ParameterName.LOW_LATENCY, PictureQuality.PARAMETER_LOW_LATENCY);
        parameterNameMap.put(ParameterName.VRR, PictureQuality.PARAMETER_VRR);
        parameterNameMap.put(ParameterName.CVRR, PictureQuality.PARAMETER_CVRR);
        parameterNameMap.put(ParameterName.HDMI_RGB_RANGE,
                PictureQuality.PARAMETER_HDMI_RGB_RANGE);
        parameterNameMap.put(ParameterName.COLOR_SPACE, PictureQuality.PARAMETER_COLOR_SPACE);
        parameterNameMap.put(ParameterName.PANEL_INIT_MAX_LUMINCE_VALID,
                PictureQuality.PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID);
        parameterNameMap.put(ParameterName.GAMMA, PictureQuality.PARAMETER_GAMMA);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE_RED_GAIN,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_GAIN);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE_GREEN_GAIN,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_GAIN);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE_BLUE_GAIN,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_GAIN);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE_RED_OFFSET,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE_RED_OFFSET);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE_GREEN_OFFSET,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET);
        parameterNameMap.put(ParameterName.COLOR_TEMPERATURE_BLUE_OFFSET,
                PictureQuality.PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET);
        parameterNameMap.put(ParameterName.ELEVEN_POINT_RED,
                PictureQuality.PARAMETER_ELEVEN_POINT_RED);
        parameterNameMap.put(ParameterName.ELEVEN_POINT_GREEN,
                PictureQuality.PARAMETER_ELEVEN_POINT_GREEN);
        parameterNameMap.put(ParameterName.ELEVEN_POINT_BLUE,
                PictureQuality.PARAMETER_ELEVEN_POINT_BLUE);
        parameterNameMap.put(ParameterName.LOW_BLUE_LIGHT,
                PictureQuality.PARAMETER_LOW_BLUE_LIGHT);
        parameterNameMap.put(ParameterName.LD_MODE, PictureQuality.PARAMETER_LD_MODE);
        parameterNameMap.put(ParameterName.OSD_RED_GAIN, PictureQuality.PARAMETER_OSD_RED_GAIN);
        parameterNameMap.put(ParameterName.OSD_GREEN_GAIN,
                PictureQuality.PARAMETER_OSD_GREEN_GAIN);
        parameterNameMap.put(ParameterName.OSD_BLUE_GAIN,
                PictureQuality.PARAMETER_OSD_BLUE_GAIN);
        parameterNameMap.put(ParameterName.OSD_RED_OFFSET,
                PictureQuality.PARAMETER_OSD_RED_OFFSET);
        parameterNameMap.put(ParameterName.OSD_GREEN_OFFSET,
                PictureQuality.PARAMETER_OSD_GREEN_OFFSET);
        parameterNameMap.put(ParameterName.OSD_BLUE_OFFSET,
                PictureQuality.PARAMETER_OSD_BLUE_OFFSET);
        parameterNameMap.put(ParameterName.OSD_HUE, PictureQuality.PARAMETER_OSD_HUE);
        parameterNameMap.put(ParameterName.OSD_SATURATION,
                PictureQuality.PARAMETER_OSD_SATURATION);
        parameterNameMap.put(ParameterName.OSD_CONTRAST,
                PictureQuality.PARAMETER_OSD_CONTRAST);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SWITCH,
                PictureQuality.PARAMETER_COLOR_TUNER_SWITCH);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_RED,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_RED);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_GREEN,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_GREEN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_BLUE,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_BLUE);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_CYAN,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_CYAN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_MAGENTA,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_MAGENTA);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_YELLOW,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_YELLOW);
        parameterNameMap.put(ParameterName.COLOR_TUNER_HUE_FLESH,
                PictureQuality.PARAMETER_COLOR_TUNER_HUE_FLESH);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_RED,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_RED);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_GREEN,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_GREEN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_BLUE,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_BLUE);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_CYAN,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_CYAN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_MAGENTA,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_MAGENTA);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_YELLOW,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_YELLOW);
        parameterNameMap.put(ParameterName.COLOR_TUNER_SATURATION_FLESH,
                PictureQuality.PARAMETER_COLOR_TUNER_SATURATION_FLESH);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_RED,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_RED);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_GREEN,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_GREEN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_BLUE,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_BLUE);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_CYAN,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_CYAN);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_MAGENTA,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_YELLOW,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW);
        parameterNameMap.put(ParameterName.COLOR_TUNER_LUMINANCE_FLESH,
                PictureQuality.PARAMETER_COLOR_TUNER_LUMINANCE_FLESH);
        parameterNameMap.put(ParameterName.BALANCE, SoundQuality.PARAMETER_BALANCE);
        parameterNameMap.put(ParameterName.BASS, SoundQuality.PARAMETER_BASS);
        parameterNameMap.put(ParameterName.TREBLE, SoundQuality.PARAMETER_TREBLE);
        parameterNameMap.put(ParameterName.SURROUND_SOUND_ENABLED,
                SoundQuality.PARAMETER_SURROUND_SOUND);
        parameterNameMap.put(ParameterName.EQUALIZER_DETAIL,
                SoundQuality.PARAMETER_EQUALIZER_DETAIL);
        parameterNameMap.put(ParameterName.SPEAKERS_ENABLED, SoundQuality.PARAMETER_SPEAKERS);
        parameterNameMap.put(ParameterName.SPEAKERS_DELAY_MS,
                SoundQuality.PARAMETER_SPEAKERS_DELAY_MILLIS);
        parameterNameMap.put(ParameterName.ENHANCED_AUDIO_RETURN_CHANNEL_ENABLED,
                SoundQuality.PARAMETER_EARC);
        parameterNameMap.put(ParameterName.AUTO_VOLUME_CONTROL,
                SoundQuality.PARAMETER_AUTO_VOLUME_CONTROL);
        parameterNameMap.put(ParameterName.DOWNMIX_MODE, SoundQuality.PARAMETER_DOWN_MIX_MODE);
        parameterNameMap.put(ParameterName.DTS_DRC, SoundQuality.PARAMETER_DTS_DRC);
        parameterNameMap.put(ParameterName.DOLBY_AUDIO_PROCESSING,
                SoundQuality.PARAMETER_DOLBY_AUDIO_PROCESSING);
        parameterNameMap.put(ParameterName.DOLBY_DIALOGUE_ENHANCER,
                SoundQuality.PARAMETER_DIALOGUE_ENHANCER);
        parameterNameMap.put(ParameterName.DTS_VIRTUAL_X,
                SoundQuality.PARAMETER_DTS_VIRTUAL_X);
        parameterNameMap.put(ParameterName.DIGITAL_OUTPUT,
                SoundQuality.PARAMETER_DIGITAL_OUTPUT_MODE);
        parameterNameMap.put(ParameterName.DIGITAL_OUTPUT_DELAY_MS,
                SoundQuality.PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS);
        parameterNameMap.put(ParameterName.SOUND_STYLE, SoundQuality.PARAMETER_SOUND_STYLE);

        return parameterNameMap.get(pn);
    }

    private static String getTempId(BiMap<Long, String> map, Cursor cursor) {
        int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_ID);
        Long dbId = colIndex != -1 ? cursor.getLong(colIndex) : null;
        populateTempIdMap(map, dbId);
        return map.getValue(dbId);
    }

    private static int getType(Cursor cursor) {
        int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_TYPE);
        return colIndex != -1 ? cursor.getInt(colIndex) : 0;
    }

    private static String getName(Cursor cursor) {
        int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_NAME);
        return colIndex != -1 ? cursor.getString(colIndex) : null;
    }

    private static String getInputId(Cursor cursor) {
        int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_INPUT_ID);
        return colIndex != -1 ? cursor.getString(colIndex) : null;
    }

    private static String getPackageName(Cursor cursor) {
        int colIndex = cursor.getColumnIndex(BaseParameters.PARAMETER_PACKAGE);
        return colIndex != -1 ? cursor.getString(colIndex) : null;
    }

    private static String getSettingsString(Cursor cursor) {
        int colIndex = cursor.getColumnIndex(SETTINGS);
        return colIndex != -1 ? cursor.getString(colIndex) : null;
    }

    private MediaQualityUtils() {

    }
}
