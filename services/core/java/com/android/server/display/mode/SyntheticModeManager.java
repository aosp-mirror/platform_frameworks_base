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

package com.android.server.display.mode;

import android.util.Size;
import android.view.Display;

import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.feature.DisplayManagerFlags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When selected by app synthetic modes will only affect render rate switch rather than mode switch
 */
public class SyntheticModeManager {
    private static final float FLOAT_TOLERANCE = 0.01f;
    private static final float SYNTHETIC_MODE_REFRESH_RATE = 60f;
    private static final float SYNTHETIC_MODE_HIGH_BOUNDARY =
            SYNTHETIC_MODE_REFRESH_RATE + FLOAT_TOLERANCE;

    private final boolean mSynthetic60HzModesEnabled;

    public SyntheticModeManager(DisplayManagerFlags flags) {
        mSynthetic60HzModesEnabled = flags.isSynthetic60HzModesEnabled();
    }

    /**
     * creates display supportedModes array, exposed to applications
     */
    public Display.Mode[] createAppSupportedModes(DisplayDeviceConfig config,
            Display.Mode[] modes) {
        if (!config.isVrrSupportEnabled() || !mSynthetic60HzModesEnabled) {
            return modes;
        }
        List<Display.Mode> appSupportedModes = new ArrayList<>();
        Map<Size, int[]> sizes = new LinkedHashMap<>();
        int nextModeId = 0;
        // exclude "real" 60Hz modes and below for VRR displays,
        // they will be replaced with synthetic 60Hz mode
        // for VRR display there should be "real" mode with rr > 60Hz
        for (Display.Mode mode : modes) {
            if (mode.getRefreshRate() > SYNTHETIC_MODE_HIGH_BOUNDARY) {
                appSupportedModes.add(mode);
            }
            if (mode.getModeId() > nextModeId) {
                nextModeId = mode.getModeId();
            }

            float divisor = mode.getVsyncRate() / SYNTHETIC_MODE_REFRESH_RATE;
            boolean is60HzAchievable = Math.abs(divisor - Math.round(divisor)) < FLOAT_TOLERANCE;
            if (is60HzAchievable) {
                sizes.put(new Size(mode.getPhysicalWidth(), mode.getPhysicalHeight()),
                        mode.getSupportedHdrTypes());
            }
        }
        // even if VRR display does not have 60Hz mode, we are still adding synthetic 60Hz mode
        // for each screen size
        // vsync rate, alternativeRates and hdrTypes  are not important for synthetic mode,
        // only refreshRate and size are used for DisplayModeDirector votes.
        for (Map.Entry<Size, int[]> entry: sizes.entrySet()) {
            nextModeId++;
            Size size = entry.getKey();
            int[] hdrTypes = entry.getValue();
            appSupportedModes.add(
                    new Display.Mode(nextModeId, size.getWidth(), size.getHeight(), 60f, 60f, true,
                            new float[0], hdrTypes));
        }
        Display.Mode[] appSupportedModesArr = new Display.Mode[appSupportedModes.size()];
        return appSupportedModes.toArray(appSupportedModesArr);
    }
}
