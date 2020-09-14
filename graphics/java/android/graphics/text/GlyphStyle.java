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

package android.graphics.text;

import android.annotation.ColorInt;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.graphics.Paint;

import java.util.Objects;

/**
 * Represents subset of Paint parameters such as font size, scaleX that is used to draw a glyph.
 *
 * Glyph is a most primitive unit of text drawing.
 *
 */
public class GlyphStyle {
    private @ColorInt int mColor;
    private float mFontSize;
    private float mScaleX;
    private float mSkewX;
    private int mFlags;

    /**
     * @param color a color.
     * @param fontSize a font size in pixels.
     * @param scaleX a horizontal scale factor.
     * @param skewX a horizontal skew factor
     * @param flags paint flags
     *
     * @see Paint#getFlags()
     * @see Paint#setFlags(int)
     */
    public GlyphStyle(
            @ColorInt int color,
            @FloatRange(from = 0) float fontSize,
            @FloatRange(from = 0) float scaleX,
            @FloatRange(from = 0) float skewX,
            int flags) {
        mColor = color;
        mFontSize = fontSize;
        mScaleX = scaleX;
        mSkewX = skewX;
        mFlags = flags;
    }

    /**
     * Create glyph style from Paint
     *
     * @param paint a paint
     */
    public GlyphStyle(@NonNull Paint paint) {
        setFromPaint(paint);
    }

    /**
     * Gets the color.
     *
     * @return a color
     * @see Paint#getColor()
     * @see Paint#setColor(int)
     */
    public @ColorInt int getColor() {
        return mColor;
    }

    /**
     * Sets the color.
     *
     * @param color a color
     * @see Paint#getColor()
     * @see Paint#setColor(int)
     */
    public void setColor(@ColorInt int color) {
        mColor = color;
    }

    /**
     * Gets the font size in pixels.
     *
     * @return font size
     * @see Paint#getTextSize()
     * @see Paint#setTextSize(float)
     */
    public @FloatRange(from = 0) float getFontSize() {
        return mFontSize;
    }

    /**
     * Sets the font size in pixels.
     *
     * @param fontSize font size in pixel
     * @see Paint#getTextSize()
     * @see Paint#setTextSize(float)
     */
    public void setFontSize(@FloatRange(from = 0) float fontSize) {
        mFontSize = fontSize;
    }

    /**
     * Return the horizontal scale factor
     *
     * @return a horizontal scale factor
     * @see Paint#getTextScaleX()
     * @see Paint#setTextScaleX(float)
     */
    public @FloatRange(from = 0) float getScaleX() {
        return mScaleX;
    }

    /**
     * Set the horizontal scale factor
     *
     * @param scaleX a horizontal scale factor
     * @see Paint#getTextScaleX()
     * @see Paint#setTextScaleX(float)
     */
    public void setScaleX(@FloatRange(from = 0) float scaleX) {
        mScaleX = scaleX;
    }

    /**
     * Return the horizontal skew factor
     *
     * @return a horizontal skew factor
     * @see Paint#getTextSkewX()
     * @see Paint#setTextSkewX(float)
     */
    public @FloatRange(from = 0) float getSkewX() {
        return mSkewX;
    }

    /**
     * Set the horizontal skew factor
     *
     * @param skewX a horizontal skew factor
     * @see Paint#getTextSkewX()
     * @see Paint#setTextSkewX(float)
     */
    public void setSkewX(@FloatRange(from = 0) float skewX) {
        mSkewX = skewX;
    }

    /**
     * Returns the Paint flags.
     *
     * @return a paint flags
     * @see Paint#getFlags()
     * @see Paint#setFlags(int)
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Set the Paint flags.
     *
     * @param flags a paint flags
     * @see Paint#getFlags()
     * @see Paint#setFlags(int)
     */
    public void setFlags(int flags) {
        mFlags = flags;
    }

    /**
     * Applies glyph style to the paint object.
     *
     * @param paint a paint object
     */
    public void applyToPaint(@NonNull Paint paint) {
        paint.setColor(mColor);
        paint.setTextSize(mFontSize);
        paint.setTextScaleX(mScaleX);
        paint.setTextSkewX(mSkewX);
        paint.setFlags(mFlags);
    }

    /**
     * Copy parameters from a Paint object.
     *
     * @param paint a paint object
     */
    public void setFromPaint(@NonNull Paint paint) {
        mColor = paint.getColor();
        mFontSize = paint.getTextSize();
        mScaleX = paint.getTextScaleX();
        mSkewX = paint.getTextSkewX();
        mFlags = paint.getFlags();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GlyphStyle)) return false;
        GlyphStyle that = (GlyphStyle) o;
        return that.mColor == mColor
                && Float.compare(that.mFontSize, mFontSize) == 0
                && Float.compare(that.mScaleX, mScaleX) == 0
                && Float.compare(that.mSkewX, mSkewX) == 0
                && mFlags == that.mFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mColor, mFontSize, mScaleX, mSkewX, mFlags);
    }

    @Override
    public String toString() {
        return "GlyphStyle{"
                + "mColor=" + mColor
                + ", mFontSize=" + mFontSize
                + ", mScaleX=" + mScaleX
                + ", mSkewX=" + mSkewX
                + ", mFlags=" + mFlags
                + '}';
    }
}
