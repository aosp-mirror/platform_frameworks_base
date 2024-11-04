/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.config;

import static com.android.server.display.config.HighBrightnessModeData.HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;

import android.annotation.Nullable;
import android.os.PowerManager;
import android.util.Spline;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayBrightnessState;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Brightness config for HDR content
 * <pre>
 * {@code
 * <displayConfiguration>
 *     ...
 *     <hdrBrightnessConfig>
 *         <brightnessMap>
 *             <point>
 *                 <first>500</first>
 *                 <second>0.3</second>
 *             </point>
 *             <point>
 *                 <first>1200</first>
 *                 <second>0.6</second>
 *             </point>
 *         </brightnessMap>
 *         <brightnessIncreaseDebounceMillis>1000</brightnessIncreaseDebounceMillis>
 *         <screenBrightnessRampIncrease>0.04</brightnessIncreaseDurationMillis>
 *         <brightnessDecreaseDebounceMillis>13000</brightnessDecreaseDebounceMillis>
 *         <screenBrightnessRampDecrease>0.03</brightnessDecreaseDurationMillis>
 *         <minimumHdrPercentOfScreenForNbm>0.2</minimumHdrPercentOfScreenForNbm>
 *         <minimumHdrPercentOfScreenForHbm>0.5</minimumHdrPercentOfScreenForHbm>
 *         <allowInLowPowerMode>true</allowInLowPowerMode>
 *         <sdrHdrRatioMap>
 *             <point>
 *                 <first>2.0</first>
 *                 <second>4.0</second>
 *             </point>
 *             <point>
 *                 <first>100</first>
 *                 <second>8.0</second>
 *             </point>
 *         </sdrHdrRatioMap>
 *     </hdrBrightnessConfig>
 *     ...
 * </displayConfiguration>
 * }
 * </pre>
 */
public class HdrBrightnessData {
    private static final String TAG = "HdrBrightnessData";

    /**
     * Lux to brightness map
     */
    public final Map<Float, Float> maxBrightnessLimits;

    /**
     * Debounce time for brightness increase
     */
    public final long brightnessIncreaseDebounceMillis;

    /**
     * Brightness increase animation speed
     */
    public final float screenBrightnessRampIncrease;

    /**
     * Debounce time for brightness decrease
     */
    public final long brightnessDecreaseDebounceMillis;

    /**
     * Brightness decrease animation speed
     */
    public final float screenBrightnessRampDecrease;

    /**
     * Brightness level at which we transition from normal to high-brightness
     */
    public final float hbmTransitionPoint;

    /**
     * Min Hdr layer size to start hdr brightness boost up to high brightness mode transition point
     */
    public final float minimumHdrPercentOfScreenForNbm;

    /**
     * Min Hdr layer size to start hdr brightness boost above high brightness mode transition point
     */
    public final float minimumHdrPercentOfScreenForHbm;

    /**
     * If Hdr brightness boost allowed in low power mode
     */
    public final boolean allowInLowPowerMode;

    /**
     * brightness to boost ratio spline
     */
    @Nullable
    public final Spline sdrToHdrRatioSpline;

    public final float highestHdrSdrRatio;

    @VisibleForTesting
    public HdrBrightnessData(Map<Float, Float> maxBrightnessLimits,
            long brightnessIncreaseDebounceMillis, float screenBrightnessRampIncrease,
            long brightnessDecreaseDebounceMillis, float screenBrightnessRampDecrease,
            float hbmTransitionPoint,
            float minimumHdrPercentOfScreenForNbm, float minimumHdrPercentOfScreenForHbm,
            boolean allowInLowPowerMode, @Nullable Spline sdrToHdrRatioSpline,
            float highestHdrSdrRatio) {
        this.maxBrightnessLimits = maxBrightnessLimits;
        this.brightnessIncreaseDebounceMillis = brightnessIncreaseDebounceMillis;
        this.screenBrightnessRampIncrease = screenBrightnessRampIncrease;
        this.brightnessDecreaseDebounceMillis = brightnessDecreaseDebounceMillis;
        this.screenBrightnessRampDecrease = screenBrightnessRampDecrease;
        this.hbmTransitionPoint = hbmTransitionPoint;
        this.minimumHdrPercentOfScreenForNbm = minimumHdrPercentOfScreenForNbm;
        this.minimumHdrPercentOfScreenForHbm = minimumHdrPercentOfScreenForHbm;
        this.allowInLowPowerMode = allowInLowPowerMode;
        this.sdrToHdrRatioSpline = sdrToHdrRatioSpline;
        this.highestHdrSdrRatio = highestHdrSdrRatio;
    }

    @Override
    public String toString() {
        return "HdrBrightnessData {"
                + "mMaxBrightnessLimits: " + maxBrightnessLimits
                + ", mBrightnessIncreaseDebounceMillis: " + brightnessIncreaseDebounceMillis
                + ", mScreenBrightnessRampIncrease: " + screenBrightnessRampIncrease
                + ", mBrightnessDecreaseDebounceMillis: " + brightnessDecreaseDebounceMillis
                + ", mScreenBrightnessRampDecrease: " + screenBrightnessRampDecrease
                + ", transitionPoint: " + hbmTransitionPoint
                + ", minimumHdrPercentOfScreenForNbm: " + minimumHdrPercentOfScreenForNbm
                + ", minimumHdrPercentOfScreenForHbm: " + minimumHdrPercentOfScreenForHbm
                + ", allowInLowPowerMode: " + allowInLowPowerMode
                + ", sdrToHdrRatioSpline: " + sdrToHdrRatioSpline
                + ", highestHdrSdrRatio: " + highestHdrSdrRatio
                + "} ";
    }

