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

public class SweepGradient extends Shader {

    private static final int TYPE_COLORS_AND_POSITIONS = 1;
    private static final int TYPE_COLOR_START_AND_COLOR_END = 2;

    /**
     * Type of the LinearGradient: can be either TYPE_COLORS_AND_POSITIONS or
     * TYPE_COLOR_START_AND_COLOR_END.
     */
    private int mType;

    private float mCx;
    private float mCy;
    private int[] mColors;
    private float[] mPositions;
    private int mColor0;
    private int mColor1;

    /**
     * A subclass of Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param colors   The colors to be distributed between around the center.
     *                 There must be at least 2 colors in the array.
     * @param positions May be NULL. The relative position of
     *                 each corresponding color in the colors array, beginning
     *                 with 0 and ending with 1.0. If the values are not
     *                 monotonic, the drawing may produce unexpected results.
     *                 If positions is NULL, then the colors are automatically
     *                 spaced evenly.
     */
    public SweepGradient(float cx, float cy,
                         int colors[], float positions[]) {
        if (colors.length < 2) {
            throw new IllegalArgumentException("needs >= 2 number of colors");
        }
        if (positions != null && colors.length != positions.length) {
            throw new IllegalArgumentException(
                        "color and position arrays must be of equal length");
        }
        mType = TYPE_COLORS_AND_POSITIONS;
        mCx = cx;
        mCy = cy;
        mColors = colors;
        mPositions = positions;
        native_instance = nativeCreate1(cx, cy, colors, positions);
        native_shader = nativePostCreate1(native_instance, cx, cy, colors, positions);
    }

    /**
     * A subclass of Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param color0   The color to use at the start of the sweep
     * @param color1   The color to use at the end of the sweep
     */
    public SweepGradient(float cx, float cy, int color0, int color1) {
        mType = TYPE_COLOR_START_AND_COLOR_END;
        mCx = cx;
        mCy = cy;
        mColor0 = color0;
        mColor1 = color1;
        native_instance = nativeCreate2(cx, cy, color0, color1);
        native_shader = nativePostCreate2(native_instance, cx, cy, color0, color1);
    }

    /**
     * @hide
     */
    @Override
    protected Shader copy() {
        final SweepGradient copy;
        switch (mType) {
            case TYPE_COLORS_AND_POSITIONS:
                copy = new SweepGradient(mCx, mCy, mColors.clone(),
                        mPositions != null ? mPositions.clone() : null);
                break;
            case TYPE_COLOR_START_AND_COLOR_END:
                copy = new SweepGradient(mCx, mCy, mColor0, mColor1);
                break;
            default:
                throw new IllegalArgumentException("SweepGradient should be created with either " +
                        "colors and positions or start color and end color");
        }
        copyLocalMatrix(copy);
        return copy;
    }

    private static native int nativeCreate1(float x, float y, int colors[], float positions[]);
    private static native int nativeCreate2(float x, float y, int color0, int color1);

    private static native int nativePostCreate1(int native_shader, float cx, float cy,
            int[] colors, float[] positions);    
    private static native int nativePostCreate2(int native_shader, float cx, float cy,
            int color0, int color1);
}

