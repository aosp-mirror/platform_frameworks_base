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
import android.content.res.Resources;

import com.android.internal.R;

/**
 * RefreshRates config for display
 */
public class RefreshRateData {
    public static RefreshRateData DEFAULT_REFRESH_RATE_DATA = loadRefreshRateData(null, null);

    private static final int DEFAULT_REFRESH_RATE = 60;
    private static final int DEFAULT_PEAK_REFRESH_RATE = 0;
    private static final int DEFAULT_REFRESH_RATE_IN_HBM = 0;

    /**
     * The default refresh rate for a given device. This value sets the higher default
     * refresh rate. If the hardware composer on the device supports display modes with
     * a higher refresh rate than the default value specified here, the framework may use those
     * higher refresh rate modes if an app chooses one by setting preferredDisplayModeId or calling
     * setFrameRate(). We have historically allowed fallback to mDefaultPeakRefreshRate if
     * defaultRefreshRate is set to 0, but this is not supported anymore.
     */
    public final int defaultRefreshRate;

    /**
     * The default peak refresh rate for a given device. This value prevents the framework from
     * using higher refresh rates, even if display modes with higher refresh rates are available
     * from hardware composer. Only has an effect if the value is non-zero.
     */
    public final int defaultPeakRefreshRate;

    /**
     * Default refresh rate while the device has high brightness mode enabled for HDR.
     */
    public final int defaultRefreshRateInHbmHdr;

    /**
     * Default refresh rate while the device has high brightness mode enabled for Sunlight.
     */
    public final int defaultRefreshRateInHbmSunlight;

    public RefreshRateData(int defaultRefreshRate, int defaultPeakRefreshRate,
            int defaultRefreshRateInHbmHdr, int defaultRefreshRateInHbmSunlight) {
        this.defaultRefreshRate = defaultRefreshRate;
        this.defaultPeakRefreshRate = defaultPeakRefreshRate;
        this.defaultRefreshRateInHbmHdr = defaultRefreshRateInHbmHdr;
        this.defaultRefreshRateInHbmSunlight = defaultRefreshRateInHbmSunlight;
    }


    @Override
    public String toString() {
        return "RefreshRateData {"
                + "defaultRefreshRate: " + defaultRefreshRate
                + "defaultPeakRefreshRate: " + defaultPeakRefreshRate
                + "defaultRefreshRateInHbmHdr: " + defaultRefreshRateInHbmHdr
                + "defaultRefreshRateInHbmSunlight: " + defaultRefreshRateInHbmSunlight
                + "} ";
    }

    /**
     * Loads RefreshRateData from DisplayConfiguration and Resources
     */
    public static RefreshRateData loadRefreshRateData(
            @Nullable DisplayConfiguration config, @Nullable Resources resources) {
        RefreshRateConfigs refreshRateConfigs = config == null ? null : config.getRefreshRate();

        int defaultRefreshRate = loadDefaultRefreshRate(refreshRateConfigs, resources);
        int defaultPeakRefreshRate = loadDefaultPeakRefreshRate(refreshRateConfigs, resources);
        int defaultRefreshRateInHbmHdr = loadDefaultRefreshRateInHbm(refreshRateConfigs, resources);
        int defaultRefreshRateInHbmSunlight = loadDefaultRefreshRateInHbmSunlight(
                refreshRateConfigs, resources);

        return new RefreshRateData(defaultRefreshRate, defaultPeakRefreshRate,
                defaultRefreshRateInHbmHdr, defaultRefreshRateInHbmSunlight);
    }

    private static int loadDefaultRefreshRate(
            @Nullable RefreshRateConfigs refreshRateConfigs, @Nullable Resources resources) {
        if (refreshRateConfigs != null && refreshRateConfigs.getDefaultRefreshRate() != null) {
            return refreshRateConfigs.getDefaultRefreshRate().intValue();
        } else if (resources != null) {
            return resources.getInteger(R.integer.config_defaultRefreshRate);
        }
        return DEFAULT_REFRESH_RATE;
    }

    private static int loadDefaultPeakRefreshRate(
            @Nullable RefreshRateConfigs refreshRateConfigs, @Nullable Resources resources) {
        if (refreshRateConfigs != null && refreshRateConfigs.getDefaultPeakRefreshRate() != null) {
            return refreshRateConfigs.getDefaultPeakRefreshRate().intValue();
        } else if (resources != null) {
            return resources.getInteger(R.integer.config_defaultPeakRefreshRate);
        }
        return DEFAULT_PEAK_REFRESH_RATE;
    }

    private static int loadDefaultRefreshRateInHbm(
            @Nullable RefreshRateConfigs refreshRateConfigs, @Nullable Resources resources) {
        if (refreshRateConfigs != null
                && refreshRateConfigs.getDefaultRefreshRateInHbmHdr() != null) {
            return refreshRateConfigs.getDefaultRefreshRateInHbmHdr().intValue();
        } else if (resources != null) {
            return resources.getInteger(R.integer.config_defaultRefreshRateInHbmHdr);
        }
        return DEFAULT_REFRESH_RATE_IN_HBM;
    }

    private static int loadDefaultRefreshRateInHbmSunlight(
            @Nullable RefreshRateConfigs refreshRateConfigs, @Nullable Resources resources) {
        if (refreshRateConfigs != null
                && refreshRateConfigs.getDefaultRefreshRateInHbmSunlight() != null) {
            return refreshRateConfigs.getDefaultRefreshRateInHbmSunlight().intValue();
        } else if (resources != null) {
            return resources.getInteger(R.integer.config_defaultRefreshRateInHbmSunlight);
        }
        return DEFAULT_REFRESH_RATE_IN_HBM;
    }
}
