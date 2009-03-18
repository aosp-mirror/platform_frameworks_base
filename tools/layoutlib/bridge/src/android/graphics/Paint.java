/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.TextUtils;

import java.awt.Font;
import java.awt.Toolkit;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

/**
 * A paint implementation overridden by the LayoutLib bridge.
 */
public class Paint extends _Original_Paint {

    private int mColor = 0xFFFFFFFF;
    private float mTextSize = 20;
    private float mScaleX = 1;
    private float mSkewX = 0;
    private Align mAlign = Align.LEFT;
    private Style mStyle = Style.FILL;
    private int mFlags = 0;
    
    private Font mFont;
    private final FontRenderContext mFontContext = new FontRenderContext(
            new AffineTransform(), true, true);
    private java.awt.FontMetrics mMetrics;

    @SuppressWarnings("hiding")
    public static final int ANTI_ALIAS_FLAG       = _Original_Paint.ANTI_ALIAS_FLAG;
    @SuppressWarnings("hiding")
    public static final int FILTER_BITMAP_FLAG    = _Original_Paint.FILTER_BITMAP_FLAG;
    @SuppressWarnings("hiding")
    public static final int DITHER_FLAG           = _Original_Paint.DITHER_FLAG;
    @SuppressWarnings("hiding")
    public static final int UNDERLINE_TEXT_FLAG   = _Original_Paint.UNDERLINE_TEXT_FLAG;
    @SuppressWarnings("hiding")
    public static final int STRIKE_THRU_TEXT_FLAG = _Original_Paint.STRIKE_THRU_TEXT_FLAG;
    @SuppressWarnings("hiding")
    public static final int FAKE_BOLD_TEXT_FLAG   = _Original_Paint.FAKE_BOLD_TEXT_FLAG;
    @SuppressWarnings("hiding")
    public static final int LINEAR_TEXT_FLAG      = _Original_Paint.LINEAR_TEXT_FLAG;
    @SuppressWarnings("hiding")
    public static final int SUBPIXEL_TEXT_FLAG    = _Original_Paint.SUBPIXEL_TEXT_FLAG;
    @SuppressWarnings("hiding")
    public static final int DEV_KERN_TEXT_FLAG    = _Original_Paint.DEV_KERN_TEXT_FLAG;

    public static class FontMetrics extends _Original_Paint.FontMetrics {
    } 

    public static class FontMetricsInt extends _Original_Paint.FontMetricsInt {
    }
    
    /**
     * The Style specifies if the primitive being drawn is filled,
     * stroked, or both (in the same color). The default is FILL.
     */
    public enum Style {
        /**
         * Geometry and text drawn with this style will be filled, ignoring all
         * stroke-related settings in the paint.
         */
        FILL            (0),
        /**
         * Geometry and text drawn with this style will be stroked, respecting
         * the stroke-related fields on the paint.
         */
        STROKE          (1),
        /**
         * Geometry and text drawn with this style will be both filled and
         * stroked at the same time, respecting the stroke-related fields on
         * the paint.
         */
        FILL_AND_STROKE (2);
        
