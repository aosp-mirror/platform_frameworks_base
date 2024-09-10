/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.paint;


/**
 * Provides a Builder pattern for a PaintBundle
 */
class Painter {
    PaintBundle mPaint;

    /**
     * Write the paint to the buffer
     */
    public PaintBundle commit() {
        return mPaint;
    }

    public Painter setAntiAlias(boolean aa) {
        mPaint.setAntiAlias(aa);
        return this;
    }

    public Painter setColor(int color) {
        mPaint.setColor(color);
        return this;
    }

    public Painter setColorId(int colorId) {
        mPaint.setColorId(colorId);
        return this;
    }

    /**
     * Set the paint's Join.
     *
     * @param join set the paint's Join, used whenever the paint's style is
     *             Stroke or StrokeAndFill.
     */
    public Painter setStrokeJoin(int join) {
        mPaint.setStrokeJoin(join);
        return this;
    }

    /**
     * Set the width for stroking. Pass 0 to stroke in hairline mode.
     * Hairlines always draws a single
     * pixel independent of the canvas's matrix.
     *
     * @param width set the paint's stroke width, used whenever the paint's
     *             style is Stroke or StrokeAndFill.
     */
    public Painter setStrokeWidth(float width) {
        mPaint.setStrokeWidth(width);
        return this;
    }

    /**
     * Set the paint's style, used for controlling how primitives' geometries
     * are interpreted (except for drawBitmap, which always assumes Fill).
     *
     * @param style The new style to set in the paint
     */
    public Painter setStyle(int style) {
        mPaint.setStyle(style);
        return this;
    }

    /**
     * Set the paint's Cap.
     *
     * @param cap set the paint's line cap style, used whenever the paint's
     *           style is Stroke or StrokeAndFill.
     */
    public Painter setStrokeCap(int cap) {
        mPaint.setStrokeCap(cap);
        return this;
    }

    /**
     * Set the paint's stroke miter value. This is used to control the behavior
     * of miter joins when the joins angle is sharp. This value must be >= 0.
     *
     * @param miter set the miter limit on the paint, used whenever the paint's
     *             style is Stroke or StrokeAndFill.
     */
    public Painter setStrokeMiter(float miter) {
        mPaint.setStrokeMiter(miter);
        return this;
    }

    /**
     * Helper to setColor(), that only assigns the color's alpha value,
     * leaving its r,g,b values unchanged. Results are undefined if the alpha
     * value is outside of the range [0..1.0]
     *
     * @param alpha set the alpha component [0..1.0] of the paint's color.
     */
    public Painter setAlpha(float alpha) {
        mPaint.setAlpha((alpha > 2) ? alpha / 255f : alpha);
        return this;
    }

    /**
     * Create a color filter that uses the specified color and Porter-Duff mode.
     *
     * @param color The ARGB source color used with the specified Porter-Duff
     *             mode
     * @param mode  The porter-duff mode that is applied
     */
    public Painter setPorterDuffColorFilter(int color, int mode) {
        mPaint.setColorFilter(color, mode);
        return this;
    }

    /**
     * sets a shader that draws a linear gradient along a line.
     *
     * @param startX    The x-coordinate for the start of the gradient line
     * @param startY    The y-coordinate for the start of the gradient line
     * @param endX      The x-coordinate for the end of the gradient line
     * @param endY      The y-coordinate for the end of the gradient line
     * @param colors    The sRGB colors to be distributed along the gradient
     *                  line
     * @param positions May be null. The relative positions [0..1] of each
     *                 corresponding color in the colors array. If this is null,
     *                 the colors are distributed evenly along the gradient
     *                 line.
     * @param tileMode  The Shader tiling mode
     */
    public Painter setLinearGradient(
            float startX,
            float startY,
            float endX,
            float endY,
            int[] colors,
            float[] positions,
            int tileMode
    ) {
        mPaint.setLinearGradient(colors, positions, startX,
                startY, endX, endY, tileMode);
        return this;
    }

    /**
     * Sets a shader that draws a radial gradient given the center and radius.
     *
     * @param centerX   The x-coordinate of the center of the radius
     * @param centerY   The y-coordinate of the center of the radius
     * @param radius    Must be positive. The radius of the circle for this
     *                  gradient.
     * @param colors    The sRGB colors to be distributed between the center
     *                  and edge of the circle
     * @param positions May be <code>null</code>. Valid values are between
     *                  <code>0.0f</code> and
     *                  <code>1.0f</code>. The relative position of each
     *                  corresponding color in the colors array. If
     *                  <code>null</code>, colors are distributed evenly
     *                  between the center and edge of the circle.
     * @param tileMode  The Shader tiling mode
     */
    public Painter setRadialGradient(
            float centerX,
            float centerY,
            float radius,
            int[] colors,
            float[] positions,
            int tileMode
    ) {
        mPaint.setRadialGradient(colors, positions, centerX,
                centerY, radius, tileMode);
        return this;
    }

    /**
     * Set a shader that draws a sweep gradient around a center point.
     *
     * @param centerX   The x-coordinate of the center
     * @param centerY   The y-coordinate of the center
     * @param colors    The sRGB colors to be distributed between around the
     *                  center. There must be at least 2 colors in the array.
     * @param positions May be NULL. The relative position of each corresponding
     *                 color in the colors array, beginning with 0 and ending
     *                 with 1.0. If the values are not monotonic, the drawing
     *                  may produce unexpected results. If positions is NULL,
     *                  then the colors are automatically spaced evenly.
     */
    public Painter setSweepGradient(
            float centerX,
            float centerY,
            int[] colors,
            float[] positions
    ) {
        mPaint.setSweepGradient(colors, positions, centerX, centerY);
        return this;
    }

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param size set the paint's text size in pixel units.
     */
    public Painter setTextSize(float size) {
        mPaint.setTextSize(size);
        return this;
    }

    /**
     * sets a typeface object that best matches the specified existing
     * typeface and the specified weight and italic style
     *
     * <p>Below are numerical values and corresponding common weight names.</p>
     * <table> <thead>
     * <tr><th>Value</th><th>Common weight name</th></tr> </thead> <tbody>
     * <tr><td>100</td><td>Thin</td></tr>
     * <tr><td>200</td><td>Extra Light</td></tr>
     * <tr><td>300</td><td>Light</td></tr>
     * <tr><td>400</td><td>Normal</td></tr>
     * <tr><td>500</td><td>Medium</td></tr>
     * <tr><td>600</td><td>Semi Bold</td></tr>
     * <tr><td>700</td><td>Bold</td></tr>
     * <tr><td>800</td><td>Extra Bold</td></tr>
     * <tr><td>900</td><td>Black</td></tr> </tbody> </table>
     *
     * @param fontType 0 = default 1 = sans serif 2 = serif 3 = monospace
     * @param weight   The desired weight to be drawn.
     * @param italic   {@code true} if italic style is desired to be drawn.
     *                            Otherwise, {@code false}
     */
    public Painter setTypeface(int fontType, int weight, boolean italic) {
        mPaint.setTextStyle(fontType, weight, italic);
        return this;
    }


    public Painter setFilterBitmap(boolean filter) {
        mPaint.setFilterBitmap(filter);
        return this;
    }


    public Painter setShader(int id) {
        mPaint.setShader(id);
        return this;
    }

}