    /**
     * Loads HdrBrightnessData from DisplayConfiguration
     */
    @Nullable
    public static HdrBrightnessData loadConfig(DisplayConfiguration config,
            Function<HighBrightnessMode, Float> transitionPointProvider) {
        HighBrightnessMode hbmConfig = config.getHighBrightnessMode();
        HdrBrightnessConfig hdrConfig = config.getHdrBrightnessConfig();
        if (hdrConfig == null) {
            return getFallbackData(hbmConfig, transitionPointProvider);
        }

        List<NonNegativeFloatToFloatPoint> points = hdrConfig.getBrightnessMap().getPoint();
        Map<Float, Float> brightnessLimits = new HashMap<>();
        for (NonNegativeFloatToFloatPoint point: points) {
            brightnessLimits.put(point.getFirst().floatValue(), point.getSecond().floatValue());
        }

        float minHdrPercentForHbm = hdrConfig.getMinimumHdrPercentOfScreenForHbm() != null
                ? hdrConfig.getMinimumHdrPercentOfScreenForHbm().floatValue()
                : getFallbackHdrPercent(hbmConfig);

        float minHdrPercentForNbm = hdrConfig.getMinimumHdrPercentOfScreenForNbm() != null
                ? hdrConfig.getMinimumHdrPercentOfScreenForNbm().floatValue() : minHdrPercentForHbm;

        if (minHdrPercentForNbm > minHdrPercentForHbm) {
            throw new IllegalArgumentException(
                    "minHdrPercentForHbm should be >= minHdrPercentForNbm");
        }

        return new HdrBrightnessData(brightnessLimits,
                hdrConfig.getBrightnessIncreaseDebounceMillis().longValue(),
                hdrConfig.getScreenBrightnessRampIncrease().floatValue(),
                hdrConfig.getBrightnessDecreaseDebounceMillis().longValue(),
                hdrConfig.getScreenBrightnessRampDecrease().floatValue(),
                getTransitionPoint(hbmConfig, transitionPointProvider),
                minHdrPercentForNbm, minHdrPercentForHbm, hdrConfig.getAllowInLowPowerMode(),
                getSdrHdrRatioSpline(hdrConfig, config.getHighBrightnessMode()),
                getHighestSdrHdrRatio(hdrConfig, config.getHighBrightnessMode())
                );
    }

    private static float getTransitionPoint(@Nullable HighBrightnessMode hbm,
            Function<HighBrightnessMode, Float> transitionPointProvider) {
        if (hbm == null) {
            return PowerManager.BRIGHTNESS_MAX;
        } else {
            return transitionPointProvider.apply(hbm);
        }
    }

    @Nullable
    private static HdrBrightnessData getFallbackData(@Nullable HighBrightnessMode hbm,
            Function<HighBrightnessMode, Float> transitionPointProvider) {
        if (hbm == null) {
            return null;
        }
        float fallbackPercent = getFallbackHdrPercent(hbm);
        Spline fallbackSpline = getFallbackSdrHdrRatioSpline(hbm);
        return new HdrBrightnessData(Collections.emptyMap(),
                0, DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET,
                0, DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET,
                getTransitionPoint(hbm, transitionPointProvider),
                fallbackPercent, fallbackPercent, false, fallbackSpline,
                getFallbackHighestSdrHdrRatio(hbm));
    }

    private static float getFallbackHdrPercent(HighBrightnessMode hbm) {
        BigDecimal minHdrPctOfScreen = hbm != null ? hbm.getMinimumHdrPercentOfScreen_all() : null;
        return minHdrPctOfScreen != null ? minHdrPctOfScreen.floatValue()
                : HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;
    }

    @Nullable
    private static Spline getSdrHdrRatioSpline(HdrBrightnessConfig hdrConfig,
            HighBrightnessMode hbm) {
        NonNegativeFloatToFloatMap sdrHdrRatioMap = hdrConfig.getSdrHdrRatioMap();
        if (sdrHdrRatioMap == null) {
            return getFallbackSdrHdrRatioSpline(hbm);
        }
        return DisplayDeviceConfigUtils.createSpline(sdrHdrRatioMap.getPoint(),
                NonNegativeFloatToFloatPoint::getFirst, NonNegativeFloatToFloatPoint::getSecond);
    }

    @Nullable
    private static Spline getFallbackSdrHdrRatioSpline(HighBrightnessMode hbm) {
        SdrHdrRatioMap fallbackMap = hbm != null ? hbm.getSdrHdrRatioMap_all() : null;
        if (fallbackMap == null) {
            return null;
        }
        return DisplayDeviceConfigUtils.createSpline(fallbackMap.getPoint(),
                SdrHdrRatioPoint::getSdrNits, SdrHdrRatioPoint::getHdrRatio);
    }

    private static float getHighestSdrHdrRatio(HdrBrightnessConfig hdrConfig,
            HighBrightnessMode hbm) {
        NonNegativeFloatToFloatMap sdrHdrRatioMap = hdrConfig.getSdrHdrRatioMap();
        if (sdrHdrRatioMap == null) {
            return getFallbackHighestSdrHdrRatio(hbm);
        }
        return DisplayDeviceConfigUtils.getHighestHdrSdrRatio(sdrHdrRatioMap.getPoint(),
                NonNegativeFloatToFloatPoint::getSecond);
    }

    private static float getFallbackHighestSdrHdrRatio(HighBrightnessMode hbm) {
        SdrHdrRatioMap fallbackMap = hbm != null ? hbm.getSdrHdrRatioMap_all() : null;
        if (fallbackMap == null) {
            return 1;
        }
        return DisplayDeviceConfigUtils.getHighestHdrSdrRatio(fallbackMap.getPoint(),
                SdrHdrRatioPoint::getHdrRatio);
    }
}