        Style(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * The Cap specifies the treatment for the beginning and ending of
     * stroked lines and paths. The default is BUTT.
     */
    public enum Cap {
        /**
         * The stroke ends with the path, and does not project beyond it.
         */
        BUTT    (0),
        /**
         * The stroke projects out as a square, with the center at the end
         * of the path.
         */
        ROUND   (1),
        /**
         * The stroke projects out as a semicircle, with the center at the
         * end of the path.
         */
        SQUARE  (2);
        
        private Cap(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * The Join specifies the treatment where lines and curve segments
     * join on a stroked path. The default is MITER.
     */
    public enum Join {
        /**
         * The outer edges of a join meet at a sharp angle
         */
        MITER   (0),
        /**
         * The outer edges of a join meet in a circular arc.
         */
        ROUND   (1),
        /**
         * The outer edges of a join meet with a straight line
         */
        BEVEL   (2);
        
        private Join(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * Align specifies how drawText aligns its text relative to the
     * [x,y] coordinates. The default is LEFT.
     */
    public enum Align {
        /**
         * The text is drawn to the right of the x,y origin
         */
        LEFT    (0),
        /**
         * The text is drawn centered horizontally on the x,y origin
         */
        CENTER  (1),
        /**
         * The text is drawn to the left of the x,y origin
         */
        RIGHT   (2);
        
        private Align(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    public Paint() {
        this(0);
    }

    public Paint(int flags) {
        setFlags(flags | DEFAULT_PAINT_FLAGS);
        initFont();
    }

    public Paint(Paint paint) {
        set(paint);
        initFont();
    }
    
    @Override
    public void finalize() throws Throwable {
        // pass
    }
    
    /**
     * Returns the {@link Font} object.
     */
    public Font getFont() {
        return mFont;
    }
    
    private void initFont() {
        mTypeface = Typeface.DEFAULT;
        updateFontObject();
    }
    
    /**
     * Update the {@link Font} object from the typeface, text size and scaling
     */
    private void updateFontObject() {
        if (mTypeface != null) {
            // get the typeface font object, and get our font object from it, based on the current size
            mFont = mTypeface.getFont().deriveFont(mTextSize);
            if (mScaleX != 1.0 || mSkewX != 0) {
                // TODO: support skew
                mFont = mFont.deriveFont(new AffineTransform(
                        mScaleX, mSkewX, 0, 0, 1, 0));
            }
            
            mMetrics = Toolkit.getDefaultToolkit().getFontMetrics(mFont);
        }
    }
    
    //----------------------------------------
    
    public void set(Paint src) {
        if (this != src) {
            mColor = src.mColor;
            mTextSize = src.mTextSize;
            mScaleX = src.mScaleX;
            mSkewX = src.mSkewX;
            mAlign = src.mAlign;
            mStyle = src.mStyle;
            mFlags = src.mFlags;

            super.set(src);
        }
    }

    @Override
    public int getFlags() {
        return mFlags;
    }

    @Override
    public void setFlags(int flags) {
        mFlags = flags;
    }
    
    /**
     * Return the font's recommended interline spacing, given the Paint's
     * settings for typeface, textSize, etc. If metrics is not null, return the
     * fontmetric values in it.
     *
     * @param metrics If this object is not null, its fields are filled with
     *                the appropriate values given the paint's text attributes.
     * @return the font's recommended interline spacing.
     */
    public float getFontMetrics(FontMetrics metrics) {
        if (mMetrics != null) {
            if (metrics != null) {
                // ascent stuff should be negatif, but awt returns them as positive.
                metrics.top = - mMetrics.getMaxAscent();
                metrics.ascent = - mMetrics.getAscent();
                metrics.descent = mMetrics.getDescent();
                metrics.bottom = mMetrics.getMaxDescent();
                metrics.leading = mMetrics.getLeading();
            }
    
            return mMetrics.getHeight();
        }
        
        return 0;
    }

    public int getFontMetricsInt(FontMetricsInt metrics) {
        if (mMetrics != null) {
            if (metrics != null) {
                // ascent stuff should be negatif, but awt returns them as positive.
                metrics.top = - mMetrics.getMaxAscent();
                metrics.ascent = - mMetrics.getAscent();
                metrics.descent = mMetrics.getDescent();
                metrics.bottom = mMetrics.getMaxDescent();
                metrics.leading = mMetrics.getLeading();
            }
    
            return mMetrics.getHeight();
        }
        
        return 0;
    }
    
    /**
     * Reimplemented to return Paint.FontMetrics instead of _Original_Paint.FontMetrics
     */
    public FontMetrics getFontMetrics() {
        FontMetrics fm = new FontMetrics();
        getFontMetrics(fm);
        return fm;
    }
    
    /**
     * Reimplemented to return Paint.FontMetricsInt instead of _Original_Paint.FontMetricsInt
     */
    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt fm = new FontMetricsInt();
        getFontMetricsInt(fm);
        return fm;
    }



    @Override
    public float getFontMetrics(_Original_Paint.FontMetrics metrics) {
        // TODO implement if needed
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    @Override
    public int getFontMetricsInt(_Original_Paint.FontMetricsInt metrics) {
        // TODO implement if needed
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }
    
    @Override
    public Typeface setTypeface(Typeface typeface) {
        if (typeface != null) {
            mTypeface = typeface;
        } else {
            mTypeface = Typeface.DEFAULT;
        }
        
        updateFontObject();

        return typeface;
    }
    
    @Override
    public int getColor() {
        return mColor;
    }

    @Override
    public void setColor(int color) {
        mColor = color;
    }


    @Override
    public void setAlpha(int alpha) {
        mColor = (alpha << 24) | (mColor & 0x00FFFFFF);
    }
    
    @Override
    public int getAlpha() {
        return mColor >>> 24;
    }
    
    /**
     * Set or clear the shader object.
     * <p />
     * Pass null to clear any previous shader.
     * As a convenience, the parameter passed is also returned.
     *
     * @param shader May be null. the new shader to be installed in the paint
     * @return       shader
     */
    @Override
    public Shader setShader(Shader shader) {
        return mShader = shader;
    }

    /**
     * Set or clear the paint's colorfilter, returning the parameter.
     *
     * @param filter May be null. The new filter to be installed in the paint
     * @return       filter
     */
    @Override
    public ColorFilter setColorFilter(ColorFilter filter) {
        int filterNative = 0;
        if (filter != null)
            filterNative = filter.native_instance;
        mColorFilter = filter;
        return filter;
    }

    /**
     * Set or clear the xfermode object.
     * <p />
     * Pass null to clear any previous xfermode.
     * As a convenience, the parameter passed is also returned.
     *
     * @param xfermode May be null. The xfermode to be installed in the paint
     * @return         xfermode
     */
    @Override
    public Xfermode setXfermode(Xfermode xfermode) {
        return mXfermode = xfermode;
    }
    
    public void setTextAlign(Align align) {
        mAlign = align;
    }
    
    @Override
    public void setTextAlign(android.graphics._Original_Paint.Align align) {
        // TODO implement if needed
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }
    
    public Align getTextAlign() {
        return mAlign;
    }
    
    public void setStyle(Style style) {
        mStyle = style;
    }

    @Override
    public void setStyle(android.graphics._Original_Paint.Style style) {
        // TODO implement if needed
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    public Style getStyle() {
        return mStyle;
    }
    
    @Override
    public void setDither(boolean dither) {
        mFlags |= dither ? DITHER_FLAG : ~DITHER_FLAG;
    }
    
    @Override
    public void setAntiAlias(boolean aa) {
        mFlags |= aa ? ANTI_ALIAS_FLAG : ~ANTI_ALIAS_FLAG;
    }
    
    @Override
    public void setFakeBoldText(boolean flag) {
        mFlags |= flag ? FAKE_BOLD_TEXT_FLAG : ~FAKE_BOLD_TEXT_FLAG;
    }

    /**
     * Return the paint's text size.
     *
     * @return the paint's text size.
     */
    @Override
    public float getTextSize() {
        return mTextSize;
    }

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param textSize set the paint's text size.
     */
    @Override
    public void setTextSize(float textSize) {
        mTextSize = textSize;
        
        updateFontObject();
    }

    /**
     * Return the paint's horizontal scale factor for text. The default value
     * is 1.0.
     *
     * @return the paint's scale factor in X for drawing/measuring text
     */
    @Override
    public float getTextScaleX() {
        return mScaleX;
    }

    /**
     * Set the paint's horizontal scale factor for text. The default value
     * is 1.0. Values > 1.0 will stretch the text wider. Values < 1.0 will
     * stretch the text narrower.
     *
     * @param scaleX set the paint's scale in X for drawing/measuring text.
     */
    @Override
    public void setTextScaleX(float scaleX) {
        mScaleX = scaleX;
        
        updateFontObject();
    }

    /**
     * Return the paint's horizontal skew factor for text. The default value
     * is 0.
     *
     * @return         the paint's skew factor in X for drawing text.
     */
    @Override
    public float getTextSkewX() {
        return mSkewX;
    }

    /**
     * Set the paint's horizontal skew factor for text. The default value
     * is 0. For approximating oblique text, use values around -0.25.
     *
     * @param skewX set the paint's skew factor in X for drawing text.
     */
    @Override
    public void setTextSkewX(float skewX) {
        mSkewX = skewX;
        
        updateFontObject();
    }

    /**
     * Return the distance above (negative) the baseline (ascent) based on the
     * current typeface and text size.
     *
     * @return the distance above (negative) the baseline (ascent) based on the
     *         current typeface and text size.
     */
    @Override
    public float ascent() {
        if (mMetrics != null) {
            // ascent stuff should be negatif, but awt returns them as positive.
            return - mMetrics.getAscent();
        }
        
        return 0;
    }

    /**
     * Return the distance below (positive) the baseline (descent) based on the
     * current typeface and text size.
     *
     * @return the distance below (positive) the baseline (descent) based on
     *         the current typeface and text size.
     */
    @Override
    public float descent() {
        if (mMetrics != null) {
            return mMetrics.getDescent();
        }
        
        return 0;
    }
    
    /**
     * Return the width of the text.
     *
     * @param text  The text to measure
     * @param index The index of the first character to start measuring
     * @param count THe number of characters to measure, beginning with start
     * @return      The width of the text
     */
    @Override
    public float measureText(char[] text, int index, int count) {
        if (mFont != null && text != null && text.length > 0) {
            Rectangle2D bounds = mFont.getStringBounds(text, index, index + count, mFontContext);
            
            return (float)bounds.getWidth();
        }
        
        return 0;
    }

    /**
     * Return the width of the text.
     *
     * @param text  The text to measure
     * @param start The index of the first character to start measuring
     * @param end   1 beyond the index of the last character to measure
     * @return      The width of the text
     */
    @Override
    public float measureText(String text, int start, int end) {
        return measureText(text.toCharArray(), start, end - start);
    }

    /**
     * Return the width of the text.
     *
     * @param text  The text to measure
     * @return      The width of the text
     */
    @Override
    public float measureText(String text) {
        return measureText(text.toCharArray(), 0, text.length());
    }
    
    /*
     * re-implement to call SpannableStringBuilder.measureText with a Paint object
     * instead of an _Original_Paint
     */
    @Override
    public float measureText(CharSequence text, int start, int end) {
        if (text instanceof String) {
            return measureText((String)text, start, end);
        }
        if (text instanceof SpannedString ||
            text instanceof SpannableString) {
            return measureText(text.toString(), start, end);
        }
        if (text instanceof SpannableStringBuilder) {
            return ((SpannableStringBuilder)text).measureText(start, end, this);
        }

        char[] buf = TemporaryBuffer.obtain(end - start);
        TextUtils.getChars(text, start, end, buf, 0);
        float result = measureText(buf, 0, end - start);
        TemporaryBuffer.recycle(buf);
        return result;
    }
    
    /**
     * Measure the text, stopping early if the measured width exceeds maxWidth.
     * Return the number of chars that were measured, and if measuredWidth is
     * not null, return in it the actual width measured.
     *
     * @param text  The text to measure
     * @param index The offset into text to begin measuring at
     * @param count The number of maximum number of entries to measure. If count
     *              is negative, then the characters before index are measured
     *              in reverse order. This allows for measuring the end of
     *              string.
     * @param maxWidth The maximum width to accumulate.
     * @param measuredWidth Optional. If not null, returns the actual width
     *                     measured.
     * @return The number of chars that were measured. Will always be <=
     *         abs(count).
     */
    @Override
    public int breakText(char[] text, int index, int count,
                                float maxWidth, float[] measuredWidth) {
        int inc = count > 0 ? 1 : -1;
        
        int measureIndex = 0;
        float measureAcc = 0;
        for (int i = index ; i != index + count ; i += inc, measureIndex++) {
            int start, end;
            if (i < index) {
                start = i;
                end = index;
            } else {
                start = index;
                end = i;
            }
            
            // measure from start to end
            float res = measureText(text, start, end - start + 1);
            
            if (measuredWidth != null) {
                measuredWidth[measureIndex] = res;
            }
            
            measureAcc += res;
            if (res > maxWidth) {
                // we should not return this char index, but since it's 0-based and we need
                // to return a count, we simply return measureIndex;
                return measureIndex;
            }
            
        }
        
        return measureIndex;
    }

    /**
     * Measure the text, stopping early if the measured width exceeds maxWidth.
     * Return the number of chars that were measured, and if measuredWidth is
     * not null, return in it the actual width measured.
     *
     * @param text  The text to measure
     * @param measureForwards If true, measure forwards, starting at index.
     *                        Otherwise, measure backwards, starting with the
     *                        last character in the string.
     * @param maxWidth The maximum width to accumulate.
     * @param measuredWidth Optional. If not null, returns the actual width
     *                     measured.
     * @return The number of chars that were measured. Will always be <=
     *         abs(count).
     */
    @Override
    public int breakText(String text, boolean measureForwards,
                                float maxWidth, float[] measuredWidth) {
        // NOTE: javadoc doesn't match. Just a guess.
        return breakText(text,
                0 /* start */, text.length() /* end */,
                measureForwards, maxWidth, measuredWidth);
    }

    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text     The text to measure
     * @param index    The index of the first char to to measure
     * @param count    The number of chars starting with index to measure
     * @param widths   array to receive the advance widths of the characters.
     *                 Must be at least a large as count.
     * @return         the actual number of widths returned.
     */
    @Override
    public int getTextWidths(char[] text, int index, int count,
                             float[] widths) {
        if (mMetrics != null) {
            if ((index | count) < 0 || index + count > text.length
                    || count > widths.length) {
                throw new ArrayIndexOutOfBoundsException();
            }
    
            for (int i = 0; i < count; i++) {
                widths[i] = mMetrics.charWidth(text[i + index]);
            }
            
            return count;
        }
        
        return 0;
    }

    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text   The text to measure
     * @param start  The index of the first char to to measure
     * @param end    The end of the text slice to measure
     * @param widths array to receive the advance widths of the characters.
     *               Must be at least a large as the text.
     * @return       the number of unichars in the specified text.
     */
    @Override
    public int getTextWidths(String text, int start, int end, float[] widths) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end - start > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        
        return getTextWidths(text.toCharArray(), start, end - start, widths);
    }
    
    /*
     * re-implement to call SpannableStringBuilder.getTextWidths with a Paint object
     * instead of an _Original_Paint
     */
    @Override
    public int getTextWidths(CharSequence text, int start, int end, float[] widths) {
        if (text instanceof String) {
            return getTextWidths((String)text, start, end, widths);
        }
        if (text instanceof SpannedString || text instanceof SpannableString) {
            return getTextWidths(text.toString(), start, end, widths);
        }
        if (text instanceof SpannableStringBuilder) {
            return ((SpannableStringBuilder)text).getTextWidths(start, end, widths, this);
        }

        char[] buf = TemporaryBuffer.obtain(end - start);
        TextUtils.getChars(text, start, end, buf, 0);
        int result = getTextWidths(buf, 0, end - start, widths);
        TemporaryBuffer.recycle(buf);
        return result;
    }


    /**
     * Return the path (outline) for the specified text.
     * Note: just like Canvas.drawText, this will respect the Align setting in
     * the paint.
     *
     * @param text     The text to retrieve the path from
     * @param index    The index of the first character in text
     * @param count    The number of characterss starting with index
     * @param x        The x coordinate of the text's origin
     * @param y        The y coordinate of the text's origin
     * @param path     The path to receive the data describing the text. Must
     *                 be allocated by the caller.
     */
    @Override
    public void getTextPath(char[] text, int index, int count,
                            float x, float y, Path path) {

        // TODO this is the ORIGINAL implementation. REPLACE AS NEEDED OR REMOVE
        
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        
        // TODO native_getTextPath(mNativePaint, text, index, count, x, y, path.ni());
        
        throw new UnsupportedOperationException("IMPLEMENT AS NEEDED");
    }

    /**
     * Return the path (outline) for the specified text.
     * Note: just like Canvas.drawText, this will respect the Align setting
     * in the paint.
     *
     * @param text  The text to retrieve the path from
     * @param start The first character in the text
     * @param end   1 past the last charcter in the text
     * @param x     The x coordinate of the text's origin
     * @param y     The y coordinate of the text's origin
     * @param path  The path to receive the data describing the text. Must
     *              be allocated by the caller.
     */
    @Override
    public void getTextPath(String text, int start, int end,
                            float x, float y, Path path) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        
        getTextPath(text.toCharArray(), start, end - start, x, y, path);
    }
    
    /**
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * @param text  String to measure and return its bounds
     * @param start Index of the first char in the string to measure
     * @param end   1 past the last char in the string measure
     * @param bounds Returns the unioned bounds of all the text. Must be
     *               allocated by the caller.
     */
    @Override
    public void getTextBounds(String text, int start, int end, Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        
        getTextBounds(text.toCharArray(), start, end - start, bounds);
    }
    
    /**
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * @param text  Array of chars to measure and return their unioned bounds
     * @param index Index of the first char in the array to measure
     * @param count The number of chars, beginning at index, to measure
     * @param bounds Returns the unioned bounds of all the text. Must be
     *               allocated by the caller.
     */
    @Override
    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        if (mFont != null) {
            if ((index | count) < 0 || index + count > text.length) {
                throw new ArrayIndexOutOfBoundsException();
            }
            if (bounds == null) {
                throw new NullPointerException("need bounds Rect");
            }
            
            Rectangle2D rect = mFont.getStringBounds(text, index, index + count, mFontContext);
            bounds.set(0, 0, (int)rect.getWidth(), (int)rect.getHeight());
        }
    }
}
