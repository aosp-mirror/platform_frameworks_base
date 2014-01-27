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

public class RadialGradient extends Shader {

    private static final int TYPE_COLORS_AND_POSITIONS = 1;
    private static final int TYPE_COLOR_CENTER_AND_COLOR_EDGE = 2;

    /**
     * Type of the RadialGradient: can be either TYPE_COLORS_AND_POSITIONS or
     * TYPE_COLOR_CENTER_AND_COLOR_EDGE.
     */
    private int mType;

    private float mX;
    private float mY;
    private float mRadius;
    private int[] mColors;
    private float[] mPositions;
    private int mColor0;
    private int mColor1;

    private TileMode mTileMode;

	/**	Create a shader that draws a radial gradient given the center and radius.
        @param x        The x-coordinate of the center of the radius
        @param y        The y-coordinate of the center of the radius
		@param radius   Must be positive. The radius of the circle for this gradient
        @param colors   The colors to be distributed between the center and edge of the circle
        @param positions May be NULL. The relative position of
                        each corresponding color in the colors array. If this is NULL,
                        the the colors are distributed evenly between the center and edge of the circle.
        @param  tile    The Shader tiling mode
	*/
	public RadialGradient(float x, float y, float radius,
                          int colors[], float positions[], TileMode tile) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        if (colors.length < 2) {
            throw new IllegalArgumentException("needs >= 2 number of colors");
        }
        if (positions != null && colors.length != positions.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }
        mType = TYPE_COLORS_AND_POSITIONS;
        mX = x;
        mY = y;
        mRadius = radius;
        mColors = colors;
        mPositions = positions;
        mTileMode = tile;
        native_instance = nativeCreate1(x, y, radius, colors, positions, tile.nativeInt);
        native_shader = nativePostCreate1(native_instance, x, y, radius, colors, positions,
                tile.nativeInt);
    }

	/**	Create a shader that draws a radial gradient given the center and radius.
        @param x        The x-coordinate of the center of the radius
        @param y        The y-coordinate of the center of the radius
		@param radius   Must be positive. The radius of the circle for this gradient
        @param color0   The color at the center of the circle.
        @param color1   The color at the edge of the circle.
        @param tile     The Shader tiling mode
	*/
	public RadialGradient(float x, float y, float radius,
                          int color0, int color1, TileMode tile) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        mType = TYPE_COLOR_CENTER_AND_COLOR_EDGE;
        mX = x;
        mY = y;
        mRadius = radius;
        mColor0 = color0;
        mColor1 = color1;
        mTileMode = tile;
        native_instance = nativeCreate2(x, y, radius, color0, color1, tile.nativeInt);
        native_shader = nativePostCreate2(native_instance, x, y, radius, color0, color1,
                tile.nativeInt);
    }

    /**
     * @hide
     */
    @Override
    protected Shader copy() {
        final RadialGradient copy;
        switch (mType) {
            case TYPE_COLORS_AND_POSITIONS:
                copy = new RadialGradient(mX, mY, mRadius, mColors.clone(),
                        mPositions != null ? mPositions.clone() : null, mTileMode);
                break;
            case TYPE_COLOR_CENTER_AND_COLOR_EDGE:
                copy = new RadialGradient(mX, mY, mRadius, mColor0, mColor1, mTileMode);
                break;
            default:
                throw new IllegalArgumentException("RadialGradient should be created with either " +
                        "colors and positions or center color and edge color");
        }
        copyLocalMatrix(copy);
        return copy;
    }

    private static native int nativeCreate1(float x, float y, float radius,
            int colors[], float positions[], int tileMode);
	private static native int nativeCreate2(float x, float y, float radius,
            int color0, int color1, int tileMode);

    private static native int nativePostCreate1(int native_shader, float x, float y, float radius,
            int colors[], float positions[], int tileMode);
    private static native int nativePostCreate2(int native_shader, float x, float y, float radius,
            int color0, int color1, int tileMode);
}

