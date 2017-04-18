/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;

public class LinearGradient extends Shader {

    private static final int TYPE_COLORS_AND_POSITIONS = 1;
    private static final int TYPE_COLOR_START_AND_COLOR_END = 2;

    /**
     * Type of the LinearGradient: can be either TYPE_COLORS_AND_POSITIONS or
     * TYPE_COLOR_START_AND_COLOR_END.
     */
    private int mType;

    private float mX0;
    private float mY0;
    private float mX1;
    private float mY1;
    private int[] mColors;
    private float[] mPositions;
    private int mColor0;
    private int mColor1;

    private TileMode mTileMode;

    /**
     * Create a shader that draws a linear gradient along a line.
     *
     * @param x0           The x-coordinate for the start of the gradient line
     * @param y0           The y-coordinate for the start of the gradient line
     * @param x1           The x-coordinate for the end of the gradient line
     * @param y1           The y-coordinate for the end of the gradient line
     * @param colors       The colors to be distributed along the gradient line
     * @param positions    May be null. The relative positions [0..1] of
     *                     each corresponding color in the colors array. If this is null,
     *                     the the colors are distributed evenly along the gradient line.
     * @param tile         The Shader tiling mode
    */
    public LinearGradient(float x0, float y0, float x1, float y1, @NonNull @ColorInt int colors[],
            @Nullable float positions[], @NonNull TileMode tile) {
        if (colors.length < 2) {
            throw new IllegalArgumentException("needs >= 2 number of colors");
        }
        if (positions != null && colors.length != positions.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }
        mType = TYPE_COLORS_AND_POSITIONS;
        mX0 = x0;
        mY0 = y0;
        mX1 = x1;
        mY1 = y1;
        mColors = colors.clone();
        mPositions = positions != null ? positions.clone() : null;
        mTileMode = tile;
    }

    /**
     * Create a shader that draws a linear gradient along a line.
     *
     * @param x0       The x-coordinate for the start of the gradient line
     * @param y0       The y-coordinate for the start of the gradient line
     * @param x1       The x-coordinate for the end of the gradient line
     * @param y1       The y-coordinate for the end of the gradient line
     * @param color0   The color at the start of the gradient line.
     * @param color1   The color at the end of the gradient line.
     * @param tile     The Shader tiling mode
    */
    public LinearGradient(float x0, float y0, float x1, float y1,
            @ColorInt int color0, @ColorInt int color1,
            @NonNull TileMode tile) {
        mType = TYPE_COLOR_START_AND_COLOR_END;
        mX0 = x0;
        mY0 = y0;
        mX1 = x1;
        mY1 = y1;
        mColor0 = color0;
        mColor1 = color1;
        mColors = null;
        mPositions = null;
        mTileMode = tile;
    }

    @Override
    long createNativeInstance(long nativeMatrix) {
        if (mType == TYPE_COLORS_AND_POSITIONS) {
            return nativeCreate1(nativeMatrix, mX0, mY0, mX1, mY1,
                    mColors, mPositions, mTileMode.nativeInt);
        } else { // TYPE_COLOR_START_AND_COLOR_END
            return nativeCreate2(nativeMatrix, mX0, mY0, mX1, mY1,
                    mColor0, mColor1, mTileMode.nativeInt);
        }
    }

    /**
     * @hide
     */
    @Override
    protected Shader copy() {
        final LinearGradient copy;
        if (mType == TYPE_COLORS_AND_POSITIONS) {
            copy = new LinearGradient(mX0, mY0, mX1, mY1, mColors.clone(),
                    mPositions != null ? mPositions.clone() : null, mTileMode);
        } else { // TYPE_COLOR_START_AND_COLOR_END
            copy = new LinearGradient(mX0, mY0, mX1, mY1, mColor0, mColor1, mTileMode);
        }
        copyLocalMatrix(copy);
        return copy;
    }

    private native long nativeCreate1(long matrix, float x0, float y0, float x1, float y1,
            int colors[], float positions[], int tileMode);
    private native long nativeCreate2(long matrix, float x0, float y0, float x1, float y1,
            int color0, int color1, int tileMode);
}
