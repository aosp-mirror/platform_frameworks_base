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

public class LinearGradient extends Shader {
	/**	Create a shader that draws a linear gradient along a line.
        @param x0           The x-coordinate for the start of the gradient line
        @param y0           The y-coordinate for the start of the gradient line
        @param x1           The x-coordinate for the end of the gradient line
        @param y1           The y-coordinate for the end of the gradient line
        @param  colors      The colors to be distributed along the gradient line
        @param  positions   May be null. The relative positions [0..1] of
                            each corresponding color in the colors array. If this is null,
                            the the colors are distributed evenly along the gradient line.
        @param  tile        The Shader tiling mode
	*/
	public LinearGradient(float x0, float y0, float x1, float y1,
                          int colors[], float positions[], TileMode tile) {
        if (colors.length < 2) {
            throw new IllegalArgumentException("needs >= 2 number of colors");
        }
        if (positions != null && colors.length != positions.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }
        native_instance = nativeCreate1(x0, y0, x1, y1, colors, positions, tile.nativeInt);
        native_shader = nativePostCreate1(native_instance, x0, y0, x1, y1, colors, positions,
                tile.nativeInt);
    }

	/**	Create a shader that draws a linear gradient along a line.
        @param x0       The x-coordinate for the start of the gradient line
        @param y0       The y-coordinate for the start of the gradient line
        @param x1       The x-coordinate for the end of the gradient line
        @param y1       The y-coordinate for the end of the gradient line
        @param  color0  The color at the start of the gradient line.
        @param  color1  The color at the end of the gradient line.
        @param  tile    The Shader tiling mode
	*/
	public LinearGradient(float x0, float y0, float x1, float y1,
                          int color0, int color1, TileMode tile) {
        native_instance = nativeCreate2(x0, y0, x1, y1, color0, color1, tile.nativeInt);
        native_shader = nativePostCreate2(native_instance, x0, y0, x1, y1, color0, color1,
                tile.nativeInt);
    }

	private native int nativeCreate1(float x0, float y0, float x1, float y1,
            int colors[], float positions[], int tileMode);
	private native int nativeCreate2(float x0, float y0, float x1, float y1,
            int color0, int color1, int tileMode);
    private native int nativePostCreate1(int native_shader, float x0, float y0, float x1, float y1,
            int colors[], float positions[], int tileMode);
    private native int nativePostCreate2(int native_shader, float x0, float y0, float x1, float y1,
            int color0, int color1, int tileMode);
}
