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
package com.android.internal.jank;

import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__UNKNOWN_REFRESH_RATE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__VARIABLE_REFRESH_RATE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_30_HZ;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_60_HZ;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_90_HZ;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_120_HZ;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_240_HZ;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Display refresh rate related functionality.
 * @hide
 */
public class DisplayRefreshRate {
    public static final int UNKNOWN_REFRESH_RATE =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__UNKNOWN_REFRESH_RATE;
    public static final int VARIABLE_REFRESH_RATE =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__VARIABLE_REFRESH_RATE;
    public static final int REFRESH_RATE_30_HZ =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_30_HZ;
    public static final int REFRESH_RATE_60_HZ =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_60_HZ;
    public static final int REFRESH_RATE_90_HZ =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_90_HZ;
    public static final int REFRESH_RATE_120_HZ =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_120_HZ;
    public static final int REFRESH_RATE_240_HZ =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_REFRESH_RATE__RR_240_HZ;

    /** @hide */
    @IntDef({
        UNKNOWN_REFRESH_RATE,
        VARIABLE_REFRESH_RATE,
        REFRESH_RATE_30_HZ,
        REFRESH_RATE_60_HZ,
        REFRESH_RATE_90_HZ,
        REFRESH_RATE_120_HZ,
        REFRESH_RATE_240_HZ,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RefreshRate {
    }

    private DisplayRefreshRate() {
    }

    /**
     * Computes the display refresh rate based off the frame interval.
     */
    @RefreshRate
    public static int getRefreshRate(long frameIntervalNs) {
        long rate = Math.round(1e9 / frameIntervalNs);
        if (rate < 50) {
            return REFRESH_RATE_30_HZ;
        } else if (rate < 80) {
            return REFRESH_RATE_60_HZ;
        } else if (rate < 110) {
            return REFRESH_RATE_90_HZ;
        } else if (rate < 180) {
            return REFRESH_RATE_120_HZ;
        } else {
            return REFRESH_RATE_240_HZ;
        }
    }
}
