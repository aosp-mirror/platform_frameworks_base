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
import android.annotation.ColorLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;

public class RadialGradient extends Shader {
    @UnsupportedAppUsage
    private float mX;
    @UnsupportedAppUsage
    private float mY;
    @UnsupportedAppUsage
    private float mRadius;
    @UnsupportedAppUsage
    private float[] mPositions;
    @UnsupportedAppUsage
    private TileMode mTileMode;

    // @ColorInts are replaced by @ColorLongs, but these remain due to @UnsupportedAppUsage.
    @UnsupportedAppUsage
    @ColorInt
    private int[] mColors;
    @UnsupportedAppUsage
    @ColorInt
    private int mCenterColor;
    @UnsupportedAppUsage
    @ColorInt
    private int mEdgeColor;

    @ColorLong
    private final long[] mColorLongs;

    /**
     * Create a shader that draws a radial gradient given the center and radius.
     *
     * @param centerX  The x-coordinate of the center of the radius
     * @param centerY  The y-coordinate of the center of the radius
     * @param radius   Must be positive. The radius of the circle for this gradient.
     * @param colors   The sRGB colors to be distributed between the center and edge of the circle
     * @param stops    May be <code>null</code>. Valid values are between <code>0.0f</code> and
     *                 <code>1.0f</code>. The relative position of each corresponding color in
     *                 the colors array. If <code>null</code>, colors are distributed evenly
     *                 between the center and edge of the circle.
     * @param tileMode The Shader tiling mode
     */
    public RadialGradient(float centerX, float centerY, float radius,
            @NonNull @ColorInt int[] colors, @Nullable float[] stops,
            @NonNull TileMode tileMode) {
        this(centerX, centerY, radius, convertColors(colors), stops, tileMode,
                ColorSpace.get(ColorSpace.Named.SRGB));
    }

    /**
     * Create a shader that draws a radial gradient given the center and radius.
     *
     * @param centerX  The x-coordinate of the center of the radius
     * @param centerY  The y-coordinate of the center of the radius
     * @param radius   Must be positive. The radius of the circle for this gradient.
     * @param colors   The colors to be distributed between the center and edge of the circle
     * @param stops    May be <code>null</code>. Valid values are between <code>0.0f</code> and
     *                 <code>1.0f</code>. The relative position of each corresponding color in
     *                 the colors array. If <code>null</code>, colors are distributed evenly
     *                 between the center and edge of the circle.
     * @param tileMode The Shader tiling mode
     *
     * @throws IllegalArgumentException if there are less than two colors, the colors do
     *      not share the same {@link ColorSpace} or do not use a valid one, or {@code stops}
     *      is not {@code null} and has a different length from {@code colors}.
     */
    public RadialGradient(float centerX, float centerY, float radius,
            @NonNull @ColorLong long[] colors, @Nullable float[] stops,
            @NonNull TileMode tileMode) {
        this(centerX, centerY, radius, colors.clone(), stops, tileMode, detectColorSpace(colors));
    }

    /**
     * Base constructor. Assumes @param colors is a copy that this object can hold onto,
     * and all colors share @param colorSpace.
     */
    private RadialGradient(float centerX, float centerY, float radius,
            @NonNull @ColorLong long[] colors, @Nullable float[] stops,
            @NonNull TileMode tileMode, ColorSpace colorSpace) {
        super(colorSpace);

        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        if (stops != null && colors.length != stops.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }
        mX = centerX;
        mY = centerY;
        mRadius = radius;
        mColorLongs = colors;
        mPositions = stops != null ? stops.clone() : null;
        mTileMode = tileMode;
    }

    /**
     * Create a shader that draws a radial gradient given the center and radius.
     *
     * @param centerX     The x-coordinate of the center of the radius
     * @param centerY     The y-coordinate of the center of the radius
     * @param radius      Must be positive. The radius of the circle for this gradient
     * @param centerColor The sRGB color at the center of the circle.
     * @param edgeColor   The sRGB color at the edge of the circle.
     * @param tileMode    The Shader tiling mode
     */
    public RadialGradient(float centerX, float centerY, float radius,
            @ColorInt int centerColor, @ColorInt int edgeColor, @NonNull TileMode tileMode) {
        this(centerX, centerY, radius, Color.pack(centerColor), Color.pack(edgeColor), tileMode);
    }

    /**
     * Create a shader that draws a radial gradient given the center and radius.
     *
     * @param centerX     The x-coordinate of the center of the radius
     * @param centerY     The y-coordinate of the center of the radius
     * @param radius      Must be positive. The radius of the circle for this gradient
     * @param centerColor The color at the center of the circle.
     * @param edgeColor   The color at the edge of the circle.
     * @param tileMode    The Shader tiling mode
     *
     * @throws IllegalArgumentException if the colors do
     *      not share the same {@link ColorSpace} or do not use a valid one.
     */
    public RadialGradient(float centerX, float centerY, float radius,
            @ColorLong long centerColor, @ColorLong long edgeColor, @NonNull TileMode tileMode) {
        this(centerX, centerY, radius, new long[] {centerColor, edgeColor}, null, tileMode);
    }

    @Override
    long createNativeInstance(long nativeMatrix) {
        return nativeCreate(nativeMatrix, mX, mY, mRadius,
                mColorLongs, mPositions, mTileMode.nativeInt,
                colorSpace().getNativeInstance());
    }

    private static native long nativeCreate(long matrix, float x, float y, float radius,
            @ColorLong long[] colors, float[] positions, int tileMode, long colorSpaceHandle);
}

