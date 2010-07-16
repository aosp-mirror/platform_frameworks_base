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

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A paint implementation overridden by the LayoutLib bridge.
 */
public class Paint extends _Original_Paint {

    private int mColor = 0xFFFFFFFF;
    private float mStrokeWidth = 1.f;
    private float mTextSize = 20;
    private float mScaleX = 1;
    private float mSkewX = 0;
    private Align mAlign = Align.LEFT;
    private Style mStyle = Style.FILL;
    private float mStrokeMiter = 4.0f;
    private Cap mCap = Cap.BUTT;
    private Join mJoin = Join.MITER;
    private int mFlags = 0;

    /**
     * Class associating a {@link Font} and it's {@link java.awt.FontMetrics}.
     */
    public static final class FontInfo {
        Font mFont;
        java.awt.FontMetrics mMetrics;
    }

    private List<FontInfo> mFonts;
    private final FontRenderContext mFontContext = new FontRenderContext(
            new AffineTransform(), true, true);

    public static final int ANTI_ALIAS_FLAG       = _Original_Paint.ANTI_ALIAS_FLAG;
    public static final int FILTER_BITMAP_FLAG    = _Original_Paint.FILTER_BITMAP_FLAG;
    public static final int DITHER_FLAG           = _Original_Paint.DITHER_FLAG;
    public static final int UNDERLINE_TEXT_FLAG   = _Original_Paint.UNDERLINE_TEXT_FLAG;
    public static final int STRIKE_THRU_TEXT_FLAG = _Original_Paint.STRIKE_THRU_TEXT_FLAG;
    public static final int FAKE_BOLD_TEXT_FLAG   = _Original_Paint.FAKE_BOLD_TEXT_FLAG;
    public static final int LINEAR_TEXT_FLAG      = _Original_Paint.LINEAR_TEXT_FLAG;
    public static final int SUBPIXEL_TEXT_FLAG    = _Original_Paint.SUBPIXEL_TEXT_FLAG;
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

        /** custom for layoutlib */
        public int getJavaCap() {
            switch (this) {
                case BUTT:
                    return BasicStroke.CAP_BUTT;
                case ROUND:
                    return BasicStroke.CAP_ROUND;
                default:
                case SQUARE:
                    return BasicStroke.CAP_SQUARE;
            }
        }
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

        /** custom for layoutlib */
        public int getJavaJoin() {
            switch (this) {
                default:
                case MITER:
                    return BasicStroke.JOIN_MITER;
                case ROUND:
                    return BasicStroke.JOIN_ROUND;
                case BEVEL:
                    return BasicStroke.JOIN_BEVEL;
            }
        }
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

    /*
     * Do not remove or com.android.layoutlib.bridge.TestClassReplacement fails.
     */
    @Override
    public void finalize() { }

    public Paint(int flags) {
        setFlags(flags | DEFAULT_PAINT_FLAGS);
        initFont();
    }

    public Paint(Paint paint) {
        set(paint);
        initFont();
    }

    @Override
    public void reset() {
        super.reset();
    }

    /**
     * Returns the list of {@link Font} objects. The first item is the main font, the rest
     * are fall backs for characters not present in the main font.
     */
    public List<FontInfo> getFonts() {
        return mFonts;
    }

    private void initFont() {
        mTypeface = Typeface.DEFAULT;
        updateFontObject();
    }

