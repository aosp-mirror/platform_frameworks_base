/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.color;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_SATURATION;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.util.Slog;

import java.util.Arrays;

/** Control the color transform for global device saturation. */
public class GlobalSaturationTintController extends TintController {

    private final float[] mMatrixGlobalSaturation = new float[16];

    @Override
    public void setUp(Context context, boolean needsLinear) {
    }

    @Override
    public float[] getMatrix() {
        return Arrays.copyOf(mMatrixGlobalSaturation, mMatrixGlobalSaturation.length);
    }

    @Override
    public void setMatrix(int saturationLevel) {
        if (saturationLevel < 0) {
            saturationLevel = 0;
        } else if (saturationLevel > 100) {
            saturationLevel = 100;
        }
        Slog.d(ColorDisplayService.TAG, "Setting saturation level: " + saturationLevel);

        if (saturationLevel == 100) {
            setActivated(false);
            Matrix.setIdentityM(mMatrixGlobalSaturation, 0);
        } else {
            setActivated(true);
            float saturation = saturationLevel * 0.01f;
            float desaturation = 1.0f - saturation;
            float[] luminance = {0.231f * desaturation, 0.715f * desaturation,
                    0.072f * desaturation};
            mMatrixGlobalSaturation[0] = luminance[0] + saturation;
            mMatrixGlobalSaturation[1] = luminance[0];
            mMatrixGlobalSaturation[2] = luminance[0];
            mMatrixGlobalSaturation[4] = luminance[1];
            mMatrixGlobalSaturation[5] = luminance[1] + saturation;
            mMatrixGlobalSaturation[6] = luminance[1];
            mMatrixGlobalSaturation[8] = luminance[2];
            mMatrixGlobalSaturation[9] = luminance[2];
            mMatrixGlobalSaturation[10] = luminance[2] + saturation;
        }
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_SATURATION;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }
}
