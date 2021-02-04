/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_REDUCE_BRIGHT_COLORS;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.util.Slog;

import com.android.internal.R;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Control the color transform for bright color reduction.
 */
public class ReduceBrightColorsTintController extends TintController {

    private final float[] mMatrix = new float[16];
    private final float[] mCoefficients = new float[3];

    private int mStrength;

    @Override
    public void setUp(Context context, boolean needsLinear) {
        final String[] coefficients = context.getResources().getStringArray(
                needsLinear ? R.array.config_reduceBrightColorsCoefficients
                        : R.array.config_reduceBrightColorsCoefficientsNonlinear);
        for (int i = 0; i < 3 && i < coefficients.length; i++) {
            mCoefficients[i] = Float.parseFloat(coefficients[i]);
        }
    }

    @Override
    public float[] getMatrix() {
        return isActivated() ? Arrays.copyOf(mMatrix, mMatrix.length)
                : ColorDisplayService.MATRIX_IDENTITY;
    }

    @Override
    public void setMatrix(int strengthLevel) {
        // Clamp to valid range.
        if (strengthLevel < 0) {
            strengthLevel = 0;
        } else if (strengthLevel > 100) {
            strengthLevel = 100;
        }
        Slog.d(ColorDisplayService.TAG, "Setting bright color reduction level: " + strengthLevel);
        mStrength = strengthLevel;

        Matrix.setIdentityM(mMatrix, 0);

        // All three (r,g,b) components are equal and calculated with the same formula.
        final float componentValue = computeComponentValue(strengthLevel);
        mMatrix[0] = componentValue;
        mMatrix[5] = componentValue;
        mMatrix[10] = componentValue;
    }

    private float clamp(float value) {
        if (value > 1f) {
            return 1f;
        } else if (value < 0f) {
            return 0f;
        }
        return value;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("    mStrength = " + mStrength);
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_REDUCE_BRIGHT_COLORS;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }

    public int getStrength() {
        return mStrength;
    }

    /** Returns the offset factor at Ymax. */
    public float getOffsetFactor() {
        // Strength terms drop out as strength --> 1, leaving the coefficients.
        return mCoefficients[0] + mCoefficients[1] + mCoefficients[2];
    }

    /**
     * Returns the effective brightness (in nits), which has been adjusted to account for the effect
     * of the bright color reduction.
     */
    public float getAdjustedBrightness(float nits) {
        return computeComponentValue(mStrength) * nits;
    }

    private float computeComponentValue(int strengthLevel) {
        final float percentageStrength = strengthLevel / 100f;
        final float squaredPercentageStrength = percentageStrength * percentageStrength;
        return clamp(
                squaredPercentageStrength * mCoefficients[0] + percentageStrength * mCoefficients[1]
                        + mCoefficients[2]);
    }
}