    /**
     * Update the {@link Font} object from the typeface, text size and scaling
     */
    @SuppressWarnings("deprecation")
    private void updateFontObject() {
        if (mTypeface != null) {
            // Get the fonts from the TypeFace object.
            List<Font> fonts = mTypeface.getFonts();

            // create new font objects as well as FontMetrics, based on the current text size
            // and skew info.
            ArrayList<FontInfo> infoList = new ArrayList<FontInfo>(fonts.size());
            for (Font font : fonts) {
                FontInfo info = new FontInfo();
                info.mFont = font.deriveFont(mTextSize);
                if (mScaleX != 1.0 || mSkewX != 0) {
                    // TODO: support skew
                    info.mFont = info.mFont.deriveFont(new AffineTransform(
                            mScaleX, mSkewX, 0, 0, 1, 0));
                }
                info.mMetrics = Toolkit.getDefaultToolkit().getFontMetrics(info.mFont);

                infoList.add(info);
            }

            mFonts = Collections.unmodifiableList(infoList);
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

            updateFontObject();

            super.set(src);
        }
    }

    @Override
    public void setCompatibilityScaling(float factor) {
        super.setCompatibilityScaling(factor);
    }

    @Override
    public int getFlags() {
        return mFlags;
    }

    @Override
    public void setFlags(int flags) {
        mFlags = flags;
    }

    @Override
    public boolean isAntiAlias() {
        return super.isAntiAlias();
    }

    @Override
    public boolean isDither() {
        return super.isDither();
    }

    @Override
    public boolean isLinearText() {
        return super.isLinearText();
    }

    @Override
    public boolean isStrikeThruText() {
        return super.isStrikeThruText();
    }

    @Override
    public boolean isUnderlineText() {
        return super.isUnderlineText();
    }

    @Override
    public boolean isFakeBoldText() {
        return super.isFakeBoldText();
    }

    @Override
    public boolean isSubpixelText() {
        return super.isSubpixelText();
    }

    @Override
    public boolean isFilterBitmap() {
        return super.isFilterBitmap();
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
        if (mFonts.size() > 0) {
            java.awt.FontMetrics javaMetrics = mFonts.get(0).mMetrics;
            if (metrics != null) {
                // Android expects negative ascent so we invert the value from Java.
                metrics.top = - javaMetrics.getMaxAscent();
                metrics.ascent = - javaMetrics.getAscent();
                metrics.descent = javaMetrics.getDescent();
                metrics.bottom = javaMetrics.getMaxDescent();
                metrics.leading = javaMetrics.getLeading();
            }

            return javaMetrics.getHeight();
        }

        return 0;
    }

