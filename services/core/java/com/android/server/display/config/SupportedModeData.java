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

import java.util.ArrayList;
import java.util.List;

/**
 * Supported display mode data. Display mode is uniquely identified by refreshRate-vsync pair
 */
public class SupportedModeData {
    public final float refreshRate;
    public final float vsyncRate;

    public SupportedModeData(float refreshRate, float vsyncRate) {
        this.refreshRate = refreshRate;
        this.vsyncRate = vsyncRate;
    }

    @Override
    public String toString() {
        return "SupportedModeData{"
                + "refreshRate= " + refreshRate
                + ", vsyncRate= " + vsyncRate
                + '}';
    }

    static List<SupportedModeData> load(@Nullable NonNegativeFloatToFloatMap configMap) {
        ArrayList<SupportedModeData> supportedModes = new ArrayList<>();
        if (configMap != null) {
            for (NonNegativeFloatToFloatPoint supportedMode : configMap.getPoint()) {
                supportedModes.add(new SupportedModeData(supportedMode.getFirst().floatValue(),
                        supportedMode.getSecond().floatValue()));
            }
        }
        return supportedModes;
    }
}
