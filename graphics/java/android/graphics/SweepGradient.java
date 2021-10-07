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
import android.os.Build;

public class SweepGradient extends Shader {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mCx;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mCy;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float[] mPositions;

    // @ColorInts are replaced by @ColorLongs, but these remain due to @UnsupportedAppUsage.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @ColorInt
    private int[] mColors;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @ColorInt
    private int mColor0;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @ColorInt
    private int mColor1;

    @ColorLong
    private final long[] mColorLongs;

    /**
     * A Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param colors   The sRGB colors to be distributed between around the center.
     *                 There must be at least 2 colors in the array.
     * @param positions May be NULL. The relative position of
     *                 each corresponding color in the colors array, beginning
     *                 with 0 and ending with 1.0. If the values are not
     *                 monotonic, the drawing may produce unexpected results.
     *                 If positions is NULL, then the colors are automatically
     *                 spaced evenly.
     */
    public SweepGradient(float cx, float cy, @NonNull @ColorInt int[] colors,
            @Nullable float[] positions) {
        this(cx, cy, convertColors(colors), positions, ColorSpace.get(ColorSpace.Named.SRGB));
    }

    /**
     * A Shader that draws a sweep gradient around a center point.
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
     * @throws IllegalArgumentException if there are less than two colors, the colors do
     *      not share the same {@link ColorSpace} or do not use a valid one, or {@code positions}
     *      is not {@code null} and has a different length from {@code colors}.
     */
    public SweepGradient(float cx, float cy, @NonNull @ColorLong long[] colors,
            @Nullable float[] positions) {
        this(cx, cy, colors.clone(), positions, detectColorSpace(colors));
    }

    /**
     * Base constructor. Assumes @param colors is a copy that this object can hold onto,
     * and all colors share @param colorSpace.
     */
    private SweepGradient(float cx, float cy, @NonNull @ColorLong long[] colors,
            @Nullable float[] positions, ColorSpace colorSpace) {
        super(colorSpace);

        if (positions != null && colors.length != positions.length) {
            throw new IllegalArgumentException(
                    "color and position arrays must be of equal length");
        }
        mCx = cx;
        mCy = cy;
        mColorLongs = colors;
        mPositions = positions != null ? positions.clone() : null;
    }

    /**
     * A Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param color0   The sRGB color to use at the start of the sweep
     * @param color1   The sRGB color to use at the end of the sweep
     */
    public SweepGradient(float cx, float cy, @ColorInt int color0, @ColorInt int color1) {
        this(cx, cy, Color.pack(color0), Color.pack(color1));
    }

    /**
     * A Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param color0   The color to use at the start of the sweep
     * @param color1   The color to use at the end of the sweep
     *
     * @throws IllegalArgumentException if the colors do
     *      not share the same {@link ColorSpace} or do not use a valid one.
     */
    public SweepGradient(float cx, float cy, @ColorLong long color0, @ColorLong long color1) {
        this(cx, cy, new long[] {color0, color1}, null);
    }

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        return nativeCreate(nativeMatrix, mCx, mCy, mColorLongs, mPositions,
                colorSpace().getNativeInstance());
    }

    private static native long nativeCreate(long matrix, float x, float y,
            long[] colors, float[] positions, long colorSpaceHandle);
}

