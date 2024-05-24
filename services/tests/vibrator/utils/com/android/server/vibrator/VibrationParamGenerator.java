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

package com.android.server.vibrator;

import android.frameworks.vibrator.ScaleParam;
import android.frameworks.vibrator.VibrationParam;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class that can be used to generate arrays of {@link VibrationParam}.
 */
public final class VibrationParamGenerator {
    /**
     * Generates an array of {@link VibrationParam}.
     */
    public static VibrationParam[] generateVibrationParams(SparseArray<Float> vibrationScales) {
        List<VibrationParam> vibrationParamList = new ArrayList<>();
        for (int i = 0; i < vibrationScales.size(); i++) {
            int type = vibrationScales.keyAt(i);
            float scale = vibrationScales.valueAt(i);

            vibrationParamList.add(generateVibrationParam(type, scale));
        }

        return vibrationParamList.toArray(new VibrationParam[0]);
    }

    private static VibrationParam generateVibrationParam(int type, float scale) {
        ScaleParam scaleParam = new ScaleParam();
        scaleParam.typesMask = type;
        scaleParam.scale = scale;
        VibrationParam vibrationParam = new VibrationParam();
        vibrationParam.setScale(scaleParam);

        return vibrationParam;
    }
}