    public int getFontMetricsInt(FontMetricsInt metrics) {
        if (mFonts.size() > 0) {
            java.awt.FontMetrics javaMetrics = mFonts.get(0).mMetrics;
            if (metrics != null) {
                // Android expects negative ascent so we invert the value from Java.
                metrics.top = - javaMetrics.getMaxAscent();
                metrics.ascent = - javaMetrics.getAscent();
                metrics.descent = javaMetrics.getDescent();
                metrics.bottom = javaMetrics.getMaxDescent();
                metrics.leading = javaMetrics.getLeading();
            }

            return javaMetrics.getHeight();
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
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    @Override
    public int getFontMetricsInt(_Original_Paint.FontMetricsInt metrics) {
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
    public Typeface getTypeface() {
        return super.getTypeface();
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
    public void setARGB(int a, int r, int g, int b) {
        super.setARGB(a, r, g, b);
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

    @Override
    public Shader getShader() {
        return super.getShader();
    }

    /**
     * Set or clear the paint's colorfilter, returning the parameter.
     *
     * @param filter May be null. The new filter to be installed in the paint
     * @return       filter
     */
    @Override
    public ColorFilter setColorFilter(ColorFilter filter) {
        mColorFilter = filter;
        return filter;
    }

    @Override
    public ColorFilter getColorFilter() {
        return super.getColorFilter();
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

    @Override
    public Xfermode getXfermode() {
        return super.getXfermode();
    }

    @Override
    public Rasterizer setRasterizer(Rasterizer rasterizer) {
        mRasterizer = rasterizer;
        return rasterizer;
    }

    @Override
    public Rasterizer getRasterizer() {
        return super.getRasterizer();
    }

    @Override
    public void setShadowLayer(float radius, float dx, float dy, int color) {
        // TODO Auto-generated method stub
    }

    @Override
    public void clearShadowLayer() {
        super.clearShadowLayer();
    }

    public void setTextAlign(Align align) {
        mAlign = align;
    }

    @Override
    public void setTextAlign(android.graphics._Original_Paint.Align align) {
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

    @Override
    public void setLinearText(boolean flag) {
        mFlags |= flag ? LINEAR_TEXT_FLAG : ~LINEAR_TEXT_FLAG;
    }

    @Override
    public void setSubpixelText(boolean flag) {
        mFlags |= flag ? SUBPIXEL_TEXT_FLAG : ~SUBPIXEL_TEXT_FLAG;
    }

    @Override
    public void setUnderlineText(boolean flag) {
        mFlags |= flag ? UNDERLINE_TEXT_FLAG : ~UNDERLINE_TEXT_FLAG;
    }

    @Override
    public void setStrikeThruText(boolean flag) {
        mFlags |= flag ? STRIKE_THRU_TEXT_FLAG : ~STRIKE_THRU_TEXT_FLAG;
    }

    @Override
    public void setFilterBitmap(boolean flag) {
        mFlags |= flag ? FILTER_BITMAP_FLAG : ~FILTER_BITMAP_FLAG;
    }

    @Override
    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    @Override
    public void setStrokeWidth(float width) {
        mStrokeWidth = width;
    }

    @Override
    public float getStrokeMiter() {
        return mStrokeMiter;
    }

    @Override
    public void setStrokeMiter(float miter) {
        mStrokeMiter = miter;
    }

    @Override
    public void setStrokeCap(android.graphics._Original_Paint.Cap cap) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    public void setStrokeCap(Cap cap) {
        mCap = cap;
    }

    public Cap getStrokeCap() {
        return mCap;
    }

    @Override
    public void setStrokeJoin(android.graphics._Original_Paint.Join join) {
        throw new UnsupportedOperationException("CALL TO PARENT FORBIDDEN");
    }

    public void setStrokeJoin(Join join) {
        mJoin = join;
    }

    public Join getStrokeJoin() {
        return mJoin;
    }

    @Override
    public boolean getFillPath(Path src, Path dst) {
        return super.getFillPath(src, dst);
    }

    @Override
    public PathEffect setPathEffect(PathEffect effect) {
        mPathEffect = effect;
        return effect;
    }

    @Override
    public PathEffect getPathEffect() {
        return super.getPathEffect();
    }

    @Override
    public MaskFilter setMaskFilter(MaskFilter maskfilter) {
        mMaskFilter = maskfilter;
        return maskfilter;
    }

    @Override
    public MaskFilter getMaskFilter() {
        return super.getMaskFilter();
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

    @Override
    public float getFontSpacing() {
        return super.getFontSpacing();
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
        if (mFonts.size() > 0) {
            java.awt.FontMetrics javaMetrics = mFonts.get(0).mMetrics;
            // Android expects negative ascent so we invert the value from Java.
            return - javaMetrics.getAscent();
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
        if (mFonts.size() > 0) {
            java.awt.FontMetrics javaMetrics = mFonts.get(0).mMetrics;
            return javaMetrics.getDescent();
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
        // WARNING: the logic in this method is similar to Canvas.drawText.
        // Any change to this method should be reflected in Canvas.drawText
        if (mFonts.size() > 0) {
            FontInfo mainFont = mFonts.get(0);
            int i = index;
            int lastIndex = index + count;
            float total = 0f;
            while (i < lastIndex) {
                // always start with the main font.
                int upTo = mainFont.mFont.canDisplayUpTo(text, i, lastIndex);
                if (upTo == -1) {
                    // shortcut to exit
                    return total + mainFont.mMetrics.charsWidth(text, i, lastIndex - i);
                } else if (upTo > 0) {
                    total += mainFont.mMetrics.charsWidth(text, i, upTo - i);
                    i = upTo;
                    // don't call continue at this point. Since it is certain the main font
                    // cannot display the font a index upTo (now ==i), we move on to the
                    // fallback fonts directly.
                }

                // no char supported, attempt to read the next char(s) with the
                // fallback font. In this case we only test the first character
                // and then go back to test with the main font.
                // Special test for 2-char characters.
                boolean foundFont = false;
                for (int f = 1 ; f < mFonts.size() ; f++) {
                    FontInfo fontInfo = mFonts.get(f);

                    // need to check that the font can display the character. We test
                    // differently if the char is a high surrogate.
                    int charCount = Character.isHighSurrogate(text[i]) ? 2 : 1;
                    upTo = fontInfo.mFont.canDisplayUpTo(text, i, i + charCount);
                    if (upTo == -1) {
                        total += fontInfo.mMetrics.charsWidth(text, i, charCount);
                        i += charCount;
                        foundFont = true;
                        break;

                    }
                }

                // in case no font can display the char, measure it with the main font.
                if (foundFont == false) {
                    int size = Character.isHighSurrogate(text[i]) ? 2 : 1;
                    total += mainFont.mMetrics.charsWidth(text, i, size);
                    i += size;
                }
            }
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
        return breakText(text,
                0 /* start */, text.length() /* end */,
                measureForwards, maxWidth, measuredWidth);
    }

    /**
     * Measure the text, stopping early if the measured width exceeds maxWidth.
     * Return the number of chars that were measured, and if measuredWidth is
     * not null, return in it the actual width measured.
     *
     * @param text  The text to measure
     * @param start The offset into text to begin measuring at
     * @param end   The end of the text slice to measure.
     * @param measureForwards If true, measure forwards, starting at start.
     *                        Otherwise, measure backwards, starting with end.
     * @param maxWidth The maximum width to accumulate.
     * @param measuredWidth Optional. If not null, returns the actual width
     *                     measured.
     * @return The number of chars that were measured. Will always be <=
     *         abs(end - start).
     */
    @Override
    public int breakText(CharSequence text, int start, int end, boolean measureForwards,
            float maxWidth, float[] measuredWidth) {
        char[] buf = new char[end - start];
        int result;

        TextUtils.getChars(text, start, end, buf, 0);

        if (measureForwards) {
            result = breakText(buf, 0, end - start, maxWidth, measuredWidth);
        } else {
            result = breakText(buf, 0, -(end - start), maxWidth, measuredWidth);
        }

        return result;
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
        if (mFonts.size() > 0) {
            if ((index | count) < 0 || index + count > text.length
                    || count > widths.length) {
                throw new ArrayIndexOutOfBoundsException();
            }

            // FIXME: handle multi-char characters.
            // Need to figure out if the lengths of the width array takes into account
            // multi-char characters.
            for (int i = 0; i < count; i++) {
                char c = text[i + index];
                boolean found = false;
                for (FontInfo info : mFonts) {
                    if (info.mFont.canDisplay(c)) {
                        widths[i] = info.mMetrics.charWidth(c);
                        found = true;
                        break;
                    }
                }

                if (found == false) {
                    // we stop there.
                    return i;
                }
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

    @Override
    public int getTextWidths(String text, float[] widths) {
        return super.getTextWidths(text, widths);
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
        // FIXME
        if (mFonts.size() > 0) {
            if ((index | count) < 0 || index + count > text.length) {
                throw new ArrayIndexOutOfBoundsException();
            }
            if (bounds == null) {
                throw new NullPointerException("need bounds Rect");
            }

            FontInfo mainInfo = mFonts.get(0);

            Rectangle2D rect = mainInfo.mFont.getStringBounds(text, index, index + count, mFontContext);
            bounds.set(0, 0, (int)rect.getWidth(), (int)rect.getHeight());
        }
    }

    public static void finalizer(int foo) {
        // pass
    }
}
