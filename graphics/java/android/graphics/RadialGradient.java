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
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

public class RadialGradient extends Shader {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mX;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mY;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mRadius;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float[] mPositions;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private TileMode mTileMode;

    private final float mFocalX;
    private final float mFocalY;
    private final float mFocalRadius;

    // @ColorInts are replaced by @ColorLongs, but these remain due to @UnsupportedAppUsage.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @ColorInt
    private int[] mColors;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @ColorInt
    private int mCenterColor;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
        this(centerX, centerY, 0f, centerX, centerY, radius, convertColors(colors),
                stops, tileMode, ColorSpace.get(ColorSpace.Named.SRGB));
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
        this(centerX, centerY, 0f, centerX, centerY, radius, colors.clone(), stops,
                tileMode, detectColorSpace(colors));
    }

    /**
     * Create a shader that draws a radial gradient given the start and end points as well as
     * starting and ending radii. The starting point is often referred to as the focal center and
     * represents the starting circle of the radial gradient.
     *
     * @param startX   The x-coordinate of the center of the starting circle of the radial gradient,
     *                often referred to as the focal point.
     * @param startY   The y-coordinate of the center of the starting circle of the radial gradient,
     *                 often referred to as the focal point.
     * @param startRadius The radius of the starting circle of the radial gradient, often referred
     *                    to as the focal radius. Must be greater than or equal to zero.
     * @param endX  The x-coordinate of the center of the radius for the end circle of the
     *                 radial gradient
     * @param endY  The y-coordinate of the center of the radius for the end circle of the
     *                 radial gradient
     * @param endRadius   The radius of the ending circle for this gradient. This must be strictly
     *                    greater than zero. A radius value equal to zero is not allowed.
     * @param colors   The colors to be distributed between the center and edge of the circle
     * @param stops    May be <code>null</code>. Valid values are between <code>0.0f</code> and
     *                 <code>1.0f</code>. The relative position of each corresponding color in
     *                 the colors array. If <code>null</code>, colors are distributed evenly
     *                 between the center and edge of the circle.
     * @param tileMode The Shader tiling mode
     *
     * @throws IllegalArgumentException In one of the following circumstances:
     *      <ul>
     *          <li>There are less than two colors</li>
     *          <li>The colors do not share the same {@link ColorSpace}</li>
     *          <li>The colors do not use a valid {@link ColorSpace}</li>
     *          <li>
     *              The {@code stops} parameter is not {@code null} and has a different length from
     *              {@code colors}.
     *          </li>
     *          <li>The {@code startRadius} is negative</li>
     *          <li>The {@code endRadius} is less than or equal to zero</li>
     *       </ul>
     */
    public RadialGradient(float startX, float startY, @FloatRange(from = 0.0f) float startRadius,
            float endX, float endY, @FloatRange(from = 0.0f, fromInclusive = false) float endRadius,
            @NonNull @ColorLong long[] colors, @Nullable float[] stops,
            @NonNull TileMode tileMode) {
        this(startX, startY, startRadius, endX, endY, endRadius, colors.clone(), stops, tileMode,
                detectColorSpace(colors));
    }

    /**
     * Base constructor. Assumes @param colors is a copy that this object can hold onto,
     * and all colors share @param colorSpace.
     */
    private RadialGradient(float startX, float startY, float startRadius, float endX, float endY,
            float endRadius, @NonNull @ColorLong long[] colors, @Nullable float[] stops,
            @NonNull TileMode tileMode, ColorSpace colorSpace
    ) {
        super(colorSpace);
        // A focal or starting radius of zero with a focal point that matches the center is
        // identical to a regular radial gradient
        if (startRadius < 0) {
            throw new IllegalArgumentException("starting/focal radius must be >= 0");
        }

        if (endRadius <= 0) {
            throw new IllegalArgumentException("ending radius must be > 0");
        }

        if (stops != null && colors.length != stops.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }
        mX = endX;
        mY = endY;
        mRadius = endRadius;
        mFocalX = startX;
        mFocalY = startY;
        mFocalRadius = startRadius;
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

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        return nativeCreate(nativeMatrix, mFocalX, mFocalY, mFocalRadius, mX, mY, mRadius,
                mColorLongs, mPositions, mTileMode.nativeInt, colorSpace().getNativeInstance());
    }

    private static native long nativeCreate(long matrix, float startX, float startY,
            float startRadius, float endX, float endY, float endRadius, @ColorLong long[] colors,
            float[] positions, int tileMode, long colorSpaceHandle);
}

