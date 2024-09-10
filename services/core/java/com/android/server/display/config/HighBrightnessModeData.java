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

package com.android.server.display.config;

import android.annotation.Nullable;
import android.util.Slog;
import android.util.Spline;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * Container for high brightness mode configuration data.
 */
public class HighBrightnessModeData {
    private static final String TAG = "HighBrightnessModeData";

    static final float HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT = 0.5f;

    /** Minimum lux needed to enter high brightness mode */
    public final float minimumLux;

    /** Brightness level at which we transition from normal to high-brightness. */
    public final float transitionPoint;

    /** Whether HBM is allowed when {@code Settings.Global.LOW_POWER_MODE} is active. */
    public final boolean allowInLowPowerMode;

    /** Time window for HBM. */
    public final long timeWindowMillis;

    /** Maximum time HBM is allowed to be during in a {@code timeWindowMillis}. */
    public final long timeMaxMillis;

    /** Minimum time that HBM can be on before being enabled. */
    public final long timeMinMillis;

    /** Minimum HDR video size to enter high brightness mode */
    public final float minimumHdrPercentOfScreen;

    @Nullable
    public final Spline sdrToHdrRatioSpline;

    @Nullable
    public final SurfaceControl.RefreshRateRange refreshRateLimit;

    public final boolean isHighBrightnessModeEnabled;

    @VisibleForTesting
    public HighBrightnessModeData(float minimumLux, float transitionPoint, long timeWindowMillis,
            long timeMaxMillis, long timeMinMillis, boolean allowInLowPowerMode,
            float minimumHdrPercentOfScreen, @Nullable Spline sdrToHdrRatioSpline,
            @Nullable SurfaceControl.RefreshRateRange refreshRateLimit,
            boolean isHighBrightnessModeEnabled) {
        this.minimumLux = minimumLux;
        this.transitionPoint = transitionPoint;
        this.timeWindowMillis = timeWindowMillis;
        this.timeMaxMillis = timeMaxMillis;
        this.timeMinMillis = timeMinMillis;
        this.allowInLowPowerMode = allowInLowPowerMode;
        this.minimumHdrPercentOfScreen = minimumHdrPercentOfScreen;
        this.sdrToHdrRatioSpline = sdrToHdrRatioSpline;
        this.refreshRateLimit = refreshRateLimit;
        this.isHighBrightnessModeEnabled = isHighBrightnessModeEnabled;
    }

    @Override
    public String toString() {
        return "HBM{"
                + "minLux: " + minimumLux
                + ", transition: " + transitionPoint
                + ", timeWindow: " + timeWindowMillis + "ms"
                + ", timeMax: " + timeMaxMillis + "ms"
                + ", timeMin: " + timeMinMillis + "ms"
                + ", allowInLowPowerMode: " + allowInLowPowerMode
                + ", minimumHdrPercentOfScreen: " + minimumHdrPercentOfScreen
                + ", mSdrToHdrRatioSpline=" + sdrToHdrRatioSpline
                + ", refreshRateLimit=" + refreshRateLimit
                + ", isHighBrightnessModeEnabled=" + isHighBrightnessModeEnabled
                + "} ";
    }

    /**
     * Loads HighBrightnessModeData from DisplayConfiguration
     */
    public static HighBrightnessModeData loadHighBrightnessModeData(DisplayConfiguration config,
            Function<HighBrightnessMode, Float> transitionPointProvider) {
        final HighBrightnessMode hbm = config.getHighBrightnessMode();
        float minimumLux = 0f;
        float transitionPoint = 0f;
        long timeWindowMillis = 0L;
        long timeMaxMillis = 0L;
        long timeMinMillis = 0L;
        boolean allowInLowPowerMode = false;
        float minimumHdrPercentOfScreen = HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;
        Spline sdrToHdrRatioSpline = null;
        SurfaceControl.RefreshRateRange refreshRateLimit = null;
        boolean isEnabled = false;

        if (hbm != null) {
            minimumLux = hbm.getMinimumLux_all().floatValue();
            transitionPoint = transitionPointProvider.apply(hbm);
            HbmTiming hbmTiming = hbm.getTiming_all();
            timeWindowMillis = hbmTiming.getTimeWindowSecs_all().longValue() * 1000;
            timeMaxMillis = hbmTiming.getTimeMaxSecs_all().longValue() * 1000;
            timeMinMillis = hbmTiming.getTimeMinSecs_all().longValue() * 1000;
            allowInLowPowerMode = hbm.getAllowInLowPowerMode_all();
            BigDecimal minHdrPctOfScreen = hbm.getMinimumHdrPercentOfScreen_all();
            if (minHdrPctOfScreen != null) {
                minimumHdrPercentOfScreen = minHdrPctOfScreen.floatValue();
                if (minimumHdrPercentOfScreen > 1 || minimumHdrPercentOfScreen < 0) {
                    Slog.w(TAG, "Invalid minimum HDR percent of screen: "
                            + minimumHdrPercentOfScreen);
                    minimumHdrPercentOfScreen = HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;
                }
            }

            sdrToHdrRatioSpline = loadSdrHdrRatioMap(hbm);
            RefreshRateRange rr = hbm.getRefreshRate_all();
            if (rr != null) {
                refreshRateLimit = new SurfaceControl.RefreshRateRange(
                        rr.getMinimum().floatValue(), rr.getMaximum().floatValue());
            }
            isEnabled = hbm.getEnabled();
        }
        return new HighBrightnessModeData(minimumLux, transitionPoint,
                timeWindowMillis, timeMaxMillis, timeMinMillis, allowInLowPowerMode,
                minimumHdrPercentOfScreen, sdrToHdrRatioSpline, refreshRateLimit, isEnabled);

    }

    private static Spline loadSdrHdrRatioMap(HighBrightnessMode hbmConfig) {
        final SdrHdrRatioMap sdrHdrRatioMap = hbmConfig.getSdrHdrRatioMap_all();
        if (sdrHdrRatioMap == null) {
            return null;
        }
        return DisplayDeviceConfigUtils.createSpline(sdrHdrRatioMap.getPoint(),
                SdrHdrRatioPoint::getSdrNits, SdrHdrRatioPoint::getHdrRatio);
    }
}
